package io.github.ghosthack.harnessstimulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The monitor/stimulation loop with the rails from spec.txt: confidence gating,
 * max repeats with backoff, escalation after stalled stimuli, approval/unrecognized
 * states are notify-only, and everything is audit-logged.
 *
 * <p>There is deliberately no completion signal. Done detection is impossible from
 * here — an agent that knew it was finished would not need babysitting in the first
 * place — so the only stop conditions are the budget, escalation, and the human.
 *
 * <p>Loop mode ({@link StrategyConfig.OnIdle#RESET}) changes what an idle harness
 * receives, not when a run stops: each idle starts an iteration that wipes the
 * harness context with its reset command, waits for the screen to classify idle
 * again, and then types the instruction — two separately gated sends, never one
 * blind chain through the slash-menu and redraw states in between.
 */
public final class Stimulator {

    public enum Outcome {
        CONTINUE,
        STOPPED_BROKEN,
        STOPPED_ESCALATED,
        STOPPED_MAX_STIMULI,
        DETACHED_BY_USER
    }

    /** Side-channel commands: stop = observe-only, resume = re-enable, detach = exit, steal = hold for human. */
    public enum Command { STOP, RESUME, DETACH, STEAL }

    /**
     * Steal handover: WAITING_FOR_HUMAN bridges the gap between the steal ack and
     * the human's tmux attach (without it, the zero-clients instant right after the
     * ack would read as an immediate release); HUMAN_ATTACHED waits for their
     * read-write client count to drop back to zero — releasing is just ctrl-b d.
     */
    private enum HoldPhase { NONE, WAITING_FOR_HUMAN, HUMAN_ATTACHED }

    private static final Duration HOLD_WAIT_FOR_HUMAN = Duration.ofSeconds(60);

    /**
     * Out-of-band control channel, e.g. a control file written by the stop/resume/
     * detach CLI commands. File-based on purpose: while attached the tmux session
     * is dedicated to the stimulator and the human's channel is read-only peek, so
     * control needs a side channel that works from any terminal. {@code poll()}
     * consumes and returns the pending command, or null when there is none.
     */
    public interface ControlChannel {
        Command poll();

        ControlChannel NONE = () -> null;
    }

    /** Real waits between probe keystrokes; no-op in tests. */
    public interface Pauser {
        void pause(long millis);

        Pauser REAL = millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /** Live stimuli-used/budget snapshot for an external reader (e.g. a session
     * manager's sidebar badge) that has no other way to see this run's progress toward
     * its budget. NONE for tests and any caller with nothing listening. */
    public interface StatusSink {
        void update(int stimuliSent, int maxStimuli);

        StatusSink NONE = (sent, max) -> { };
    }

    /** Rarely bound as a dialog hotkey, unlike letters or y/n. */
    static final String PROBE_CHAR = ",";
    private static final long PROBE_SETTLE_MILLIS = 500;

    private final String session;
    private final Tmux tmux;
    private final ControlChannel control;
    private final StateDetector detector;
    private final StrategyConfig cfg;
    // the harness's /clear-equivalent; non-null exactly when cfg.onIdle() is RESET
    private final String contextReset;
    private final AuditLog audit;
    private final Notifier notifier;
    private final Clock clock;
    private final Pauser pauser;
    private final StatusSink statusSink;

    private int stimuliSent;
    private int consecutiveStalls;
    private Instant lastStimulusAt;
    private Duration currentBackoff;
    private boolean sawWorkingSinceLastStimulus;
    private boolean questionAnswered;
    private HarnessState lastState;
    private boolean notifiedForEpisode;
    // observe-only mode: monitoring, alerts, and audit continue; no keystrokes
    private boolean stimulationEnabled = true;
    private HoldPhase hold = HoldPhase.NONE;
    private Instant holdDeadline;
    // Sticky: once the echo probe exposes an unrecognized interactive element,
    // fallback stimulation stays off until a plugin pattern recognizes the screen
    // again — re-probing a dialog the first probe already perturbed can pass falsely.
    private boolean fallbackProbeVetoed;
    // one alert per outage, not one per retry
    private boolean sendFailureNotified;
    // loop mode, mid-iteration: the reset landed, the instruction has not yet
    private boolean awaitingLoopInstruction;
    // armed by a *delivered* stimulus, consumed by exactly one stall/progress
    // evaluation: a tick whose send failed must not count as a stalled stimulus,
    // or a tmux send outage (`tmux attach -r`) escalates and ends the very run
    // sendFailed promises to keep alive
    private boolean stallCheckPending;
    // a stop request that arrived during a hold is honored at release, not dropped
    private boolean stopAfterHold;

    public Stimulator(String session, Tmux tmux, StateDetector detector, StrategyConfig cfg,
                      java.util.Optional<String> contextReset, AuditLog audit, Notifier notifier,
                      Clock clock, Pauser pauser, ControlChannel control, StatusSink statusSink) {
        this.session = session;
        this.tmux = tmux;
        this.pauser = pauser;
        this.control = control;
        this.statusSink = statusSink;
        this.detector = detector;
        this.cfg = cfg;
        if (cfg.onIdle() == StrategyConfig.OnIdle.RESET) {
            this.contextReset = contextReset.orElseThrow(() -> new IllegalArgumentException(
                    "on-idle reset needs the harness's context-reset command, and this harness has none"));
        } else {
            this.contextReset = null;
        }
        this.audit = audit;
        this.notifier = notifier;
        this.clock = clock;
        this.currentBackoff = cfg.initialBackoff();
    }

    /** Runs until a stop condition fires or the thread is interrupted. */
    public Outcome run() throws InterruptedException {
        while (true) {
            Outcome outcome = tick();
            if (outcome != Outcome.CONTINUE) {
                return outcome;
            }
            Thread.sleep(cfg.pollInterval());
        }
    }

    /** One poll cycle; extracted from {@link #run()} so tests can drive time explicitly. */
    public Outcome tick() {
        Screen screen = tmux.capture(session);
        Classification c = detector.classify(screen);

        // explicit human intent wins over everything, including an imminent stimulus
        Command cmd = control.poll();
        if (cmd == Command.DETACH) {
            audit.record(clock.instant(), "control", c, screen, "detach requested by user");
            notifier.alert("detach requested — ending attachment for session " + session);
            return Outcome.DETACHED_BY_USER;
        }
        if (cmd == Command.STOP && (stimulationEnabled || (hold != HoldPhase.NONE && !stopAfterHold))) {
            stimulationEnabled = false;
            if (hold != HoldPhase.NONE) {
                // during a hold stimulation is already off, but the request must not be
                // silently swallowed: the stop CLI just promised the user it would stick
                stopAfterHold = true;
                audit.record(clock.instant(), "control", c, screen,
                        "stop requested during hold — will stay observe-only after release");
                notifier.alert("stop noted — staying observe-only after the human releases the session;"
                        + " resume with `resume " + session + "`");
            } else {
                audit.record(clock.instant(), "control", c, screen, "stop requested — observe-only mode");
                notifier.alert("stimulation stopped — observing only (monitoring and alerts continue);"
                        + " resume with `resume " + session + "`, end with `detach " + session + "`");
            }
        } else if (cmd == Command.RESUME && !stimulationEnabled) {
            stopAfterHold = false;
            if (hold != HoldPhase.NONE) {
                // ending a hold this way must run the same cleanup as a ctrl-b-d release:
                // without it a half-done loop iteration's stale instruction would be typed
                // into whatever conversation the human just established
                resumeFromHold(c, screen, "resume requested during hold — ending the hold");
            } else {
                stimulationEnabled = true;
                audit.record(clock.instant(), "control", c, screen, "resume requested — stimulation re-enabled");
                notifier.alert("stimulation resumed for session " + session);
            }
        } else if (cmd == Command.STEAL) {
            // a stop already in effect survives the hold: whether the user's stop
            // sticks must not depend on whether it happened to race the steal
            stopAfterHold = stopAfterHold || (hold == HoldPhase.NONE && !stimulationEnabled);
            stimulationEnabled = false;
            hold = HoldPhase.WAITING_FOR_HUMAN;
            holdDeadline = clock.instant().plus(HOLD_WAIT_FOR_HUMAN);
            audit.record(clock.instant(), "control", c, screen, "steal — holding for human takeover");
            notifier.alert("session handed to human — holding (no keystrokes);"
                    + (stopAfterHold
                            ? " a stop is pending, so it stays observe-only after they detach (ctrl-b d)"
                            : " resumes automatically when they detach (ctrl-b d)"));
        }

        if (hold != HoldPhase.NONE) {
            return holdTick(c, screen);
        }

        if (c.state() != lastState) {
            notifiedForEpisode = false;
            lastState = c.state();
        }
        if (c.origin() == Classification.Origin.PLUGIN) {
            fallbackProbeVetoed = false;
        }
        audit.record(clock.instant(), "state", c, screen, "");

        return switch (c.state()) {
            case WORKING -> {
                sawWorkingSinceLastStimulus = true;
                questionAnswered = false;
                yield Outcome.CONTINUE;
            }
            case BROKEN -> {
                notifier.alert("harness broken (" + c.reason() + "), stopping. session=" + session);
                audit.record(clock.instant(), "stop", c, screen, "broken");
                yield Outcome.STOPPED_BROKEN;
            }
            case WAITING_FOR_APPROVAL -> {
                notifyOnce(c, screen, "approval prompt on screen — never auto-answered, human needed");
                yield Outcome.CONTINUE;
            }
            case UNRECOGNIZED_PROMPT -> {
                notifyOnce(c, screen, "unrecognized quiescent prompt — doing nothing");
                yield Outcome.CONTINUE;
            }
            case WAITING_FOR_QUESTION -> {
                handleQuestion(c, screen);
                yield Outcome.CONTINUE;
            }
            case IDLE -> stimulationEnabled ? handleIdle(c, screen) : Outcome.CONTINUE;
        };
    }

    /** While the human holds the session: monitor and audit only; broken still stops. */
    private Outcome holdTick(Classification c, Screen screen) {
        audit.record(clock.instant(), "state", c, screen, "holding for human");
        if (c.state() == HarnessState.BROKEN) {
            notifier.alert("harness broken during human takeover (" + c.reason() + "), stopping. session=" + session);
            audit.record(clock.instant(), "stop", c, screen, "broken during hold");
            return Outcome.STOPPED_BROKEN;
        }
        Instant now = clock.instant();
        int rwClients = tmux.attachedReadWriteClients(session);
        if (hold == HoldPhase.WAITING_FOR_HUMAN) {
            if (rwClients > 0) {
                hold = HoldPhase.HUMAN_ATTACHED;
            } else if (!now.isBefore(holdDeadline)) {
                resumeFromHold(c, screen, "no human attached after steal — resuming stimulation");
            }
        } else if (rwClients == 0) {
            resumeFromHold(c, screen, "human released the session — re-classifying and resuming stimulation");
        }
        return Outcome.CONTINUE;
    }

    private void resumeFromHold(Classification c, Screen screen, String message) {
        hold = HoldPhase.NONE;
        stimulationEnabled = !stopAfterHold;
        if (stopAfterHold) {
            stopAfterHold = false;
            message = "hold released with a pending stop — observing only"
                    + " (resume with `resume " + session + "`)";
        }
        // the human may have typed anything: drop assumptions tied to the pre-steal
        // screen; a half-done loop iteration restarts from its reset, and a stall
        // evaluation still pending from before the steal is dropped too — the hold
        // was a blind window (holdTick never records WORKING), so counting it as
        // "the harness ignored the stimulus" would escalate right after a handover
        if (awaitingLoopInstruction) {
            // the aborted iteration's reset redraw is quarantined progress; the wipe
            // that would have cleared it (instruction delivery) will never run now,
            // and letting it leak into the next evaluation would erase accrued stalls
            sawWorkingSinceLastStimulus = false;
        }
        fallbackProbeVetoed = false;
        questionAnswered = false;
        notifiedForEpisode = false;
        awaitingLoopInstruction = false;
        stallCheckPending = false;
        audit.record(clock.instant(), "control", c, screen, message);
        notifier.alert(message + " (session=" + session + ")");
    }

    private void handleQuestion(Classification c, Screen screen) {
        if (stimulationEnabled && cfg.questionAnswer().isPresent() && c.textAnswerable()
                && c.confidence() == Confidence.HIGH && !questionAnswered) {
            String answer = cfg.questionAnswer().get();
            try {
                tmux.sendText(session, answer);
            } catch (TmuxException e) {
                sendFailed(c, screen, clock.instant(), "question answer", e);
                return;
            }
            questionAnswered = true;
            sendFailureNotified = false;
            audit.record(clock.instant(), "stimulus", c, screen, "question answer: " + answer);
        } else if (!c.textAnswerable()) {
            // the answer would not land as text: characters navigate the option list and
            // the Enter that follows commits whatever is highlighted, so this is as
            // untouchable as an approval dialog
            notifyOnce(c, screen, "harness asked a question in a selector widget — an answer cannot be"
                    + " typed into it without committing an arbitrary option, human answer needed");
        } else if (!stimulationEnabled || cfg.questionAnswer().isEmpty() || c.confidence() != Confidence.HIGH) {
            notifyOnce(c, screen, "harness asked a question — human answer needed");
        }
    }

    private Outcome handleIdle(Classification c, Screen screen) {
        if (c.confidence() != Confidence.HIGH) {
            notifyOnce(c, screen, "idle-looking but confidence " + c.confidence() + " — not stimulating");
            return Outcome.CONTINUE;
        }
        boolean probeRequired = cfg.probeFallbackIdle() && c.origin() == Classification.Origin.BEHAVIORAL;
        if (probeRequired && fallbackProbeVetoed) {
            return Outcome.CONTINUE;
        }
        Instant now = clock.instant();
        if (awaitingLoopInstruction) {
            return sendLoopInstruction(c, screen, now, probeRequired);
        }
        if (lastStimulusAt != null) {
            if (Duration.between(lastStimulusAt, now).compareTo(currentBackoff) < 0) {
                return Outcome.CONTINUE;
            }
            // progress is credited whenever it is seen — even when the evaluation for
            // this stimulus already ran on a tick whose own send then failed. Only the
            // stall count is limited to one evaluation per *delivered* stimulus (see
            // stallCheckPending), so refused sends can neither escalate the run nor
            // swallow a late reaction to the stimulus that did land.
            if (sawWorkingSinceLastStimulus) {
                consecutiveStalls = 0;
                currentBackoff = cfg.initialBackoff();
                stallCheckPending = false;
            } else if (stallCheckPending) {
                stallCheckPending = false;
                consecutiveStalls++;
                if (consecutiveStalls >= cfg.escalateAfterStalls()) {
                    notifier.alert("no progress after " + consecutiveStalls
                            + " stimuli — escalating to human and stopping. session=" + session);
                    audit.record(now, "stop", c, screen,
                            "escalated after " + consecutiveStalls + " stalls");
                    return Outcome.STOPPED_ESCALATED;
                }
            }
        }
        // maxStimuli 0 = unlimited: nudge until escalation fires or the human detaches
        if (cfg.maxStimuli() > 0 && stimuliSent >= cfg.maxStimuli()) {
            notifier.alert("max stimuli (" + cfg.maxStimuli() + ") reached — stopping. session=" + session);
            audit.record(now, "stop", c, screen, "max stimuli reached");
            return Outcome.STOPPED_MAX_STIMULI;
        }
        if (probeRequired) {
            Outcome held = probeOutcome(c, screen, now);
            if (held != null) {
                return held;
            }
        }
        boolean reset = cfg.onIdle() == StrategyConfig.OnIdle.RESET;
        String stimulus = reset ? contextReset : cfg.idleMessage();
        try {
            tmux.sendText(session, stimulus);
        } catch (TmuxException e) {
            return sendFailed(c, screen, now, reset ? "context reset" : "idle message", e);
        }
        sendFailureNotified = false;
        stimuliSent++;
        statusSink.update(stimuliSent, cfg.maxStimuli());
        lastStimulusAt = now;
        if (reset) {
            awaitingLoopInstruction = true;
            // sawWorkingSinceLastStimulus and stallCheckPending are deliberately left
            // alone here and re-armed when the instruction lands: the reset's own
            // redraw must not read as progress, or the escalation rail would never
            // fire in loop mode
            audit.record(now, "stimulus", c, screen,
                    "context reset #" + stimuliSent + ": " + stimulus);
        } else {
            sawWorkingSinceLastStimulus = false;
            stallCheckPending = true;
            audit.record(now, "stimulus", c, screen,
                    "idle message #" + stimuliSent + ": " + stimulus);
        }
        currentBackoff = Duration.ofMillis((long) (currentBackoff.toMillis() * cfg.backoffMultiplier()));
        return Outcome.CONTINUE;
    }

    /**
     * Second half of a loop iteration: the reset landed and the screen has come back to
     * a verifiably idle composer, so the instruction can be typed. Deliberately not
     * gated on backoff, stalls, or the budget — the iteration was charged when its
     * reset was sent, and stopping between the two would leave a wiped context with no
     * instruction. Classification still gates it like any stimulus: the slash-menu and
     * redraw states between reset and composer are never typed into, and a failed send
     * retries at poll rate exactly as a failed question answer does.
     */
    private Outcome sendLoopInstruction(Classification c, Screen screen, Instant now,
                                        boolean probeRequired) {
        if (probeRequired) {
            Outcome held = probeOutcome(c, screen, now);
            if (held != null) {
                return held;
            }
        }
        try {
            tmux.sendText(session, cfg.idleMessage());
        } catch (TmuxException e) {
            // the iteration stays pending: the reset already landed, so giving up here
            // would leave a wiped context with no instruction
            return sendFailed(c, screen, now, "loop instruction", e);
        }
        sendFailureNotified = false;
        awaitingLoopInstruction = false;
        lastStimulusAt = now;
        sawWorkingSinceLastStimulus = false;
        stallCheckPending = true;
        audit.record(now, "stimulus", c, screen,
                "loop instruction #" + stimuliSent + ": " + cfg.idleMessage());
        return Outcome.CONTINUE;
    }

    /** Fallback-idle echo probe. Null means clear to type; non-null is the outcome to yield. */
    private Outcome probeOutcome(Classification c, Screen screen, Instant now) {
        String failure;
        try {
            failure = runEchoProbe(screen);
        } catch (TmuxException e) {
            // the probe told us nothing about the screen, so this must not set the
            // sticky veto — only that we could not talk to tmux this time round
            return sendFailed(c, screen, now, "echo probe", e);
        }
        if (failure == null) {
            audit.record(now, "probe", c, screen, "echo probe confirmed a real text input");
            return null;
        }
        fallbackProbeVetoed = true;
        audit.record(now, "probe", c, screen, "echo probe failed (" + failure + "), holding");
        notifier.alert("fallback idle failed the echo probe (" + failure + ") — unrecognized"
                + " interactive element, not typing until a human looks or the harness moves on."
                + " session=" + session);
        return Outcome.CONTINUE;
    }

    /**
     * A send failed. This must never end the run: tmux refuses `send-keys` whenever the
     * client it resolves for the command is a read-only attachment, so an operator
     * running `tmux attach -r` against the session is enough to make every stimulus
     * fail — transiently, for as long as they watch. Audit it, tell the human once, and
     * back off before trying again; the stimulus did not land, so it is not counted
     * against the budget.
     */
    private Outcome sendFailed(Classification c, Screen screen, Instant now, String what, TmuxException e) {
        lastStimulusAt = now;
        audit.record(now, "send-failed", c, screen, what + " could not be sent: " + e.getMessage());
        if (!sendFailureNotified) {
            sendFailureNotified = true;
            notifier.alert("could not send to session " + session + " (" + e.getMessage()
                    + ") — not counted against the budget, retrying with backoff."
                    + " A read-only `tmux attach -r` on this session causes exactly this.");
        }
        return Outcome.CONTINUE;
    }

    /**
     * Active idle check: type one probe char (never Enter — Enter is what commits
     * actions in dialogs), require the echo to change exactly one line, undo with
     * Backspace, and require the screen to revert byte-for-byte. A dialog moves
     * selection state or fails to revert; a real text input passes both.
     */
    private String runEchoProbe(Screen before) {
        tmux.sendLiteral(session, PROBE_CHAR);
        pauser.pause(PROBE_SETTLE_MILLIS);
        Screen typed = tmux.capture(session);
        tmux.sendBackspace(session);
        pauser.pause(PROBE_SETTLE_MILLIS);
        Screen after = tmux.capture(session);
        if (!echoedOnExactlyOneLine(before.text(), typed.text())) {
            return "echo mismatch: probe char did not appear as a single-line change";
        }
        if (!after.text().equals(before.text())) {
            return "revert mismatch: screen did not return to its pre-probe state after backspace";
        }
        return null;
    }

    static boolean echoedOnExactlyOneLine(String before, String typed) {
        var b = before.lines().toList();
        var t = typed.lines().toList();
        if (b.size() != t.size()) {
            return false;
        }
        int changed = -1;
        for (int i = 0; i < b.size(); i++) {
            if (!b.get(i).equals(t.get(i))) {
                if (changed >= 0) {
                    return false;
                }
                changed = i;
            }
        }
        return changed >= 0 && t.get(changed).contains(PROBE_CHAR);
    }

    private void notifyOnce(Classification c, Screen screen, String message) {
        if (notifiedForEpisode) {
            return;
        }
        notifiedForEpisode = true;
        notifier.alert(message + " (session=" + session + ", " + c.reason() + ")");
        audit.record(clock.instant(), "notify", c, screen, message);
    }
}

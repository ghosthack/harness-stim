package io.github.ghosthack.harnessstimulator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StimulatorTest {

    private static final String IDLE_MSG = "lets continue with the next item";
    private static final String IDLE_SCREEN = "checked off item 3\n❯ ";
    private static final String RESET_CMD = "/clear";
    private static final String LOOP_INSTRUCTION = "work through TODO.md, next unchecked item";

    @TempDir
    Path tmp;

    private final MutableClock clock = new MutableClock();
    private final FakeTmux tmux = new FakeTmux();
    private final List<String> alerts = new ArrayList<>();

    private Stimulator stimulator;

    private StrategyConfig config(Optional<String> questionAnswer, int maxStimuli, int escalateAfter) {
        return new StrategyConfig("cursor-agent", IDLE_MSG, StrategyConfig.OnIdle.NUDGE, questionAnswer,
                maxStimuli, Duration.ofSeconds(1), 1.0, escalateAfter,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(30), true);
    }

    private StrategyConfig resetConfig(int maxStimuli, int escalateAfter) {
        return new StrategyConfig("cursor-agent", LOOP_INSTRUCTION, StrategyConfig.OnIdle.RESET,
                Optional.empty(), maxStimuli, Duration.ofSeconds(1), 1.0, escalateAfter,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(30), true);
    }

    private final java.util.ArrayDeque<Stimulator.Command> commands = new java.util.ArrayDeque<>();

    private void build(StrategyConfig cfg) {
        build(cfg, Optional.empty());
    }

    private void build(StrategyConfig cfg, Optional<String> contextReset) {
        StateDetector detector = new StateDetector(new CursorAgentPlugin(), clock,
                cfg.shortQuiescence(), cfg.longQuiescence());
        AuditLog audit = new AuditLog(tmp.resolve("audit.jsonl"));
        stimulator = new Stimulator("test", tmux, detector, cfg, contextReset, audit, alerts::add,
                clock, millis -> { }, commands::poll, Stimulator.StatusSink.NONE);
    }

    @BeforeEach
    void setUp() {
        build(config(Optional.empty(), 25, 3));
    }

    @Test
    void stimulatesHighConfidenceIdleAndRespectsBackoff() {
        tmux.screen(IDLE_SCREEN);
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick()); // first sight: WORKING
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick()); // quiescent idle -> stimulate
        assertEquals(List.of(IDLE_MSG), tmux.sent);

        clock.advance(Duration.ofMillis(500)); // inside 1s backoff
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick());
        assertEquals(1, tmux.sent.size());
    }

    @Test
    void statusSinkReceivesStimuliSentAndBudgetAfterEachStimulus() {
        List<int[]> updates = new ArrayList<>();
        StrategyConfig cfg = config(Optional.empty(), 25, 3);
        StateDetector detector = new StateDetector(new CursorAgentPlugin(), clock,
                cfg.shortQuiescence(), cfg.longQuiescence());
        stimulator = new Stimulator("test", tmux, detector, cfg, Optional.empty(),
                new AuditLog(tmp.resolve("audit.jsonl")), alerts::add, clock,
                millis -> { }, commands::poll, (sent, max) -> updates.add(new int[] {sent, max}));

        tmux.screen(IDLE_SCREEN);
        stimulator.tick(); // first sight: WORKING, no stimulus yet
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // quiescent idle -> stimulus #1
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // stimulus #2

        assertEquals(List.of(List.of(1, 25), List.of(2, 25)),
                updates.stream().map(u -> List.of(u[0], u[1])).toList());
    }

    @Test
    void neverStimulatesApprovalAndNotifiesOnce() {
        tmux.screen("doing item 8\nRun this command? [y/n]");
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();
        assertTrue(tmux.sent.isEmpty());
        assertEquals(1, alerts.size());
        assertTrue(alerts.getFirst().contains("approval"));
    }

    @Test
    void theAgentClaimingItIsFinishedIsNotAStopCondition() {
        // done detection is impossible from here: an agent that reliably knew it was
        // finished would not need babysitting. Only the budget, escalation, and the
        // human stop a run — a completion claim on screen is just more screen text.
        tmux.screen("progress written\nALL_DONE\n❯ ");
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick());
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick());
        assertEquals(List.of(IDLE_MSG), tmux.sent, "still idle, so it still gets nudged");
    }

    @Test
    void escalatesAfterConsecutiveStalledStimuli() {
        build(config(Optional.empty(), 25, 2));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // stimulus 1
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // no progress -> stall 1, stimulus 2
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.STOPPED_ESCALATED, stimulator.tick()); // stall 2 -> escalate
        assertEquals(2, tmux.sent.size());
        assertTrue(alerts.getLast().contains("escalating"));
    }

    @Test
    void progressBetweenStimuliResetsStallCounter() {
        build(config(Optional.empty(), 25, 2));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // stimulus 1
        tmux.screen("working on item 4...\noutput streaming");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // screen changed -> WORKING, progress seen
        tmux.screen("checked off item 4\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // change again -> WORKING
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick()); // idle again -> stimulus 2, no escalation
        assertEquals(2, tmux.sent.size());
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick()); // stall 1 only -> stimulus 3
        assertEquals(3, tmux.sent.size());
    }

    @Test
    void backoffResetsToInitialAfterProgress() {
        // backoff 1s, multiplier 4x: without the reset, the third stimulus would
        // need 4s of waiting even though the harness progressed in between
        StrategyConfig cfg = new StrategyConfig("cursor-agent", IDLE_MSG, StrategyConfig.OnIdle.NUDGE,
                Optional.empty(), 25, Duration.ofSeconds(1), 4.0, 5,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(30), true);
        build(cfg);
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // stimulus 1, backoff grows to 4s
        tmux.screen("working on item 4...");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // progress
        tmux.screen("checked off item 4\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // change -> WORKING
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // idle -> stimulus 2, backoff reset to 1s then grows to 4s
        assertEquals(2, tmux.sent.size());
        tmux.screen("working on item 5...");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // progress again
        tmux.screen("checked off item 5\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6)); // > initial 1s, < grown 16s without reset
        stimulator.tick(); // stimulus 3 only possible because backoff reset
        assertEquals(3, tmux.sent.size());
    }

    @Test
    void stopsAtMaxStimuli() {
        build(config(Optional.empty(), 1, 10));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // stimulus 1 (the max)
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.STOPPED_MAX_STIMULI, stimulator.tick());
        assertEquals(1, tmux.sent.size());
    }

    @Test
    void answersQuestionOnceWhenStrategyConfigured() {
        build(config(Optional.of("option A"), 25, 3));
        tmux.screen("Item 14: should notes/item_14.txt contain option A or option B?");
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // question -> answer
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // still same question on screen -> no repeat
        assertEquals(List.of("option A"), tmux.sent);
    }

    @Test
    void notifiesOnQuestionWhenNoAnswerConfigured() {
        tmux.screen("Item 14: should notes/item_14.txt contain option A or option B?");
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();
        assertTrue(tmux.sent.isEmpty());
        assertEquals(1, alerts.size());
        assertTrue(alerts.getFirst().contains("question"));
    }

    @Test
    void unrecognizedPromptNotifiesOnceAndNeverStimulates() {
        // no plugin match, quiescent between short (5s) and long (30s) thresholds
        tmux.screen("some unusual dialog without known shape");
        stimulator.tick();
        clock.advance(Duration.ofSeconds(10));
        stimulator.tick();
        clock.advance(Duration.ofSeconds(5));
        stimulator.tick();
        assertTrue(tmux.sent.isEmpty());
        assertEquals(1, alerts.size());
        assertTrue(alerts.getFirst().contains("unrecognized"));
    }

    @Test
    void zeroMaxStimuliMeansUnlimited() {
        build(config(Optional.empty(), 0, 100));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        for (int i = 0; i < 30; i++) { // well past the old default cap of 25
            clock.advance(Duration.ofSeconds(6));
            tmux.screen(IDLE_SCREEN + "\ncycle " + i); // progress each cycle, no stall
            stimulator.tick(); // WORKING (screen changed)
            clock.advance(Duration.ofSeconds(6));
            assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick());
        }
        assertEquals(30, tmux.sent.size());
    }

    @Test
    void pluginRecognizedIdleIsNeverProbed() {
        tmux.screen(IDLE_SCREEN); // "❯ " input box — plugin idle, not fallback
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();
        assertEquals(List.of(IDLE_MSG), tmux.sent);
        assertTrue(tmux.literals.isEmpty());
        assertEquals(0, tmux.backspaces);
    }

    @Test
    void fallbackIdleStimulatesOnlyAfterEchoProbePasses() {
        String base = "some plain shell output\nuser@host %";
        tmux.screen(base);
        stimulator.tick(); // first sight: WORKING
        clock.advance(Duration.ofSeconds(31)); // beyond long quiescence -> generic fallback idle
        tmux.queueCapture(base);                                   // tick's own capture
        tmux.queueCapture("some plain shell output\nuser@host % ,"); // probe echo: one line changed
        tmux.queueCapture(base);                                   // after backspace: reverted
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick());
        assertEquals(List.of(","), tmux.literals);
        assertEquals(1, tmux.backspaces);
        assertEquals(List.of(IDLE_MSG), tmux.sent);
    }

    @Test
    void failedEchoProbeBlocksFallbackStimulationStickily() {
        String base = "1. Pick one\n[ ] Option A\n[ ] Option B";
        tmux.screen(base);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(31));
        tmux.queueCapture(base);
        // dialog reaction: two lines change, not a simple echo
        tmux.queueCapture("1. Pick one\n[x] Option A\n[ ] Other: ,");
        tmux.queueCapture("1. Pick one\n[x] Option A\n[ ] Other:"); // residue: no revert
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick());
        assertTrue(tmux.sent.isEmpty());
        assertEquals(1, alerts.size());
        assertTrue(alerts.getFirst().contains("echo probe"));

        // sticky: later fallback-idle ticks neither probe nor stimulate
        clock.advance(Duration.ofSeconds(40));
        stimulator.tick(); // residue changed screen -> WORKING
        clock.advance(Duration.ofSeconds(31));
        stimulator.tick();
        assertEquals(1, tmux.literals.size());
        assertTrue(tmux.sent.isEmpty());
    }

    @Test
    void probeVetoClearsWhenPluginRecognizesTheScreenAgain() {
        String base = "1. Pick one\n[ ] Option A\n[ ] Option B";
        tmux.screen(base);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(31));
        tmux.queueCapture(base);
        tmux.queueCapture("1. Pick one\n[x] Option A\n[ ] Other: ,");
        tmux.queueCapture("1. Pick one\n[x] Option A\n[ ] Other:");
        stimulator.tick(); // probe fails, veto set
        assertTrue(tmux.sent.isEmpty());

        tmux.screen("checked off item 5\n❯ "); // plugin-recognized idle clears the veto
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING (screen changed)
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // plugin idle -> stimulate without probing
        assertEquals(List.of(IDLE_MSG), tmux.sent);
        assertEquals(1, tmux.literals.size()); // no second probe
    }

    @Test
    void detachWinsOverPendingStimulus() {
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        commands.add(Stimulator.Command.DETACH); // lands while a stimulus is due
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.DETACHED_BY_USER, stimulator.tick());
        assertTrue(tmux.sent.isEmpty());
        assertTrue(alerts.getFirst().contains("detach requested"));
    }

    @Test
    void stopEntersObserveOnlyAndResumeReenables() {
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        commands.add(Stimulator.Command.STOP);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick()); // stays attached
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();
        assertTrue(tmux.sent.isEmpty()); // idle, but observe-only: no stimulus
        assertTrue(alerts.getFirst().contains("observing only"));

        commands.add(Stimulator.Command.RESUME);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick());
        assertEquals(List.of(IDLE_MSG), tmux.sent); // stimulation back on
    }

    @Test
    void observeOnlyStillAlertsOnApprovalAndStopsOnBroken() {
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        commands.add(Stimulator.Command.STOP);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick();
        tmux.screen("doing item 8\nRun this command? [y/n]");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING (screen changed)
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();
        assertTrue(alerts.stream().anyMatch(a -> a.contains("approval"))); // alerts continue
        tmux.paneDies();
        assertEquals(Stimulator.Outcome.STOPPED_BROKEN, stimulator.tick()); // broken still stops
        assertTrue(tmux.sent.isEmpty());
    }

    @Test
    void stealHoldsThenResumesWhenHumanReleases() {
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        commands.add(Stimulator.Command.STEAL);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // enters hold, WAITING_FOR_HUMAN
        assertTrue(alerts.getFirst().contains("holding"));

        tmux.rwClients = 1; // human attaches
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // HUMAN_ATTACHED
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // idle screen but human holds: no stimulus
        assertTrue(tmux.sent.isEmpty());

        tmux.rwClients = 0; // ctrl-b d
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // release detected, resumes
        assertTrue(alerts.getLast().contains("released"));
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // stimulation back on
        assertEquals(List.of(IDLE_MSG), tmux.sent);
    }

    @Test
    void stealTimesOutWhenNoHumanAttaches() {
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        commands.add(Stimulator.Command.STEAL);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // hold, waiting for human
        clock.advance(Duration.ofSeconds(61)); // past the 60s human-arrival deadline
        stimulator.tick();
        assertTrue(alerts.getLast().contains("no human attached"));
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // stimulation resumed
        assertEquals(List.of(IDLE_MSG), tmux.sent);
    }

    @Test
    void brokenDuringHoldStops() {
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        commands.add(Stimulator.Command.STEAL);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick();
        tmux.rwClients = 1;
        tmux.paneDies();
        assertEquals(Stimulator.Outcome.STOPPED_BROKEN, stimulator.tick());
    }

    @Test
    void deadPaneStopsAsBroken() {
        tmux.paneDies();
        assertEquals(Stimulator.Outcome.STOPPED_BROKEN, stimulator.tick());
        assertFalse(alerts.isEmpty());
    }

    @Test
    void neverTypesAnAnswerIntoASelectorQuestion() {
        // codex's request_user_input widget: the configured answer would not land as
        // text — the characters navigate the option list and the Enter that follows
        // commits whatever ends up highlighted. Notify-only, like approval.
        StrategyConfig cfg = new StrategyConfig("codex", IDLE_MSG, StrategyConfig.OnIdle.NUDGE,
                Optional.of("use your best judgment"), 25,
                Duration.ofSeconds(1), 1.0, 3, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(30), true);
        StateDetector detector = new StateDetector(new CodexPlugin(), clock,
                cfg.shortQuiescence(), cfg.longQuiescence());
        stimulator = new Stimulator("test", tmux, detector, cfg, Optional.empty(),
                new AuditLog(tmp.resolve("audit.jsonl")), alerts::add, clock,
                millis -> { }, commands::poll, Stimulator.StatusSink.NONE);

        tmux.screen("""
                \u203a Ask me which option to use.
                  Question 1/1 (1 unanswered)
                  Which text should the file contain?
                  \u203a 1. option A (Recommended)
                    2. option B
                  tab to add notes | enter to submit answer | esc to interrupt""");
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();

        assertTrue(tmux.sent.isEmpty(), "must not type into a selector question");
        assertTrue(tmux.literals.isEmpty(), "must not probe a selector question");
        assertEquals(1, alerts.size());
        assertTrue(alerts.getFirst().contains("selector widget"));
    }

    @Test
    void stillAnswersAPlainTextQuestionWhenConfigured() {
        StrategyConfig cfg = new StrategyConfig("codex", IDLE_MSG, StrategyConfig.OnIdle.NUDGE,
                Optional.of("use your best judgment"), 25,
                Duration.ofSeconds(1), 1.0, 3, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(30), true);
        StateDetector detector = new StateDetector(new CodexPlugin(), clock,
                cfg.shortQuiescence(), cfg.longQuiescence());
        stimulator = new Stimulator("test", tmux, detector, cfg, Optional.empty(),
                new AuditLog(tmp.resolve("audit.jsonl")), alerts::add, clock,
                millis -> { }, commands::poll, Stimulator.StatusSink.NONE);

        tmux.screen("""
                \u2022 Should the file contain option A or option B?
                \u203a Explain this codebase
                  gpt-5.6-terra medium \u00b7 /tmp/x \u00b7 Context 99% left \u00b7 0.147.0 \u00b7 Fast off""");
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();

        assertEquals(List.of("use your best judgment"), tmux.sent);
    }

    @Test
    void aRefusedSendDoesNotKillTheRun() {
        // tmux refuses send-keys whenever the client it resolves is a read-only
        // attachment, so somebody running `tmux attach -r` is enough to fail every
        // stimulus. That killed a real 20-item run on the nudge after the last item.
        tmux.sendsRefused = true;
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick());
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick());
        assertTrue(tmux.sent.isEmpty());
        assertEquals(1, alerts.size(), "one alert per outage, not one per retry");
        assertTrue(alerts.getFirst().contains("read-only"));
    }

    @Test
    void aRefusedStimulusIsNotChargedToTheBudget() {
        build(config(Optional.empty(), 2, 99));
        tmux.sendsRefused = true;
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(6));
            assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick());
        }
        // five refused attempts against a budget of two: none of them counted
        tmux.sendsRefused = false;
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();
        assertEquals(List.of(IDLE_MSG), tmux.sent, "the budget survived the outage");
    }

    @Test
    void stimulationRecoversWhenSendsWorkAgain() {
        tmux.sendsRefused = true;
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();
        assertTrue(tmux.sent.isEmpty());

        tmux.sendsRefused = false;   // the peeker detached
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();
        assertEquals(List.of(IDLE_MSG), tmux.sent);
    }

    @Test
    void aRefusedProbeDoesNotStickTheFallbackVeto() {
        // the probe learned nothing about the screen, so it must not latch the sticky
        // veto that a genuinely failed probe sets
        StrategyConfig cfg = config(Optional.empty(), 25, 99);
        build(cfg);
        tmux.sendsRefused = true;
        tmux.screen("a screen no plugin pattern recognises");
        stimulator.tick();
        clock.advance(Duration.ofSeconds(45));      // generic behavioural idle -> probe
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick());

        tmux.sendsRefused = false;
        tmux.queueCapture("a screen no plugin pattern recognises");
        tmux.queueCapture("a screen no plugin pattern recognises,");
        tmux.queueCapture("a screen no plugin pattern recognises");
        clock.advance(Duration.ofSeconds(45));
        stimulator.tick();
        assertEquals(List.of(IDLE_MSG), tmux.sent, "probing resumes once tmux accepts sends");
    }

    @Test
    void loopModeWipesContextThenTypesTheInstruction() {
        build(resetConfig(25, 3), Optional.of(RESET_CMD));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick(); // first sight: WORKING
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // idle -> reset only, never chained with the instruction
        assertEquals(List.of(RESET_CMD), tmux.sent);

        tmux.screen("fresh conversation\n❯ "); // the wipe's own redraw
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING (screen changed)
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // idle again -> the instruction lands
        assertEquals(List.of(RESET_CMD, LOOP_INSTRUCTION), tmux.sent);
    }

    @Test
    void loopModeNeverTypesTheInstructionUntilTheScreenIsIdleAgain() {
        // between reset and instruction anything can appear — a dialog there must hold
        // the instruction exactly as it would hold any stimulus
        build(resetConfig(25, 3), Optional.of(RESET_CMD));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // reset sent
        tmux.screen("Run this command? [y/n]");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING (screen changed)
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // approval dialog -> notify, no instruction
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();
        assertEquals(List.of(RESET_CMD), tmux.sent);
        assertTrue(alerts.getFirst().contains("approval"));

        tmux.screen("fresh conversation\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // idle -> now the instruction goes
        assertEquals(List.of(RESET_CMD, LOOP_INSTRUCTION), tmux.sent);
    }

    @Test
    void loopModeBudgetCountsIterationsAndFinishesTheLastOne() {
        // one iteration = one budget unit, charged at the reset; the instruction of a
        // charged iteration still goes out, or the budget edge would leave a wiped
        // context with no instruction
        build(resetConfig(1, 10), Optional.of(RESET_CMD));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // iteration 1: reset (budget now spent)
        tmux.screen("fresh conversation\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // instruction still sent
        assertEquals(List.of(RESET_CMD, LOOP_INSTRUCTION), tmux.sent);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.STOPPED_MAX_STIMULI, stimulator.tick());
        assertEquals(2, tmux.sent.size(), "no second iteration starts");
    }

    @Test
    void loopModeStallsAccrueWhenTheInstructionProducesNoWork() {
        // the wipe's own redraw is screen activity, so if the stall flag re-armed at
        // the reset the escalation rail would never fire in loop mode; it re-arms at
        // the instruction instead
        build(resetConfig(25, 2), Optional.of(RESET_CMD));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // iteration 1: reset
        tmux.screen("fresh conversation 1\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING (redraw)
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // instruction 1
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // still the same idle screen -> stall 1, iteration 2: reset
        tmux.screen("fresh conversation 2\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING (redraw) — must NOT reset the stall counter
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // instruction 2
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.STOPPED_ESCALATED, stimulator.tick()); // stall 2
        assertEquals(List.of(RESET_CMD, LOOP_INSTRUCTION, RESET_CMD, LOOP_INSTRUCTION), tmux.sent);
        assertTrue(alerts.getLast().contains("escalating"));
    }

    @Test
    void loopModeStallCounterResetsWhenTheInstructionTriggersWork() {
        build(resetConfig(25, 2), Optional.of(RESET_CMD));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        for (int i = 1; i <= 3; i++) { // three full iterations, past escalateAfter=2
            clock.advance(Duration.ofSeconds(6));
            assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick()); // reset
            tmux.screen("fresh conversation " + i + "\n❯ ");
            clock.advance(Duration.ofSeconds(2));
            stimulator.tick(); // WORKING (redraw)
            clock.advance(Duration.ofSeconds(6));
            stimulator.tick(); // instruction
            tmux.screen("working on item " + i + "...\noutput streaming");
            clock.advance(Duration.ofSeconds(2));
            stimulator.tick(); // real work after the instruction -> no stall
            tmux.screen("checked off item " + i + "\n❯ ");
            clock.advance(Duration.ofSeconds(2));
            stimulator.tick(); // WORKING (screen changed)
        }
        assertEquals(6, tmux.sent.size(), "three iterations of reset + instruction, no escalation");
    }

    @Test
    void stealDuringALoopIterationRestartsTheIterationCleanly() {
        // the human may have typed their own task into the wiped context; forcing the
        // pending instruction on release could contradict it, so the half-done
        // iteration is dropped and the next idle starts over from a reset
        build(resetConfig(25, 3), Optional.of(RESET_CMD));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // reset sent, instruction pending
        assertEquals(List.of(RESET_CMD), tmux.sent);

        commands.add(Stimulator.Command.STEAL);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // hold
        tmux.rwClients = 1;
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // human attached
        tmux.rwClients = 0;
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // released

        tmux.screen("the human typed something\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING (screen changed)
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // idle -> a fresh iteration's reset, not the stale instruction
        assertEquals(List.of(RESET_CMD, RESET_CMD), tmux.sent);
    }

    @Test
    void aSendOutageDoesNotAdvanceEscalation() {
        // escalation counts stimuli the harness received and ignored; retries of sends
        // that never landed must not count, or a read-only peek plus one quiet stimulus
        // escalates and ends the run sendFailed promises to keep alive
        build(config(Optional.empty(), 25, 3));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // stimulus 1 delivered; the harness ignores it
        tmux.sendsRefused = true;
        for (int i = 0; i < 6; i++) { // far past escalateAfter=3 if refused sends counted
            clock.advance(Duration.ofSeconds(6));
            assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick());
        }
        assertEquals(1, tmux.sent.size());
        tmux.sendsRefused = false;
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick()); // stimulus 2 delivered
        assertEquals(2, tmux.sent.size());
    }

    @Test
    void aLateReactionDuringASendOutageStillCountsAsProgress() {
        // the stall evaluation for a stimulus can run before the harness's reaction
        // arrives; if the reaction is then seen during a send outage it must still
        // reset the counter, or a read-only peek escalates a progressing run
        build(config(Optional.empty(), 25, 2));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // stimulus 1 delivered
        tmux.sendsRefused = true;
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // evaluation: stall 1; stimulus 2 refused
        tmux.screen("working on item 4...\noutput streaming");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // the harness reacts late to stimulus 1
        tmux.screen("checked off item 4\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING (screen changed)
        tmux.sendsRefused = false;
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // progress credited (stalls back to 0), stimulus 2 delivered
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick(), // stall 1 again, not 2
                "the late reaction must have reset the stall counter");
        assertEquals(3, tmux.sent.size());
    }

    @Test
    void stealAfterAStopKeepsObserveOnlyAfterRelease() {
        // whether an explicit stop sticks must not depend on whether it raced the steal
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        commands.add(Stimulator.Command.STOP);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // observe-only
        commands.add(Stimulator.Command.STEAL);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // hold; the ack must not promise an automatic resume
        assertTrue(alerts.getLast().contains("observe-only"));
        tmux.rwClients = 1;
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick();
        tmux.rwClients = 0;
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // released — the stop still holds
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();
        assertTrue(tmux.sent.isEmpty(), "stopped before the steal, so stopped after it");
        commands.add(Stimulator.Command.RESUME);
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();
        assertEquals(List.of(IDLE_MSG), tmux.sent, "only an explicit resume re-enables");
    }

    @Test
    void aStallEvaluationPendingAtStealTimeIsDroppedWithTheHold() {
        // the hold is a blind window (WORKING during it is never recorded), so an
        // evaluation armed before the steal must not count it as a stall — that would
        // escalate immediately after the human hands the session back
        build(config(Optional.empty(), 25, 1));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // stimulus 1 delivered; evaluation pending
        commands.add(Stimulator.Command.STEAL);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // hold before any evaluation ran
        tmux.rwClients = 1;
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick();
        tmux.rwClients = 0;
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // released
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick(),
                "escalateAfter=1 must not fire off the pre-steal evaluation");
        assertEquals(2, tmux.sent.size(), "babysitting continues with a fresh stimulus");
    }

    @Test
    void resumeDuringAStealHoldDropsThePendingLoopInstruction() {
        // resume is a second way out of a hold; it must run the same cleanup as a
        // ctrl-b-d release, or the stale instruction lands in whatever conversation
        // the human just established
        build(resetConfig(25, 3), Optional.of(RESET_CMD));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // reset sent, instruction pending
        commands.add(Stimulator.Command.STEAL);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // hold
        tmux.rwClients = 1;
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // human attached
        commands.add(Stimulator.Command.RESUME);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // hold ends with cleanup; idle -> a fresh iteration's reset
        assertEquals(List.of(RESET_CMD, RESET_CMD), tmux.sent,
                "a fresh reset, never the stale instruction");
        assertTrue(alerts.stream().anyMatch(a -> a.contains("ending the hold")));
    }

    @Test
    void stopDuringAHoldSticksAfterRelease() {
        // a stop that arrives while the human holds the session must not be silently
        // swallowed just because stimulation is already off during the hold
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        commands.add(Stimulator.Command.STEAL);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // hold
        tmux.rwClients = 1;
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // human attached
        commands.add(Stimulator.Command.STOP);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // stop noted, not dropped
        assertTrue(alerts.getLast().contains("staying observe-only"));
        tmux.rwClients = 0;
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // released -> observing only, per the pending stop
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick();
        assertTrue(tmux.sent.isEmpty(), "no stimulus after release with a pending stop");
        commands.add(Stimulator.Command.RESUME);
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // resume re-enables; idle -> nudge
        assertEquals(List.of(IDLE_MSG), tmux.sent);
    }

    @Test
    void loopInstructionOnFallbackIdleRequiresTheEchoProbe() {
        build(resetConfig(25, 3), Optional.of(RESET_CMD));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // reset via plugin-recognized idle
        String base = "some unrecognized fresh screen";
        tmux.screen(base);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING (screen changed)
        clock.advance(Duration.ofSeconds(31)); // long quiescence -> behavioral fallback idle
        tmux.queueCapture(base);                                   // tick's own capture
        tmux.queueCapture("some unrecognized fresh screen,");      // probe echo
        tmux.queueCapture(base);                                   // reverted
        stimulator.tick(); // probe passes -> instruction
        assertEquals(List.of(RESET_CMD, LOOP_INSTRUCTION), tmux.sent);
        assertEquals(List.of(","), tmux.literals);
        assertEquals(1, tmux.backspaces);
    }

    @Test
    void loopInstructionIsHeldWhenTheFallbackProbeFails() {
        build(resetConfig(25, 3), Optional.of(RESET_CMD));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // reset sent
        String base = "some unrecognized fresh screen";
        tmux.screen(base);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING
        clock.advance(Duration.ofSeconds(31));
        tmux.queueCapture(base);
        tmux.queueCapture(base); // no echo at all: an unrecognized interactive element
        tmux.queueCapture(base);
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick());
        assertEquals(List.of(RESET_CMD), tmux.sent, "the instruction is held behind the veto");
        assertTrue(alerts.stream().anyMatch(a -> a.contains("echo probe")));

        // sticky: the pending instruction stays held on later fallback-idle ticks
        clock.advance(Duration.ofSeconds(31));
        stimulator.tick();
        assertEquals(List.of(RESET_CMD), tmux.sent);
        assertEquals(1, tmux.literals.size(), "no re-probe of a perturbed screen");
    }

    @Test
    void aRefusedLoopInstructionKeepsTheIterationPendingAndRetries() {
        build(resetConfig(25, 3), Optional.of(RESET_CMD));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // reset delivered
        tmux.screen("fresh conversation\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING
        tmux.sendsRefused = true;
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick()); // instruction refused
        assertEquals(List.of(RESET_CMD), tmux.sent);
        tmux.sendsRefused = false;
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // retried — without a second reset first
        assertEquals(List.of(RESET_CMD, LOOP_INSTRUCTION), tmux.sent,
                "the wiped context still gets its instruction");
        assertTrue(alerts.stream().anyMatch(a -> a.contains("read-only")));
    }

    @Test
    void loopModeBudgetPermitsExactlyMaxStimuliIterations() {
        // pins one-iteration-one-unit beyond the degenerate budget of 1, where
        // double-charging reset and instruction would be observationally identical
        build(resetConfig(2, 10), Optional.of(RESET_CMD));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        for (int i = 1; i <= 2; i++) {
            clock.advance(Duration.ofSeconds(6));
            assertEquals(Stimulator.Outcome.CONTINUE, stimulator.tick()); // reset i
            tmux.screen("fresh conversation " + i + "\n❯ ");
            clock.advance(Duration.ofSeconds(2));
            stimulator.tick(); // WORKING (redraw)
            clock.advance(Duration.ofSeconds(6));
            stimulator.tick(); // instruction i
        }
        assertEquals(4, tmux.sent.size(), "two full iterations fit a budget of two");
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.STOPPED_MAX_STIMULI, stimulator.tick());
    }

    @Test
    void loopModeBackoffGrowsBetweenIterations() {
        StrategyConfig cfg = new StrategyConfig("cursor-agent", LOOP_INSTRUCTION,
                StrategyConfig.OnIdle.RESET, Optional.empty(), 25,
                Duration.ofSeconds(1), 4.0, 10, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(30), true);
        build(cfg, Optional.of(RESET_CMD));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // reset 1: backoff grows 1s -> 4s
        tmux.screen("fresh conversation\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING (redraw)
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // instruction 1
        clock.advance(Duration.ofSeconds(2)); // inside the grown 4s backoff
        stimulator.tick();
        assertEquals(2, tmux.sent.size(), "no second iteration inside the backoff");
        clock.advance(Duration.ofSeconds(3)); // 5s since the instruction: past it
        stimulator.tick();
        assertEquals(3, tmux.sent.size(), "iteration 2's reset once the backoff elapses");
    }

    @Test
    void aPendingLoopInstructionSurvivesStopThenResume() {
        // stop is observe-only, not a steal: no human typed into the session, so the
        // half-done iteration completes once stimulation is re-enabled
        build(resetConfig(25, 3), Optional.of(RESET_CMD));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // reset sent
        commands.add(Stimulator.Command.STOP);
        tmux.screen("fresh conversation\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // observe-only from here
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // idle, but stopped: no instruction
        assertEquals(List.of(RESET_CMD), tmux.sent);
        commands.add(Stimulator.Command.RESUME);
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // resumed; idle -> the pending instruction, not a new reset
        assertEquals(List.of(RESET_CMD, LOOP_INSTRUCTION), tmux.sent);
    }

    @Test
    void aStealDuringTheResetWindowDoesNotEraseAccruedStalls() {
        // the reset's redraw is quarantined progress, wiped when the instruction
        // lands; a steal that aborts the iteration must not let it leak into the
        // next evaluation and zero the stall counter
        build(resetConfig(25, 2), Optional.of(RESET_CMD));
        tmux.screen(IDLE_SCREEN);
        stimulator.tick();
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // iteration 1: reset
        tmux.screen("fresh conversation 1\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING (redraw)
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // instruction 1 — ignored by the harness
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // stall 1, iteration 2: reset
        tmux.screen("fresh conversation 2\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING (redraw) — quarantined progress
        commands.add(Stimulator.Command.STEAL);
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // hold mid-iteration
        tmux.rwClients = 1;
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick();
        tmux.rwClients = 0;
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // released; the aborted iteration's redraw must not count
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // idle -> iteration 3: reset (no stall change, no credit)
        tmux.screen("fresh conversation 3\n❯ ");
        clock.advance(Duration.ofSeconds(2));
        stimulator.tick(); // WORKING (redraw)
        clock.advance(Duration.ofSeconds(6));
        stimulator.tick(); // instruction 3 — also ignored
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Stimulator.Outcome.STOPPED_ESCALATED, stimulator.tick(),
                "stall 2: the redraw seen before the steal was not progress");
        assertEquals(5, tmux.sent.size());
    }

    @Test
    void loopModeRefusesToBuildWithoutAResetCommand() {
        StateDetector detector = new StateDetector(new CursorAgentPlugin(), clock,
                Duration.ofSeconds(5), Duration.ofSeconds(30));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Stimulator("test", tmux, detector, resetConfig(25, 3), Optional.empty(),
                        new AuditLog(tmp.resolve("audit.jsonl")), alerts::add, clock,
                        millis -> { }, commands::poll, Stimulator.StatusSink.NONE));
    }
}

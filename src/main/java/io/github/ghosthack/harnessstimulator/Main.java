package io.github.ghosthack.harnessstimulator;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/** CLI entry point: setup / attach / status. */
public final class Main {

    static final String SUPPORTED_HARNESSES = "cursor-agent, claude-code, codex";

    private static final String USAGE = """
            usage:
              harness-stimulator run [handle] [options]
                  the one command you need: starts (or resumes) the harness, waits for
                  it to come up, and begins babysitting. You never touch tmux.
                  Ctrl-C detaches; the harness keeps running.

                  Resuming is the point: this app is for work where a conversation has
                  built up context worth keeping, so you usually want to carry on an
                  existing harness session rather than start a blank one.
                      run --harness codex --last              most recent conversation
                      run --harness codex --resume <id|name>  a specific one
                      run --harness codex                     start fresh

                  [handle] names the babysat session for peek/stop/detach. Omit it and
                  one is derived (bs-codex-last, bs-codex-<id>, bs-codex); --name overrides.

                  options: --harness <name>            (cursor-agent | claude-code | codex;
                                                        default cursor-agent)
                           --model <name>              (omit to inherit the harness default;
                                                        verified for claude-code and codex)
                           --resume <id|name>          (resume this harness conversation)
                           --last                      (resume the most recent one)
                           --name <handle>             (explicit handle)
                           --dir <workdir>             (default: current directory)
                           --harness-args "<args>"     (passed to the harness binary,
                                                        e.g. "--ask-for-approval never")
                           --prompt <text>             (optional first instruction; only sent
                                                        if the harness is at an idle prompt,
                                                        never into a trust/consent dialog.
                                                        Omit it and use `steal` to type the
                                                        task yourself)
                           --idle-message <text>       (the nudge)
                           --on-idle <nudge|reset>     (what an idle harness gets. nudge
                                                        (default): type --idle-message into
                                                        the ongoing conversation. reset: wipe
                                                        the context with the harness's own
                                                        /clear-equivalent, wait for the
                                                        composer to come back, then type the
                                                        instruction again — a fresh-context
                                                        loop. The instruction is --prompt
                                                        unless --idle-message overrides it,
                                                        and must be self-contained: state
                                                        lives in the repo, not the
                                                        conversation, so "continue" won't
                                                        work. Every instruction, including
                                                        the first, lands after a wipe;
                                                        --max-stimuli counts iterations)
                           --question-answer <text>    (omit = notify-only on questions)
                           --max-stimuli <n>           (0 = unlimited)
                           --escalate-after <n>

              harness-stimulator setup <session> --dir <workdir> [options]
                  lower-level: creates the session and persists config, but does not
                  start the harness or attach (use `run` unless you need this)
                  options: --harness <name>            (cursor-agent | claude-code | codex;
                                                        default cursor-agent)
                           --idle-message <text>
                           --on-idle <nudge|reset>     (see run)
                           --question-answer <text>    (omit = notify-only on questions)
                           --max-stimuli <n>           (0 = unlimited; the stop conditions are
                                                        the budget, escalation, and the human —
                                                        there is no completion signal)
                           --escalate-after <n>        (stimuli with NO harness activity
                                                        before escalating; liveness, not
                                                        progress)
              harness-stimulator attach <session>
                  attaches monitoring + stimulation to an existing session (Ctrl-C detaches;
                  the tmux session and harness keep running untouched)
              harness-stimulator status <session>
                  one-shot state classification
              harness-stimulator stop <session>
                  observe-only at the next poll: stimulation stops, but monitoring,
                  alerts, and the audit trail continue
              harness-stimulator resume <session>
                  re-enables stimulation on a stopped (observe-only) session
              harness-stimulator detach <session> [--wait]
                  ends the attachment at the next poll; the tmux session and harness
                  keep running untouched. --wait returns only after the stimulator has
                  released ownership, for a race-free direct tmux takeover
              (control requests are discarded by the next attach if nothing is attached)
              harness-stimulator peek <session> [--embedded]
                  read-only view of the live session, refreshed once a second
                  (Ctrl-C to stop). Attaches no tmux client, so it cannot disturb
                  an attached stimulator. --embedded removes the interactive preamble
                  for a host UI that owns the viewer process
              harness-stimulator steal <session>
                  borrows the session: the stimulator holds (no keystrokes, monitoring
                  continues) while you drive read-write; it resumes automatically when
                  you detach (ctrl-b d), unless a stop is pending — then it stays
                  observe-only until `resume`. use detach to remove the babysitter
                  for good.
              harness-stimulator inspect <session> [--json]
                  reports persisted configuration and whether a live stimulator owns
                  the session; --json is the stable machine-facing form
              harness-stimulator close <session>
                  stops stimulation, waits for a high-confidence idle prompt, exits
                  the harness with its verified native command, then exits the tmux
                  shell. Never kills a process or types through work/dialogs

            safe abort at any time:         tmux kill-session -t <session>
            """;

    public static void main(String[] args) throws Exception {
        Deque<String> a = new ArrayDeque<>(List.of(args));
        if (a.isEmpty()) {
            System.out.print(USAGE);
            System.exit(2);
        }
        String command = a.pop();
        Clock clock = Clock.systemUTC();
        ConfigStore store = ConfigStore.inUserHome();
        Tmux tmux = new RealTmux(clock);
        switch (command) {
            case "run" -> run(a, store, tmux, clock);
            case "setup" -> setup(a, store, tmux);
            case "attach" -> attach(a, store, tmux, clock);
            case "status" -> status(a, store, tmux, clock);
            case "stop" -> control(a, store, "stop",
                    "stimulation will stop at the next poll; monitoring and alerts continue");
            case "resume" -> control(a, store, "resume",
                    "stimulation will resume at the next poll");
            case "detach" -> detach(a, store);
            case "peek" -> peek(a, tmux);
            case "steal" -> steal(a, store, tmux);
            case "inspect" -> inspect(a, store, tmux);
            case "close" -> close(a, store, tmux, clock);
            default -> {
                System.err.println("unknown command: " + command);
                System.out.print(USAGE);
                System.exit(2);
            }
        }
    }

    /** Flags shared by setup and run. {@code harnessArgs} is only meaningful to run. */
    private record Options(String session, String workdir, StrategyConfig strategy, String harnessArgs,
                           Optional<String> model,
                           Optional<String> prompt, Optional<String> resume, boolean resumeLast) {
    }

    private static Options parseOptions(Deque<String> a, boolean workdirRequired, boolean sessionRequired) {
        // the handle is optional for run: what matters there is which harness conversation
        // to resume, and the tmux name is plumbing we can derive
        String session = null;
        if (!a.isEmpty() && !a.peek().startsWith("--")) {
            session = a.pop();
        } else if (sessionRequired) {
            die("missing session name");
        }
        StrategyConfig d = StrategyConfig.defaults();
        String workdir = null;
        String harness = d.harness();
        String idleMessage = null; // null = not given; resolved after the flags are read
        StrategyConfig.OnIdle onIdle = d.onIdle();
        Optional<String> questionAnswer = d.questionAnswer();
        int maxStimuli = d.maxStimuli();
        int escalateAfter = d.escalateAfterStalls();
        String harnessArgs = "";
        Optional<String> model = Optional.empty();
        Optional<String> prompt = Optional.empty();
        Optional<String> resume = Optional.empty();
        boolean resumeLast = false;
        while (!a.isEmpty()) {
            String flag = a.pop();
            switch (flag) {
                case "--dir" -> workdir = require(a, "--dir value");
                case "--harness" -> harness = require(a, "--harness value");
                case "--harness-args" -> harnessArgs = require(a, "--harness-args value");
                case "--model" -> {
                    String value = require(a, "--model value").strip();
                    if (value.isEmpty()) {
                        die("--model must not be blank");
                    }
                    model = Optional.of(value);
                }
                case "--prompt" -> prompt = Optional.of(require(a, "--prompt value"));
                case "--resume" -> resume = Optional.of(require(a, "--resume value"));
                case "--last" -> resumeLast = true;
                case "--name" -> session = require(a, "--name value");
                case "--idle-message" -> idleMessage = require(a, "--idle-message value");
                case "--on-idle" -> onIdle = onIdleOf(require(a, "--on-idle value"));
                case "--question-answer" -> questionAnswer = Optional.of(require(a, "--question-answer value"));
                case "--max-stimuli" -> maxStimuli = Integer.parseInt(require(a, "--max-stimuli value"));
                case "--escalate-after" -> escalateAfter = Integer.parseInt(require(a, "--escalate-after value"));
                default -> die("unknown flag: " + flag);
            }
        }
        if (workdir == null) {
            if (workdirRequired) {
                die("--dir is required");
            }
            workdir = Path.of("").toAbsolutePath().toString();
        }
        // fail here rather than at attach: a persisted config naming a harness we have
        // no plugin for is only discovered when the babysitter is meant to start working
        try {
            HarnessPlugin plugin = pluginFor(harness);
            if (model.isPresent() && plugin.modelFlag().isEmpty()) {
                die("harness '" + harness + "' has no verified model-selection syntax;"
                        + " omit --model to inherit its configured default");
            }
        } catch (IllegalArgumentException e) {
            die(e.getMessage());
        }
        try {
            idleMessage = resolveIdleMessage(harness, onIdle, idleMessage, prompt);
        } catch (IllegalArgumentException e) {
            die(e.getMessage());
        }
        StrategyConfig strategy = new StrategyConfig(harness, idleMessage, onIdle, questionAnswer,
                maxStimuli, d.initialBackoff(), d.backoffMultiplier(), escalateAfter,
                d.pollInterval(), d.shortQuiescence(), d.longQuiescence(), d.probeFallbackIdle());
        if (resume.isPresent() && resumeLast) {
            die("--resume and --last are mutually exclusive");
        }
        return new Options(session, workdir, strategy, harnessArgs, model,
                prompt, resume, resumeLast);
    }

    private static StrategyConfig.OnIdle onIdleOf(String value) {
        return switch (value) {
            case "nudge" -> StrategyConfig.OnIdle.NUDGE;
            case "reset" -> StrategyConfig.OnIdle.RESET;
            default -> {
                die("--on-idle must be nudge or reset, not '" + value + "'");
                yield null;
            }
        };
    }

    /**
     * What gets typed into an idle harness, resolved from the flags. {@code idleMessage}
     * is null when --idle-message was not given: nudge mode falls back to the default
     * nudge, reset mode to {@link #resetModeInstruction}. Reset mode also requires the
     * harness to have a reset command — refusing here, at parse time, rather than
     * letting the mode guess one and wipe the wrong thing.
     */
    static String resolveIdleMessage(String harness, StrategyConfig.OnIdle onIdle,
                                     String idleMessage, Optional<String> prompt) {
        if (onIdle == StrategyConfig.OnIdle.RESET) {
            if (pluginFor(harness).resetCommand().isEmpty()) {
                throw new IllegalArgumentException("harness '" + harness + "' has no known"
                        + " context-reset command, so --on-idle reset would have to guess one"
                        + " and wipe the wrong thing. claude-code (/clear) and codex (/new)"
                        + " support it");
            }
            String instruction = resetModeInstruction(idleMessage, prompt);
            // refused here, at parse time, so it cannot launch a harness and then die
            // at attach; the attach-time twin of this check covers hand-edited configs
            if (instruction.equals(StrategyConfig.defaults().idleMessage())) {
                throw new IllegalArgumentException("the default conversational nudge cannot be"
                        + " the loop instruction — it lands in a context wiped every iteration."
                        + " Pass a self-contained instruction via --prompt or --idle-message");
            }
            return instruction;
        }
        return idleMessage != null ? idleMessage : StrategyConfig.defaults().idleMessage();
    }

    /**
     * Loop mode types this into a zero-context harness at every iteration, so the
     * conversational default nudge is never usable there. Without an explicit
     * --idle-message the opening --prompt is the instruction — the same self-contained
     * instruction, a fresh context, and the repo carrying the state in between.
     */
    static String resetModeInstruction(String explicitIdleMessage, Optional<String> prompt) {
        if (explicitIdleMessage != null) {
            return explicitIdleMessage;
        }
        return prompt.orElseThrow(() -> new IllegalArgumentException(
                "--on-idle reset starts every iteration from zero context, so it needs a"
                        + " self-contained instruction: pass --prompt (reused each iteration)"
                        + " or --idle-message. \"continue\" won't work — after the reset there"
                        + " is nothing to continue from; state must live in the repo"));
    }

    private static void setup(Deque<String> a, ConfigStore store, Tmux tmux) {
        Options o = parseOptions(a, true, true);
        store.save(new ConfigStore.SessionConfig(o.session(), o.workdir(), o.strategy(), "",
                o.model().orElse("")));
        if (tmux.hasSession(o.session())) {
            System.out.println("session '" + o.session() + "' already exists — adopted, config saved");
        } else {
            tmux.newSession(o.session(), o.workdir());
            System.out.println("created session '" + o.session() + "' in " + o.workdir());
        }
        System.out.println("start it all with:  harness-stimulator run " + o.session());
    }

    /**
     * setup + launch the harness + attach, in one command. The whole point is that a
     * user never has to know tmux exists: the app owns the session, starts the harness
     * binary in it, waits for the process to actually come up, and begins babysitting.
     */
    private static void run(Deque<String> a, ConfigStore store, Tmux tmux, Clock clock) throws InterruptedException {
        Options o = parseOptions(a, false, false);
        StrategyConfig s = o.strategy();
        HarnessPlugin plugin = pluginFor(s.harness());
        String session = o.session() != null ? o.session()
                : deriveHandle(plugin, o.resume(), o.resumeLast());
        String requested = conversationKey(s.harness(), o.resume(), o.resumeLast());

        // Own the handle before setup/launch, not only once the polling loop begins.
        // A manager can therefore discover and detach a worker even while it is waiting
        // for the harness banner or the opening prompt, with no takeover race window.
        Path runLock = store.lockPath(session);
        long self = ProcessHandle.current().pid();
        java.util.OptionalLong owner = acquireAttachLock(runLock, self,
                pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        if (owner.isPresent()) {
            die("a stimulator (pid " + owner.getAsLong() + ") is already attached to '" + session + "'");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> releaseAttachLock(runLock, self)));
        Path startupControl = store.controlPath(session);
        deleteQuietly(startupControl); // discard a stale request from an earlier owner
        try {

        boolean existed = tmux.hasSession(session);
        String foreground = existed ? tmux.capture(session).foregroundCommand() : "";
        boolean harnessUp = existed && plugin.ownsProcess(foreground);
        Optional<ConfigStore.SessionConfig> stored = store.load(session);
        String recorded = stored.map(ConfigStore.SessionConfig::conversation).orElse("");

        // both guards run before store.save: a refused run must not have already
        // clobbered the config of whoever is legitimately using this handle
        if (harnessUp && !recorded.isEmpty() && !recorded.equals(requested)) {
            die("handle '" + session + "' is already babysitting a different conversation:"
                    + " it holds `" + recorded + "`, you asked for `" + requested + "`."
                    + " Pick a different --name, or end the old run first"
                    + " (harness-stimulator steal " + session + ", then exit the harness)");
        }
        if (existed && !harnessUp && !foreground.isEmpty() && !StateDetector.isShell(foreground)) {
            die("refusing to start " + s.harness() + " under handle '" + session + "':"
                    + " its pane is running '" + foreground + "'"
                    + harnessOwningProcess(foreground)
                            .map(h -> " (the " + h + " harness — rerun with --harness " + h
                                    + " to babysit it)")
                            .orElse(" — a launch command typed in there would land in that"
                                    + " program, not a shell")
                    + ". Pick a different --name, or free the pane first");
        }

        // run persists the flags it was given, so a re-run without them rewrites the
        // saved strategy; a silent nudge<->loop flip is the one rewrite that changes
        // what gets typed into the session, so it at least gets said out loud
        if (stored.isPresent() && stored.get().strategy().onIdle() != s.onIdle()) {
            System.out.println("[babysit] note: handle '" + session + "' was saved with --on-idle "
                    + stored.get().strategy().onIdle().name().toLowerCase(java.util.Locale.ROOT)
                    + "; this run rewrites it to --on-idle "
                    + s.onIdle().name().toLowerCase(java.util.Locale.ROOT)
                    + " — re-pass the flag if that is not what you meant");
        }
        // provenance records only what this app launched; adoption keeps whatever claim
        // (possibly none) the handle already carried, because adoption verifies nothing
        store.save(new ConfigStore.SessionConfig(session, o.workdir(), s,
                harnessUp ? recorded : requested, o.model().orElse("")));

        if (!existed) {
            checkStartupDetach(startupControl);
            tmux.newSession(session, o.workdir());
        }
        if (harnessUp) {
            System.out.println("[babysit] " + s.harness() + " already running under handle '"
                    + session + "' — adopting it, not restarting");
        } else {
            String command = launchCommandFor(plugin, o);
            System.out.println("[babysit] " + command + "   (handle: " + session + ")");
            checkStartupDetach(startupControl);
            tmux.sendText(session, command);
            if (!waitForHarness(tmux, plugin, session, startupControl)) {
                die("'" + s.harness() + "' did not start."
                        + " Check it is installed and on PATH, then look with:"
                        + "  harness-stimulator peek " + session);
            }
        }
        boolean loop = s.onIdle() == StrategyConfig.OnIdle.RESET;
        System.out.println("[babysit] " + s.harness() + " is up. Budget: "
                + (s.maxStimuli() == 0 ? "unlimited" : s.maxStimuli()
                        + (loop ? " iterations" : " stimuli"))
                + " (--max-stimuli 0 for unlimited)"
                + (loop ? " — loop mode: context is wiped before every instruction" : ""));
        if (loop) {
            // no opening prompt in loop mode: every instruction, including the first,
            // lands after a context reset — otherwise a --resume'd conversation would
            // get its first instruction in the stale context the mode promises to wipe
            System.out.println("[babysit] loop mode: the first instruction lands after the"
                    + " first context reset, at the first idle");
        } else if (o.prompt().isPresent()) {
            sendOpeningPrompt(tmux, plugin, s, clock, session, o.prompt().get(), startupControl);
        } else {
            System.out.println("[babysit] give it its first instruction with:"
                    + "  harness-stimulator steal " + session);
        }
        attachSession(session, store, tmux, clock);
        } catch (StartupDetach requestedDetach) {
            deleteQuietly(startupControl);
            System.out.println("[babysit] detached during startup; tmux session and harness keep running");
        } finally {
            releaseAttachLock(runLock, self);
        }
    }

    /**
     * Types the opening instruction — but only into a screen the detector calls IDLE.
     * A harness meeting a directory for the first time often opens on a trust dialog,
     * and a prompt typed into a consent dialog is the exact misfire spec.txt forbids;
     * the same caution the stimulation loop applies has to apply to the first message.
     */
    private static void sendOpeningPrompt(Tmux tmux, HarnessPlugin plugin, StrategyConfig s,
                                          Clock clock, String session, String prompt, Path control)
            throws InterruptedException {
        // wait for idle rather than sampling once: a harness paints a banner, checks for
        // updates and settles, all of which reads as WORKING for the first few seconds
        StateDetector detector = new StateDetector(plugin, clock, s.shortQuiescence(), s.longQuiescence());
        Classification c = null;
        for (int i = 0; i < 60; i++) {
            checkStartupDetach(control);
            c = detector.classify(tmux.capture(session));
            if (c.state() == HarnessState.IDLE && c.confidence() == Confidence.HIGH) {
                checkStartupDetach(control);
                tmux.sendText(session, prompt);
                System.out.println("[babysit] sent the opening instruction");
                return;
            }
            Thread.sleep(1000);
        }
        System.out.println("[babysit] NOT sending the opening instruction: after 60s the harness is at "
                + c.state() + ", not an idle prompt (" + c.reason() + ").");
        System.out.println("[babysit] clear it and send the instruction yourself with:"
                + "  harness-stimulator steal " + session);
    }

    /**
     * What actually starts the harness. Resuming is the primary path: this app exists for
     * work where a conversation has accumulated context worth keeping, and its premise is
     * that you already have that context and just need it kept moving. Starting fresh is
     * the fallback.
     */
    private static String launchCommandFor(HarnessPlugin plugin, Options o) {
        String base;
        if (o.resumeLast()) {
            base = plugin.resumeLastCommand().orElseGet(() -> {
                die("harness '" + plugin.name() + "' has no known way to resume its most recent"
                        + " session — start it yourself and adopt it with `setup` + `attach`");
                return null;
            });
        } else if (o.resume().isPresent()) {
            base = plugin.resumeCommand(o.resume().get()).orElseGet(() -> {
                die("harness '" + plugin.name() + "' has no known resume syntax"
                        + " — start it yourself and adopt it with `setup` + `attach`");
                return null;
            });
        } else {
            base = plugin.launchCommand();
        }
        base = applyModel(plugin, base, o.model());
        return base + (o.harnessArgs().isEmpty() ? "" : " " + o.harnessArgs());
    }

    /** Applies a verified model flag while keeping the model value one shell word. */
    static String applyModel(HarnessPlugin plugin, String base, Optional<String> model) {
        if (model.isEmpty()) {
            return base;
        }
        String flag = plugin.modelFlag().orElseThrow(() -> new IllegalArgumentException(
                "harness '" + plugin.name() + "' has no verified model-selection syntax"));
        return base + " " + flag + " " + shellWord(model.get());
    }

    /** POSIX-shell single-quote form; tmux sends the resulting launch command to a shell. */
    static String shellWord(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /**
     * A tmux name nobody has to think about. Two properties are load-bearing. Stable:
     * re-running the same resume must adopt the session already babysitting it rather
     * than start a second. Unique per (harness, conversation): two resumes must never
     * converge on one handle, or the second `run` overwrites the first one's config and
     * — because the adoption check sees the wrong foreground process — types its launch
     * command into the first one's live conversation as a prompt. The {@code bs-} prefix
     * is the app's namespace on top of that: without it a fresh {@code run --harness
     * codex} would derive "codex" and adopt a user's own session of that name.
     */
    static String deriveHandle(HarnessPlugin plugin, Optional<String> resume, boolean resumeLast) {
        if (resumeLast) {
            return "bs-" + plugin.name() + "-last";
        }
        if (resume.isPresent()) {
            String cleaned = resume.get().replaceAll("[^A-Za-z0-9_-]", "-");
            return fitToFilename("bs-" + plugin.name() + "-" + cleaned);
        }
        return "bs-" + plugin.name();
    }

    /**
     * Canonical description of which harness conversation a `run` starts, written as the
     * command flags that ask for it so a provenance-mismatch message is directly
     * actionable. This is the value compared against {@code SessionConfig.conversation}.
     */
    static String conversationKey(String harness, Optional<String> resume, boolean resumeLast) {
        if (resumeLast) {
            return harness + " --last";
        }
        return resume.map(r -> harness + " --resume " + r).orElse(harness + " (fresh)");
    }

    /** The harness whose process owns the pane, if the foreground is one we recognize. */
    private static Optional<String> harnessOwningProcess(String foreground) {
        for (String name : SUPPORTED_HARNESSES.split(", ")) {
            if (pluginFor(name).ownsProcess(foreground)) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    // ~/.harness-stimulator/<handle>.audit.jsonl must fit a 255-byte filename component;
    // tmux itself puts no limit on session names
    static final int MAX_HANDLE_LENGTH = 200;

    /**
     * Truncation alone would reintroduce the collision {@link #deriveHandle} exists to
     * prevent, so an overlong handle keeps a fingerprint of the full value it was cut
     * from.
     */
    private static String fitToFilename(String handle) {
        if (handle.length() <= MAX_HANDLE_LENGTH) {
            return handle;
        }
        String fingerprint = sha256Hex8(handle);
        return handle.substring(0, MAX_HANDLE_LENGTH - fingerprint.length() - 1) + "-" + fingerprint;
    }

    private static String sha256Hex8(String s) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
    }

    /** Polls until the harness process owns the pane, so we never babysit a bare shell. */
    private static boolean waitForHarness(Tmux tmux, HarnessPlugin plugin, String session, Path control)
            throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            checkStartupDetach(control);
            Thread.sleep(500);
            if (plugin.ownsProcess(tmux.capture(session).foregroundCommand())) {
                return true;
            }
        }
        return false;
    }

    private static void checkStartupDetach(Path control) {
        try {
            if (java.nio.file.Files.exists(control)
                    && "detach".equals(java.nio.file.Files.readString(control).strip())) {
                java.nio.file.Files.deleteIfExists(control);
                throw new StartupDetach();
            }
        } catch (java.io.IOException ignored) {
            // The normal loop's control channel will retry once startup completes.
        }
    }

    private static final class StartupDetach extends RuntimeException {
    }

    private static void control(Deque<String> a, ConfigStore store, String command, String effect) {
        String session = require(a, "session name");
        writeControl(store.controlPath(session), command);
        System.out.println(command + " requested for '" + session + "' — " + effect);
        System.out.println("(discarded by the next attach if nothing is attached)");
    }

    /**
     * Gracefully tears down both layers owned by a run. The close process becomes the
     * sole pane owner before it waits or types: an attached stimulator is stopped,
     * allowed to acknowledge that stop, detached, and then replaced under the same
     * lock. The harness-native exit is sent only to a plugin-recognized idle composer;
     * the shell exit is sent only after pane_current_command proves the harness is gone.
     */
    private static void close(Deque<String> a, ConfigStore store, Tmux tmux, Clock clock)
            throws InterruptedException {
        String session = require(a, "session name");
        if (!a.isEmpty()) {
            die("unexpected close argument: " + a.pop());
        }
        ConfigStore.SessionConfig config = store.load(session)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no config for session '" + session + "' — run setup first"));
        HarnessPlugin plugin = pluginFor(config.strategy().harness());
        String harnessExit = plugin.gracefulExitCommand().orElseGet(() -> {
            die("harness '" + plugin.name() + "' has no verified graceful-exit command;"
                    + " refusing to guess or kill it");
            return null;
        });
        if (!tmux.hasSession(session)) {
            System.out.println("session '" + session + "' is already closed");
            return;
        }

        Path lock = store.lockPath(session);
        Path control = store.controlPath(session);
        long self = ProcessHandle.current().pid();
        long pollMillis = Math.max(100L, config.strategy().pollInterval().toMillis());
        java.util.OptionalLong owner = liveLockOwner(lock);
        if (owner.isPresent()) {
            writeControl(control, "stop");
            System.out.println("[close] stopping stimulation for '" + session + "'");
            waitForControlAckOrOwnerExit(control, lock, pollMillis);
            if (liveLockOwner(lock).isPresent()) {
                writeControl(control, "detach");
                System.out.println("[close] detaching the stimulator");
            }
        }

        acquireCloseOwnership(lock, self, pollMillis);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> releaseAttachLock(lock, self)));
        try {
            deleteQuietly(control);
            StrategyConfig strategy = config.strategy();
            StateDetector detector = new StateDetector(plugin, clock,
                    strategy.shortQuiescence(), strategy.longQuiescence());
            AuditLog audit = new AuditLog(store.auditLogPath(session));
            System.out.println("[close] waiting for a high-confidence idle prompt"
                    + " (Ctrl-C cancels; the harness and tmux stay running)");
            CloseReady ready = waitUntilCloseReady(session, tmux, plugin, detector,
                    strategy.pollInterval());
            if (ready == null) {
                System.out.println("[close] session closed before an exit command was needed");
                return;
            }

            tmux.sendText(session, harnessExit);
            audit.record(clock.instant(), "close", ready.classification(), ready.screen(),
                    "graceful harness exit: " + harnessExit);
            System.out.println("[close] sent " + harnessExit + " to " + plugin.name());

            Screen shell = waitForHarnessToExit(session, tmux, plugin, Duration.ofSeconds(30));
            if (shell == null) {
                System.out.println("[close] tmux session ended with the harness");
                return;
            }
            if (!StateDetector.isShell(shell.foregroundCommand())) {
                throw new IllegalStateException("harness exited, but pane foreground is '"
                        + shell.foregroundCommand() + "', not a recognized shell;"
                        + " leaving tmux session '" + session + "' running");
            }
            tmux.sendText(session, "exit");
            audit.record(clock.instant(), "close", ready.classification(), shell,
                    "graceful tmux shell exit");
            waitForTmuxToClose(session, tmux, Duration.ofSeconds(10));
            System.out.println("[close] harness and tmux session '" + session + "' closed cleanly");
        } finally {
            releaseAttachLock(lock, self);
        }
    }

    private record CloseReady(Screen screen, Classification classification) {
    }

    static boolean readyForGracefulExit(HarnessPlugin plugin, Screen screen,
                                        Classification classification, int readWriteClients) {
        return !screen.paneDead()
                && plugin.ownsProcess(screen.foregroundCommand())
                && readWriteClients == 0
                && classification.state() == HarnessState.IDLE
                && classification.confidence() == Confidence.HIGH
                && classification.origin() == Classification.Origin.PLUGIN;
    }

    private static CloseReady waitUntilCloseReady(String session, Tmux tmux,
                                                   HarnessPlugin plugin,
                                                   StateDetector detector,
                                                   Duration pollInterval)
            throws InterruptedException {
        HarnessState lastReported = null;
        while (true) {
            Screen screen = tmux.capture(session);
            if (screen.paneDead()) {
                return null;
            }
            if (!plugin.ownsProcess(screen.foregroundCommand())) {
                throw new IllegalStateException("pane for '" + session + "' is running '"
                        + screen.foregroundCommand() + "', not " + plugin.name()
                        + "; refusing to type an exit command");
            }
            Classification c = detector.classify(screen);
            int clients = tmux.attachedReadWriteClients(session);
            if (readyForGracefulExit(plugin, screen, c, clients)) {
                return new CloseReady(screen, c);
            }
            if (c.state() != lastReported) {
                System.out.println("[close] waiting: " + c.state().name().toLowerCase(
                        java.util.Locale.ROOT) + " (" + c.reason() + ")"
                        + (clients > 0 ? "; a human tmux client is attached" : ""));
                lastReported = c.state();
            }
            Thread.sleep(Math.max(100L, pollInterval.toMillis()));
        }
    }

    private static void waitForControlAckOrOwnerExit(Path control, Path lock, long pollMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + (pollMillis * 4 + 5000L) * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (!java.nio.file.Files.exists(control) || liveLockOwner(lock).isEmpty()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("timed out waiting for the stimulator to stop");
    }

    private static void acquireCloseOwnership(Path lock, long self, long pollMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + (pollMillis * 4 + 5000L) * 1_000_000L;
        while (System.nanoTime() < deadline) {
            java.util.OptionalLong owner = acquireAttachLock(lock, self,
                    pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            if (owner.isEmpty()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("timed out taking ownership from the stimulator");
    }

    /** Null means the tmux session disappeared together with the harness. */
    private static Screen waitForHarnessToExit(String session, Tmux tmux, HarnessPlugin plugin,
                                               Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Screen screen = tmux.capture(session);
            if (screen.paneDead()) {
                return null;
            }
            if (!plugin.ownsProcess(screen.foregroundCommand())) {
                return screen;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException(plugin.name() + " did not exit within " + timeout.toSeconds()
                + " seconds; leaving the tmux session running");
    }

    private static void waitForTmuxToClose(String session, Tmux tmux, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!tmux.hasSession(session)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("shell did not exit within " + timeout.toSeconds()
                + " seconds; tmux session '" + session + "' is still running");
    }

    /**
     * Permanent handoff rather than {@code steal}: with --wait, return only after the
     * stimulation loop has observed DETACH and released its one-owner lock. That makes
     * the caller's subsequent read-write tmux attach race-free.
     */
    private static void detach(Deque<String> a, ConfigStore store) throws InterruptedException {
        String session = require(a, "session name");
        boolean wait = false;
        if (!a.isEmpty()) {
            String option = a.pop();
            if (!"--wait".equals(option)) {
                die("unexpected detach argument: " + option);
            }
            wait = true;
        }
        if (!a.isEmpty()) {
            die("unexpected detach argument: " + a.pop());
        }
        Path lock = store.lockPath(session);
        java.util.OptionalLong owner = liveLockOwner(lock);
        Path control = store.controlPath(session);
        if (owner.isEmpty()) {
            deleteQuietly(control);
            System.out.println("no live stimulator is attached to '" + session + "'");
            return;
        }
        writeControl(control, "detach");
        if (!wait) {
            System.out.println("detach requested for '" + session
                    + "' — attachment will end at the next poll; the tmux session stays running");
            return;
        }
        long pollMillis = store.load(session)
                .map(c -> c.strategy().pollInterval().toMillis()).orElse(2000L);
        long deadline = System.nanoTime() + (pollMillis * 4 + 5000L) * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (liveLockOwner(lock).isEmpty()) {
                deleteQuietly(control);
                System.out.println("stimulator detached from '" + session + "'; tmux is ready for takeover");
                return;
            }
            Thread.sleep(100);
        }
        die("timed out waiting for the stimulator on '" + session + "' to detach");
    }

    private static void writeControl(Path control, String command) {
        try {
            java.nio.file.Files.createDirectories(control.getParent());
            java.nio.file.Files.writeString(control, command + "\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Live owner only; a stale or malformed lock is reported as detached. */
    static java.util.OptionalLong liveLockOwner(Path lock) {
        long pid = lockHolder(lock);
        if (pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            return java.util.OptionalLong.of(pid);
        }
        return java.util.OptionalLong.empty();
    }

    private static void inspect(Deque<String> a, ConfigStore store, Tmux tmux) {
        String session = require(a, "session name");
        boolean json = false;
        if (!a.isEmpty()) {
            String option = a.pop();
            if (!"--json".equals(option)) {
                die("unexpected inspect argument: " + option);
            }
            json = true;
        }
        if (!a.isEmpty()) {
            die("unexpected inspect argument: " + a.pop());
        }
        Optional<ConfigStore.SessionConfig> config = store.load(session);
        java.util.OptionalLong owner = liveLockOwner(store.lockPath(session));
        boolean tmuxLive = tmux.hasSession(session);
        if (json) {
            String harness = config.map(c -> c.strategy().harness()).orElse("");
            String model = config.map(ConfigStore.SessionConfig::model).orElse("");
            int max = config.map(c -> c.strategy().maxStimuli()).orElse(0);
            System.out.println("{\"session\":" + jsonString(session)
                    + ",\"configured\":" + config.isPresent()
                    + ",\"tmuxLive\":" + tmuxLive
                    + ",\"attached\":" + owner.isPresent()
                    + ",\"pid\":" + (owner.isPresent() ? owner.getAsLong() : "null")
                    + ",\"harness\":" + jsonString(harness)
                    + ",\"model\":" + jsonString(model)
                    + ",\"maxStimuli\":" + max + "}");
            return;
        }
        System.out.println("session:    " + session);
        System.out.println("configured: " + config.isPresent());
        System.out.println("tmux live:  " + tmuxLive);
        System.out.println("attached:   " + owner.isPresent()
                + (owner.isPresent() ? " (pid " + owner.getAsLong() + ")" : ""));
        config.ifPresent(c -> {
            System.out.println("harness:    " + c.strategy().harness());
            System.out.println("model:      " + (c.model().isEmpty() ? "inherit" : c.model()));
            System.out.println("max stimuli: " + c.strategy().maxStimuli());
        });
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * Renders the pane by polling {@code capture-pane}, deliberately not by attaching a
     * read-only tmux client. tmux refuses {@code send-keys} whenever the client it
     * resolves for the command is a read-only attachment, so `tmux attach -r` makes
     * every stimulus fail for as long as the peek lasts — the opposite of what peek
     * promises. Targeting the pane by id, using a grouped session, and having a
     * read-write client elsewhere were all tried and all still fail; the incompatibility
     * is in tmux, so peek has to stop creating a client at all.
     *
     * <p>Side benefit: this shows exactly the text the detector classifies from.
     */
    private static void peek(Deque<String> a, Tmux tmux) throws InterruptedException {
        String session = require(a, "session name");
        boolean embedded = false;
        if (!a.isEmpty()) {
            String option = a.pop();
            if (!"--embedded".equals(option)) {
                die("unexpected peek argument: " + option);
            }
            embedded = true;
        }
        if (!a.isEmpty()) {
            die("unexpected peek argument: " + a.pop());
        }
        if (!tmux.hasSession(session)) {
            die("tmux session '" + session + "' does not exist");
        }
        if (!embedded) {
            System.out.println("peeking '" + session + "' — read-only, refreshing every second."
                    + " Ctrl-C to stop. No tmux client is attached, so the stimulator is untouched.");
            Thread.sleep(1200);
        }
        String header = "── peek " + session + " (read-only" + (embedded ? "" : ", Ctrl-C to stop") + ") "
                + "─".repeat(20) + "\n";
        while (true) {
            Screen screen = tmux.capture(session);
            if (screen.paneDead()) {
                System.out.println("session '" + session + "' is gone or its pane died — ending peek");
                return;
            }
            System.out.print("\u001b[H\u001b[2J" + header + screen.text());
            System.out.flush();
            Thread.sleep(1000);
        }
    }

    private static void steal(Deque<String> a, ConfigStore store, Tmux tmux) throws InterruptedException {
        String session = require(a, "session name");
        if (!tmux.hasSession(session)) {
            die("tmux session '" + session + "' does not exist");
        }
        Path control = store.controlPath(session);
        long pollMillis = store.load(session)
                .map(c -> c.strategy().pollInterval().toMillis()).orElse(2000L);
        // the control file is a single slot: overwriting an unconsumed command (a
        // `stop` issued a moment ago, as `stop && steal` scripts do) would silently
        // discard it, and with it the promise that the stop sticks after release.
        // Wait for the stimulator to consume it first; if nothing does, nothing is
        // attached and overwriting matches the documented discard-on-next-attach
        long clobberDeadline = System.nanoTime() + (pollMillis * 3 + 2000) * 1_000_000L;
        while (java.nio.file.Files.exists(control) && System.nanoTime() < clobberDeadline) {
            Thread.sleep(200);
        }
        try {
            java.nio.file.Files.createDirectories(control.getParent());
            java.nio.file.Files.writeString(control, "steal\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        // the control file is consumed-on-read, so its disappearance is the
        // stimulator's acknowledgment; wait a few polls before concluding nothing
        // was attached
        long deadline = System.nanoTime() + (pollMillis * 3 + 2000) * 1_000_000L;
        boolean consumed = false;
        while (System.nanoTime() < deadline) {
            if (!java.nio.file.Files.exists(control)) {
                consumed = true;
                break;
            }
            Thread.sleep(200);
        }
        if (consumed) {
            System.out.println("stimulator holding — the session is yours;"
                    + " it resumes automatically when you detach (ctrl-b d)."
                    + " If a stop was requested it stays observe-only instead,"
                    + " until `resume " + session + "`");
        } else {
            deleteQuietly(control);
            System.out.println("no stimulator consumed the steal request (none attached?) — attaching anyway");
        }
        execTmuxAttach(session);
    }

    /** Read-write attach, used by steal. Peek deliberately does not attach at all. */
    private static void execTmuxAttach(String session) throws InterruptedException {
        List<String> cmd = List.of("tmux", "attach-session", "-t", "=" + session);
        try {
            int code = new ProcessBuilder(cmd).inheritIO().start().waitFor();
            if (code != 0) {
                die("tmux attach failed (exit " + code + ") — steal needs a real terminal");
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void attach(Deque<String> a, ConfigStore store, Tmux tmux, Clock clock) throws InterruptedException {
        attachSession(require(a, "session name"), store, tmux, clock);
    }

    private static void attachSession(String session, ConfigStore store, Tmux tmux, Clock clock)
            throws InterruptedException {
        ConfigStore.SessionConfig config = store.load(session)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no config for session '" + session + "' — run setup first"));
        StrategyConfig s = config.strategy();
        HarnessPlugin plugin = pluginFor(s.harness());
        // re-derived from the plugin on every attach rather than persisted, so a
        // plugin update that changes the command is never shadowed by stale config
        Optional<String> contextReset = plugin.resetCommand();
        if (s.onIdle() == StrategyConfig.OnIdle.RESET) {
            if (contextReset.isEmpty()) {
                die("session '" + session + "' is configured for --on-idle reset, but harness '"
                        + s.harness() + "' has no known context-reset command");
            }
            // the CLI can't produce this combination, but the properties file is
            // user-editable: a hand-written onIdle=reset with no idleMessage would
            // loop the conversational default nudge into a freshly wiped context
            if (s.idleMessage().equals(StrategyConfig.defaults().idleMessage())) {
                die("session '" + session + "' is configured for --on-idle reset, but its"
                        + " idleMessage is the default conversational nudge, which is useless in a"
                        + " context that is wiped every iteration. Set idleMessage in the session's"
                        + " properties file to a self-contained instruction, or re-run with"
                        + " --on-idle reset --prompt <text>");
            }
        }
        if (!tmux.hasSession(session)) {
            die("tmux session '" + session + "' does not exist");
        }
        // one stimulator per session: two loops on one pane double-spend the budget and
        // corrupt each other's echo probes (A's probe char breaks B's byte-for-byte
        // revert check, tripping B's sticky fallback veto on a dialog that isn't there)
        Path lock = store.lockPath(session);
        long self = ProcessHandle.current().pid();
        boolean preowned = lockHolder(lock) == self;
        java.util.OptionalLong owner = preowned
                ? java.util.OptionalLong.empty()
                : acquireAttachLock(lock, self,
                        pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        if (owner.isPresent()) {
            die("a stimulator (pid " + owner.getAsLong() + ") is already attached to '" + session
                    + "' — watch it with: harness-stimulator peek " + session
                    + ", or ask it to exit with: harness-stimulator detach " + session);
        }
        // Ctrl-C skips finally blocks, so release on shutdown too; a dead pid also makes
        // the lock sweepable, this just keeps the directory tidy
        Runtime.getRuntime().addShutdownHook(new Thread(() -> releaseAttachLock(lock, self)));
        try {
            // Re-attach always re-classifies from scratch (fresh detector) — the user may
            // have typed into the session while we were detached.
            StateDetector detector = new StateDetector(plugin, clock,
                    s.shortQuiescence(), s.longQuiescence());
            AuditLog audit = new AuditLog(store.auditLogPath(session));
            // a stale control request from a previous session must not act on this one
            Path control = store.controlPath(session);
            if (!preowned) {
                deleteQuietly(control);
            }
            Stimulator.ControlChannel channel = () -> consumeCommand(control);
            Stimulator.StatusSink statusSink = (sent, max) -> store.saveStatus(session, sent, max);
            // written before the loop starts so a reader sees the full budget immediately
            // on attach, not only after the first stimulus lands (which may be minutes away)
            statusSink.update(0, s.maxStimuli());
            Stimulator stimulator = new Stimulator(session, tmux, detector, s, contextReset,
                    audit, Notifier.console(), clock, Stimulator.Pauser.REAL, channel, statusSink);
            System.out.println("[babysit] attached to '" + session + "' (harness=" + s.harness()
                    + "); peek read-only with: harness-stimulator peek " + session
                    + "; take over with: harness-stimulator steal " + session);
            Stimulator.Outcome outcome = stimulator.run();
            deleteQuietly(control);
            store.deleteStatus(session);
            System.out.println("[babysit] detached: " + outcome);
        } finally {
            releaseAttachLock(lock, self);
        }
    }

    /**
     * Takes the attach lock or reports who holds it. A lock owned by a dead pid is swept
     * and retaken — Ctrl-C is the normal way a stimulator dies, so stale locks are the
     * expected case, not corruption. Creation is atomic ({@code CREATE_NEW}), so two
     * simultaneous attaches race safely: exactly one wins, the other sees the winner.
     */
    static java.util.OptionalLong acquireAttachLock(Path lock, long selfPid,
                                                    java.util.function.LongPredicate pidAlive) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                java.nio.file.Files.createDirectories(lock.getParent());
                java.nio.file.Files.writeString(lock, Long.toString(selfPid),
                        java.nio.file.StandardOpenOption.CREATE_NEW);
                return java.util.OptionalLong.empty();
            } catch (java.nio.file.FileAlreadyExistsException e) {
                long holder = lockHolder(lock);
                if (holder > 0 && pidAlive.test(holder)) {
                    return java.util.OptionalLong.of(holder);
                }
                deleteQuietly(lock);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        // swept a stale lock twice and still lost the recreate race — someone else won
        long holder = lockHolder(lock);
        return java.util.OptionalLong.of(holder > 0 ? holder : -1);
    }

    /** Deletes the lock only if this pid still owns it — never a successor's lock. */
    static void releaseAttachLock(Path lock, long selfPid) {
        if (lockHolder(lock) == selfPid) {
            deleteQuietly(lock);
        }
    }

    private static long lockHolder(Path lock) {
        try {
            return Long.parseLong(java.nio.file.Files.readString(lock).strip());
        } catch (java.io.IOException | NumberFormatException e) {
            return -1;
        }
    }

    private static void status(Deque<String> a, ConfigStore store, Tmux tmux, Clock clock) throws InterruptedException {
        String session = require(a, "session name");
        StrategyConfig s = store.load(session).map(ConfigStore.SessionConfig::strategy)
                .orElse(StrategyConfig.defaults());
        StateDetector detector = new StateDetector(pluginFor(s.harness()), clock,
                s.shortQuiescence(), s.longQuiescence());
        // Two captures so quiescence has a baseline; a single capture always reads as WORKING.
        detector.classify(tmux.capture(session));
        Thread.sleep(Duration.ofSeconds(Math.max(1, s.shortQuiescence().toSeconds())));
        Screen screen = tmux.capture(session);
        Classification c = detector.classify(screen);
        System.out.println("session:    " + session);
        System.out.println("state:      " + c.state() + " (" + c.confidence() + ")");
        System.out.println("reason:     " + c.reason());
        List<String> tail = screen.tailLines(5);
        System.out.println("screen tail:");
        tail.forEach(l -> System.out.println("  | " + l));
    }

    private static Stimulator.Command consumeCommand(Path file) {
        try {
            if (!java.nio.file.Files.exists(file)) {
                return null;
            }
            String word = java.nio.file.Files.readString(file).strip();
            java.nio.file.Files.deleteIfExists(file);
            return switch (word) {
                case "stop" -> Stimulator.Command.STOP;
                case "resume" -> Stimulator.Command.RESUME;
                case "detach" -> Stimulator.Command.DETACH;
                case "steal" -> Stimulator.Command.STEAL;
                default -> null;
            };
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (java.io.IOException e) {
            // best effort — a leftover control file only matters until the next attach clears it
        }
    }

    private static HarnessPlugin pluginFor(String harness) {
        return switch (harness) {
            case "cursor-agent" -> new CursorAgentPlugin();
            case "claude-code" -> new ClaudeCodePlugin();
            case "codex" -> new CodexPlugin();
            default -> throw new IllegalArgumentException(
                    "no plugin for harness '" + harness + "' (supported: " + SUPPORTED_HARNESSES + ")");
        };
    }

    private static String require(Deque<String> a, String what) {
        if (a.isEmpty()) {
            die("missing " + what);
        }
        return a.pop();
    }

    private static void die(String message) {
        System.err.println("error: " + message);
        System.exit(2);
        throw new IllegalStateException("unreachable");
    }

    private Main() {
    }
}

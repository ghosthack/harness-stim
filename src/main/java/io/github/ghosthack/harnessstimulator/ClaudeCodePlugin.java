package io.github.ghosthack.harnessstimulator;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Textual patterns for the Claude Code TUI. Same contract as the cursor-agent
 * plugin: these rot with harness updates and empty means "fall back to the
 * behavioral heuristics". Order matters — working beats approval (a frozen
 * long tool run must not be probed), approval beats question (approval dialogs
 * end with '?'), question beats idle (the input box is visible below dialogs).
 */
public final class ClaudeCodePlugin implements HarnessPlugin {

    static final String PATTERNS_VERSION = "2026-08-17.6";

    // shown while a turn is running; the screen can freeze for the length of a
    // long tool call, so without this a busy harness hits the 30s idle fallback
    // (and Claude Code queues typed text while working, so the echo probe would
    // pass and the stimulus would land as a queued message mid-turn)
    private static final Pattern WORKING_INDICATOR = Pattern.compile(
            "(?i)esc to interrupt");

    private static final Pattern APPROVAL = Pattern.compile(
            "(?i)(do you want to|don't ask again|[❯›]?\\s*1\\.\\s*yes\\b"
                    + "|allow this|\\[y/n]|\\(y/n\\))");

    // AskUserQuestion and other option selectors: a caret on a numbered row;
    // checked after APPROVAL so Yes/No permission dialogs don't land here
    private static final Pattern OPTION_SELECTOR = Pattern.compile(
            "[❯›]\\s*\\d+\\.\\s");

    // main agent idle while subagents/background shells still run: the turn has
    // ended (no esc-to-interrupt) and the screen is quiescent (the agent timer does
    // not redraw), so both generic layers read it as idle — but a nudge would land
    // in an agent that is deliberately waiting. Only the live UI region BELOW the
    // input caret is checked: transcript text above it retains stale lines (the
    // "✻ Waiting for N background agents" banner persists after completion), while
    // the "· N shell ·" status segment and "◯" agents-panel rows disappear when
    // the background work finishes.
    private static final Pattern BACKGROUND_WORK = Pattern.compile(
            "(?im)(·\\s*\\d+\\s+shells?\\s*·|^\\s*◯\\s+\\S+)");

    private static final Pattern INPUT_LINE = Pattern.compile(
            "(^[\\s\\u00A0]*❯|^[\\s\\u00A0]*│[\\s\\u00A0]*>)");

    // idle is detected from the status bar, not the input line: Claude Code renders
    // a dim history-derived ghost suggestion inside the input caret once any prompt
    // has been sent, so "caret is empty" stops being true after the first stimulus
    // (and capture-pane cannot tell dim ghost text from a real draft). The status
    // hints below only render when no turn is running; a running turn shows
    // "esc to interrupt", which is classified WORKING before this check.
    // Bare-caret note: the caret is padded with a NO-BREAK SPACE (U+00A0), which \s ignores.
    private static final Pattern IDLE_STATUS = Pattern.compile(
            "(shift\\+tab to cycle|\\? for shortcuts|auto mode on|accept edits on"
                    + "|^[\\s\\u00A0]*│?[\\s\\u00A0]*>[\\s\\u00A0]*│?[\\s\\u00A0]*$"
                    + "|^[\\s\\u00A0]*❯[\\s\\u00A0]*$)");

    private static final Pattern BROKEN = Pattern.compile(
            "(?i)(claude: command not found|panic:|traceback \\(most recent call last\\)"
                    + "|segmentation fault|fatal error)");

    // box-drawing borders (input box and dialogs), horizontal rules, hint bars,
    // the bare input caret, and the emoji status line
    private static final Pattern CHROME = Pattern.compile(
            "(?i)(^\\s*[╭╰│⏵❯]|^\\s*─+\\s*$|\\? for shortcuts|esc to |shift\\+tab"
                    + "|auto-accept|plan mode|bypass permissions|tokens?\\s*$|📁|🧠)");

    @Override
    public String name() {
        return "claude-code";
    }

    @Override
    public String processName() {
        return "claude";
    }

    // the launcher execs the versioned binary, so tmux reports e.g. "2.1.235"
    // (verified live against Claude Code 2.1.235); no other TUI names its process
    // like a bare version, so the shape is safe to claim
    private static final Pattern VERSIONED_BINARY = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    @Override
    public boolean ownsProcess(String foregroundCommand) {
        return "claude".equals(foregroundCommand)
                || VERSIONED_BINARY.matcher(foregroundCommand).matches();
    }

    @Override
    public String launchCommand() {
        return "claude";
    }

    @Override
    public Optional<String> modelFlag() {
        return Optional.of("--model");
    }

    @Override
    public Optional<String> resumeCommand(String harnessSession) {
        return Optional.of("claude --resume " + harnessSession);
    }

    @Override
    public Optional<String> resumeLastCommand() {
        return Optional.of("claude --continue");
    }

    // typing "/clear" opens the slash-command menu with the exact match selected, so
    // the Enter that sendText appends commits /clear itself; the 400ms text-to-Enter
    // delay in RealTmux gives the menu time to settle first. NOT yet grounded against
    // a live loop run — if the menu ranks another command first, the misfire lands in
    // the audit log with the screen it was typed into
    @Override
    public Optional<String> resetCommand() {
        return Optional.of("/clear");
    }

    /** Verified live against Claude Code 2.1.239: /exit returns to the pane shell. */
    @Override
    public Optional<String> gracefulExitCommand() {
        return Optional.of("/exit");
    }

    @Override
    public String patternsVersion() {
        return PATTERNS_VERSION;
    }

    @Override
    public Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor) {
        List<String> tail = screen.tailLines(20);
        String tailText = String.join("\n", tail);

        if (BROKEN.matcher(tailText).find()) {
            return Optional.of(new Classification(HarnessState.BROKEN, Confidence.HIGH,
                    "error signature on screen (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        if (WORKING_INDICATOR.matcher(tailText).find()) {
            return Optional.of(new Classification(HarnessState.WORKING, Confidence.HIGH,
                    "esc-to-interrupt visible: turn still running despite quiescent screen (patterns "
                            + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        if (APPROVAL.matcher(tailText).find()) {
            return Optional.of(new Classification(HarnessState.WAITING_FOR_APPROVAL, Confidence.HIGH,
                    "permission dialog shape (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        if (OPTION_SELECTOR.matcher(tailText).find()) {
            return Optional.of(new Classification(HarnessState.WAITING_FOR_QUESTION, Confidence.HIGH,
                    "option selector widget (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        List<String> content = tail.stream().filter(l -> !CHROME.matcher(l).find()).toList();
        if (!content.isEmpty() && content.getLast().stripTrailing().endsWith("?")) {
            return Optional.of(new Classification(HarnessState.WAITING_FOR_QUESTION, Confidence.HIGH,
                    "quiescent with trailing question above UI chrome (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        // checked after approval/question so a subagent's surfaced permission
        // dialog still classifies as approval even while background work runs
        int lastInput = -1;
        for (int i = 0; i < tail.size(); i++) {
            if (INPUT_LINE.matcher(tail.get(i)).find()) {
                lastInput = i;
            }
        }
        if (lastInput >= 0) {
            String belowInput = String.join("\n", tail.subList(lastInput + 1, tail.size()));
            if (BACKGROUND_WORK.matcher(belowInput).find()) {
                return Optional.of(new Classification(HarnessState.WORKING, Confidence.HIGH,
                        "main idle but subagents/background work running (patterns " + PATTERNS_VERSION + ")",
                        Classification.Origin.PLUGIN));
            }
        }
        if (tail.stream().anyMatch(l -> IDLE_STATUS.matcher(l).find())) {
            return Optional.of(new Classification(HarnessState.IDLE, Confidence.HIGH,
                    "idle status bar, no running-turn indicator (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        return Optional.empty();
    }
}

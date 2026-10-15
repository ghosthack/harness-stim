package io.github.ghosthack.harnessstimulator;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Textual patterns for the OpenAI Codex CLI TUI (captured against codex-cli 0.147.0).
 * Same contract as the other plugins: these rot with harness updates and empty means
 * "fall back to the behavioral heuristics".
 *
 * <p>Two things make codex differ from the cursor-agent/claude-code plugins:
 *
 * <ul>
 *   <li>Codex renders <em>inline</em>, not on the alternate screen, and its running-turn
 *       line carries a live elapsed-time counter ("• Working (5s • esc to interrupt)").
 *       A running turn therefore never goes quiescent, so the behavioral layer already
 *       reads WORKING and this plugin is rarely consulted mid-turn; the esc-to-interrupt
 *       check below is the safety net for a turn whose render does freeze.
 *   <li>Codex puts non-agent modals in the same numbered-selector widget it uses for
 *       permission prompts — directory-trust on startup, rate-limit "switch model?"
 *       nudges. All of them are consent decisions a human must make, and typing free
 *       text into one is exactly the misfire spec.txt forbids, so an unrecognized
 *       numbered selector classifies as WAITING_FOR_APPROVAL (notify-only) rather than
 *       WAITING_FOR_QUESTION (auto-answerable) — the opposite of the claude-code plugin,
 *       whose selector widget is AskUserQuestion.
 * </ul>
 */
public final class CodexPlugin implements HarnessPlugin {

    static final String PATTERNS_VERSION = "2026-08-17.4";

    private static final Pattern AUTO_RESOLVE_COUNTDOWN = Pattern.compile(
            "(?i)auto-resolves in \\d+s");

    // "• Working (5s • esc to interrupt)" — verified live, including through a 45s tool
    // call, where the counter keeps ticking rather than freezing.
    //
    // Must match the whole "Working (… esc to interrupt)" shape, not the bare hint:
    // the request_user_input widget ends with "tab to add notes | enter to submit
    // answer | esc to interrupt", so a bare match classifies a pending question as a
    // running turn and the human is never told the harness is waiting on them.
    private static final Pattern WORKING_INDICATOR = Pattern.compile(
            "(?i)Working\\s*\\([^)]*esc to interrupt");

    // verified live: exec approval ("Would you like to run the following command?"),
    // patch approval ("Would you like to make the following edits?"), both followed by
    // "Press enter to confirm or esc to cancel", and the startup directory-trust prompt
    // ("Do you trust the contents of this directory?" + "Press enter to continue").
    // The remaining alternatives are lifted from the 0.147.0 binary's string table and
    // are NOT yet confirmed against a live render — they can only add approvals, and
    // approval is the notify-only state, so an over-match here is the safe direction.
    private static final Pattern APPROVAL_EXPLICIT = Pattern.compile(
            "(?i)(would you like to (run the following command|make the following edits)"
                    + "|do you trust the contents of this directory"
                    + "|press enter to (confirm|continue)"
                    + "|needs your approval"
                    + "|do you want to approve network access to"
                    + "|codex wants to edit)");

    // request_user_input widget (tui/src/bottom_pane/request_user_input). Derived from
    // the binary's string table, NOT yet confirmed against a live render — see
    // codex-harness-test-plan.md. Checked after APPROVAL_EXPLICIT so a verified consent
    // phrase always wins; if these strings are wrong the screen falls through to the
    // generic selector below and is treated as approval, which is notify-only.
    private static final Pattern QUESTION_WIDGET = Pattern.compile(
            "(?i)(enter to submit answer|\\d+ unanswered|auto-resolves in|^\\s*Question \\d+/\\d+\\b)",
            Pattern.MULTILINE);

    // "› 1. Yes, proceed (y)" — the caret is U+203A. Covers the exec approval, the
    // directory-trust prompt, and codex's own modals (rate-limit model-switch nudge).
    private static final Pattern OPTION_SELECTOR = Pattern.compile(
            "^\\s*›\\s*\\d+\\.\\s");

    // narrow on purpose: a coding agent's transcript is full of "command not found"
    // and "fatal error" from commands it ran, so only codex's own death counts.
    // Both shell spellings — bash says "codex: command not found", zsh says
    // "command not found: codex".
    private static final Pattern BROKEN = Pattern.compile(
            "(?i)(codex: command not found|command not found: codex(?![\\w-])"
                    + "|killed\\s+codex\\b"
                    + "|agent errored:"
                    + "|failed to start codex session:)");

    // Idle is read off the composer+status-footer layout at the bottom of the screen,
    // never off the caret being empty: codex renders a rotating dim placeholder inside
    // the caret ("› Explain this codebase", "› Find and fix a bug in @filename") and
    // capture-pane cannot tell ghost text from a real draft.
    //
    // The footer's *contents* are user configuration — [tui] status_line picks from
    // ~20 components (current-dir, context-remaining, codex-version, fast-mode, …) —
    // so matching its text alone would make idle detection depend on the operator's
    // config. The structural facts are what hold: the composer line is the last or
    // second-to-last non-blank line, and codex's modal dialogs replace that whole
    // region rather than drawing above it, so a visible composer means no modal.
    private static final Pattern COMPOSER_LINE = Pattern.compile("^\\s*›");

    // /goal is codex's own self-loop. "Pursuing goal (7s)" rides alongside the normal
    // running-turn line so it needs no pattern, and "Goal achieved (11s)" freezes at its
    // final duration and reads as ordinary idle — both verified live. These three
    // variants are the ones a nudge cannot fix: the session is idle-looking but what it
    // needs is `/goal resume` from a human, not another message. Wording is verbatim
    // from the 0.147.0 string table; the footer position is verified, the exact variants
    // are not, and a miss here simply falls through to idle.
    private static final Pattern GOAL_NEEDS_HUMAN = Pattern.compile(
            "(?i)Goal (paused|stalled|hit usage limits|abandoned)");

    // fast path for the default-ish footers, so idle still resolves if the composer
    // line itself is scrolled or styled unexpectedly
    private static final Pattern FOOTER_COMPONENT = Pattern.compile(
            "(?i)(Context\\s+\\d+%\\s+left|\\d+%\\s+context|\\d+K\\s+window"
                    + "|Fast\\s+(on|off)|\\bv?\\d+\\.\\d+\\.\\d+\\b)");

    // everything that is UI furniture rather than agent speech, stripped before the
    // trailing-question-mark check: composer/echoed prompts/selector carets (all "›"),
    // horizontal rules, the welcome box, tool-output tree rows, option rows, the
    // command preview, the approval receipt, the tip and quota banners, and the footer
    private static final Pattern CHROME = Pattern.compile(
            "(?i)(^\\s*›"
                    + "|^\\s*─+\\s*$"
                    + "|^\\s*[╭╮╰╯│└]"
                    + "|^\\s*\\d+\\.\\s"
                    + "|^\\s*\\$\\s"
                    + "|^\\s*✔"
                    + "|^\\s*⚠"
                    + "|^\\s*Tip:"
                    + "|^\\s*Environment:\\s"
                    + "|press enter to "
                    + "|esc to interrupt"
                    + "|Context\\s+\\d+%\\s+left)");

    @Override
    public String name() {
        return "codex";
    }

    @Override
    public String launchCommand() {
        return "codex";
    }

    @Override
    public Optional<String> modelFlag() {
        return Optional.of("--model");
    }

    /** {@code codex resume [SESSION_ID] [PROMPT]} — takes a UUID or a session name. */
    @Override
    public Optional<String> resumeCommand(String harnessSession) {
        return Optional.of("codex resume " + harnessSession);
    }

    @Override
    public Optional<String> resumeLastCommand() {
        return Optional.of("codex resume --last");
    }

    /**
     * {@code /new} starts a fresh conversation in place; the process stays up. Like the
     * question-widget strings, NOT yet grounded against a live loop run — a misfire
     * lands in the audit log with the screen it was typed into.
     */
    @Override
    public Optional<String> resetCommand() {
        return Optional.of("/new");
    }

    @Override
    public String patternsVersion() {
        return PATTERNS_VERSION;
    }

    /**
     * "Question 1/1 (1 unanswered) · auto-resolves in 52s" repaints every second while
     * codex waits for an answer, and auto-answers when it reaches zero. Without
     * normalizing the counter away the screen never goes quiescent, the plugin is never
     * consulted, and the whole question episode reads as a running turn.
     */
    @Override
    public String stripVolatile(String screenText) {
        return AUTO_RESOLVE_COUNTDOWN.matcher(screenText).replaceAll("auto-resolves in <n>s");
    }

    @Override
    public Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor) {
        List<String> tail = screen.tailLines(20);
        String tailText = String.join("\n", tail);

        if (BROKEN.matcher(tailText).find()) {
            return Optional.of(classified(HarnessState.BROKEN, "error signature on screen"));
        }
        if (WORKING_INDICATOR.matcher(tailText).find()) {
            return Optional.of(classified(HarnessState.WORKING,
                    "esc-to-interrupt visible: turn still running despite quiescent screen"));
        }
        if (APPROVAL_EXPLICIT.matcher(tailText).find()) {
            return Optional.of(classified(HarnessState.WAITING_FOR_APPROVAL, "consent dialog shape"));
        }
        if (QUESTION_WIDGET.matcher(tailText).find()) {
            // not text-answerable: typing into it navigates the option list instead of
            // entering text, and the trailing Enter commits whatever ends up highlighted
            return Optional.of(new Classification(HarnessState.WAITING_FOR_QUESTION, Confidence.HIGH,
                    "request-user-input selector widget (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN, false));
        }
        if (tail.stream().anyMatch(l -> OPTION_SELECTOR.matcher(l).find())) {
            return Optional.of(classified(HarnessState.WAITING_FOR_APPROVAL,
                    "unrecognized numbered selector — treated as a consent decision, never auto-answered"));
        }
        List<String> content = tail.stream().filter(l -> !CHROME.matcher(l).find()).toList();
        if (!content.isEmpty() && content.getLast().stripTrailing().endsWith("?")) {
            return Optional.of(classified(HarnessState.WAITING_FOR_QUESTION,
                    "quiescent with trailing question above UI chrome"));
        }
        if (GOAL_NEEDS_HUMAN.matcher(tailText).find()) {
            return Optional.of(classified(HarnessState.UNRECOGNIZED_PROMPT,
                    "goal loop is paused/stalled — needs `/goal resume` from a human, not a nudge"));
        }
        if (composerVisible(tail)) {
            return Optional.of(classified(HarnessState.IDLE,
                    "composer and status footer present, no running-turn indicator"));
        }
        return Optional.empty();
    }

    /**
     * True when the bottom of the screen is codex's composer region rather than a modal.
     * Accepts the three shapes the configurable status line can produce: a recognizable
     * footer component on the last line, a composer line directly above an indented
     * footer, or a composer line as the last line (status_line configured empty).
     */
    private static boolean composerVisible(List<String> tail) {
        if (tail.isEmpty()) {
            return false;
        }
        String last = tail.getLast();
        if (COMPOSER_LINE.matcher(last).find()) {
            return true;
        }
        if (FOOTER_COMPONENT.matcher(last).find()) {
            return true;
        }
        return tail.size() >= 2
                && COMPOSER_LINE.matcher(tail.get(tail.size() - 2)).find()
                && !last.isBlank()
                && Character.isWhitespace(last.charAt(0));
    }

    private static Classification classified(HarnessState state, String reason) {
        return new Classification(state, Confidence.HIGH,
                reason + " (patterns " + PATTERNS_VERSION + ")", Classification.Origin.PLUGIN);
    }
}

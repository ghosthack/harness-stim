package io.github.ghosthack.harnessstimulator;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Textual patterns for the cursor-agent TUI. These WILL rot as cursor-agent
 * updates; that is expected — the detector falls back to behavioral heuristics
 * when nothing here matches. Order matters: approval prompts often end with a
 * question mark, so approval is checked before question.
 */
public final class CursorAgentPlugin implements HarnessPlugin {

    static final String PATTERNS_VERSION = "2026-08-17.5";

    private static final Pattern APPROVAL = Pattern.compile(
            "(?i)(\\[y/n]|\\(y/n\\)|\\by/N\\b|\\byes/no\\b|allow this|allow\\?"
                    + "|run this command|run command\\?|press enter to (confirm|continue|approve)"
                    + "|do you want to (run|proceed|continue|allow)"
                    + "|[▸❯>]\\s*(yes|allow|approve))");

    private static final Pattern IDLE_INPUT_BOX = Pattern.compile(
            "(?i)(^\\s*[❯>]\\s*$|type a message|add a follow-up|send a message)");

    // main agent idle while background subagents run: cursor-agent shows an
    // "N tasks" line in the live status region below the input box while they
    // run; it disappears on completion. Matched only below the last input line —
    // transcript text above it could mention "N tasks" and would match forever.
    private static final Pattern BACKGROUND_TASKS = Pattern.compile(
            "(?i)^\\s*\\d+\\s+tasks?\\s*$");

    private static final Pattern INPUT_LINE = Pattern.compile(
            "(?i)(add a follow-up|type a message|send a message|^\\s*[❯>]\\s*$)");

    private static final Pattern BROKEN = Pattern.compile(
            "(?i)(cursor-agent: command not found|panic:|traceback \\(most recent call last\\)"
                    + "|segmentation fault|fatal error)");

    // cursor-agent asks questions via a multiple-choice selector widget (checkbox
    // options, "Other: (type to answer)", a Space/Enter hint bar) — typed text lands
    // in the Other field, so a plain-text answer still works as the reply mechanism
    private static final Pattern QUESTION_WIDGET = Pattern.compile(
            "(?i)(enter next/submit|space select|\\(type to answer\\)|[›▸]\\s*\\[[ x]\\])");

    // cursor-agent renders an input box, status line, and key hints BELOW the chat,
    // so the question check must look at the last content line above this chrome
    private static final Pattern CHROME = Pattern.compile(
            "(?i)(^\\s*[→▸❯>]?\\s*(add a follow-up|type a message|send a message)"
                    + "|\\s·\\s"
                    + "|ctrl\\+\\w"
                    + "|^\\s*tip:"
                    // workdir footer; outside a git repo it is a bare path with no "· branch" suffix
                    + "|^\\s*[/~]\\S*\\s*$"
                    + "|^\\s*[⠀-⣿]+\\s*working)");

    @Override
    public String name() {
        return "cursor-agent";
    }

    @Override
    public String launchCommand() {
        return "cursor-agent";
    }

    // resumeCommand/resumeLastCommand/resetCommand deliberately left unimplemented:
    // cursor-agent was not available to check the syntax against, and a guessed command
    // would silently start (or wipe) the wrong conversation. Empty makes the CLI say so
    // instead — loop mode refuses this harness until a reset command is verified live.

    @Override
    public String patternsVersion() {
        return PATTERNS_VERSION;
    }

    @Override
    public Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor) {
        List<String> tail = screen.tailLines(15);
        String tailText = String.join("\n", tail);

        if (BROKEN.matcher(tailText).find()) {
            return Optional.of(new Classification(HarnessState.BROKEN, Confidence.HIGH,
                    "error signature on screen (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        if (APPROVAL.matcher(tailText).find()) {
            return Optional.of(new Classification(HarnessState.WAITING_FOR_APPROVAL, Confidence.HIGH,
                    "approval prompt shape (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        if (QUESTION_WIDGET.matcher(tailText).find()) {
            return Optional.of(new Classification(HarnessState.WAITING_FOR_QUESTION, Confidence.HIGH,
                    "question selector widget (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        List<String> content = tail.stream().filter(l -> !CHROME.matcher(l).find()).toList();
        if (!content.isEmpty() && content.getLast().stripTrailing().endsWith("?")) {
            return Optional.of(new Classification(HarnessState.WAITING_FOR_QUESTION, Confidence.HIGH,
                    "quiescent with trailing question above UI chrome (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        int lastInput = -1;
        for (int i = 0; i < tail.size(); i++) {
            if (INPUT_LINE.matcher(tail.get(i)).find()) {
                lastInput = i;
            }
        }
        if (lastInput >= 0 && tail.subList(lastInput + 1, tail.size()).stream()
                .anyMatch(l -> BACKGROUND_TASKS.matcher(l).find())) {
            return Optional.of(new Classification(HarnessState.WORKING, Confidence.HIGH,
                    "main idle but background tasks running (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        if (tail.stream().anyMatch(l -> IDLE_INPUT_BOX.matcher(l).find())) {
            return Optional.of(new Classification(HarnessState.IDLE, Confidence.HIGH,
                    "empty input box (patterns " + PATTERNS_VERSION + ")",
                    Classification.Origin.PLUGIN));
        }
        return Optional.empty();
    }
}

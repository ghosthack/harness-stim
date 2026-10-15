package io.github.ghosthack.harnessstimulator;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Detector tests against realistic Claude Code TUI screen shapes. */
class ClaudeCodePluginTest {

    private static final Duration SHORT = Duration.ofSeconds(5);
    private static final Duration LONG = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock();
    private final StateDetector detector =
            new StateDetector(new ClaudeCodePlugin(), clock, SHORT, LONG);

    private Classification classifyQuiescent(String text, Duration quiet) {
        Screen s = new Screen(text, false, Instant.EPOCH);
        detector.classify(s);
        clock.advance(quiet);
        return detector.classify(s);
    }

    @Test
    void frozenLongToolRunStaysWorkingEvenPastLongQuiescence() {
        // during a long bash call the screen can freeze entirely; without the
        // esc-to-interrupt marker this would hit the generic idle fallback
        Classification c = classifyQuiescent("""
                ● Bash(mvn package)
                  ⎿  Running…
                · Wrangling… (esc to interrupt)""", Duration.ofSeconds(45));
        assertEquals(HarnessState.WORKING, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void permissionDialogIsApproval() {
        Classification c = classifyQuiescent("""
                ╭──────────────────────────────────────────────╮
                │ Bash command                                 │
                │   rm -f target/output.txt                    │
                │ Do you want to proceed?                      │
                │ ❯ 1. Yes                                     │
                │   2. Yes, and don't ask again for rm in this │
                │   3. No, and tell Claude what to do (esc)    │
                ╰──────────────────────────────────────────────╯""", Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_APPROVAL, c.state());
    }

    @Test
    void askUserQuestionSelectorIsQuestion() {
        Classification c = classifyQuiescent("""
                Which color should the widget use?
                ❯ 1. Red
                  2. Blue
                  3. Other""", Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_QUESTION, c.state());
    }

    @Test
    void plainChatQuestionAboveInputBoxIsQuestion() {
        Classification c = classifyQuiescent("""
                ● Should I use approach A or approach B?
                ╭──────────────────────────────────────────────╮
                │ >                                            │
                ╰──────────────────────────────────────────────╯
                  ? for shortcuts""", Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_QUESTION, c.state());
    }

    @Test
    void emptyInputBoxIsIdle() {
        Classification c = classifyQuiescent("""
                ● Done. Item 3 is checked off. Waiting for further instructions.
                ╭──────────────────────────────────────────────╮
                │ >                                            │
                ╰──────────────────────────────────────────────╯
                  ? for shortcuts""", Duration.ofSeconds(6));
        assertEquals(HarnessState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void bareCaretLayoutIsIdle() {
        // verbatim from a live capture (Claude Code 2.1.233): no boxed input,
        // just a bare caret padded with a NO-BREAK SPACE (U+00A0) between
        // horizontal rules and an emoji status line
        Classification c = classifyQuiescent("""
                ● Done. Item 1 is checked off in TODO.md. Waiting for further instructions.
                ────────────────────────────────────────────────────────────────
                ❯\u00A0
                ────────────────────────────────────────────────────────────────
                  🧠 Fable 5 high 💾 41k █░░░ 4% 📁 /private/tmp/babysit-cc
                  ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents""", Duration.ofSeconds(6));
        assertEquals(HarnessState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void questionAboveBareCaretLayoutIsQuestion() {
        Classification c = classifyQuiescent("""
                ● Should one.txt contain option A or option B?
                ────────────────────────────────────────────────────────────────
                ❯\u00A0
                ────────────────────────────────────────────────────────────────
                  🧠 Fable 5 high 💾 41k █░░░ 4% 📁 /private/tmp/babysit-cc
                  ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents""", Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_QUESTION, c.state());
    }

    @Test
    void ghostSuggestionInInputLineIsStillIdle() {
        // after the first prompt, Claude Code renders a dim history-derived
        // suggestion inside the caret — idle must key off the status bar instead
        Classification c = classifyQuiescent("""
                ● Done. Item 2 is checked off. Waiting for further instructions.
                ────────────────────────────────────────────────────────────────
                ❯ continue with the last item
                ────────────────────────────────────────────────────────────────
                  🧠 Fable 5 high 💾 42k █░░░ 4% 📁 /private/tmp/babysit-cc
                  ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents""", Duration.ofSeconds(6));
        assertEquals(HarnessState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void mainIdleWithBackgroundSubagentsIsWorking() {
        // verbatim from a live capture: turn ended, bare idle caret, quiescent
        // screen (the agent timer does not redraw) — but a subagent is running
        Classification c = classifyQuiescent("""
                ⏺ Subagent launched in the background — it will run sleep 120 and report when
                  done. I'll wait for its completion notification.
                ✻ Waiting for 1 background agent to finish
                ────────────────────────────────────────────────────────────────
                ❯
                ────────────────────────────────────────────────────────────────
                  🧠 Fable 5 high 💾 41k █░░░ 4% 📁 /private/tmp/babysit-subagent
                  ⏵⏵ auto mode on · 1 shell · ← for agents
                  ⏺ main
                  ◯ general-purpose  Sleep 120 then report                  18s · ↓ 21.5k tokens""",
                Duration.ofSeconds(45));
        assertEquals(HarnessState.WORKING, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void staleWaitingBannerInTranscriptIsStillIdle() {
        // verbatim from a live capture after the subagent completed: the
        // "✻ Waiting" banner persists in the transcript above the caret, but the
        // live region below it (status bar, agents panel) has no running markers
        Classification c = classifyQuiescent("""
                ✻ Waiting for 1 background agent to finish
                ⏺ The subagent reported done.
                ────────────────────────────────────────────────────────────────
                ❯ launch one more: sleep 60, same deal
                ────────────────────────────────────────────────────────────────
                  🧠 Fable 5 high 💾 43k █░░░ 4% 📁 /private/tmp/babysit-subagent
                  ⏵⏵ auto mode on (shift+tab to cycle) · ← for agents""",
                Duration.ofSeconds(45));
        assertEquals(HarnessState.IDLE, c.state());
    }

    @Test
    void commandNotFoundIsBroken() {
        Classification c = classifyQuiescent(
                "zsh: claude: command not found", Duration.ofSeconds(6));
        assertEquals(HarnessState.BROKEN, c.state());
    }

    @Test
    void ownsProcessAcceptsTheVersionedBinaryTheLauncherExecsInto() {
        // tmux reports the versioned binary, not "claude" — seen live on 2.1.235
        ClaudeCodePlugin plugin = new ClaudeCodePlugin();
        assertTrue(plugin.ownsProcess("claude"));
        assertTrue(plugin.ownsProcess("2.1.235"));
        assertFalse(plugin.ownsProcess("zsh"));
        assertFalse(plugin.ownsProcess("codex"));
        assertFalse(plugin.ownsProcess("node"));
        assertFalse(plugin.ownsProcess("2.1"), "two components is not a claude version");
    }
}

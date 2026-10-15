package io.github.ghosthack.harnessstimulator;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StateDetectorTest {

    private static final Duration SHORT = Duration.ofSeconds(5);
    private static final Duration LONG = Duration.ofSeconds(30);

    private static final HarnessPlugin NO_PATTERNS = new HarnessPlugin() {
        @Override
        public String name() {
            return "null";
        }

        @Override
        public String patternsVersion() {
            return "0";
        }

        @Override
        public String launchCommand() {
            return "null-harness";
        }

        @Override
        public Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor) {
            return Optional.empty();
        }
    };

    private final MutableClock clock = new MutableClock();

    private Screen screen(String text) {
        return new Screen(text, false, Instant.EPOCH);
    }

    @Test
    void changingScreenIsWorking() {
        StateDetector d = new StateDetector(NO_PATTERNS, clock, SHORT, LONG);
        assertEquals(HarnessState.WORKING, d.classify(screen("a")).state());
        clock.advance(Duration.ofSeconds(2));
        Classification c = d.classify(screen("ab"));
        assertEquals(HarnessState.WORKING, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void brieflyQuiescentIsStillWorkingMediumConfidence() {
        StateDetector d = new StateDetector(NO_PATTERNS, clock, SHORT, LONG);
        d.classify(screen("a"));
        clock.advance(Duration.ofSeconds(3));
        Classification c = d.classify(screen("a"));
        assertEquals(HarnessState.WORKING, c.state());
        assertEquals(Confidence.MEDIUM, c.confidence());
    }

    @Test
    void quiescentUnmatchedIsUnrecognizedLowConfidence() {
        StateDetector d = new StateDetector(NO_PATTERNS, clock, SHORT, LONG);
        d.classify(screen("a"));
        clock.advance(Duration.ofSeconds(10));
        Classification c = d.classify(screen("a"));
        assertEquals(HarnessState.UNRECOGNIZED_PROMPT, c.state());
        assertEquals(Confidence.LOW, c.confidence());
    }

    @Test
    void longQuiescenceFallsBackToIdleHighConfidence() {
        StateDetector d = new StateDetector(NO_PATTERNS, clock, SHORT, LONG);
        d.classify(screen("a"));
        clock.advance(Duration.ofSeconds(31));
        Classification c = d.classify(screen("a"));
        assertEquals(HarnessState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void deadPaneIsBroken() {
        StateDetector d = new StateDetector(NO_PATTERNS, clock, SHORT, LONG);
        Classification c = d.classify(new Screen("", true, Instant.EPOCH));
        assertEquals(HarnessState.BROKEN, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void cursorAgentApprovalPromptDetected() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("doing item 8\nRun this command? [y/n]");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_APPROVAL, d.classify(s).state());
    }

    @Test
    void approvalWinsOverQuestionWhenPromptEndsWithQuestionMark() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("Do you want to run date > notes/item_08.txt?");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_APPROVAL, d.classify(s).state());
    }

    @Test
    void cursorAgentTrailingQuestionDetected() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("Item 14: should notes/item_14.txt contain option A or option B?");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_QUESTION, d.classify(s).state());
    }

    @Test
    void questionDetectedAboveInputBoxAndFooterChrome() {
        // real cursor-agent layout: the question sits above the input box, status
        // line, and key hints — the last raw line is chrome, not the question
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                  Should notes/item_14.txt contain "option A" or "option B"?
                  → Add a follow-up                              ctrl+c to stop
                  Auto Balance · 7.2% · 1 file edited
                  /private/tmp/babysit-scratch · main""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(HarnessState.WAITING_FOR_QUESTION, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void questionSelectorWidgetDetected() {
        // verbatim shape from a live audit snapshot: cursor-agent asks via a
        // multiple-choice widget, so no content line ends with a question mark
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                 │ 1. Should test.txt contain option A or option B?      │
                 │   › [ ] Option A                                       │
                 │     [ ] Option B                                       │
                 │     [ ] Other: (type to answer)                        │
                 │ ↑/↓ option · Space select · Enter next/submit · Esc to skip │
                 └────────────────────────────────────────────────────────┘""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(HarnessState.WAITING_FOR_QUESTION, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void questionDetectedWithBarePathFooterOutsideGitRepo() {
        // outside a git repo the footer has no "· branch" suffix — just a bare path
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                  one: should test.txt contain option A or option B?
                  → Add a follow-up
                  Auto Balance · 9.2%
                  /private/tmp/babysit-question""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_QUESTION, d.classify(s).state());
    }

    @Test
    void idleWithChromeStaysIdleWhenLastContentLineIsNotAQuestion() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                  Item 12 is done: created notes/item_12.txt and checked it off.
                  Waiting for further instructions.
                  → Add a follow-up
                  Auto Balance · 7.3% · 1 file edited
                  /private/tmp/babysit-scratch · main""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(HarnessState.IDLE, d.classify(s).state());
    }

    @Test
    void cursorAgentEmptyInputBoxIsIdleBeforeLongFallback() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("checked off item 3\n❯ ");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        Classification c = d.classify(s);
        assertEquals(HarnessState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void cursorAgentBackgroundTasksBelowInputIsWorking() {
        // verbatim from a live capture: main turn ended, input box idle, but a
        // background subagent runs — indicated by "2 tasks" below the input line
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                  Background subagent launched for sleep 60. Ready for further instructions.
                  → Add a follow-up
                  2 tasks
                  Auto Balance · 6.3%
                  /private/tmp/babysit-ca-bg""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(45));
        Classification c = d.classify(s);
        assertEquals(HarnessState.WORKING, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void cursorAgentStaleTasksMentionAboveInputIsIdle() {
        // "N tasks" appearing in transcript text above the input line must not
        // keep the session classified as working forever
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("""
                  I finished 2 tasks
                  → Add a follow-up
                  Auto Balance · 6.5%
                  /private/tmp/babysit-ca-bg""");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(HarnessState.IDLE, d.classify(s).state());
    }

    @Test
    void cursorAgentErrorSignatureIsBroken() {
        StateDetector d = new StateDetector(new CursorAgentPlugin(), clock, SHORT, LONG);
        Screen s = screen("zsh: cursor-agent: command not found");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(HarnessState.BROKEN, d.classify(s).state());
    }

    @Test
    void paneBackAtAShellPromptIsBrokenNotIdle() {
        // the harness exited and left a bare shell prompt: quiescent, no error text, and
        // it passes the echo probe like any real text input — so without the liveness
        // check the idle message would be typed into the shell and executed as a command
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        Screen s = new Screen("tester@host project %", false, Instant.EPOCH, "zsh");
        d.classify(s);
        clock.advance(Duration.ofSeconds(45));
        Classification c = d.classify(s);
        assertEquals(HarnessState.BROKEN, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void livenessCheckUsesTheBinaryNameNotTheProfileName() {
        // profile "claude-code" runs a process called "claude"
        StateDetector d = new StateDetector(new ClaudeCodePlugin(), clock, SHORT, LONG);
        Screen running = new Screen("""
                ● Done. Waiting for further instructions.
                ❯\u00A0
                  ⏵⏵ auto mode on (shift+tab to cycle)""", false, Instant.EPOCH, "claude");
        d.classify(running);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(HarnessState.IDLE, d.classify(running).state());
    }

    @Test
    void unknownForegroundProcessDoesNotTriggerTheLivenessCheck() {
        // tmux could not report it (empty), or the harness shelled out to a pager —
        // neither is evidence the harness died, so classification proceeds normally
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        Screen s = new Screen("""
                • All done.
                › Explain this codebase
                  gpt-5.6-sol high · /tmp/x · Context 99% left · 0.147.0 · Fast off""",
                false, Instant.EPOCH, "less");
        d.classify(s);
        clock.advance(Duration.ofSeconds(6));
        assertEquals(HarnessState.IDLE, d.classify(s).state());
    }

    @Test
    void deadPaneStillWinsOverEverything() {
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        assertEquals(HarnessState.BROKEN,
                d.classify(new Screen("", true, Instant.EPOCH, "zsh")).state());
    }

    @Test
    void tickingAutoResolveCountdownStillReachesQuiescence() {
        // codex repaints "auto-resolves in Ns" every second while it waits for an
        // answer. Without stripVolatile the screen never stops changing, the plugin is
        // never consulted, and a question that needs a human reads as a running turn.
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        String withCountdown = """
                › Ask me which option to use.
                  Question 1/1 (1 unanswered) \u00b7 auto-resolves in %ds
                  Which text should the file contain?
                  › 1. option A (Recommended)
                    2. option B
                  tab to add notes | enter to submit answer | esc to interrupt""";
        d.classify(screen(withCountdown.formatted(59)));
        clock.advance(Duration.ofSeconds(3));
        d.classify(screen(withCountdown.formatted(56)));
        clock.advance(Duration.ofSeconds(3));
        Classification c = d.classify(screen(withCountdown.formatted(53)));
        assertEquals(HarnessState.WAITING_FOR_QUESTION, c.state());
        assertEquals(Classification.Origin.PLUGIN, c.origin());
    }

    @Test
    void realOutputChangesStillCountAsWorkingWhileTheCountdownIsStripped() {
        StateDetector d = new StateDetector(new CodexPlugin(), clock, SHORT, LONG);
        d.classify(screen("  Question 1/1 (1 unanswered) \u00b7 auto-resolves in 59s"));
        clock.advance(Duration.ofSeconds(3));
        Classification c = d.classify(screen("  Question 1/1 (1 unanswered) \u00b7 auto-resolves in 56s\n• new output"));
        assertEquals(HarnessState.WORKING, c.state());
    }
}

package io.github.ghosthack.harnessstimulator;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detector tests against Codex CLI screen shapes. Every fixture except the two
 * marked UNVERIFIED is a verbatim `tmux capture-pane -p` from codex-cli 0.147.0
 * at 120x40; see codex-harness-test-plan.md for how they were taken.
 */
class CodexPluginTest {

    private static final Duration SHORT = Duration.ofSeconds(5);
    private static final Duration LONG = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock();
    private final StateDetector detector =
            new StateDetector(new CodexPlugin(), clock, SHORT, LONG);

    private Classification classifyQuiescent(String text, Duration quiet) {
        Screen s = new Screen(text, false, Instant.EPOCH);
        detector.classify(s);
        clock.advance(quiet);
        return detector.classify(s);
    }

    @Test
    void runningTurnIsWorkingEvenIfTheRenderFreezes() {
        // codex's working line ticks ("Working (2s" -> "(5s"), so a live turn never
        // reaches quiescence; this covers the turn whose render does freeze
        Classification c = classifyQuiescent("""
                • Ran date
                  └ Mon Aug 17 19:22:03 PDT 2026
                • Working (5s • esc to interrupt)
                › Explain this codebase
                  gpt-5.6-sol high · /private/tmp/codex-capture · Context 99% left · 0.147.0 · Fast off""",
                Duration.ofSeconds(45));
        assertEquals(HarnessState.WORKING, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void execApprovalDialogIsApproval() {
        Classification c = classifyQuiescent("""
                • Running date
                  Would you like to run the following command?
                  Environment: local
                  $ date
                › 1. Yes, proceed (y)
                  2. Yes, and don't ask again for commands that start with `date` (p)
                  3. No, and tell Codex what to do differently (esc)
                  Press enter to confirm or esc to cancel""", Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_APPROVAL, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void startupDirectoryTrustPromptIsApproval() {
        Classification c = classifyQuiescent("""
                > You are in /private/tmp/codex-capture

                  Do you trust the contents of this directory? Working with untrusted contents comes with higher risk of prompt
                  injection. Trusting the directory allows project-local config, hooks, and exec policies to load.

                › 1. Yes, continue
                  2. No, quit

                  Press enter to continue""", Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_APPROVAL, c.state());
    }

    @Test
    void codexOwnRateLimitModalIsApprovalNotQuestion() {
        // codex interposes its own modals in the same selector widget as permission
        // prompts. The prompt line ends with '?', so without the selector check this
        // would read as an answerable question and a configured question-answer
        // strategy would type prose into a list view.
        Classification c = classifyQuiescent("""
                ────────────────────────────────────────────────────────────────
                • Mon Aug 17 19:22:03 PDT 2026
                ────────────────────────────────────────────────────────────────
                  Approaching rate limits
                  Switch to gpt-5.6-luna for lower credit usage?
                › 1. Switch to gpt-5.6-luna                 Fast and affordable agentic coding model.
                  2. Keep current model
                  3. Keep current model (never show again)  Hide future rate limit reminders about switching models.
                  Press enter to confirm or esc to go back""", Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_APPROVAL, c.state());
    }

    @Test
    void freshSessionBannerIsIdle() {
        Classification c = classifyQuiescent("""
                ╭────────────────────────────────────────────────╮
                │ >_ OpenAI Codex (v0.147.0)                     │
                │                                                │
                │ model:     gpt-5.6-sol high   /model to change │
                │ directory: /private/tmp/codex-capture          │
                ╰────────────────────────────────────────────────╯

                  Tip: New Use /fast to enable our fastest inference with increased plan usage.

                ⚠ Heads up, you have less than 10% of your weekly limit left. Run /status for a breakdown.


                › Explain this codebase

                  gpt-5.6-sol high · /private/tmp/codex-capture · Context 100% left · 0.147.0 · Fast off""",
                Duration.ofSeconds(6));
        assertEquals(HarnessState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void completedTurnIsIdleDespiteGhostTextInTheComposer() {
        // "› Explain this codebase" is a dim placeholder, not a draft — idle keys off
        // the status footer instead of the caret
        Classification c = classifyQuiescent("""
                • Ran date
                  └ Mon Aug 17 19:22:03 PDT 2026
                ────────────────────────────────────────────────────────────────
                • Mon Aug 17 19:22:03 PDT 2026
                ────────────────────────────────────────────────────────────────
                › Explain this codebase
                  gpt-5.6-sol high · /private/tmp/codex-capture · Context 99% left · 0.147.0 · 258K window · Fast off""",
                Duration.ofSeconds(6));
        assertEquals(HarnessState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void planModeIdleIsStillIdle() {
        Classification c = classifyQuiescent("""
                • Model changed to gpt-5.6-sol medium for Plan mode.
                › Explain this codebase
                  gpt-5.6-sol medium · /private/tmp/codex-capture · Context 100% left · 0.147.0 · Fast off                   Plan mode""",
                Duration.ofSeconds(6));
        assertEquals(HarnessState.IDLE, c.state());
    }

    @Test
    void plainChatQuestionAboveTheComposerIsQuestion() {
        Classification c = classifyQuiescent("""
                ────────────────────────────────────────────────────────────────
                • Should notes/item_14.txt contain option A or option B?
                ────────────────────────────────────────────────────────────────
                › Explain this codebase
                  gpt-5.6-sol high · /private/tmp/codex-capture · Context 99% left · 0.147.0 · Fast off""",
                Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_QUESTION, c.state());
    }

    @Test
    void killedHarnessLeavingAShellPromptIsBroken() {
        Classification c = classifyQuiescent("""
                › zsh: killed     codex --ask-for-approval untrusted --sandbox workspace-write
                tester@m4pro-2 codex-capture %""", Duration.ofSeconds(6));
        assertEquals(HarnessState.BROKEN, c.state());
    }

    @Test
    void commandNotFoundIsBrokenInBothShellSpellings() {
        // zsh and bash word it differently; the other plugins only match the bash form
        assertEquals(HarnessState.BROKEN,
                classifyQuiescent("zsh: command not found: codex", Duration.ofSeconds(6)).state());
        assertEquals(HarnessState.BROKEN,
                classifyQuiescent("bash: codex: command not found", Duration.ofSeconds(6)).state());
    }

    @Test
    void agentTranscriptMentioningCommandNotFoundIsNotBroken() {
        // a coding agent's own output is full of failing commands; only codex's death counts
        Classification c = classifyQuiescent("""
                • Ran make build
                  └ /bin/sh: line 1: gcc: command not found
                ────────────────────────────────────────────────────────────────
                • The build failed because gcc is missing.
                ────────────────────────────────────────────────────────────────
                › Explain this codebase
                  gpt-5.6-sol high · /private/tmp/codex-capture · Context 98% left · 0.147.0 · Fast off""",
                Duration.ofSeconds(6));
        assertEquals(HarnessState.IDLE, c.state());
    }

    @Test
    void unrecognizedScreenFallsBackToBehavioral() {
        Classification c = classifyQuiescent("some screen codex never renders", Duration.ofSeconds(45));
        assertEquals(Classification.Origin.BEHAVIORAL, c.origin());
    }

    @Test
    void idleSurvivesACustomStatusLineConfig() {
        // [tui] status_line is operator config picked from ~20 components, so idle must
        // not depend on any one of them being enabled. Verbatim capture with
        // status_line = ["current-dir"]: the footer is just the directory.
        Classification c = classifyQuiescent("""
                  Tip: New Use /fast to enable our fastest inference with increased plan usage.
                ⚠ MCP startup interrupted. The following servers were not initialized: codex_apps, porkbun
                ⚠ Heads up, you have less than 10% of your weekly limit left. Run /status for a breakdown.
                › Find and fix a bug in @filename
                  /private/tmp/codex-capture""", Duration.ofSeconds(6));
        assertEquals(HarnessState.IDLE, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void modalDialogsAreNotMistakenForTheComposer() {
        // codex modals replace the composer region rather than drawing above it, which
        // is what makes "composer visible" a sound idle signal — assert the approval
        // screen really has no composer left to match
        Classification c = classifyQuiescent("""
                • Running date
                  Would you like to run the following command?
                  Environment: local
                  $ date
                › 1. Yes, proceed (y)
                  3. No, and tell Codex what to do differently (esc)
                  Press enter to confirm or esc to cancel""", Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_APPROVAL, c.state());
    }

    @Test
    void requestUserInputWidgetIsAQuestionNotARunningTurn() {
        // verbatim. The hint bar ends in "esc to interrupt" — the same string the
        // running-turn line carries — so a loose WORKING pattern swallows the whole
        // question episode and the human is never told the harness is waiting.
        Classification c = classifyQuiescent("""
                › Ask me which option to use.
                  Question 1/1 (1 unanswered)
                  Which text should `notes/item_14.txt` contain?
                  › 1. option A (Recommended)  Write the text \u201coption A\u201d to the file.
                    2. option B                Write the text \u201coption B\u201d to the file.
                    3. None of the above       Optionally, add details in notes (tab).
                  tab to add notes | enter to submit answer | esc to interrupt""", Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_QUESTION, c.state());
        assertEquals(Confidence.HIGH, c.confidence());
    }

    @Test
    void selectorQuestionIsNotTextAnswerable() {
        // verified live: typing "use your best judgment" into this widget entered no
        // text at all — the letters moved the highlight from option 1 to option 2 and
        // the trailing Enter committed "option B"
        Classification c = classifyQuiescent("""
                › Ask me which option to use.
                  Question 1/1 (1 unanswered)
                  Which text should `notes/item_14.txt` contain?
                  › 1. option A (Recommended)  Write the text \u201coption A\u201d to the file.
                    2. option B                Write the text \u201coption B\u201d to the file.
                    3. None of the above       Optionally, add details in notes (tab).
                  tab to add notes | enter to submit answer | esc to interrupt""", Duration.ofSeconds(6));
        assertFalse(c.textAnswerable());
    }

    @Test
    void plainChatQuestionRemainsTextAnswerable() {
        Classification c = classifyQuiescent("""
                ────────────────────────────────────────────────────────────────
                • Should notes/item_14.txt contain option A or option B?
                ────────────────────────────────────────────────────────────────
                › Explain this codebase
                  gpt-5.6-terra medium · /tmp/x · Context 99% left · 0.147.0 · Fast off""",
                Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_QUESTION, c.state());
        assertTrue(c.textAnswerable());
    }

    @Test
    void questionWidgetWithAutoResolveCountdownStillClassifies() {
        Classification c = classifyQuiescent("""
                › Ask me which option to use.
                  Question 1/1 (1 unanswered) · auto-resolves in 52s
                  Which text should `notes/item_14.txt` contain?
                  › 1. option A (Recommended)  Write the text \u201coption A\u201d to the file.
                    2. option B                Write the text \u201coption B\u201d to the file.
                  tab to add notes | enter to submit answer | esc to interrupt""",
                Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_QUESTION, c.state());
        assertFalse(c.textAnswerable());
    }

    @Test
    void waitingOnBackgroundSubagentsStaysWorking() {
        // verbatim, ~1m38s into a subagent wait. Unlike Claude Code, codex keeps the
        // turn open while it waits for agents, so the "main idle while subagents run"
        // trap does not exist here and needs no separate pattern.
        Classification c = classifyQuiescent("""
                • I\u2019ve launched a background subagent to run sleep 90; I\u2019ll wait for its completion.
                • Started `/root/sleep_wait`
                • Waiting for agents
                • Working (1m 38s • esc to interrupt)
                › Improve documentation in @filename
                  gpt-5.6-terra medium · /private/tmp/codex-cap2 · Context 99% left · 0.147.0 · Fast off · Main [default]""",
                Duration.ofSeconds(45));
        assertEquals(HarnessState.WORKING, c.state());
    }

    @Test
    void goalAchievedIsOrdinaryIdle() {
        // "Goal achieved (11s)" freezes at its final duration rather than ticking, so
        // the screen quiesces and this is a normal idle awaiting input
        Classification c = classifyQuiescent("""
                ────────────────────────────────────────────────────────────────
                • Created and verified empty: goal_done.txt.
                ────────────────────────────────────────────────────────────────
                › Improve documentation in @filename
                  gpt-5.6-terra medium · /private/tmp/codex-cap2 · Context 98% left · 0.147.0 · Fas… Goal achieved (11s)""",
                Duration.ofSeconds(6));
        assertEquals(HarnessState.IDLE, c.state());
    }

    @Test
    void stalledGoalIsNotifyOnlyRatherThanNudged() {
        Classification c = classifyQuiescent("""
                • Ran out of ideas for the objective.
                › Improve documentation in @filename
                  gpt-5.6-terra medium · /private/tmp/codex-cap2 · Context 98% left · 0.147.0 · Goal stalled (/goal resume)""",
                Duration.ofSeconds(6));
        assertEquals(HarnessState.UNRECOGNIZED_PROMPT, c.state());
    }

    @Test
    void patchApprovalIsApproval() {
        // verbatim, captured during a live babysit run of the seeded workload
        Classification c = classifyQuiescent("""
                    6 -- [ ] 1. Create notes/item_01.txt containing the word: one
                    6 +- [x] 1. Create notes/item_01.txt containing the word: one
                  └ notes/item_01.txt (+1 -0)
                    1 +one
                  Would you like to make the following edits?
                › 1. Yes, proceed (y)
                  2. Yes, and don't ask again for these files (a)
                  3. No, and tell Codex what to do differently (esc)
                  Press enter to confirm or esc to cancel""", Duration.ofSeconds(6));
        assertEquals(HarnessState.WAITING_FOR_APPROVAL, c.state());
    }
}

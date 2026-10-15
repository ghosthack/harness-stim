package io.github.ghosthack.harnessstimulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    private static final HarnessPlugin CODEX = new CodexPlugin();
    private static final HarnessPlugin CLAUDE = new ClaudeCodePlugin();

    @Test
    void derivedHandleCarriesHarnessAndFullTarget() {
        assertEquals("bs-codex-refactor-billing-api",
                Main.deriveHandle(CODEX, Optional.of("refactor-billing-api"), false));
    }

    @Test
    void samePrefixTargetsOnDifferentHarnessesGetDistinctHandles() {
        // both used to truncate to bs-refactor-billing, so the second run typed its
        // launch command into the first one's live conversation
        String codex = Main.deriveHandle(CODEX, Optional.of("refactor-billing-api"), false);
        String claude = Main.deriveHandle(CLAUDE, Optional.of("refactor-billing-auth"), false);
        assertNotEquals(codex, claude);
    }

    @Test
    void samePrefixTargetsOnOneHarnessGetDistinctHandles() {
        String api = Main.deriveHandle(CODEX, Optional.of("refactor-billing-api"), false);
        String auth = Main.deriveHandle(CODEX, Optional.of("refactor-billing-auth"), false);
        assertNotEquals(api, auth);
    }

    @Test
    void targetIsCleanedToTmuxAndFilenameSafeCharacters() {
        assertEquals("bs-codex-a-b-c_d-e",
                Main.deriveHandle(CODEX, Optional.of("a.b:c_d/e"), false));
    }

    @Test
    void freshAndLastFormsStayNamespaced() {
        assertEquals("bs-codex", Main.deriveHandle(CODEX, Optional.empty(), false));
        assertEquals("bs-codex-last", Main.deriveHandle(CODEX, Optional.empty(), true));
    }

    @Test
    void overlongTargetsAreCappedYetStableAndDistinct() {
        String base = "x".repeat(300);
        String a = Main.deriveHandle(CODEX, Optional.of(base + "-api"), false);
        String b = Main.deriveHandle(CODEX, Optional.of(base + "-auth"), false);
        assertTrue(a.length() <= Main.MAX_HANDLE_LENGTH, "must fit a filename component");
        assertTrue(b.length() <= Main.MAX_HANDLE_LENGTH, "must fit a filename component");
        assertNotEquals(a, b);
        assertEquals(a, Main.deriveHandle(CODEX, Optional.of(base + "-api"), false),
                "re-running the same resume must adopt, so the handle must be stable");
        assertTrue(a.matches("[A-Za-z0-9_-]+"), "cap overflow must stay tmux/filename safe");
    }

    @Test
    void conversationKeyDistinguishesTargetHarnessAndForm() {
        assertEquals("codex --resume task-a",
                Main.conversationKey("codex", Optional.of("task-a"), false));
        assertEquals("codex --last", Main.conversationKey("codex", Optional.empty(), true));
        assertEquals("codex (fresh)", Main.conversationKey("codex", Optional.empty(), false));
        assertNotEquals(Main.conversationKey("codex", Optional.of("task-a"), false),
                Main.conversationKey("claude-code", Optional.of("task-a"), false));
    }

    @TempDir
    Path tmp;

    @Test
    void attachLockIsAcquiredWhenFree() throws IOException {
        Path lock = tmp.resolve("bs-x.lock");
        assertEquals(OptionalLong.empty(), Main.acquireAttachLock(lock, 111, pid -> true));
        assertEquals("111", Files.readString(lock));
    }

    @Test
    void attachLockHeldByLivePidIsRefused() throws IOException {
        Path lock = tmp.resolve("bs-x.lock");
        Files.writeString(lock, "222");
        assertEquals(OptionalLong.of(222), Main.acquireAttachLock(lock, 111, pid -> true));
        assertEquals("222", Files.readString(lock), "the holder's lock must be untouched");
    }

    @Test
    void attachLockHeldByDeadPidIsSweptAndRetaken() throws IOException {
        Path lock = tmp.resolve("bs-x.lock");
        Files.writeString(lock, "222");
        assertEquals(OptionalLong.empty(), Main.acquireAttachLock(lock, 111, pid -> false));
        assertEquals("111", Files.readString(lock));
    }

    @Test
    void liveLockOwnerIgnoresDeadAndMalformedOwners() throws IOException {
        Path lock = tmp.resolve("owner.lock");
        Files.writeString(lock, Long.toString(ProcessHandle.current().pid()));
        assertEquals(OptionalLong.of(ProcessHandle.current().pid()), Main.liveLockOwner(lock));
        Files.writeString(lock, "999999999");
        assertEquals(OptionalLong.empty(), Main.liveLockOwner(lock));
        Files.writeString(lock, "garbage");
        assertEquals(OptionalLong.empty(), Main.liveLockOwner(lock));
    }

    @Test
    void attachLockWithGarbageContentIsSweptAndRetaken() throws IOException {
        Path lock = tmp.resolve("bs-x.lock");
        Files.writeString(lock, "not-a-pid");
        assertEquals(OptionalLong.empty(), Main.acquireAttachLock(lock, 111, pid -> true));
        assertEquals("111", Files.readString(lock));
    }

    @Test
    void resetModeInstructionPrefersAnExplicitIdleMessage() {
        assertEquals("loop instruction",
                Main.resetModeInstruction("loop instruction", Optional.of("opening prompt")));
    }

    @Test
    void resetModeInstructionFallsBackToThePrompt() {
        // the ralph-loop shape: the opening instruction is the every-iteration instruction
        assertEquals("opening prompt",
                Main.resetModeInstruction(null, Optional.of("opening prompt")));
    }

    @Test
    void resetModeInstructionRefusesWhenNeitherIsGiven() {
        // the default nudge is conversational ("keep waiting ... continue") and lands in
        // zero context every iteration, so silently using it would be the canonical
        // loop-mode mistake baked in as a default
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> Main.resetModeInstruction(null, Optional.empty()));
        assertTrue(e.getMessage().contains("--prompt"));
    }

    @Test
    void resolveIdleMessageRefusesResetForAHarnessWithoutAResetCommand() {
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> Main.resolveIdleMessage("cursor-agent", StrategyConfig.OnIdle.RESET,
                        null, Optional.of("work through TODO.md")));
        assertTrue(e.getMessage().contains("context-reset"));
    }

    @Test
    void resolveIdleMessageWiresThePromptThroughInResetMode() {
        assertEquals("work through TODO.md",
                Main.resolveIdleMessage("claude-code", StrategyConfig.OnIdle.RESET,
                        null, Optional.of("work through TODO.md")));
    }

    @Test
    void resolveIdleMessageRefusesTheDefaultNudgeAsALoopInstruction() {
        // parse-time twin of the attach-time check: without it this combination
        // launches a harness and only then dies at attach, blaming the config file
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> Main.resolveIdleMessage("claude-code", StrategyConfig.OnIdle.RESET,
                        StrategyConfig.defaults().idleMessage(), Optional.empty()));
        assertTrue(e.getMessage().contains("self-contained"));
    }

    @Test
    void resolveIdleMessageFallsBackToTheDefaultNudgeOnlyInNudgeMode() {
        // the conversational default is fine as a nudge, and the prompt must not
        // leak into the nudge: it was the opening instruction, not the reminder
        assertEquals(StrategyConfig.defaults().idleMessage(),
                Main.resolveIdleMessage("codex", StrategyConfig.OnIdle.NUDGE,
                        null, Optional.of("opening prompt")));
        assertEquals("custom nudge",
                Main.resolveIdleMessage("codex", StrategyConfig.OnIdle.NUDGE,
                        "custom nudge", Optional.empty()));
    }

    @Test
    void resetCommandsExistExactlyWhereVerified() {
        // loop mode's CLI validation leans on these: a harness without a verified
        // reset command must refuse the mode rather than guess and wipe the wrong thing
        assertEquals(Optional.of("/clear"), CLAUDE.resetCommand());
        assertEquals(Optional.of("/new"), CODEX.resetCommand());
        assertEquals(Optional.empty(), new CursorAgentPlugin().resetCommand());
    }

    @Test
    void gracefulExitCommandsExistExactlyWhereVerified() {
        assertEquals(Optional.of("/exit"), CLAUDE.gracefulExitCommand());
        assertEquals(Optional.empty(), CODEX.gracefulExitCommand());
        assertEquals(Optional.empty(), new CursorAgentPlugin().gracefulExitCommand());
    }

    @Test
    void gracefulExitRequiresPluginIdleOwnedPaneAndNoHumanClient() {
        Screen claudePane = new Screen("idle", false, java.time.Instant.EPOCH, "2.1.239");
        Classification ready = new Classification(HarnessState.IDLE, Confidence.HIGH,
                "idle", Classification.Origin.PLUGIN);
        assertTrue(Main.readyForGracefulExit(CLAUDE, claudePane, ready, 0));
        assertFalse(Main.readyForGracefulExit(CLAUDE, claudePane,
                new Classification(HarnessState.WORKING, Confidence.HIGH, "working",
                        Classification.Origin.PLUGIN), 0));
        assertFalse(Main.readyForGracefulExit(CLAUDE, claudePane,
                new Classification(HarnessState.IDLE, Confidence.HIGH, "fallback",
                        Classification.Origin.BEHAVIORAL), 0));
        assertFalse(Main.readyForGracefulExit(CLAUDE, claudePane, ready, 1));
        assertFalse(Main.readyForGracefulExit(CLAUDE,
                new Screen("shell", false, java.time.Instant.EPOCH, "zsh"), ready, 0));
    }

    @Test
    void modelFlagsExistExactlyWhereVerified() {
        assertEquals(Optional.of("--model"), CLAUDE.modelFlag());
        assertEquals(Optional.of("--model"), CODEX.modelFlag());
        assertEquals(Optional.empty(), new CursorAgentPlugin().modelFlag());
    }

    @Test
    void modelSelectionIsAppliedToFreshAndResumeCommandsAsOneShellWord() {
        assertEquals("codex --model 'gpt-5.6-sol'",
                Main.applyModel(CODEX, "codex", Optional.of("gpt-5.6-sol")));
        assertEquals("codex resume task-a --model 'name'\"'\"'s model'",
                Main.applyModel(CODEX, "codex resume task-a", Optional.of("name's model")));
        assertEquals("codex", Main.applyModel(CODEX, "codex", Optional.empty()));
    }

    @Test
    void unverifiedModelSelectionIsRefused() {
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> Main.applyModel(new CursorAgentPlugin(), "cursor-agent", Optional.of("fast")));
        assertTrue(e.getMessage().contains("no verified model-selection syntax"));
    }

    @Test
    void releaseOnlyDeletesOwnLock() throws IOException {
        Path lock = tmp.resolve("bs-x.lock");
        Files.writeString(lock, "222");
        Main.releaseAttachLock(lock, 111);
        assertTrue(Files.exists(lock), "a successor's lock must never be deleted");
        Main.releaseAttachLock(lock, 222);
        assertFalse(Files.exists(lock));
        Main.releaseAttachLock(lock, 222); // gone already — must be a no-op, not a crash
    }
}

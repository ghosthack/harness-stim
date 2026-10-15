package io.github.ghosthack.harnessstimulator;

import java.time.Duration;
import java.util.Optional;

/**
 * Per-harness textual pattern layer. Only consulted once the behavioral core has
 * established the screen is quiescent; returning empty falls back to the generic
 * behavioral heuristics, which is the expected outcome when a harness update
 * breaks the patterns.
 */
public interface HarnessPlugin {

    String name();

    /**
     * The pane's foreground process name while this harness runs, as tmux reports it in
     * {@code pane_current_command}. Defaults to {@link #name()}; override when the
     * profile name and the binary name differ.
     */
    default String processName() {
        return name();
    }

    /**
     * Whether a pane whose foreground is {@code foregroundCommand} is running this
     * harness. Equality with {@link #processName()} by default; override when the
     * harness execs into something else — Claude Code's launcher execs a binary named
     * after its version, so tmux reports "2.1.235", a name that changes every release
     * and can never be matched by equality.
     */
    default boolean ownsProcess(String foregroundCommand) {
        return processName().equals(foregroundCommand);
    }

    /** Command that starts the harness fresh, with no prior conversation. */
    String launchCommand();

    /**
     * Command-line flag used to select a model when launching or resuming this
     * harness. Empty means the syntax has not been verified and callers must inherit
     * the harness's configured default rather than guess.
     */
    default Optional<String> modelFlag() {
        return Optional.empty();
    }

    /**
     * Command that resumes an existing harness conversation by id or name — the whole
     * premise of this app is that you already have a stable context going and just need
     * it kept moving, so resuming is the primary path and starting fresh the exception.
     * Empty means this profile has no verified resume syntax.
     */
    default Optional<String> resumeCommand(String harnessSession) {
        return Optional.empty();
    }

    /** Command that resumes the most recent conversation. Empty if unsupported. */
    default Optional<String> resumeLastCommand() {
        return Optional.empty();
    }

    /**
     * Slash command that resets the conversation context to zero while leaving the
     * harness process running — what loop mode ({@code --on-idle reset}) types before
     * re-issuing the instruction. It goes through the same idle-gated stimulus path as
     * any keystroke and is never chained blindly with the instruction that follows: the
     * stimulator waits for the screen to classify idle again in between. Empty means
     * this profile has no verified reset command, and loop mode refuses the harness.
     */
    default Optional<String> resetCommand() {
        return Optional.empty();
    }

    /**
     * Harness-native command that exits the interactive process cleanly. Empty
     * means the command has not been verified and graceful close must refuse the
     * harness rather than guess or fall back to killing its process.
     */
    default Optional<String> gracefulExitCommand() {
        return Optional.empty();
    }

    /** Pattern version, logged with every classification so misfires can be tied to a pattern set. */
    String patternsVersion();

    Optional<Classification> classifyQuiescent(Screen screen, Duration quietFor);

    /**
     * Blanks regions that repaint on a timer while the harness is <em>waiting</em>, so
     * that screen quiescence — the whole behavioral core — still fires and this plugin
     * still gets consulted. Codex's question widget counts down to an auto-answer
     * ("auto-resolves in 52s"), which without this keeps the screen changing every
     * second and hides a state that needs a human.
     *
     * <p>Deliberately not for regions that tick while the harness is <em>working</em>:
     * that churn is a true liveness signal, and normalizing it away would let a frozen
     * plugin pattern set decay into a mid-turn nudge.
     */
    default String stripVolatile(String screenText) {
        return screenText;
    }
}

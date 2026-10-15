package io.github.ghosthack.harnessstimulator;

import java.time.Instant;
import java.util.List;

/**
 * One capture of the tmux pane's rendered text, plus the pane's foreground process
 * name ({@code pane_current_command}) when tmux could report it. The process name is
 * the one state signal on this screen that no harness UI update can rot: if the pane
 * has fallen back to a shell, the harness is gone no matter what text is on screen.
 * Empty means "unknown" and is ignored by the detector.
 */
public record Screen(String text, boolean paneDead, Instant capturedAt, String foregroundCommand) {

    public Screen(String text, boolean paneDead, Instant capturedAt) {
        this(text, paneDead, capturedAt, "");
    }

    /** Last {@code n} non-blank lines, oldest first — the region prompts live in. */
    public List<String> tailLines(int n) {
        List<String> nonBlank = text.lines().filter(l -> !l.isBlank()).toList();
        return nonBlank.subList(Math.max(0, nonBlank.size() - n), nonBlank.size());
    }
}

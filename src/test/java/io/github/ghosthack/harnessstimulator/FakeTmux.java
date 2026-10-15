package io.github.ghosthack.harnessstimulator;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Scripted tmux: the test sets the current screen; sent text is recorded.
 * Captures come from the queue first (for probe choreography: base, typed,
 * reverted), then fall back to the current screen.
 */
final class FakeTmux implements Tmux {

    private Screen current = new Screen("", false, Instant.EPOCH);
    private final Deque<Screen> captureQueue = new ArrayDeque<>();
    final List<String> sent = new ArrayList<>();
    final List<String> literals = new ArrayList<>();
    int backspaces;
    int rwClients;
    /** Mimics tmux refusing send-keys while a read-only client is attached. */
    boolean sendsRefused;

    void screen(String text) {
        current = new Screen(text, false, Instant.EPOCH);
    }

    void queueCapture(String text) {
        captureQueue.add(new Screen(text, false, Instant.EPOCH));
    }

    void paneDies() {
        current = new Screen("", true, Instant.EPOCH);
    }

    @Override
    public boolean hasSession(String session) {
        return true;
    }

    @Override
    public void newSession(String session, String workdir) {
    }

    @Override
    public Screen capture(String session) {
        return captureQueue.isEmpty() ? current : captureQueue.poll();
    }

    @Override
    public void sendText(String session, String text) {
        refuseIfReadOnly();
        sent.add(text);
    }

    private void refuseIfReadOnly() {
        if (sendsRefused) {
            throw new TmuxException("tmux send-keys failed: client is read-only");
        }
    }

    @Override
    public void sendLiteral(String session, String text) {
        refuseIfReadOnly();
        literals.add(text);
    }

    @Override
    public void sendBackspace(String session) {
        refuseIfReadOnly();
        backspaces++;
    }

    @Override
    public int attachedReadWriteClients(String session) {
        return rwClients;
    }
}

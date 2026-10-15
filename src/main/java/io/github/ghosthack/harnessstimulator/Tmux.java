package io.github.ghosthack.harnessstimulator;

/** Minimal tmux surface the app needs; faked in tests. */
public interface Tmux {

    boolean hasSession(String session);

    /** Creates a detached session running the default shell in {@code workdir}. */
    void newSession(String session, String workdir);

    /** Captures the rendered pane text. A missing session or dead pane yields {@code paneDead=true}. */
    Screen capture(String session);

    /** Sends {@code text} literally followed by Enter. */
    void sendText(String session, String text);

    /** Sends {@code text} literally with NO Enter — used by the echo probe. */
    void sendLiteral(String session, String text);

    /** Sends a single Backspace — used by the echo probe to undo itself. */
    void sendBackspace(String session);

    /**
     * Count of attached read-write clients. Read-only peekers are excluded, so a
     * peek never blocks the release detection after a steal.
     */
    int attachedReadWriteClients(String session);
}

package io.github.ghosthack.harnessstimulator;

/**
 * A tmux command failed. Part of the {@link Tmux} contract rather than any one
 * implementation's private business, because callers have to handle it: a send can
 * fail for reasons that have nothing to do with the harness and that later polls
 * recover from — most notably `client is read-only`, which tmux raises whenever the
 * client it resolves for the command is a read-only attachment.
 */
public class TmuxException extends RuntimeException {

    public TmuxException(String message) {
        super(message);
    }

    public TmuxException(String message, Throwable cause) {
        super(message, cause);
    }
}

package io.github.ghosthack.harnessstimulator;

/**
 * Result of one detector pass over a captured screen. {@code origin} records
 * whether a harness plugin pattern recognized the screen or the generic
 * behavioral heuristics produced the state — fallback-idle stimulation is
 * gated on an echo probe precisely because BEHAVIORAL means "nothing
 * recognized this screen".
 *
 * <p>{@code textAnswerable} qualifies WAITING_FOR_QUESTION only. False means the
 * question is a selector widget rather than a text field, so the configured answer
 * strategy must not be used: verified against codex, typing an answer into one does
 * not enter text at all — the characters act as navigation keys, move the highlight,
 * and the trailing Enter commits whichever option the letters happened to land on.
 * A question that cannot be answered as text is notify-only, like approval.
 */
public record Classification(HarnessState state, Confidence confidence, String reason, Origin origin,
                             boolean textAnswerable) {

    /**
     * Defaults {@code textAnswerable} to true, which is what a plain chat question is.
     */
    public Classification(HarnessState state, Confidence confidence, String reason, Origin origin) {
        this(state, confidence, reason, origin, true);
    }

    public enum Origin { BEHAVIORAL, PLUGIN }
}

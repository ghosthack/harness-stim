package io.github.ghosthack.harnessstimulator;

import java.time.Duration;
import java.util.Optional;

/**
 * Stimulation strategy and rails. {@code questionAnswer} empty means
 * waiting-for-question is notify-only, like approval always is.
 *
 * <p>{@code escalateAfterStalls} counts consecutive stimuli after which the harness
 * did nothing at all — no output, no turn. It is a liveness rail, not a progress
 * one: a harness that answers every nudge without advancing the work resets the
 * counter and never escalates, which is correct. Measuring real progress would
 * mean knowing what the task is, and anything that can measure advancement toward
 * a task can measure arrival at it — a completion predicate, which this design
 * refuses to trust and which belongs to a self-loop anyway. What bounds an
 * endlessly-polite harness is the budget and the human, not escalation.
 */
public record StrategyConfig(
        String harness,
        String idleMessage,
        OnIdle onIdle,
        Optional<String> questionAnswer,
        int maxStimuli,
        Duration initialBackoff,
        double backoffMultiplier,
        int escalateAfterStalls,
        Duration pollInterval,
        Duration shortQuiescence,
        Duration longQuiescence,
        boolean probeFallbackIdle) {

    /**
     * What an idle harness receives. NUDGE types {@code idleMessage} into the ongoing
     * conversation. RESET wipes the harness context first (its /clear-equivalent slash
     * command) and then types {@code idleMessage} again — a fresh-context loop in which
     * the repo, not the conversation, carries state between iterations. The iteration
     * boundary is idle detection, the exact signal the nudge already trusts; it is not
     * a completion signal, and the stop conditions are unchanged (budget, escalation,
     * the human).
     */
    public enum OnIdle { NUDGE, RESET }

    public static StrategyConfig defaults() {
        return new StrategyConfig(
                "cursor-agent",
                // deferential by design: detection can misread "main idle, subagents
                // working" as plain idle (and pattern rot slips past the echo probe
                // there, since the input box is real) — the agent knows its own
                // pending work, so the nudge defers to that knowledge
                "if you are waiting on subagents or background work, keep waiting; "
                        + "otherwise continue with the next item",
                OnIdle.NUDGE,
                Optional.empty(),
                25,
                Duration.ofSeconds(20),
                1.5,
                3,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                true);
    }
}

package io.github.ghosthack.harnessstimulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Behavioral-first state detection. The core signal is screen quiescence, which
 * survives harness UI updates; the per-harness plugin's textual patterns only
 * refine a quiescent screen. Stateful: tracks the last screen across calls.
 */
public final class StateDetector {

    /**
     * If the pane's foreground process is one of these while the harness is supposed to
     * be running, the harness has exited and left a shell prompt behind. That prompt is
     * quiescent and passes the echo probe like any other real text input, so without
     * this check the generic idle fallback would type the configured idle message into
     * the shell — which would then execute it as a command, once per stimulus.
     */
    private static final Set<String> SHELLS =
            Set.of("zsh", "bash", "sh", "fish", "dash", "ksh", "tcsh", "csh");

    /** Also the safety gate for `run`: a launch command may only ever be typed into a shell. */
    static boolean isShell(String foregroundCommand) {
        return SHELLS.contains(foregroundCommand);
    }

    private final HarnessPlugin plugin;
    private final Clock clock;
    private final Duration shortQuiescence;
    private final Duration longQuiescence;

    private String lastText;
    private Instant lastChange;

    public StateDetector(HarnessPlugin plugin, Clock clock,
                         Duration shortQuiescence, Duration longQuiescence) {
        this.plugin = plugin;
        this.clock = clock;
        this.shortQuiescence = shortQuiescence;
        this.longQuiescence = longQuiescence;
    }

    public Classification classify(Screen screen) {
        if (screen.paneDead()) {
            return new Classification(HarnessState.BROKEN, Confidence.HIGH, "pane dead or session gone",
                    Classification.Origin.BEHAVIORAL);
        }
        String foreground = screen.foregroundCommand();
        if (!foreground.isEmpty() && !plugin.ownsProcess(foreground) && isShell(foreground)) {
            return new Classification(HarnessState.BROKEN, Confidence.HIGH,
                    "pane is at a " + foreground + " prompt: the " + plugin.processName()
                            + " process is not running (never started, exited, or crashed)",
                    Classification.Origin.BEHAVIORAL);
        }
        Instant now = clock.instant();
        String comparable = plugin.stripVolatile(screen.text());
        if (!comparable.equals(lastText)) {
            lastText = comparable;
            lastChange = now;
            return new Classification(HarnessState.WORKING, Confidence.HIGH, "screen output changing",
                    Classification.Origin.BEHAVIORAL);
        }
        Duration quiet = Duration.between(lastChange, now);
        if (quiet.compareTo(shortQuiescence) < 0) {
            return new Classification(HarnessState.WORKING, Confidence.MEDIUM,
                    "quiescent only " + quiet.toSeconds() + "s, may still be working",
                    Classification.Origin.BEHAVIORAL);
        }
        Optional<Classification> refined = plugin.classifyQuiescent(screen, quiet);
        if (refined.isPresent()) {
            return refined.get();
        }
        if (quiet.compareTo(longQuiescence) >= 0) {
            return new Classification(HarnessState.IDLE, Confidence.HIGH,
                    "quiescent " + quiet.toSeconds() + "s, generic behavioral fallback",
                    Classification.Origin.BEHAVIORAL);
        }
        return new Classification(HarnessState.UNRECOGNIZED_PROMPT, Confidence.LOW,
                "quiescent " + quiet.toSeconds() + "s but no pattern matched",
                Classification.Origin.BEHAVIORAL);
    }
}

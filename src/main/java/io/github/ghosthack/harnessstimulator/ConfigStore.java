package io.github.ghosthack.harnessstimulator;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Persists per-session setup under ~/.harness-stimulator/<session>.properties so
 * re-attaching to an already-running session restores the same profile and strategy.
 */
public final class ConfigStore {

    /**
     * {@code conversation} is provenance: a human-readable key for the harness
     * conversation that `run` launched under this handle (e.g. "codex --resume task-a"),
     * so a later `run` reusing the handle can tell it is about to hijack a different
     * conversation. Empty means unknown — `setup` adopts sessions it never launched and
     * records no claim about what is in them.
     */
    public record SessionConfig(String session, String workdir, StrategyConfig strategy,
                                String conversation, String model) {

        public SessionConfig(String session, String workdir, StrategyConfig strategy) {
            this(session, workdir, strategy, "", "");
        }

        public SessionConfig(String session, String workdir, StrategyConfig strategy, String conversation) {
            this(session, workdir, strategy, conversation, "");
        }
    }

    private final Path dir;

    public ConfigStore(Path dir) {
        this.dir = dir;
    }

    public static ConfigStore inUserHome() {
        return new ConfigStore(Path.of(System.getProperty("user.home"), ".harness-stimulator"));
    }

    public Path auditLogPath(String session) {
        return dir.resolve(session + ".audit.jsonl");
    }

    /** Side-channel control file; its first word is a command ("stop" for now). */
    public Path controlPath(String session) {
        return dir.resolve(session + ".control");
    }

    /** Holds the pid of the stimulator attached to the session, so a second one can refuse. */
    public Path lockPath(String session) {
        return dir.resolve(session + ".lock");
    }

    /** Live stimuli-used/budget snapshot, refreshed on every stimulus by whoever is
     * attached. Plain file I/O rather than a CLI round-trip so a host UI (e.g. a session
     * manager's sidebar) can read it on a tight poll without spawning a process per row. */
    public Path statusPath(String session) {
        return dir.resolve(session + ".status");
    }

    public void saveStatus(String session, int stimuliSent, int maxStimuli) {
        Properties p = new Properties();
        p.setProperty("stimuliSent", Integer.toString(stimuliSent));
        p.setProperty("maxStimuli", Integer.toString(maxStimuli));
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(statusPath(session))) {
                p.store(w, null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Best-effort: a leftover status file only matters until the next attach overwrites it. */
    public void deleteStatus(String session) {
        try {
            Files.deleteIfExists(statusPath(session));
        } catch (IOException e) {
            // ignored — see above
        }
    }

    public void save(SessionConfig config) {
        Properties p = new Properties();
        StrategyConfig s = config.strategy();
        p.setProperty("workdir", config.workdir());
        p.setProperty("harness", s.harness());
        p.setProperty("idleMessage", s.idleMessage());
        // stored in the spelling the CLI documents, and read back case-insensitively:
        // this file is documented as user-editable, and a hand-written "reset" must not
        // brick every command that loads the config
        p.setProperty("onIdle", s.onIdle().name().toLowerCase(Locale.ROOT));
        s.questionAnswer().ifPresent(a -> p.setProperty("questionAnswer", a));
        p.setProperty("maxStimuli", Integer.toString(s.maxStimuli()));
        p.setProperty("initialBackoffSeconds", Long.toString(s.initialBackoff().toSeconds()));
        p.setProperty("backoffMultiplier", Double.toString(s.backoffMultiplier()));
        p.setProperty("escalateAfterStalls", Integer.toString(s.escalateAfterStalls()));
        p.setProperty("pollSeconds", Long.toString(s.pollInterval().toSeconds()));
        p.setProperty("shortQuiescenceSeconds", Long.toString(s.shortQuiescence().toSeconds()));
        p.setProperty("longQuiescenceSeconds", Long.toString(s.longQuiescence().toSeconds()));
        p.setProperty("probeFallbackIdle", Boolean.toString(s.probeFallbackIdle()));
        if (!config.conversation().isEmpty()) {
            p.setProperty("conversation", config.conversation());
        }
        if (!config.model().isEmpty()) {
            p.setProperty("model", config.model());
        }
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(configPath(config.session()))) {
                p.store(w, "harness-stimulator session config");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<SessionConfig> load(String session) {
        Path path = configPath(session);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(path)) {
            p.load(r);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        StrategyConfig d = StrategyConfig.defaults();
        StrategyConfig strategy = new StrategyConfig(
                p.getProperty("harness", d.harness()),
                p.getProperty("idleMessage", d.idleMessage()),
                StrategyConfig.OnIdle.valueOf(p.getProperty("onIdle", d.onIdle().name())
                        .strip().toUpperCase(Locale.ROOT)),
                Optional.ofNullable(p.getProperty("questionAnswer")),
                intOf(p, "maxStimuli", d.maxStimuli()),
                secondsOf(p, "initialBackoffSeconds", d.initialBackoff()),
                Double.parseDouble(p.getProperty("backoffMultiplier",
                        Double.toString(d.backoffMultiplier()))),
                intOf(p, "escalateAfterStalls", d.escalateAfterStalls()),
                secondsOf(p, "pollSeconds", d.pollInterval()),
                secondsOf(p, "shortQuiescenceSeconds", d.shortQuiescence()),
                secondsOf(p, "longQuiescenceSeconds", d.longQuiescence()),
                Boolean.parseBoolean(p.getProperty("probeFallbackIdle",
                        Boolean.toString(d.probeFallbackIdle()))));
        return Optional.of(new SessionConfig(session, p.getProperty("workdir", "."), strategy,
                p.getProperty("conversation", ""), p.getProperty("model", "")));
    }

    private Path configPath(String session) {
        return dir.resolve(session + ".properties");
    }

    private static int intOf(Properties p, String key, int fallback) {
        return Integer.parseInt(p.getProperty(key, Integer.toString(fallback)));
    }

    private static Duration secondsOf(Properties p, String key, Duration fallback) {
        return Duration.ofSeconds(Long.parseLong(p.getProperty(key, Long.toString(fallback.toSeconds()))));
    }
}

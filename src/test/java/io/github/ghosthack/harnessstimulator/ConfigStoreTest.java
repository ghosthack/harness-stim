package io.github.ghosthack.harnessstimulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConfigStoreTest {

    @TempDir
    Path tmp;

    @Test
    void loopModeStrategySurvivesTheRoundTrip() {
        // re-attach restores the persisted strategy; losing onIdle would silently turn
        // a fresh-context loop back into a nudge of one ever-growing conversation
        ConfigStore store = new ConfigStore(tmp);
        StrategyConfig strategy = new StrategyConfig("claude-code",
                "work through TODO.md, next unchecked item", StrategyConfig.OnIdle.RESET,
                Optional.empty(), 10, Duration.ofSeconds(20), 1.5, 3,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(30), true);
        store.save(new ConfigStore.SessionConfig("bs-loop", "/tmp/x", strategy));
        assertEquals(strategy, store.load("bs-loop").orElseThrow().strategy());
    }

    @Test
    void launchModelSurvivesTheRoundTrip() {
        ConfigStore store = new ConfigStore(tmp);
        store.save(new ConfigStore.SessionConfig("bs-model", "/tmp/x",
                StrategyConfig.defaults(), "codex (fresh)", "gpt-5.6-sol"));
        ConfigStore.SessionConfig loaded = store.load("bs-model").orElseThrow();
        assertEquals("gpt-5.6-sol", loaded.model());
        assertEquals("codex (fresh)", loaded.conversation());
    }

    @Test
    void handEditedOnIdleInTheCliSpelling() throws IOException {
        // the CLI and quickstart spell the value lowercase, and the properties file is
        // documented as user-editable — the documented spelling must not brick the config
        ConfigStore store = new ConfigStore(tmp);
        Files.writeString(tmp.resolve("bs-edited.properties"), """
                harness=claude-code
                onIdle=reset
                """);
        assertEquals(StrategyConfig.OnIdle.RESET,
                store.load("bs-edited").orElseThrow().strategy().onIdle());
    }

    @Test
    void configsSavedBeforeLoopModeExistedLoadAsNudge() throws IOException {
        ConfigStore store = new ConfigStore(tmp);
        Files.writeString(tmp.resolve("bs-old.properties"), """
                harness=codex
                idleMessage=keep going
                """);
        assertEquals(StrategyConfig.OnIdle.NUDGE,
                store.load("bs-old").orElseThrow().strategy().onIdle());
    }

    @Test
    void statusIsPlainPropertiesAReaderOutsideThisAppCanParseWithoutIt() throws IOException {
        // a host UI (e.g. a session manager) reads this file directly, with its own
        // Properties parsing, rather than depending on this module — so the round trip
        // has to hold from raw text, not just through ConfigStore's own saveStatus
        ConfigStore store = new ConfigStore(tmp);
        store.saveStatus("bs-run", 3, 25);
        java.util.Properties p = new java.util.Properties();
        try (var r = Files.newBufferedReader(store.statusPath("bs-run"))) {
            p.load(r);
        }
        assertEquals("3", p.getProperty("stimuliSent"));
        assertEquals("25", p.getProperty("maxStimuli"));
    }

    @Test
    void savingStatusAgainOverwritesTheOldSnapshot() {
        ConfigStore store = new ConfigStore(tmp);
        store.saveStatus("bs-run", 1, 25);
        store.saveStatus("bs-run", 2, 25);
        assertEquals(2, propertyInt(store, "bs-run", "stimuliSent"));
    }

    @Test
    void deleteStatusRemovesTheFileAndToleratesItAlreadyBeingGone() {
        ConfigStore store = new ConfigStore(tmp);
        store.saveStatus("bs-run", 1, 25);
        store.deleteStatus("bs-run");
        assertFalse(Files.exists(store.statusPath("bs-run")));
        store.deleteStatus("bs-run"); // must not throw the second time
    }

    private static int propertyInt(ConfigStore store, String session, String key) {
        java.util.Properties p = new java.util.Properties();
        try (var r = Files.newBufferedReader(store.statusPath(session))) {
            p.load(r);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return Integer.parseInt(p.getProperty(key));
    }
}

package io.github.ghosthack.harnessstimulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLogTest {

    @TempDir
    Path tmp;

    private static final Classification IDLE = new Classification(
            HarnessState.IDLE, Confidence.HIGH, "idle", Classification.Origin.PLUGIN);
    private static final Classification WORKING = new Classification(
            HarnessState.WORKING, Confidence.HIGH, "working", Classification.Origin.PLUGIN);

    private static Screen screen(String text) {
        return new Screen(text, false, Instant.EPOCH);
    }

    private List<String> lines(Path p) throws IOException {
        return Files.exists(p) ? Files.readAllLines(p) : List.of();
    }

    @Test
    void repeatedIdenticalStatePollsCollapseToOneLine() throws IOException {
        Path f = tmp.resolve("a.jsonl");
        AuditLog log = new AuditLog(f);
        for (int i = 0; i < 100; i++) {
            log.record(Instant.EPOCH.plusSeconds(i * 2L), "state", IDLE, screen("same screen"), "");
        }
        assertEquals(1, lines(f).size());
    }

    @Test
    void stateTransitionsAreAlwaysRecorded() throws IOException {
        Path f = tmp.resolve("b.jsonl");
        AuditLog log = new AuditLog(f);
        log.record(Instant.EPOCH, "state", IDLE, screen("s1"), "");
        log.record(Instant.EPOCH.plusSeconds(2), "state", IDLE, screen("s1"), "");
        log.record(Instant.EPOCH.plusSeconds(4), "state", WORKING, screen("s2"), "");
        log.record(Instant.EPOCH.plusSeconds(6), "state", IDLE, screen("s3"), "");
        assertEquals(3, lines(f).size());
    }

    @Test
    void heartbeatStillRecordsALineDuringALongUnchangedStretch() throws IOException {
        Path f = tmp.resolve("c.jsonl");
        AuditLog log = new AuditLog(f, AuditLog.DEFAULT_MAX_BYTES, 3, Duration.ofMinutes(5));
        for (int i = 0; i < 900; i++) { // 30 minutes at a 2s poll
            log.record(Instant.EPOCH.plusSeconds(i * 2L), "state", IDLE, screen("same"), "");
        }
        // one at the start plus a heartbeat every 5 minutes — proof of life, not 900 lines
        assertEquals(6, lines(f).size());
    }

    @Test
    void keystrokeEventsAreNeverCollapsedAndAlwaysCarryTheirScreen() throws IOException {
        Path f = tmp.resolve("d.jsonl");
        AuditLog log = new AuditLog(f);
        // a state line first, so the stimulus screen is a repeat and dedupe would bite
        log.record(Instant.EPOCH, "state", IDLE, screen("the screen"), "");
        for (int i = 1; i <= 3; i++) {
            log.record(Instant.EPOCH.plusSeconds(i), "stimulus", IDLE, screen("the screen"), "nudge " + i);
        }
        List<String> out = lines(f);
        assertEquals(4, out.size(), "every keystroke event must be its own line");
        for (int i = 1; i <= 3; i++) {
            assertTrue(out.get(i).contains("nudge " + i));
        }
    }

    @Test
    void bookkeepingLinesBackReferenceAnUnchangedScreen() throws IOException {
        // two different classifications of the same screen: both get a line, but the
        // second does not restate 5 KB of identical text
        Path f = tmp.resolve("e.jsonl");
        AuditLog log = new AuditLog(f);
        String big = "x".repeat(5000);
        log.record(Instant.EPOCH, "state", IDLE, screen(big), "");
        log.record(Instant.EPOCH.plusSeconds(2), "state", WORKING, screen(big), "");
        List<String> out = lines(f);
        assertEquals(2, out.size());
        assertTrue(out.get(0).contains("\"snapshot\":\""), "first occurrence carries the screen");
        assertTrue(out.get(1).contains("\"snapshotUnchanged\":true"));
        assertFalse(out.get(1).contains(big), "the repeat must not restate the screen");
    }

    @Test
    void changedScreenCarriesAFreshSnapshot() throws IOException {
        Path f = tmp.resolve("g.jsonl");
        AuditLog log = new AuditLog(f);
        log.record(Instant.EPOCH, "state", IDLE, screen("one"), "");
        log.record(Instant.EPOCH.plusSeconds(2), "state", WORKING, screen("two"), "");
        assertTrue(lines(f).get(1).contains("\"snapshot\":\"two\""));
    }

    @Test
    void rotationCapsTheFileAndKeepsGenerations() throws IOException {
        Path f = tmp.resolve("h.jsonl");
        AuditLog log = new AuditLog(f, 4096, 3, Duration.ofMinutes(5));
        for (int i = 0; i < 400; i++) {
            log.record(Instant.EPOCH.plusSeconds(i), "stimulus", IDLE,
                    screen("screen " + i + " " + "y".repeat(200)), "n" + i);
        }
        assertTrue(Files.size(f) < 4096 + 2048, "live file stays near the cap");
        assertTrue(Files.exists(tmp.resolve("h.jsonl.1")));
        assertTrue(Files.exists(tmp.resolve("h.jsonl.2")));
        assertFalse(Files.exists(tmp.resolve("h.jsonl.4")), "only keptFiles generations survive");
    }

    @Test
    void keystrokeEventsNeverBackReferenceAcrossARotation() throws IOException {
        // the spec requires every injected keystroke to carry the screen it went into,
        // so a stimulus line must be readable on its own even when the line it would
        // have pointed at has already rotated away
        Path f = tmp.resolve("i.jsonl");
        AuditLog log = new AuditLog(f, 1024, 3, Duration.ofMinutes(5));
        String repeated = "z".repeat(400);
        for (int i = 0; i < 20; i++) {
            log.record(Instant.EPOCH.plusSeconds(i), "stimulus", IDLE, screen(repeated), "n" + i);
        }
        for (String line : lines(f)) {
            assertTrue(line.contains("\"snapshot\":\""), "every stimulus line carries its own screen");
        }
    }
}

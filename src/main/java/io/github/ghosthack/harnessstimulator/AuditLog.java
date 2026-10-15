package io.github.ghosthack.harnessstimulator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;

/**
 * Append-only JSONL audit trail. Every injected keystroke is logged together
 * with a snapshot of the screen it was injected into, so misfires can be debugged.
 *
 * <p>That requirement applies to keystroke and decision events — stimulus, probe,
 * notify, stop, control — which are always written in full. The per-poll {@code state}
 * events are bookkeeping, and writing one per poll with a full screen snapshot is what
 * made a four-minute run cost 196 KB (~3 MB/hour at the default poll interval), growing
 * fastest during exactly the long unattended sessions this app exists to support.
 *
 * <p>Three measures, in order of how much they save:
 *
 * <ul>
 *   <li>A {@code state} event is written only when the classification actually changes,
 *       or when {@code heartbeat} has elapsed since the last one — consecutive identical
 *       classifications are what the poll loop produces by the hundred, and they carry
 *       no information the transition either side of them does not.
 *   <li>A {@code state} line whose screen is identical to the last one written records
 *       {@code "snapshotUnchanged":true} instead of repeating it; walk back to the most
 *       recent line carrying a {@code snapshot} to recover it. Keystroke and decision
 *       events are never back-referenced — they always carry their own screen, so the
 *       spec's requirement holds even for a line read in isolation, or in a generation
 *       whose predecessor has already rotated away.
 *   <li>Size-based rotation as a backstop, keeping {@code keptFiles} generations.
 * </ul>
 */
public final class AuditLog {

    static final long DEFAULT_MAX_BYTES = 8L * 1024 * 1024;
    static final int DEFAULT_KEPT_FILES = 3;
    static final Duration DEFAULT_HEARTBEAT = Duration.ofMinutes(5);

    private final Path path;
    private final long maxBytes;
    private final int keptFiles;
    private final Duration heartbeat;

    private String lastSnapshot;
    private String lastStateKey;
    private Instant lastStateAt;

    public AuditLog(Path path) {
        this(path, DEFAULT_MAX_BYTES, DEFAULT_KEPT_FILES, DEFAULT_HEARTBEAT);
    }

    AuditLog(Path path, long maxBytes, int keptFiles, Duration heartbeat) {
        this.path = path;
        this.maxBytes = maxBytes;
        this.keptFiles = keptFiles;
        this.heartbeat = heartbeat;
    }

    public void record(Instant ts, String event, Classification c, Screen screen, String detail) {
        if (event.equals("state") && !stateWorthRecording(ts, c, detail)) {
            return;
        }
        // only bookkeeping may back-reference; a keystroke event always carries its screen
        boolean screenUnchanged = event.equals("state") && screen.text().equals(lastSnapshot);
        String line = "{\"ts\":\"" + ts
                + "\",\"event\":\"" + escape(event)
                + "\",\"state\":\"" + c.state()
                + "\",\"confidence\":\"" + c.confidence()
                + "\",\"reason\":\"" + escape(c.reason())
                + "\",\"detail\":\"" + escape(detail)
                + "\"," + (screenUnchanged
                        ? "\"snapshotUnchanged\":true"
                        : "\"snapshot\":\"" + escape(screen.text()) + "\"")
                + "}\n";
        try {
            Files.createDirectories(path.getParent());
            rotateIfFull();
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!screenUnchanged) {
            lastSnapshot = screen.text();
        }
    }

    /**
     * Collapses the identical classifications a poll loop emits between transitions.
     * Keyed on detail as well as the classification, so the distinct state lines the
     * hold loop writes are not folded into the ordinary ones.
     */
    private boolean stateWorthRecording(Instant ts, Classification c, String detail) {
        String key = c.state() + "|" + c.confidence() + "|" + c.origin() + "|" + detail;
        boolean changed = !key.equals(lastStateKey);
        boolean heartbeatDue = lastStateAt == null || !ts.isBefore(lastStateAt.plus(heartbeat));
        if (changed || heartbeatDue) {
            lastStateKey = key;
            lastStateAt = ts;
            return true;
        }
        return false;
    }

    private void rotateIfFull() throws IOException {
        if (!Files.exists(path) || Files.size(path) < maxBytes) {
            return;
        }
        Files.deleteIfExists(generation(keptFiles));
        for (int i = keptFiles - 1; i >= 1; i--) {
            Path from = generation(i);
            if (Files.exists(from)) {
                Files.move(from, generation(i + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(path, generation(1), StandardCopyOption.REPLACE_EXISTING);
    }

    private Path generation(int n) {
        return path.resolveSibling(path.getFileName() + "." + n);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
}

package io.github.ghosthack.harnessstimulator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Shells out to the tmux binary. */
public final class RealTmux implements Tmux {

    private final Clock clock;

    public RealTmux(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean hasSession(String session) {
        return exec("has-session", "-t", "=" + session).exitCode() == 0;
    }

    @Override
    public void newSession(String session, String workdir) {
        Result r = exec("new-session", "-d", "-s", session, "-c", workdir);
        if (r.exitCode() != 0) {
            throw new TmuxException("tmux new-session failed: " + r.output());
        }
    }

    @Override
    public Screen capture(String session) {
        if (!hasSession(session)) {
            return new Screen("", true, clock.instant());
        }
        // both pane facts in one round trip; the second is the harness-liveness signal
        Result info = exec("display-message", "-p", "-t", paneTarget(session),
                "#{pane_dead}\t#{pane_current_command}");
        String foreground = "";
        if (info.exitCode() == 0) {
            String[] fields = info.output().strip().split("\t", -1);
            if (fields[0].equals("1")) {
                return new Screen("", true, clock.instant());
            }
            if (fields.length > 1) {
                foreground = fields[1].strip();
            }
        }
        Result r = exec("capture-pane", "-p", "-t", paneTarget(session));
        if (r.exitCode() != 0) {
            return new Screen("", true, clock.instant());
        }
        return new Screen(r.output(), false, clock.instant(), foreground);
    }

    @Override
    public void sendText(String session, String text) {
        Result r = exec("send-keys", "-t", paneTarget(session), "-l", text);
        if (r.exitCode() != 0) {
            throw new TmuxException("tmux send-keys failed: " + r.output());
        }
        // TUIs with paste detection (cursor-agent among them) treat a CR arriving
        // within milliseconds of the text as a literal newline, not a submit.
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TmuxException("interrupted between text and Enter", e);
        }
        Result enter = exec("send-keys", "-t", paneTarget(session), "Enter");
        if (enter.exitCode() != 0) {
            throw new TmuxException("tmux send-keys Enter failed: " + enter.output());
        }
    }

    @Override
    public void sendLiteral(String session, String text) {
        Result r = exec("send-keys", "-t", paneTarget(session), "-l", text);
        if (r.exitCode() != 0) {
            throw new TmuxException("tmux send-keys (literal) failed: " + r.output());
        }
    }

    @Override
    public void sendBackspace(String session) {
        Result r = exec("send-keys", "-t", paneTarget(session), "BSpace");
        if (r.exitCode() != 0) {
            throw new TmuxException("tmux send-keys BSpace failed: " + r.output());
        }
    }

    @Override
    public int attachedReadWriteClients(String session) {
        Result r = exec("list-clients", "-t", "=" + session, "-F", "#{client_readonly}");
        if (r.exitCode() != 0) {
            return 0;
        }
        return (int) r.output().lines()
                .filter(l -> !l.isBlank())
                .filter(l -> !l.strip().equals("1"))
                .count();
    }

    /** Pane-targeting commands need the trailing colon; bare {@code =session} is only a session target. */
    private static String paneTarget(String session) {
        return "=" + session + ":";
    }

    private record Result(int exitCode, String output) {
    }

    private static Result exec(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("tmux");
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            return new Result(code, out);
        } catch (IOException e) {
            throw new TmuxException("failed to run tmux " + String.join(" ", args), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TmuxException("interrupted running tmux " + String.join(" ", args), e);
        }
    }

}

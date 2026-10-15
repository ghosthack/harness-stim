package io.github.ghosthack.harnessstimulator;

/** Escalation channel to the human. Console-only for now; Slack/push are future extensions. */
public interface Notifier {

    void alert(String message);

    static Notifier console() {
        return message -> System.out.println("[babysit] " + message);
    }
}

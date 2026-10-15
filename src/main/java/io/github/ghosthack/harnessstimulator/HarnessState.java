package io.github.ghosthack.harnessstimulator;

/** States the monitored harness can be in, per spec.txt. */
public enum HarnessState {
    WORKING,
    IDLE,
    WAITING_FOR_APPROVAL,
    WAITING_FOR_QUESTION,
    BROKEN,
    UNRECOGNIZED_PROMPT
}

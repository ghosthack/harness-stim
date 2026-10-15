# harness-stim

Keeps coding agents moving inside tmux sessions. It watches terminal output,
classifies the agent's state, and sends configured prompts when the agent is idle.
It supports Codex, Claude Code and cursor-agent.

Requires JDK 26 or newer and tmux on macOS or Linux. The Java artifact is
platform-independent; the session backend requires tmux. There are no runtime
Java dependencies.

## Build and run

```sh
./scripts/build.sh
java -jar target/harness-stim-0.1.0.jar run --harness codex --last \
  --name work --idle-message "keep going"
```

See [quickstart.md](quickstart.md) for commands, configuration and session control.
The build helper uses empty Maven settings and an isolated dependency cache.
Tests use fake sessions and do not launch coding agents or touch existing tmux
sessions.

## Maven dependency

```xml
<dependency>
  <groupId>io.github.ghosthack</groupId>
  <artifactId>harness-stim</artifactId>
  <version>0.1.0</version>
</dependency>
```

The main JAR is both the embeddable library and standalone CLI; no additional
CLI classifier is needed. Java packages remain
`io.github.ghosthack.harnessstimulator` for existing integrations.

It never answers permission prompts. It has no automatic completion decision:
a run ends when its stimulus budget is exhausted, it escalates, or you detach it.

## License and public source cut

Original code is MIT licensed. See [LICENSE](LICENSE) and
[THIRD-PARTY.md](THIRD-PARTY.md). This repository starts with an allowlisted
snapshot, without importing development history. Subsequent exports append
public commits. See [PUBLISHING.md](PUBLISHING.md).

# Quickstart

```sh
JAR=target/harness-stim-0.1.0.jar    # after: mvn -q package -DskipTests

# START — resumes your agent's last conversation and keeps it moving
java -jar $JAR run --harness codex --last --name work --idle-message "keep going"

# PEEK — watch it, read-only.                        Leave with Ctrl-C
java -jar $JAR peek work

# END — stop babysitting. The agent keeps running.
java -jar $JAR detach work

# HANDOFF — wait until no stimulus can race a direct tmux attach
java -jar $JAR detach work --wait

# CLOSE — wait for idle, then exit the harness and tmux cleanly
java -jar $JAR close work
```

`work` is any name you pick, to refer to it later. That's the whole thing.

Resuming is the point — the app is for a conversation that already has context
worth keeping. Use `--resume <id|name>` for a specific one, or drop both flags
to start fresh.

---

## The rest, only if you need it

**Other commands.** `status work` (one-shot state), `stop work` / `resume work`
(pause and unpause nudging), `steal work` (take the keyboard — leave with
**Ctrl-b**, release, then **d**), `attach work` (resume babysitting after Ctrl-C),
`inspect work --json` (machine-facing ownership/config status).

`close work` is a synchronous graceful teardown: it stops stimulation, waits
for a recognized idle composer with no human tmux client attached, invokes the
harness's verified native exit command, and exits the pane shell. Ctrl-C while
it is waiting leaves the harness and tmux running. Currently this is grounded
for Claude Code (`/exit`); other harnesses are refused rather than guessed.

**Kill it all:** `tmux kill-session -t work`.

**`run` options.** `--harness` (`codex` | `claude-code` | `cursor-agent`),
`--last` / `--resume <id|name>` (which conversation to carry on; omit both for a
new one), `--name` (handle; derived if omitted),
`--dir` (default: here), `--prompt` (first instruction — optional; without it,
use `steal` to type the task), `--model` (codex/claude-code model; omit to inherit),
`--harness-args` (flags for the agent binary),
`--idle-message` (the nudge), `--on-idle` (`nudge` | `reset`; see loop mode
below), `--question-answer`, `--max-stimuli` (0 = unlimited),
`--escalate-after`.

**Loop mode.** `--on-idle reset` wipes the harness's context at every idle
(claude-code `/clear`, codex `/new`) and types the instruction again, instead of
nudging the same conversation:

```sh
java -jar $JAR run --harness claude-code --on-idle reset \
    --prompt "work through TODO.md, one unchecked item, then stop"
```

The repo carries the state between iterations, so the instruction must be
self-contained — `--prompt "continue"` cannot work, there is nothing to continue
from. `--prompt` is reused every iteration (`--idle-message` overrides it), and
`--max-stimuli` counts iterations. Every instruction lands in a wiped context,
including the first. cursor-agent has no known reset command, so the mode
refuses it.

**Codex.** Use `--harness-args "--ask-for-approval never --sandbox workspace-write"`,
or it asks permission for even read-only commands and you spend the run
approving things. Set `notice.hide_rate_limit_model_nudge = true` in
`~/.codex/config.toml` so its rate-limit popup can't block an unattended run.

**Three things that surprise people.**

- It never answers permission prompts. It stops and tells you. Deliberate.
- There is no "done" — a run ends on the nudge budget, on escalation, or when
  you decide it's finished and detach.
- First time in a directory, an agent may ask if it trusts it. `--prompt` is not
  sent into that dialog; clear it once with `steal`.

**Files.** `~/.harness-stimulator/work.properties` (config),
`work.audit.jsonl` (every keystroke + the screen it went into, rotates at 8 MB).

See [README.md](README.md) for the public build and package overview.

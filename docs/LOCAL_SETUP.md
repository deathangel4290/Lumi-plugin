# Working on LumiWeapons locally

How to continue the plugin work on your own computer instead of a cloud session. Everything the
project needs is in the GitHub repository. The conversation itself is optional (see the end).

## 1. Install the tools (once)

| Tool | Why | Windows |
|---|---|---|
| Git | get the code, push your work | git-scm.com (includes Git Bash) |
| Java 21 (JDK) | build the plugin | Eclipse Temurin 21 from adoptium.net |
| Maven 3.9+ | the build tool | maven.apache.org, add its `bin` folder to PATH |
| Python 3 + PyYAML | the checks in `tools/` | python.org, then `pip install pyyaml` |
| Claude Code | the assistant | `npm install -g @anthropic-ai/claude-code`, or the desktop app |

## 2. Get the code

```
git clone https://github.com/deathangel4290/lumi-plugin.git Lumi-plugin
cd Lumi-plugin
git checkout claude/charming-gauss-8znkiy
```

All the work is on `claude/charming-gauss-8znkiy`; `main` only has the first commit. If the art
session already has a clone on this computer, use that one and just `git pull`.

## 3. Build and check

```
mvn -B package          # makes target/LumiWeapons-1.3.0.jar
tools/check.sh          # config check, lore check, pack check (run in Git Bash on Windows)
```

Locally Maven can download paper-api from repo.papermc.io, so you don't need
`tools/build-paper-api.sh` (that's only for cloud sessions, which can't reach it).

The resource pack zip is `pack.mcmeta` and `assets/` from `resourcepack/`, zipped together.

## 4. Start Claude there

Run `claude` inside the `Lumi-plugin` folder. It reads `CLAUDE.md` by itself, which holds the
project rules: who owns what, the git workflow, the code/art contracts and how to build.

A good first message: *"Read docs/NEXT_BATCH.md and git log --oneline -15, then let's continue."*

One local Claude can now do both jobs (code and art), since both are on your computer. Keep the
commit messages written for "the other session" anyway; they're the project's history.

## Where things stand (2026-11-15)

- 30 legendaries in `config.yml`. The newest twelve (Earthshaker Hammer ... Battle Horn) are
  coded, configured and in `lore.yml`, but haven't been played on a server yet.
- Every weapon with an ultimate: the ability key is Ability 1, sneak + ability key is Ability 2,
  and a double-tap of the ability key fires the ultimate (`ultimates.double-tap-millis`).
- The pack has placeholder looks (tinted vanilla items) for custom_model_data 23-34. The real
  models are the art to-do in `docs/NEW_WEAPONS.md`.
- Open decisions and bigger work: `docs/NEXT_BATCH.md`.

## The old conversation (optional)

The cloud conversation is about 10 MB. You don't need it: the repository, `CLAUDE.md` and the
docs above hold what matters, and a fresh session works better than reloading that much history.

If you want it anyway, for reading or to resume it:
- It's a `.jsonl` file (one JSON message per line).
- Claude Code keeps conversations in `~/.claude/projects/<folder>/` (on Windows
  `C:\Users\<you>\.claude\projects\<folder>\`). `<folder>` is the project's full path with every
  character other than letters and digits turned into `-`, e.g. `C:\Users\you\Lumi-plugin`
  becomes `C--Users-you-Lumi-plugin`. Put the file there and `claude --resume` should list it.
- It refers to the cloud's paths (`/home/user/Lumi-plugin`) and tools, so resuming it locally may
  not work perfectly.

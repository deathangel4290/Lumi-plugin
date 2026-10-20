# Luminara / LegendaryWeapons

A Paper 1.21.11 plugin (`src/`), its resource pack (`resourcepack/`), and the animated BetterModel
models for the bosses and the Token Trader (`bettermodel/models/`).

## Two Claude sessions work on this repo

| Session | Owns |
|---|---|
| Plugin session (cloud) | Java code, `config.yml`, `plugin.yml`, `pom.xml`, docs, building the jar |
| Art session (local, Windows) | Resource pack art: textures, item models, `.bbmodel` files, `docs/previews/` |

Either one may touch the other's area when a change needs both sides, but say so in the commit
message so the other session notices it when it pulls.

### GitHub is the shared copy

- Work branch: **`claude/charming-gauss-8znkiy`**. Both sessions commit to it. `main` only has the
  initial commit until this branch is merged.
- **Before you start:** `git pull --rebase origin claude/charming-gauss-8znkiy`, then read
  `git log --oneline -15` to see what the other session did.
- **When you finish a piece of work:** commit, `git pull --rebase` again, then
  `git push origin claude/charming-gauss-8znkiy`. Push often, in small commits, so the other
  session does not work from stale files.
- If a rebase conflicts in a file the other session owns, keep their version and re-apply your
  change on top. Never force-push.
- Write commit messages for the other session: what changed, and anything it must do (for example,
  "new animation `howl` on hollow_warden, the code doesn't play it yet").
- Don't commit build output: `target/`, `dist/`, `javac.*.args`, or zips of the source or the pack.

## Contracts between code and art

Renaming either side breaks the other, so change both in the same commit or leave a note.

- **Effect sprites:** the code spawns `luminara:vfx/<name>` item models (see `SpriteFx.java` and
  `RitualScene.java`). Files: `assets/luminara/items/vfx/`, `models/vfx/`, `textures/item/vfx/`.
  - Slashes are crescents in the texture's XZ plane, bulging toward +x (to the right in the PNG).
    The code aims them by that.
  - `slash_tint` and `flash_tint` are tinted by the code: `tints: [{type: custom_model_data, index: 0}]`.
- **Ritual:** `luminara:ritual_altar` and `luminara:ritual_crystal`. The crystal is tinted the same
  way, and the rune circles are `vfx/ritual_circle_outer` and `vfx/ritual_circle_inner`.
- **Ancient Altars:** they use the item model named in `ritual.ancient-altars.model`, which is
  `luminara:ritual_altar` for now. To make them look different, add a `luminara:ancient_altar` model
  and change that config value in the same commit.
- **Boss and trader animations:** the code plays these by name. A missing one is skipped, but then
  that moment has no animation.

  | Model | Played by the code |
  |---|---|
  | `hollow_warden` | attack, roar, enraged |
  | `wither_king` | attack, cast, nova, rain, enraged |
  | `wither_king_landed` | attack, slam, leap, landed |
  | `leviathan` | attack, drag, bolt, whirl, geyser, wave, summon, flood, surfaced |
  | `ender_colossus` | attack, cast, banish, pull, rift, stomp, split, scramble, enraged |
  | `token_trader` | greet, trade |

  BetterModel plays `idle`, `walk`, `idle_fly`, `walk_fly` and `spawn` by itself.
  - The model names are the `bettermodel:` keys in `config.yml`. Existing servers get them through
    `ConfigManager.RETUNED`.
- **Weapon models:** item model ids and custom_model_data numbers are set in `config.yml`; the pack's
  `assets/minecraft/items/*.json` must match them.

## Lore

Weapon tooltips are built from `src/main/resources/lore.yml`. The plugin session owns it: it
describes what the code does. Lines are `what | result`, and `{s:key}` and the other placeholders
read the weapon's numbers from `config.yml`.

## Menus

Every chest menu extends `guis/Menu` and builds its buttons with `guis/Icon`. Those two classes hold
the shared look: frame, colours, sounds, controls in fixed places on the bottom row, and pages.
`/lumi` is the hub. A menu icon can use a custom item model with `Icon.model("luminara:...")`, so
the art session can give menus their own icons later.

## Building

- `mvn -B package` produces `target/LegendaryWeapons-1.2.0.jar`. It needs paper-api 1.21.11 and
  BetterModel's `bettermodel-api` and `bettermodel-bukkit-api` 2.2.0 (provided; not shaded).
- The pack zip holds `pack.mcmeta` and `assets/` from `resourcepack/`.
- **1.21.11 renames:** `CHAIN` is `IRON_CHAIN`. When a material differs between versions, look it up
  with `Material.matchMaterial` instead of naming the constant.
- The scripts in `resourcepack/_source/` generated the **old** textures and models. Running them
  overwrites the current art, so don't run them unless the art session asks for it.

## Config changes for existing servers

A server keeps its own `config.yml`. When you change a default that existing servers should also
get, add a line to `ConfigManager.RETUNED` with the next revision number, and bump
`balance-revision` in `config.yml`. The line only changes values the server never edited.

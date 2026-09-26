# Luminara — LegendaryWeapons

Paper 1.21.11 plugin: legendary weapons forged at ritual altars, weapon fragments, and admin-run
events. The `resourcepack/` folder is the matching Luminara resource pack.

## Building

```
mvn package          # -> target/LegendaryWeapons-<version>.jar
```

Zip the *contents* of `resourcepack/` (so `pack.mcmeta` sits at the root of the zip) to get the
resource pack.

## Weapons

| Tier | Weapon | Ability |
|---|---|---|
| I | Ender Glaive, Hermes's Blade | spear throw / pearl; dash through players |
| II | Chione's Blade, Lost Trident, Blade of Diminution, **Tether Chain** | freeze + Ice Wave; water wall; shrink yourself; hook and pull |
| III | Scythe of Diablo, Axe of Zeus, Hellstorm, Titan's Axe, Veil of Mimicry | combos/backstab; throw + stun; volley; armour-breaking Slam; disguise |
| IV | Sword of Eclipse, Luminosity | kill-scaling split blades; dash charges + SuperNova |

Every item's lore lists its ability numbers and cooldown. Lore reads the numbers from
`config.yml` (`{s:dash-cooldown}` etc.), and weapons already in players' inventories are updated
when the config changes.

The action bar shows the held weapon's cooldowns, charges and meters, your Veil disguise, and your
bounty target during a Bounty Hunt.

## Getting legendaries

* **Ritual Altar** — crafted (obsidian/diamonds/nether star) or `/giveritualblock`. Right-click it
  for a 5-slot ritual menu that previews the weapon the ingredients will forge. A running ritual
  shows every player a boss bar with its coordinates, distance and direction — or, when you are in
  the Overworld and it is in the Nether (or the other way round), the coordinates converted to your
  dimension. Breaking the altar ends the ritual and spills the ingredients.
* **Weapon Fragments** (Tier I–IV) — dropped by bosses (`fragments.mob-drops`), found in supply
  drops, won in events. 4 of a tier fuse into one of the next tier at the altar.
* **Luminosity Fragment** — only from events. Luminosity's recipe needs 3.
* `acquisition:` in the config switches ritual crafting, fragment requirements, one-per-server
  legendaries and fusion on or off.

## Events (all started by an admin)

| Command | What it does |
|---|---|
| `/lwevent bounty start [min]` | Everyone gets a target, tracked on the action bar (distance + arrow, or the target's dimension). Killing your target scores on a sidebar leaderboard; the top hunter wins a Luminosity Fragment. |
| `/lwevent koth marker` → place it → `/lwevent koth start [min]` | King of the Hill. A player alone on the hill scores each second; two or more = contested. A floating leaderboard sits above the hill. Winner gets a Luminosity Fragment. |
| `/lwevent supplydrop <early\|mid\|late> [count] [world]` | Crates fall at random safe spots inside the world border, with a beam until claimed. Loot tables per tier are in the config. |
| `/lwevent mostwanted set <name>` → `confirm` | The named player is anonymous from the moment they join (no join message, neutral name, default skin). After an admin confirms, they get 20 hearts and Speed II and their rough position is broadcast regularly. On death: a blast throws players back, a loot chest appears with a Luminosity Fragment in the centre, and they are banned (`events.most-wanted.ban-on-death`). |
| `/lwevent status`, `/lwevent stopall` | Overview; stop bounty + KotH without prizes. |

Winners who are offline when an event ends get their prize the next time they join.

## Commands

| Command | Who | |
|---|---|---|
| `/recipes`, `/binds`, `/wpspark` | everyone | recipe book, ability keybind, fire ability from a client keybind |
| `/lwfx <full\|reduced\|minimal\|off>` | everyone | your own particle level |
| `/gui` | admin | take any weapon, fragment or tool |
| `/resetcd [player]` | admin | clear cooldowns, dash charges/locks, freeze immunity |
| `/setkills <n> [player] [weapon]` | admin | kill count on Sword of Eclipse / Hermes's Blade |
| `/charge [percent] [player] [weapon]` | admin | fill Luminosity's SuperNova or Titan's Slam |
| `/lwtest reset \| finishrituals \| fragments \| give <item> [n]` | admin | undo all effects, finish rituals, get test items |
| `/lw give\|reload\|...` | admin | original admin commands |

Permissions: `legendaryweapons.admin` (op), `legendaryweapons.veil.spy` (op) — see who is really
talking when a disguised player chats.

## Protection and anti-abuse

* Abilities respect the world PvP setting, WorldGuard's `pvp` flag and Towny PvP (soft
  dependencies, detected automatically). Block-changing abilities check build permission.
* Charged ultimates (Luminosity, Titan's Axe) only charge from player hits, are throttled per
  victim and drain out of combat. Cooldowns persist through death and relogging.
* Veil chat is logged with the real sender's name and UUID to the console and `veil-chat.log`.

## Upgrading from 1.1

The old `config.yml` is saved as `config-v1-backup.yml` and replaced with the new defaults on the
first start (many balance values changed). Re-apply custom edits from the backup. Ritual altars and
running rituals from 1.1 are carried over; altars placed in 1.1 that were lost on restart have to
be placed again.

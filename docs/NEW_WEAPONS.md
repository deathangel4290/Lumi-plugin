# The five newest legendaries: models

Done (art session, 2026-10-30; preview in `docs/previews/new_weapons_2026-10-30.png`). The user's
concept art stays in `docs/concepts/` as reference.

| Weapon (config key) | Item | custom_model_data |
|---|---|---|
| Blade of Talaria (`blade-of-talaria`) | `netherite_sword` | 17 |
| Twin Requiem (`twin-requiem`) | `netherite_sword` | 18 |
| Crescent (`crescent`) | `netherite_sword` | 19 |
| Oculus (`oculus`) | `netherite_sword` | 20 |
| Horizon (`horizon`) | `bow` (standby + three draw states) | 21 |
| Talaria's floating shield | `shield` (standby + blocking) | 22 (`weapons.blade-of-talaria.shield-model-data`) |

15 and 16 on the netherite sword are the Sword of Eclipse's light and dark split blades.

Still open, optional: Oculus's giant eye for Judgement. Make `luminara:vfx/oculus_eye` and set
`weapons.oculus.eye-model: "luminara:vfx/oculus_eye"` in the same commit; until then it's an Eye
of Ender scaled up.

# The twelve after those: models wanted

Code, config and lore are done (plugin session, 2026-11-04). The weapons work now and look like
the plain item until the pack has these. Add a `custom_model_data` range-dispatch entry for each
number to the item's `assets/minecraft/items/<item>.json` (new files for the items that have
none yet).

| Weapon (config key) | Item | custom_model_data | Look |
|---|---|---|---|
| Earthshaker Hammer (`earthshaker-hammer`) | `mace` | 23 | stone-and-bronze war hammer |
| Abyssal Trident (`abyssal-trident`) | `trident` (in hand and thrown) | 24 | deep-sea teal and black |
| Shadowblades (`shadowblades`) | `netherite_sword` | 25 | dark blades, blood-red edge |
| Phoenix Wand (`phoenix-wand`) | `blaze_rod` | 26 | flame orange and gold |
| Glacial Glaive (`glacial-glaive`) | `netherite_sword` | 27 | ice-blue glaive |
| Seraphic Scepter (`seraphic-scepter`) | `breeze_rod` | 28 | white-gold, winged head |
| Arcane Crossbow (`arcane-crossbow`) | `crossbow` (standby, pulling, charged) | 29 | violet, runes |
| Chakram of Chaos (`chakram-of-chaos`) | `netherite_sword` | 30 | ring blade, pink-violet. It is also shown flying flat as an item display, so a disc that reads well from above helps |
| Golem Gauntlet (`golem-gauntlet`) | `netherite_axe` | 31 | stone fist, mossy |
| Chronoscourge (`chronoscourge`) | `netherite_hoe` | 32 | gold and deep blue, clock motif |
| Mirror Shield (`mirror-shield`) | `shield` (standby + blocking) | 33 | polished mirror face |
| Battle Horn (`battle-horn`) | `goat_horn` (also the tooting pose) | 34 | brass-bound war horn |

Effects use the existing sprites only (`vfx/ritual_circle_outer`, `vfx/ritual_circle_inner`,
waves and bursts), so nothing else is needed.

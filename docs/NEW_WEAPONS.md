# Five new legendaries: what the art session needs to make

The code for these is done (plugin session). Until their models exist each one shows as its plain
vanilla item, which is safe but plain. Reference art from the user is in `docs/concepts/` (reference
only, not for the pack).

| Weapon (config key) | Item | custom_model_data | Edit / add | Concept |
|---|---|---|---|---|
| Blade of Talaria (`blade-of-talaria`) | `netherite_sword` | 14 | add threshold 14 to `assets/minecraft/items/netherite_sword.json` | `concepts/blade_of_talaria.png` (the sword) |
| Twin Requiem (`twin-requiem`) | `netherite_sword` | 15 | add threshold 15 to `netherite_sword.json` | `concepts/twin_requiem.png` |
| Crescent (`crescent`) | `netherite_sword` | 16 | add threshold 16 to `netherite_sword.json` | `concepts/crescent.png` |
| Oculus (`oculus`) | `breeze_rod` | 17 | new `assets/minecraft/items/breeze_rod.json` (fallback: vanilla breeze rod) | `concepts/oculus.png` |
| Horizon (`horizon`) | `bow` | 18 | new `assets/minecraft/items/bow.json`: it must keep vanilla's draw states (`using_item` + `use_duration`) for both the plain bow and Horizon | `concepts/horizon.webp` |

Like the others: a 2D icon for the `gui` context and a 3D model everywhere else, with a `fixed`
display that stands the weapon upright and centred (the ritual floats it over the altar in that pose).

## Extra models (optional, switched on in config.yml)

- **Talaria's floating shield** (`concepts/blade_of_talaria.png`, the shield): it orbits the
  player and swings in front of them while they block. Make `luminara:talaria_shield`, then set
  `weapons.blade-of-talaria.shield-model: "luminara:talaria_shield"`. Until then it's a vanilla shield.
- **Oculus's giant eye** for Judgement: a big billboarded eye (the black orb in a ring of fire
  from the concept). Make `luminara:vfx/oculus_eye`, then set
  `weapons.oculus.eye-model: "luminara:vfx/oculus_eye"`. Until then it's a scaled-up Eye of Ender.

Change the model and the config value in the same commit, so neither side points at something
that isn't there (a missing model draws as the purple-and-black cube).

## Colours (ritual-colors, used for the ritual and the effects)

| Weapon | Main | Accent |
|---|---|---|
| Blade of Talaria | `#E8C46A` gold | `#C8102E` crimson |
| Twin Requiem | `#C8D8F0` steel | `#6FA8FF` frost blue |
| Crescent | `#D8DCF0` moonlight | `#3F5A8A` night blue |
| Oculus | `#FF8A1F` flame | `#FFD27A` corona |
| Horizon | `#4B4BFF` royal blue | `#F2C45A` gold |

The effects already use the shared sprites (`vfx/slash_tint`, `vfx/flash_tint`,
`vfx/ritual_circle_outer`), tinted per weapon, so nothing else is required.

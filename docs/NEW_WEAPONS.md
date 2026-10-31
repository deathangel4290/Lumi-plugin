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

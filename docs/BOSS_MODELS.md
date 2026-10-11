# Boss and Token Trader models

Every boss works without a model: it shows as its vanilla mob (Warden, Wither, Elder Guardian,
Enderman), and the Token Trader is a plain cleric villager. Each one has a **template** already
wired into the resource pack and the config. You model over the template, export it into the
pack, and switch it on.

| Template | Used by | Blockbench file |
|---|---|---|
| `luminara:boss_hollow_warden` | Hollow Warden | `resourcepack/_source/blockbench/templates/boss_hollow_warden.bbmodel` |
| `luminara:boss_wither_king` | Wither King, flying (phases 1-3) | `.../boss_wither_king.bbmodel` |
| `luminara:boss_wither_king_landed` | Wither King, landed (phase 4) | `.../boss_wither_king_landed.bbmodel` |
| `luminara:boss_leviathan` | Leviathan | `.../boss_leviathan.bbmodel` |
| `luminara:boss_ender_colossus` | Ender Colossus | `.../boss_ender_colossus.bbmodel` |
| `luminara:token_trader` | Token Trader | `.../token_trader.bbmodel` |

The templates are blocky stand-ins at the right proportions (a preview is in
`resourcepack/_source/blockbench/templates/templates_preview.png`).

## The workflow

1. Open the template's `.bbmodel` in Blockbench (Java Block/Item format).
2. Model over it. Keep to the **template rules** below so it lines up with the mob.
3. Export it into the pack, overwriting the template:
   - *File > Export > Export Block/Item Model* to `resourcepack/assets/luminara/models/item/<name>.json`
   - save the texture to `resourcepack/assets/luminara/textures/item/<name>.png`. The template's
     texture is already named that way, so Blockbench writes the right `luminara:item/<name>` reference.
   - if your texture is not animated, delete the old `<name>.png.mcmeta` next to it.
4. In `config.yml`, set `enabled: true` under `bosses.<boss>.model` or `token-shop.trader.model`.
   Then run `/lw reload` (or restart) and resend the pack.

The item definition (`resourcepack/assets/luminara/items/<name>.json`) already points at the model.
You don't need to touch it.

## Template rules

- **Feet on the bottom of the grid:** the model stands on y = -16, the lowest line of
  Blockbench's 3×3×3 grid, centred on x = z = 8. The plugin lifts it so those feet land on the
  mob's feet; no offset needed.
- **Front faces north** (towards -z, Blockbench's "North" side). The model turns with the mob.
- **Size** comes from `scale`: one Blockbench unit is `scale / 16` blocks. The templates use:

| Template | `scale` | Template size | In game |
|---|---|---|---|
| Hollow Warden | 1.32 | 46 units tall | 3.8 blocks |
| Wither King | 2.06 | 38 tall (flying), 48 tall (landed) | 4.9 / 6.2 blocks |
| Leviathan | 2.67 | 48 long, 24 tall | 8 blocks long |
| Ender Colossus | 2.9 | 48 tall | 8.7 blocks |
| Token Trader | 0.975 | 32 to the top of the head (hat above) | a villager, 1.95 |

  Both Wither King templates share one `scale`, so keep the landed one taller *in units*.
  Anything up to the full grid (-16..32 on every axis) is fine. Make it bigger or smaller by
  changing `scale` rather than squeezing the model.
- **Glow:** give glowing faces the `light_emission` property in Blockbench (it is on the
  element), or use an emissive-looking texture. Animated textures (`.png.mcmeta`) work like the
  weapons' do.

## Config

```yaml
model:
  enabled: true
  scale: 1.32            # see the table above
  hide-body: true        # false = show the vanilla mob too (handy while lining it up)
  offset-y: 0.0          # optional nudge, in blocks
  yaw-offset: 0          # optional: degrees to turn the model if it faces the wrong way
  view-range: 2.0
  states:
    idle: "luminara:boss_hollow_warden"            # an item model id ...
    attack: "luminara:boss_hollow_warden_attack"   # ... add more states the same way
    enraged: 1003                                  # ... or a custom_model_data number on `item`
```

An item model id `namespace:path` points to `assets/<namespace>/items/<path>.json` in the pack.
For an extra state, copy a template's item definition to a new file and point it at your
second model.

## States each boss switches to

| Boss | States |
|---|---|
| all | `idle` (required), `attack`, `enraged` (final phase) |
| hollow_warden | `roar` (it just adapted to a damage type) |
| wither_king | `landed` (phase 4: it stops flying and becomes a giant ground body) |
| leviathan | `surfaced` (the vulnerable window above the water) |
| ender_colossus | `cast` (swaps and other tricks) |
| token trader | `idle` only |

A state you don't provide falls back to `idle`, or to `landed` once the Wither King has
landed, so one model per form is enough.

## How it is drawn

The mob turns invisible and keeps doing everything: AI, hitbox, sounds. An item display
follows it every tick, drawn with the `fixed` display transform. It follows rather than rides
because a mob carrying a passenger cannot be teleported, and the bosses teleport.

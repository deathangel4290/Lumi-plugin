# Boss models

Every boss works without a model: it shows as its vanilla mob (Warden, Wither, Elder
Guardian, Enderman). When you add a model, that mob turns invisible and your model rides
it. The mob still does the moving, fighting and sounds, and supplies the hitbox.

## Hooking a model up

In `config.yml` under `bosses.<boss>`:

```yaml
model:
  item: PAPER            # only used for number states (custom_model_data)
  scale: 3.0             # size of the display
  offset-y: 0.0          # raise/lower it on the mob
  hide-body: true        # false = show the vanilla mob too (handy while lining it up)
  view-range: 2.0
  states:
    idle: "luminara:hollow_warden_idle"      # an item model id ...
    attack: "luminara:hollow_warden_attack"
    enraged: 1003                            # ... or a custom_model_data number on `item`
```

An item model id `namespace:path` points to `assets/<namespace>/items/<path>.json` in the
resource pack (1.21.4+ item model definitions). The model is drawn with the `fixed`
display transform, centred on the mob and facing the way the mob faces.

## States each boss switches to

| Boss | States |
|---|---|
| all | `idle` (required), `attack`, `enraged` (final phase) |
| hollow_warden | `roar` (it just adapted to a damage type) |
| wither_king | `landed` (phase 4: it stops flying and becomes a giant ground body) |
| leviathan | `surfaced` (the vulnerable window above the water) |
| ender_colossus | `cast` (swaps and other tricks) |

A state you don't provide falls back to `idle`, so one `idle` model is enough to start.

## Size guide (blocks, at the default `scale` of the mob)

| Boss | Base mob | Mob scale | Roughly |
|---|---|---|---|
| Hollow Warden | Warden | 1.3 | 1.2 wide, 3.8 tall |
| Wither King | Wither (then Wither Skeleton, scale 2.6, when landed) | 1.4 | 1.3 wide, 4.9 tall; landed ~6.2 tall |
| Leviathan | Elder Guardian | 2.2 | 4.4 wide and tall (it swims) |
| Ender Colossus | Enderman | 3.0 | 1.8 wide, 8.7 tall |

Animated parts can use animated textures (`.png.mcmeta`), the same way the weapons do.

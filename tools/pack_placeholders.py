#!/usr/bin/env python3
"""
Gives every legendary in config.yml an entry in the resource pack's item definitions.

Minecraft's range_dispatch on custom_model_data picks the highest threshold at or below the
item's number. A weapon with no entry of its own therefore borrows the look of the legendary just
below it. This script adds an entry for each weapon number that has none, so that can't happen.

The entry is a placeholder: the vanilla item, tinted with the weapon's colour (the brighter of
its ritual-colors). The art session replaces it with real models later; the script never changes
an entry that already exists, so running it again is safe and never touches finished art.

Where the vanilla item draws itself with a special renderer (a trident in the hand, a shield),
the placeholder is the plain vanilla look there, since a tint can't apply to those.

Run from the repository root:  python3 tools/pack_placeholders.py
With --check it only reports weapons without their own entry and changes nothing.
"""
import json
import os
import sys

import yaml

ITEMS = "resourcepack/assets/minecraft/items"

# Plain-model placeholders tint better from a light texture than from dark netherite.
LIGHTER = {
    "minecraft:item/netherite_sword": "minecraft:item/iron_sword",
    "minecraft:item/netherite_axe": "minecraft:item/iron_axe",
    "minecraft:item/netherite_hoe": "minecraft:item/iron_hoe",
}

# Vanilla definitions for items the pack doesn't have a file for yet.
VANILLA = {
    "blaze_rod": {"type": "minecraft:model", "model": "minecraft:item/blaze_rod"},
    "breeze_rod": {"type": "minecraft:model", "model": "minecraft:item/breeze_rod"},
    "netherite_hoe": {"type": "minecraft:model", "model": "minecraft:item/netherite_hoe"},
    "goat_horn": {
        "type": "minecraft:condition",
        "property": "minecraft:using_item",
        "on_false": {"type": "minecraft:model", "model": "minecraft:item/goat_horn"},
        "on_true": {"type": "minecraft:model", "model": "minecraft:item/tooting_goat_horn"},
    },
}


def brightness(hex_colour):
    v = int(hex_colour.lstrip("#"), 16)
    r, g, b = v >> 16 & 255, v >> 8 & 255, v & 255
    return 0.299 * r + 0.587 * g + 0.114 * b


def tint_of(weapon):
    colours = weapon.get("ritual-colors") or ["#FFFFFF"]
    best = max(colours, key=brightness)
    return int(best.lstrip("#"), 16)


def placeholder(fallback, tint):
    """The placeholder for one branch: the branch's vanilla model, tinted when it's a plain model."""
    if isinstance(fallback, dict) and fallback.get("type") == "minecraft:model":
        return {"type": "minecraft:model", "model": LIGHTER.get(fallback["model"], fallback["model"]),
                "tints": [{"type": "minecraft:constant", "value": tint}]}
    return json.loads(json.dumps(fallback))   # a special renderer: keep the vanilla look


def dispatches(node):
    """Every range_dispatch on custom_model_data in a definition."""
    if isinstance(node, dict):
        if node.get("type") == "minecraft:range_dispatch" and node.get("property") == "minecraft:custom_model_data":
            yield node
        for value in node.values():
            yield from dispatches(value)
    elif isinstance(node, list):
        for value in node:
            yield from dispatches(value)


def wrap(vanilla):
    """A vanilla definition with an empty range_dispatch at each plain model, ready for entries."""
    if vanilla.get("type") == "minecraft:model":
        return {"type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data", "index": 0,
                "entries": [], "fallback": vanilla}
    out = dict(vanilla)
    for key in ("on_true", "on_false", "fallback"):
        if key in out:
            out[key] = wrap(out[key])
    return out


def main():
    check = "--check" in sys.argv
    config = yaml.safe_load(open("src/main/resources/config.yml"))
    added = 0
    for key, weapon in config["weapons"].items():
        if not isinstance(weapon, dict) or "material" not in weapon:
            continue
        cmd = weapon.get("custom-model-data")
        if cmd is None:
            print(f"!! {key} has no custom-model-data")
            continue
        item = weapon["material"].lower()
        path = os.path.join(ITEMS, item + ".json")
        if os.path.exists(path):
            definition = json.load(open(path))
        elif item in VANILLA:
            definition = {"model": wrap(VANILLA[item])}
        else:
            print(f"!! {key}: no pack file for {item} and no vanilla definition known")
            continue

        changed = False
        for node in dispatches(definition):
            if any(entry["threshold"] == cmd for entry in node["entries"]):
                continue
            if check:
                print(f"!! {key}: no entry of its own at {cmd} in {item}.json")
                added += 1
                break
            node["entries"].append({"threshold": cmd, "model": placeholder(node["fallback"], tint_of(weapon))})
            node["entries"].sort(key=lambda entry: entry["threshold"])
            changed = True

        if changed and not check:
            with open(path, "w") as out:
                json.dump(definition, out, indent=2)
                out.write("\n")
            added += 1
            print(f"+ {key}: placeholder at {cmd} in {item}.json")

    if check:
        print("pack: ok" if added == 0 else f"pack: {added} weapon(s) without their own entry")
        return 1 if added else 0
    print(f"{added} weapon(s) given placeholder entries")
    return 0


if __name__ == "__main__":
    sys.exit(main())

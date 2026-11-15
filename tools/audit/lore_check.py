#!/usr/bin/env python3
"""Every {s:key}-style placeholder in lore.yml must name an ability in that weapon's config, and
every weapon in config.yml needs a lore entry. Run from the repository root."""
import re
import sys

import yaml

config = yaml.safe_load(open("src/main/resources/config.yml"))
lore = yaml.safe_load(open("src/main/resources/lore.yml"))
problems = []
for weapon, entry in lore.items():
    abilities = ((config["weapons"].get(weapon) or {}).get("abilities")) or {}
    for section in entry.values():
        for line in section if isinstance(section, list) else [section]:
            for key in re.findall(r"\{\w+:([a-z0-9-]+)\}", line):
                if key not in abilities:
                    problems.append(f"{weapon}: lore uses {key}, which isn't in its abilities")
for weapon, block in config["weapons"].items():
    if isinstance(block, dict) and "material" in block and weapon not in lore:
        problems.append(f"{weapon}: no lore entry")
print("\n".join(problems) or "lore: ok")
sys.exit(1 if problems else 0)

"""The Ritual Altar and its crystal, built with toon.py (same cel-shaded look as the weapons).

Run from resourcepack/_source:  python3 altar.py ../ <preview-dir>

  luminara:item/ritual_altar     the altar itself. The plugin shows it on an item display over an empty
                                 spawner, so it fills exactly one block: the hitbox and the outline match.
  luminara:item/ritual_crystal   the floating crystal over an idle altar and the four ritual pylons.
                                 Its faces carry tintindex 0, so the plugin colours it per weapon.

Also writes Blockbench copies (.bbmodel) of both next to the previews so they can be edited by hand.
"""
import json, os, sys
import numpy as np
from toon import Model, showcase
from toonexport import save_texture
from bbexport import to_bbmodel

STONE = ['#100d17', '#1d1828', '#2b2440', '#3d3357', '#54486f']          # deep violet basalt
STONE_HI = ['#18131f', '#2a2338', '#3c3254', '#52466f', '#6c5e8c']       # lighter trim stone
GOLD = ['#4a2c06', '#9a6414', '#d99a28', '#f7cf4a', '#fff4a8']
RUNE = ['#2c0a52', '#6a22c0', '#a858ff', '#d8a2ff', '#fbefff']          # glowing amethyst
CRYSTAL = ['#5a5a66', '#9a9aa8', '#cfcfdc', '#ececf6', '#ffffff']       # neutral: tinted in game

BLOCK_DISPLAY = {
    'gui': {'rotation': [30, 225, 0], 'translation': [0, 0, 0], 'scale': [0.625, 0.625, 0.625]},
    'ground': {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [0.25, 0.25, 0.25]},
    'fixed': {'rotation': [0, 0, 0], 'translation': [0, 0, 0], 'scale': [0.5, 0.5, 0.5]},
    'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [0.375, 0.375, 0.375]},
    'thirdperson_lefthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [0.375, 0.375, 0.375]},
    'firstperson_righthand': {'rotation': [0, 45, 0], 'translation': [0, 0, 0], 'scale': [0.4, 0.4, 0.4]},
    'firstperson_lefthand': {'rotation': [0, 225, 0], 'translation': [0, 0, 0], 'scale': [0.4, 0.4, 0.4]},
    'head': {'rotation': [0, 0, 0], 'translation': [0, 0, 0], 'scale': [1.0, 1.0, 1.0]},
}


def altar():
    m = Model()
    m.mat('stone', STONE, 'stone')
    m.mat('trim', STONE_HI, 'stone')
    m.mat('gold', GOLD)
    m.mat('rune', RUNE, 'glow', emit=12)

    # plinth and its gold band
    m.box((0, 0, 0), (16, 2.5, 16), 'stone')
    m.box((0.5, 2.5, 0.5), (15.5, 3.5, 15.5), 'gold')
    # the body, set in from the plinth, with a stepped collar under the top
    m.box((2.5, 3.5, 2.5), (13.5, 11, 13.5), 'stone')
    m.box((1.5, 11, 1.5), (14.5, 12, 14.5), 'trim')
    # corner pillars with gold caps and feet
    for x in (0.5, 12.5):
        for z in (0.5, 12.5):
            m.box((x + 0.25, 3.5, z + 0.25), (x + 2.75, 4.5, z + 2.75), 'gold')
            m.box((x + 0.5, 4.5, z + 0.5), (x + 2.5, 11, z + 2.5), 'trim')
            m.box((x + 0.25, 11, z + 0.25), (x + 2.75, 12, z + 2.75), 'gold')
    # a glowing rune on every side: a diamond with a slit above and below
    for face in ('north', 'south', 'east', 'west'):
        lo, hi = 2.3, 2.55                                  # just proud of the body
        if face == 'north':
            frm, to, axis, origin = (6.6, 5.9, lo), (9.4, 8.7, hi), 'z', (8, 7.3, 2.4)
            slits = [((7.6, 9.3, lo), (8.4, 10.5, hi)), ((7.6, 4.1, lo), (8.4, 5.3, hi))]
        elif face == 'south':
            frm, to, axis, origin = (6.6, 5.9, 16 - hi), (9.4, 8.7, 16 - lo), 'z', (8, 7.3, 13.6)
            slits = [((7.6, 9.3, 16 - hi), (8.4, 10.5, 16 - lo)), ((7.6, 4.1, 16 - hi), (8.4, 5.3, 16 - lo))]
        elif face == 'west':
            frm, to, axis, origin = (lo, 5.9, 6.6), (hi, 8.7, 9.4), 'x', (2.4, 7.3, 8)
            slits = [((lo, 9.3, 7.6), (hi, 10.5, 8.4)), ((lo, 4.1, 7.6), (hi, 5.3, 8.4))]
        else:
            frm, to, axis, origin = (16 - hi, 5.9, 6.6), (16 - lo, 8.7, 9.4), 'x', (13.6, 7.3, 8)
            slits = [((16 - hi, 9.3, 7.6), (16 - lo, 10.5, 8.4)), ((16 - hi, 4.1, 7.6), (16 - lo, 5.3, 8.4))]
        m.box(frm, to, 'rune', rot=(axis, 45.0, origin))
        for a, b in slits:
            m.box(a, b, 'rune')
    # the top: a full slab with a raised rim and a recessed basin
    m.box((0, 12, 0), (16, 14, 16), 'stone')
    m.box((0, 14, 0), (16, 15.5, 1.5), 'trim')
    m.box((0, 14, 14.5), (16, 15.5, 16), 'trim')
    m.box((0, 14, 1.5), (1.5, 15.5, 14.5), 'trim')
    m.box((14.5, 14, 1.5), (16, 15.5, 14.5), 'trim')
    for x in (0, 13.5):
        for z in (0, 13.5):
            m.box((x, 15.5, z), (x + 2.5, 16, z + 2.5), 'gold')
    # sigil in the basin: two squares, one turned 45 degrees, around a bright core
    m.box((4.5, 14, 4.5), (11.5, 14.25, 11.5), 'gold')
    m.box((5, 14, 5), (11, 14.5, 11), 'rune', rot=('y', 45.0, (8, 14, 8)))
    m.box((6.25, 14.5, 6.25), (9.75, 14.75, 9.75), 'gold')
    m.box((7, 14.75, 7), (9, 15, 9), 'rune')
    return m


def crystal():
    """A long double-ended gem, square in section and turned 45 degrees, with a band round its waist."""
    m = Model()
    m.mat('crystal', CRYSTAL, 'gem')
    m.mat('core', CRYSTAL, 'glow', emit=10)
    r = ('y', 45.0, (8, 8, 8))
    m.box((6, 3.5, 6), (10, 12.5, 10), 'crystal', rot=r)
    m.box((6.75, 12.5, 6.75), (9.25, 14.5, 9.25), 'crystal', rot=r)
    m.box((6.75, 1.5, 6.75), (9.25, 3.5, 9.25), 'crystal', rot=r)
    m.box((7.5, 14.5, 7.5), (8.5, 16, 8.5), 'core', rot=r)
    m.box((7.5, 0, 7.5), (8.5, 1.5, 8.5), 'core', rot=r)
    m.box((5.6, 7.4, 5.6), (10.4, 8.6, 10.4), 'core', rot=r)
    return m


def tinted(model_json):
    for el in model_json['elements']:
        for face in el['faces'].values():
            face['tintindex'] = 0
    return model_json


def write(pack, name, m, tint=False):
    ref = f'luminara:item/{name}'
    out, tex = m.export(ref, BLOCK_DISPLAY)
    if tint:
        tinted(out)
    if pack != '-':
        for d in ('textures/item', 'models/item', 'items'):
            os.makedirs(f'{pack}/assets/luminara/{d}', exist_ok=True)
        save_texture(m, tex, f'{pack}/assets/luminara/textures/item/{name}.png')
        json.dump(out, open(f'{pack}/assets/luminara/models/item/{name}.json', 'w'), indent=1)
        item = {'model': {'type': 'minecraft:model', 'model': ref}}
        if tint:
            # the plugin sets the colour through custom_model_data; amethyst when it does not
            item['model']['tints'] = [{'type': 'minecraft:custom_model_data', 'index': 0, 'default': 0xC88CFF}]
        json.dump(item, open(f'{pack}/assets/luminara/items/{name}.json', 'w'), indent=1)
    return out, tex


if __name__ == '__main__':
    pack, prev = sys.argv[1], sys.argv[2]
    os.makedirs(prev, exist_ok=True)
    shots = []
    for name, build, tint in (('ritual_altar', altar, False), ('ritual_crystal', crystal, True)):
        m = build()
        out, tex = write(pack, name, m, tint)
        to_bbmodel(out, tex, name, f'{prev}/{name}.bbmodel')
        for yaw, pitch in ((35, 22), (-20, 40)):
            shots.append(showcase(out, tex, yaw=yaw, pitch=pitch, size=360))
        print(f'{name}: {len(out["elements"])} elements, {tex.width}px atlas')
    sheet = np.concatenate([np.asarray(s) for s in shots], axis=1)
    from PIL import Image
    Image.fromarray(sheet).save(f'{prev}/altar_preview.png')

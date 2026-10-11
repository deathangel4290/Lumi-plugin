"""Model templates for the bosses and the Token Trader: blocky stand-ins at the right proportions,
already wired into the pack, so a real model only has to replace them.

Run from resourcepack/_source:  python3 templates.py ../ <preview-dir>

Convention (BossModel in the plugin relies on it):
  - the model stands on the BOTTOM of the Blockbench grid (y = -16), centred on x = z = 8
  - the front faces NORTH (towards -z)
  - one Blockbench unit = scale / 16 blocks in game; each template's scale is in SCALE below and in
    config.yml (bosses.<id>.model.scale, token-shop.trader.model.scale)

Writes, per template:
  assets/luminara/items/<name>.json               item model definition (what config.yml names)
  assets/luminara/models/item/<name>.json         the model
  assets/luminara/textures/item/<name>.png(.mcmeta)
  _source/blockbench/templates/<name>.bbmodel     open this in Blockbench, model over it, export
"""
import json, os, sys
import numpy as np
from PIL import Image
from toon import Model, showcase
from toonexport import save_texture
from bbexport import to_bbmodel

GOLD = ['#4a2c06', '#9a6414', '#d99a28', '#f7cf4a', '#fff4a8']

# blocks tall (or long, for the Leviathan) in game  /  template height in Blockbench units * 16
SCALE = {
    'boss_hollow_warden': 1.32,        # 46 units -> 3.8 blocks
    'boss_wither_king': 2.06,          # 38 units -> 4.9 blocks (flying)
    'boss_wither_king_landed': 2.06,   # same scale: 48 units -> 6.2 blocks
    'boss_leviathan': 2.67,            # 48 units long -> 8 blocks
    'boss_ender_colossus': 2.9,        # 48 units -> 8.7 blocks
    'token_trader': 0.975,             # 32 units to the top of the head -> 1.95 blocks (a villager)
}


def pair(m, x0, x1, y0, y1, z0, z1, mat, rot=None):
    """A box and its mirror image across x = 8."""
    m.box((x0, y0, z0), (x1, y1, z1), mat, rot=rot)
    m.box((16 - x1, y0, z0), (16 - x0, y1, z1), mat, rot=rot)


def hollow_warden():
    m = Model()
    m.mat('hide', ['#05161a', '#0a262b', '#103a40', '#195058', '#246c75'], 'stone')
    m.mat('bone', ['#4c4b42', '#7c7b6a', '#a8a690', '#cbc9b2', '#ecead8'])
    m.mat('soul', ['#063f48', '#0f8fa0', '#2fd4e6', '#93f3ff', '#e6ffff'], 'glow', emit=12)
    pair(m, 3, 7, -16, -2, 5, 11, 'hide')                       # legs
    m.box((1, -2, 3), (15, 18, 13), 'hide')                      # torso
    m.box((4, 3, 2.5), (12, 14, 3), 'soul')                      # glowing ribcage
    for y in (5, 8, 11):
        m.box((3, y, 2.4), (13, y + 1, 2.6), 'bone')             # ribs over it
    pair(m, -3, 1, -5, 16, 5, 11, 'hide')                        # arms
    pair(m, -3.5, 1.5, -7, -5, 4.5, 11.5, 'bone')                # knuckles
    m.box((2, 18, 2), (14, 28, 14), 'hide')                      # head
    pair(m, -1, 2, 21, 30, 6, 10, 'soul')                        # tendrils
    pair(m, 4, 6, 22, 24, 1.6, 2, 'soul')                        # eye pits (it is blind; they glow faintly)
    return m


def wither_king(landed=False):
    m = Model()
    m.mat('bone', ['#121214', '#24242a', '#3a3a44', '#55555f', '#76767f'])
    m.mat('ash', ['#1a1622', '#2c2638', '#40384f', '#584e6b', '#73698a'], 'stone')
    m.mat('gold', GOLD)
    m.mat('soul', ['#2a0c4a', '#6a2ab8', '#a868ff', '#d6b0ff', '#f6ecff'], 'glow', emit=12)
    m.mat('blade', ['#0c0b10', '#1d1b24', '#2e2b39', '#45414f', '#6a6578'])

    def head(x0, y0, z0, size, crown):
        m.box((x0, y0, z0), (x0 + size, y0 + size, z0 + size), 'bone')
        e = size * 0.22
        m.box((x0 + size * 0.15, y0 + size * 0.45, z0 - 0.3), (x0 + size * 0.15 + e, y0 + size * 0.45 + e, z0), 'soul')
        m.box((x0 + size * 0.85 - e, y0 + size * 0.45, z0 - 0.3), (x0 + size * 0.85, y0 + size * 0.45 + e, z0), 'soul')
        if crown:
            top = y0 + size
            m.box((x0 - 0.5, top, z0 - 0.5), (x0 + size + 0.5, top + 1.5, z0 + size + 0.5), 'gold')
            for cx in (x0, x0 + size / 2 - 0.75, x0 + size - 1.5):
                m.box((cx, top + 1.5, z0 + size / 2 - 0.75), (cx + 1.5, top + 3, z0 + size / 2 + 0.75), 'gold')

    if not landed:
        # the flying King: a tail of vertebrae, a ribcage, a shoulder bar and three heads
        for i, (w, y) in enumerate(((1.5, -16), (2, -12), (2.5, -8), (3, -4))):
            m.box((8 - w, y, 8 - w), (8 + w, y + 3.5, 8 + w), 'bone')
        for y in (0, 3, 6):
            m.box((2, y, 5), (14, y + 1.5, 11), 'ash')
        m.box((6.5, 0, 6.5), (9.5, 9, 9.5), 'bone')
        m.box((-6, 8, 6), (22, 11, 10), 'ash')
        head(-6, 11, 5, 6, False)
        head(16, 11, 5, 6, False)
        head(3, 11, 3, 8, True)                                     # crown reaches y = 22 (38 units)
    else:
        # landed: a giant crowned skeleton with a greatsword
        pair(m, 3, 7, -16, 2, 6, 10, 'bone')
        m.box((2, 2, 5), (14, 5, 11), 'ash')
        m.box((6.5, 5, 6.5), (9.5, 14, 9.5), 'bone')
        for y in (8, 11, 14):
            m.box((2, y, 4), (14, y + 1.5, 12), 'ash')
        m.box((-2, 17, 5), (18, 20, 11), 'ash')
        pair(m, -4, 0, 2, 17, 6, 10, 'bone')
        head(3, 20, 3, 9, True)                                     # crown reaches y = 32 (48 units)
        m.box((17, -14, 6.5), (19, 21, 9.5), 'blade')               # the blade, planted by its right hand
        m.box((15, 1, 5.5), (21, 2.5, 10.5), 'gold')
        m.box((17.25, 2.5, 7), (18.75, 4, 9), 'soul')
    return m


def leviathan():
    m = Model()
    m.mat('scale', ['#04202e', '#08364a', '#0e5068', '#186e8a', '#2a92ae'], 'stone')
    m.mat('belly', ['#2a4a4a', '#4a7470', '#6e9a92', '#96bcb0', '#c4e0d4'])
    m.mat('fin', ['#063a4a', '#0e6a82', '#22a2bc', '#6ad2e4', '#c8f6ff'], 'glow', emit=8)
    m.mat('eye', ['#3a2a00', '#a07800', '#f0c020', '#fff070', '#ffffe0'], 'glow', emit=15)
    # head at the north end (z = -16), tail at the south end (z = 32)
    m.box((1, -12, -16), (15, 0, -4), 'scale')                     # head
    m.box((2, -16, -15), (14, -12, -6), 'belly')                   # lower jaw
    for x in (2.5, 5, 7.5, 10, 12.5):
        m.box((x, -12, -16.0), (x + 1, -10.5, -15.5), 'belly')     # teeth
    pair(m, 0.6, 1, -6, -3.5, -12, -9, 'eye')
    m.box((0, -14, -4), (16, 2, 6), 'scale')
    m.box((1, -16, -4), (15, -14, 6), 'belly')
    m.box((2, -13, 6), (14, 0, 16), 'scale')
    m.box((4, -12, 16), (12, -2, 24), 'scale')
    m.box((6, -11, 24), (10, -4, 30), 'scale')
    m.box((7.25, -14, 28), (8.75, 2, 32), 'fin')                   # tail fin
    for z, h in ((-2, 6), (4, 7), (10, 5), (17, 4)):
        m.box((7.25, 1 if z < 6 else -1, z), (8.75, (1 if z < 6 else -1) + h, z + 3), 'fin')   # dorsal spines
    pair(m, -6, 0, -8, -7, 0, 6, 'fin')                            # side fins
    return m


def ender_colossus():
    m = Model()
    m.mat('void', ['#050407', '#0c0a12', '#16121f', '#221c2e', '#30283f'], 'stone')
    m.mat('eye', ['#3a0060', '#8a20d0', '#d070ff', '#f0c0ff', '#ffffff'], 'glow', emit=15)
    m.mat('shard', ['#20063a', '#4a1480', '#7a30c0', '#b070f0', '#e8c8ff'], 'gem')
    pair(m, 5, 7, -16, 10, 7, 9, 'void')                           # long thin legs
    m.box((4, 10, 6), (12, 22, 10), 'void')                        # torso
    pair(m, 2, 4, -8, 22, 7, 9, 'void')                            # arms to the knees
    m.box((3, 22, 3), (13, 32, 13), 'void')                        # head
    pair(m, 4, 7, 26, 27, 2.6, 3, 'eye')
    for x, y, z in ((-6, 18, 4), (19, 20, 10), (-4, 8, 12), (18, 6, 3)):
        m.box((x, y, z), (x + 2.5, y + 4, z + 2.5), 'shard', rot=('y', 45.0, (x + 1.25, y, z + 1.25)))
    return m


def token_trader():
    m = Model()
    m.mat('robe', ['#2a0f3a', '#4a1c62', '#6c2c88', '#9244b0', '#b86ad4'])
    m.mat('trim', GOLD)
    m.mat('skin', ['#5a3a28', '#8a5e44', '#b4826a', '#d4a48a', '#f0c8b0'])
    m.mat('token', ['#6a4000', '#c08000', '#ffc020', '#ffe070', '#fffad0'], 'glow', emit=12)
    m.mat('pack', ['#2a1a0c', '#4a3018', '#6a4828', '#8c6438', '#b08850'])
    m.box((3, -16, 4), (13, -2, 12), 'robe')                       # robe, wide at the hem
    m.box((2.5, -16, 3.5), (13.5, -14.5, 12.5), 'trim')
    m.box((4, -2, 5), (12, 8, 11), 'robe')                         # body
    m.box((3, 1, 3.4), (13, 4, 5), 'robe')                         # folded arms
    m.box((6.5, 4.5, 4.3), (9.5, 7.5, 4.8), 'token')               # the token medallion
    m.box((4.5, -1, 11), (11.5, 7, 14), 'pack')                    # satchel on the back
    m.box((4, 8, 4), (12, 16, 12), 'skin')                         # head
    m.box((7, 9, 2), (9, 13, 4), 'skin')                           # the villager nose
    m.box((3.5, 15, 3.5), (12.5, 16.5, 12.5), 'robe')              # hat brim
    m.box((5, 16.5, 5), (11, 20, 11), 'robe')
    m.box((5, 16.5, 4.9), (11, 17.5, 5), 'trim')                   # hat band
    return m


TEMPLATES = {
    'boss_hollow_warden': hollow_warden,
    'boss_wither_king': lambda: wither_king(False),
    'boss_wither_king_landed': lambda: wither_king(True),
    'boss_leviathan': leviathan,
    'boss_ender_colossus': ender_colossus,
    'token_trader': token_trader,
}


def main():
    pack, prev = sys.argv[1], sys.argv[2]
    bb = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'blockbench', 'templates')
    for d in (prev, bb, f'{pack}/assets/luminara/items', f'{pack}/assets/luminara/models/item', f'{pack}/assets/luminara/textures/item'):
        os.makedirs(d, exist_ok=True)
    shots = []
    for name, build in TEMPLATES.items():
        m = build()
        lo, hi = m.bounds()
        assert abs(lo[1] + 16) < 1e-6, f'{name}: feet must be on y = -16, got {lo[1]}'
        ref = f'luminara:item/{name}'
        out, tex = m.export(ref, {})
        save_texture(m, tex, f'{pack}/assets/luminara/textures/item/{name}.png')
        json.dump(out, open(f'{pack}/assets/luminara/models/item/{name}.json', 'w'), indent=1)
        json.dump({'model': {'type': 'minecraft:model', 'model': ref}}, open(f'{pack}/assets/luminara/items/{name}.json', 'w'), indent=1)
        to_bbmodel(out, tex, name, f'{bb}/{name}.bbmodel')
        shots.append(showcase(out, tex, yaw=200, pitch=12, size=300))
        tall = (hi[1] - lo[1]) * SCALE[name] / 16.0
        print(f'{name}: {len(out["elements"])} elements, {tex.width}px atlas, {hi[1] - lo[1]:.0f} units tall = {tall:.1f} blocks')
    Image.fromarray(np.concatenate([np.asarray(s) for s in shots], axis=1)).save(f'{prev}/templates_preview.png')


if __name__ == '__main__':
    main()

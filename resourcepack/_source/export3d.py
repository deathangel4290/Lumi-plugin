"""Export every 3D weapon into the resource pack + Blockbench projects, and point the item definitions at them.
Inventory (gui) keeps the 2D sprite; hands, ground, frames and heads get the 3D model."""
import json, os, sys, copy
from bbexport import to_bbmodel
from weapons3d import MODELS

PACK = sys.argv[1]
BB = sys.argv[2]
TEX = f'{PACK}/assets/luminara/textures/item'
MOD = f'{PACK}/assets/luminara/models/item'
ITEMS = f'{PACK}/assets/minecraft/items'

# y (model units) of the spot the hand closes around; the display translation is derived from it so every
# weapon sits in the fist the way the scythe does (grip at y=6 -> tp 3.0 / fp 3.2).
GRIP = {'scythe_of_diablo': 6, 'luminosity': -4, 'ender_glaive': 0, 'hermes_blade': 5, 'chiones_blade': 5,
        'blade_of_diminution': 5, 'sword_of_eclipse': 5, 'axe_of_zeus': -4, 'titans_axe': -4, 'lost_trident': 0,
        'tether_chain': -9}

def v(*a): return list(a)

def display(grip):
    tp = round(1.8 + (8 - grip) * 0.6, 2); fp = round(2.2 + (8 - grip) * 0.5, 2)
    return {
        'thirdperson_righthand': {'rotation': v(0, -90, 10), 'translation': v(0, tp, 1), 'scale': v(.6, .6, .6)},
        'thirdperson_lefthand': {'rotation': v(0, 90, -10), 'translation': v(0, tp, 1), 'scale': v(.6, .6, .6)},
        'firstperson_righthand': {'rotation': v(0, -90, -20), 'translation': v(1.13, fp, 1.13), 'scale': v(.5, .5, .5)},
        'firstperson_lefthand': {'rotation': v(0, 90, 20), 'translation': v(1.13, fp, 1.13), 'scale': v(.5, .5, .5)},
        'ground': {'rotation': v(0, 0, 0), 'translation': v(0, 2, 0), 'scale': v(.3, .3, .3)},
        'fixed': {'rotation': v(0, 180, 0), 'translation': v(0, 0, 0), 'scale': v(.35, .35, .35)},
        'head': {'rotation': v(0, 180, 0), 'translation': v(0, 13, 7), 'scale': v(.6, .6, .6)},
        'gui': {'rotation': v(30, 225, 0), 'translation': v(0, 0, 0), 'scale': v(.35, .35, .35)},
    }

BOOK = {
    'thirdperson_righthand': {'rotation': v(0, -90, 0), 'translation': v(0, 2, 1), 'scale': v(.55, .55, .55)},
    'thirdperson_lefthand': {'rotation': v(0, 90, 0), 'translation': v(0, 2, 1), 'scale': v(.55, .55, .55)},
    'firstperson_righthand': {'rotation': v(0, -90, 15), 'translation': v(1.13, 3.2, 1.13), 'scale': v(.68, .68, .68)},
    'firstperson_lefthand': {'rotation': v(0, 90, -15), 'translation': v(1.13, 3.2, 1.13), 'scale': v(.68, .68, .68)},
    'ground': {'rotation': v(0, 0, 0), 'translation': v(0, 2, 0), 'scale': v(.5, .5, .5)},
    'fixed': {'rotation': v(0, 180, 0), 'translation': v(0, 0, 0), 'scale': v(1, 1, 1)},
    'head': {'rotation': v(0, 180, 0), 'translation': v(0, 13, 7), 'scale': v(1, 1, 1)},
    'gui': {'rotation': v(30, 225, 0), 'translation': v(0, 0, 0), 'scale': v(.625, .625, .625)},
}

os.makedirs(BB, exist_ok=True)
for name, build in MODELS.items():
    m = build()
    ref = f'luminara:item/{name}_3d'
    disp = BOOK if name == 'veil_of_mimicry' else display(GRIP[name])
    out = m.export(ref, disp)
    m.atlas.img.save(f'{TEX}/{name}_3d.png')
    json.dump(out, open(f'{MOD}/{name}_3d.json', 'w'), indent=1)
    to_bbmodel(out, m.atlas.img, f'{name}_3d', f'{BB}/{name}_3d.bbmodel')
    if name == 'lost_trident':
        # the charge-up pose raises the arm overhead; flip it so the prongs point at the target like vanilla
        thr = copy.deepcopy(out)
        for hand, s in (('thirdperson_righthand', 1), ('thirdperson_lefthand', -1)):
            thr['display'][hand]['rotation'] = v(0, -90 * s, 180)
            thr['display'][hand]['translation'] = v(0, -(disp[hand]['translation'][1]) + 4, 1)
        for hand, s in (('firstperson_righthand', 1), ('firstperson_lefthand', -1)):
            thr['display'][hand]['rotation'] = v(0, -90 * s, 25 * s)
        json.dump(thr, open(f'{MOD}/{name}_3d_throwing.json', 'w'), indent=1)
    print(name, len(out['elements']), 'elements')

# ---- item definitions: wrap each custom_model_data entry in gui -> 2D / else -> 3D
def wrap(entry_model, name):
    return {'type': 'minecraft:select', 'property': 'minecraft:display_context',
            'cases': [{'when': ['gui'], 'model': entry_model}],
            'fallback': {'type': 'minecraft:model', 'model': f'luminara:item/{name}_3d'}}

for item in ('netherite_sword', 'netherite_axe', 'mace', 'book', 'lead'):
    path = f'{ITEMS}/{item}.json'
    d = json.load(open(path))
    for e in d['model']['entries']:
        mdl = e['model']
        if mdl['type'] == 'minecraft:select':
            mdl = mdl['cases'][0]['model']
        ref = mdl['model'].split('/')[-1]
        name = ref.split('__')[0]
        if name in MODELS:
            e['model'] = wrap(mdl, name)
    json.dump(d, open(path, 'w'), indent=2)

path = f'{ITEMS}/trident.json'
s = open(path).read()
s = s.replace('luminara:item/lost_trident__trident_throwing_3d', 'luminara:item/lost_trident_3d_throwing')
s = s.replace('luminara:item/lost_trident__trident_in_hand_3d', 'luminara:item/lost_trident_3d')
open(path, 'w').write(s)

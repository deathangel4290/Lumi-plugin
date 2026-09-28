"""Build every stylised weapon: pack model + texture, Blockbench project, and previews.
usage: python3 toonexport.py PACK_DIR BLOCKBENCH_DIR PREVIEW_DIR"""
import json, os, sys, math, copy
import numpy as np
from PIL import Image
from toon import rot_xyz, showcase, first_person, _corners
from toonweapons import MODELS, GRIP, hellstorm, XBOW_STATES, TOOLS
from bbexport import to_bbmodel

FP_ROLL = -5
FP_YAW = -75
REACH = {'tether_chain': (10.0, 22.0)}
UPRIGHT = {'scythe_of_diablo', 'ender_glaive', 'axe_of_zeus', 'titans_axe', 'lost_trident', 'luminosity'}

def carried_upright(scale, grip_y):
    """Third person for long weapons: shaft up, leaning forward and out from the body, head facing forward
    (vanilla's sword pose left half of the haft sticking out behind the player)."""
    from tpsim import solve_tp
    return solve_tp([(0, 1, 0), (-1, 0, 0)], [(-0.08, 1, 0.18), (0, 0, 1)], scale, (8, grip_y, 8))

G_VANILLA = np.array([-4.5, -4.5, 0.0])       # where the hand holds a vanilla sword sprite (centred units)

def rnd(v): return [round(float(x), 3) for x in v]

def solve(rot_v, t_v, s_v, rot_m, s_m, grip):
    """Translation that puts our grip exactly where the vanilla transform puts the vanilla grip."""
    target = np.array(t_v) + rot_xyz(*rot_v) @ (s_v * G_VANILLA)
    return target - rot_xyz(*rot_m) @ (s_m * grip)

THIRD = {   # model axes -> world axes for the RIGHT hand (+x is the player's left); the left hand mirrors
    'sword':   ([(0, 1, 0), (0, 0, 1)], [(0, 0.65, 1), (-1, 0, 0)]),     # ready stance: blade forward-up, flat outward
    'upright': ([(0, 1, 0), (-1, 0, 0)], [(0, 1, 0.25), (0, 0, 1)]),     # carried like a staff, head facing forward
    'tether':  ([(0, 1, 0), (1, 0, 0)], [(0, 1, 0.3), (0, 0, 1)]),       # chain up, hook facing forward
    'book':    ([(0, 1, 0), (0, 0, -1)], [(0, 1, 0), (-0.5, 0, 0.85)]),  # cover facing forward and out
}

def third_person(kind, scale, grip, pose=None):
    import tpsim
    model_axes, world = THIRD[kind]
    right = tpsim.solve_hand(model_axes, world, scale, grip, False, pose)
    return right, (same(right) if kind == 'book' else flipz(right))

def same(t):
    """The game negates a lefthand rotation's y/z and translation x, so storing the right-hand values
    gives the true mirror image (our old mirror() stored the negated values: not mirrored at all)."""
    return copy.deepcopy(t)

def flipz(t):
    """Left-hand transform that shows the true mirror image of the right hand. The game renders the
    lefthand values as M*F*M (M = x-mirror); the left arm chain is M*C*M. Our models are symmetric
    front-to-back (z), not side to side, so the mirror image is the model spun 180 degrees about y:
    F = right * Ry(180). Storing the right values (same) only works for side-to-side symmetric models:
    it flipped scythe and axe heads outward."""
    from tpsim import _euler
    M = rot_xyz(*t['rotation']) @ rot_xyz(0, 180, 0)
    return {'rotation': _euler(M), 'translation': list(t['translation']), 'scale': list(t['scale'])}

def mirror(t):
    r = t['rotation']
    return {'rotation': [r[0], -r[1], -r[2]], 'translation': t['translation'], 'scale': t['scale']}

def fit(rot, s, centre, base=(0, 0, 0)):
    return {'rotation': rot, 'translation': rnd(np.array(base) - rot_xyz(*rot) @ (s * centre)), 'scale': [round(s, 3)] * 3}

def displays(pts, grip_y, fp_reach=10.0, tp_reach=20.0, fp_shift=(-2.0, 0.5, 0.0)):
    g = np.array([0.0, grip_y - 8, 0.0])
    above = pts[pts[:, 1] > grip_y]
    reach = max(math.hypot(p[0] - 8, p[1] - grip_y) for p in above)
    s_fp, s_tp = min(0.68, fp_reach / reach), min(0.85, tp_reach / reach)
    mn, mx = pts.min(0), pts.max(0); c = (mn + mx) / 2 - 8; big = (mx - mn).max()
    tp = {'rotation': [0, -90, 10], 'scale': [round(s_tp, 3)] * 3,
          'translation': rnd(solve((0, -90, 55), (0, 4, 0.5), 0.85, (0, -90, 10), s_tp, g))}
    fp = {'rotation': [0, FP_YAW, FP_ROLL], 'scale': [round(s_fp, 3)] * 3,
          'translation': rnd(solve((0, -90, 25), (1.13, 3.2, 1.13), 0.68, (0, FP_YAW, FP_ROLL), s_fp, g) + np.array(fp_shift))}
    return {'thirdperson_righthand': tp, 'thirdperson_lefthand': mirror(tp),
            'firstperson_righthand': fp, 'firstperson_lefthand': mirror(fp),
            'ground': fit([0, 0, 0], 10 / big, c, (0, 2, 0)),  # noqa
            'fixed': fit([0, 180, 0], 15 / big, c),
            'head': fit([0, 180, 0], 16 / big, c, (0, 13, 7)),
            'gui': fit([30, 225, 0], 15 / big, c)}

def book_displays(pts):
    mn, mx = pts.min(0), pts.max(0); c = (mn + mx) / 2 - 8
    # the cover is the north (-z) face; turn it toward the camera / away from the body
    fp = fit([-10, 145, -10], 0.36, c, (-1, 4.5, 0))
    tp = fit([0, 90, 0], 0.5, c, (0, 2.5, 2))
    return {'thirdperson_righthand': tp, 'thirdperson_lefthand': mirror(tp),
            'firstperson_righthand': fp, 'firstperson_lefthand': mirror(fp),
            'ground': fit([0, 0, 0], 0.5, c, (0, 2, 0)), 'fixed': fit([0, 180, 0], 0.9, c),
            'head': fit([0, 180, 0], 1.0, c, (0, 13, 7)), 'gui': fit([30, 225, 0], 0.8, c)}

def save_texture(m, tex, path):
    mcmeta = path + '.mcmeta'
    if m.anim_strip is not None:
        m.anim_strip.save(path)
        json.dump({'animation': {'frametime': 2, 'interpolate': True}}, open(mcmeta, 'w'))
    else:
        tex.save(path)
        if os.path.exists(mcmeta): os.remove(mcmeta)

def crossbow_displays(pts, state='standby'):
    mn, mx = pts.min(0), pts.max(0); c = (mn + mx) / 2 - 8; big = (mx - mn).max()
    import tpsim
    from xbowstates import cancel, charged_extra, charging_extra
    r2 = 1 / math.sqrt(2)
    stock, top, grip = (r2, r2, 0), (0, 0, 1), (8 - 5 * r2, 8 - 5 * r2, 8)
    # Third person: each state is solved against the arm pose vanilla uses for it, so the crossbow
    # is level at the side when idle, pointed down-forward while drawing, and aimed when loaded.
    if state in ('arrow', 'firework'):
        tp, tpl = tpsim.solve_both([stock, top], [(0.05, 0, 1), (0, 1, 0)], 0.9, grip, tpsim.CROSSBOW_HOLD)
    elif state.startswith('pulling'):
        tp, tpl = tpsim.solve_both([stock, top], [(0.1, -0.35, 1), (0, 1, 0)], 0.9, grip, tpsim.CROSSBOW_CHARGE)
    else:
        tp, tpl = tpsim.solve_both([stock, top], [(0, -0.1, 1), (0, 1, 0)], 0.9, grip)
    from xbowpose import solve as xbow
    fp = xbow(scale=0.7, target=(0.3, -0.4, -0.6))          # stock forward, limbs level, top up
    # First person: vanilla slides a loaded crossbow to the screen centre and swings a drawing one
    # around; cancel both so the Hellstorm stays where it is in every state.
    if state in ('arrow', 'firework'):
        fp = cancel(fp, charged_extra())
    elif state.startswith('pulling'):
        fp = cancel(fp, charging_extra())
    return {'thirdperson_righthand': tp, 'thirdperson_lefthand': flipz(tp),
            'firstperson_righthand': fp, 'firstperson_lefthand': flipz(fp),
            'ground': fit([0, 0, 0], 0.5, c, (0, 2, 0)), 'fixed': fit([0, 180, 0], 1.0, c),
            'head': fit([0, 180, 0], 1.0, c, (0, 13, 7)), 'gui': fit([0, 0, 0], 1.0, c)}

if __name__ == '__main__':
    pack, bb, prev = sys.argv[1:4]
    only = sys.argv[4].split(',') if len(sys.argv) > 4 else None
    for d in (bb, prev): os.makedirs(d, exist_ok=True)
    shows, fps = [], []
    for name, build in MODELS.items():
        if only and name not in only: continue
        m = build()
        pts = np.array([p for e in m.elements for q in _corners(e).values() for p in q])
        disp = book_displays(pts) if name == 'veil_of_mimicry' else displays(pts, GRIP[name], *REACH.get(name, ()))
        kind = 'book' if name == 'veil_of_mimicry' else 'upright' if name in UPRIGHT else 'tether' if name == 'tether_chain' else 'sword'
        grip = (8, 6, 8) if kind == 'book' else (8, GRIP[name], 8)
        disp['thirdperson_righthand'], disp['thirdperson_lefthand'] = third_person(kind, disp['thirdperson_righthand']['scale'][0], grip)
        disp['firstperson_lefthand'] = (same if kind == 'book' else flipz)(disp['firstperson_righthand'])
        ref = f'luminara:item/{name}_3d'
        out, tex = m.export(ref, disp)
        if pack != '-':
            save_texture(m, tex, f'{pack}/assets/luminara/textures/item/{name}_3d.png')
            json.dump(out, open(f'{pack}/assets/luminara/models/item/{name}_3d.json', 'w'), indent=1)
            if name == 'lost_trident':
                thr = copy.deepcopy(out)
                g = np.array([0.0, GRIP[name] - 8, 0.0]); s = disp['thirdperson_righthand']['scale'][0]
                # THROW_SPEAR raises the arm straight overhead: aim the prongs forward and a little up
                import tpsim
                tr, tl = tpsim.solve_both([(0, 1, 0), (1, 0, 0)], [(0, 0.3, 1), (1, 0, 0)], s, (8, GRIP[name], 8), (-math.pi, 0.0))
                thr['display']['thirdperson_righthand'] = tr; thr['display']['thirdperson_lefthand'] = flipz(tr)
                # first person: the vanilla spear charge pose tilts the arm; aim the prongs at the crosshair
                from spearpose import solve as spear
                f = spear(disp['firstperson_righthand']['scale'][0], GRIP[name], forward=(-0.18, 0.12, -1.0))
                thr['display']['firstperson_righthand'] = f; thr['display']['firstperson_lefthand'] = flipz(f)
                json.dump(thr, open(f'{pack}/assets/luminara/models/item/{name}_3d_throwing.json', 'w'), indent=1)
        to_bbmodel(out, tex, f'{name}_3d', f'{bb}/{name}_3d.bbmodel')
        shows.append(showcase(out, tex, yaw=28, pitch=12, size=300))
        fps.append(first_person(out, tex, W=384, H=216))
        print(f'{name}: {len(out["elements"])} elements, {tex.width}px atlas, fp scale {disp["firstperson_righthand"]["scale"][0]}')
    if not only or 'hellstorm' in only:
        for st in XBOW_STATES:
            m = hellstorm(st)
            pts = np.array([p for e in m.elements for q in _corners(e).values() for p in q])
            out, tex = m.export(f'luminara:item/hellstorm_3d_{st}', crossbow_displays(pts, st))
            if pack != '-':
                save_texture(m, tex, f'{pack}/assets/luminara/textures/item/hellstorm_3d_{st}.png')
                json.dump(out, open(f'{pack}/assets/luminara/models/item/hellstorm_3d_{st}.json', 'w'), indent=1)
            to_bbmodel(out, tex, f'hellstorm_3d_{st}', f'{bb}/hellstorm_3d_{st}.bbmodel')
            if st in ('standby', 'arrow'):
                shows.append(showcase(out, tex, yaw=20, pitch=25, size=300)); fps.append(first_person(out, tex, W=384, H=216))
        print('hellstorm: 6 states')
    HANDHELD = {'thirdperson_righthand': {'rotation': [0, -90, 55], 'translation': [0, 4, 0.5], 'scale': [0.85] * 3},
                'thirdperson_lefthand': {'rotation': [0, 90, -55], 'translation': [0, 4, 0.5], 'scale': [0.85] * 3},
                'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68] * 3},
                'firstperson_lefthand': {'rotation': [0, 90, -25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68] * 3},
                'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.5] * 3},
                'head': {'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1] * 3},
                'fixed': {'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [1] * 3},
                'gui': {'rotation': [0, 0, 0], 'translation': [0, 0, 0], 'scale': [1] * 3}}
    if not only or 'tools' in only:
        for name, build in TOOLS.items():
            m = build()
            out, tex = m.export(f'luminara:item/{name}', HANDHELD)
            if pack != '-':
                save_texture(m, tex, f'{pack}/assets/luminara/textures/item/{name}.png')
                json.dump(out, open(f'{pack}/assets/luminara/models/item/{name}.json', 'w'), indent=1)
            to_bbmodel(out, tex, name, f'{bb}/{name}.bbmodel')
        print('tools: 3')
    def grid(imgs, cols, path):
        w, h = imgs[0].size; rows = (len(imgs) + cols - 1) // cols
        g = Image.new('RGB', (w * cols, h * rows), (139, 139, 139))
        for i, im in enumerate(imgs): g.paste(im, ((i % cols) * w, (i // cols) * h))
        g.save(path)
    if shows: grid(shows, 6, f'{prev}/models.png'); grid(fps, 4, f'{prev}/first_person.png')

"""Build every stylised weapon: pack model + texture, Blockbench project, and previews.
usage: python3 toonexport.py PACK_DIR BLOCKBENCH_DIR PREVIEW_DIR"""
import json, os, sys, math, copy
import numpy as np
from PIL import Image
from toon import rot_xyz, showcase, first_person, _corners
from toonweapons import MODELS, GRIP
from bbexport import to_bbmodel

FP_ROLL = 5
FP_YAW = -65
REACH = {'tether_chain': (14.0, 24.0)}
G_VANILLA = np.array([-4.5, -4.5, 0.0])       # where the hand holds a vanilla sword sprite (centred units)

def rnd(v): return [round(float(x), 3) for x in v]

def solve(rot_v, t_v, s_v, rot_m, s_m, grip):
    """Translation that puts our grip exactly where the vanilla transform puts the vanilla grip."""
    target = np.array(t_v) + rot_xyz(*rot_v) @ (s_v * G_VANILLA)
    return target - rot_xyz(*rot_m) @ (s_m * grip)

def mirror(t):
    r = t['rotation']
    return {'rotation': [r[0], -r[1], -r[2]], 'translation': t['translation'], 'scale': t['scale']}

def fit(rot, s, centre, base=(0, 0, 0)):
    return {'rotation': rot, 'translation': rnd(np.array(base) - rot_xyz(*rot) @ (s * centre)), 'scale': [round(s, 3)] * 3}

def displays(pts, grip_y, fp_reach=12.0, tp_reach=20.0, fp_shift=(-2.5, -0.5, 0.0)):
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
            'ground': fit([0, 0, 0], 10 / big, c, (0, 2, 0)),
            'fixed': fit([0, 180, 0], 15 / big, c),
            'head': fit([0, 180, 0], 16 / big, c, (0, 13, 7)),
            'gui': fit([30, 225, 0], 15 / big, c)}

def book_displays(pts):
    mn, mx = pts.min(0), pts.max(0); c = (mn + mx) / 2 - 8
    # the cover is the north (-z) face; turn it toward the camera / away from the body
    fp = fit([0, 165, 0], 0.5, c, (-1.5, 6.5, 0))
    tp = fit([0, 90, 0], 0.5, c, (0, 2.5, 2))
    return {'thirdperson_righthand': tp, 'thirdperson_lefthand': mirror(tp),
            'firstperson_righthand': fp, 'firstperson_lefthand': mirror(fp),
            'ground': fit([0, 0, 0], 0.5, c, (0, 2, 0)), 'fixed': fit([0, 180, 0], 0.9, c),
            'head': fit([0, 180, 0], 1.0, c, (0, 13, 7)), 'gui': fit([30, 225, 0], 0.8, c)}

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
        ref = f'luminara:item/{name}_3d'
        out, tex = m.export(ref, disp)
        if pack != '-':
            tex.save(f'{pack}/assets/luminara/textures/item/{name}_3d.png')
            json.dump(out, open(f'{pack}/assets/luminara/models/item/{name}_3d.json', 'w'), indent=1)
            if name == 'lost_trident':
                thr = copy.deepcopy(out)
                g = np.array([0.0, GRIP[name] - 8, 0.0]); s = disp['thirdperson_righthand']['scale'][0]
                t = {'rotation': [0, -90, -170], 'scale': [s] * 3,
                     'translation': rnd(solve((0, -90, 55), (0, 4, 0.5), 0.85, (0, -90, -170), s, g))}
                thr['display']['thirdperson_righthand'] = t; thr['display']['thirdperson_lefthand'] = mirror(t)
                json.dump(thr, open(f'{pack}/assets/luminara/models/item/{name}_3d_throwing.json', 'w'), indent=1)
        to_bbmodel(out, tex, f'{name}_3d', f'{bb}/{name}_3d.bbmodel')
        shows.append(showcase(out, tex, yaw=28, pitch=12, size=300))
        fps.append(first_person(out, tex, W=384, H=216))
        print(f'{name}: {len(out["elements"])} elements, {tex.width}px atlas, fp scale {disp["firstperson_righthand"]["scale"][0]}')
    def grid(imgs, cols, path):
        w, h = imgs[0].size; rows = (len(imgs) + cols - 1) // cols
        g = Image.new('RGB', (w * cols, h * rows), (139, 139, 139))
        for i, im in enumerate(imgs): g.paste(im, ((i % cols) * w, (i // cols) * h))
        g.save(path)
    grid(shows, 6, f'{prev}/models.png'); grid(fps, 4, f'{prev}/first_person.png')

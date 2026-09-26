from frame import *
from PIL import Image
import random

MID, HALF, REACH, BULGE, NECK = 50.0, 11.5, 18.5, 3.5, 6.0

MASK = None   # when set, only pixels of this material on this canvas are painted

def line_uv(img, a, b, col, steps=24):
    for i in range(steps+1):
        t = i/steps; u, v = a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t
        if MASK is not None:
            x, y = pt(u, v)
            if MASK[0].get(int(round(x)), int(round(y))) != MASK[1]: continue
        put_uv(img, u, v, col)

def edge(side, t):
    return (MID + HALF - 2*HALF*t, side*(REACH + BULGE*math.sin(math.pi*t)))

def crack(rng, start, direction, steps, jitter):
    """A jagged crack: a random walk heading roughly in `direction` (in u,v)."""
    path = [start]; u, v = start
    for _ in range(steps):
        u += direction[0] + rng.uniform(-jitter, jitter)
        v += direction[1] + rng.uniform(-jitter*0.4, jitter*0.4)
        path.append((u, v))
    return path

def build_veins():
    out = {}
    for s in (-1, 1):
        rng = random.Random(7 if s < 0 else 11)
        trunk = crack(rng, (MID - 1, s*4.0), (0.25, s*1.55), 8, 1.1)
        paths = [trunk]
        for k, idx in enumerate((3, 5, 6)):   # branches peeling off the trunk
            u, v = trunk[idx]
            du = (-1.3 if k % 2 == 0 else 1.2)
            paths.append(crack(rng, (u, v), (du, s*0.9), 4 + k, 0.9))
        out[s] = paths
    return out

VEINS = build_veins()

def titan(heat):
    c = Canvas(N)
    fill_profile(c, 2, 64, lambda u: 2.6, 'handle')
    for s in (-1, 1):
        pts = [pt(MID - NECK, s*2.5), pt(MID + NECK, s*2.5)]
        pts += [pt(*edge(s, i/24)) for i in range(25)]
        c.poly(pts, 'stone')
    fill_profile(c, MID - 7.5, MID + 7.5, lambda u: 3.6, 'bronze')          # head core
    c.poly([pt(MID + 7.5, -3), pt(MID + 16, 0), pt(MID + 7.5, 3)], 'bronze')   # crown spike
    for u0 in (MID - 9.5, MID + 8.5):                                        # binding bands
        fill_profile(c, u0, u0 + 1.4, lambda u: 4.2, 'bronze')
    for u0 in (10, 18, 26):                                                  # grip rings
        fill_profile(c, u0, u0 + 2.0, lambda u: 3.2, 'bronze')
    c.poly([pt(0, 0), pt(3, -3.6), pt(6, 0), pt(3, 3.6)], 'bronze')          # faceted pommel
    ramps = {'handle': [(16,10,8),(40,26,18),(62,42,30),(90,64,46),(122,92,68)],
             'stone':  [(12,10,12),(34,30,34),(54,48,54),(80,72,78),(116,106,112)],
             'bronze': [(46,22,8),(116,66,26),(170,106,44),(214,156,82),(244,206,140)]}
    img = render_bevel(c, ramps)
    for s in (-1, 1):
        for i in range(0, 61):                                               # honed steel edge
            u, v = edge(s, i/60)
            put_uv(img, u, v - s*0.7, (226, 230, 240))
            put_uv(img, u, v - s*1.6, (150, 154, 166))
        # magma veins: a trunk from the core branching toward the edge
        hot = lerp((150, 40, 10), (255, 200, 80), heat)
        core = lerp((255, 110, 20), (255, 245, 180), heat)
        global MASK
        MASK = (c, 'stone')
        for path in VEINS[s]:
            for a, b in zip(path, path[1:]):
                line_uv(img, a, b, hot, steps=6)
            for a, b in zip(path[:len(path)//2], path[1:len(path)//2 + 1]):   # hottest near the core
                line_uv(img, (a[0] + 0.5, a[1]), (b[0] + 0.5, b[1]), core, steps=6)
        MASK = None
        for u, v in ((MID - 3.5, s*6), (MID + 3.5, s*6)):                     # rivets
            put_uv(img, u, v, (244, 206, 140)); put_uv(img, u + 0.7, v + 0.7, (70, 36, 12))
    for u in (14, 22, 30, 38):                                               # handle studs
        put_uv(img, u, 0, (230, 180, 110)); put_uv(img, u + 0.7, 0.7, (60, 30, 10))
    gem(img, MID, 0, 2.2, (70, 16, 0), lerp((230, 80, 10), (255, 220, 120), heat), (255, 250, 220))
    return img

heats = (0.15, 0.3, 0.55, 0.85, 1.0, 0.8, 0.5, 0.3)
frames = [titan(h) for h in heats]
frames[4].save('hd_titan2.png')
strip = Image.new('RGBA', (N, N*len(frames)))
for i, f in enumerate(frames): strip.paste(f, (0, N*i))
strip.save('hd_titan2_anim.png')
preview([Image.open('hd_titan.png'), frames[0], frames[4]], 'p_titan.png', scale=5)

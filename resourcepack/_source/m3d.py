"""Minimal Java item-model builder: cuboids + a painted atlas + a software preview renderer."""
import json, math, random
from PIL import Image, ImageDraw

ALLOWED = (-45.0, -22.5, 0.0, 22.5, 45.0)
FACES = ('north', 'south', 'east', 'west', 'up', 'down')

def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(len(a)))

class Atlas:
    """A grid of 16x16-pixel swatches, one per material, on a 128x128 texture (8 px per uv unit)."""
    def __init__(self, size=128, cell=16):
        self.size, self.cell = size, cell
        self.img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        self.slots = {}

    def material(self, name, painter):
        if name in self.slots: return self.slots[name]
        per_row = self.size // self.cell
        i = len(self.slots); cx, cy = (i % per_row) * self.cell, (i // per_row) * self.cell
        tile = Image.new('RGBA', (self.cell, self.cell))
        painter(tile, random.Random(hash(name) & 0xffff))
        self.img.paste(tile, (cx, cy))
        self.slots[name] = (cx, cy)
        return self.slots[name]

    def uv(self, name):
        cx, cy = self.slots[name]; k = 16.0 / self.size
        inset = 0.25                                           # avoid bleeding into neighbours
        return [ (cx + inset) * k, (cy + inset) * k, (cx + self.cell - inset) * k, (cy + self.cell - inset) * k ]

# ---------------------------------------------------------------- material painters
def metal(dark, base, light, shine, streak=True):
    def paint(t, rng):
        n = t.width
        for y in range(n):
            for x in range(n):
                g = y / (n - 1)
                c = lerp(light, base, g) if g < 0.5 else lerp(base, dark, (g - 0.5) * 2)
                if streak and rng.random() < 0.08: c = lerp(c, shine, 0.4)
                if x in (0, n - 1) or y in (0, n - 1): c = lerp(c, dark, 0.45)
                if y == 1 and 0 < x < n - 1: c = lerp(c, shine, 0.6)
                t.putpixel((x, y), c + (255,))
    return paint

def grain(dark, base, light):
    def paint(t, rng):
        n = t.width
        offs = [rng.uniform(0, 6.28) for _ in range(4)]
        for y in range(n):
            for x in range(n):
                v = 0.5 + 0.25 * math.sin(x * 1.3 + offs[0]) + 0.15 * math.sin(x * 3.1 + y * 0.2 + offs[1])
                c = lerp(dark, light, max(0.0, min(1.0, v)))
                if rng.random() < 0.05: c = lerp(c, dark, 0.5)
                if x in (0, n - 1): c = lerp(c, dark, 0.5)
                t.putpixel((x, y), c + (255,))
    return paint

def wrap(dark, base, light):
    def paint(t, rng):
        n = t.width
        for y in range(n):
            for x in range(n):
                band = ((x + y) // 3) % 2
                c = base if band else lerp(base, dark, 0.5)
                if (x + y) % 3 == 0: c = lerp(c, light, 0.35)
                t.putpixel((x, y), c + (255,))
    return paint

def gem(dark, base, light):
    def paint(t, rng):
        n = t.width; cx = cy = (n - 1) / 2
        for y in range(n):
            for x in range(n):
                d = math.hypot(x - cx, y - cy) / (n / 2)
                c = lerp(light, base, min(1, d * 1.2)) if d < 0.8 else lerp(base, dark, min(1, (d - 0.8) * 3))
                t.putpixel((x, y), c + (255,))
        for x, y in ((4, 4), (5, 4), (4, 5)): t.putpixel((x, y), (255, 255, 255, 255))
    return paint

def flat(col, edge=None):
    def paint(t, rng):
        n = t.width
        for y in range(n):
            for x in range(n):
                c = col
                if edge and (x in (0, n - 1) or y in (0, n - 1)): c = edge
                t.putpixel((x, y), c + (255,))
    return paint

# ---------------------------------------------------------------- model
class Model:
    def __init__(self):
        self.atlas = Atlas()
        self.elements = []

    def box(self, frm, to, mat, rot=None, faces=FACES):
        """rot = (axis, angle, origin). Validated against what Java item models accept."""
        for v in list(frm) + list(to):
            assert -16 <= v <= 32, f'{mat}: coordinate {v} outside -16..32'
        e = {'from': [round(v, 3) for v in frm], 'to': [round(v, 3) for v in to], 'mat': mat, 'faces': faces}
        if rot:
            axis, angle, origin = rot
            assert angle in ALLOWED, f'{mat}: angle {angle} not allowed'
            if angle != 0: e['rotation'] = {'axis': axis, 'angle': angle, 'origin': [round(v, 3) for v in origin]}
        self.elements.append(e)

    def segment_xy(self, start, angle, length, width, depth, mat, z=8.0, overlap=0.6):
        """A flat bar lying in the XY plane, extending LEFT (-x) from `start`, tilted by `angle` (deg, CCW).
        Returns the far end so segments can be chained into curves."""
        sx, sy = start
        self.box((sx - length - overlap, sy - width / 2, z - depth / 2), (sx + overlap * 0.3, sy + width / 2, z + depth / 2), mat,
                 ('z', angle, (sx, sy, z)))
        a = math.radians(angle)
        return (sx - length * math.cos(a), sy - length * math.sin(a))

    def export(self, texture_ref, display):
        out = {'credit': 'Luminara', 'texture_size': [self.atlas.size, self.atlas.size],
               'textures': {'0': texture_ref, 'particle': texture_ref}, 'elements': [], 'display': display}
        for e in self.elements:
            el = {'from': e['from'], 'to': e['to'], 'faces': {}}
            if 'rotation' in e: el['rotation'] = e['rotation']
            for f in e['faces']:
                el['faces'][f] = {'uv': [round(v, 3) for v in self.atlas.uv(e['mat'])], 'texture': '#0'}
            out['elements'].append(el)
        return out

# ---------------------------------------------------------------- preview renderer
def _rot(p, axis, deg, origin):
    a = math.radians(deg); c, s = math.cos(a), math.sin(a)
    x, y, z = (p[i] - origin[i] for i in range(3))
    if axis == 'x': y, z = y * c - z * s, y * s + z * c
    elif axis == 'y': x, z = x * c + z * s, -x * s + z * c
    else: x, y = x * c - y * s, x * s + y * c
    return (x + origin[0], y + origin[1], z + origin[2])

def _corners(e):
    (x0, y0, z0), (x1, y1, z1) = e['from'], e['to']
    quads = {'north': [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
             'south': [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
             'east':  [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
             'west':  [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
             'up':    [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
             'down':  [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)]}
    if 'rotation' in e:
        r = e['rotation']
        quads = {k: [_rot(p, r['axis'], r['angle'], r['origin']) for p in v] for k, v in quads.items()}
    return quads

def render(model, yaw=35, pitch=25, size=420, pad=20, bg=(139, 139, 139)):
    tex = model.atlas.img.convert('RGBA')
    polys = []
    for e in model.elements:
        u0, v0, u1, v1 = model.atlas.uv(e['mat']); k = model.atlas.size / 16.0
        for f, quad in _corners(e).items():
            if f not in e['faces']: continue
            pts = [_rot(_rot(p, 'y', yaw, (8, 8, 8)), 'x', pitch, (8, 8, 8)) for p in quad]
            ax, ay, az = (pts[1][i] - pts[0][i] for i in range(3)); bx, by, bz = (pts[3][i] - pts[0][i] for i in range(3))
            nz = ax * by - ay * bx
            if nz >= 0: continue                                # back-facing (screen y flips below)
            nx, ny = ay * bz - az * by, az * bx - ax * bz
            ln = math.sqrt(nx * nx + ny * ny + nz * nz) or 1
            light = 0.55 + 0.45 * max(0.0, (-ny / ln) * 0.6 + (-nz / ln) * 0.5)   # upper-front faces brighter
            depth = sum(p[2] for p in pts) / 4
            polys.append((depth, pts, light, (u0 * k, v0 * k, u1 * k, v1 * k)))
    allp = [p for poly in polys for p in poly[1]]
    minx, maxx = min(p[0] for p in allp), max(p[0] for p in allp)
    miny, maxy = min(p[1] for p in allp), max(p[1] for p in allp)
    scale = (size - 2 * pad) / max(maxx - minx, maxy - miny)
    img = Image.new('RGBA', (size, size), bg + (255,))
    px = img.load()
    for depth, pts, light, (tu0, tv0, tu1, tv1) in sorted(polys, key=lambda q: q[0]):
        sc = [((p[0] - minx) * scale + pad, size - ((p[1] - miny) * scale + pad)) for p in pts]
        xs, ys = [p[0] for p in sc], [p[1] for p in sc]
        for yy in range(max(0, int(min(ys))), min(size, int(max(ys)) + 1)):
            for xx in range(max(0, int(min(xs))), min(size, int(max(xs)) + 1)):
                # inverse bilinear via two triangles (affine)
                for tri, tuv in (((0, 1, 2), ((0, 0), (1, 0), (1, 1))), ((0, 2, 3), ((0, 0), (1, 1), (0, 1)))):
                    (x1, y1), (x2, y2), (x3, y3) = (sc[i] for i in tri)
                    det = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3)
                    if abs(det) < 1e-9: continue
                    l1 = ((y2 - y3) * (xx + 0.5 - x3) + (x3 - x2) * (yy + 0.5 - y3)) / det
                    l2 = ((y3 - y1) * (xx + 0.5 - x3) + (x1 - x3) * (yy + 0.5 - y3)) / det
                    l3 = 1 - l1 - l2
                    if min(l1, l2, l3) < -0.001: continue
                    s = l1 * tuv[0][0] + l2 * tuv[1][0] + l3 * tuv[2][0]
                    t = l1 * tuv[0][1] + l2 * tuv[1][1] + l3 * tuv[2][1]
                    c = tex.getpixel((min(tex.width - 1, int(tu0 + s * (tu1 - tu0))), min(tex.height - 1, int(tv0 + t * (tv1 - tv0)))))
                    if c[3] > 0: px[xx, yy] = (int(c[0] * light), int(c[1] * light), int(c[2] * light), 255)
                    break
    return img

def _curve_box(m, start, phi, length, width, depth, mat, z, overlap):
    """Bar leaving `start` at `phi` degrees below the leftward horizontal (0..90), using only legal rotations:
    0..45 = a leftward bar tilted down, 45..90 = a downward bar tilted left."""
    sx, sy = start
    if phi <= 45:
        m.box((sx - length - overlap, sy - width / 2, z - depth / 2), (sx + overlap * 0.3, sy + width / 2, z + depth / 2), mat,
              ('z', phi, (sx, sy, z)))
    else:
        theta = phi - 90.0
        m.box((sx - width / 2, sy - length - overlap, z - depth / 2), (sx + width / 2, sy + overlap * 0.3, z + depth / 2), mat,
              ('z', theta, (sx, sy, z)))
    a = math.radians(phi)
    return (sx - length * math.cos(a), sy - length * math.sin(a))

def blade_curve(m, start, steps, depth, mats, z=8.0, edge_w=0.9, spine_w=0.7):
    """steps: [(phi, length, width)]. mats: (blade, edge, spine). The edge is on the inner (lower/right) side."""
    pos = start
    for phi, length, width in steps:
        a = math.radians(phi)
        d = (-math.cos(a), -math.sin(a)); n = (-d[1], d[0])          # inner normal
        _curve_box(m, pos, phi, length, width, depth, mats[0], z, 0.7)
        e = (pos[0] + n[0] * (width / 2 - edge_w / 2 + 0.15), pos[1] + n[1] * (width / 2 - edge_w / 2 + 0.15))
        _curve_box(m, e, phi, length, edge_w, depth + 0.25, mats[1], z, 0.5)
        s = (pos[0] - n[0] * (width / 2 - spine_w / 2 + 0.1), pos[1] - n[1] * (width / 2 - spine_w / 2 + 0.1))
        _curve_box(m, s, phi, length, spine_w, depth + 0.3, mats[2], z, 0.5)
        pos = (pos[0] - length * math.cos(a), pos[1] - length * math.sin(a))
    return pos

# ---------------------------------------------------------------- silhouette extrusion
def extrude(m, mat_at, x0, x1, y0, y1, z, depth, step=0.5):
    """Build a flat shape from horizontal slices. mat_at(x, y) returns a material name or None for each
    `step`-sized cell; runs of the same material on a row become one box, so curves stay smooth."""
    y = y0
    while y < y1 - 1e-9:
        x = x0; run_mat = None; run_start = None
        while x <= x1 + 1e-9:
            mat = mat_at(x + step / 2, y + step / 2) if x < x1 - 1e-9 else None
            if mat != run_mat:
                if run_mat is not None:
                    m.box((run_start, y, z - depth / 2), (x, y + step, z + depth / 2), run_mat)
                run_mat, run_start = mat, x
            x += step
        y += step

def ring(m, y, h, r, mat, cx=8.0, cz=8.0):
    m.box((cx - r, y, cz - r), (cx + r, y + h, cz + r), mat)

def rod(m, y0, y1, r, mat, cx=8.0, cz=8.0):
    m.box((cx - r, y0, cz - r), (cx + r, y1, cz + r), mat)

def stone(dark, base, light):
    def paint(t, rng):
        n = t.width
        for y in range(n):
            for x in range(n):
                v = rng.random()
                c = lerp(dark, light, 0.35 + 0.4 * v) if v > 0.2 else lerp(dark, base, 0.5)
                if x in (0, n - 1) or y in (0, n - 1): c = lerp(c, dark, 0.5)
                t.putpixel((x, y), c + (255,))
    return paint

def glow(core, rim):
    def paint(t, rng):
        n = t.width; cx = cy = (n - 1) / 2
        for y in range(n):
            for x in range(n):
                d = min(1.0, math.hypot(x - cx, y - cy) / (n / 2))
                t.putpixel((x, y), lerp(core, rim, d) + (255,))
    return paint

def sheet(models, path, yaw=35, pitch=20, size=300):
    from PIL import Image
    imgs = [render(mm, yaw=yaw, pitch=pitch, size=size) for mm in models]
    out = Image.new('RGBA', (sum(i.width for i in imgs), size))
    x = 0
    for i in imgs: out.paste(i, (x, 0)); x += i.width
    out.save(path)

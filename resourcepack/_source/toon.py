"""Stylised Java item-model builder: cuboids with cel-shaded pixel-art faces.

Every face gets its own texels at a fixed density (D texels per model unit), painted as clean pixel art:
a 5-colour ramp per material, light top-left bevel, dark bottom-right bevel, no noise. Flat shapes
(blades, axe heads) are drawn as one pixel-art sprite and extruded, so the front of a blade is a single
picture with a dark outline and a highlighted edge -- the same look as a vanilla item, just thick.
"""
import json, math
import numpy as np
from PIL import Image

D = 2                                   # texels per model unit
ALLOWED = (-45.0, -22.5, 0.0, 22.5, 45.0)
FACES = ('north', 'south', 'east', 'west', 'up', 'down')


def hexc(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


class Mat:
    def __init__(self, ramp, kind='metal', emit=0):
        assert len(ramp) == 5
        self.r = [hexc(c) for c in ramp]
        self.kind, self.emit = kind, emit


def _hash(*a):
    h = 2166136261
    for v in a:
        h = ((h ^ (int(v) & 0xffffffff)) * 16777619) & 0xffffffff
    return h


def paint_face(M, face, w, h, salt=0):
    """Pixel art for one box face, oriented as seen from outside (up = +y on side faces)."""
    img = Image.new('RGBA', (w, h))
    base = {'up': 3, 'down': 1}.get(face, 2)
    for v in range(h):
        for u in range(w):
            k = base
            if M.kind == 'wrap' and face not in ('up', 'down'):
                if (u + v) % 4 == 0: k -= 1
                elif (u + v) % 4 == 1: k += 1
            elif M.kind == 'wood' and face not in ('up', 'down'):
                if u % 3 == 1 and (_hash(u, v // 3, salt) % 3 == 0): k -= 1
            elif M.kind == 'stone':
                n = _hash(u // 2, v // 2, salt) % 7
                k += (-1 if n == 0 else 1 if n == 1 else 0)
            if M.kind == 'gem':
                edge = u in (0, w - 1) or v in (0, h - 1)
                k = 1 if edge else 3
                if (u, v) in ((1, 1), (2, 1), (1, 2)) and w > 2 and h > 2: k = 4
                if u == w - 2 and v == h - 2 and w > 3: k = 2
            elif M.kind == 'glow':
                edge = u in (0, w - 1) or v in (0, h - 1)
                k = 3 if edge and w > 2 and h > 2 else 4
            elif w >= 3 and h >= 3:
                if v == 0 or u == 0: k += 1
                if v == h - 1 or u == w - 1: k -= 1
            elif w >= 2 and h >= 2 and u == 0 and v == 0:
                k += 1
            img.putpixel((u, v), M.r[max(0, min(4, k))] + (255,))
    return img


def paint_sprite(model, grid, nx, ny):
    """grid[j][i] (j = row from the bottom) -> material name or None."""
    img = Image.new('RGBA', (nx, ny))
    solid = lambda i, j: 0 <= i < nx and 0 <= j < ny and grid[j][i] is not None
    for j in range(ny):
        for i in range(nx):
            name = grid[j][i]
            if name is None: continue
            M = model.mats[name]; r = M.r
            if any(not solid(i + a, j + b) for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                c = r[0]
            elif M.kind == 'glow':
                same = all(solid(i + a, j + b) and grid[j + b][i + a] == name for a in (-1, 0, 1) for b in (-1, 0, 1))
                c = r[4] if same else r[3]
            elif M.kind == 'gem':
                c = r[3] if solid(i - 1, j + 1) and grid[j + 1][i - 1] == name else r[4]
            else:
                ring1 = [(a, b) for a in (-1, 0, 1) for b in (-1, 0, 1) if not solid(i + a, j + b)]
                ring2 = [(a, b) for a in range(-2, 3) for b in range(-2, 3) if not solid(i + a, j + b)]
                if ring1:
                    c = r[4] if any(a < 0 or b > 0 for a, b in ring1) else r[1]
                elif ring2:
                    c = r[3] if any(a < 0 or b > 0 for a, b in ring2) else r[2]
                else:
                    c = r[2]
                if M.kind == 'stone' and c == r[2]:
                    n = _hash(i // 2, j // 2, 7) % 6
                    c = r[1] if n == 0 else r[3] if n == 1 else r[2]
            img.putpixel((i, ny - 1 - j), c + (255,))
    return img


class Model:
    def __init__(self):
        self.mats, self.elements, self.sprites = {}, [], []

    def mat(self, name, ramp, kind='metal', emit=0):
        self.mats[name] = Mat(ramp, kind, emit)

    def box(self, frm, to, mat, rot=None, faces=FACES):
        assert mat in self.mats, mat
        for v in list(frm) + list(to):
            assert -16 <= v <= 32, f'{mat}: coordinate {v} outside -16..32'
        e = {'from': [round(v, 3) for v in frm], 'to': [round(v, 3) for v in to], 'mat': mat, 'faces': tuple(faces)}
        if rot and rot[1] != 0:
            axis, angle, origin = rot
            assert angle in ALLOWED, f'{mat}: angle {angle}'
            e['rotation'] = {'axis': axis, 'angle': angle, 'origin': [round(v, 3) for v in origin]}
        self.elements.append(e)
        return e

    def extrude(self, at, x0, x1, y0, y1, z, depth, step=0.5):
        """Flat pixel-art shape: at(x, y) -> material or None per step-sized cell. One box per same-material
        run on each row; the front/back of every run shows its slice of one painted sprite."""
        assert abs(step * D - 1) < 1e-9, 'sprites are one texel per cell'
        nx, ny = round((x1 - x0) / step), round((y1 - y0) / step)
        grid = [[at(x0 + (i + .5) * step, y0 + (j + .5) * step) for i in range(nx)] for j in range(ny)]
        sid = len(self.sprites)
        self.sprites.append((grid, nx, ny))
        for j in range(ny):
            i = 0
            while i < nx:
                name = grid[j][i]
                if name is None: i += 1; continue
                k = i
                while k < nx and grid[j][k] == name: k += 1
                faces = ['north', 'south']
                if i == 0 or grid[j][i - 1] is None: faces.append('west')
                if k == nx or grid[j][k] is None: faces.append('east')
                if j == ny - 1 or any(grid[j + 1][c] is None for c in range(i, k)): faces.append('up')
                if j == 0 or any(grid[j - 1][c] is None for c in range(i, k)): faces.append('down')
                e = self.box((x0 + i * step, y0 + j * step, z - depth / 2), (x0 + k * step, y0 + (j + 1) * step, z + depth / 2), name, faces=faces)
                e['sprite'] = (sid, i, k, ny - 1 - j)
                i = k

    def bounds(self):
        pts = [p for e in self.elements for q in _corners(e).values() for p in q]
        return [min(p[i] for p in pts) for i in range(3)], [max(p[i] for p in pts) for i in range(3)]

    # ------------------------------------------------------------ texture + json
    def export(self, texture_ref, display):
        pieces = []                       # (key, image)
        for sid, (grid, nx, ny) in enumerate(self.sprites):
            pieces.append((('s', sid), paint_sprite(self, grid, nx, ny)))
        for n, e in enumerate(self.elements):
            if 'sprite' in e: continue
            (x0, y0, z0), (x1, y1, z1) = e['from'], e['to']
            dims = {'north': (x1 - x0, y1 - y0), 'south': (x1 - x0, y1 - y0), 'east': (z1 - z0, y1 - y0),
                    'west': (z1 - z0, y1 - y0), 'up': (x1 - x0, z1 - z0), 'down': (x1 - x0, z1 - z0)}
            for f in e['faces']:
                w, h = (max(1, round(d * D)) for d in dims[f])
                pieces.append((('f', n, f), paint_face(self.mats[e['mat']], f, w, h, salt=n)))
        size, place = _pack([p[1] for p in pieces])
        atlas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        at = {}
        for (key, img), (px, py) in zip(pieces, place):
            # 1-texel border copied from the edge so mipmaps never pull in a neighbour
            big = img.resize((img.width + 2, img.height + 2), Image.NEAREST)
            big.paste(img, (1, 1))
            atlas.paste(big, (px, py))
            at[key] = (px + 1, py + 1)
        k = 16.0 / size
        lookup = dict(pieces)
        out = {'credit': 'Luminara', 'texture_size': [size, size],
               'textures': {'0': texture_ref, 'particle': texture_ref}, 'elements': [], 'display': display}
        for n, e in enumerate(self.elements):
            el = {'from': e['from'], 'to': e['to']}
            if 'rotation' in e: el['rotation'] = e['rotation']
            M = self.mats[e['mat']]
            if M.emit: el['light_emission'] = M.emit
            el['faces'] = {}
            for f in e['faces']:
                if 'sprite' in e:
                    sid, i0, i1, row = e['sprite']; sx, sy = at[('s', sid)]
                    uv = {'south': (sx + i0, sy + row, sx + i1, sy + row + 1),
                          'north': (sx + i1, sy + row, sx + i0, sy + row + 1),
                          'up': (sx + i0, sy + row, sx + i1, sy + row + 1),
                          'down': (sx + i0, sy + row, sx + i1, sy + row + 1),
                          'east': (sx + i1 - 1, sy + row, sx + i1, sy + row + 1),
                          'west': (sx + i0, sy + row, sx + i0 + 1, sy + row + 1)}[f]
                else:
                    fx, fy = at[('f', n, f)]
                    img = lookup[('f', n, f)]
                    uv = (fx, fy, fx + img.width, fy + img.height)
                el['faces'][f] = {'uv': [round(v * k, 4) for v in uv], 'texture': '#0'}
            out['elements'].append(el)
        return out, atlas


def _pack(imgs):
    order = sorted(range(len(imgs)), key=lambda i: (-(imgs[i].height + 2), -(imgs[i].width + 2)))
    for size in (64, 128, 256, 512, 1024):
        if max(i.width + 2 for i in imgs) > size: continue
        place = [None] * len(imgs); x = y = row_h = 0; ok = True
        for i in order:
            w, h = imgs[i].width + 2, imgs[i].height + 2
            if x + w > size: x, y, row_h = 0, y + row_h, 0
            if y + h > size: ok = False; break
            place[i] = (x, y); x += w; row_h = max(row_h, h)
        if ok: return size, place
    raise ValueError('atlas too big')


# ---------------------------------------------------------------- geometry
def _rot(p, axis, deg, origin):
    a = math.radians(deg); c, s = math.cos(a), math.sin(a)
    x, y, z = (p[i] - origin[i] for i in range(3))
    if axis == 'x': y, z = y * c - z * s, y * s + z * c
    elif axis == 'y': x, z = x * c + z * s, -x * s + z * c
    else: x, y = x * c - y * s, x * s + y * c
    return (x + origin[0], y + origin[1], z + origin[2])


def _corners(e):
    (x0, y0, z0), (x1, y1, z1) = e['from'], e['to']
    q = {'north': [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
         'south': [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
         'east': [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
         'west': [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
         'up': [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
         'down': [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)]}
    if 'rotation' in e:
        r = e['rotation']
        q = {k: [_rot(p, r['axis'], r['angle'], r['origin']) for p in v] for k, v in q.items()}
    return q


def rot_xyz(rx, ry, rz):
    """JOML rotationXYZ: Rx * Ry * Rz (degrees) -- what Minecraft uses for display transforms."""
    a, b, c = (math.radians(v) for v in (rx, ry, rz))
    Rx = np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])
    Ry = np.array([[math.cos(b), 0, math.sin(b)], [0, 1, 0], [-math.sin(b), 0, math.cos(b)]])
    Rz = np.array([[math.cos(c), -math.sin(c), 0], [math.sin(c), math.cos(c), 0], [0, 0, 1]])
    return Rx @ Ry @ Rz


# ---------------------------------------------------------------- z-buffered preview renderer
def raster(model_json, tex, project, W, H, bg=(139, 139, 139), light_dir=(0.35, 0.8, 0.5)):
    """project(points Nx3 model units) -> (sx, sy, depth) arrays."""
    T = np.asarray(tex.convert('RGBA'), dtype=np.float64); S = tex.width
    img = np.zeros((H, W, 3)); img[:] = bg
    zb = np.full((H, W), np.inf)
    L = np.array(light_dir); L = L / np.linalg.norm(L)
    for el in model_json['elements']:
        quads = _corners(el)
        for f, fd in el['faces'].items():
            q = np.array(quads[f], dtype=np.float64)
            sx, sy, dz = project(q)
            if np.any(dz <= 0.01): continue
            # screen-space winding test (y down): front faces are clockwise here
            cross = (sx[1] - sx[0]) * (sy[3] - sy[0]) - (sy[1] - sy[0]) * (sx[3] - sx[0])
            if cross <= 0: continue
            # lighting from the model-space normal, like MC's fixed item lighting
            n = np.cross(q[1] - q[0], q[3] - q[0]); n = n / (np.linalg.norm(n) or 1)
            shade = 0.55 + 0.45 * max(0.0, float(n @ L))
            if el.get('light_emission'): shade = 1.0
            u1, v1, u2, v2 = (v * S / 16 for v in fd['uv'])
            tuv = np.array([(u1, v1), (u2, v1), (u2, v2), (u1, v2)])
            for tri in ((0, 1, 2), (0, 2, 3)):
                _tri(img, zb, T, sx[list(tri)], sy[list(tri)], dz[list(tri)], tuv[list(tri)], shade, W, H)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


def _tri(img, zb, T, xs, ys, ds, uv, shade, W, H):
    x0, x1 = int(max(0, math.floor(xs.min()))), int(min(W - 1, math.ceil(xs.max())))
    y0, y1 = int(max(0, math.floor(ys.min()))), int(min(H - 1, math.ceil(ys.max())))
    if x0 > x1 or y0 > y1: return
    det = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
    if abs(det) < 1e-12: return
    X, Y = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
    l1 = ((ys[1] - ys[2]) * (X - xs[2]) + (xs[2] - xs[1]) * (Y - ys[2])) / det
    l2 = ((ys[2] - ys[0]) * (X - xs[2]) + (xs[0] - xs[2]) * (Y - ys[2])) / det
    l3 = 1 - l1 - l2
    m = (l1 >= -1e-6) & (l2 >= -1e-6) & (l3 >= -1e-6)
    if not m.any(): return
    d = l1 * ds[0] + l2 * ds[1] + l3 * ds[2]
    u = l1 * uv[0, 0] + l2 * uv[1, 0] + l3 * uv[2, 0]
    v = l1 * uv[0, 1] + l2 * uv[1, 1] + l3 * uv[2, 1]
    ui = np.clip(np.floor(np.clip(u, uv[:, 0].min(), uv[:, 0].max() - 1e-3)).astype(int), 0, T.shape[1] - 1)
    vi = np.clip(np.floor(np.clip(v, uv[:, 1].min(), uv[:, 1].max() - 1e-3)).astype(int), 0, T.shape[0] - 1)
    c = T[vi, ui]
    sub_z = zb[y0:y1 + 1, x0:x1 + 1]
    m = m & (d < sub_z) & (c[..., 3] > 0)
    sub_z[m] = d[m]
    img[y0:y1 + 1, x0:x1 + 1][m] = c[..., :3][m] * shade


def showcase(model_json, tex, yaw=30, pitch=15, size=320, pad=14):
    R = rot_xyz(pitch, 0, 0) @ rot_xyz(0, yaw, 0)
    pts = np.array([p for el in model_json['elements'] for q in _corners(el).values() for p in q]) - 8
    P = pts @ R.T
    mn, mx = P.min(0), P.max(0)
    s = (size - 2 * pad) / max(mx[0] - mn[0], mx[1] - mn[1])
    cx, cy = (mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2

    def proj(q):
        p = (q - 8) @ R.T
        return size / 2 + (p[:, 0] - cx) * s, size / 2 - (p[:, 1] - cy) * s, 100 - p[:, 2]
    return raster(model_json, tex, proj, size, size)


def first_person(model_json, tex, W=480, H=270, fov=70):
    """Right-hand first-person view, following ItemInHandRenderer (arm offset 0.56, -0.52, -0.72)."""
    t = model_json['display']['firstperson_righthand']
    R = rot_xyz(*t['rotation']); s = np.array(t['scale']); tr = np.array(t['translation']) / 16
    f = (H / 2) / math.tan(math.radians(fov / 2))

    def proj(q):
        p = ((q - 8) / 16 * s) @ R.T + tr + np.array([0.56, -0.52, -0.72])
        return W / 2 + p[:, 0] / -p[:, 2] * f, H / 2 - p[:, 1] / -p[:, 2] * f, -p[:, 2]
    img = raster(model_json, tex, proj, W, H, bg=(120, 160, 210))
    px = img.load()
    for k in range(-6, 7):                  # crosshair
        px[W // 2 + k, H // 2] = (255, 255, 255); px[W // 2, H // 2 + k] = (255, 255, 255)
    return img

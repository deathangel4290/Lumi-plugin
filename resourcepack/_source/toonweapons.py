"""The Luminara weapons as stylised 3D item models (see toon.py for the look)."""
import math
from toon import Model

# ---------------------------------------------------------------- shape helpers
def inside(poly, x, y):
    c = False; n = len(poly)
    for i in range(n):
        (x1, y1), (x2, y2) = poly[i], poly[(i + 1) % n]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1: c = not c
    return c

def seg_dist(px, py, a, b):
    ax, ay = a; bx, by = b; dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy or 1)))
    return math.hypot(px - ax - t * dx, py - ay - t * dy)

def near_poly(poly, x, y, d):
    return any(seg_dist(x, y, poly[i], poly[(i + 1) % len(poly)]) <= d for i in range(len(poly)))

def near_line(pts, x, y, d):
    return any(seg_dist(x, y, pts[i], pts[i + 1]) <= d for i in range(len(pts) - 1))

def rod(m, y0, y1, r, mat, cx=8.0, cz=8.0):
    m.box((cx - r, y0, cz - r), (cx + r, y1, cz + r), mat)

def ring(m, y, h, r, mat):
    rod(m, y, y + h, r, mat)

GOLD = ['#4a2c06', '#9a6414', '#d99a28', '#f7cf4a', '#fff4a8']
SILVER = ['#2a2d3a', '#5f6679', '#9ea6bb', '#d4d9e6', '#ffffff']
BRONZE = ['#3a1c08', '#7a4016', '#b86e2a', '#e3a452', '#fbd99a']
RUBY = ['#3a0008', '#8a0a1e', '#d8203a', '#ff6a7a', '#ffd0d8']
SAPPHIRE = ['#04143a', '#0c3a8a', '#2a70e0', '#70b0ff', '#e0f0ff']

# Where the hand closes (model y). Everything else about the in-hand pose is derived from this.
GRIP = {}

# ---------------------------------------------------------------- Scythe of Diablo
def scythe():
    m = Model()
    m.mat('haft', ['#12060a', '#24101a', '#3a1a28', '#56283a', '#6e3a4e'], 'wood')
    m.mat('wrap', ['#3a0610', '#801226', '#c0263a', '#ec5a5a', '#ff9a8a'], 'wrap')
    m.mat('gold', GOLD)
    m.mat('ruby', RUBY, 'gem', 6)
    m.mat('blade', ['#1a0206', '#6e0a16', '#b01824', '#e03a3a', '#ff7a66'])
    m.mat('edge', ['#5a1a1a', '#ff9a8a', '#ffd0c0', '#ffe8e0', '#ffffff'])
    m.mat('rune', ['#6a0000', '#c01010', '#ff4a20', '#ff9a40', '#ffe080'], 'glow', 12)
    rod(m, -13, 25, 1.0, 'haft')
    rod(m, -6, 2, 1.25, 'wrap')
    for y in (8, 14, 19.5): ring(m, y, 1, 1.25, 'gold')
    rod(m, -15, -13, 1.5, 'gold'); rod(m, -16, -15, 0.75, 'ruby')
    m.box((6, 24, 6), (10, 30, 10), 'gold')
    m.box((7, 25.5, 5.5), (9, 28.5, 10.5), 'ruby')
    rod(m, 30, 32, 0.75, 'gold')
    m.box((9.5, 26.5, 7.25), (15, 28, 8.75), 'gold', ('z', 22.5, (9.5, 27.25, 8)))
    OC, OR, IC, IR = (6.0, 13.5), 16.5, (3.4, 9.2), 15.0
    runes = [(1.0, 27.2), (-4.5, 25.4), (-8.5, 21.8)]
    def at(x, y):
        do = math.hypot(x - OC[0], y - OC[1]); di = math.hypot(x - IC[0], y - IC[1])
        if not (do <= OR and di > IR and x < 6.5 and y > 11): return None
        if any(math.hypot(x - rx, y - ry) < 0.8 for rx, ry in runes): return 'rune'
        if di - IR < 1.3: return 'edge'
        return 'blade'
    m.extrude(at, -12, 7, 11, 30.5, 8.0, 1.5)
    return m
GRIP['scythe_of_diablo'] = -2

# ---------------------------------------------------------------- Luminosity (sun hammer)
def luminosity():
    m = Model()
    m.mat('grip', ['#2a1606', '#5a3212', '#8a5424', '#b67a3e', '#d8a066'], 'wrap')
    m.mat('band', GOLD)
    m.mat('head', ['#5a3a06', '#b07a10', '#e8b020', '#ffd84a', '#fff4b0'])
    m.mat('rim', ['#3e2604', '#7a5008', '#b88216', '#e8b43a', '#ffe08a'])
    m.mat('sun', ['#c06000', '#ff9a00', '#ffcc30', '#ffee80', '#ffffff'], 'glow', 15)
    rod(m, -12, 22, 1.0, 'grip')
    for y in (-2, 6, 14): ring(m, y, 1, 1.5, 'band')
    rod(m, -15, -12, 1.75, 'band')
    m.box((0, 21.5, 4.5), (16, 30.5, 11.5), 'head')
    for x0 in (-2.5, 16):
        m.box((x0, 20.5, 3.5), (x0 + 2.5, 31.5, 12.5), 'rim')
    m.box((6.5, 30.5, 6.5), (9.5, 32, 9.5), 'rim')
    for z0, z1 in ((4, 4.5), (11.5, 12)):
        m.box((5, 23, z0), (11, 29, z1), 'sun')
        m.box((7.5, 29, z0), (8.5, 30.5, z1), 'sun'); m.box((7.5, 21.5, z0), (8.5, 23, z1), 'sun')
        m.box((3, 25.5, z0), (5, 26.5, z1), 'sun'); m.box((11, 25.5, z0), (13, 26.5, z1), 'sun')
    return m
GRIP['luminosity'] = -8

# ---------------------------------------------------------------- Ender Glaive
def glaive():
    m = Model()
    m.mat('shaft', ['#0a0612', '#1a1026', '#2c1a40', '#40285a', '#583a78'], 'wood')
    m.mat('wrap', ['#10061e', '#2a1248', '#46207a', '#6a3aa8', '#9a6ad8'], 'wrap')
    m.mat('collar', ['#1e1030', '#4a2c70', '#7a50aa', '#a882d8', '#d8c0ff'])
    m.mat('blade', ['#1a0630', '#4a1a8a', '#7a38d0', '#a86af0', '#e0c8ff'])
    m.mat('edge', ['#3a1a6a', '#c8a0ff', '#e8d8ff', '#f4ecff', '#ffffff'])
    m.mat('eye', ['#022a18', '#0a6a3a', '#18b060', '#60f0a0', '#e0fff0'], 'gem', 10)
    rod(m, -14, 16, 1.0, 'shaft')
    rod(m, -8, 0, 1.25, 'wrap')
    for y in (4, 10): ring(m, y, 1, 1.25, 'collar')
    rod(m, -16, -14, 0.75, 'collar')
    m.box((6, 14.5, 6), (10, 18, 10), 'collar')
    m.box((7, 15, 5.5), (9, 17.5, 10.5), 'eye')
    hook = [(6.5, 16.5), (2, 21.5), (3, 22.5), (7, 19)]
    def at(x, y):
        if inside(hook, x, y): return 'blade'
        if not 17.5 <= y <= 32: return None
        t = (y - 17.5) / 14.5
        cx = 8 + 3.0 * t * t
        hw = 3.6 * math.sin(math.pi * min(1.0, t * 1.02 + 0.06)) ** 0.75
        if abs(x - cx) > hw: return None
        if x - cx > hw - 1.2: return 'edge'
        return 'blade'
    m.extrude(at, 1.5, 14.5, 16.5, 32, 8.0, 1.5)
    return m
GRIP['ender_glaive'] = -4

# ---------------------------------------------------------------- one-handed swords
def sword_base(m, grip, pommel):
    rod(m, 1, 9, 1.0, grip)
    rod(m, -1.5, 1, 1.5, pommel)

def hermes():
    m = Model()
    m.mat('grip', ['#10284a', '#1e4a8a', '#3a78c8', '#6aa8f0', '#b0d8ff'], 'wrap')
    m.mat('gold', GOLD)
    m.mat('blade', ['#3a3e4e', '#8a92a8', '#c4cad8', '#e8ecf4', '#ffffff'])
    m.mat('feather', ['#5a6480', '#b8c4dc', '#e8eef8', '#ffffff', '#ffffff'])
    m.mat('tip', ['#2a5aa0', '#6aa8f0', '#a8d0ff', '#d8ecff', '#ffffff'])
    m.mat('sapphire', SAPPHIRE, 'gem', 8)
    sword_base(m, 'grip', 'gold')
    m.box((5, 9, 6.5), (11, 10.5, 9.5), 'gold')
    m.box((7, 9, 6), (9, 10.5, 10), 'sapphire')
    m.mat('fuller', ['#2a2d3a', '#4a5064', '#7a8298', '#a8b0c4', '#d4d9e6'])
    def blade(x, y):
        if y < 10.5: return None
        hw = 2.0 if y < 26.5 else 2.0 * max(0.0, (31.5 - y) / 5)
        if abs(x - 8) > hw: return None
        return 'fuller' if abs(x - 8) < 0.5 and y < 25 else 'blade'
    m.extrude(blade, 5.5, 10.5, 10.5, 31.5, 8.0, 1.5)
    # three swept-back feathers per side, longest on top, blue tips
    feathers = [((7, 11), (15.5, 18), 2.0), ((7, 10), (16.5, 13.5), 1.7), ((7, 9.5), (14.5, 8.5), 1.4)]
    def wing(x, y):
        dx = abs(x - 8)
        if dx < 2.5: return None
        for (ax, ay), (bx, by), w in feathers:
            a, b = (8 + (ax - 8), ay), (8 + (bx - 8), by)
            px = 8 + dx
            t = max(0.0, min(1.0, ((px - a[0]) * (b[0] - a[0]) + (y - a[1]) * (b[1] - a[1])) / ((b[0] - a[0]) ** 2 + (b[1] - a[1]) ** 2)))
            if seg_dist(px, y, a, b) <= w * (1 - 0.6 * t ** 2):
                return 'tip' if t > 0.7 else 'feather'
        return None
    m.extrude(wing, -1.5, 17.5, 6.5, 20, 8.0, 1.0)
    return m
GRIP['hermes_blade'] = 5

def chione():
    m = Model()
    m.mat('grip', ['#0e1a30', '#22385a', '#3a5a88', '#5a82b0', '#8ab0d8'], 'wrap')
    m.mat('silver', SILVER)
    m.mat('ice', ['#12305a', '#2a6aa8', '#5aaee0', '#a0e0ff', '#f0ffff'])
    m.mat('frost', ['#3a78a8', '#c0ecff', '#e8faff', '#ffffff', '#ffffff'])
    m.mat('gem', ['#1a4a80', '#3a90d0', '#80d8ff', '#c8f4ff', '#ffffff'], 'gem', 10)
    sword_base(m, 'grip', 'silver')
    m.box((5.5, 9, 6.5), (10.5, 10.5, 9.5), 'silver')
    m.box((7, 9, 6), (9, 10.5, 10), 'gem')
    def blade(x, y):
        if y < 10.5: return None
        tooth = (y * 0.55) % 1.0
        hw = 1.6 + 1.2 * tooth if y < 27.5 else (1.6 + 1.2 * tooth) * max(0.0, (32 - y) / 4.5)
        if abs(x - 8) > hw: return None
        return 'frost' if abs(x - 8) > hw - 1.0 else 'ice'
    m.extrude(blade, 5, 11, 10.5, 32, 8.0, 1.5)
    spikes = [((6, 10), (1.5, 13.5)), ((6, 9.5), (2.5, 7.5)), ((10, 10), (14.5, 13.5)), ((10, 9.5), (13.5, 7.5))]
    def guard(x, y):
        if 5.5 <= x <= 10.5: return None
        for a, b in spikes:
            t = math.hypot(x - a[0], y - a[1]) / math.hypot(b[0] - a[0], b[1] - a[1])
            if seg_dist(x, y, a, b) < 1.0 - 0.7 * t: return 'frost'
        return None
    m.extrude(guard, 1, 15, 7, 14, 8.0, 1.0)
    return m
GRIP['chiones_blade'] = 5

def diminution():
    m = Model()
    m.mat('grip', ['#1a0826', '#3a1656', '#5a2a84', '#8050b0', '#a878d8'], 'wrap')
    m.mat('guard', ['#1e0a2a', '#4a1e62', '#7a3a9a', '#a864c8', '#d8a8f0'])
    m.mat('blade', ['#2a0a3a', '#5a2080', '#8a44c0', '#b87ae8', '#ecc8ff'])
    m.mat('gem', ['#4a0a3a', '#a01880', '#e040b0', '#ff88d8', '#ffe0f4'], 'gem', 8)
    sword_base(m, 'grip', 'guard')
    def blade(x, y):
        if y < 10.5: return None
        hw = 2.6 - (y - 10.5) * 0.05 if y < 27 else (2.6 - 16.5 * 0.05) * max(0.0, (32 - y) / 5)
        return 'blade' if abs(x - 8) <= hw else None
    m.extrude(blade, 5, 11, 10.5, 32, 8.0, 1.5)
    arms = [(7, 9.8), (2.5, 12.5)], [(9, 9.8), (13.5, 12.5)]
    m.extrude(lambda x, y: 'guard' if (5 <= x <= 11 and 9 <= y <= 10.5) or near_line(arms[0], x, y, 0.8) or near_line(arms[1], x, y, 0.8) else None,
              1, 15, 8.5, 13.5, 8.0, 2.0)
    for y0, r in ((13, 1.0), (17, 0.75), (20.5, 0.5), (23.5, 0.5)):   # gems shrinking up the blade
        m.box((8 - r, y0, 7), (8 + r, y0 + 2 * r, 9), 'gem')
    m.box((7, -4, 7), (9, -1.5, 9), 'gem', ('y', 45, (8, -2.75, 8)))
    return m
GRIP['blade_of_diminution'] = 5

def eclipse():
    m = Model()
    m.mat('grip', ['#0e0a16', '#241c38', '#3a2e56', '#56467a', '#7a68a0'], 'wrap')
    m.mat('gold', GOLD)
    m.mat('light', ['#4a4060', '#b8b0cc', '#e6e2f2', '#ffffff', '#ffffff'])
    m.mat('dark', ['#000000', '#0c0816', '#1c1430', '#30264e', '#4a3c70'])
    m.mat('seam', ['#6a1050', '#c02890', '#ff50c0', '#ff9ae0', '#fff0fa'], 'glow', 12)
    m.mat('horn', ['#2a0a24', '#6a1a58', '#a8309a', '#d860c8', '#ffa8f0'])
    m.mat('sun', GOLD, 'gem', 10)
    m.mat('moon', SILVER, 'gem', 6)
    sword_base(m, 'grip', 'gold')
    def blade(x, y):
        if y < 10.5: return None
        hw = 2.5 if y < 26.5 else 2.5 * max(0.0, (31.5 - y) / 5)
        if abs(x - 8) > hw: return None
        if abs(x - 8) < 0.5: return 'seam'
        return 'light' if x < 8 else 'dark'
    m.extrude(blade, 5, 11, 10.5, 31.5, 8.0, 1.5)
    horns = [(7, 9.5), (4.5, 10), (2.5, 12), (2, 15)], [(9, 9.5), (11.5, 10), (13.5, 12), (14, 15)]
    m.extrude(lambda x, y: 'horn' if (5 <= x <= 11 and 9 <= y <= 10.5) or near_line(horns[0], x, y, 0.85) or near_line(horns[1], x, y, 0.85) else None,
              0.5, 15.5, 8.5, 16, 8.0, 1.75)
    m.box((6, 15.5, 6.75), (7.5, 17, 9.25), 'sun')
    m.box((8.5, 21, 6.75), (10, 22.5, 9.25), 'moon')
    return m
GRIP['sword_of_eclipse'] = 5

# ---------------------------------------------------------------- axes
def zeus():
    m = Model()
    m.mat('handle', ['#08101e', '#141e36', '#223050', '#34466e', '#4a6090'], 'wood')
    m.mat('wrap', ['#0a1428', '#16305a', '#244c8a', '#3a70b8', '#6aa0e0'], 'wrap')
    m.mat('gold', GOLD)
    m.mat('steel', ['#0a1a3a', '#1e4080', '#2e64b8', '#5a90e0', '#a8d0ff'])
    m.mat('edge', ['#2a6ab0', '#60b0ff', '#a8e0ff', '#e0f6ff', '#ffffff'], 'metal', 10)
    m.mat('bolt', ['#a06000', '#f0a800', '#ffd83a', '#fff08a', '#ffffff'], 'glow', 15)
    m.mat('sapphire', SAPPHIRE, 'gem', 10)
    rod(m, -14, 26, 1.0, 'handle')
    rod(m, -10, -2, 1.25, 'wrap')
    for y in (2, 7, 12): ring(m, y, 1, 1.25, 'gold')
    rod(m, -16, -14, 1.5, 'gold')
    m.box((6, 17, 6), (10, 29, 10), 'gold')
    rod(m, 29, 32, 0.75, 'gold')
    m.box((7, 21.5, 5.5), (9, 24, 10.5), 'sapphire')
    head = [(7, 28.5), (3, 29.5), (-1.5, 30.5), (-5.5, 29.5), (-8.5, 26.5), (-10, 22), (-9.5, 17), (-7.5, 13.5), (-5, 12.5), (-4, 15), (-2, 18), (1.5, 18.5), (7, 18)]
    fluke = [(9, 27), (14, 29.5), (12.5, 24), (9, 22.5)]
    bolt = [(3, 27), (-2, 24), (1, 22.5), (-4.5, 19.5), (-2, 18.5), (-7, 15)]
    def at(x, y):
        if inside(fluke, x, y): return 'steel'
        if not inside(head, x, y): return None
        if near_line(bolt, x, y, 0.7): return 'bolt'
        if x < -3 and near_poly(head[3:10], x, y, 1.4): return 'edge'
        return 'steel'
    m.extrude(at, -11, 15, 12, 31, 8.0, 1.5)
    return m
GRIP['axe_of_zeus'] = -6

def titan():
    m = Model()
    m.mat('handle', ['#1a0e06', '#36200e', '#553418', '#774c26', '#9a6a3c'], 'wood')
    m.mat('bronze', BRONZE)
    m.mat('stone', ['#1a171c', '#34303a', '#504a56', '#6e6676', '#8e8698'], 'stone')
    m.mat('steel', ['#3a3e4a', '#8a90a0', '#c0c6d4', '#e4e8f0', '#ffffff'])
    m.mat('magma', ['#801800', '#e04000', '#ff7a10', '#ffc040', '#fff0a0'], 'glow', 15)
    rod(m, -14, 20, 1.25, 'handle')
    for y in (-2, 4, 10): ring(m, y, 1, 1.5, 'bronze')
    m.box((6, -16, 6), (10, -14, 10), 'bronze', ('y', 45, (8, -15, 8)))
    m.box((5.5, 16, 5.5), (10.5, 30, 10.5), 'bronze')
    m.box((6.5, 30, 6.5), (9.5, 32, 9.5), 'bronze')
    right = [(10, 18), (13, 16), (17, 15.5), (20, 17.5), (21.5, 22), (21.5, 26), (20, 30), (17, 31.5), (13, 30.5), (10, 28)]
    left = [(16 - x, y) for x, y in right]
    cracks = [[(13, 23), (15.5, 21.5), (17, 22.5), (19, 20)], [(15.5, 21.5), (16, 26), (18.5, 28)]]
    cracks += [[(16 - x, y) for x, y in c] for c in cracks]
    def at(x, y):
        for poly in (right, left):
            if inside(poly, x, y):
                if any(near_line(c, x, y, 0.55) for c in cracks): return 'magma'
                return 'steel' if abs(x - 8) > 10 and near_poly(poly, x, y, 1.4) else 'stone'
        return None
    m.extrude(at, -6, 22, 15, 32, 8.0, 2.5)
    return m
GRIP['titans_axe'] = -8

# ---------------------------------------------------------------- trident
def trident():
    m = Model()
    m.mat('shaft', ['#06282e', '#0e4a54', '#18707a', '#34a0a8', '#70d0d0'])
    m.mat('coral', ['#4a0a1e', '#9a1e40', '#e04a6a', '#ff8a9a', '#ffd0d8'], 'wrap')
    m.mat('gold', GOLD)
    m.mat('prong', ['#0a3a44', '#18808a', '#30c0c0', '#80f0e8', '#e0fffc'])
    m.mat('pearl', ['#6a6a88', '#b8bcd8', '#e8ecff', '#ffffff', '#ffffff'], 'gem', 8)
    rod(m, -14, 20, 0.75, 'shaft')
    rod(m, -6, 2, 1.0, 'coral')
    for y in (8, 14): ring(m, y, 1, 1.0, 'coral')
    rod(m, -16, -14, 1.25, 'gold')
    m.box((1.5, 19.5, 7), (14.5, 21.5, 9), 'gold')
    m.box((6.5, 19, 6.5), (9.5, 22, 9.5), 'pearl')
    def at(x, y):
        for cx, top in ((8, 32), (2.5, 29.5), (13.5, 29.5)):
            t = (y - (top - 4.5)) / 4.5
            hw = 0.75 if t < 0 else 0.75 + 1.1 * t / 0.3 if t < 0.3 else 1.85 * (1 - t) / 0.7
            if abs(x - cx) <= hw: return 'prong'
        return None
    m.extrude(at, 1, 15, 21.5, 32, 8.0, 1.5)
    return m
GRIP['lost_trident'] = -2

# ---------------------------------------------------------------- tether chain
def tether():
    m = Model()
    m.mat('link', SILVER)
    m.mat('dark', ['#16161c', '#3a3a46', '#62626e', '#8a8a98', '#b4b4c2'])
    m.mat('hook', ['#3a1a06', '#80401a', '#c06a2a', '#eaa05a', '#ffd8a0'])
    m.mat('grip', ['#1a0e06', '#3a2210', '#5a3818', '#7a5028', '#9a6a3c'], 'wrap')
    rod(m, -16, -10, 1.25, 'grip')
    y = -10
    for i in range(5):
        mat = 'link' if i % 2 == 0 else 'dark'
        if i % 2 == 0:
            m.box((6, y, 7.5), (10, y + 1, 8.5), mat); m.box((6, y + 5, 7.5), (10, y + 6, 8.5), mat)
            m.box((6, y + 1, 7.5), (7, y + 5, 8.5), mat); m.box((9, y + 1, 7.5), (10, y + 5, 8.5), mat)
        else:
            m.box((7.5, y, 6), (8.5, y + 1, 10), mat); m.box((7.5, y + 5, 6), (8.5, y + 6, 10), mat)
            m.box((7.5, y + 1, 6), (8.5, y + 5, 7), mat); m.box((7.5, y + 1, 9), (8.5, y + 5, 10), mat)
        y += 5
    hook = [(8, 15), (8, 24), (9, 28), (12, 30.5), (15, 29), (15.5, 25.5), (13.5, 23)]
    m.extrude(lambda x, yy: 'hook' if near_line(hook, x, yy, 1.1) else None, 6, 17, 14.5, 32, 8.0, 2.0)
    m.box((12.5, 22, 7), (14.5, 24, 9), 'hook', ('z', 45, (13.5, 23, 8)))
    return m
GRIP['tether_chain'] = -13

# ---------------------------------------------------------------- Veil of Mimicry (book, cover faces north)
def veil():
    m = Model()
    m.mat('leather', ['#12061e', '#2a1044', '#44206a', '#643a92', '#8a5cba'])
    m.mat('pages', ['#8a7a5a', '#c8b890', '#ece0bc', '#fff6dc', '#ffffff'])
    m.mat('silver', SILVER)
    m.mat('light', ['#6a6480', '#d8d4e6', '#f4f2fa', '#ffffff', '#ffffff'])
    m.mat('dark', ['#000000', '#0e0818', '#1c1230', '#2c2048', '#403260'])
    m.mat('eye', ['#6a30b0', '#a060ff', '#d0a0ff', '#f0e0ff', '#ffffff'], 'glow', 10)
    m.box((2, 0, 6), (14, 16, 7), 'leather'); m.box((2, 0, 9), (14, 16, 10), 'leather')
    m.box((1, 0, 6), (2, 16, 10), 'leather')
    m.box((2.5, 0.5, 7), (13.5, 15.5, 9), 'pages')
    for x0, y0 in ((12, 14), (12, 0), (1, 14), (1, 0)):
        m.box((x0, y0, 5.5), (x0 + 2, y0 + 2, 10.5), 'silver')
    for x0, y0, x1, y1 in ((3, 1.5, 13, 2), (3, 14, 13, 14.5), (3, 2, 3.5, 14), (12.5, 2, 13, 14)):   # silver frame
        m.box((x0, y0, 5.5), (x1, y1, 6), 'silver')
    m.box((4.5, 3.5, 5.5), (8, 12.5, 6), 'light'); m.box((8, 3.5, 5.5), (11.5, 12.5, 6), 'dark')   # the two-faced mask
    m.box((5.5, 9, 5), (7, 10, 5.5), 'dark'); m.box((9, 9, 5), (10.5, 10, 5.5), 'eye')           # eyes
    m.box((8, 6, 5), (10.5, 7, 5.5), 'eye')                                                     # the lopsided smile
    return m

MODELS = {'scythe_of_diablo': scythe, 'luminosity': luminosity, 'ender_glaive': glaive, 'hermes_blade': hermes,
          'chiones_blade': chione, 'blade_of_diminution': diminution, 'sword_of_eclipse': eclipse, 'axe_of_zeus': zeus,
          'titans_axe': titan, 'lost_trident': trident, 'tether_chain': tether, 'veil_of_mimicry': veil}

# ---------------------------------------------------------------- Hellstorm (crossbow, one model per draw state)
# Built in the same diagonal layout as the vanilla crossbow sprite so the vanilla crossbow hand poses fit:
# everything is modelled along u (butt -> nose) and v, then turned 45 degrees about the model centre.
DIAG = ('z', 45.0, (8.0, 8.0, 8.0))
NOCK = {'standby': 2.2, 'pulling_0': 0.8, 'pulling_1': -1.2, 'pulling_2': -3.2, 'arrow': -3.2, 'firework': -3.2}

def hellstorm(state='standby'):
    m = Model()
    m.mat('stock', ['#140a08', '#2e1a14', '#4a2c20', '#6a4230', '#8a5a40'], 'wood')
    m.mat('iron', ['#24242c', '#4a4a56', '#74747e', '#a4a4ae', '#d8d8e0'])
    m.mat('wrap', ['#200806', '#4a1410', '#74241a', '#9a3a28', '#c05a40'], 'wrap')
    m.mat('limb', ['#3a0c00', '#8a2400', '#d05010', '#f08a30', '#ffc870'])
    m.mat('ember', ['#802000', '#e04a00', '#ff8a20', '#ffc860', '#fff0b0'], 'glow', 12)
    m.mat('string', ['#ffe0b0', '#ffe0b0', '#ffe8c8', '#fff4e0', '#ffffff'], 'glow', 8)
    m.mat('gem', ['#5a0000', '#b01010', '#ff3a20', '#ff9a60', '#ffe0c0'], 'gem', 12)
    U = lambda u: 8.0 + u                                   # u/v -> pre-rotation x/y
    def box(u0, v0, u1, v1, z0, z1, mat):
        m.box((U(u0), U(v0), z0), (U(u1), U(v1), z1), mat, DIAG)
    box(-9.0, -1.0, 6.0, 1.0, 7.0, 9.0, 'stock')           # stock
    box(-9.5, -1.4, -8.5, 1.4, 6.6, 9.4, 'iron')           # butt plate
    box(-7.0, -1.25, -4.0, 1.25, 6.75, 9.25, 'wrap')       # grip
    box(-3.5, -2.5, -2.5, -1.0, 7.5, 8.5, 'iron')          # trigger
    box(-3.0, -1.2, 1.5, 1.2, 9.0, 9.4, 'iron')            # rail
    box(4.0, -1.4, 6.5, 1.4, 6.6, 9.4, 'iron')             # nose cap
    box(4.6, -0.6, 5.9, 0.6, 6.3, 9.7, 'gem')              # fire gem through the nose
    # limbs: an arc bulging toward the nose, with a glowing vein
    def limb(x, y):
        u, v = x - 8, y - 8
        if abs(v) > 6.6: return None
        c = 4.8 - 0.055 * v * v
        w = 0.9 - 0.25 * abs(v) / 6.6
        if abs(u - c) > w: return None
        return 'ember' if abs(u - c) < 0.3 and 0.8 < abs(v) < 5.8 else 'limb'
    m.extrude(limb, 0, 16, 0, 16, 8.0, 2.0, rot=DIAG)
    # string: tips -> nock
    tip = 4.8 - 0.055 * 6.4 * 6.4
    nock = NOCK[state]
    def string(x, y):
        u, v = x - 8, y - 8
        if abs(v) > 6.4: return None
        target = nock + (tip - nock) * abs(v) / 6.4
        return 'string' if abs(u - target) <= 0.26 else None
    m.extrude(string, 0, 16, 0, 16, 9.0, 0.5, rot=DIAG)
    if state == 'arrow':
        box(-3.5, -0.3, 7.5, 0.3, 9.4, 10.0, 'stock')      # shaft
        box(7.5, -0.8, 9.0, 0.8, 9.3, 10.1, 'ember')       # flaming head
        box(-3.5, -1.0, -2.0, 1.0, 9.5, 9.9, 'iron')       # fletching
    elif state == 'firework':
        m.mat('rocket', ['#4a0008', '#900a18', '#d8203a', '#ff6070', '#ffc0c8'])
        m.mat('paper', ['#8a7a5a', '#c8b890', '#ece0bc', '#fff6dc', '#ffffff'])
        box(-2.5, -1.0, 5.5, 1.0, 9.4, 11.4, 'rocket')
        box(-1.5, -1.05, -0.5, 1.05, 9.35, 11.45, 'paper')
        box(3.5, -1.05, 4.5, 1.05, 9.35, 11.45, 'paper')
        box(5.5, -0.6, 7.0, 0.6, 9.8, 11.0, 'iron')
    return m

XBOW_STATES = list(NOCK)

# ---------------------------------------------------------------- amethyst tools (DonutSMP style)
# Vanilla tool layout (handle bottom-left, head top-right) so the vanilla handheld poses fit exactly.
AMETHYST = ['#2a0a4a', '#5a2a9a', '#9a5ad8', '#c89aff', '#f4e4ff']
AMETHYST_EDGE = ['#3a1a6a', '#a878e8', '#cca8ff', '#eadcff', '#ffffff']

def _uv(x, y):
    return ((x - 8) + (y - 8)) / math.sqrt(2), ((y - 8) - (x - 8)) / math.sqrt(2)

def _tool(head):
    m = Model()
    m.mat('handle', ['#141216', '#2a262c', '#423c44', '#5e5660', '#7a7280'])
    m.mat('band', AMETHYST)
    m.mat('head', AMETHYST)
    m.mat('edge', AMETHYST_EDGE)
    m.mat('core', ['#6a2ab0', '#b070ff', '#e0b8ff', '#f8ecff', '#ffffff'], 'glow', 8)
    def at(x, y):
        u, v = _uv(x, y)
        h = head(u, v)
        if h: return h
        if -9.2 <= u <= 5.0 and abs(v) <= 0.8:
            return 'band' if (-6.2 <= u <= -5.2) or (-2.2 <= u <= -1.2) else 'handle'
        return None
    m.extrude(at, 0, 16, 0, 16, 8.0, 1.25)
    return m

def amethyst_pickaxe():
    def head(u, v):
        if abs(v) > 7.6: return None
        c = 5.2 - 0.05 * v * v
        w = 1.35 - 0.8 * (abs(v) / 7.6) ** 2
        if abs(u - c) > w: return None
        if abs(v) < 1.1 and abs(u - c) < 0.7: return 'core'
        return 'edge' if abs(v) > 5.6 else 'head'
    return _tool(head)

def amethyst_shovel():
    def head(u, v):
        if 2.8 <= u <= 4.2 and abs(v) <= 1.1: return 'band'
        d = ((u - 7.0) / 3.1) ** 2 + (v / 2.5) ** 2
        if d > 1: return None
        if d < 0.12: return 'core'
        return 'edge' if u > 8.6 else 'head'
    return _tool(head)

def amethyst_axe():
    def head(u, v):
        if 3.2 <= u <= 6.8 and -1.9 <= v <= 0.9: return 'head'          # poll behind the handle
        if v < 0.8: return None
        lo, hi = 3.4 - 0.45 * (v - 0.8), 6.6 + 0.45 * (v - 0.8)
        edge = 4.6 + 0.9 * (1 - ((u - 5.0) / 3.3) ** 2)
        if not (lo <= u <= hi) or v > edge: return None
        if abs(u - 5.0) < 0.6 and 1.4 < v < 2.4: return 'core'
        return 'edge' if v > edge - 1.1 else 'head'
    return _tool(head)

TOOLS = {'amethyst_pickaxe': amethyst_pickaxe, 'amethyst_shovel': amethyst_shovel, 'amethyst_axe': amethyst_axe}

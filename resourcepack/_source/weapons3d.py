import math
from m3d import *

# ---------------------------------------------------------------- geometry helpers
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

GOLD = metal((110, 66, 12), (212, 158, 46), (250, 214, 110), (255, 246, 200))
SILVER = metal((70, 74, 88), (170, 176, 192), (220, 226, 238), (255, 255, 255))
BRONZE = metal((80, 42, 14), (166, 104, 44), (212, 156, 82), (244, 206, 140))

# ---------------------------------------------------------------- Scythe of Diablo (smooth crescent)
def scythe():
    m = Model(); A = m.atlas
    A.material('haft', grain((14, 8, 12), (38, 22, 30), (70, 44, 56)))
    A.material('wrap', wrap((60, 6, 14), (140, 18, 30), (220, 70, 80)))
    A.material('gold', GOLD)
    A.material('ruby', gem((90, 6, 16), (210, 20, 40), (255, 150, 150)))
    A.material('blade', metal((80, 8, 16), (150, 20, 34), (196, 46, 58), (236, 110, 110), streak=False))
    A.material('edge', flat((255, 205, 195), (235, 160, 160)))
    A.material('spine', flat((60, 4, 12), (40, 2, 8)))
    A.material('rune', glow((255, 120, 90), (220, 30, 30)))
    rod(m, -12, 27, 1.0, 'haft')
    rod(m, 2, 10, 1.1, 'wrap')
    for y in (-8, 14, 20): ring(m, y, 1, 1.2, 'wrap')
    rod(m, -14, -12, 1.5, 'gold'); rod(m, -15, -14, 0.7, 'ruby')
    m.box((6.2, 24.5, 6.2), (9.8, 29.5, 9.8), 'gold')
    m.box((7.0, 25.5, 5.9), (9.0, 28.5, 10.1), 'ruby')
    rod(m, 29.5, 32, 0.6, 'gold')
    m.box((9.5, 26.6, 7.6), (14.5, 27.8, 8.4), 'gold', ('z', 22.5, (9.5, 27.2, 8)))
    OC, OR, IC, IR = (6.0, 13.5), 16.5, (3.6, 10.5), 15.0
    runes = [(1.0, 27.2), (-4.5, 25.4), (-8.5, 21.8)]
    def at(x, y):
        do = math.hypot(x - OC[0], y - OC[1]); di = math.hypot(x - IC[0], y - IC[1])
        if not (do <= OR and di > IR and x < 6.5 and y > 11): return None
        if any(math.hypot(x - rx, y - ry) < 0.6 for rx, ry in runes): return 'rune'
        if di - IR < 0.9: return 'edge'
        if OR - do < 0.7: return 'spine'
        return 'blade'
    extrude(m, at, -12, 7, 11, 30.5, 8.0, 1.1)
    return m

# ---------------------------------------------------------------- Luminosity (warhammer)
def luminosity():
    m = Model(); A = m.atlas
    A.material('grip', grain((50, 30, 10), (110, 74, 30), (160, 118, 60)))
    A.material('band', GOLD)
    A.material('head', metal((130, 86, 16), (222, 166, 42), (248, 208, 96), (255, 244, 190)))
    A.material('rim', metal((96, 60, 10), (190, 136, 30), (236, 190, 80), (255, 232, 160)))
    A.material('sun', glow((255, 255, 235), (255, 190, 60)))
    rod(m, -12, 22, 1.1, 'grip')
    for y in (-6, 2, 10, 16): ring(m, y, 1, 1.35, 'band')
    rod(m, -15, -12, 1.7, 'band')
    m.box((0, 21.5, 4.5), (16, 30.5, 11.5), 'head')
    for x0 in (-2.5, 16):                                            # thick faces
        m.box((x0, 20.5, 3.5), (x0 + 2.5, 31.5, 12.5), 'rim')
    for x0, y0 in ((-3.5, 19.5), (-3.5, 30.5), (16.5, 19.5), (16.5, 30.5)):   # corner studs
        m.box((x0, y0, 5.5), (x0 + 3, y0 + 1.5, 10.5), 'rim')
    m.box((6.5, 30.5, 6.5), (9.5, 32, 9.5), 'rim')                   # crown
    for z0 in (4.1, 11.1):                                           # the sun gem on both faces
        m.box((5, 23, z0 - 0.4), (11, 29, z0 + 0.8), 'sun')
    for (x0, y0, x1, y1) in ((7.5, 29, 8.5, 30.5), (7.5, 21.5, 8.5, 23), (3.5, 25.5, 5, 26.5), (11, 25.5, 12.5, 26.5)):
        m.box((x0, y0, 3.9), (x1, y1, 12.1), 'sun')                  # rays
    return m

# ---------------------------------------------------------------- Ender Glaive
def glaive():
    m = Model(); A = m.atlas
    A.material('shaft', grain((14, 8, 24), (34, 18, 52), (58, 34, 84)))
    A.material('collar', metal((40, 24, 60), (96, 70, 122), (140, 110, 166), (190, 160, 210)))
    A.material('blade', metal((50, 16, 90), (126, 62, 206), (172, 110, 244), (230, 200, 255), streak=False))
    A.material('edge', flat((238, 214, 255), (200, 170, 240)))
    A.material('fuller', flat((86, 38, 150), (70, 30, 120)))
    A.material('eye', gem((8, 50, 30), (40, 190, 110), (180, 255, 220)))
    rod(m, -14, 16, 0.9, 'shaft')
    for y in (-10, -4, 12): ring(m, y, 1, 1.15, 'collar')
    rod(m, -16, -14, 0.6, 'collar')
    m.box((6.3, 14.5, 6.3), (9.7, 18, 9.7), 'collar')
    m.box((7.0, 15.2, 6.0), (9.0, 17.3, 10.0), 'eye')
    hook = [(6.5, 16.5), (2.5, 21), (3.2, 21.8), (6.8, 18.8)]
    def at(x, y):
        if inside(hook, x, y): return 'blade'
        if not 17.5 <= y <= 32: return None
        t = (y - 17.5) / 14.5
        cx = 8 + 3.0 * t * t
        hw = 3.3 * math.sin(math.pi * min(1.0, t * 1.02 + 0.06)) ** 0.75
        if abs(x - cx) > hw: return None
        if x - cx > hw - 0.8: return 'edge'
        if abs(x - cx + 0.4) < 0.35 and t < 0.8: return 'fuller'
        return 'blade'
    extrude(m, at, 2, 14, 16.5, 32, 8.0, 1.0)
    return m

# ---------------------------------------------------------------- one-handed sword template
def sword(m, blade_at, grip_mat, guard, pommel, blade_top=31.5):
    rod(m, 1, 9, 0.9, grip_mat)
    for y in (2.5, 5, 7.5): ring(m, y, 0.6, 1.05, pommel)
    rod(m, -1.5, 1, 1.4, pommel)
    extrude(m, blade_at, 3, 13, 10.5, blade_top, 8.0, 1.0)

def hermes():
    m = Model(); A = m.atlas
    A.material('grip', wrap((20, 40, 80), (50, 90, 160), (120, 170, 230)))
    A.material('gold', GOLD)
    A.material('blade', SILVER)
    A.material('fuller', flat((150, 156, 176), (130, 136, 156)))
    A.material('feather', metal((170, 170, 184), (232, 232, 240), (250, 250, 255), (255, 255, 255), streak=False))
    A.material('sapphire', gem((10, 30, 90), (60, 130, 240), (200, 230, 255)))
    def blade(x, y):
        hw = 1.9 if y < 27 else 1.9 * max(0.0, (31.5 - y) / 4.5)
        if abs(x - 8) > hw: return None
        return 'fuller' if abs(x - 8) < 0.35 and y < 27 else 'blade'
    sword(m, blade, 'grip', None, 'gold')
    m.box((5, 9, 6.8), (11, 10.5, 9.2), 'gold')
    m.box((7.2, 9.1, 6.5), (8.8, 10.4, 9.5), 'sapphire')
    def wing(x, y):
        for side in (-1, 1):
            dx = (x - 8) * side
            if 2.5 <= dx <= 8:
                top = 10.5 + (dx - 2.5) * 0.85
                if 9 <= y <= top and not ((dx % 1.6) < 0.45 and y > top - 1.5): return 'feather'
        return None
    extrude(m, wing, -1, 17, 9, 16, 8.0, 0.8)
    return m

def chione():
    m = Model(); A = m.atlas
    A.material('grip', wrap((14, 24, 44), (40, 64, 104), (100, 140, 190)))
    A.material('silver', SILVER)
    A.material('ice', metal((60, 130, 190), (140, 206, 244), (200, 238, 255), (255, 255, 255), streak=True))
    A.material('frost', flat((235, 250, 255), (200, 236, 255)))
    A.material('gem', gem((20, 60, 110), (120, 210, 255), (255, 255, 255)))
    def blade(x, y):
        tooth = (y * 0.55) % 1.0
        hw = 1.6 + 1.1 * tooth if y < 27.5 else (1.6 + 1.1 * tooth) * max(0.0, (32 - y) / 4.5)
        if abs(x - 8) > hw: return None
        return 'frost' if abs(x - 8) > hw - 0.5 else 'ice'
    sword(m, blade, 'grip', None, 'silver', blade_top=32)
    m.box((5.5, 9, 6.8), (10.5, 10.5, 9.2), 'silver')
    m.box((7.1, 9.1, 6.5), (8.9, 10.4, 9.5), 'gem')
    spikes = [((6, 10), (1.5, 13.5)), ((6, 9.5), (2.5, 8)), ((10, 10), (14.5, 13.5)), ((10, 9.5), (13.5, 8))]
    def guard(x, y):
        for a, b in spikes:
            if seg_dist(x, y, a, b) < 0.8 - 0.5 * (math.hypot(x - a[0], y - a[1]) / math.hypot(b[0] - a[0], b[1] - a[1])): return 'frost'
        return None
    extrude(m, guard, 1, 15, 7.5, 14, 8.0, 0.9)
    return m

def diminution():
    m = Model(); A = m.atlas
    A.material('grip', wrap((24, 10, 34), (70, 34, 96), (130, 80, 170)))
    A.material('guard', metal((50, 16, 70), (130, 70, 170), (180, 120, 220), (236, 200, 255)))
    A.material('blade', metal((80, 30, 110), (170, 90, 214), (214, 150, 240), (250, 220, 255), streak=False))
    A.material('gem', gem((80, 10, 60), (255, 110, 220), (255, 230, 250)))
    A.material('crystal', gem((60, 10, 90), (170, 90, 240), (250, 220, 255)))
    def blade(x, y):
        hw = 2.4 - (y - 10.5) * 0.045 if y < 27 else (2.4 - 16.5 * 0.045) * max(0.0, (32 - y) / 5)
        return 'blade' if abs(x - 8) <= hw else None
    sword(m, blade, 'grip', None, 'guard', blade_top=32)
    arms = [((7, 9.8), (2.5, 12.5)), ((9, 9.8), (13.5, 12.5))]
    extrude(m, lambda x, y: 'guard' if (5.5 <= x <= 10.5 and 9 <= y <= 10.5) or near_line(arms[0], x, y, 0.7) or near_line(arms[1], x, y, 0.7) else None,
            1, 15, 9, 13.5, 8.0, 1.6)
    for y0, r in ((13, 0.9), (16.5, 0.75), (19.8, 0.6), (22.8, 0.45), (25.5, 0.35)):   # gems shrinking up the blade
        m.box((8 - r, y0, 7.3 - r * 0.4), (8 + r, y0 + 2 * r, 8.7 + r * 0.4), 'gem')
    m.box((6.8, -4, 6.8), (9.2, -1.5, 9.2), 'crystal', ('y', 45, (8, -2.75, 8)))  # diamond pommel
    return m

def eclipse():
    m = Model(); A = m.atlas
    A.material('grip', wrap((14, 10, 20), (40, 30, 56), (90, 70, 120)))
    A.material('gold', GOLD)
    A.material('light', metal((190, 180, 200), (236, 232, 246), (250, 248, 255), (255, 255, 255), streak=False))
    A.material('dark', metal((6, 4, 12), (26, 20, 40), (46, 38, 70), (90, 80, 130), streak=False))
    A.material('seam', glow((255, 190, 240), (255, 110, 210)))
    A.material('horn', metal((80, 20, 60), (190, 70, 160), (230, 130, 210), (255, 220, 250)))
    A.material('sun', gem((160, 110, 20), (255, 210, 80), (255, 255, 220)))
    A.material('moon', gem((90, 90, 130), (200, 200, 230), (255, 255, 255)))
    def blade(x, y):
        hw = 2.3 if y < 26.5 else 2.3 * max(0.0, (31.5 - y) / 5)
        if abs(x - 8) > hw: return None
        if abs(x - 8) < 0.25: return 'seam'
        return 'light' if x < 8 else 'dark'
    sword(m, blade, 'grip', None, 'gold')
    horns = [(7, 9.5), (4.5, 10), (2.5, 12), (2, 14.5)], [(9, 9.5), (11.5, 10), (13.5, 12), (14, 14.5)]
    extrude(m, lambda x, y: 'horn' if (5.5 <= x <= 10.5 and 9 <= y <= 10.8) or near_line(horns[0], x, y, 0.75) or near_line(horns[1], x, y, 0.75) else None,
            1, 15, 9, 15.5, 8.0, 1.4)
    m.box((6.5, 16, 7.3), (7.6, 17.1, 8.7), 'sun')
    m.box((8.4, 21, 7.3), (9.5, 22.1, 8.7), 'moon')
    m.box((7.1, 9.3, 6.6), (8.9, 10.9, 9.4), 'seam')
    return m

# ---------------------------------------------------------------- axes
def zeus():
    m = Model(); A = m.atlas
    A.material('handle', grain((10, 12, 24), (26, 30, 52), (52, 60, 92)))
    A.material('gold', GOLD)
    A.material('steel', metal((20, 30, 56), (48, 66, 108), (80, 104, 150), (140, 166, 210), streak=False))
    A.material('edge', glow((235, 250, 255), (100, 190, 255)))
    A.material('bolt', glow((255, 250, 200), (240, 180, 40)))
    A.material('sapphire', gem((10, 30, 90), (70, 150, 255), (220, 240, 255)))
    rod(m, -14, 26, 1.0, 'handle')
    for y in range(-10, 14, 3): ring(m, y, 0.6, 1.15, 'gold')
    rod(m, -16, -14, 1.5, 'gold')
    m.box((6.4, 17, 6.4), (9.6, 29, 9.6), 'gold')
    rod(m, 29, 32, 0.6, 'gold')
    m.box((7.0, 21.5, 6.1), (9.0, 23.5, 9.9), 'sapphire')
    head = [(7, 28.5), (3, 29.5), (-1.5, 30.5), (-5.5, 29.5), (-8.5, 26.5), (-10, 22), (-9.5, 17), (-7.5, 13.5), (-5, 12.5), (-4, 15), (-2, 18), (1.5, 18.5), (7, 18)]
    fluke = [(9, 27), (13.5, 29), (12, 24.5), (9, 23)]
    bolt = [(3, 27), (-2, 24), (1, 22.5), (-4.5, 19.5), (-2, 18.5), (-7, 15)]
    def at(x, y):
        if inside(fluke, x, y): return 'steel'
        if not inside(head, x, y): return None
        if near_line(bolt, x, y, 0.55): return 'bolt'
        if x < -3 and near_poly(head[3:10], x, y, 0.9): return 'edge'
        return 'steel'
    extrude(m, at, -11, 14, 12, 31, 8.0, 1.3)
    return m

def titan():
    m = Model(); A = m.atlas
    A.material('handle', grain((18, 12, 8), (46, 30, 20), (84, 58, 40)))
    A.material('bronze', BRONZE)
    A.material('stone', stone((24, 22, 26), (56, 50, 56), (96, 88, 94)))
    A.material('steel', metal((110, 114, 126), (180, 186, 200), (220, 226, 238), (250, 252, 255), streak=False))
    A.material('magma', glow((255, 236, 150), (255, 110, 20)))
    rod(m, -14, 20, 1.3, 'handle')
    for y in (-8, -2, 4, 10): ring(m, y, 1.2, 1.5, 'bronze')
    m.box((6.2, -16, 6.2), (9.8, -14, 9.8), 'bronze', ('y', 45, (8, -15, 8)))
    m.box((5.5, 16, 5.5), (10.5, 30, 10.5), 'bronze')
    m.box((6.5, 30, 6.5), (9.5, 32, 9.5), 'bronze')
    right = [(10, 18), (13, 16), (17, 15.5), (20, 17.5), (21.5, 22), (21.5, 26), (20, 30), (17, 31.5), (13, 30.5), (10, 28)]
    left = [(16 - x, y) for x, y in right]
    cracks = [[(13, 23), (15.5, 21.5), (17, 22.5), (19, 20)], [(15.5, 21.5), (16, 26), (18.5, 28)]]
    cracks += [[(16 - x, y) for x, y in c] for c in cracks]
    def at(x, y):
        for poly in (right, left):
            if inside(poly, x, y):
                if any(near_line(c, x, y, 0.45) for c in cracks): return 'magma'
                far = abs(x - 8) > 10
                return 'steel' if far and near_poly(poly, x, y, 1.1) else 'stone'
        return None
    extrude(m, at, -6, 22, 15, 32, 8.0, 1.8)
    return m

# ---------------------------------------------------------------- trident
def trident():
    m = Model(); A = m.atlas
    A.material('shaft', metal((10, 50, 60), (30, 120, 130), (70, 175, 180), (160, 230, 230), streak=False))
    A.material('coral', wrap((90, 14, 40), (200, 60, 90), (250, 160, 170)))
    A.material('gold', GOLD)
    A.material('prong', metal((20, 90, 100), (70, 190, 200), (140, 235, 235), (230, 255, 252), streak=False))
    A.material('pearl', gem((150, 150, 170), (230, 236, 250), (255, 255, 255)))
    rod(m, -14, 20, 0.8, 'shaft')
    for y in (-8, -2, 4): ring(m, y, 1, 1.05, 'coral')
    rod(m, -16, -14, 1.2, 'gold')
    m.box((1.5, 19.5, 7), (14.5, 21.5, 9), 'gold')
    m.box((6.8, 19.2, 6.4), (9.2, 21.8, 9.6), 'pearl')
    def at(x, y):
        for cx, top in ((8, 32), (2.5, 29.5), (13.5, 29.5)):
            hw = 0.75 if y < top - 2.5 else 0.75 * max(0.0, (top - y) / 2.5)
            if abs(x - cx) <= hw: return 'prong'
            if cx != 8 and top - 6.5 <= y <= top - 5 and 0 < (8 - cx) * (x - cx) / abs(8 - cx) <= 1.8 - (y - (top - 6.5)): return 'prong'
        return None
    extrude(m, at, 1, 15, 21.5, 32, 8.0, 1.2)
    return m

# ---------------------------------------------------------------- tether chain
def tether():
    m = Model(); A = m.atlas
    A.material('link', SILVER)
    A.material('dark', metal((30, 30, 36), (80, 84, 96), (120, 124, 138), (170, 174, 188)))
    A.material('hook', metal((70, 36, 16), (160, 96, 46), (210, 150, 90), (250, 210, 160)))
    y = -12
    for i in range(6):
        mat = 'link' if i % 2 == 0 else 'dark'
        if i % 2 == 0:   # link in the XY plane
            m.box((6, y, 7.5), (10, y + 1, 8.5), mat); m.box((6, y + 4.5, 7.5), (10, y + 5.5, 8.5), mat)
            m.box((6, y, 7.5), (7, y + 5.5, 8.5), mat); m.box((9, y, 7.5), (10, y + 5.5, 8.5), mat)
        else:            # link in the ZY plane, interlocking
            m.box((7.5, y, 6), (8.5, y + 1, 10), mat); m.box((7.5, y + 4.5, 6), (8.5, y + 5.5, 10), mat)
            m.box((7.5, y, 6), (8.5, y + 5.5, 7), mat); m.box((7.5, y, 9), (8.5, y + 5.5, 10), mat)
        y += 4.5
    hook = [(8, 16), (8, 24), (9, 28), (12, 30.5), (15, 29), (15.5, 25.5), (13.5, 23)]
    extrude(m, lambda x, yy: 'hook' if near_line(hook, x, yy, 0.95) else None, 6, 17, 15.5, 32, 8.0, 1.6)
    m.box((12.5, 22, 7.3), (14.5, 24, 8.7), 'hook', ('z', 45, (13.5, 23, 8)))   # barb
    return m

# ---------------------------------------------------------------- Veil of Mimicry (book)
def veil():
    m = Model(); A = m.atlas
    A.material('leather', grain((20, 6, 34), (56, 22, 86), (96, 50, 140)))
    A.material('pages', flat((236, 228, 200), (200, 190, 160)))
    A.material('silver', SILVER)
    A.material('light', flat((240, 230, 250), (210, 200, 225)))
    A.material('dark', flat((34, 18, 50), (20, 8, 30)))
    A.material('eye', flat((200, 140, 255), (160, 100, 220)))
    m.box((2, 0, 6), (14, 16, 7), 'leather'); m.box((2, 0, 9), (14, 16, 10), 'leather')
    m.box((1, 0, 6), (2, 16, 10), 'leather')
    m.box((2.5, 0.5, 7), (13.5, 15.5, 9), 'pages')
    for x0, y0 in ((12.5, 14.5), (12.5, 0), (1.5, 14.5), (1.5, 0)):
        m.box((x0, y0, 5.8), (x0 + 2, y0 + 1.5, 10.2), 'silver')
    m.box((5, 3.5, 5.5), (8, 12.5, 6), 'light'); m.box((8, 3.5, 5.5), (11, 12.5, 6), 'dark')   # the two-faced mask
    m.box((6, 9, 5.3), (7, 10, 5.5), 'dark'); m.box((9, 9, 5.3), (10, 10, 5.5), 'eye')
    m.box((5.8, 5.5, 5.3), (7.6, 6.2, 5.5), 'dark')
    return m

MODELS = {'scythe_of_diablo': scythe, 'luminosity': luminosity, 'ender_glaive': glaive, 'hermes_blade': hermes,
          'chiones_blade': chione, 'blade_of_diminution': diminution, 'sword_of_eclipse': eclipse, 'axe_of_zeus': zeus,
          'titans_axe': titan, 'lost_trident': trident, 'tether_chain': tether, 'veil_of_mimicry': veil}

if __name__ == '__main__':
    import sys
    built = {k: f() for k, f in MODELS.items()}
    for k, mm in built.items(): print(k, len(mm.elements))
    names = list(built)
    sheet([built[n] for n in names[:6]], 'p3d_a.png')
    sheet([built[n] for n in names[6:]], 'p3d_b.png')

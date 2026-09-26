from frame import *

def line_uv(img, a, b, col, steps=24):
    for i in range(steps+1):
        t = i/steps; put_uv(img, a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t, col)

def flared_bit(c, side, u_mid, neck_half, edge_half, reach, bulge, mat):
    """An axe bit: narrow at the haft, flaring out to a convex cutting edge `reach` away."""
    pts = [pt(u_mid - neck_half, side*2), pt(u_mid + neck_half, side*2)]
    for i in range(0, 13):
        t = i / 12.0
        u = u_mid + edge_half - 2*edge_half*t
        v = side*(reach + bulge*math.sin(math.pi*t))
        pts.append(pt(u, v))
    c.poly(pts, mat)
    return pts

def edge_band(img, side, u_mid, edge_half, reach, bulge, cols):
    for i in range(0, 61):
        t = i / 60.0
        u = u_mid + edge_half - 2*edge_half*t
        for k, col in enumerate(cols):
            put_uv(img, u, side*(reach + bulge*math.sin(math.pi*t) - 0.6 - k), col)

# ---------------------------------------------------------------- Axe of Zeus: storm-steel bit with an engraved bolt
def zeus():
    c = Canvas(N)
    fill_profile(c, 2, 72, lambda u: 1.8, 'handle')
    flared_bit(c, -1, 61, 5, 10, 15, 4, 'bit')
    c.poly([pt(56,1.5), pt(66,1.5), pt(62,8)], 'bit')                     # back spike
    fill_profile(c, 53, 70, lambda u: 2.4, 'collar')
    for u0 in (10, 16, 22):
        fill_profile(c, u0, u0+2, lambda u: 2.4, 'wrap')
    c.disc(*pt(2.5, 0), 2.8, 'wrap')
    ramps = {'handle': [(20,26,40),(44,56,80),(66,82,112),(96,114,148),(130,150,184)],
             'bit': [(20,50,80),(70,140,190),(130,200,240),(190,236,255),(245,252,255)],
             'collar': SILVER, 'wrap': GOLD}
    img = render_bevel(c, ramps)
    edge_band(img, -1, 61, 10, 15, 4, [(245,252,255), (200,240,255)])
    bolt = [(67,-4),(61,-8.5),(65,-9.5),(58,-15)]                          # zigzag lightning
    for (p0, p1) in zip(bolt, bolt[1:]):
        line_uv(img, p0, p1, (255, 236, 90)); line_uv(img, (p0[0]+0.7, p0[1]), (p1[0]+0.7, p1[1]), (200, 150, 20))
    gem(img, 62, 0, 1.8, (20,60,110), (110,220,255), (255,255,255))
    return img

# ---------------------------------------------------------------- Titan's Axe: huge double-bit stone greataxe
def titan():
    c = Canvas(N)
    fill_profile(c, 2, 70, lambda u: 2.4, 'handle')
    for s in (-1, 1):
        flared_bit(c, s, 61, 6, 12, 17, 3.5, 'stone')
    fill_profile(c, 56, 68, lambda u: 3.2, 'band')
    c.poly([pt(70,-2.5), pt(79,0), pt(70,2.5)], 'band')                   # top spike
    for u0 in (8, 15, 22):
        fill_profile(c, u0, u0+2.2, lambda u: 3.0, 'band')
    c.disc(*pt(2.5, 0), 3.2, 'band')
    ramps = {'handle': [(22,14,8),(56,36,20),(86,58,34),(120,86,54),(156,120,82)],
             'stone': [(26,26,30),(70,70,78),(110,110,120),(150,150,160),(196,196,206)],
             'band': [(52,30,10),(126,80,30),(176,120,52),(214,164,90),(240,206,140)]}
    img = render_bevel(c, ramps)
    for s in (-1, 1):
        edge_band(img, s, 61, 12, 17, 3.5, [(236,240,250), (180,186,200), (140,144,156)])
        for u, v in ((56, s*6), (66, s*6)):                              # rivets
            put_uv(img, u, v, (240, 206, 140)); put_uv(img, u+0.7, v+0.7, (80, 50, 20))
        crack = [(59, s*8), (62, s*11), (60, s*13), (63, s*16)]            # molten crack
        for (p0, p1) in zip(crack, crack[1:]):
            line_uv(img, p0, p1, (255, 150, 40), steps=8)
    return img

# ---------------------------------------------------------------- Lost Trident of Atlantis
def trident():
    c = Canvas(N)
    fill_profile(c, 2, 64, lambda u: 1.6, 'shaft')
    fill_profile(c, 58, 62, lambda u: 8.5, 'gold')                        # crossbar
    fill_profile(c, 60, 82, lambda u: 1.6 if u < 76 else 1.6*max(0,(82-u)/6), 'prong')
    for s in (-1, 1):
        for y in range(N):
            for x in range(N):
                u, v = uv(x+0.5, y+0.5)
                if 60 <= u <= 76 and abs(v - s*7.0) <= (1.5 if u < 71 else 1.5*max(0,(76-u)/5)): c.set(x, y, 'prong')
        c.poly([pt(66, s*5.5), pt(70, s*3.4), pt(68, s*5.5)], 'prong')       # barbs
        c.poly([pt(60, s*8.4), pt(64, s*10.5), pt(62, s*8.4)], 'gold')
    for u0 in (12, 20, 28):
        fill_profile(c, u0, u0+1.6, lambda u: 2.2, 'coral')
    c.disc(*pt(2.5, 0), 2.6, 'gold')
    ramps = {'shaft': [(8,40,50),(20,90,100),(36,130,140),(70,175,180),(130,220,220)],
             'prong': [(10,50,60),(40,150,160),(90,210,210),(160,245,240),(230,255,252)],
             'gold': GOLD, 'coral': [(60,10,30),(180,50,80),(230,100,120),(250,160,170),(255,210,215)]}
    img = render_bevel(c, ramps)
    gem(img, 60, 0, 2.2, (40,40,60), (230,236,250), (255,255,255))        # pearl
    return img

imgs = [zeus(), titan(), trident()]
for n, im in zip(['hd_zeus', 'hd_titan', 'hd_trident'], imgs): im.save(n + '.png')
preview(imgs, 'p_axes.png', scale=5)

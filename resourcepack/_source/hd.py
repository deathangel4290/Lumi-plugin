import math
from kit import *
from PIL import Image
N = 64

def put(img, x, y, c, a=255):
    if 0 <= x < N and 0 <= y < N: img.putpixel((int(x), int(y)), tuple(c) + (a,))

# ------------------------------------------------------------------ Scythe of Diablo
def scythe():
    c = Canvas(N)
    c.line(8, 61, 45, 10, 'haft', w=4)
    OC, OR = (39.0, 36.0), 29.0
    IC, IR = (32.5, 43.0), 25.5
    ino = lambda px, py: (px-OC[0])**2 + (py-OC[1])**2 <= OR**2
    ini = lambda px, py: (px-IC[0])**2 + (py-IC[1])**2 <= IR**2
    for y in range(N):
        for x in range(N):
            px, py = x+0.5, y+0.5
            if ino(px, py) and not ini(px, py) and py < 28 and px < 47:
                c.set(x, y, 'blade')
    c.poly([(44,9),(56,4),(58,6),(47,15)], 'blade')          # back spike
    c.poly([(40,11),(47,7),(51,12),(44,17)], 'gold')          # collar plate
    for t in (0.47, 0.53, 0.59, 0.65):                        # leather wraps
        x = 8 + 37*t; y = 61 - 51*t
        c.line(x-2, y-1, x+2, y+2, 'wrap', w=2)
    c.disc(9, 60.5, 3.2, 'gold')                              # pommel
    ramps = {
      'haft':  [(12,6,10),(30,17,25),(50,30,40),(78,48,60),(108,72,84)],
      'blade': [(30,2,6),(88,8,18),(142,20,32),(196,44,54),(250,130,120)],
      'gold':  [(58,28,4),(146,92,18),(204,150,40),(244,204,90),(255,242,176)],
      'wrap':  [(24,4,6),(76,10,16),(122,18,26),(168,36,44),(210,80,80)],
    }
    img = render_bevel(c, ramps, bevel=2)
    for y in range(N):
        for x in range(N):
            if c.get(x, y) != 'blade': continue
            px, py = x+0.5, y+0.5
            if ini(px, py+1.5) or ini(px+1.2, py):            # honed edge, two tones
                put(img, x, y, (255, 214, 200))
            elif ini(px, py+3.2):
                put(img, x, y, (232, 110, 104))
            elif not ino(px, py-2.0):                          # dark spine
                put(img, x, y, (80, 8, 18))
    # engraved runes along the blade
    for i, (x, y) in enumerate([(20,15),(25,12),(30,10),(35,9)]):
        for dx, dy in ((0,0),(1,0),(0,1),(1,1)) if i % 2 else ((0,0),(1,1),(0,1)):
            put(img, x+dx, y+dy, (255, 60, 50))
    # ruby in the collar
    for dx, dy, col in ((0,0,(255,90,90)),(1,0,(200,20,30)),(0,1,(200,20,30)),(1,1,(120,6,14)),(-1,0,(255,170,170))):
        put(img, 45+dx, 12+dy, col)
    return img

# ------------------------------------------------------------------ Ender Glaive
def glaive():
    c = Canvas(N)
    c.line(7, 61, 38, 26, 'shaft', w=3)
    blade = [(36,27),(38,20),(41,14),(46,8),(52,3),(58,1),(60,3),(59,8),(56,15),(52,21),(47,26),(42,29),(39,30)]
    c.poly(blade, 'blade')
    c.poly([(37,25),(30,18),(32,17),(40,22)], 'blade')        # back hook
    c.poly([(33,28),(38,23),(44,28),(39,33)], 'collar')
    c.poly([(7,61),(3,63),(4,59),(9,57)], 'collar')          # butt spike
    for t in (0.2, 0.3):                                      # shaft rings
        x = 7 + 31*t; y = 61 - 35*t
        c.line(x-1.5, y-1.5, x+1.5, y+1.5, 'collar', w=2)
    ramps = {
      'shaft':  [(10,5,18),(26,14,40),(44,24,62),(70,42,92),(98,64,124)],
      'blade':  [(26,6,46),(80,32,146),(126,62,206),(172,110,244),(228,190,255)],
      'collar': [(18,9,28),(56,38,76),(92,68,116),(136,106,160),(178,148,198)],
    }
    img = render_bevel(c, ramps, bevel=2)
    # fuller (groove) down the middle of the blade
    for t in [i/40 for i in range(41)]:
        x = 41 + (56-41)*t; y = 25 + (5-25)*t
        put(img, x, y, (90, 40, 160)); put(img, x+1, y, (150, 100, 230))
    # glinting edge on the leading side
    for y in range(N):
        for x in range(N):
            if c.get(x, y) == 'blade' and c.get(x+1, y) is None and y < 26:
                put(img, x, y, (240, 214, 255))
    # ender eye gem with pupil
    cx, cy = 38.5, 28
    for y in range(N):
        for x in range(N):
            d = math.hypot(x+0.5-cx, y+0.5-cy)
            if d <= 3.2: put(img, x, y, (30, 150, 90) if d > 2.2 else (70, 220, 140))
    for dx, dy, col in ((0,0,(8,40,26)),(0,-1,(8,40,26)),(0,1,(8,40,26)),(-1,-2,(220,255,235))):
        put(img, 38+dx, 28+dy, col)
    return img

# ------------------------------------------------------------------ Luminosity (warhammer)
def luminosity(pulse):
    c = Canvas(N)
    d = (0.6, -0.8)            # handle direction, bottom-left -> top-right
    p = (0.8, 0.6)             # across the head
    hx, hy = 36.5, 22.5        # head centre
    c.line(10, 59, hx, hy, 'grip', w=4)
    def quad(cx, cy, a, b):     # rotated rectangle: a along p, b along d
        return [(cx + p[0]*sa*a + d[0]*sb*b, cy + p[1]*sa*a + d[1]*sb*b) for sa, sb in ((-1,-1),(1,-1),(1,1),(-1,1))]
    c.poly(quad(hx, hy, 17, 10), 'head')                      # the block head
    for s in (-1, 1):                                          # thicker rims at both faces
        c.poly(quad(hx + p[0]*s*15, hy + p[1]*s*15, 3.5, 11.5), 'rim')
    for s in (-1, 1):                                          # flanged corners
        for t in (-1, 1):
            cx = hx + p[0]*s*15 + d[0]*t*11; cy = hy + p[1]*s*15 + d[1]*t*11
            c.poly([(cx + d[0]*t*4 + p[0]*s*2, cy + d[1]*t*4 + p[1]*s*2), (cx + p[0]*3, cy + p[1]*3), (cx - p[0]*3, cy - p[1]*3)], 'rim')
    tip = (hx + d[0]*12, hy + d[1]*12)                         # crown spike on top
    c.poly([(tip[0] + d[0]*7, tip[1] + d[1]*7), (tip[0] + p[0]*4, tip[1] + p[1]*4), (tip[0] - p[0]*4, tip[1] - p[1]*4)], 'rim')
    for t in (0.18, 0.32, 0.46):                               # grip bands
        x = 10 + (hx-10)*t; y = 59 + (hy-59)*t
        c.line(x - p[0]*2.5, y - p[1]*2.5, x + p[0]*2.5, y + p[1]*2.5, 'band', w=2)
    c.disc(10, 59, 3.4, 'band')                                # pommel
    ramps = {
      'grip': [(40,22,8),(92,58,20),(134,92,38),(178,134,66),(214,176,104)],
      'band': [(58,28,4),(146,92,18),(204,150,40),(244,204,90),(255,242,176)],
      'head': [(76,44,6),(170,112,20),(222,166,42),(248,208,96),(255,240,180)],
      'rim':  [(60,34,4),(140,90,16),(196,140,32),(236,190,80),(255,232,160)],
    }
    img = render_bevel(c, ramps, bevel=2)
    # sun gem set in the head, with rays engraved around it; the core pulses between frames
    gx, gy = hx, hy
    for y in range(N):
        for x in range(N):
            dx, dy = x+0.5-gx, y+0.5-gy; r = math.hypot(dx, dy)
            if r <= 5.6:
                if r > 4.6: col = (150, 96, 18)
                else:
                    t = 1.0 - r/4.6
                    col = lerp((255, 196, 70), (255, 255, 235), min(1.0, t*(0.7+0.5*pulse)+0.15*pulse))
                put(img, x, y, col)
            elif r <= 9.0:
                a = math.degrees(math.atan2(dy, dx)) % 45
                if a < 6 or a > 39: put(img, x, y, lerp((200, 140, 30), (255, 236, 150), 0.4 + 0.6*pulse))
    put(img, gx-2, gy-2, (255, 255, 255))
    return img

scythe().save('hd_scythe.png')
glaive().save('hd_glaive.png')
frames = [luminosity(v) for v in (0.0, 0.3, 0.65, 1.0, 0.65, 0.3)]
frames[3].save('hd_luminosity.png')
strip = Image.new('RGBA', (N, N*len(frames)))
for i, f in enumerate(frames): strip.paste(f, (0, N*i))
strip.save('hd_luminosity_anim.png')
preview([Image.open(f) for f in ['hd_scythe.png', 'hd_glaive.png', 'hd_luminosity.png']], 'p_hd.png', scale=5)

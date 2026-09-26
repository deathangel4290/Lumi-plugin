import math
from kit import *
from PIL import Image
N = 64
S2 = math.sqrt(0.5)
D = (S2, -S2)     # along the weapon, bottom-left -> top-right
P = (S2, S2)      # across the weapon (toward bottom-right)
O = (5.0, 59.0)   # pommel end

def pt(u, v):
    return (O[0] + u*D[0] + v*P[0], O[1] + u*D[1] + v*P[1])

def uv(x, y):
    dx, dy = x - O[0], y - O[1]
    return (dx*D[0] + dy*D[1], dx*P[0] + dy*P[1])

def fill_profile(c, u0, u1, halfwidth, m, off=0.0):
    """Fill the region u0..u1 whose half-width at u is halfwidth(u) (can be asymmetric via off)."""
    for y in range(N):
        for x in range(N):
            u, v = uv(x+0.5, y+0.5)
            if u0 <= u <= u1:
                w = halfwidth(u)
                if w > 0 and abs(v - off) <= w: c.set(x, y, m)

def put(img, x, y, col, a=255):
    x, y = int(round(x)), int(round(y))
    if 0 <= x < N and 0 <= y < N: img.putpixel((x, y), tuple(col) + (a,))

def put_uv(img, u, v, col):
    x, y = pt(u, v); put(img, x, y, col)

def gem(img, u, v, r, rim, body, shine):
    cx, cy = pt(u, v)
    for y in range(N):
        for x in range(N):
            d = math.hypot(x+0.5-cx, y+0.5-cy)
            if d <= r: put(img, x, y, rim if d > r-1.0 else body)
    put(img, cx-r*0.4, cy-r*0.4, shine)

def taper(base, tipU, bladeEnd, tipLen):
    return lambda u: base if u < bladeEnd - tipLen else base * max(0.0, (bladeEnd - u) / tipLen)

GOLD = [(58,28,4),(146,92,18),(204,150,40),(244,204,90),(255,242,176)]
SILVER = [(34,36,46),(96,100,116),(150,156,172),(204,210,224),(244,248,255)]

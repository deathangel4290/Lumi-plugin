"""Tiny pixel-art kit: paint material ids on a grid, then shade + outline automatically."""
import math
from PIL import Image

class Canvas:
    def __init__(self, n=32):
        self.n = n
        self.g = [[None]*n for _ in range(n)]
    def set(self, x, y, m):
        x, y = int(round(x)), int(round(y))
        if 0 <= x < self.n and 0 <= y < self.n:
            self.g[y][x] = m
    def get(self, x, y):
        return self.g[y][x] if 0 <= x < self.n and 0 <= y < self.n else None
    def line(self, x0, y0, x1, y1, m, w=1):
        steps = int(max(abs(x1-x0), abs(y1-y0))*2)+1
        for i in range(steps+1):
            t = i/steps; x = x0+(x1-x0)*t; y = y0+(y1-y0)*t
            r = (w-1)/2
            for dx in range(-int(math.ceil(r)), int(math.ceil(r))+1):
                for dy in range(-int(math.ceil(r)), int(math.ceil(r))+1):
                    if dx*dx+dy*dy <= r*r+0.3: self.set(x+dx, y+dy, m)
    def poly(self, pts, m):
        ys = [p[1] for p in pts]
        for y in range(int(min(ys)), int(max(ys))+1):
            yc = y+0.5; xs = []
            for i in range(len(pts)):
                (ax, ay), (bx, by) = pts[i], pts[(i+1) % len(pts)]
                if (ay <= yc < by) or (by <= yc < ay):
                    xs.append(ax+(yc-ay)*(bx-ax)/(by-ay))
            xs.sort()
            for a, b in zip(xs[::2], xs[1::2]):
                for x in range(int(math.floor(a+0.5)), int(math.floor(b-0.5))+1):
                    self.set(x, y, m)
    def disc(self, cx, cy, r, m):
        for y in range(self.n):
            for x in range(self.n):
                if (x+0.5-cx)**2+(y+0.5-cy)**2 <= r*r: self.set(x, y, m)
    def arc_band(self, cx, cy, r0, r1, a0, a1, m):
        for y in range(self.n):
            for x in range(self.n):
                dx, dy = x+0.5-cx, y+0.5-cy
                d = math.hypot(dx, dy); a = math.degrees(math.atan2(dy, dx)) % 360
                inside = (a0 <= a <= a1) if a0 <= a1 else (a >= a0 or a <= a1)
                if r0 <= d <= r1 and inside: self.set(x, y, m)

    def render(self, ramps, glow=None, outline=True):
        """ramps: id -> [outline, shadow, base, light, highlight]. Light comes from the top-left."""
        n = self.n; img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
        for y in range(n):
            for x in range(n):
                m = self.g[y][x]
                if m is None: continue
                r = ramps[m]
                tl = self.get(x-1, y) != m or self.get(x, y-1) != m
                br = self.get(x+1, y) != m or self.get(x, y+1) != m
                tl2 = self.get(x-1, y-1) != m
                if tl and not br: c = r[3]
                elif br and not tl: c = r[1]
                elif tl and br: c = r[2]
                else: c = r[2] if not tl2 else r[3]
                img.putpixel((x, y), c+(255,))
        if outline:
            for y in range(n):
                for x in range(n):
                    if self.g[y][x] is None:
                        nb = [self.get(x+dx, y+dy) for dx, dy in ((1,0),(-1,0),(0,1),(0,-1))]
                        nb = [b for b in nb if b is not None]
                        if nb: img.putpixel((x, y), ramps[nb[0]][0]+(255,))
        return img

def hi(img, pts, c):
    for x, y in pts: img.putpixel((x, y), c+(255,))

def preview(imgs, path, scale=8, bg=(139,139,139)):
    w = sum(i.width*scale+12 for i in imgs)+12; h = max(i.height for i in imgs)*scale+24
    sheet = Image.new('RGBA', (w, h), bg+(255,)); x = 12
    for i in imgs:
        big = i.resize((i.width*scale, i.height*scale), Image.NEAREST); sheet.paste(big, (x, 12), big); x += big.width+12
    sheet.save(path)

def lerp(a, b, t):
    return tuple(int(round(a[i]+(b[i]-a[i])*t)) for i in range(3))

def render_bevel(c, ramps, bevel=2, outline=True):
    """Bevelled shading for larger sprites. ramp = [outline, shadow, base, light, highlight].
    Pixels whose top-left neighbour (within `bevel`) is outside the region are lit; bottom-right are shaded;
    the flat middle gets a gentle diagonal gradient so large areas are not a single colour."""
    n = c.n; img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    for y in range(n):
        for x in range(n):
            m = c.g[y][x]
            if m is None: continue
            r = ramps[m]
            lit = next((k for k in range(1, bevel+1) if c.get(x-k, y-k) != m or c.get(x-k, y) != m and c.get(x, y-k) != m), None)
            dark = next((k for k in range(1, bevel+1) if c.get(x+k, y+k) != m or c.get(x+k, y) != m and c.get(x, y+k) != m), None)
            if lit == 1 and not dark: col = r[4]
            elif lit and not dark: col = r[3]
            elif dark == 1 and not lit: col = r[0] if False else lerp(r[1], r[0], 0.25)
            elif dark and not lit: col = r[1]
            else:
                t = ((x - y) / (2.0 * n)) + 0.5          # upper-right slightly lighter
                col = lerp(r[2], r[3], max(0.0, min(1.0, t - 0.25)) * 0.6)
            img.putpixel((x, y), col + (255,))
    if outline:
        for y in range(n):
            for x in range(n):
                if c.g[y][x] is None:
                    nb = [c.get(x+dx, y+dy) for dx, dy in ((1,0),(-1,0),(0,1),(0,-1))]
                    nb = [b for b in nb if b is not None]
                    if nb: img.putpixel((x, y), ramps[nb[0]][0] + (255,))
    return img

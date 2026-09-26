import random
from frame import *
from PIL import Image

def line_uv(img, a, b, col, steps=30):
    for i in range(steps+1):
        t = i/steps; put_uv(img, a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t, col)

EDGE = [(66,-9),(65,-14),(61,-18.5),(55,-21.5),(49,-22.5),(43,-21),(39,-17.5),(37,-13)]
def edge_curve(t):
    """Cutting edge from the top horn (t=0) down to the beard tip (t=1), linear between key points."""
    f = t*(len(EDGE)-1); i = min(int(f), len(EDGE)-2); k = f - i
    return (EDGE[i][0]+(EDGE[i+1][0]-EDGE[i][0])*k, EDGE[i][1]+(EDGE[i+1][1]-EDGE[i][1])*k)

def zeus(flash):
    c = Canvas(N)
    fill_profile(c, 2, 66, lambda u: 1.9, 'handle')
    head = [pt(49, -2.2), pt(62, -2.2), pt(65, -5)]
    head += [pt(*edge_curve(i/28)) for i in range(29)]
    head += [pt(39, -12.5), pt(44, -10.5), pt(47.5, -6), pt(49, -3)]       # inner curve of the beard back to the haft
    c.poly(head, 'head')
    c.poly([pt(52, 2), pt(60, 2), pt(63, 8), pt(55, 5.5)], 'head')     # back fluke
    fill_profile(c, 49, 61, lambda u: 2.6, 'collar')
    c.poly([pt(61, -2.2), pt(69, 0), pt(61, 2.2)], 'collar')          # top spike
    c.disc(*pt(2.5, 0), 3.0, 'collar')
    ramps = {'handle': [(10,12,24),(26,30,52),(42,48,78),(66,74,108),(96,104,140)],
             'head': [(10,16,34),(34,50,86),(58,80,124),(90,116,162),(140,166,210)],
             'collar': GOLD}
    img = render_bevel(c, ramps)
    for k in range(0, 120):                               # gold spiral wrap down the handle
        u = 6 + k*0.4
        if u > 44: break
        v = 1.6*math.sin(u*0.9)
        put_uv(img, u, v, (236, 190, 70) if math.cos(u*0.9) > 0 else (150, 96, 20))
    # electric edge: bright band along the whole cutting curve, stronger when flashing
    edge = lerp((90, 190, 255), (235, 250, 255), flash)
    for i in range(0, 81):
        u, v = edge_curve(i/80)
        put_uv(img, u, v + 0.7, edge)
        put_uv(img, u, v + 1.6, lerp((40, 110, 200), (140, 210, 255), flash))
    # the inlaid lightning bolt across the head
    bolt = [(57, -4), (52, -10), (57, -11.5), (50, -17), (54, -18), (48, -22)]
    for a, b in zip(bolt, bolt[1:]):
        line_uv(img, a, b, lerp((240, 180, 40), (255, 252, 210), flash))
        line_uv(img, (a[0] + 0.8, a[1]), (b[0] + 0.8, b[1]), lerp((255, 210, 80), (255, 255, 240), flash))
        line_uv(img, (a[0] - 0.8, a[1]), (b[0] - 0.8, b[1]), (130, 84, 10))
    # sparks jumping off the edge (only on bright frames)
    rng = random.Random(int(flash * 10))
    for _ in range(int(3 + 6*flash)):
        u, v = edge_curve(rng.random())
        put_uv(img, u + rng.uniform(-1, 2), v - rng.uniform(1.5, 4), (200, 240, 255))
    gem(img, 55, 0, 2.0, (20,40,110), lerp((70,150,255), (200,240,255), flash), (255,255,255))
    return img

frames = [zeus(f) for f in (0.0, 0.0, 0.15, 1.0, 0.35, 0.0, 0.1, 0.0)]
frames[3].save('hd_zeus2.png')
strip = Image.new('RGBA', (N, N*len(frames)))
for i, f in enumerate(frames): strip.paste(f, (0, N*i))
strip.save('hd_zeus2_anim.png')
preview([Image.open('hd_zeus.png'), frames[0], frames[3]], 'p_zeus.png', scale=5)

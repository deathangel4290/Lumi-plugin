from frame import *

def line_uv(img, a, b, col, steps=30):
    for i in range(steps+1):
        t = i/steps; put_uv(img, a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t, col)

# ---------------------------------------------------------------- Hellstorm: infernal crossbow, 6 states
def hellstorm(string_u, load=None):
    c = Canvas(N)
    fill_profile(c, 4, 60, lambda u: 2.4 if u > 12 else 3.2, 'stock')
    limb_u = 55
    for s in (-1, 1):   # recurved limbs
        pts = []
        for i in range(0, 13):
            t = i/12.0
            pts.append(pt(limb_u - 6*t*t + 2*math.sin(math.pi*t), s*(2 + 22*t)))
        for i in range(12, -1, -1):
            t = i/12.0
            w = 2.6*(1-t) + 1.0
            pts.append(pt(limb_u - 6*t*t + 2*math.sin(math.pi*t) - w, s*(2 + 22*t)))
        c.poly(pts, 'limb')
    fill_profile(c, 50, 60, lambda u: 3.4, 'iron')      # prod mount
    fill_profile(c, 22, 30, lambda u: 3.0, 'iron')      # trigger housing
    c.poly([pt(24,3), pt(27,3), pt(25,7)], 'iron')      # trigger
    if load == 'arrow':
        fill_profile(c, string_u, 72, lambda u: 0.9 if u < 66 else 0, 'bolt')
        c.poly([pt(66,-2.4), pt(73,0), pt(66,2.4)], 'fire')
    elif load == 'firework':
        fill_profile(c, string_u, 68, lambda u: 2.0, 'rocket')
        c.poly([pt(68,-2.2), pt(73,0), pt(68,2.2)], 'rocketcap')
    ramps = {'stock': [(14,8,8),(40,22,18),(66,38,30),(98,60,44),(130,86,62)],
             'limb': [(40,6,0),(120,30,8),(190,70,16),(240,130,40),(255,200,110)],
             'iron': [(18,18,22),(50,50,58),(80,80,92),(120,120,134),(170,170,186)],
             'bolt': [(40,26,14),(100,70,40),(150,110,70),(190,150,100),(230,200,150)],
             'fire': [(90,20,0),(220,70,10),(255,140,30),(255,200,80),(255,245,190)],
             'rocket': [(70,6,6),(160,20,20),(210,40,40),(240,90,80),(255,160,150)],
             'rocketcap': [(40,40,40),(140,140,140),(200,200,200),(230,230,230),(255,255,255)]}
    img = render_bevel(c, ramps)
    tip = lambda s: (limb_u - 6 + 2*math.sin(math.pi) - 1.2, s*23.5)
    for s in (-1, 1):   # the string, from each limb tip to the nock
        line_uv(img, tip(s), (string_u, 0), (255, 220, 170))
        for i in range(4, 20, 4):   # lava veins in the limbs
            t = i/24.0; put_uv(img, limb_u - 6*t*t + 2*math.sin(math.pi*t) - 1.3, s*(2 + 22*t), (255, 230, 120))
    for u in range(34, 50, 3):
        put_uv(img, u, -1.2, (255, 120, 30))   # ember inlay on the stock
    gem(img, 55, 0, 2.0, (80,10,0), (255,90,20), (255,230,160))
    return img

# ---------------------------------------------------------------- Veil of Mimicry: leather tome with a two-faced mask
def veil():
    c = Canvas(N)
    c.poly([(10,14),(46,6),(56,48),(20,58)], 'cover')
    c.poly([(20,58),(56,48),(57,52),(21,62)], 'pages')
    c.poly([(8,15),(10,14),(20,58),(18,60)], 'spine')
    for (x, y) in ((12,16),(44,9),(52,46),(21,55)):     # silver corners
        c.disc(x, y, 3.0, 'metal')
    ramps = {'cover': [(20,6,34),(56,22,86),(84,40,120),(118,66,160),(160,110,200)],
             'pages': [(90,80,60),(200,190,160),(232,224,196),(248,242,220),(255,255,245)],
             'spine': [(14,4,24),(40,14,60),(60,26,86),(90,46,120),(130,80,160)],
             'metal': SILVER}
    img = render_bevel(c, ramps)
    # the mask: a face split down the middle, one half light and smiling, one half dark and blank
    cx, cy = 33, 31
    for y in range(N):
        for x in range(N):
            dx, dy = (x+0.5-cx)/9.0, (y+0.5-cy)/11.0
            if dx*dx + dy*dy <= 1.0:
                put(img, x, y, (236, 226, 246) if x < cx else (34, 18, 50))
    for (x, y) in ((29,28),(30,28),(36,28),(37,28)):
        put(img, x, y, (40, 10, 60) if x < cx else (220, 150, 255))
    for i in range(6):   # smile on the light half, flat line on the dark half
        put(img, 27 + i, 35 + (1 if i in (1, 4) else 0) + (1 if 1 < i < 4 else 0), (80, 30, 100))
        put(img, 34 + i, 36, (160, 100, 200))
    for i in range(40):   # stitched border
        t = i/40.0
        if i % 2 == 0: put(img, 13 + 36*t*0.9, 17 - 8*t*0.9, (180, 150, 210))
    return img

# ---------------------------------------------------------------- Tether Chain: forged links and a grappling hook
def tether():
    c = Canvas(N)
    for k in range(6):
        u = 8 + k*9.5
        if k % 2 == 0:   # face-on link: an oval ring
            for y in range(N):
                for x in range(N):
                    lu, lv = uv(x+0.5, y+0.5)
                    e = ((lu-u)/6.2)**2 + (lv/4.6)**2
                    if 0.3 <= e <= 1.0: c.set(x, y, 'link')
        else:            # edge-on link: a flat bar
            fill_profile(c, u-6, u+6, lambda uu: 1.6, 'link2')
    c.poly([pt(57,-1.8), pt(63,-1.8), pt(63,1.8), pt(57,1.8)], 'hook')
    for i in range(18):   # the hook: a thick curl back over the top
        t = i/17.0; a = math.pi*1.25*t
        cx, cy = pt(67 + 6*math.sin(a), -6*(1-math.cos(a)))
        c.disc(cx, cy, 2.4 if t < 0.85 else 2.4*(1-(t-0.85)/0.15)+0.5, 'hook')
    ramps = {'link': [(24,24,30),(80,84,96),(130,134,148),(186,190,204),(236,240,250)],
             'link2': [(24,24,30),(70,72,84),(110,112,126),(160,164,178),(210,214,226)],
             'hook': [(30,20,14),(110,60,30),(170,100,50),(220,150,90),(250,210,160)]}
    img = render_bevel(c, ramps)
    return img

states = {'hellstorm__crossbow': hellstorm(52), 'hellstorm__crossbow_pulling_0': hellstorm(48),
          'hellstorm__crossbow_pulling_1': hellstorm(43), 'hellstorm__crossbow_pulling_2': hellstorm(37),
          'hellstorm__crossbow_arrow': hellstorm(37, 'arrow'), 'hellstorm__crossbow_firework': hellstorm(37, 'firework')}
for n, im in states.items(): im.save('hd_' + n + '.png')
veil().save('hd_veil.png'); tether().save('hd_tether.png')
preview(list(states.values()) + [veil(), tether()], 'p_misc.png', scale=3)

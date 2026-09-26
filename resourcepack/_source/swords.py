from frame import *

def base_hilt(c, grip='grip', guard='guard', pommel='pommel', guard_w=9.0, guard_u=(19, 22.5)):
    c.disc(*pt(3.5, 0), 3.2, pommel)
    fill_profile(c, 5, guard_u[0], lambda u: 1.7, grip)
    fill_profile(c, guard_u[0], guard_u[1], lambda u: guard_w, guard)

# ---------------------------------------------------------------- Hermes's Blade: winged guard, silver-white blade
def hermes():
    c = Canvas(N)
    fill_profile(c, 22, 80, taper(3.6, 80, 80, 12), 'blade')
    base_hilt(c, guard_w=3.0)
    for s in (-1, 1):   # wings sweeping back toward the grip
        c.poly([pt(23, s*2), pt(26, s*12), pt(21, s*15), pt(17, s*11), pt(19, s*3)], 'wing')
    ramps = {'blade': SILVER, 'grip': [(20,40,70),(40,70,120),(60,100,170),(90,140,210),(150,190,240)],
             'guard': GOLD, 'pommel': GOLD, 'wing': [(90,90,100),(190,190,200),(232,232,240),(250,250,255),(255,255,255)]}
    img = render_bevel(c, ramps)
    for s in (-1, 1):   # feather lines
        for k in range(3):
            a, b = pt(20 + k*1.5, s*(5 + k*2.5)), pt(18 + k*1.2, s*(10 + k*1.3))
            for t in range(6):
                put(img, a[0]+(b[0]-a[0])*t/5, a[1]+(b[1]-a[1])*t/5, (150,150,165))
    for u in range(26, 70, 1):   # fuller
        put_uv(img, u, 0, (170, 176, 196)); put_uv(img, u, 0.8, (232, 238, 250))
    gem(img, 20.8, 0, 1.8, (20,40,90), (90,170,255), (230,245,255))
    return img

# ---------------------------------------------------------------- Chione's Blade: ice crystal blade, icicle guard
def chione():
    c = Canvas(N)
    def ice(u):
        base = 3.4 + 1.6*((u*0.28) % 1.0)       # serrated crystal: each tooth ramps out then snaps back
        return base if u < 68 else base * max(0.0, (80 - u) / 12)
    fill_profile(c, 22, 80, ice, 'blade')
    base_hilt(c, guard_w=3.2)
    for s in (-1, 1):   # icicle guard points
        c.poly([pt(19, s*2), pt(22, s*2), pt(25, s*11), pt(20.5, s*6)], 'guard2')
        c.poly([pt(19, s*3), pt(20.5, s*4), pt(16, s*9)], 'guard2')
    ice_r = [(20,50,90),(70,140,200),(130,200,240),(190,236,255),(245,252,255)]
    ramps = {'blade': ice_r, 'grip': [(14,24,44),(34,54,90),(52,80,126),(80,112,160),(120,150,196)],
             'guard': SILVER, 'guard2': [(30,70,110),(110,180,225),(170,225,250),(215,245,255),(255,255,255)], 'pommel': SILVER}
    img = render_bevel(c, ramps)
    for u in range(26, 72, 3):   # frost facets inside the blade
        put_uv(img, u, -1.2, (225, 248, 255)); put_uv(img, u+1, 1.0, (100, 175, 225))
    for u, v in ((34,0.5),(48,-1),(60,0.3)):
        put_uv(img, u, v, (255, 255, 255))
    gem(img, 20.8, 0, 2.0, (20,60,110), (120,210,255), (255,255,255))
    return img

# ---------------------------------------------------------------- Blade of Diminution: nested shrinking diamonds
def diminution():
    c = Canvas(N)
    fill_profile(c, 22, 80, lambda u: max(0.0, 4.8 - (u-22)*0.045) if u < 70 else max(0.0, (80-u)*0.18), 'blade')
    base_hilt(c, guard_w=2.4, guard_u=(19, 22))
    for s in (-1, 1):   # swept guard arms angled toward the blade
        c.poly([pt(19, s*2), pt(22, s*2), pt(26, s*8.5), pt(24, s*9.5)], 'guard')
    c.poly([pt(0, 0), pt(3.5, -3), pt(7, 0), pt(3.5, 3)], 'crystal')   # amethyst pommel
    ramps = {'blade': [(40,10,60),(120,40,160),(180,90,220),(222,150,245),(250,215,255)],
             'grip': [(24,10,34),(54,26,72),(80,44,104),(116,72,140),(150,110,180)],
             'guard': [(40,14,54),(110,50,140),(160,90,190),(205,140,230),(240,200,255)],
             'pommel': GOLD, 'crystal': [(50,10,70),(140,60,190),(200,120,240),(235,180,255),(255,240,255)]}
    img = render_bevel(c, ramps)
    for u in range(24, 70):   # fuller
        put_uv(img, u, 0.9, (130, 60, 170))
    for u0, r in ((29, 2.2), (39, 1.8), (48, 1.45), (56, 1.1), (63, 0.8)):   # gems shrinking toward the tip
        gem(img, u0, 0, r, (70,10,60), (255,130,225), (255,235,250))
    gem(img, 20.5, 0, 1.6, (60,10,40), (255,120,220), (255,230,250))
    return img

# ---------------------------------------------------------------- Sword of Eclipse: split light/dark blade, crescent guard
def eclipse():
    c = Canvas(N)
    blade = taper(4.4, 80, 80, 13)
    for y in range(N):
        for x in range(N):
            u, v = uv(x+0.5, y+0.5)
            if 22 <= u <= 80 and abs(v) <= blade(u):
                c.set(x, y, 'light' if v < 0 else 'dark')
    base_hilt(c, guard_w=3.0)
    for s in (-1, 1):   # crescent guard horns curling toward the blade
        c.poly([pt(18.5, s*2), pt(22, s*3), pt(26, s*10), pt(24, s*11), pt(19.5, s*6)], 'guard')
    ramps = {'light': [(120,110,130),(214,206,226),(240,236,250),(252,250,255),(255,255,255)],
             'dark': [(6,4,12),(20,16,32),(36,30,54),(60,52,86),(96,84,130)],
             'grip': [(14,10,20),(34,26,46),(54,42,72),(80,64,104),(110,92,140)],
             'guard': [(60,20,50),(160,60,140),(220,110,200),(245,170,230),(255,225,250)], 'pommel': GOLD}
    img = render_bevel(c, ramps)
    for u in range(23, 78):   # the terminator line glows pink
        put_uv(img, u, 0, (255, 140, 220))
    # sun on the light half, moon on the dark half
    gem(img, 38, -2.0, 1.6, (200,150,40), (255,220,90), (255,255,220))
    cx, cy = pt(52, 2.2)
    for y in range(N):
        for x in range(N):
            d = math.hypot(x+0.5-cx, y+0.5-cy); d2 = math.hypot(x+0.5-cx-0.9, y+0.5-cy+0.9)
            if d <= 1.9 and d2 > 1.5: put(img, x, y, (200, 200, 230))
    gem(img, 20.8, 0, 2.0, (60,20,50), (255,120,220), (255,230,250))
    return img

imgs = [hermes(), chione(), diminution(), eclipse()]
for n, im in zip(['hd_hermes', 'hd_chione', 'hd_diminution', 'hd_eclipse'], imgs): im.save(n + '.png')
preview(imgs, 'p_swords.png', scale=5)

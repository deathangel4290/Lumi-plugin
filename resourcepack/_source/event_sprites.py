import math, sys
from PIL import Image
def hexc(h,a=255): h=h.lstrip('#'); return (int(h[0:2],16),int(h[2:4],16),int(h[4:6],16),a)
def lerp(a,b,t): return tuple(int(round(a[i]+(b[i]-a[i])*t)) for i in range(4))
def outline(img, col):
    px=img.load(); W,H=img.size; out=img.copy(); po=out.load()
    for y in range(H):
        for x in range(W):
            if px[x,y][3]==0:
                for dx,dy in ((1,0),(-1,0),(0,1),(0,-1)):
                    X,Y=x+dx,y+dy
                    if 0<=X<W and 0<=Y<H and px[X,Y][3]>0: po[x,y]=col; break
    return out

def token():
    im=Image.new('RGBA',(16,16),(0,0,0,0)); px=im.load()
    c=7.5; R=6.6
    for y in range(16):
        for x in range(16):
            d=math.hypot(x-c,y-c)
            if d<=R:
                t=(x+y)/30
                base=lerp(hexc('#FFE27A'),hexc('#D9971E'),t)
                if d>R-1.3: base=lerp(hexc('#E8B23A'),hexc('#9A6512'),t)
                elif d>R-2.3: base=lerp(base,hexc('#C0841A'),0.35)
                px[x,y]=base
    # emblem: 4 point star
    star=[(7,4),(8,4),(7,5),(8,5),(6,6),(7,6),(8,6),(9,6),(4,7),(5,7),(6,7),(7,7),(8,7),(9,7),(10,7),(11,7),(4,8),(5,8),(6,8),(7,8),(8,8),(9,8),(10,8),(11,8),(6,9),(7,9),(8,9),(9,9),(7,10),(8,10),(7,11),(8,11)]
    for x,y in star: px[x,y]=hexc('#FFF7D0')
    for x,y in [(7,7),(8,7),(7,8),(8,8)]: px[x,y]=hexc('#FFFFFF')
    for x,y in [(4,4),(5,4),(4,5)]: px[x,y]=hexc('#FFF2B0')
    return outline(im,hexc('#4A2E06'))

def heart():
    T=["................",
       "................",
       "...XXXX..XXXX...",
       "..XXXXXXXXXXXX..",
       ".XXXXXXXXXXXXXX.",
       ".XXXXXXXXXXXXXX.",
       ".XXXXXXXXXXXXXX.",
       ".XXXXXXXXXXXXXX.",
       "..XXXXXXXXXXXX..",
       "...XXXXXXXXXX...",
       "....XXXXXXXX....",
       ".....XXXXXX.....",
       "......XXXX......",
       ".......XX.......",
       "................",
       "................"]
    im=Image.new('RGBA',(16,16),(0,0,0,0)); px=im.load()
    for y,row in enumerate(T):
        for x,ch in enumerate(row):
            if ch=='X':
                t=min(1,max(0,(x*0.35+y-2)/13))
                px[x,y]=lerp(hexc('#CF7BFF'),hexc('#4E0C86'),t)
    # facet seam and right-lobe shade
    for y in range(3,13):
        if px[8,y][3]: px[8,y]=lerp(px[8,y],hexc('#3A0866'),0.35)
    for x,y in [(3,3),(4,3),(2,4),(3,4),(2,5)]: px[x,y]=hexc('#F6E2FF')
    for x,y in [(5,3),(4,4),(3,5),(2,6)]: px[x,y]=hexc('#E3B4FF')
    for x,y in [(10,3),(11,4)]: px[x,y]=hexc('#DDA6FF')
    # glowing core
    for x,y in [(6,6),(7,6),(6,7),(7,7),(5,7),(7,8),(6,8)]: px[x,y]=hexc('#F9D2FF')
    for x,y in [(6,7)]: px[x,y]=hexc('#FFFFFF')
    return outline(im,hexc('#22053A'))

def core():
    im=Image.new('RGBA',(16,16),(0,0,0,0)); px=im.load()
    c=7.5
    for y in range(16):
        for x in range(16):
            dx,dy=x-c,y-c; d=math.hypot(dx,dy)
            ray = (abs(dx)<1.0 and d<7.6) or (abs(dy)<1.0 and d<7.6) or (abs(abs(dx)-abs(dy))<0.8 and d<5.4)
            if d<=3.6:
                px[x,y]=lerp(hexc('#FFFFFF'),hexc('#FFD23A'),max(0,(d-0.8)/2.8))
            elif ray:
                px[x,y]=lerp(hexc('#FFE98A'),hexc('#F08A1C'),min(1,(d-3.6)/4))
    return outline(im,hexc('#5A2A00'))

for name,fn in [('luminara_token',token),('boss_heart',heart),('star_core',core)]:
    im=fn(); im.save(name+'.png')
sheet=Image.new('RGBA',(3*160+40,180),(139,139,139,255))
for i,name in enumerate(['luminara_token','boss_heart','star_core']):
    im=Image.open(name+'.png').resize((160,160),Image.NEAREST)
    sheet.paste(im,(10+i*170,10),im)
sheet.save('sheet.png')

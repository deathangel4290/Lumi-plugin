import json, sys
from PIL import Image, ImageDraw
import tpsim
R='/home/user/Lumi-plugin/resourcepack/assets/luminara/'
names=['scythe_of_diablo','axe_of_zeus','sword_of_eclipse','luminosity','titans_axe','ender_glaive','hermes_blade','chiones_blade','blade_of_diminution','lost_trident','tether_chain','veil_of_mimicry','hellstorm_3d_standby']
import sys
eye=tuple(map(float,sys.argv[1].split(","))) if len(sys.argv)>1 else (0,1.3,3.4)
look=(0,1.0,0)
rows=[]
for n in names:
    fn = n if n.startswith('hellstorm') else n+'_3d'
    js=json.load(open(R+'models/item/'+fn+'.json'))
    t=list(js['textures'].values())[0].split(':')[-1]
    tex=Image.open(R+'textures/'+t+'.png')
    if tex.height>tex.width: tex=tex.crop((0,0,tex.width,tex.width))
    pose = tpsim.CROSSBOW_HOLD if n.startswith('hellstorm') else None
    ims=[tpsim.render_hand(js,tex,left=False,pose=pose,W=220,H=260,eye=eye,look=look),
         tpsim.render_hand(js,tex,left=True,pose=pose,W=220,H=260,eye=eye,look=look)]
    rows.append((n,ims))
cols=4
W=440*cols; H=280*((len(rows)+cols-1)//cols)
out=Image.new('RGB',(W,H),(60,60,60)); d=ImageDraw.Draw(out)
for i,(n,ims) in enumerate(rows):
    x=(i%cols)*440; y=(i//cols)*280
    out.paste(ims[0],(x,y+20)); out.paste(ims[1],(x+220,y+20))
    d.text((x+4,y+4),n+'  R | L',fill=(255,255,255))
out.save('prev/hands%s.png'%(sys.argv[2] if len(sys.argv)>2 else ''))

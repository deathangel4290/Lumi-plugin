import json
from PIL import Image, ImageDraw
from toon import first_person
R='/home/user/Lumi-plugin/resourcepack/assets/luminara/'
names=['scythe_of_diablo','axe_of_zeus','sword_of_eclipse','luminosity','titans_axe','ender_glaive','hermes_blade','lost_trident','tether_chain','veil_of_mimicry','hellstorm_3d_standby','hellstorm_3d_arrow']
out=Image.new('RGB',(2*384*2,216*6+120),(50,50,50)); d=ImageDraw.Draw(out)
for i,n in enumerate(names):
    fn = n if n.startswith('hellstorm') else n+'_3d'
    js=json.load(open(R+'models/item/'+fn+'.json'))
    t=list(js['textures'].values())[0].split(':')[-1]
    tex=Image.open(R+'textures/'+t+'.png')
    if tex.height>tex.width: tex=tex.crop((0,0,tex.width,tex.width))
    x=(i%2)*768; y=(i//2)*(216+20)
    out.paste(first_person(js,tex,W=384,H=216,left=True),(x,y+20)); out.paste(first_person(js,tex,W=384,H=216),(x+384,y+20))
    d.text((x+4,y+4),n+'   LEFT | RIGHT',fill=(255,255,255))
out.save('prev/fp_hands.png')

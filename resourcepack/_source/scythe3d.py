from m3d import *

def build():
    m = Model(); A = m.atlas
    A.material('haft', grain((14, 8, 12), (38, 22, 30), (70, 44, 56)))
    A.material('wrap', wrap((60, 6, 14), (140, 18, 30), (220, 70, 80)))
    A.material('gold', metal((110, 66, 12), (212, 158, 46), (250, 214, 110), (255, 246, 200)))
    A.material('ruby', gem((90, 6, 16), (210, 20, 40), (255, 150, 150)))
    A.material('blade', metal((70, 6, 14), (150, 20, 34), (200, 50, 62), (240, 120, 120), streak=False))
    A.material('edge', flat((255, 205, 195), (230, 150, 150)))
    A.material('spine', flat((60, 4, 12), (40, 2, 8)))
    A.material('rune', flat((255, 70, 60), (200, 30, 30)))

    # haft: grip near y=6 so it sits in the hand
    m.box((7.0, -12, 7.0), (9.0, 27, 9.0), 'haft')
    m.box((6.9, 2, 6.9), (9.1, 10, 9.1), 'wrap')                       # leather grip
    for y in (-8, 14, 20):                                            # red rings
        m.box((6.8, y, 6.8), (9.2, y + 1, 9.2), 'wrap')
    m.box((6.5, -14, 6.5), (9.5, -12, 9.5), 'gold')                   # pommel
    m.box((7.3, -15, 7.3), (8.7, -14, 8.7), 'ruby')
    # head / collar with a ruby set in both faces
    m.box((6.2, 25, 6.2), (9.8, 29.5, 9.8), 'gold')
    m.box((7.0, 26, 5.9), (9.0, 28.5, 10.1), 'ruby')
    m.box((7.4, 29.5, 7.4), (8.6, 32, 8.6), 'gold')                   # crown spike
    # back spike, angled up-right
    m.box((9.5, 26.6, 7.6), (14.5, 27.8, 8.4), 'gold', ('z', 22.5, (9.5, 27.2, 8)))

    # the blade: a crescent sweeping left from the head and curling down to vertical
    steps = [(0.0, 7.0, 7.0), (22.5, 6.5, 6.0), (45.0, 5.5, 5.0), (67.5, 4.5, 3.8), (90.0, 4.0, 2.6), (90.0, 2.0, 1.2)]
    blade_curve(m, (6.4, 27.0), steps, 1.1, ('blade', 'edge', 'spine'))
    return m

if __name__ == '__main__':
    m = build()
    print(len(m.elements), 'elements')
    from PIL import Image
    views = [render(m, yaw=0, pitch=0), render(m, yaw=35, pitch=20), render(m, yaw=-60, pitch=15)]
    sheet = Image.new('RGBA', (sum(v.width for v in views), views[0].height))
    x = 0
    for v in views: sheet.paste(v, (x, 0)); x += v.width
    sheet.save('p_scythe3d.png')
    m.atlas.img.resize((256, 256), Image.NEAREST).save('atlas_scythe.png')

"""The launcher icon: the vinyl of option 3 with a "6" (as in option 2) in its lower half and one foot of each colour
on the 6 (user 2026-10-19). Draws at 4x and downsamples. Writes design/icons/icon.png (512) and the mipmaps.

    python design/make_icon.py            # icon.png and the mipmaps
    python design/make_icon.py --preview  # icon.png only
"""
import math, os, sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, '..', 'app', 'src', 'main', 'res')
S = 4                      # supersampling
W = 512 * S
CREAM = (248, 246, 240, 255); DARK = (28, 28, 34, 255); RING = (52, 52, 60, 255)
ORANGE = (240, 180, 50, 255); BLUE = (46, 110, 230, 255); PINK = (235, 80, 160, 255)
FONT = '/usr/share/fonts/opentype/inter/Inter-ExtraBold.otf'

def px(v): return int(round(v * S))

# the shoe sole of every picture (dance/shoe.py; the app has a Kotlin copy in FloorView.kt)
sys.path.insert(0, os.path.join(HERE, '..', 'dance'))
import shoe as SHOE


def shoe(draw, cx, cy, length, angle_deg, side, colour):
    """a shoe sole `length` long (512 units) centred on (cx, cy); angle 0 = toe up, positive = clockwise; side 'L' or 'R'"""
    k = length / SHOE.LENGTH
    a = math.radians(angle_deg); ca, sa = math.cos(a), math.sin(a)
    centre_v = (SHOE.V_MAX + SHOE.V_MIN) / 2
    for piece in SHOE.outlines(side, n=24):
        out = []
        for u, v in piece:
            u, v = u * k, (v - centre_v) * k
            out.append((px(cx + u * ca + v * sa), px(cy + u * sa - v * ca)))
        draw.polygon(out, fill=colour)


def six_mask(top, bottom):
    """the glyph 6 as a mask image (W x W) whose ink spans y = top..bottom (512 units), centred on the disc"""
    size = 10
    while True:
        f = ImageFont.truetype(FONT, size * S)
        l, t, r, b = f.getbbox('6')
        if (b - t) / S >= bottom - top: break
        size += 1
    m = Image.new('L', (W, W), 0)
    d = ImageDraw.Draw(m)
    x = px(256) - (l + r) // 2
    y = px(top) - t
    d.text((x, y), '6', font=f, fill=255)
    return m

def hollow_bbox(mask):
    """the inner hole of the 6's bowl: the enclosed background region, as (x0, y0, x1, y1) in 512 units"""
    # flood fill the outside from a corner; what remains unfilled and un-inked is the hole
    from PIL import ImageOps
    filled = mask.copy()
    ImageDraw.floodfill(filled, (0, 0), 128)
    hole = filled.point(lambda v: 255 if v == 0 else 0)
    bb = hole.getbbox()
    return tuple(v / S for v in bb)

def curl_end(mask):
    """the end of the 6's top curl: the rightmost ink point in the upper third of the glyph, in 512 units"""
    bb = mask.getbbox()
    top, bottom = bb[1], bb[3]
    band = mask.crop((0, top, W, top + (bottom - top) // 3))
    best = None
    bx = band.load()
    for yy in range(band.size[1]):
        for xx in range(band.size[0] - 1, 0, -1):
            if bx[xx, yy]:
                if best is None or xx > best[0]: best = (xx, yy)
                break
    return best[0] / S, (best[1] + top) / S

def touching(glyph, cx, cy, length, angle, side):
    """how many supersampled pixels a shoe at (cx, cy) shares with the glyph mask"""
    m = Image.new('L', (W, W), 0)
    shoe(ImageDraw.Draw(m), cx, cy, length, angle, side, 255)
    from PIL import ImageChops
    return sum(ImageChops.multiply(m, glyph).point(lambda v: 1 if v > 127 else 0).histogram()[1:])


def render(label=True, top=312, bottom=440, plate=True):
    im = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if plate: d.rounded_rectangle([0, 0, W - 1, W - 1], radius=px(110), fill=CREAM)
    cx = cy = 256; R = 200
    d.ellipse([px(cx - R), px(cy - R), px(cx + R), px(cy + R)], fill=DARK)
    for r in range(70, 190, 22):
        d.ellipse([px(cx - r), px(cy - r), px(cx + r), px(cy + r)], outline=RING, width=px(1.2))
    if label:
        d.ellipse([px(cx - 50), px(cy - 50), px(cx + 50), px(cy + 50)], fill=ORANGE)
        d.ellipse([px(cx - 8), px(cy - 8), px(cx + 8), px(cy + 8)], fill=DARK)
    else:
        d.ellipse([px(cx - 8), px(cy - 8), px(cx + 8), px(cy + 8)], fill=ORANGE)
    m = six_mask(top, bottom)
    im.paste(Image.new('RGBA', (W, W), CREAM), (0, 0), m)
    d = ImageDraw.Draw(im)
    gb = tuple(v / S for v in m.getbbox())
    hx0, hy0, hx1, hy1 = hollow_bbox(m)
    ex, ey = curl_end(m)
    h = bottom - top
    length = h * 0.62
    # the blue foot (a left shoe): on the 6's lower-left corner, toes 45 degrees up and left
    shoe(d, gb[0] + h * 0.19, gb[3] - h * 0.21, length, -45, 'L', BLUE)
    # the pink foot (a right shoe): toes 20 degrees right of vertical (v12: 55 degrees, turned 35 anticlockwise), its centre
    # two-thirds of the way from its v11 spot up to the top of the 6 (v12), then slid right until it only just touches the
    # 6 (v13, user 2026-10-19)
    width = h * 0.24; rh = width * 0.40
    a = math.radians(55); ux, uy = math.sin(a), -math.cos(a)
    heel_x, heel_y = hx1 + rh * 0.3, hy0 - rh * 0.3
    px0, py0 = heel_x - ux * rh + ux * length / 2, heel_y - uy * rh + uy * length / 2   # the v11 centre
    py1 = py0 + (gb[1] - py0) * 2 / 3
    x = px0
    while touching(m, x + 0.25, py1, length, 20, 'R') > 0: x += 0.25
    shoe(d, x, py1, length, 20, 'R', PINK)
    return im.resize((512, 512), Image.LANCZOS)

def write_mipmaps(icon, fg_art):
    dens = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
    for name, size in dens.items():
        folder = os.path.join(RES, 'mipmap-' + name); os.makedirs(folder, exist_ok=True)
        icon.resize((size, size), Image.LANCZOS).save(os.path.join(folder, 'ic_launcher.png'))
        rnd = icon.resize((size, size), Image.LANCZOS)
        mask = Image.new('L', (size * 4, size * 4), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
        rnd.putalpha(mask.resize((size, size), Image.LANCZOS))
        rnd.save(os.path.join(folder, 'ic_launcher_round.png'))
        # the adaptive foreground: 108 dp with the disc in the 72 dp safe zone; the cream plate is the background colour
        # (ic_launcher_background in colors.xml), so whatever shape the launcher cuts, the corners stay cream
        fg_size = int(size * 108 / 48)
        fg = Image.new('RGBA', (fg_size, fg_size), (0, 0, 0, 0))
        inner = int(fg_size * 72 / 108 * 1.06)
        sq = fg_art.resize((inner, inner), Image.LANCZOS)
        fg.paste(sq, ((fg_size - inner) // 2, (fg_size - inner) // 2), sq)
        fg.save(os.path.join(folder, 'ic_launcher_foreground.png'))

if __name__ == '__main__':
    preview = '--preview' in sys.argv
    out = os.path.join(HERE, 'icons')
    # the chosen layout: the 6 in the lower half of the disc, standing in front of the label
    a = render(label=True, top=256, bottom=440); a.save(os.path.join(out, 'icon.png'))
    if preview:
        render(label=True, top=304, bottom=440).save(os.path.join(out, 'icon_under_label.png'))
        render(label=False, top=262, bottom=440).save(os.path.join(out, 'icon_no_label.png'))
    else: write_mipmaps(a, render(label=True, top=256, bottom=440, plate=False))
    print('written')

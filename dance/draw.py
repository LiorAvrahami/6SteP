"""Drawing helpers shared by the storyboard (check.py) and the video renderer (render.py). PIL only."""
import math
from PIL import Image, ImageDraw, ImageFont
from model import fwd, right, add, dist, SHOULDER_HALF, ARM_MAX, BODY_DIAM, FOOT_DIAM

LEADER_RGB = (46, 110, 230)
FOLLOWER_RGB = (235, 80, 160)
GREY = (120, 120, 120)
FOOT_LEN, FOOT_WID = 26.0, 10.0   # cm, silhouette size
ARROW_LEN = 9.0                   # cm, the heading arrow out of the toe

def _font(size):
    for name in ('arial.ttf', 'DejaVuSans.ttf'):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()

class View:
    """Maps world cm -> pixels. y up in the world, down on screen."""
    def __init__(self, cx, cy, scale, w, h):
        self.cx, self.cy, self.scale, self.w, self.h = cx, cy, scale, w, h
    def px(self, p):
        return (self.w / 2 + (p[0] - self.cx) * self.scale, self.h / 2 - (p[1] - self.cy) * self.scale)

def foot_polygon(x, y, heading, side):
    """Outline of a foot silhouette (toe forward, slight asymmetry so left and right differ), in cm."""
    f = fwd(heading); r = right(heading)
    s = 1.0 if side == 'R' else -1.0
    pts = []
    # a rounded sole: heel semicircle, toe wider on the big-toe side
    n = 10
    for i in range(n + 1):  # toe arc, from outer side to inner side
        a = math.pi * i / n
        px = math.cos(a) * FOOT_WID / 2 * (1.0 + 0.25 * (1 if math.cos(a) * s < 0 else 0))
        py = FOOT_LEN / 2 - FOOT_WID / 2 + math.sin(a) * FOOT_WID / 2 * 1.15
        pts.append((px, py))
    for i in range(n + 1):  # heel arc
        a = math.pi + math.pi * i / n
        pts.append((math.cos(a) * FOOT_WID / 2 * 0.85, -FOOT_LEN / 2 + FOOT_WID / 2 * 0.85 + math.sin(a) * FOOT_WID / 2 * 0.85))
    out = []
    for px, py in pts:
        wx = x + px * r[0] + py * f[0]
        wy = y + px * r[1] + py * f[1]
        out.append((wx, wy))
    return out

def _blend(img, pts, draw_fn, pad=6):
    """Draw translucent shapes through a layer the size of their bounding box (not the whole frame) and composite it.
    draw_fn(d, ox, oy) draws on the layer with coordinates shifted by (-ox, -oy)."""
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    x0 = max(0, int(min(xs)) - pad); y0 = max(0, int(min(ys)) - pad)
    x1 = min(img.width, int(max(xs)) + pad + 1); y1 = min(img.height, int(max(ys)) + pad + 1)
    if x1 <= x0 or y1 <= y0:
        return
    layer = Image.new('RGBA', (x1 - x0, y1 - y0), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(layer), x0, y0)
    img.alpha_composite(layer, dest=(x0, y0))

def draw_foot(img, view, x, y, heading, side, rgb, weight, outline=None):
    """Grey base at 0.2 under the colour at opacity = weight (user Q7)."""
    poly = [view.px(p) for p in foot_polygon(x, y, heading, side)]
    _blend(img, poly, lambda d, ox, oy: d.polygon([(px - ox, py - oy) for px, py in poly], fill=GREY + (int(255 * 0.2),)))
    if weight > 0.001:
        _blend(img, poly, lambda d, ox, oy: d.polygon([(px - ox, py - oy) for px, py in poly], fill=rgb + (int(255 * max(0.0, min(1.0, weight))),)))
    if outline:
        ImageDraw.Draw(img).polygon(poly, outline=outline)
    # arrow out of the toe, pointing where the foot faces (user 2026-10-18)
    f = fwd(heading); r = right(heading)
    toe = (x + f[0] * FOOT_LEN / 2, y + f[1] * FOOT_LEN / 2)
    tip = (toe[0] + f[0] * ARROW_LEN, toe[1] + f[1] * ARROW_LEN)
    acol = (rgb if weight > 0.5 else GREY) + (int(255 * max(0.45, weight)),)
    lw = max(2, int(view.scale * 1.6))
    head = [tip, (tip[0] - f[0] * 5 + r[0] * 3.5, tip[1] - f[1] * 5 + r[1] * 3.5), (tip[0] - f[0] * 5 - r[0] * 3.5, tip[1] - f[1] * 5 - r[1] * 3.5)]
    pts = [view.px(toe), view.px(tip)] + [view.px(q) for q in head]
    def arrow(d, ox, oy):
        d.line([(pts[0][0] - ox, pts[0][1] - oy), (pts[1][0] - ox, pts[1][1] - oy)], fill=acol, width=lw)
        d.polygon([(q[0] - ox, q[1] - oy) for q in pts[2:]], fill=acol)
    _blend(img, pts, arrow, pad=lw + 4)
    # letter L/R at the heel
    hp = view.px((x - f[0] * FOOT_LEN * 0.22, y - f[1] * FOOT_LEN * 0.22))
    fnt = _font(max(9, int(view.scale * 7)))
    ImageDraw.Draw(img).text(hp, side, fill=(255, 255, 255), font=fnt, anchor='mm')

def body(feet, weights=None):
    """Body centre and heading from the two feet (dict 'L','R' -> (x,y,h))."""
    (lx, ly, lh), (rx, ry, rh) = feet['L'], feet['R']
    cx, cy = (lx + rx) / 2, (ly + ry) / 2
    # circular mean of the two headings
    sx = math.sin(math.radians(lh)) + math.sin(math.radians(rh))
    sy = math.cos(math.radians(lh)) + math.cos(math.radians(rh))
    h = math.degrees(math.atan2(sx, sy)) if (abs(sx) + abs(sy)) > 1e-9 else lh
    return (cx, cy, h)

def shoulder(b, side):
    cx, cy, h = b
    return add((cx, cy), right(h), SHOULDER_HALF if side == 'R' else -SHOULDER_HALF)

def body_point(b, which):
    cx, cy, h = b
    if which == 'back':
        return add((cx, cy), fwd(h), -8.0)
    if which == 'lshoulder':
        return shoulder(b, 'L')
    if which == 'rshoulder':
        return shoulder(b, 'R')
    if which == 'lhip':
        return add((cx, cy), right(h), -12.0)
    if which == 'rhip':
        return add((cx, cy), right(h), 12.0)
    raise ValueError(which)

def resolve_hand(conn, bodies):
    """conn like 'LL-FR', 'LR-back', 'LL-FR^F' (joined hands raised, the follower passes under them; '^L' = the leader does).
    Returns a list of arms: (dancer, hand side, shoulder point, hand point, raised, ok)."""
    raised = '^' in conn
    under = conn.split('^')[1] if raised and len(conn.split('^')[1]) == 1 else None
    c = conn.split('^')[0]
    owner, target = c.split('-')
    d1, h1 = owner[0], owner[1]
    b1 = bodies[d1]
    s1 = shoulder(b1, h1)
    arms = []
    if len(target) == 2 and target[0] in 'LF':
        d2, h2 = target[0], target[1]
        s2 = shoulder(bodies[d2], h2)
        if under in ('L', 'F'):
            # the joined hands are above the head of the dancer who goes under them
            bu = bodies[under]
            hand = (bu[0], bu[1])
        else:
            hand = ((s1[0] + s2[0]) / 2, (s1[1] + s2[1]) / 2)
        ok = dist(s1, hand) <= ARM_MAX and dist(s2, hand) <= ARM_MAX
        arms.append((d1, h1, s1, hand, raised, ok))
        arms.append((d2, h2, s2, hand, raised, ok))
    else:
        d2 = 'F' if d1 == 'L' else 'L'
        hand = body_point(bodies[d2], target)
        ok = dist(s1, hand) <= ARM_MAX
        arms.append((d1, h1, s1, hand, raised, ok))
    return arms

def elbow(sh, hand, side_sign):
    """Upper arm and forearm have a maximum length (ARM_MAX/2 each) but no minimum (user): the elbow sits a little
    outside the shoulder->hand line (side_sign: +1 = to the right of that line), never so far that a segment exceeds its max."""
    half = ARM_MAX / 2
    d = dist(sh, hand)
    mx, my = (sh[0] + hand[0]) / 2, (sh[1] + hand[1]) / 2
    if d < 1e-6:
        return (mx, my)
    limit = math.sqrt(max(0.0, half * half - (d / 2) ** 2))
    off = min(0.22 * d + 4.0, limit)
    ux, uy = (hand[0] - sh[0]) / d, (hand[1] - sh[1]) / d
    nx, ny = uy * side_sign, -ux * side_sign
    return (mx + nx * off, my + ny * off)

def draw_arm(img, view, dancer, hand_side, sh, hand, raised, ok, alpha=255):
    rgb = LEADER_RGB if dancer == 'L' else FOLLOWER_RGB
    col = (rgb if ok else (220, 30, 30)) + (alpha if not raised else int(alpha * 0.55),)
    el = elbow(sh, hand, 1 if hand_side == 'R' else -1)
    w = max(2, int(view.scale * 2.0))
    r = max(2, int(view.scale * 2.2))
    a, b, c = view.px(sh), view.px(el), view.px(hand)
    def arm(d, ox, oy):
        d.line([(a[0] - ox, a[1] - oy), (b[0] - ox, b[1] - oy)], fill=col, width=w)
        d.line([(b[0] - ox, b[1] - oy), (c[0] - ox, c[1] - oy)], fill=col, width=w)
        d.ellipse([c[0] - ox - r, c[1] - oy - r, c[0] - ox + r, c[1] - oy + r], fill=col)
    _blend(img, [a, b, c], arm, pad=w + r + 2)

def draw_body(img, view, b, rgb, alpha=60):
    cx, cy, h = b
    r = BODY_DIAM / 2 * view.scale
    p = view.px((cx, cy))
    tip = view.px(add((cx, cy), fwd(h), BODY_DIAM / 2 + 6))
    lw = max(1, int(view.scale))
    def body_(d, ox, oy):
        d.ellipse([p[0] - ox - r, p[1] - oy - r, p[0] - ox + r, p[1] - oy + r], fill=rgb + (alpha,), outline=rgb + (140,))
        d.line([(p[0] - ox, p[1] - oy), (tip[0] - ox, tip[1] - oy)], fill=rgb + (160,), width=lw)
    _blend(img, [(p[0] - r, p[1] - r), (p[0] + r, p[1] + r), tip], body_, pad=lw + 2)

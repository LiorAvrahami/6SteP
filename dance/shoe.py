"""The shoe sole every picture draws a foot with: the storyboards and videos (draw.py), the launcher icon (design/make_icon.py)
and, as a Kotlin copy of the same numbers, the app (app/src/main/java/org/lior/sixstep/FloorView.kt: `sole` and `heel`).
Change all three together.

A LEFT shoe seen from below, in cm: u across (+ = the inner, big-toe side), v along (+ = the toe). The front sole runs from a
pointed toe to the waist; the heel is a separate piece behind a small gap (user 2026-10-19: "the bottom of a dancing shoe").
Each outline is a start point, then cubic segments (control 1, control 2, end). A right shoe is the mirror (u -> -u).
The outline spans v = -13.0 .. 13.4 around the foot's placement, so the shoe is 26.4 cm long and 10.2 cm wide."""

SOLE = [(2.6, -3.0), ((3.0, 0.5), (4.8, 3.0), (4.6, 6.0)), ((4.4, 9.5), (2.8, 12.4), (1.2, 13.0)),
        ((-0.6, 13.4), (-5.2, 8.5), (-5.0, 4.5)), ((-4.9, 1.5), (-3.6, -1.0), (-3.4, -3.0)), ((-1.5, -2.7), (0.8, -2.7), (2.6, -3.0))]
HEEL = [(2.7, -4.4), ((2.9, -6.5), (2.9, -9.5), (2.4, -11.0)), ((1.9, -12.5), (0.9, -13.0), (-0.4, -13.0)),
        ((-1.8, -13.0), (-2.8, -12.4), (-3.3, -11.0)), ((-3.8, -9.5), (-3.6, -6.5), (-3.4, -4.4)), ((-1.3, -4.1), (0.6, -4.1), (2.7, -4.4))]
V_MIN, V_MAX = -13.0, 13.4
LENGTH = V_MAX - V_MIN


def bezier(shape, n=16):
    """the outline sampled as a polygon (list of (u, v))"""
    pts = [shape[0]]; p0 = shape[0]
    for c1, c2, p3 in shape[1:]:
        for i in range(1, n + 1):
            t = i / n; a, b, c, d = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3
            pts.append((a * p0[0] + b * c1[0] + c * c2[0] + d * p3[0], a * p0[1] + b * c1[1] + c * c2[1] + d * p3[1]))
        p0 = p3
    return pts


def outlines(side, n=16):
    """both pieces of a shoe, local (u = to the foot's right, v = forward) in cm, for side 'L' or 'R'"""
    m = 1.0 if side == 'L' else -1.0
    return [[(u * m, v) for u, v in bezier(shape, n)] for shape in (SOLE, HEEL)]


# ---------------------------------------------------------------- the gap between two shoes (the collision rule of check.py)
import math as _math


def world(p, side, n=6):
    """both pieces of the shoe of a foot placed at p = (x, y, heading) as world polygons (cm)"""
    x, y, h = p[0], p[1], p[2]
    r = _math.radians(h); f = (_math.sin(r), _math.cos(r)); rt = (_math.cos(r), -_math.sin(r))
    return [[(x + u * rt[0] + v * f[0], y + u * rt[1] + v * f[1]) for u, v in piece] for piece in outlines(side, n)]


def _inside(pt, poly):
    x, y = pt; c = False
    for i in range(len(poly)):
        (x1, y1), (x2, y2) = poly[i], poly[i - 1]
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            c = not c
    return c


def _seg(pt, a, b):
    ax, ay = a; bx, by = b; px, py = pt
    dx, dy = bx - ax, by - ay; L = dx * dx + dy * dy
    t = 0.0 if L == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L))
    return _math.hypot(px - ax - t * dx, py - ay - t * dy)


def gap(p1, side1, p2, side2, n=6):
    """the smallest distance in cm between the outlines of two shoes; 0 when they touch or overlap"""
    A = world(p1, side1, n); B = world(p2, side2, n)
    best = 1e9
    for pa in A:
        for pb in B:
            if any(_inside(q, pb) for q in pa) or any(_inside(q, pa) for q in pb):
                return 0.0
            for P, Q in ((pa, pb), (pb, pa)):
                for q in P:
                    for i in range(len(Q)):
                        d = _seg(q, Q[i - 1], Q[i])
                        if d < best: best = d
    return best

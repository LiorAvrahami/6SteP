"""Render a sequence of elements to a video with the music.

    python dance/render.py "rock step" "triple step" "triple step" "follower underarm pass" "circle" --track hot --out dance/out/demo.mp4
    python dance/render.py --list                      # the elements that exist, by start state
    python dance/render.py --composition "rock double triple step" --track hot
    options: --start side|facing|reversed_side   --track hot|sunday|slow   --fps 30   --size 1280x720   --start-bar N   --frames-dir DIR

Timing (from the track's beat grid JSON in music/chosen): count n of an element is beat (start + n - 1) of the track,
the "&" is swing*beat later (0.68 on Hot Swing). The dance starts on the first bar that begins at or after 3 s
unless --start-bar is given. One bar of the start position is shown before the first step.

Weight and travel: a foot lands on its count and its weight ramps up over the next 0.3 beat (or less if the next
step comes sooner) while the other foot's weight ramps down; a foot travels only while its weight is zero, from the
moment it is released until it lands. Opacity = weight over a grey base of 0.2 (user Q7).
"""
import sys, os, json, math, argparse, subprocess, glob
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import model, draw
from model import fwd, right, add, dist, norm_angle
from PIL import Image, ImageDraw

MUSIC = os.path.join(os.path.dirname(model.HERE), 'music', 'chosen')
TRACKS = {
    'hot': 'Hot Swing - Kevin MacLeod - 5min loop (144bpm, CC BY)',
    'sunday': '15 - Laughing Manny - Sunday Reflection (127bpm)',
    'slow': '16 - Laughing Manny - Slow Your Roll (123bpm)',
}
RAMP = 0.3          # beats, weight transfer after landing
RAMP_FRACTION = 0.45  # at most this fraction of the gap to the dancer's next step
BG = (248, 246, 240, 255)
MIN_SCALE, MAX_SCALE = 3.6, 5.0   # px per cm

def load_grid(track):
    name = TRACKS[track]
    with open(os.path.join(MUSIC, name + ' - beatgrid.json'), encoding='utf-8') as f:
        g = json.load(f)
    return g, os.path.join(MUSIC, name + '.mp3')

def beat_seconds(grid, b):
    """Seconds of fractional beat index b (0-based index into beats[])."""
    beats = grid['beats']
    k = int(math.floor(b)); frac = b - k
    if k + 1 < len(beats):
        return beats[k] + frac * (beats[k + 1] - beats[k])
    return beats[-1] + (b - (len(beats) - 1)) * grid['beat']

def start_beat_index(grid, start_bar=None):
    bars, beats = grid['bars'], grid['beats']
    if start_bar is None:
        t = next(b for b in bars if b >= 3.0)
    else:
        t = bars[start_bar]
    return min(range(len(beats)), key=lambda i: abs(beats[i] - t))

def smoothstep(u):
    u = max(0.0, min(1.0, u)); return u * u * (3 - 2 * u)

class FootTrack:
    """Landings of one foot: list of (t_land, t_release_prev, placement). Travel happens in [t_release_prev, t_land]."""
    def __init__(self, start_placement):
        self.landings = [(-1e9, -1e9, start_placement)]
    def placement_at(self, t):
        # find the last landing with t_land <= t, and whether we are travelling toward the next one
        i = 0
        for j, (tl, tr, p) in enumerate(self.landings):
            if tl <= t: i = j
        tl, tr, p = self.landings[i]
        if i + 1 < len(self.landings):
            ntl, ntr, npl = self.landings[i + 1]
            if t >= ntr and t < ntl:
                u = smoothstep((t - ntr) / max(1e-6, ntl - ntr))
                dh = npl[2] - p[2]   # the raw difference: the stored winding chooses the way round
                return (p[0] + (npl[0] - p[0]) * u, p[1] + (npl[1] - p[1]) * u, p[2] + dh * u)
        return p

def build_tracks(comp, swing):
    """Per dancer: foot tracks and the weight function."""
    out = {}
    for d in 'LF':
        steps = [s for s in comp['steps'] if s['dancer'] == d]
        steps.sort(key=lambda s: s['t'])
        tracks = {'L': FootTrack(comp['start'][f'{d}.L']), 'R': FootTrack(comp['start'][f'{d}.R'])}
        w0 = 'R' if d == 'L' else 'L'   # leader starts weight on R, follower on L (6-beat_rhythm.html)
        # ramps: (t_land, ramp, foot gaining)
        ramps = []
        for i, s in enumerate(steps):
            nxt = steps[i + 1]['t'] if i + 1 < len(steps) else s['t'] + 2.0
            ramp = min(RAMP, RAMP_FRACTION * (nxt - s['t']))
            ramps.append((s['t'], ramp, s['foot']))
        for i, s in enumerate(steps):
            if i == 0:
                release = s['t'] - 0.6   # the first step: the free foot starts moving just before its count
            else:
                release = ramps[i - 1][0] + ramps[i - 1][1]
            tr = tracks[s['foot']]
            prev = tr.landings[-1][2]
            if dist(prev, (s['x'], s['y'])) < 0.5 and abs(prev[2] - s['heading']) < 0.5:
                release = s['t']  # replace: no travel
            tr.landings.append((s['t'], min(release, s['t']), (s['x'], s['y'], s['heading'])))
        # before the first step: the neutral stance, weight on both feet; it moves onto the foot that stays (the other one
        # steps first) over SHIFT beats, ending as the first foot leaves (user 2026-10-19)
        if steps:
            w0 = 'R' if steps[0]['foot'] == 'L' else 'L'
            shift_end = tracks[steps[0]['foot']].landings[1][1]
        else:
            shift_end = -1e9
        out[d] = {'tracks': tracks, 'ramps': ramps, 'w0': w0, 'shift_end': shift_end}
    return out

SHIFT = 0.3   # beats: the neutral stance's weight moving onto the standing foot before the first step

def weights_at(dancer_tracks, t):
    w0 = dancer_tracks['w0']
    w = {'L': 0.0, 'R': 0.0}; w[w0] = 1.0
    se = dancer_tracks.get('shift_end', -1e9)
    if t < se:
        u = smoothstep((t - (se - SHIFT)) / SHIFT); other = 'R' if w0 == 'L' else 'L'
        w[w0] = 0.5 + 0.5 * u; w[other] = 0.5 - 0.5 * u
        return w
    for tl, ramp, foot in dancer_tracks['ramps']:
        if t < tl: break
        u = smoothstep((t - tl) / max(1e-6, ramp))
        other = 'R' if foot == 'L' else 'L'
        w[foot] = max(w[foot], u) if u < 1 else 1.0
        w[other] = min(w[other], 1 - u) if u < 1 else 0.0
        if u >= 1: w[foot], w[other] = 1.0, 0.0
    return w

def hands_at(comp, t):
    hands = list(comp['start_hands'])
    for s in comp['steps']:
        if s['t'] <= t: hands = s['hands']
        else: break
    return hands

def label_at(comp, t):
    for i, e in enumerate(comp['elements']):
        if e['start'] <= t < e['start'] + e['beats']:
            return e['name'], e
    return None, None

def count_label(comp, t, swing):
    """The musical count being danced: the element's own numbering (a triple on 5&6 shows 5, 5&, 6); '&' only where a step exists."""
    el, e = label_at(comp, t)
    if e is None: return ''
    idx = comp['elements'].index(e)
    counts = [s['count'] for s in comp['steps'] if s['element'] == idx]
    base = int(str(counts[0]).rstrip('&'))
    b = t - e['start']
    k = int(math.floor(b)); frac = b - k
    n = base + k
    if frac >= swing - 0.02 and f"{n}&" in counts:
        return f"{n}&"
    return str(n)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('elements', nargs='*')
    ap.add_argument('--start', default='side')
    ap.add_argument('--track', default='hot', choices=list(TRACKS))
    ap.add_argument('--out', default=os.path.join(model.HERE, 'out', 'sequence.mp4'))
    ap.add_argument('--fps', type=int, default=30)
    ap.add_argument('--size', default='1280x720')
    ap.add_argument('--start-bar', type=int, default=None)
    ap.add_argument('--frames-dir', default=None, help='also write every frame as PNG here')
    ap.add_argument('--list', action='store_true')
    ap.add_argument('--composition', default=None, help='render a named composition from compositions.json instead of element names')
    ap.add_argument('--file', default=model.SKELETONS)
    a = ap.parse_args()
    data = model.load(a.file)
    if a.list:
        for e in data['elements']:
            print(f"{e['name']:35s} from {e['start_state']:14s} -> {e['end_state']:14s} leader free {e.get('leader_free_start','L')}  {e['beats']} beats")
        return
    if a.composition:
        comp_def = model.find_composition(a.composition)
        if comp_def is None:
            ap.error(f'no composition named {a.composition!r} in compositions.json')
        a.elements = model.composition_sequence(comp_def)
        a.groups = model.composition_groups(comp_def)
        a.start = comp_def['start_state']
    if not a.elements:
        ap.error('give element names, --composition NAME, or --list')
    grid, mp3 = load_grid(a.track)
    swing = float(grid.get('swing', model.DEFAULT_SWING))
    comp = model.compose(data, a.elements, a.start, swing, groups=getattr(a, 'groups', None))
    for err in comp['errors']:
        print('ERROR', err)
    if comp['errors']:
        sys.exit(1)
    W, H = [int(v) for v in a.size.lower().split('x')]
    k0 = start_beat_index(grid, a.start_bar)
    t_end_beats = comp['length'] + 1.0
    t0_s = 0.0
    t_end_s = beat_seconds(grid, k0 + t_end_beats)
    track_end = float(grid.get('duration') or 0.0)
    truncated = None
    if track_end and t_end_s > track_end:
        truncated = (t_end_s, track_end)
        t_end_s = track_end
    nframes = int(math.ceil(t_end_s * a.fps))
    tracks = build_tracks(comp, swing)
    # camera: bounding box of every placement
    pts = [(s['x'], s['y']) for s in comp['steps']] + [(p[0], p[1]) for p in comp['start'].values()]
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    bw, bh = max(xs) - min(xs) + 120, max(ys) - min(ys) + 120
    # feet must stay readable: at least MIN_SCALE px per cm; the camera follows the couple when the whole sequence does not fit
    scale = min(W / bw, H / bh)
    follow = False
    if scale < MIN_SCALE:
        follow, scale = True, MIN_SCALE
    scale = min(scale, MAX_SCALE)
    cx0, cy0 = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    # per-frame beat time and centroid (for the following camera, smoothed symmetrically over +-1.5 s)
    frame_beats = []
    inv = []  # seconds -> beats: invert beat_seconds by search
    beats = grid['beats']
    for f in range(nframes):
        ts = f / a.fps
        # beat index at time ts (fractional), relative to k0
        j = max(0, min(len(beats) - 2, next((i for i in range(len(beats) - 1) if beats[i + 1] > ts), len(beats) - 2)))
        b = j + (ts - beats[j]) / (beats[j + 1] - beats[j])
        frame_beats.append(b - k0)
    cents = []
    for b in frame_beats:
        ps = []
        for d in 'LF':
            for f_ in 'LR':
                p = tracks[d]['tracks'][f_].placement_at(b); ps.append((p[0], p[1]))
        cents.append((sum(p[0] for p in ps) / 4, sum(p[1] for p in ps) / 4))
    if follow:
        r = int(1.5 * a.fps)
        sm = []
        for i in range(nframes):
            lo, hi = max(0, i - r), min(nframes, i + r + 1)
            sm.append((sum(c[0] for c in cents[lo:hi]) / (hi - lo), sum(c[1] for c in cents[lo:hi]) / (hi - lo)))
        cents = sm
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    if a.frames_dir: os.makedirs(a.frames_dir, exist_ok=True)
    dur = t_end_s
    cmd = ['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}', '-r', str(a.fps), '-i', '-',
           '-ss', '0', '-t', f'{dur:.3f}', '-i', mp3, '-map', '0:v', '-map', '1:a', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '20',
           '-c:a', 'aac', '-b:a', '160k', '-shortest', a.out]
    errlog = open(a.out + '.ffmpeg.log', 'w', encoding='utf-8')
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=errlog)
    font = draw._font(28); small = draw._font(18); big = draw._font(64)
    broken = False
    for f in range(nframes):
        if broken:
            break
        b = frame_beats[f]
        cx, cy = cents[f] if follow else (cx0, cy0)
        view = draw.View(cx, cy, scale, W, H)
        img = Image.new('RGBA', (W, H), BG)
        d = ImageDraw.Draw(img)
        g = 50
        x0 = math.floor((cx - W / scale / 2) / g) * g
        while x0 < cx + W / scale / 2:
            p = view.px((x0, 0)); d.line([(p[0], 0), (p[0], H)], fill=(232, 229, 220)); x0 += g
        y0 = math.floor((cy - H / scale / 2) / g) * g
        while y0 < cy + H / scale / 2:
            p = view.px((0, y0)); d.line([(0, p[1]), (W, p[1])], fill=(232, 229, 220)); y0 += g
        feet = {}; weights = {}
        for dn in 'LF':
            for fo in 'LR':
                feet[f'{dn}.{fo}'] = tracks[dn]['tracks'][fo].placement_at(b)
            weights[dn] = weights_at(tracks[dn], b)
        bodies = {'L': draw.body({'L': feet['L.L'], 'R': feet['L.R']}), 'F': draw.body({'L': feet['F.L'], 'R': feet['F.R']})}
        if draw.SHOW_BODIES:
            draw.draw_body(img, view, bodies['L'], draw.LEADER_RGB, alpha=40)
            draw.draw_body(img, view, bodies['F'], draw.FOLLOWER_RGB, alpha=40)
        for conn in (hands_at(comp, b) if draw.SHOW_HANDS else []):
            for (dn, hs, sh, hand, raised, ok) in draw.resolve_hand(conn, bodies):
                draw.draw_arm(img, view, dn, hs, sh, hand, raised, ok, alpha=230)
        # draw the travelling (weightless) feet first so landed feet sit on top
        order = sorted(feet.keys(), key=lambda k: weights[k[0]][k[2]])
        for key in order:
            dn, fo = key.split('.')
            x, y, h = feet[key]
            draw.draw_foot(img, view, x, y, h, fo, draw.LEADER_RGB if dn == 'L' else draw.FOLLOWER_RGB, weights[dn][fo])
        name, e = label_at(comp, b)
        if b < 0:
            name = 'ready: ' + comp['elements'][0]['start_state'].replace('_', ' ') + ' position'
        elif name is None:
            name = 'end: ' + comp['end_state'].replace('_', ' ') + ' position'
        d = ImageDraw.Draw(img)
        d.text((24, 18), name, fill=(30, 30, 30), font=font)
        if e is not None:
            d.text((W - 24, 12), count_label(comp, b, swing), fill=(30, 30, 30), font=big, anchor='ra')
        d.text((24, H - 30), 'leader blue, follower pink; opacity = weight; the pointed toe is on the big-toe side', fill=(110, 110, 110), font=small)
        frame = img.convert('RGB')
        try:
            proc.stdin.write(frame.tobytes())
        except (BrokenPipeError, OSError):
            broken = True
        if a.frames_dir:
            frame.save(os.path.join(a.frames_dir, f'f{f:05d}.png'))
    try:
        proc.stdin.close()
    except OSError:
        pass
    proc.wait(); errlog.close()
    ff = open(a.out + '.ffmpeg.log', encoding='utf-8', errors='replace').read().strip()
    try:
        os.remove(a.out + '.ffmpeg.log')
    except OSError:
        pass
    if proc.returncode or broken:
        print(f"ffmpeg failed (exit {proc.returncode}{', pipe closed early' if broken else ''}): {ff or 'no message'}")
        sys.exit(1)
    print(f"wrote {a.out}: {nframes} frames, {dur:.1f} s, {len(comp['elements'])} elements, {comp['length']:g} beats from beat {k0} of {TRACKS[a.track]}")
    if truncated:
        print(f"NOTE: the track ends at {truncated[1]:.0f} s but the dance runs to {truncated[0]:.0f} s; the video stops where the music does. Use a longer track (Hot Swing is 5:15) for the whole thing.")

if __name__ == '__main__':
    main()

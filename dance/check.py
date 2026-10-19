"""Check every skeleton against the user's hard rules and the notation, and draw storyboards for review by eye.

    python dance/check.py                 # report (exit 1 on any error)
    python dance/check.py --storyboard    # also writes dance/out/storyboard_<element>_<state>_<free>.png
    python dance/check.py --element circle

Rules (handover-what-i-want.md): a weighted foot never moves or rotates; a dancer's feet are never more than 70 cm apart;
foot circles (diam 20) of the two dancers never overlap; body circles (diam 20) never overlap; a close hold the notes
describe explicitly is exempt (user Q6), and the exempted steps are listed. No correction layers: a failure means the data
is wrong.
"""
import sys, os, re, math, argparse
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import model
from model import dist, right, add, norm_angle, STANCE, MAX_SPREAD, FOOT_DIAM, BODY_DIAM, ARM_MAX
import draw

OWN_FEET_MIN = 10.0   # own feet may touch; below this they overlap (agent threshold, warn only)
END_TOL_CM, END_TOL_DEG = 3.0, 10.0
FREE_FOOT_WARN = 30.0

ROT_RE = re.compile(r'(\d)([LR])$')

def step_rotation(code):
    m = ROT_RE.search(code)
    if not m:
        return 0.0
    return int(m.group(1)) * 45.0 * (1 if m.group(2) == 'R' else -1)

def is_replace(code):
    c = code[1:] if code.startswith('e') else code
    return c.startswith('R') and not c.startswith('RR') is None

def step_type_replace(code):
    c = code[1:] if code.startswith('e') else code
    return len(c) >= 2 and c[0] == 'R' and c[1] in 'LR'

def moments(elem, states):
    """Yield (count, feet) with feet = {'L.L':(x,y,h),...,'weighted':{'L':'R','F':'L'}, 'exempt':{'L.L':bool..}} after each count's landings."""
    feet = dict(model.state_feet({'states': states}, elem['start_state']))
    exempt = {k: False for k in feet}
    weighted = {'L': 'R' if elem.get('leader_free_start', 'L') == 'L' else 'L'}
    weighted['F'] = 'L' if weighted['L'] == 'R' else 'R'
    by_count = []
    for s in elem['steps']:
        if not by_count or by_count[-1][0] != s['count']:
            by_count.append((s['count'], []))
        by_count[-1][1].append(s)
    for count, ss in by_count:
        for s in ss:
            key = f"{s['dancer']}.{s['foot']}"
            feet[key] = (s['x'], s['y'], s['heading'])
            exempt[key] = bool(s.get('exempt'))
            weighted[s['dancer']] = s['foot']
        yield count, dict(feet), dict(weighted), dict(exempt)

def check_element(elem, states, out):
    errs, warns = [], []
    name = f"{elem['name']} [{elem['start_state']} -> {elem['end_state']}, leader free {elem.get('leader_free_start','L')}]"
    nominal = model.state_feet({'states': states}, elem['start_state'])
    # 1. structure: alternation, replace in place, rotation digits
    free = {'L': elem.get('leader_free_start', 'L')}
    free['F'] = 'R' if free['L'] == 'L' else 'L'
    last_pos = dict(nominal)
    last_heading = {'L': 0.0, 'F': nominal['F.L'][2]}
    for s in elem['steps']:
        d, f, code = s['dancer'], s['foot'], s.get('code', '')
        if f != free[d]:
            errs.append(f"count {s['count']}: {d} steps on {f} but the free foot is {free[d]} (code {code})")
        free[d] = 'R' if f == 'L' else 'L'
        key = f"{d}.{f}"
        # the notation checks verify the agent's transcription of the notes; a step the user placed (src 'user') is the
        # user's own statement, so for it they only warn
        notation = warns if s.get('src') == 'user' else errs
        if step_type_replace(code):
            px, py, ph = last_pos[key]
            if dist((px, py), (s['x'], s['y'])) > 1.0 or abs(norm_angle(ph - s['heading'])) > 1.0:
                notation.append(f"count {s['count']}: {d}.{f} code {code} is a replace but the foot moved from ({px:.0f},{py:.0f},{ph:.0f}) to ({s['x']:.0f},{s['y']:.0f},{s['heading']:.0f})")
        rot = step_rotation(code)
        got = norm_angle(s['heading'] - last_heading[d])
        if abs(norm_angle(got - rot)) > 1.0:
            notation.append(f"count {s['count']}: {d}.{f} code {code} says rotation {rot:+.0f} but the heading changes {got:+.0f} (from {last_heading[d]:.0f} to {s['heading']:.0f})")
        last_heading[d] = s['heading']
        last_pos[key] = (s['x'], s['y'], s['heading'])
        if 'code' not in s or not code:
            warns.append(f"count {s['count']}: {d}.{f} has no code")
    # 2. geometry at every count
    exempted = []
    for count, feet, weighted, exempt in moments(elem, states):
        for d in 'LF':
            a, b = feet[f'{d}.L'], feet[f'{d}.R']
            sp = dist(a, b)
            if sp > MAX_SPREAD + 1e-6:
                errs.append(f"count {count}: {d}'s feet are {sp:.0f} cm apart (max {MAX_SPREAD:.0f})")
            if sp < OWN_FEET_MIN:
                warns.append(f"count {count}: {d}'s feet are only {sp:.0f} cm apart")
        anyex = any(exempt.values())
        for lf in ('L.L', 'L.R'):
            for ff in ('F.L', 'F.R'):
                dd = dist(feet[lf], feet[ff])
                if dd < FOOT_DIAM - 1e-6:
                    if exempt[lf] or exempt[ff]:
                        exempted.append(f"count {count}: {lf} and {ff} {dd:.0f} cm apart, exempt (close hold in the notes)")
                    else:
                        errs.append(f"count {count}: feet collide: {lf} and {ff} are {dd:.0f} cm apart (min {FOOT_DIAM:.0f})")
        bl = draw.body({'L': feet['L.L'], 'R': feet['L.R']})
        bf = draw.body({'L': feet['F.L'], 'R': feet['F.R']})
        bd = dist(bl, bf)
        if bd < BODY_DIAM - 1e-6:
            if anyex:
                exempted.append(f"count {count}: bodies {bd:.0f} cm apart, exempt (close hold in the notes)")
            else:
                errs.append(f"count {count}: bodies collide: centres {bd:.0f} cm apart (min {BODY_DIAM:.0f})")
    # 3. hands reach
    hands = list(states[elem['start_state']].get('hands', []))
    changes = {str(h['count']): h['hands'] for h in elem.get('hands', [])}
    for count, feet, weighted, exempt in moments(elem, states):
        if count in changes:
            hands = list(changes[count])
        bodies = {'L': draw.body({'L': feet['L.L'], 'R': feet['L.R']}), 'F': draw.body({'L': feet['F.L'], 'R': feet['F.R']})}
        for conn in hands:
            for (d, hs, sh, hand, raised, ok) in draw.resolve_hand(conn, bodies):
                reach = dist(sh, hand)
                if not ok:
                    errs.append(f"count {count}: hand {conn}: {d}'s {hs} shoulder is {reach:.0f} cm from the hand point (arm max {ARM_MAX:.0f})")
    # 4. end state
    end_frame = model.element_end_frame(elem)
    nom_end = model.state_feet({'states': states}, elem['end_state'])
    last_feet = None
    for count, feet, weighted, exempt in moments(elem, states):
        last_feet, last_w = feet, weighted
    for key, p in nom_end.items():
        wx, wy, wh = end_frame.apply(p)
        ax, ay, ah = last_feet[key]
        d, f = key.split('.')
        off = dist((wx, wy), (ax, ay)); dh = abs(norm_angle(wh - ah))
        if last_w[d] == f:
            if off > END_TOL_CM or dh > END_TOL_DEG:
                errs.append(f"end: weighted foot {key} is at ({ax:.0f},{ay:.0f},{ah:.0f}) but the {elem['end_state']} state puts it at ({wx:.0f},{wy:.0f},{wh:.0f})")
        else:
            if off > FREE_FOOT_WARN or dh > 45:
                warns.append(f"end: free foot {key} rests at ({ax:.0f},{ay:.0f},{ah:.0f}), {off:.0f} cm from its {elem['end_state']} spot ({wx:.0f},{wy:.0f},{wh:.0f}); the next element moves it from there")
    # 5. beats vs counts
    span = max(model.count_time(s['count'], 1.0) for s in elem['steps']) - model.element_base_count(elem) + 1
    if span != elem['beats']:
        errs.append(f"counts span {span:g} beats but the element says {elem['beats']}")
    out.append((name, errs, warns, exempted))
    return errs, warns

def storyboard(elem, states, path):
    frames = list(moments(elem, states))
    allpts = [p for _, feet, _, _ in frames for p in feet.values()]
    for v in model.state_feet({'states': states}, elem['start_state']).values():
        allpts.append(v)
    xs = [p[0] for p in allpts]; ys = [p[1] for p in allpts]
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    span = max(max(xs) - min(xs), max(ys) - min(ys)) + 70
    panel = 300
    scale = panel / span
    n = len(frames) + 1
    cols = min(n, 5); rows = math.ceil(n / cols)
    img = Image.new('RGBA', (cols * panel, rows * (panel + 24)), (250, 250, 248, 255))
    fnt = draw._font(14)
    hands = list(states[elem['start_state']].get('hands', []))
    changes = {str(h['count']): h['hands'] for h in elem.get('hands', [])}
    start_feet = model.state_feet({'states': states}, elem['start_state'])
    w0 = {'L': 'R' if elem.get('leader_free_start', 'L') == 'L' else 'L'}
    w0['F'] = 'L' if w0['L'] == 'R' else 'R'
    seq = [('start', start_feet, w0, {k: False for k in start_feet})] + frames
    for i, (count, feet, weighted, exempt) in enumerate(seq):
        r, c = divmod(i, cols)
        sub = Image.new('RGBA', (panel, panel), (255, 255, 255, 255))
        view = draw.View(cx, cy, scale, panel, panel)
        d = ImageDraw.Draw(sub)
        # grid every 20 cm
        g = 20
        x0 = math.floor((cx - span / 2) / g) * g
        while x0 < cx + span / 2:
            p = view.px((x0, 0)); d.line([(p[0], 0), (p[0], panel)], fill=(235, 235, 235)); x0 += g
        y0 = math.floor((cy - span / 2) / g) * g
        while y0 < cy + span / 2:
            p = view.px((0, y0)); d.line([(0, p[1]), (panel, p[1])], fill=(235, 235, 235)); y0 += g
        if count in changes and count != 'start':
            hands = list(changes[count])
        bodies = {'L': draw.body({'L': feet['L.L'], 'R': feet['L.R']}), 'F': draw.body({'L': feet['F.L'], 'R': feet['F.R']})}
        draw.draw_body(sub, view, bodies['L'], draw.LEADER_RGB)
        draw.draw_body(sub, view, bodies['F'], draw.FOLLOWER_RGB)
        for conn in hands:
            for (dn, hs, sh, hand, raised, ok) in draw.resolve_hand(conn, bodies):
                draw.draw_arm(sub, view, dn, hs, sh, hand, raised, ok, alpha=200)
        for key, (x, y, h) in feet.items():
            dn, f = key.split('.')
            rgb = draw.LEADER_RGB if dn == 'L' else draw.FOLLOWER_RGB
            # the start panel is the neutral stance: the weight on both feet (user 2026-10-19)
            wt = 0.5 if count == 'start' else (1.0 if weighted[dn] == f else 0.0)
            outline = (200, 0, 0) if exempt.get(key) else None
            draw.draw_foot(sub, view, x, y, h, f, rgb, wt, outline=outline)
        img.paste(sub, (c * panel, r * (panel + 24)))
        label = 'start (neutral stance)' if count == 'start' else f"count {count}"
        ImageDraw.Draw(img).text((c * panel + 6, r * (panel + 24) + panel + 4), label, fill=(0, 0, 0), font=fnt)
    img.convert('RGB').save(path)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--storyboard', action='store_true')
    ap.add_argument('--element', default=None)
    ap.add_argument('--file', default=model.SKELETONS)
    a = ap.parse_args()
    data = model.load(a.file)
    states = data['states']
    out = []
    nerr = 0
    for e in data['elements']:
        if a.element and e['name'] != a.element:
            continue
        errs, warns = check_element(e, states, out)
        nerr += len(errs)
        if a.storyboard:
            os.makedirs(os.path.join(model.HERE, 'out'), exist_ok=True)
            fn = f"storyboard_{e['name'].replace(' ', '_')}_{e['start_state']}_{e.get('leader_free_start','L')}.png"
            storyboard(e, states, os.path.join(model.HERE, 'out', fn))
    for name, errs, warns, exempted in out:
        status = 'FAIL' if errs else 'ok'
        print(f"[{status}] {name}")
        for x in errs: print('   ERROR', x)
        for x in warns: print('   warn ', x)
        for x in exempted: print('   exempt', x)
    print(f"{nerr} errors in {len(out)} skeletons")
    sys.exit(1 if nerr else 0)

if __name__ == '__main__':
    main()

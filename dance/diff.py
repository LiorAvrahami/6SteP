"""Print what the user changed in the editor: skeletons.json against skeletons.baseline.json, step by step.

    python dance/diff.py            # human-readable report (the user's message to the agent)
    python dance/diff.py --json     # machine-readable
    python dance/diff.py --accept   # after the agent has read and answered the diff: copy the current file over the baseline

Elements are matched by (name, start_state, leader_free_start); steps by their index in the element.
"""
import sys, os, json, argparse, shutil, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import model
from model import norm_angle

def key(e):
    return (e['name'], e['start_state'], e.get('leader_free_start', 'L'))

def diff(cur, base):
    out = []
    bmap = {key(e): e for e in base['elements']}
    for e in cur['elements']:
        b = bmap.get(key(e))
        if b is None:
            out.append({'element': key(e), 'new_element': True, 'steps': []}); continue
        changes = []
        for i, s in enumerate(e['steps']):
            if i >= len(b['steps']):
                changes.append({'index': i, 'added': True, 'step': s}); continue
            t = b['steps'][i]
            dx, dy, dh = s['x'] - t['x'], s['y'] - t['y'], norm_angle(s['heading'] - t['heading'])
            if abs(dx) > 0.01 or abs(dy) > 0.01 or abs(dh) > 0.01:
                changes.append({'index': i, 'count': s['count'], 'dancer': s['dancer'], 'foot': s['foot'], 'code': s.get('code', ''),
                                'from': (t['x'], t['y'], t['heading']), 'to': (s['x'], s['y'], s['heading']),
                                'dx': dx, 'dy': dy, 'dheading': dh})
        for h_cur, h_base in [(e.get('hands', []), b.get('hands', []))]:
            if json.dumps(h_cur, sort_keys=True) != json.dumps(h_base, sort_keys=True):
                changes.append({'hands': True, 'from': h_base, 'to': h_cur})
        if changes:
            out.append({'element': key(e), 'steps': changes})
    for k in bmap:
        if not any(key(e) == k for e in cur['elements']):
            out.append({'element': k, 'removed_element': True, 'steps': []})
    return out

def fmt(p):
    return f"({p[0]:.0f},{p[1]:.0f},{p[2]:.0f})"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--json', action='store_true')
    ap.add_argument('--accept', action='store_true')
    a = ap.parse_args()
    cur, base = model.load(model.SKELETONS), model.load(model.BASELINE)
    d = diff(cur, base)
    if a.json:
        print(json.dumps(d, indent=1))
    elif not d:
        print('no differences between skeletons.json and the baseline')
    else:
        n = 0
        for e in d:
            name, st, free = e['element']
            tag = ' (new element)' if e.get('new_element') else (' (removed)' if e.get('removed_element') else '')
            print(f"{name} [from {st}, leader free {free}]{tag}")
            for c in e['steps']:
                n += 1
                if c.get('hands'):
                    print(f"   hands: {c['from']} -> {c['to']}"); continue
                if c.get('added'):
                    print(f"   step {c['index']} added: {c['step']}"); continue
                parts = []
                if abs(c['dx']) > 0.01 or abs(c['dy']) > 0.01:
                    parts.append(f"moved {math.hypot(c['dx'], c['dy']):.0f} cm (dx {c['dx']:+.0f}, dy {c['dy']:+.0f})")
                if abs(c['dheading']) > 0.01:
                    parts.append(f"rotated {c['dheading']:+.0f} deg")
                print(f"   count {c['count']:<3} {c['dancer']}.{c['foot']} {c['code']:<8} {fmt(c['from'])} -> {fmt(c['to'])}   {', '.join(parts)}")
        print(f"{n} changed items in {len(d)} elements")
    if a.accept:
        shutil.copyfile(model.SKELETONS, model.BASELINE)
        print('baseline updated from skeletons.json')

if __name__ == '__main__':
    main()

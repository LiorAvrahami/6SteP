"""Shared model for the Swing5 footwork skeletons.

Coordinates: centimetres, top view. x to the leader's starting right, y to the
leader's starting front. Heading in degrees, 0 = facing +y, positive =
clockwise seen from above (lurklurk's "R" rotation is positive).

A placement is (x, y, heading) of one foot's centre. A step moves one foot to a
placement and transfers the weight to it on its count.
"""
import json, math, copy, os, re

HERE = os.path.dirname(os.path.abspath(__file__))
# The skeletons since 2026-10-25: a folder, one file per element, so that several people can edit different elements at once
# (and git merges them): skeletons/_states.json (the states and the rest of the document) and skeletons/elements/<name>__<start
# state>__<leader's free foot>.json. load() assembles the usual document {states, ..., elements}; save() writes back only the
# elements that changed and removes the files of elements that are gone. A plain .json path still works (old files, fixtures).
SKELETONS = os.path.join(HERE, 'skeletons')
LEGACY_SKELETONS = os.path.join(HERE, 'skeletons.json')
STATES_FILE = '_states.json'
STATE_ORDER = {'side': 0, 'facing': 1, 'reversed_side': 2}

def element_file(e):
    """The file name of an element: its name (letters and digits, the rest as _), start state and the leader's free foot."""
    nm = re.sub(r'[^A-Za-z0-9]+', '_', e['name']).strip('_') or 'element'
    return f"{nm}__{e['start_state']}__{e.get('leader_free_start', 'L')}.json"

def skeletons_version(path=SKELETONS):
    """Changes whenever any of the files changes (the editor uses it to see that someone else wrote meanwhile)."""
    if not os.path.isdir(path):
        return str(os.stat(path).st_mtime_ns) if os.path.exists(path) else ''
    parts = []
    for root in (path, os.path.join(path, 'elements')):
        if os.path.isdir(root):
            for f in sorted(os.listdir(root)):
                if f.endswith('.json'):
                    st = os.stat(os.path.join(root, f)); parts.append(f'{f}:{st.st_mtime_ns}:{st.st_size}')
    import hashlib
    return hashlib.sha1('|'.join(parts).encode()).hexdigest()[:16]

def _dump(obj):
    return json.dumps(obj, indent=1, ensure_ascii=False) + '\n'
COMPOSITIONS = os.path.join(HERE, 'compositions.json')

STANCE = 20.0        # natural distance between a dancer's feet (user rule)
MAX_SPREAD = 70.0    # a dancer's two feet are never further apart (user rule)
FOOT_DIAM = 20.0     # collision circle on every foot (user rule until 2026-10-19; replaced by MIN_GAP)
MIN_GAP = 4.0        # cm: the shoe outlines of the two dancers stay at least this far apart (user 2026-10-19)
BODY_DIAM = 20.0     # collision circle at each dancer's midpoint (user rule)
SHOULDER_HALF = 18.0 # shoulder offset from the body centre, each side (agent guess)
ARM_MAX = 65.0       # shoulder to hand, upper 32.5 + forearm 32.5 (agent guess)
DEFAULT_SWING = 0.67 # where the "&" lands inside its beat (music/README.md)

def fwd(h):
    r = math.radians(h); return (math.sin(r), math.cos(r))
def right(h):
    r = math.radians(h); return (math.cos(r), -math.sin(r))
def add(p, v, k=1.0):
    return (p[0] + k * v[0], p[1] + k * v[1])
def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])
def norm_angle(a):
    return (a + 180.0) % 360.0 - 180.0

def count_time(count, swing=DEFAULT_SWING):
    """Beat offset inside an element for a count label: '1'..'8' or '3&' (the & after count 3)."""
    c = str(count)
    if c.endswith('&'):
        return int(c[:-1]) - 1 + swing
    return int(c) - 1

class Frame:
    """A rigid transform: local coordinates of a skeleton -> world. Defined by
    where the leader's left foot would stand in natural stance, and its heading."""
    def __init__(self, x=0.0, y=0.0, h=0.0):
        self.x, self.y, self.h = x, y, h
    def apply(self, p):
        x, y, h = p
        r = math.radians(self.h)
        c, s = math.cos(r), math.sin(r)
        # local +y is forward (heading self.h); local +x is the leader's right
        f = fwd(self.h); rt = right(self.h)
        wx = self.x + x * rt[0] + y * f[0]
        wy = self.y + x * rt[1] + y * f[1]
        return (wx, wy, h + self.h)

def load(path=SKELETONS):
    if not os.path.isdir(path):
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    with open(os.path.join(path, STATES_FILE), encoding='utf-8') as f:
        doc = json.load(f)
    elements = []
    edir = os.path.join(path, 'elements')
    for fn in sorted(os.listdir(edir)) if os.path.isdir(edir) else []:
        if fn.endswith('.json'):
            with open(os.path.join(edir, fn), encoding='utf-8') as f:
                elements.append(json.load(f))
    # the order of the list: each element keeps its 'order' number (given once, when it is first saved)
    elements.sort(key=lambda e: (e.get('order', 1e9), e['name'].lower(), STATE_ORDER.get(e['start_state'], 9), e.get('leader_free_start', 'L')))
    doc['elements'] = elements
    return doc

def save(data, path=SKELETONS):
    if not os.path.isdir(path) and path != SKELETONS:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(_dump(data))
        return
    edir = os.path.join(path, 'elements'); os.makedirs(edir, exist_ok=True)
    names = [element_file(e) for e in data['elements']]
    dup = {n for n in names if names.count(n) > 1}
    if dup:
        raise ValueError(f'two elements would share the file {sorted(dup)[0]}: rename one')
    head = {k: v for k, v in data.items() if k != 'elements'}
    def write(p, text):
        old = open(p, encoding='utf-8').read() if os.path.exists(p) else None
        if old != text:
            with open(p, 'w', encoding='utf-8') as f:
                f.write(text)
    write(os.path.join(path, STATES_FILE), _dump(head))
    top = max([e['order'] for e in data['elements'] if isinstance(e.get('order'), (int, float))] + [0])
    for e, fn in zip(data['elements'], names):
        if not isinstance(e.get('order'), (int, float)):
            top += 1; e['order'] = top
        write(os.path.join(edir, fn), _dump(e))
    for fn in os.listdir(edir):
        if fn.endswith('.json') and fn not in names:
            os.remove(os.path.join(edir, fn))

def import_legacy():
    """An old single skeletons.json next to the folder (written by an editor that was still running old code): its elements
    that differ from the folder's are taken (they are the newer edits), none is deleted; the file is then moved to
    out/backups. Returns the elements taken."""
    if not os.path.exists(LEGACY_SKELETONS):
        return []
    with open(LEGACY_SKELETONS, encoding='utf-8') as f:
        old = json.load(f)
    taken = []
    if os.path.isdir(SKELETONS):
        doc = load()
        at = {element_file(e): i for i, e in enumerate(doc['elements'])}
        for e in old['elements']:
            fn = element_file(e); e = dict(e)
            if fn in at:
                e.setdefault('order', doc['elements'][at[fn]].get('order'))
                if json.dumps(e, sort_keys=True) != json.dumps(doc['elements'][at[fn]], sort_keys=True):
                    doc['elements'][at[fn]] = e; taken.append(fn)
            else:
                doc['elements'].append(e); taken.append(fn)
    else:
        doc = old; taken = [element_file(e) for e in old['elements']]
    save(doc)
    bdir = os.path.join(HERE, 'out', 'backups'); os.makedirs(bdir, exist_ok=True)
    import time as _t
    os.replace(LEGACY_SKELETONS, os.path.join(bdir, 'legacy_skeletons_' + _t.strftime('%Y-%m-%d_%H-%M-%S') + '.json'))
    return taken

def state_feet(data, state):
    """Nominal placements of the four feet in a facing state, local frame: dict 'L.L','L.R','F.L','F.R' -> (x,y,h)."""
    return {k: tuple(v) for k, v in data['states'][state]['feet'].items()}

def find_element(data, name, start_state, leader_free='L'):
    for e in data['elements']:
        if e['name'] == name and e['start_state'] == start_state and e.get('leader_free_start', 'L') == leader_free:
            return e
    return None

COUNT_RE = re.compile(r'^\d+&?$')

def element_problem(elem):
    """Why an element cannot be danced or checked yet (an element being built in the editor), or None.
    Every tool calls this before it reads an element's steps."""
    steps = elem.get('steps') or []
    bad = [s.get('count') for s in steps if not COUNT_RE.match(str(s.get('count', '')))]
    if bad:
        return f"count {bad[0]!r} is not a count label (1, 2, 3&, ...)"
    for d, who in (('L', 'leader'), ('F', 'follower')):
        if not any(s.get('dancer') == d and not s.get('noweight') for s in steps):
            return f"no steps for the {who} yet"
    return None

def element_base_count(elem):
    """Beat offset of the element's first count (counts are the musical labels, e.g. a triple on 5&6 starts at count 5)."""
    return min(count_time(s['count'], 0.0) for s in elem['steps'])

def nominal_left_frame(foot, placement):
    """Given the leader's weighted foot and its placement, where his left foot stands in natural stance (the frame origin)."""
    x, y, h = placement
    if foot == 'L':
        return Frame(x, y, h)
    lx, ly = add((x, y), right(h), -STANCE)
    return Frame(lx, ly, h)

def element_end_frame(elem):
    """Frame at the end of an element (local coords of that element), from the leader's last weighted foot."""
    last = [s for s in elem['steps'] if s['dancer'] == 'L' and not s.get('noweight')][-1]
    return nominal_left_frame(last['foot'], (last['x'], last['y'], last['heading']))

def element_free_feet_end(elem):
    """Which foot is free for each dancer at the end of an element."""
    out = {}
    for d in 'LF':
        last = [s for s in elem['steps'] if s['dancer'] == d and not s.get('noweight')][-1]   # a kick or a point keeps the weight
        out[d] = 'R' if last['foot'] == 'L' else 'L'
    return out

def unwrap_element(data, e):
    """Headings keep their winding since 2026-10-25 (a foot turns from one heading to the next by the raw difference, so the
    user chooses clockwise or anticlockwise, and 370 is not 10). An element made before that is converted once: each step's
    heading becomes the shortest turn from that foot's previous heading, which is how it was danced until then; then it is
    marked 'winding'. Returns True when it was converted."""
    if e.get('winding'):
        return False
    feet = {k: v[2] for k, v in state_feet(data, e['start_state']).items()}
    for s in e['steps']:
        k = f"{s['dancer']}.{s['foot']}"
        h = feet[k] + norm_angle(s['heading'] - feet[k])
        h = round(h, 6)
        s['heading'] = int(h) if h == int(h) else h
        feet[k] = s['heading']
    e['winding'] = True
    return True

def unwrap_all(data):
    """Converts every element that is not marked 'winding' yet (see unwrap_element); the names of those converted."""
    return [f"{e['name']} [{e['start_state']}, {e.get('leader_free_start', 'L')}]" for e in data['elements'] if unwrap_element(data, e)]

def compose(data, sequence, start_state='side', swing=DEFAULT_SWING, leader_free=None, pad_to_loop=False, filler='triple step', groups=None):
    """Turn a list of element names into a world timeline.

    leader_free: which of the leader's feet is free at the start (default: the state's, i.e. the left).
    pad_to_loop: append filler elements (triple steps, picked by the composer from the end condition) until the leader's
    free foot is back where it started, so the sequence can be danced again straight after itself; filler elements are
    flagged 'filler'. A six-count, or a rock step alone, already closes on its own and gets none (user 2026-10-19: a rock
    step played alone must not turn into rock, triple, triple; the earlier rule also padded to at least a bar).

    Returns dict with 'steps' (t beats, dancer, foot, x, y, heading, element index, count, code, exempt, hands),
    'elements' (name, start beat, beats, states, free feet, filler flag), 'start' (feet at t=0), 'start_hands', 'end_state',
    'end_free', 'length', 'errors' (list of str).
    """
    errors = []
    feet = {k: v for k, v in state_feet(data, start_state).items()}
    last_h = {k: v[2] for k, v in feet.items()}   # each foot's last world heading, winding included
    frame = Frame(0, 0, 0)
    state = start_state
    free = {'L': leader_free or data['states'][start_state].get('leader_free', 'L')}
    free['F'] = 'R' if free['L'] == 'L' else 'L'
    start_free = free['L']
    hands = list(data['states'][start_state].get('hands', []))
    t = 0.0
    steps, elements = [], []

    def add(elem, name, is_filler, group=None):
        nonlocal frame, free, state, t, hands
        elements.append({'name': name, 'start': t, 'beats': elem['beats'], 'start_state': state, 'end_state': elem['end_state'],
                         'free': elem.get('leader_free_start', 'L'), 'end_free': element_free_feet_end(elem)['L'], 'filler': is_filler, 'group': group})
        base = element_base_count(elem)
        hand_changes = {str(hc['count']): hc['hands'] for hc in elem.get('hands', [])}
        seen = []
        # an element's headings assume its start state's headings: a foot that arrives wound by a full turn keeps it, instead of
        # spinning back on its first step
        st = state_feet(data, state)
        wind = {k: 360.0 * round((last_h[k] - (frame.h + st[k][2])) / 360.0) for k in last_h}
        for s in elem['steps']:
            c = str(s['count'])
            if c in hand_changes and c not in seen:
                hands = list(hand_changes[c]); seen.append(c)
            wx, wy, wh = frame.apply((s['x'], s['y'], s['heading']))
            wh += wind[f"{s['dancer']}.{s['foot']}"]
            last_h[f"{s['dancer']}.{s['foot']}"] = wh
            steps.append({'t': t + count_time(c, swing) - base, 'count': c, 'dancer': s['dancer'], 'foot': s['foot'],
                          'x': wx, 'y': wy, 'heading': wh, 'element': len(elements) - 1,
                          'exempt': bool(s.get('exempt')), 'hands': list(hands), 'filler': is_filler, 'noweight': bool(s.get('noweight'))})
        ef = element_end_frame(elem)
        frame = Frame(*frame.apply((ef.x, ef.y, ef.h)))
        free = element_free_feet_end(elem)
        state = elem['end_state']
        t += elem['beats']

    for idx, name in enumerate(sequence):
        elem = find_element(data, name, state, free['L'])
        if elem is None:
            other = find_element(data, name, state, 'R' if free['L'] == 'L' else 'L')
            where = f" (element {idx + 1} of {len(sequence)})"
            if other is not None:
                errors.append(f"{name!r} from {state!r} exists only with the leader's {other['leader_free_start']} foot free, but his {free['L']} is free{where}; stopping")
            else:
                errors.append(f"no skeleton for {name!r} starting from state {state!r}{where}; stopping")
            break
        problem = element_problem(elem)
        if problem:
            errors.append(f"{name!r} from {state!r}: {problem} (element {idx + 1} of {len(sequence)}); stopping")
            break
        add(elem, name, False, groups[idx] if groups and idx < len(groups) else None)
    # pad only an element that ends where it started (only then can fillers close the loop); one that ends elsewhere restarts
    # from its start each time, unpadded (user 2026-10-25: "we dance side to side without filling")
    if pad_to_loop and not errors and sequence and state == start_state:
        fillers = 0
        while free['L'] != start_free and fillers < 4:
            elem = find_element(data, filler, state, free['L'])
            if elem is None or element_problem(elem):
                break   # no filler for this condition: it plays unpadded (it restarts from its start), not an error
            add(elem, filler, True)
            fillers += 1
    steps.sort(key=lambda s: (s['t'], s['dancer']))
    return {'steps': steps, 'elements': elements, 'start': feet, 'start_hands': list(data['states'][start_state].get('hands', [])),
            'end_state': state, 'end_free': free['L'], 'length': t, 'errors': errors, 'start_free': start_free}

def load_compositions(path=COMPOSITIONS):
    with open(path, encoding='utf-8') as f:
        return json.load(f)['compositions']

def find_composition(name, path=COMPOSITIONS):
    for c in load_compositions(path):
        if c['name'] == name:
            return c
    return None

ENTRY_RE = re.compile(r'^(.*?)\s*(?:[xX×]\s*(\d+))?\s*$')

def parse_entry(entry):
    """'RTT x12' -> ('RTT', 12); 'rock step' -> ('rock step', 1); '(RTT x5)' -> ('RTT', 5). An entry in parentheses is
    quiet in the phone app (danced but never announced, not a swipe stop); here it is danced like any other."""
    t = str(entry).strip()
    if len(t) > 2 and t.startswith('(') and t.endswith(')'):
        t = t[1:-1].strip()
    m = ENTRY_RE.match(t)
    name, times = m.group(1).strip(), int(m.group(2) or 1)
    return name, max(1, times)

def load_subs(path=COMPOSITIONS):
    with open(path, encoding='utf-8') as f:
        return json.load(f).get('sub_compositions', [])

def expand_sequence(entries, subs, _depth=0):
    """Expand sub-composition names and 'x N' repeats into a flat list of (element name, group label or None).
    A group label names the sub-composition an element came from, e.g. 'RTT #3'."""
    by_name = {s['name']: s for s in (subs or [])}
    out = []
    counters = {}
    for entry in entries:
        if str(entry).strip().lower().startswith('say:'):
            continue  # an annotation (shown and read aloud by the phone app), no steps
        name, times = parse_entry(entry)
        for _ in range(times):
            if name in by_name and _depth < 6:
                counters[name] = counters.get(name, 0) + 1
                label = f"{name} #{counters[name]}"
                for el, _g in expand_sequence(by_name[name]['elements'], subs, _depth + 1):
                    out.append((el, label))
            else:
                out.append((name, None))
    return out

def composition_sequence(comp, subs=None):
    """The flat element list of a composition: its entries (sub-compositions and 'x N' expanded) repeated 'repeat' times."""
    flat = expand_sequence(comp['elements'], subs if subs is not None else load_subs())
    return [el for el, _ in flat] * int(comp.get('repeat', 1))

def composition_groups(comp, subs=None):
    flat = expand_sequence(comp['elements'], subs if subs is not None else load_subs())
    return [g for _, g in flat] * int(comp.get('repeat', 1))

"""Check the shared Kotlin dance engine against the Python model (dance/model.py), on the data the app ships.

It writes what the Python model makes of every composition, building block and element (the steps, their times and
places) to shared/build/engine_fixture.json, then runs the engine's own tests (gradle :shared:jvmTest) with that file:
EngineTest compares, step by step. Without the file the same tests still check that everything shipped can be danced.

Run: python tools/check_engine.py            (needs Gradle and a JDK; no Android SDK)
     python tools/check_engine.py --fixture  (only write the file)
"""
import json
import os
import subprocess
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
sys.path.insert(0, os.path.join(ROOT, 'dance'))
import model  # noqa: E402

SWING = 0.67


def composed(r):
    return {
        'errors': r['errors'], 'length': r['length'], 'end_state': r['end_state'], 'end_free': r['end_free'],
        'elements': [{'name': e['name'], 'start': e['start'], 'beats': e['beats'], 'start_state': e['start_state'], 'end_state': e['end_state']} for e in r['elements']],
        'steps': [{'t': s['t'], 'count': s['count'], 'dancer': s['dancer'], 'foot': s['foot'], 'x': s['x'], 'y': s['y'], 'heading': s['heading'],
                   'element': s['element'], 'via': s.get('via') or []} for s in r['steps']],
    }


def main():
    data = model.load()
    comps = model.load_compositions()
    comps = comps['compositions'] if isinstance(comps, dict) else comps
    subs = model.load_subs()
    out = {'swing': SWING, 'compositions': [], 'blocks': [], 'elements': []}
    for c in comps:
        r = model.compose(data, model.composition_sequence(c, subs), c['start_state'], swing=SWING, ends=model.composition_ends(c, subs))
        out['compositions'].append({'name': c['name'], 'composed': composed(r)})
    for c in subs:
        r = model.compose(data, model.composition_sequence(c, subs), c['start_state'], swing=SWING, ends=model.composition_ends(c, subs))
        out['blocks'].append({'name': c['name'], 'composed': composed(r)})
    for e in data['elements']:
        if model.element_problem(e):
            continue
        r = model.compose(data, [e['name']], e['start_state'], swing=SWING, leader_free=e.get('leader_free_start', 'L'), ends=[e['end_state']])
        out['elements'].append({'name': e['name'], 'start_state': e['start_state'], 'free': e.get('leader_free_start', 'L'), 'end_state': e['end_state'], 'composed': composed(r)})
    path = os.path.join(ROOT, 'shared', 'build', 'engine_fixture.json')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(out, f)
    print('the Python model: %d compositions, %d building blocks, %d elements -> %s' % (len(out['compositions']), len(out['blocks']), len(out['elements']), os.path.relpath(path, ROOT)))
    if '--fixture' in sys.argv:
        return
    env = dict(os.environ, SIXSTEP_SKIP_ANDROID='1', SIXSTEP_FIXTURE=path)
    sys.exit(subprocess.call(['gradle', '--no-daemon', '-q', ':shared:jvmTest', '--rerun-tasks'], cwd=ROOT, env=env))


if __name__ == '__main__':
    main()

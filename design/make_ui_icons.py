"""The app's interface icons: the Lucide icons of the user's layout draft (design/app_layout.free, 2026-10-28), converted
from the official SVGs in design/lucide/ (lucide-static 1.63.0, ISC licence, see design/lucide/LICENSE) into Android
vector drawables in app/src/main/res/drawable/ic_*.xml.

Every path is parsed and written out again with explicit separators (Android's path parser then never meets compact
forms such as ".707.707" or "a1 1 0 0 1-1 1"), circles and rounded rectangles become paths, and each command's argument
count is checked. Strokes are black with round caps and joins, as in the draft; the heart is filled red and the gear
grey, as the user drew them. Run: python design/make_ui_icons.py
"""
import os
import re
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'lucide')
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'res', 'drawable')

# drawable name: (lucide icon, stroke width in the 24-unit viewport, fills by element index)
# The draft's icons are drawn a little bolder than Lucide's 2: about 2.25 on the launcher and the heart page, about 2.9
# on the dance floor's small buttons (2.5 here, so that they stay light at phone size).
ICONS = {
    'ic_dumbbell': ('dumbbell', 2.25, {}),
    'ic_telescope': ('telescope', 2.25, {}),
    'ic_heart': ('heart', 2.25, {0: '#DE0707'}),
    'ic_settings': ('settings', 2.25, {0: '#A3A3A3', 1: '#DBDBDB'}),
    'ic_help': ('circle-question-mark', 2.25, {}),
    'ic_at': ('at-sign', 2.25, {}),
    'ic_globe': ('globe', 2.25, {}),
    'ic_dollar': ('circle-dollar-sign', 2.25, {}),
    'ic_play': ('play', 2.5, {}),
    'ic_pause': ('pause', 2.5, {}),
    'ic_volume': ('volume-2', 2.5, {}),
    'ic_volume_off': ('volume-off', 2.5, {}),
    'ic_schema': ('drafting-compass', 2.5, {}),
    'ic_flow': ('arrow-big-right-dash', 2.5, {}),
    'ic_pencil': ('pencil', 2.5, {}),
    'ic_back': ('undo-2', 2.5, {}),          # the dance floor's back button (user 2026-10-28)
    'ic_check': ('check', 3.0, {}),          # the selected version pill in explore (tinted white there)
    'ic_chevron_down': ('chevron-down', 2.5, {}),
}

ARGS = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'T': 2, 'A': 7, 'Z': 0}
NUM = re.compile(r'[-+]?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][-+]?\d+)?')


def parse(d):
    """[(command, [args])], one entry per command occurrence, implicit repeats made explicit"""
    out, i = [], 0
    while i < len(d):
        ch = d[i]
        if ch in ' ,\t\r\n':
            i += 1
            continue
        if ch.isalpha():
            if ch.upper() not in ARGS:
                raise ValueError('unknown command %r in %r' % (ch, d))
            out.append([ch, []])
            i += 1
            continue
        if not out:
            raise ValueError('number before any command in %r' % d)
        cmd, args = out[-1]
        n = ARGS[cmd.upper()]
        if n == 0:
            raise ValueError('number after Z in %r' % d)
        if len(args) == n:   # an implicit repeat; after a moveto it is a lineto
            rep = {'M': 'L', 'm': 'l'}.get(cmd, cmd)
            out.append([rep, []])
            cmd, args = out[-1]
        if cmd in 'Aa' and len(args) in (3, 4):   # the arc flags: one character each, separators optional
            if d[i] not in '01':
                raise ValueError('bad arc flag in %r' % d)
            args.append(d[i])
            i += 1
            continue
        m = NUM.match(d, i)
        if not m:
            raise ValueError('cannot read a number at %d of %r' % (i, d))
        args.append(m.group())
        i = m.end()
    for cmd, args in out:
        if len(args) != ARGS[cmd.upper()]:
            raise ValueError('%s has %d arguments, needs %d, in %r' % (cmd, len(args), ARGS[cmd.upper()], d))
    return out


def num(s):
    v = float(s)
    t = ('%.4f' % v).rstrip('0').rstrip('.')
    return '0' if t in ('-0', '') else t


def serialize(cmds):
    return ' '.join(c + (' ' + ' '.join(num(a) for a in args) if args else '') for c, args in cmds)


def circle(cx, cy, r):
    return 'M%s,%s a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 %s,0 Z' % (cx - r, cy, r, r, 2 * r, r, r, -2 * r)


def rect(x, y, w, h, rx):
    if rx <= 0:
        return 'M%s,%s H%s V%s H%s Z' % (x, y, x + w, y + h, x)
    return ('M%s,%s H%s A%s,%s 0 0 1 %s,%s V%s A%s,%s 0 0 1 %s,%s H%s A%s,%s 0 0 1 %s,%s V%s A%s,%s 0 0 1 %s,%s Z'
            % (x + rx, y, x + w - rx, rx, rx, x + w, y + rx, y + h - rx, rx, rx, x + w - rx, y + h, x + rx,
               rx, rx, x, y + h - rx, y + rx, rx, rx, x + rx, y))


def shapes(svg_path):
    root = ET.parse(svg_path).getroot()
    out = []
    for el in root:
        tag = el.tag.split('}')[-1]
        a = {k: float(v) for k, v in el.attrib.items() if k in ('cx', 'cy', 'r', 'x', 'y', 'width', 'height', 'rx', 'x1', 'y1', 'x2', 'y2')}
        if tag == 'path':
            out.append(el.attrib['d'])
        elif tag == 'circle':
            out.append(circle(a['cx'], a['cy'], a['r']))
        elif tag == 'rect':
            out.append(rect(a.get('x', 0), a.get('y', 0), a['width'], a['height'], a.get('rx', 0)))
        elif tag == 'line':
            out.append('M%s,%s L%s,%s' % (a['x1'], a['y1'], a['x2'], a['y2']))
        elif tag in ('polyline', 'polygon'):
            pts = el.attrib['points'].split()
            out.append('M' + ' L'.join(pts) + (' Z' if tag == 'polygon' else ''))
        else:
            raise ValueError('unhandled element %s in %s' % (tag, svg_path))
    return out


def main():
    for name, (icon, width, fills) in ICONS.items():
        ds = shapes(os.path.join(SRC, icon + '.svg'))
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 '<!-- Lucide "%s" (ISC, design/lucide/LICENSE); generated by design/make_ui_icons.py, do not edit -->' % icon,
                 '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
                 '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">']
        for k, d in enumerate(ds):
            cmds = parse(d)
            arcs = [len(args) for c, args in cmds if c in 'Aa']
            assert all(n == 7 for n in arcs)
            lines.append('    <path android:pathData="%s"' % serialize(cmds))
            lines.append('        android:fillColor="%s" android:strokeColor="#000000" android:strokeWidth="%s"'
                         % (fills.get(k, '#00000000'), width))
            lines.append('        android:strokeLineCap="round" android:strokeLineJoin="round" />')
        lines.append('</vector>')
        with open(os.path.join(OUT, name + '.xml'), 'w', encoding='utf-8', newline='\n') as f:
            f.write('\n'.join(lines) + '\n')
        print('%-16s %-22s %d path(s)' % (name, icon, len(ds)))


if __name__ == '__main__':
    main()

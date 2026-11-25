"""The share page's QR code (user 2026-11-25: "I want this app to be easy for people to share between one another"):
the web page's address, Texts.WEB_APP_URL in shared/src/commonMain/kotlin/org/lior/sixstep/Texts.kt, made into a QR
code by segno (pip install segno) and written out for both fronts:

- web/src/jsMain/resources/share_qr.svg, for the web page;
- app/src/main/res/drawable/share_qr.xml, a vector drawable for the Android app.

Black squares on white, with the standard quiet zone of four modules around them; each row's runs of dark modules are
one rectangle each. Both files name the address in their first comment, and ShareTest checks it against Texts: run
this again whenever the address changes.

Run: python tools/make_qr.py
"""
import os
import re
import sys

import segno

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
TEXTS = os.path.join(ROOT, 'shared', 'src', 'commonMain', 'kotlin', 'org', 'lior', 'sixstep', 'Texts.kt')
WEB = os.path.join(ROOT, 'web', 'src', 'jsMain', 'resources', 'share_qr.svg')
APP = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable', 'share_qr.xml')
QUIET = 4          # modules of white around the code, as the standard asks
APP_SIZE_DP = 220  # the drawable's own size; the share page shows it at that size


def address():
    m = re.search(r'const val WEB_APP_URL = "([^"]+)"', open(TEXTS, encoding='utf-8').read())
    if not m:
        sys.exit('Texts.kt has no WEB_APP_URL')
    return m.group(1)


def runs(matrix):
    """(x, y, length) of every run of dark modules along a row, the quiet zone counted in"""
    out = []
    for y, row in enumerate(matrix):
        x = 0
        while x < len(row):
            if row[x]:
                start = x
                while x < len(row) and row[x]:
                    x += 1
                out.append((start + QUIET, y + QUIET, x - start))
            else:
                x += 1
    return out


def main():
    url = address()
    qr = segno.make(url, error='m', micro=False)   # (segno raises the error correction further where it costs no size)
    matrix = [[bool(v) for v in row] for row in qr.matrix]
    n = len(matrix) + 2 * QUIET
    dark = runs(matrix)
    note = 'QR code of %s (Texts.WEB_APP_URL; version %s, error correction %s); made by tools/make_qr.py, do not edit' % (
        url, qr.version, qr.error.upper())
    svg = ('<!-- %s -->\n'
           '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges">\n'
           '  <rect width="%d" height="%d" fill="#ffffff"/>\n'
           '  <path fill="#000000" d="%s"/>\n'
           '</svg>\n') % (note, n, n, n, n, ''.join('M%d %dh%dv1h-%dz' % (x, y, l, l) for x, y, l in dark))
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<!-- %s -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="%ddp" android:height="%ddp" android:viewportWidth="%d" android:viewportHeight="%d">\n'
           '    <path android:fillColor="#FFFFFFFF" android:pathData="M 0 0 H %d V %d H 0 Z" />\n'
           '    <path android:fillColor="#FF000000" android:pathData="%s" />\n'
           '</vector>\n') % (note, APP_SIZE_DP, APP_SIZE_DP, n, n, n, n,
                             ' '.join('M %d %d H %d V %d H %d Z' % (x, y, x + l, y + 1, x) for x, y, l in dark))
    with open(WEB, 'w', encoding='utf-8', newline='\n') as f:
        f.write(svg)
    with open(APP, 'w', encoding='utf-8', newline='\n') as f:
        f.write(xml)
    print('%s: version %s-%s, %d modules a side with the quiet zone, %d dark runs' % (url, qr.version, qr.error.upper(), n, len(dark)))


if __name__ == '__main__':
    main()

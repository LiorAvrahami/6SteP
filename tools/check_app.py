"""A quick check of the app's sources before a push, since there is no Android SDK on the PC and GitHub is the compiler.

It cannot compile Kotlin. It catches the mistakes that are easy to make by hand:
- an R.<type>.<name> in the Kotlin, or an @<type>/<name> in the XML, that is not in res/ (or a font file name that
  Android refuses);
- a findViewById(R.id.x) in an activity whose layouts (setContentView, inflate) do not have that id;
- a getString(R.string.x, ...) whose number of arguments differs from the placeholders of the string;
- an activity in the manifest without its class, a custom view in a layout without its class;
- names that must not come back (the old activities, ids, helpers);
- brackets that do not balance in a Kotlin file (outside strings and comments).
Run: python tools/check_app.py   (exit code 1 when something is wrong)
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main')
JAVA = os.path.join(ROOT, 'java', 'org', 'lior', 'sixstep')
RES = os.path.join(ROOT, 'res')
ANDROID = '{http://schemas.android.com/apk/res/android}'
GONE = ['PlayerActivity', 'FloorActivity', 'optionsBar', 'trackPicker', 'bpmPicker', 'snareToggle', 'hatToggle',
        'voiceToggle', 'exploreHere', 'closeBar', 'mapOverlay', 'mapGrid', 'viewToggle', 'compositionList',
        'startDaily', 'metronomeOverMusic', 'speakAnnotations', 'newComposition', 'ic_strategic', 'ic_map',
        'ic_edit', 'bg_chip', 'Smoother', 'normAngle', 'conditionLabel', 'showHands', 'showBodies', 'R.color.panel',
        'R.color.ink', 'R.color.muted', '@color/panel', '@color/ink', '@color/muted']

CLASHES = {'finish', 'recreate', 'startActivity', 'setResult', 'getIntent', 'getTitle', 'setTitle', 'getWindow',
           'onBackPressed', 'isFinishing', 'runOnUiThread', 'getResources', 'draw', 'layout', 'measure', 'scrollTo',
           'scrollBy', 'invalidate', 'post', 'postDelayed', 'requestLayout', 'getWidth', 'getHeight', 'isShown',
           'onDraw', 'onTouchEvent', 'performClick', 'computeScroll', 'onSizeChanged', 'onMeasure', 'onLayout'}
problems = []


def bad(msg):
    problems.append(msg)


def resources():
    """{type: set(names)} from the res folders and values files, ids from every layout"""
    have = {}
    for d in os.listdir(RES):
        kind = d.split('-')[0]
        for f in os.listdir(os.path.join(RES, d)):
            name = os.path.splitext(f)[0]
            if kind in ('drawable', 'layout', 'font', 'raw', 'mipmap', 'xml'):
                have.setdefault(kind, set()).add(name)
                if kind == 'font' and not re.fullmatch(r'[a-z0-9_]+', name):
                    bad('font file name Android refuses: %s' % f)
            if kind == 'values' and f.endswith('.xml'):
                for el in ET.parse(os.path.join(RES, d, f)).getroot():
                    have.setdefault(el.tag, set()).add(el.attrib.get('name'))
            if kind == 'layout':
                for el in ET.parse(os.path.join(RES, d, f)).iter():
                    v = el.attrib.get(ANDROID + 'id', '')
                    if v.startswith('@+id/'):
                        have.setdefault('id', set()).add(v[5:])
    return have


def layout_ids(name):
    return {el.attrib[ANDROID + 'id'][5:] for el in ET.parse(os.path.join(RES, 'layout', name + '.xml')).iter()
            if el.attrib.get(ANDROID + 'id', '').startswith('@+id/')}


def strip_code(src):
    """the Kotlin without strings, chars and comments (for the bracket count)"""
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2); i = n if j < 0 else j + 2
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3); i = n if j < 0 else j + 3; out.append('""')
        elif src[i] == '"':
            j = i + 1
            while j < n and src[j] != '"':
                if src[j] == '\\':
                    j += 1
                elif src.startswith('${', j):   # a template: skip to its closing brace
                    depth = 0
                    while j < n:
                        if src[j] == '{': depth += 1
                        elif src[j] == '}':
                            depth -= 1
                            if depth == 0: break
                        j += 1
                j += 1
            i = j + 1; out.append('""')
        elif src[i] == "'" and i + 2 < n and (src[i + 2] == "'" or src[i + 1] == '\\'):
            j = src.find("'", i + 2 if src[i + 1] == '\\' else i + 1)
            i = j + 1; out.append("''")
        else:
            out.append(src[i]); i += 1
    return ''.join(out)


def main():
    have = resources()
    strings = {}
    for el in ET.parse(os.path.join(RES, 'values', 'strings.xml')).getroot():
        strings[el.attrib['name']] = el.text or ''
    classes = set()
    sources = {}
    for f in sorted(os.listdir(JAVA)):
        if f.endswith('.kt'):
            src = open(os.path.join(JAVA, f), encoding='utf-8').read()
            sources[f] = src
            classes.update(re.findall(r'^(?:\w+ )*(?:class|object) (\w+)', src, re.M))

    for f, src in sources.items():
        for kind, name in re.findall(r'(?<![\w.])R\.(\w+)\.(\w+)', src):   # not android.R
            if name not in have.get(kind, set()):
                bad('%s: R.%s.%s is not in res/' % (f, kind, name))
        # the ids of the layouts this file inflates
        layouts = re.findall(r'R\.layout\.(\w+)', src)
        if layouts:
            ids = set()
            for l in layouts:
                if l in have.get('layout', set()):
                    ids |= layout_ids(l)
            for name in re.findall(r'findViewById(?:<[\w.]+>)?\(R\.id\.(\w+)\)', src):
                if name not in ids:
                    bad('%s: findViewById(R.id.%s) is in none of its layouts %s' % (f, name, layouts))
        for m in re.finditer(r'getString\(R\.string\.(\w+)', src):
            name, got, depth, i = m.group(1), 0, 1, m.end()
            while i < len(src) and depth > 0:   # count the commas at the call's own level up to its closing bracket
                ch = src[i]
                if ch in '([{': depth += 1
                elif ch in ')]}': depth -= 1
                elif ch == ',' and depth == 1: got += 1
                i += 1
            want = len(set(re.findall(r'%(\d+)\$', strings.get(name, ''))))
            if want != got:
                bad('%s: getString(R.string.%s) has %d argument(s), the string wants %d' % (f, name, got, want))
        code = strip_code(src)
        # a function of an activity or a view named like one of Android's own needs `override`, or it does not compile
        for name in re.findall(r'^\s*(?:private |protected |internal )?fun (\w+)\(', code, re.M):
            if name in CLASHES:
                bad('%s: fun %s() clashes with Android\'s own %s()' % (f, name, name))
        for word in GONE:   # in the code itself, not its comments
            if re.search(r'(?<![\w@.])' + re.escape(word) + r'(?!\w)', code):
                bad('%s: mentions %s, which is gone' % (f, word))
        for o, c in ('()', '{}', '[]'):
            if code.count(o) != code.count(c):
                bad('%s: %d %s against %d %s' % (f, code.count(o), o, code.count(c), c))

    for d in os.listdir(RES):
        for f in os.listdir(os.path.join(RES, d)):
            if not f.endswith('.xml'):
                continue
            text = open(os.path.join(RES, d, f), encoding='utf-8').read()
            for kind, name in re.findall(r'@(drawable|string|font|color|layout|mipmap|raw|dimen|style)/(\w+)', text):
                if name not in have.get(kind, set()):
                    bad('res/%s/%s: @%s/%s is not in res/' % (d, f, kind, name))
            bare = re.sub(r'<!--.*?-->', '', text, flags=re.S)
            for word in GONE:
                if re.search(r'(?<![\w])' + re.escape(word) + r'(?!\w)', bare):
                    bad('res/%s/%s: mentions %s, which is gone' % (d, f, word))
            if d.startswith('layout'):
                for el in ET.parse(os.path.join(RES, d, f)).iter():
                    if el.tag.startswith('org.lior.sixstep.') and el.tag.split('.')[-1] not in classes:
                        bad('res/%s/%s: no class %s' % (d, f, el.tag))

    manifest = ET.parse(os.path.join(ROOT, 'AndroidManifest.xml')).getroot()
    for el in manifest.iter('activity'):
        name = el.attrib[ANDROID + 'name'].lstrip('.')
        if name not in classes:
            bad('AndroidManifest.xml: no class %s' % name)
    for f, src in sources.items():
        for name in re.findall(r'(\w+Activity)::class', src):
            if not any(el.attrib[ANDROID + 'name'].lstrip('.') == name for el in manifest.iter('activity')):
                bad('%s: %s is not in the manifest' % (f, name))

    for p in problems:
        print(p)
    print('%d problem(s) in %d Kotlin files and res/' % (len(problems), len(sources)))
    sys.exit(1 if problems else 0)


if __name__ == '__main__':
    main()

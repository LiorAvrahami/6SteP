"""The skeleton editor: a local web page on http://127.0.0.1:8771 (8765 is taken on this PC). Start it with python RunWeb.py.

    python dance/editor/server.py [--port 8771]

On start it ends any process already listening on the port (an editor started earlier would otherwise keep serving old code),
and takes the port exclusively.

GET  /                      -> the editor page
GET  /api/skeletons         -> the skeletons document, assembled from dance/skeletons/ (one file per element); X-Version header
POST /api/save?base=V       -> writes the document back (only the elements that changed); refused (409) when the files changed
                               since version V, and the page merges; the page saves after every edit
GET  /api/check             -> text of `python dance/check.py`
GET  /api/version           -> {"stale": true} when a Python file is newer than this process (the page shows a banner)
GET  /api/compositions      -> dance/compositions.json
GET  /api/composition?name= -> the composed world timeline of that composition (model.compose), read-only
POST /api/compositions      -> {"compositions": [...], "sub_compositions": [...]}: writes dance/compositions.json
                               (an entry in an element list is an element name, a sub-composition name, or either followed by ' x N')
POST /api/compose           -> {"start_state", "elements": [...], "repeat", "leader_free"?, "pad"?}: the composed timeline of a draft;
                               pad=true appends triple-step fillers (flagged) until it loops (the free foot restored);
                               chain_beats=N then chains a closing loop across N beats (chained = how many times), so the dance
                               continues from where the dancers are instead of restarting
POST /api/render            -> {"composition": name, "track": hot|sunday|slow} or {"elements": [...], "start": state, "track": ...};
                               runs render.py, returns {"ok", "url", "log"}; the video is served under /out/
GET  /out/<file>            -> files from dance/out (videos, storyboards), with byte ranges for the <video> element
GET  /api/track/<key>       -> the beat grid JSON of a track (hot|sunday|slow)
GET  /music/<key>.mp3       -> the track itself (for in-browser playback)
"""
import sys, os, re, json, time, argparse, subprocess, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
DANCE = os.path.dirname(HERE)
OUT = os.path.join(DANCE, 'out')
MUSIC = os.path.join(os.path.dirname(DANCE), 'music', 'chosen')
sys.path.insert(0, DANCE)
import model
STARTED = time.time()
CODE_FILES = [os.path.abspath(__file__), os.path.join(DANCE, 'model.py')]
TRACKS = {
    'hot': 'Hot Swing - Kevin MacLeod - 5min loop (144bpm, CC BY)',
    'sunday': '15 - Laughing Manny - Sunday Reflection (127bpm)',
    'slow': '16 - Laughing Manny - Slow Your Roll (123bpm)',
}

TYPES = {'.mp4': 'video/mp4', '.png': 'image/png', '.json': 'application/json', '.txt': 'text/plain; charset=utf-8', '.mp3': 'audio/mpeg'}

ELEMENT_KEYS = ('name', 'start_state', 'end_state', 'beats', 'leader_free_start', 'hands', 'steps')

def invalid_skeletons(data):
    """Why the editor's document must not be written (one bad element would break check.py, render.py and the app), or None.
    An element may have no steps yet: model.element_problem() keeps it out of the dance until it has them."""
    seen = set()
    for e in data['elements']:
        if not isinstance(e, dict) or any(k not in e for k in ELEMENT_KEYS):
            return f'an element misses one of {ELEMENT_KEYS}'
        if not isinstance(e['name'], str) or not e['name'].strip():
            return 'an element has no name'
        if not e.get('winding'):
            return f'{e["name"]!r} has no winding mark: this page is older than the server; reload it (your last change was not saved)'
        k = (e['name'], e['start_state'], e.get('leader_free_start', 'L'))
        if k in seen:
            return f'two skeletons of {k[0]!r} from {k[1]} with the leader\'s {k[2]} foot free'
        seen.add(k)
        if e['start_state'] not in data['states'] or e['end_state'] not in data['states']:
            return f'{e["name"]!r}: unknown state {e["start_state"]!r} or {e["end_state"]!r}'
        if e['leader_free_start'] not in ('L', 'R'):
            return f'{e["name"]!r}: the free foot must be L or R'
        for s in e['steps']:
            if not model.COUNT_RE.match(str(s.get('count', ''))):
                return f'{e["name"]!r}: {s.get("count")!r} is not a count label (1, 2, 3&, ...)'
            if s.get('dancer') not in ('L', 'F') or s.get('foot') not in ('L', 'R'):
                return f'{e["name"]!r} count {s.get("count")}: dancer must be L or F and foot L or R'
            if not all(isinstance(s.get(c), (int, float)) for c in ('x', 'y', 'heading')):
                return f'{e["name"]!r} count {s.get("count")}: x, y and heading must be numbers'
            if 'via' in s and not (isinstance(s['via'], list) and all(isinstance(v, list) and len(v) == 2 and all(isinstance(c, (int, float)) for c in v) for v in s['via'])):
                return f'{e["name"]!r} count {s.get("count")}: via must be a list of [x, y] anchors'
    return None

BACKUPS = os.path.join(OUT, 'backups')

def file_version(path):
    """The version of a file as the page knows it: its modification time in nanoseconds."""
    try:
        return str(os.stat(path).st_mtime_ns)
    except OSError:
        return ''

def keep_copy(path, tag, body=None):
    """A dated copy in dance/out/backups (git-ignored): of the file before it is overwritten (one per minute), or of a save that
    was refused (body). The user's edits must never be lost (2026-10-25: a forced checkout wiped two elements)."""
    try:
        os.makedirs(BACKUPS, exist_ok=True)
        name = os.path.join(BACKUPS, f"{tag}_{time.strftime('%Y-%m-%d_%H-%M')}.json")
        if body is not None:
            name = name[:-5] + time.strftime('-%S') + '.json'
            with open(name, 'wb') as f:
                f.write(body)
        elif path and os.path.isdir(path) and not os.path.exists(name):
            with open(name, 'w', encoding='utf-8') as b:
                json.dump(model.load(path), b, indent=1, ensure_ascii=False)
        elif path and os.path.isfile(path) and not os.path.exists(name):
            with open(path, 'rb') as a, open(name, 'wb') as b:
                b.write(a.read())
        olds = sorted(f for f in os.listdir(BACKUPS) if f.startswith(tag + '_'))
        for f in olds[:-500]:
            os.remove(os.path.join(BACKUPS, f))
    except OSError:
        pass

def slug(s):
    return re.sub(r'[^a-z0-9]+', '_', s.lower()).strip('_')

class H(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype='text/plain; charset=utf-8', extra=None):
        if isinstance(body, str):
            body = body.encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code=200):
        self._send(code, json.dumps(obj, ensure_ascii=False), 'application/json')

    def _file(self, path):
        """Serve a file from dance/out with HTTP range support (needed for seeking in <video>)."""
        size = os.path.getsize(path)
        ctype = TYPES.get(os.path.splitext(path)[1].lower(), 'application/octet-stream')
        rng = self.headers.get('Range')
        start, end = 0, size - 1
        code = 200
        if rng and rng.startswith('bytes='):
            a, _, b = rng[6:].partition('-')
            start = int(a) if a else max(0, size - int(b))
            end = int(b) if (b and a) else size - 1
            end = min(end, size - 1)
            code = 206
        length = end - start + 1
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(length))
        self.send_header('Cache-Control', 'no-store')
        if code == 206:
            self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        self.end_headers()
        with open(path, 'rb') as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(1 << 20, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (ConnectionAbortedError, BrokenPipeError):
                    return
                remaining -= len(chunk)

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        p = url.path
        q = urllib.parse.parse_qs(url.query)
        if p in ('/', '/index.html'):
            with open(os.path.join(HERE, 'index.html'), 'rb') as f:
                return self._send(200, f.read(), 'text/html; charset=utf-8')
        if p == '/api/skeletons':
            return self._send(200, json.dumps(model.load(), ensure_ascii=False), 'application/json', {'X-Version': model.skeletons_version()})
        if p == '/api/skeletons_version':
            return self._json({'version': model.skeletons_version()})
        if p == '/api/compositions':
            with open(model.COMPOSITIONS, 'rb') as f:
                return self._send(200, f.read(), 'application/json')
        if p == '/api/composition':
            name = (q.get('name') or [''])[0]
            c = model.find_composition(name)
            if c is None:
                return self._json({'errors': [f'no composition named {name!r}'], 'steps': [], 'elements': [], 'start': {}, 'length': 0, 'end_state': ''}, 404)
            data = model.load(model.SKELETONS)
            comp = model.compose(data, model.composition_sequence(c), c['start_state'])
            comp['definition'] = c
            return self._json(comp)
        if p == '/api/version':
            # the page warns when this process runs older code than the files on disk
            newest = max(os.path.getmtime(f) for f in CODE_FILES if os.path.exists(f))
            return self._json({'started': STARTED, 'newest': newest, 'stale': newest > STARTED + 1})
        if p == '/api/check':
            r = subprocess.run([sys.executable, os.path.join(DANCE, 'check.py')], capture_output=True, text=True, encoding='utf-8', errors='replace', cwd=DANCE)
            return self._send(200, (r.stdout or '') + (r.stderr or ''))
        if p.startswith('/api/track/'):
            key = p[len('/api/track/'):]
            if key not in TRACKS:
                return self._send(404, 'unknown track')
            with open(os.path.join(MUSIC, TRACKS[key] + ' - beatgrid.json'), 'rb') as f:
                return self._send(200, f.read(), 'application/json')
        if p.startswith('/music/'):
            key = os.path.basename(p[len('/music/'):])
            key = key[:-4] if key.endswith('.mp3') else key
            if key not in TRACKS:
                return self._send(404, 'unknown track')
            return self._file(os.path.join(MUSIC, TRACKS[key] + '.mp3'))
        if p.startswith('/out/'):
            name = os.path.basename(urllib.parse.unquote(p[5:]))
            path = os.path.join(OUT, name)
            if name and os.path.isfile(path):
                return self._file(path)
            return self._send(404, 'no such file')
        return self._send(404, 'not found')

    def do_HEAD(self):
        """Headers only, for the files (the browser's media probing)."""
        p = urllib.parse.urlparse(self.path).path
        path = None
        if p.startswith('/music/'):
            key = os.path.basename(p[len('/music/'):])
            key = key[:-4] if key.endswith('.mp3') else key
            if key in TRACKS:
                path = os.path.join(MUSIC, TRACKS[key] + '.mp3')
        elif p.startswith('/out/'):
            name = os.path.basename(urllib.parse.unquote(p[5:]))
            if name and os.path.isfile(os.path.join(OUT, name)):
                path = os.path.join(OUT, name)
        if path is None or not os.path.isfile(path):
            self.send_response(404); self.send_header('Content-Length', '0'); self.end_headers(); return
        self.send_response(200)
        self.send_header('Content-Type', TYPES.get(os.path.splitext(path)[1].lower(), 'application/octet-stream'))
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(os.path.getsize(path)))
        self.end_headers()

    def do_POST(self):
        p = urllib.parse.urlparse(self.path).path
        n = int(self.headers.get('Content-Length', '0'))
        raw = self.rfile.read(n)
        if p == '/api/save':
            try:
                data = json.loads(raw.decode('utf-8'))
                assert 'elements' in data and 'states' in data
            except Exception as e:
                keep_copy(None, 'skeletons_refused', raw)
                return self._send(400, f'bad json: {e}')
            bad = invalid_skeletons(data)
            if bad:
                keep_copy(None, 'skeletons_refused', raw)   # kept: it may hold edits made in an old page
                return self._send(400, f'not saved: {bad} (a copy is kept in dance/out/backups)')
            base = (urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get('base') or [None])[0]
            if not base:
                keep_copy(None, 'skeletons_refused', raw)   # a page older than the merge guard: never let it overwrite
                return self._send(400, 'not saved: this page is older than the server; reload it (a copy is kept in dance/out/backups)')
            if base != model.skeletons_version():
                # the file changed on disk after the page read it (the agent, a script, another tab): refused, never overwritten;
                # the page merges its own changes into the newer file and saves again (2026-10-25: an open tab dropped an element)
                keep_copy(None, 'skeletons_refused', raw)
                return self._json({'conflict': True, 'version': model.skeletons_version()}, 409)
            keep_copy(model.SKELETONS, 'skeletons')
            try:
                model.save(data)
            except ValueError as e:
                return self._send(400, f'not saved: {e}')
            return self._send(200, 'saved', extra={'X-Version': model.skeletons_version()})
        if p == '/api/compose':
            try:
                req = json.loads(raw.decode('utf-8'))
                subs = req.get('sub_compositions')
                if subs is None:
                    subs = model.load_subs()
                flat = model.expand_sequence(list(req.get('elements') or []), subs) * max(1, int(req.get('repeat', 1)))
                elements = [el for el, _ in flat]
                groups = [g for _, g in flat]
                start = req.get('start_state', 'side')
            except Exception as e:
                return self._json({'errors': [f'bad request: {e}'], 'steps': [], 'elements': [], 'start': {}, 'length': 0, 'end_state': ''}, 400)
            data = model.load(model.SKELETONS)
            if start not in data['states']:
                return self._json({'errors': [f'unknown start state {start!r}'], 'steps': [], 'elements': [], 'start': {}, 'length': 0, 'end_state': start}, 400)
            comp = model.compose(data, elements, start, leader_free=req.get('leader_free') or None, pad_to_loop=bool(req.get('pad')), groups=groups)
            comp['loop_beats'] = comp['length']
            comp['closes'] = (not comp['errors']) and comp['end_state'] == start and comp['end_free'] == comp['start_free'] and comp['length'] > 0
            comp['chained'] = 1
            chain_beats = float(req.get('chain_beats') or 0)
            if req.get('pad') and comp['closes'] and chain_beats > comp['length']:
                # the loop closes on itself: dance it again and again from where the dancers are, for the whole track
                import math as _math
                n = max(1, min(400, int(_math.ceil(chain_beats / comp['length']))))
                loop_names = [e['name'] for e in comp['elements']]
                loop_fillers = [bool(e.get('filler')) for e in comp['elements']]
                loop_groups = [e.get('group') for e in comp['elements']]
                chained = model.compose(data, loop_names * n, start, leader_free=req.get('leader_free') or None, groups=loop_groups * n)
                if not chained['errors']:
                    for k, e in enumerate(chained['elements']):
                        e['filler'] = loop_fillers[k % len(loop_names)]
                    for st in chained['steps']:
                        st['filler'] = chained['elements'][st['element']]['filler']
                    chained.update(loop_beats=comp['length'], closes=True, chained=n, start_free=comp['start_free'])
                    comp = chained
            return self._json(comp)
        if p == '/api/compositions':
            try:
                req = json.loads(raw.decode('utf-8'))
                comps = req['compositions']
                subs = req.get('sub_compositions')
                assert isinstance(comps, list)
                for c in comps + (subs or []):
                    assert isinstance(c['name'], str) and c['name'].strip() and isinstance(c['elements'], list) and c['start_state'] in ('side', 'facing', 'reversed_side')
                    c['repeat'] = max(1, int(c.get('repeat', 1)))
            except Exception as e:
                return self._send(400, f'bad compositions: {e}')
            with open(model.COMPOSITIONS, encoding='utf-8') as f:
                doc = json.load(f)
            doc['compositions'] = comps
            if subs is not None:
                doc['sub_compositions'] = subs
            keep_copy(model.COMPOSITIONS, 'compositions')
            with open(model.COMPOSITIONS, 'w', encoding='utf-8') as f:
                json.dump(doc, f, indent=1, ensure_ascii=False)
                f.write('\n')
            return self._send(200, 'saved')
        if p == '/api/render':
            try:
                req = json.loads(raw.decode('utf-8'))
            except Exception as e:
                return self._json({'ok': False, 'log': f'bad json: {e}'}, 400)
            track = req.get('track', 'hot')
            if track not in ('hot', 'sunday', 'slow'):
                return self._json({'ok': False, 'log': 'unknown track'}, 400)
            cmd = [sys.executable, os.path.join(DANCE, 'render.py'), '--track', track]
            if req.get('composition'):
                name = req['composition']
                if model.find_composition(name) is None:
                    return self._json({'ok': False, 'log': f'no composition named {name!r}'}, 404)
                out_name = f'{slug(name)}_{track}.mp4'
                cmd += ['--composition', name]
            else:
                elements = req.get('elements') or []
                if not elements:
                    return self._json({'ok': False, 'log': 'nothing to render'}, 400)
                out_name = f'{slug("_".join(elements))[:60]}_{track}.mp4'
                cmd += ['--start', req.get('start', 'side')] + list(elements)
            os.makedirs(OUT, exist_ok=True)
            cmd += ['--out', os.path.join(OUT, out_name)]
            try:
                r = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', cwd=DANCE, timeout=1800)
            except subprocess.TimeoutExpired:
                return self._json({'ok': False, 'log': 'render timed out after 30 minutes'})
            log = (r.stdout or '') + (r.stderr or '')
            if r.returncode != 0:
                return self._json({'ok': False, 'log': log})
            return self._json({'ok': True, 'url': '/out/' + out_name, 'log': log})
        return self._send(404, 'not found')

    def log_message(self, fmt, *args):
        sys.stderr.write('%s %s\n' % (self.address_string(), fmt % args))

def listeners(port):
    """PIDs of the processes listening on this TCP port (other than this one)."""
    pids = set()
    if os.name == 'nt':
        out = subprocess.run(['netstat', '-ano', '-p', 'TCP'], capture_output=True, text=True, errors='replace').stdout
        out += subprocess.run(['netstat', '-ano', '-p', 'TCPv6'], capture_output=True, text=True, errors='replace').stdout
        for line in out.splitlines():
            f = line.split()
            # TCP  <local>  <foreign>  <state>  <pid>; a listener's foreign address is 0.0.0.0:0 or [::]:0
            if len(f) == 5 and f[0] == 'TCP' and f[1].rsplit(':', 1)[-1] == str(port) and f[2] in ('0.0.0.0:0', '[::]:0'):
                pids.add(int(f[4]))
    else:
        out = subprocess.run(['lsof', '-t', f'-iTCP:{port}', '-sTCP:LISTEN'], capture_output=True, text=True).stdout
        pids = {int(x) for x in out.split()}
    pids.discard(os.getpid())
    pids.discard(0)
    return pids

def end_old_servers(port):
    """Stop whatever already listens on the port, such as an editor started earlier that still runs old code."""
    for pid in listeners(port):
        print(f'port {port} was taken by process {pid}: ending it', flush=True)
        if os.name == 'nt':
            subprocess.run(['taskkill', '/PID', str(pid), '/F'], capture_output=True)
        else:
            try:
                os.kill(pid, 15)
            except OSError:
                pass

class Server(ThreadingHTTPServer):
    # Windows lets a second server bind a port that is in use when SO_REUSEADDR is set, and the requests keep going to
    # the first one. Take the port exclusively there instead.
    allow_reuse_address = os.name != 'nt'

    def server_bind(self):
        if os.name == 'nt':
            import socket
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, default=8771)
    a = ap.parse_args()
    end_old_servers(a.port)
    taken = model.import_legacy()   # edits an old editor saved into the old single file
    if taken:
        print('took the newer elements from the old dance/skeletons.json (moved to dance/out/backups):', ', '.join(taken), flush=True)
    keep_copy(model.SKELETONS, 'skeletons'); keep_copy(model.COMPOSITIONS, 'compositions')
    data = model.load(model.SKELETONS)
    converted = model.unwrap_all(data)   # once per element: headings keep their winding from now on
    if converted:
        model.save(data, model.SKELETONS)
        print('headings converted to keep their winding (each foot still turns the same way):', ', '.join(converted), flush=True)
    for attempt in range(50):   # an ended process can take a moment to let go of the port
        try:
            srv = Server(('127.0.0.1', a.port), H)
            break
        except OSError:
            if attempt == 49:
                raise
            if attempt % 10 == 9:
                end_old_servers(a.port)
            time.sleep(0.1)
    print(f'editor at http://127.0.0.1:{a.port}/   (ctrl-c to stop)', flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass

if __name__ == '__main__':
    main()

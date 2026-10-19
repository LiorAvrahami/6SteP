"""Beat annotator: a local page to mark the beats of a track by ear on a spectrogram, and write its beat grid.

    python music/tools/beat_annotator/server.py            # http://127.0.0.1:8772

On first start it computes a mel spectrogram of every track in music/chosen (n_fft 1024, hop 128 = 2.9 ms per column) and
caches it as PNG tiles under music/tools/beat_annotator/cache/<key>/. Keys and tracks come from the Android app's list
(hot, sunday, slow). Saving writes `<track> - beatgrid.json` with grid "annotated" and the taps, keeping the analysis grid
once as `<track> - beatgrid.analysis.json` for reference.
"""
import os, sys, json, argparse, urllib.parse, shutil
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
MUSIC = os.path.normpath(os.path.join(HERE, '..', '..', 'chosen'))
CACHE = os.path.join(HERE, 'cache')
TRACKS = [
    ('hot', 'Hot Swing - Kevin MacLeod - 5min loop (144bpm, CC BY)', 'Hot Swing (144)'),
    ('sunday', '15 - Laughing Manny - Sunday Reflection (127bpm)', 'Sunday Reflection (127)'),
    ('slow', '16 - Laughing Manny - Slow Your Roll (123bpm)', 'Slow Your Roll (123)'),
]
HOP, NFFT, MELS, TILE = 128, 1024, 96, 2048
TYPES = {'.mp3': 'audio/mpeg', '.png': 'image/png', '.json': 'application/json', '.html': 'text/html; charset=utf-8'}


def colormap():
    """an inferno-like lookup table, 256 RGB rows"""
    import numpy as np
    stops = [(0.0, (0, 0, 4)), (0.2, (40, 11, 84)), (0.4, (120, 28, 109)), (0.6, (187, 55, 84)), (0.8, (237, 105, 37)), (0.93, (251, 182, 26)), (1.0, (252, 255, 164))]
    lut = np.zeros((256, 3), dtype=np.uint8)
    for i in range(256):
        u = i / 255.0
        for (a, ca), (b, cb) in zip(stops[:-1], stops[1:]):
            if a <= u <= b:
                w = (u - a) / (b - a) if b > a else 0
                lut[i] = [int(ca[k] + (cb[k] - ca[k]) * w) for k in range(3)]
                break
    return lut


def ensure_spectrogram(key, base):
    """mel spectrogram tiles for a track, computed once"""
    import numpy as np
    d = os.path.join(CACHE, key)
    meta_p = os.path.join(d, 'meta.json')
    if os.path.exists(meta_p):
        return json.load(open(meta_p))
    import librosa
    from PIL import Image
    os.makedirs(d, exist_ok=True)
    path = os.path.join(MUSIC, base + '.mp3')
    print(f'  computing the spectrogram of {key}...', flush=True)
    y, sr = librosa.load(path, sr=None, mono=True)
    S = librosa.feature.melspectrogram(y=y, sr=sr, n_fft=NFFT, hop_length=HOP, n_mels=MELS, fmin=30, fmax=min(12000, sr / 2), power=2.0)
    db = librosa.power_to_db(S, ref=np.max, top_db=80)
    norm = (db + 80) / 80.0                      # 0..1
    img = (np.clip(norm, 0, 1) * 255).astype(np.uint8)[::-1, :]   # low frequencies at the bottom
    lut = colormap()
    rgb = lut[img]                                # rows x cols x 3
    cols = rgb.shape[1]
    n = (cols + TILE - 1) // TILE
    for i in range(n):
        tile = rgb[:, i * TILE:(i + 1) * TILE]
        if tile.shape[1] < TILE:
            pad = np.zeros((tile.shape[0], TILE - tile.shape[1], 3), dtype=np.uint8); tile = np.concatenate([tile, pad], axis=1)
        Image.fromarray(tile, 'RGB').save(os.path.join(d, f'tile_{i:03d}.png'), optimize=True)
    meta = {'key': key, 'sr': sr, 'hop': HOP, 'cols_per_sec': sr / HOP, 'cols': int(cols), 'rows': int(rgb.shape[0]), 'tile': TILE, 'tiles': n, 'duration': len(y) / sr}
    json.dump(meta, open(meta_p, 'w'))
    print(f'  {key}: {cols} columns, {n} tiles', flush=True)
    return meta


def grid_paths(base):
    return os.path.join(MUSIC, base + ' - beatgrid.json'), os.path.join(MUSIC, base + ' - beatgrid.analysis.json')


def draft_path(base):
    """the marks as they are being made: written on every change, so nothing is ever lost"""
    return os.path.join(MUSIC, base + ' - taps.json')


class H(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype='text/plain; charset=utf-8'):
        if isinstance(body, str):
            body = body.encode('utf-8')
        self.send_response(code); self.send_header('Content-Type', ctype); self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store'); self.end_headers(); self.wfile.write(body)

    def _file(self, path):
        size = os.path.getsize(path); ctype = TYPES.get(os.path.splitext(path)[1].lower(), 'application/octet-stream')
        rng = self.headers.get('Range'); start, end, code = 0, size - 1, 200
        if rng and rng.startswith('bytes='):
            a, _, b = rng[6:].partition('-'); start = int(a) if a else max(0, size - int(b)); end = int(b) if (b and a) else size - 1; end = min(end, size - 1); code = 206
        length = end - start + 1
        self.send_response(code); self.send_header('Content-Type', ctype); self.send_header('Accept-Ranges', 'bytes'); self.send_header('Content-Length', str(length))
        if code == 206: self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        self.end_headers()
        with open(path, 'rb') as f:
            f.seek(start); remaining = length
            while remaining > 0:
                chunk = f.read(min(1 << 20, remaining))
                if not chunk: break
                try: self.wfile.write(chunk)
                except (ConnectionAbortedError, BrokenPipeError): return
                remaining -= len(chunk)

    def do_GET(self):
        url = urllib.parse.urlparse(self.path); p = url.path
        if p in ('/', '/index.html'):
            with open(os.path.join(HERE, 'index.html'), 'rb') as f: return self._send(200, f.read(), TYPES['.html'])
        if p == '/api/tracks':
            out = []
            for key, base, title in TRACKS:
                gp, ap = grid_paths(base)
                g = json.load(open(gp, encoding='utf-8')) if os.path.exists(gp) else {}
                meta = ensure_spectrogram(key, base)
                dp = draft_path(base)
                draft = json.load(open(dp, encoding='utf-8')) if os.path.exists(dp) else None
                out.append({'key': key, 'title': title, 'base': base, 'duration': meta['duration'], 'spec': meta,
                            'draft': draft,
                            'grid': {k: g.get(k) for k in ('bpm', 'beat', 'swing', 'beats', 'bars', 'grid', 'taps', 'downbeat_source') if k in g}})
            return self._send(200, json.dumps(out), 'application/json')
        if p.startswith('/audio/'):
            key = os.path.basename(p[7:]).replace('.mp3', '')
            for k, base, _ in TRACKS:
                if k == key: return self._file(os.path.join(MUSIC, base + '.mp3'))
            return self._send(404, 'no such track')
        if p.startswith('/spec/'):
            parts = p[6:].split('/')
            if len(parts) == 2 and parts[0] in [k for k, _, _ in TRACKS]:
                f = os.path.join(CACHE, parts[0], os.path.basename(parts[1]))
                if os.path.isfile(f): return self._file(f)
            return self._send(404, 'no such tile')
        return self._send(404, 'not found')

    def do_POST(self):
        p = urllib.parse.urlparse(self.path).path
        n = int(self.headers.get('Content-Length', '0')); raw = self.rfile.read(n)
        if p == '/api/taps':
            # autosave of the marks: every change lands here at once
            try:
                req = json.loads(raw.decode('utf-8')); key = req['key']
                base = next(b for k, b, _ in TRACKS if k == key)
                taps = [{'t': float(x['t']), 'down': bool(x.get('down'))} for x in req.get('taps', [])]
            except Exception as e:
                return self._send(400, f'bad request: {e}')
            import datetime
            doc = {'key': key, 'taps': taps, 'saved_at': datetime.datetime.now().isoformat(timespec='seconds')}
            tmp = draft_path(base) + '.tmp'
            json.dump(doc, open(tmp, 'w', encoding='utf-8'))
            os.replace(tmp, draft_path(base))
            return self._send(200, f'{len(taps)} marks autosaved {doc["saved_at"][11:]}')
        if p != '/api/save': return self._send(404, 'not found')
        try:
            req = json.loads(raw.decode('utf-8')); key = req['key']
            base = next(b for k, b, _ in TRACKS if k == key)
            beats = [float(x) for x in req['beats']]; bars = [float(x) for x in req['bars']]; taps = req.get('taps', [])
            assert len(beats) >= 2
        except Exception as e:
            return self._send(400, f'bad request: {e}')
        gp, ap = grid_paths(base)
        old = json.load(open(gp, encoding='utf-8')) if os.path.exists(gp) else {}
        if os.path.exists(gp) and not os.path.exists(ap) and old.get('grid') != 'annotated':
            shutil.copyfile(gp, ap)
        import statistics
        iv = [b - a for a, b in zip(beats[:-1], beats[1:])]
        period = statistics.median(iv)
        g = {'file': old.get('file', 'chosen/' + base + '.mp3'), 'duration': old.get('duration', beats[-1]), 'grid': 'annotated',
             'bpm': 60.0 / period, 'beat': period, 't0': beats[0], 'swing': old.get('swing', 0.67),
             'first_downbeat_beat': int(min(range(len(beats)), key=lambda i: abs(beats[i] - bars[0]))) if bars else 0,
             'downbeat_source': 'annotated by ear in the beat annotator', 'beats': beats, 'bars': bars, 'taps': taps,
             'note': 'Beats interpolated and extrapolated from the taps made in music/tools/beat_annotator; beats[] is not uniform, use it directly.'}
        json.dump(g, open(gp, 'w', encoding='utf-8'), indent=0)
        return self._send(200, f'saved {len(beats)} beats, {len(bars)} bars to {os.path.basename(gp)}')

    def log_message(self, fmt, *args):
        sys.stderr.write('%s\n' % (fmt % args))


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--port', type=int, default=8772); a = ap.parse_args()
    print('preparing the spectrograms (first time only)...', flush=True)
    for key, base, _ in TRACKS: ensure_spectrogram(key, base)
    srv = ThreadingHTTPServer(('127.0.0.1', a.port), H)
    print(f'beat annotator at http://127.0.0.1:{a.port}/   (ctrl-c to stop)', flush=True)
    try: srv.serve_forever()
    except KeyboardInterrupt: pass


if __name__ == '__main__':
    main()

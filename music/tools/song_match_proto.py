"""Prototype: recognise which mapped song a listener's own file is, and how far its start is shifted, in a way that
survives re-compression. The fingerprint is the song's rhythm of loudness: the rise of the energy from one 10 ms block
to the next, over the first 90 s. A copy is matched by sliding its pattern along each mapped song's pattern.

Run: python3 song_match_proto.py <repo>      (needs ffmpeg, numpy, scipy)
"""
import os
import subprocess
import sys
import tempfile
import time

import numpy as np
from scipy.signal import correlate

REPO = sys.argv[1]
SR = 8000          # Hz, mono
BLOCK = 80         # samples: 10 ms
FP_SECONDS = 90    # the part of the song the fingerprint covers
MAX_LAG = 15.0     # s: how far a copy's start may be shifted


def decode(path, seconds=None):
    cmd = ['ffmpeg', '-v', 'error', '-i', path] + (['-t', str(seconds)] if seconds else []) + ['-ac', '1', '-ar', str(SR), '-f', 's16le', '-']
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.int16).astype(np.float64) / 32768.0


def duration(path):
    out = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', path], capture_output=True, text=True).stdout
    return float(out.strip())


def pattern(x):
    """the rise of the loudness from block to block (100 values a second), lightly smoothed"""
    n = len(x) // BLOCK
    rms = np.sqrt(np.mean(x[:n * BLOCK].reshape(n, BLOCK) ** 2, axis=1))
    e = np.log1p(rms * 200.0)
    o = np.maximum(0.0, np.diff(e, prepend=e[0]))
    return np.convolve(o, [0.25, 0.5, 0.25], mode='same')


def match(ref, user):
    """(score 0..1, shift in seconds): the shift is how much later the music starts in the user's copy"""
    r = ref - ref.mean()
    u = user - user.mean()
    c = correlate(u, r, mode='full')                 # index k: lag = k - (len(r) - 1)
    lags = np.arange(len(c)) - (len(r) - 1)
    keep = np.abs(lags) <= int(MAX_LAG * 100)
    c, lags = c[keep], lags[keep]
    k = int(np.argmax(c))
    lag = lags[k]
    # the score: the correlation over the part where the two overlap at this lag
    a0, b0 = max(0, lag), max(0, -lag)
    m = min(len(u) - a0, len(r) - b0)
    uu, rr = u[a0:a0 + m], r[b0:b0 + m]
    score = float(np.dot(uu, rr) / (np.linalg.norm(uu) * np.linalg.norm(rr) + 1e-12))
    frac = 0.0
    if 0 < k < len(c) - 1:                           # the peak between two blocks
        y0, y1, y2 = c[k - 1], c[k], c[k + 1]
        d = y0 - 2 * y1 + y2
        if abs(d) > 1e-12:
            frac = 0.5 * (y0 - y2) / d
    return score, (lag + frac) / 100.0


def ff(src, dst, *args):
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', src, *args, dst], check=True)


songs = {
    'Hot Swing': 'music/chosen/Hot Swing - Kevin MacLeod - 5min loop (144bpm, CC BY).mp3',
    'Sunday Reflection': 'music/chosen/15 - Laughing Manny - Sunday Reflection (127bpm).mp3',
    'Slow Your Roll': 'music/chosen/16 - Laughing Manny - Slow Your Roll (123bpm).mp3',
}
refs = {}
for name, rel in songs.items():
    p = os.path.join(REPO, rel)
    refs[name] = (pattern(decode(p, FP_SECONDS)), duration(p))
    q = np.clip(np.round(refs[name][0] / refs[name][0].max() * 255), 0, 255).astype(np.uint8)
    print(f'mapped: {name:18s} length {refs[name][1]:7.2f} s, fingerprint {len(q)} bytes ({len(q) / 1024:.1f} KB)')

tmp = tempfile.mkdtemp(prefix='songmatch_')
copies = [  # (label, file extension, ffmpeg arguments, the shift this copy should show in seconds)
    ('MP3 128 kbit/s', 'mp3', ['-c:a', 'libmp3lame', '-b:a', '128k'], 0.0),
    ('AAC 96 kbit/s (.m4a)', 'm4a', ['-c:a', 'aac', '-b:a', '96k'], 0.0),
    ('Opus 64 kbit/s (.ogg)', 'ogg', ['-c:a', 'libopus', '-b:a', '64k'], 0.0),
    ('MP3 64 kbit/s mono 22 kHz', 'mp3', ['-c:a', 'libmp3lame', '-b:a', '64k', '-ac', '1', '-ar', '22050'], 0.0),
    ('0.73 s of silence added at the start', 'mp3', ['-af', 'adelay=730:all=1', '-c:a', 'libmp3lame', '-b:a', '192k'], 0.73),
    ('first 0.40 s cut off', 'm4a', ['-ss', '0.4', '-c:a', 'aac', '-b:a', '128k'], -0.40),
    ('6 dB quieter', 'mp3', ['-af', 'volume=-6dB', '-c:a', 'libmp3lame', '-b:a', '160k'], 0.0),
    ('WAV 48 kHz', 'wav', ['-ar', '48000'], 0.0),
]
print()
print(f'{"the listener\'s copy":52s} {"recognised as":18s} {"score":>5s} {"2nd best":>8s} {"shift found":>11s} {"error":>8s} {"length diff":>11s}')
worst = 0.0
t_match = []
for name, rel in songs.items():
    src = os.path.join(REPO, rel)
    for label, ext, args, shift in copies:
        dst = os.path.join(tmp, f'copy.{ext}')
        ff(src, dst, *args)
        t0 = time.time()
        u = pattern(decode(dst, FP_SECONDS + MAX_LAG))
        scored = sorted(((match(r, u), n) for n, (r, _) in refs.items()), key=lambda s: -s[0][0])
        t_match.append(time.time() - t0)
        (score, found), best = scored[0]
        second = scored[1][0][0]
        err = (found - shift) * 1000
        worst = max(worst, abs(err))
        print(f'{name + ": " + label:52s} {best:18s} {score:5.2f} {second:8.2f} {found * 1000:8.0f} ms {err:5.1f} ms {duration(dst) - refs[name][1]:8.2f} s')
print(f'\nworst error of the shift: {worst:.1f} ms; matching one copy against {len(refs)} songs took {np.mean(t_match) * 1000:.0f} ms on average (decoding included)')

print('\nfiles that must NOT be taken for a mapped song:')
others = [
    ('Hot Swing 10 BPM faster (same tune, other speed)', 'music/variants/hot_10.mp3'),
    ('Hot Swing 10 BPM slower', 'music/variants/hot_-10.mp3'),
    ('Slow Your Roll 10 BPM faster', 'music/variants/slow_10.mp3'),
    ('Hot Swing, the 50 s original (same start, other length)', 'music/chosen/Hot Swing - Kevin MacLeod - original 50s (144bpm, CC BY).mp3'),
]
for label, rel in others:
    p = os.path.join(REPO, rel)
    if not os.path.exists(p):
        print(f'  (missing) {rel}')
        continue
    u = pattern(decode(p, FP_SECONDS + MAX_LAG))
    scored = sorted(((match(r, u), n) for n, (r, _) in refs.items()), key=lambda s: -s[0][0])
    (score, found), best = scored[0]
    print(f'  {label:56s} best guess {best:18s} score {score:5.2f}, length differs by {duration(p) - refs[best][1]:8.2f} s')

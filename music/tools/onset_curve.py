"""The onset curve of a song: how hard the music "hits" every 10 ms. It is the song's rhythm, not its audio.

The app uses it to recognise a song that the listener adds from their own files (the song menu's "Upload", 2026-11-21):
the curve of the chosen file is slid along the curve of every mapped song (shared/.../Onsets.kt); where the two agree,
the file is that song, and the place of the best fit says how much later (or earlier) the music starts in the
listener's copy, so that the song's beat grid can be moved onto it. First written on 2026-10-29 for the Spotify test
page, which lined up what the phone heard with the song's timeline in the same way.

Usage: python -P music/tools/onset_curve.py <audio file> <out.json>          (needs ffmpeg and numpy)

The app computes exactly the same curve (Onsets.kt): the sound as one channel at 48 kHz, a Hann window of 2048 samples
every 480 samples, log-compressed magnitudes log(1 + 1000 |X|), the rise of each bin from 150 Hz to 6 kHz summed
(spectral flux), minus its moving average over 51 frames (0.5 s), negative parts set to 0. Change one, change both
(tools/check_songs.py compares them).

Output JSON: rate (frames per second, 100), t0 (seconds of frame 0's centre), scale (what 255 stands for),
values (0..255, one per frame).
"""
import json
import subprocess
import sys

import numpy as np

FS = 48000          # every curve is made at this sampling rate, whatever the file's own
N = 2048
F_LO, F_HI = 150.0, 6000.0
COMPRESS = 1000.0
DETREND = 51


def hop_of(fs):
    return int(round(fs / 100.0))


def flux(y, fs):
    """the raw spectral flux, one value per frame; frame n is centred at (n * hop + N / 2) / fs seconds"""
    hop = hop_of(fs)
    win = np.hanning(N + 1)[:N]     # the periodic Hann window, as in the app: 0.5 - 0.5 cos(2 pi i / N)
    lo, hi = int(np.ceil(F_LO * N / fs)), int(np.floor(F_HI * N / fs))
    count = 1 + (len(y) - N) // hop if len(y) >= N else 0
    out = np.zeros(count)
    prev = None
    for n in range(count):
        seg = y[n * hop:n * hop + N] * win
        c = np.log1p(COMPRESS * np.abs(np.fft.rfft(seg)[lo:hi + 1]))
        out[n] = 0.0 if prev is None else np.maximum(c - prev, 0.0).sum()
        prev = c
    return out


def detrend(f):
    """minus the moving average over DETREND frames (edges: the average of what is there), negatives to 0"""
    h = DETREND // 2
    cs = np.concatenate([[0.0], np.cumsum(f)])
    out = np.empty_like(f)
    for i in range(len(f)):
        a, b = max(0, i - h), min(len(f), i + h + 1)
        out[i] = f[i] - (cs[b] - cs[a]) / (b - a)
    return np.maximum(out, 0.0)


def quantise(d):
    """(scale, values 0..255): 255 stands for the value that 99.5 % of the frames stay under"""
    scale = float(np.percentile(d, 99.5)) or 1.0
    return scale, np.clip(np.round(d / scale * 255), 0, 255).astype(int)


def load(path, seconds=None):
    """the file's sound as one channel at FS, whatever it is (ffmpeg reads it)"""
    cmd = ['ffmpeg', '-v', 'error', '-i', path] + (['-t', str(seconds)] if seconds else []) + ['-ac', '1', '-ar', str(FS), '-f', 'f32le', '-']
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64), FS


def main():
    y, fs = load(sys.argv[1])
    scale, q = quantise(detrend(flux(y, fs)))
    json.dump({'file': sys.argv[1].replace('\\', '/').split('/')[-1], 'rate': fs / hop_of(fs),
               't0': N / 2 / fs, 'scale': scale, 'values': q.tolist(),
               'note': 'onset curve (spectral flux 150 Hz-6 kHz, detrended); see music/tools/onset_curve.py'},
              open(sys.argv[2], 'w'))
    print(f'{len(q)} frames at {fs / hop_of(fs):.1f}/s from {sys.argv[1]} -> {sys.argv[2]}')


if __name__ == '__main__':
    main()

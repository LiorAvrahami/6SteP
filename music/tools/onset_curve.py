"""The onset curve of a track: how hard the music "hits" every 10 ms, for the app to line up what it hears (the
microphone, or the phone's own output) with the song's known timeline. It is the song's rhythm, not its audio.

Usage: python -P music/tools/onset_curve.py <audio file> <out.json>

The app computes exactly the same curve live (OnsetSync.kt): mono, a Hann window of 2048 samples every fs/100
samples, log-compressed magnitudes log(1 + 1000 |X|), the rise of each bin from 150 Hz to 6 kHz summed (spectral
flux), minus its moving average over 51 frames (0.5 s), negative parts set to 0. Change one, change both.

Output JSON: rate (frames per second, 100), t0 (seconds of frame 0's centre), scale (what 255 stands for),
values (0..255, one per frame).
"""
import json
import sys

import numpy as np

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


def load(path):
    import soundfile as sf
    y, fs = sf.read(path, dtype='float32', always_2d=True)
    return y.mean(axis=1), fs


def main():
    y, fs = load(sys.argv[1])
    d = detrend(flux(y, fs))
    scale = float(np.percentile(d, 99.5)) or 1.0
    q = np.clip(np.round(d / scale * 255), 0, 255).astype(int)
    json.dump({'file': sys.argv[1].replace('\\', '/').split('/')[-1], 'rate': fs / hop_of(fs),
               't0': N / 2 / fs, 'scale': scale, 'values': q.tolist(),
               'note': 'onset curve (spectral flux 150 Hz-6 kHz, detrended); see music/tools/onset_curve.py'},
              open(sys.argv[2], 'w'))
    print(f'{len(q)} frames at {fs / hop_of(fs):.1f}/s from {sys.argv[1]} -> {sys.argv[2]}')


if __name__ == '__main__':
    main()

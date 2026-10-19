"""Beat grid for a track: when every count of the music happens, so footwork can be placed on it.

Usage:
  python -P tools/beatgrid.py <audio file> [approx bpm] [--first-downbeat SECONDS] [--out grid.json]

Method (works for sequenced tracks with a steady tempo, like Kevin MacLeod's):
  1. Onset-strength beat tracking gives rough beat times.
  2. Comb search: for every tempo within +-2 BPM of the rough one (0.01 BPM steps, then 0.001) and every
     phase, sum the onset strength sampled on the grid t0 + k*beat. The grid with the most energy wins.
     This is robust to swing pushes (melody notes just before the beat) that pull a snap-and-fit tempo
     off by 0.1 BPM, which is half a beat of drift over five minutes. The snap residual is still
     reported, as a steadiness measure only.
  3. Downbeats: the beat grid is tried at the four possible bar phases and the one whose bar-start
     beats have the strongest onsets wins. On syncopated swing this guess is unreliable: pass
     --first-downbeat SECONDS from listening or from the structure (for Hot Swing the first sound,
     1.70 s in the original, is the downbeat of the 2-bar intro).
  4. Swing: where the offbeat onsets fall inside the beat, as a fraction (0.5 straight, 0.67 triplet).

Output JSON: bpm, beat (s), t0 (time of beat 0), first_downbeat_beat, swing (offbeat fraction),
beats[] (time of every beat), bars[] (time of every bar start), and count_time(count) semantics below.

Reading the grid: count k of the music (0-based beat index) happens at  t = t0 + k * beat.
The swung "&" between beat k and k+1 happens at  t0 + (k + swing) * beat.
Bars are 4 beats; an 8-count phrase is 2 bars; the dance usually starts on the first downbeat after the
intro (for Hot Swing that is beat 8, t = 5.04 s).
"""
import argparse, json, sys
import numpy as np, librosa


def comb_search(onset, ot, bpm0, dur, span=2.0, step=0.01):
    """Tempo and phase that put the most onset energy on the grid."""
    best = (-1, None, None)
    for bpm in np.arange(bpm0 - span, bpm0 + span, step):
        beat = 60.0 / bpm
        n = int((dur - 0.3) / beat)
        phases = np.arange(0, beat, 0.002)
        k = np.arange(n)
        times = phases[:, None] + k[None, :] * beat
        score = np.interp(times, ot, onset).sum(axis=1)
        i = int(np.argmax(score))
        if score[i] > best[0]:
            best = (float(score[i]), float(bpm), float(phases[i]))
    return best[1], best[2]


def snap_residual(onset, ot, bpm, t0, dur):
    beat = 60.0 / bpm
    grid = np.arange(t0, dur - 0.2, beat)
    thr = np.percentile(onset, 40)
    res = []
    for k, t in enumerate(grid):
        m = (ot > t - 0.04) & (ot < t + 0.04)
        if m.any() and onset[m].max() > thr:
            res.append(ot[m][onset[m].argmax()] - t)
    res = np.array(res) * 1000
    return float(res.std()), float(abs(res).max()), len(res)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('audio'); ap.add_argument('bpm', nargs='?', type=float, default=None)
    ap.add_argument('--first-downbeat', type=float, default=None, help='time in seconds of a known downbeat')
    ap.add_argument('--out', default=None)
    a = ap.parse_args()
    y, sr = librosa.load(a.audio, sr=22050, mono=True)
    dur = len(y) / sr
    hop = 128
    onset = librosa.onset.onset_strength(y=y, sr=sr, hop_length=hop)
    ot = librosa.frames_to_time(np.arange(len(onset)), sr=sr, hop_length=hop)
    tempo, beats = librosa.beat.beat_track(onset_envelope=onset, sr=sr, hop_length=hop,
                                           start_bpm=a.bpm or 140, tightness=300, units='time')
    k = np.arange(len(beats)); slope, icpt = np.polyfit(k, beats, 1)
    bpm, t0 = comb_search(onset, ot, 60 / slope, dur, span=2.0, step=0.01)
    bpm, t0 = comb_search(onset, ot, bpm, dur, span=0.02, step=0.001)
    res_std, res_max, used = snap_residual(onset, ot, bpm, t0, dur)
    beat = 60.0 / bpm
    while t0 - beat >= 0:      # beat 0 = first beat at or after the file start
        t0 -= beat
    grid = np.arange(t0, dur, beat)
    # downbeat phase
    def ons_at(t): return float(np.interp(t, ot, onset))
    if a.first_downbeat is not None:
        phase = int(round((a.first_downbeat - t0) / beat)) % 4
    else:
        phase = int(np.argmax([np.mean([ons_at(t) for t in grid[p::4]]) for p in range(4)]))
    # swing: offbeat onset position relative to the beat
    ons = librosa.onset.onset_detect(onset_envelope=onset, sr=sr, hop_length=hop, units='time')
    first_onset = float(ons[0]) if len(ons) else None
    ph = ((ons - t0) / beat) % 1.0
    hist, _ = np.histogram(ph, bins=24, range=(0, 1)); beat_ph = (np.argmax(hist) + 0.5) / 24
    rel = (ph - beat_ph) % 1.0; off = rel[(rel > 0.33) & (rel < 0.85)]
    swing = float(np.median(off)) if len(off) else None
    out = dict(file=a.audio, duration=round(dur, 3), bpm=round(bpm, 3), beat=round(beat, 5), t0=round(t0, 4),
               fit_residual_ms=round(res_std, 1), fit_residual_max_ms=round(res_max, 0), beats_used=used,
               first_onset=round(first_onset, 3) if first_onset is not None else None,
               first_downbeat_beat=phase, downbeat_source='given' if a.first_downbeat is not None else 'onset energy (unreliable on syncopated music)',
               swing=round(swing, 2) if swing else None,
               beats=[round(float(t), 3) for t in grid], bars=[round(float(t), 3) for t in grid[phase::4]])
    print(f"{a.audio}: {bpm:.2f} bpm, beat {beat:.4f} s, beat 0 at {t0:.3f} s, first sound at {first_onset:.3f} s, downbeats from beat {phase}, "
          f"swing {swing:.2f} (0.5 straight, 0.67 triplet), fit residual {res_std:.1f} ms (max {res_max:.0f})")
    if a.out:
        json.dump(out, open(a.out, 'w'), indent=1); print('wrote', a.out)


if __name__ == '__main__':
    main()

"""Tracked beat grid for a track played by people (tempo drifts), same JSON layout as beatgrid.py.

Usage:
  python -P tools/beattrack.py <audio file> [approx bpm] [--first-downbeat SECONDS] [--smooth N] [--out grid.json]

Why: beatgrid.py fits ONE tempo to the whole file, which is right for sequenced tracks (Hot Swing: 7 ms
scatter) and wrong for a live band that drifts. On "Sunday Reflection" the single-tempo grid sits on
only 40% as much onset energy as the tracked beats, and the gap grows through the track.

Method:
  1. librosa beat tracking (dynamic programming over the onset envelope) gives one time per beat.
  2. Light smoothing: each beat time is replaced by a local linear fit over the N nearest beats
     (default 5), which removes single-beat jitter but follows real tempo changes.
  3. A local refinement: each beat may move up to 25 ms toward the nearest onset-strength peak.
  4. Downbeats: given with --first-downbeat, or guessed by onset energy (unreliable on swing).
  5. Swing: where the offbeat onsets sit inside the beat (0.5 straight, 0.67 triplet).

Output JSON: grid "tracked", bpm (median), beat (median), t0 (first beat), beats[] (one per beat,
NOT uniform), bars[], swing, first_onset, score (mean onset strength on the beats, for comparison with
beatgrid.py's fixed grid). Reading: count k is at beats[k]; the "&" after it at
beats[k] + swing * (beats[k+1] - beats[k]).
"""
import argparse
import json

import librosa
import numpy as np


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('audio')
    ap.add_argument('bpm', nargs='?', type=float, default=None)
    ap.add_argument('--first-downbeat', type=float, default=None)
    ap.add_argument('--smooth', type=int, default=5)
    ap.add_argument('--out', default=None)
    a = ap.parse_args()
    y, sr = librosa.load(a.audio, sr=22050, mono=True)
    dur = len(y) / sr
    hop = 128
    onset = librosa.onset.onset_strength(y=y, sr=sr, hop_length=hop)
    ot = librosa.frames_to_time(np.arange(len(onset)), sr=sr, hop_length=hop)
    _, beats = librosa.beat.beat_track(onset_envelope=onset, sr=sr, hop_length=hop, start_bpm=a.bpm or 130,
                                       tightness=200, units='time')
    beats = np.asarray(beats, dtype=float)
    # 2. local linear smoothing
    k = np.arange(len(beats))
    sm = beats.copy()
    half = max(1, a.smooth // 2)
    for i in range(len(beats)):
        lo, hi = max(0, i - half), min(len(beats), i + half + 1)
        if hi - lo >= 3:
            sl, ic = np.polyfit(k[lo:hi], beats[lo:hi], 1)
            sm[i] = sl * i + ic
    # 3. snap toward the nearest onset peak within 25 ms
    on = onset / onset.max()
    for i, t in enumerate(sm):
        m = (ot > t - 0.025) & (ot < t + 0.025)
        if m.any():
            j = np.argmax(on[m])
            if on[m][j] > 0.15:
                sm[i] = 0.5 * t + 0.5 * ot[m][j]
    iv = np.diff(sm)
    beat = float(np.median(iv))
    bpm = 60.0 / beat
    # 4. downbeats
    def ons_at(t):
        return float(np.interp(t, ot, on))
    if a.first_downbeat is not None:
        phase = int(np.argmin(np.abs(sm - a.first_downbeat))) % 4
    else:
        phase = int(np.argmax([np.mean([ons_at(t) for t in sm[p::4]]) for p in range(4)]))
    # 5. swing
    ons = librosa.onset.onset_detect(onset_envelope=onset, sr=sr, hop_length=hop, units='time')
    first_onset = float(ons[0]) if len(ons) else None
    rel = []
    for t in ons:
        i = np.searchsorted(sm, t) - 1
        if 0 <= i < len(sm) - 1:
            rel.append((t - sm[i]) / (sm[i + 1] - sm[i]))
    rel = np.array(rel)
    off = rel[(rel > 0.33) & (rel < 0.85)]
    swing = float(np.median(off)) if len(off) else None
    score = float(np.mean([ons_at(t) for t in sm]))
    out = dict(file=a.audio, grid='tracked', duration=round(dur, 3), bpm=round(bpm, 3), beat=round(beat, 5),
               t0=round(float(sm[0]), 4), interval_scatter_ms=round(float(iv.std() * 1000), 1),
               interval_min=round(float(iv.min()), 3), interval_max=round(float(iv.max()), 3),
               first_onset=round(first_onset, 3) if first_onset is not None else None,
               first_downbeat_beat=phase,
               downbeat_source='given' if a.first_downbeat is not None else 'onset energy (unreliable on syncopated music)',
               swing=round(swing, 2) if swing else None, score=round(score, 3),
               note='Tracked grid: beats[] are measured one by one and are not uniform; use them directly.',
               beats=[round(float(t), 3) for t in sm], bars=[round(float(t), 3) for t in sm[phase::4]])
    print(f"{a.audio}: tracked {len(sm)} beats, median {bpm:.2f} bpm, interval scatter {iv.std() * 1000:.0f} ms "
          f"(min {iv.min():.3f} max {iv.max():.3f} s), first sound {first_onset:.3f} s, downbeats from beat {phase}, "
          f"swing {swing:.2f}, onset score {score:.3f}")
    if a.out:
        json.dump(out, open(a.out, 'w'), indent=1)
        print('wrote', a.out)


if __name__ == '__main__':
    main()

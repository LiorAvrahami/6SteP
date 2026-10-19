"""Turn the marks of the beat annotator into a beat grid, and measure how the tempo moves.

    python music/tools/beat_annotator/grid_from_taps.py slow            # writes the grid + analysis_<key>.png, prints the numbers
    python music/tools/beat_annotator/grid_from_taps.py slow --no-write # numbers and plot only

Same interpolation as the page: constant tempo between consecutive marks (a whole number of beats near the running
period), the running period beyond the last mark, bar phase from the marks that are a 1 (else from the old grid).
"""
import os, sys, json, shutil, statistics, argparse
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
MUSIC = os.path.normpath(os.path.join(HERE, '..', '..', 'chosen'))
TRACKS = {'hot': 'Hot Swing - Kevin MacLeod - 5min loop (144bpm, CC BY)', 'sunday': '15 - Laughing Manny - Sunday Reflection (127bpm)', 'slow': '16 - Laughing Manny - Slow Your Roll (123bpm)'}


def interpolate(taps, period0, duration):
    ts = sorted(x['t'] for x in taps if isinstance(x.get('t'), (int, float)) and x['t'] >= 0)
    ts = [t for i, t in enumerate(ts) if i == 0 or t - ts[i - 1] > 0.08]
    if len(ts) < 2:
        return [], [], []
    sane = lambda x: x is not None and np.isfinite(x) and 0.15 < x < 2.5
    gaps = [b - a for a, b in zip(ts[:-1], ts[1:])]
    period = period0 if sane(period0) else statistics.median(gaps)
    out, p, segments = [], period, []
    for i, g in enumerate(gaps):
        n = min(400, max(1, round(g / p))); step = g / n
        for k in range(n): out.append(ts[i] + k * step)
        segments.append((ts[i], ts[i + 1], n, step))
        if sane(step): p = step
    out.append(ts[-1])
    first = gaps[0] / max(1, round(gaps[0] / period)); first = first if sane(first) else period
    before = []; t = ts[0] - first
    while t >= -1e-6 and len(before) < 5000: before.append(t); t -= first
    before.reverse()
    last = p if sane(p) else period
    after = []; t = ts[-1] + last
    while t <= duration and len(after) < 5000: after.append(t); t += last
    return before + out + after, ts, segments


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('key'); ap.add_argument('--no-write', action='store_true'); a = ap.parse_args()
    base = TRACKS[a.key]
    gp = os.path.join(MUSIC, base + ' - beatgrid.json'); apath = os.path.join(MUSIC, base + ' - beatgrid.analysis.json'); dp = os.path.join(MUSIC, base + ' - taps.json')
    old = json.load(open(apath if os.path.exists(apath) else gp, encoding='utf-8'))
    taps = json.load(open(dp, encoding='utf-8'))['taps']
    duration = old.get('duration', 0)
    beats, marks, segments = interpolate(taps, old.get('beat'), duration)
    if len(beats) < 2:
        print('fewer than two marks'); return
    beats = np.array(beats)
    # bars: from marks that are a 1, else the old grid's first bar
    downs = [x['t'] for x in taps if x.get('down')]
    if downs:
        counts = [0] * 4
        for d in downs: counts[int(np.argmin(np.abs(beats - d))) % 4] += 1
        phase = int(np.argmax(counts))
    else:
        phase = int(np.argmin(np.abs(beats - old['bars'][0]))) % 4 if old.get('bars') else 0
    bars = [float(b) for i, b in enumerate(beats) if i % 4 == phase]

    # ---- how the tempo moves: local BPM per marked segment, and the phase against a fixed metronome
    print(f"{base}: {len(marks)} marks from {marks[0]:.1f} to {marks[-1]:.1f} s, {len(beats)} beats, {len(bars)} bars")
    iv = np.diff(beats); loc = 60.0 / iv
    print(f"  beat period over the marked part: median {np.median(iv)*1000:.0f} ms = {60/np.median(iv):.1f} BPM; local BPM between marks ranges {loc.min():.1f} .. {loc.max():.1f}")
    seg_bpm = [(s[0], s[1], 60.0 / s[3]) for s in segments]
    print("  tempo per segment (start s -> end s: BPM):")
    for s0, s1, bpm in seg_bpm: print(f"    {s0:6.1f} -> {s1:6.1f}: {bpm:6.1f}")
    # the best constant tempo over the marked part, and how far each beat drifts from it (phase drift)
    k = np.arange(len(beats)); m = (beats >= marks[0]) & (beats <= marks[-1])
    A = np.vstack([k[m], np.ones(m.sum())]).T; (T, t0), *_ = np.linalg.lstsq(A, beats[m], rcond=None)
    resid = beats[m] - (t0 + T * k[m])
    print(f"  best single tempo over the marked part: {60/T:.2f} BPM; the beats wander up to {np.abs(resid).max()*1000:.0f} ms ({np.abs(resid).max()/T:.2f} beats) from that fixed metronome")
    # tempo trend: first third vs last third of the marked part
    third = len(iv) // 3
    print(f"  mean BPM first third {60/np.mean(iv[:third]):.1f}, middle {60/np.mean(iv[third:2*third]):.1f}, last third {60/np.mean(iv[2*third:]):.1f}")
    # beat-to-beat wobble inside the segments (the tempo changes removed): how uneven consecutive beats are
    steps = np.array([s[3] for s in segments]);
    # the old analysis grid against the marks
    ob = np.array(old['beats']); off = [(mk - ob[np.argmin(np.abs(ob - mk))]) * 1000 for mk in marks]
    print(f"  the previous analysis grid vs your marks: offsets from {min(off):.0f} to {max(off):.0f} ms (median {statistics.median(off):.0f} ms)")

    # ---- plot
    import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
    fig, ax = plt.subplots(3, 1, figsize=(12, 9), sharex=True)
    ax[0].plot(beats[1:], loc, '.', ms=3, color='#2e6ee6', label='local BPM between consecutive beats')
    for s0, s1, bpm in seg_bpm: ax[0].plot([s0, s1], [bpm, bpm], color='#eb50a0', lw=2)
    ax[0].plot([], [], color='#eb50a0', lw=2, label='tempo of each marked segment'); ax[0].axhline(60 / T, color='#888', ls='--', label=f'best single tempo {60/T:.1f}')
    for mk in marks: ax[0].axvline(mk, color='#ffb000', alpha=0.3, lw=1)
    ax[0].set_ylabel('BPM'); ax[0].legend(loc='upper right', fontsize=8); ax[0].set_title(base + ': tempo from your marks (yellow lines)')
    ax[1].plot(beats[m], resid * 1000, color='#2e6ee6'); ax[1].axhline(0, color='#888', ls='--'); ax[1].set_ylabel('ms late (+) or early (-) against a fixed metronome')
    ax[1].set_title('phase: where each beat falls against one constant tempo started in sync')
    ax[2].plot(marks, off, 'o-', color='#20b0c0', ms=3); ax[2].axhline(0, color='#888', ls='--'); ax[2].set_ylabel('ms'); ax[2].set_xlabel('seconds')
    ax[2].set_title('your marks minus the nearest beat of the previous automatic analysis')
    out_png = os.path.join(HERE, f'analysis_{a.key}.png'); fig.tight_layout(); fig.savefig(out_png, dpi=110); print('  plot:', out_png)

    if a.no_write: return
    if os.path.exists(gp) and not os.path.exists(apath) and old.get('grid') != 'annotated': shutil.copyfile(gp, apath)
    g = {'file': old.get('file', 'chosen/' + base + '.mp3'), 'duration': duration, 'grid': 'annotated', 'bpm': 60.0 / float(np.median(iv)), 'beat': float(np.median(iv)),
         't0': float(beats[0]), 'swing': old.get('swing', 0.67), 'first_downbeat_beat': phase, 'downbeat_source': 'annotated by ear in the beat annotator' if downs else 'the previous analysis (no mark was made as a 1)',
         'beats': [float(b) for b in beats], 'bars': bars, 'taps': taps, 'note': 'Beats interpolated and extrapolated from the marks in music/tools/beat_annotator; beats[] is not uniform, use it directly.'}
    json.dump(g, open(gp, 'w', encoding='utf-8'), indent=0)
    print('  wrote', os.path.basename(gp))


if __name__ == '__main__':
    main()

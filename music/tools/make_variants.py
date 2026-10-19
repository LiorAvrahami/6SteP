"""Tempo variants of the practice tracks: the same music at BPM offsets -40..+20 with the pitch unchanged, so the app
can slow a track down or speed it up without changing its key, and without a new beat grid: a variant at tempo
t = (bpm + offset) / bpm has every beat of the original grid at beats[i] / t seconds.

Usage (from the music folder):
  python -P tools/make_variants.py                       # build every missing variant into variants/, then verify
  python -P tools/make_variants.py --check               # only verify what is in variants/ and refresh the manifest
  python -P tools/make_variants.py --tracks slow --offsets -40 20 --force    # rebuild a subset
  python -P tools/make_variants.py --rubberband "C:\\path\\to\\rubberband.exe"

Engine, best available first (reported in the table and in variants.json):
  1. the Rubber Band command-line tool (https://breakfastquay.com/rubberband/, the "gpl-executable-windows" zip),
     R3 "--fine" engine in offline mode; found through --rubberband, the RUBBERBAND environment variable, PATH, or
     tools/rubberband*/rubberband.exe;
  2. ffmpeg's rubberband filter (R2 engine in realtime mode);
  3. ffmpeg atempo (plain WSOLA, audibly rougher on held notes).
Pipeline per variant: decode the mp3 to mono 48 kHz float WAV (ffmpeg, gapless-trimmed, mono = (L+R)/2 at half gain so the
stretcher never has to protect against clipping), stretch to duration / t, restore the gain and tame the rare peaks the
phase vocoder adds with a 30 ms look-ahead limiter (the loudness stays that of the original; a global gain drop would not),
encode with libmp3lame at 96 kbps mono 48 kHz, no tags. Nothing is trimmed at the start, so sample 0 stays sample 0.

Verification (--check, also run after a build): for the original and every variant, librosa's onset strength (sr 22050,
hop 256, interpolated to 1 ms) is correlated with a Gaussian-smoothed (sigma 8 ms) impulse train on the scaled grid
beats / t; the lag that maximises the correlation is found globally (-100..+100 ms) and in 15-second windows (windows of
the original timeline, scaled; searched within 80 ms of the file's global lag so the swing offbeat's own peak cannot be
taken for the beat). A variant passes when its duration is within 60 ms of original / t, its global lag is within 20 ms
of the original's, and every window lag is within 30 ms of the original's window lag. A second, grid-free test correlates
the variant's onset envelope (mapped back onto the original's timeline) with the original's: its "shift" is the stretcher's
smearing of the attacks and its window "drift" would show any non-linearity of the stretch.

Output: variants/<key>_<offset>.mp3 and variants/variants.json:
  {"<key>": {"base_bpm": .., "engine": "..", "variants": [{"offset": 0, "bpm": .., "tempo": 1.0, "file": "chosen/..mp3",
   "duration": ..}, {"offset": -40, .., "file": "variants/<key>_-40.mp3", "duration": .., "check": "pass", "lag_ms": ..}]}}
"""
import argparse, glob, json, os, shutil, subprocess, sys, tempfile, time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

MUSIC = Path(__file__).resolve().parent.parent
TRACKS = {
    'hot': 'Hot Swing - Kevin MacLeod - 5min loop (144bpm, CC BY)',
    'sunday': '15 - Laughing Manny - Sunday Reflection (127bpm)',
    'slow': '16 - Laughing Manny - Slow Your Roll (123bpm)',
}
OFFSETS = [-40, -30, -20, -10, 10, 20]
OUT = MUSIC / 'variants'
LAGS_MS = range(-100, 101)
WINDOW_S = 15.0
WINDOW_BAND_MS = 80   # a window's lag is searched this close to the file's global lag (see lag_profile)
PRESCALE = 0.5   # the mono mix is halved before the stretch (restored after): a stretcher that sees clipping lowers the whole file
LIMIT = 0.95     # peak ceiling of a variant (-0.45 dBFS, room for the codec's own overshoot); only the rare peaks above it are touched


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        raise RuntimeError(f"{cmd[0]} failed ({r.returncode}): {(r.stderr or r.stdout)[-2000:]}")
    return r


def find_rubberband(explicit):
    """The Rubber Band CLI if we have one that runs, else None."""
    cands = [explicit, os.environ.get('RUBBERBAND'), shutil.which('rubberband-r3'), shutil.which('rubberband')]
    cands += sorted(glob.glob(str(MUSIC / 'tools' / 'rubberband*' / '**' / 'rubberband.exe'), recursive=True), reverse=True)
    for c in cands:
        if c and Path(c).is_file():
            try:
                v = subprocess.run([c, '--version'], capture_output=True, text=True, timeout=60)
                if v.returncode == 0:
                    return c, (v.stdout or v.stderr).strip()
            except (OSError, subprocess.SubprocessError):
                pass
    return None, None


def pick_engine(args):
    if args.engine in ('rubberband-cli', 'auto'):
        exe, ver = find_rubberband(args.rubberband)
        if exe:
            return {'name': 'rubberband-cli', 'label': f'Rubber Band CLI {ver} (R3 --fine, offline)', 'exe': exe}
        if args.engine == 'rubberband-cli':
            sys.exit('no working rubberband.exe: pass --rubberband PATH or set RUBBERBAND')
        print('no Rubber Band CLI found, falling back to the ffmpeg rubberband filter')
    filters = run(['ffmpeg', '-hide_banner', '-filters']).stdout
    if args.engine in ('ffmpeg-rubberband', 'auto') and ' rubberband ' in filters:
        return {'name': 'ffmpeg-rubberband', 'label': f'ffmpeg rubberband filter (R2, transients={args.transients})', 'transients': args.transients}
    if args.engine == 'ffmpeg-rubberband':
        sys.exit('this ffmpeg has no rubberband filter')
    return {'name': 'atempo', 'label': 'ffmpeg atempo'}


def decode(mp3, wav):
    """mp3 -> mono 48 kHz float WAV, mono = (L+R)/2 (ffmpeg's own -ac 1 would add 3 dB) at PRESCALE gain. ffmpeg honours
    the LAME gapless header, so the music starts where the beat grid says."""
    run(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-i', str(mp3), '-af',
         f'aformat=channel_layouts=stereo,pan=mono|c0={PRESCALE / 2}*c0+{PRESCALE / 2}*c1', '-ar', '48000', '-c:a', 'pcm_f32le', str(wav)])


def stretch(engine, src, dst, tempo):
    """Write src slowed/sped so that its duration becomes duration / tempo, pitch unchanged."""
    if engine['name'] == 'rubberband-cli':
        # offline mode: the whole file is studied first, the start delay is compensated and the output length is exactly
        # ratio * input; --ignore-clipping because the input is pre-scaled and the limiter below handles the peaks
        run([engine['exe'], '--fine', '-q', '--ignore-clipping', '-t', f'{1 / tempo:.9f}', str(src), str(dst)])
    elif engine['name'] == 'ffmpeg-rubberband':
        run(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-i', str(src), '-af',
             f"rubberband=tempo={tempo:.9f}:transients={engine['transients']}:pitchq=quality", '-c:a', 'pcm_f32le', str(dst)])
    else:
        run(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-i', str(src), '-af', f'atempo={tempo:.9f}', '-c:a', 'pcm_f32le', str(dst)])


def restore_and_limit(wav):
    """Undo PRESCALE in place and keep the peaks under LIMIT: the gain follows the lowest needed gain within 30 ms on
    either side of a peak, smoothed with a 60 ms Hann window, so it never overshoots and ramps too slowly to be heard.
    Returns the peak before limiting and a note about what the limiter did."""
    import numpy as np, soundfile as sf
    from scipy.ndimage import minimum_filter1d
    from scipy.signal import fftconvolve
    x, sr = sf.read(str(wav), dtype='float64')
    x /= PRESCALE
    peak = float(np.abs(x).max()); note = ''
    if peak > LIMIT:
        half = int(round(0.030 * sr))
        need = np.minimum(1.0, LIMIT / np.maximum(np.abs(x), 1e-9))
        win = np.hanning(2 * half + 1)
        # edge-padded so the first and last 30 ms are not faded by the smoothing
        gain = fftconvolve(np.pad(minimum_filter1d(need, 2 * half + 1), half, mode='edge'), win / win.sum(), mode='valid')
        x *= np.minimum(gain, need)
        note = f', limiter on {int((need < 1).sum())} samples (peak {peak:.2f}, lowest gain {20 * np.log10(gain.min()):.1f} dB)'
    sf.write(str(wav), x.astype(np.float32), sr, subtype='FLOAT')
    return peak, note


def encode(wav, mp3):
    run(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-i', str(wav), '-ac', '1', '-ar', '48000', '-c:a', 'libmp3lame', '-b:a', '96k',
         '-compression_level', '0', '-map_metadata', '-1', '-id3v2_version', '0', '-write_id3v1', '0', str(mp3)])


def wav_seconds(path):
    import soundfile as sf
    i = sf.info(str(path))
    return i.frames / i.samplerate


def grid(key):
    return json.load(open(MUSIC / 'chosen' / (TRACKS[key] + ' - beatgrid.json'), encoding='utf-8'))


def variant_rows(key):
    g = grid(key)
    bpm = float(g['bpm'])
    rows = [{'offset': 0, 'bpm': bpm, 'tempo': 1.0, 'file': 'chosen/' + TRACKS[key] + '.mp3', 'duration': g.get('duration')}]
    for off in OFFSETS:
        rows.append({'offset': off, 'bpm': bpm + off, 'tempo': (bpm + off) / bpm, 'file': f'variants/{key}_{off}.mp3', 'duration': None})
    return bpm, rows


def load_manifest():
    p = OUT / 'variants.json'
    return json.load(open(p, encoding='utf-8')) if p.exists() else {}


def save_manifest(m):
    json.dump(m, open(OUT / 'variants.json', 'w', encoding='utf-8'), indent=1)


# ---------------------------------------------------------------- build

def build(args, engine):
    OUT.mkdir(exist_ok=True)
    work = Path(args.work); work.mkdir(parents=True, exist_ok=True)
    manifest = load_manifest()
    jobs = []
    for key in args.tracks:
        bpm, rows = variant_rows(key)
        entry = manifest.setdefault(key, {})
        entry['base_bpm'] = bpm
        old = {r['offset']: r for r in entry.get('variants', [])}
        entry['variants'] = [old.get(r['offset'], r) | {k: r[k] for k in ('bpm', 'tempo', 'file')} for r in rows]
        src_mp3 = MUSIC / 'chosen' / (TRACKS[key] + '.mp3')
        src_wav = work / f'{key}_src.wav'
        decode(src_mp3, src_wav)
        entry['variants'][0]['duration'] = round(wav_seconds(src_wav), 4)
        before = len(jobs)
        for row in entry['variants'][1:]:
            if row['offset'] not in args.offsets:
                continue
            out = MUSIC / row['file']
            if out.exists() and not args.force:
                print(f'  keep {out.name}')
                continue
            jobs.append((key, row, src_wav, out))
        if len(jobs) > before or 'engine' not in entry:
            entry['engine'] = engine['label']
    save_manifest(manifest)

    def one(job):
        key, row, src_wav, out = job
        t = time.time()
        tmp = work / f"{key}_{row['offset']}.wav"
        stretch(engine, src_wav, tmp, row['tempo'])
        _, note = restore_and_limit(tmp)
        encode(tmp, out)
        row['duration'] = round(wav_seconds(tmp), 4)
        for k in ('check', 'lag_ms', 'shift_ms', 'drift_ms'):     # stale verification of the old file
            row.pop(k, None)
        tmp.unlink()
        print(f"  {out.name}: tempo {row['tempo']:.4f}, {row['duration']:.2f} s, {out.stat().st_size / 1e6:.2f} MB, {time.time() - t:.0f} s{note}", flush=True)

    print(f'{len(jobs)} variants to make with {engine["label"]}, {args.jobs} at a time')
    with ThreadPoolExecutor(max_workers=args.jobs) as ex:
        list(ex.map(one, jobs))
    save_manifest(manifest)
    return manifest


# ---------------------------------------------------------------- check

def onset_env_ms(path):
    """Onset strength of a file on a 1 ms axis, and the decoded duration in seconds."""
    import numpy as np, librosa
    y, sr = librosa.load(str(path), sr=22050, mono=True)
    env = librosa.onset.onset_strength(y=y, sr=sr, hop_length=256)
    t = librosa.times_like(env, sr=sr, hop_length=256)
    ms = np.arange(0.0, len(y) / sr, 0.001)
    return np.interp(ms, t, env), len(y) / sr


def lag_profile(env, beats, tempo, windows):
    """Lag (ms, positive = the onsets come after the grid) maximising the correlation of a Gaussian-smoothed impulse train
    at beats / tempo with the onset envelope: globally and per window ((start, end) in original seconds)."""
    import numpy as np
    from scipy.ndimage import gaussian_filter1d
    n = len(env)
    train = np.zeros(n)
    idx = np.round(np.asarray(beats) / tempo * 1000).astype(int)
    idx = idx[(idx >= 0) & (idx < n)]
    np.add.at(train, idx, 1.0)
    train = gaussian_filter1d(train, 8.0)
    starts = np.array([min(n, int(round(w[0] / tempo * 1000))) for w in windows])
    ends = np.array([min(n, int(round(w[1] / tempo * 1000))) for w in windows])
    lags = np.array(LAGS_MS)
    g = np.empty(len(lags)); w = np.empty((len(lags), len(windows)))
    for j, lag in enumerate(lags):
        p = np.zeros(n)
        if lag >= 0:
            p[:n - lag] = train[:n - lag] * env[lag:]
        else:
            p[-lag:] = train[-lag:] * env[:n + lag]
        cs = np.concatenate([[0.0], np.cumsum(p)])
        g[j] = cs[-1]; w[j] = cs[ends] - cs[starts]
    lag = int(lags[np.argmax(g)])
    # a window's lag is the beat peak nearest the file's lag: the swing offbeat makes a second peak 130-140 ms before the
    # beat, which comes within reach of the +-100 ms range once the tempo changes and wins in windows where the "&" is accented
    w[np.abs(lags - lag) > WINDOW_BAND_MS] = -np.inf
    return lag, lags[np.argmax(w, axis=0)].astype(int)


def envelope_shift(env_ref, env, tempo, windows):
    """Drift test that needs no grid: the variant's onset envelope, mapped back onto the original's timeline, is correlated
    with the original's envelope; the shift that maximises it (in ms of the variant's own time, + = the variant's onsets
    come later than beats / tempo predicts) is found globally and per window. A linear stretch gives the same shift in
    every window; the shift itself is the stretcher's smearing of the attacks, not a grid error."""
    import numpy as np
    n = len(env_ref)
    mapped = np.interp(np.arange(n) * 0.001 / tempo, np.arange(len(env)) * 0.001, env)
    a = env_ref - env_ref.mean(); b = mapped - mapped.mean()
    starts = np.array([min(n, int(round(w[0] * 1000))) for w in windows])
    ends = np.array([min(n, int(round(w[1] * 1000))) for w in windows])
    lags = np.array(LAGS_MS)
    g = np.empty(len(lags)); w = np.empty((len(lags), len(windows)))
    for j, lag in enumerate(lags):
        p = np.zeros(n)          # p[t]: the variant at t against the original at t - lag, so lag > 0 = the variant is later
        if lag >= 0:
            p[lag:] = b[lag:] * a[:n - lag]
        else:
            p[:n + lag] = b[:n + lag] * a[-lag:]
        cs = np.concatenate([[0.0], np.cumsum(p)])
        g[j] = cs[-1]; w[j] = cs[ends] - cs[starts]
    return float(lags[np.argmax(g)] / tempo), lags[np.argmax(w, axis=0)] / tempo


def check_windows(beats, duration):
    """15 s windows of the original timeline; a short tail (under 8 beats) is merged into the previous window."""
    import numpy as np
    edges = list(np.arange(0.0, duration, WINDOW_S)) + [duration]
    wins = [(a, b) for a, b in zip(edges[:-1], edges[1:])]
    beats = np.asarray(beats)
    if len(wins) > 1 and ((beats >= wins[-1][0]) & (beats < wins[-1][1])).sum() < 8:
        wins[-2:] = [(wins[-2][0], wins[-1][1])]
    return wins


def check(args, manifest):
    """Alignment table. lag: grid-based lag of the whole file (ms, + = onsets after the grid); win: largest difference
    between a window's lag and the original's lag in that window; shift / drift: envelope_shift of the whole file and the
    largest window deviation from it. pass = duration within 60 ms of original / tempo, |lag - original lag| <= 20 ms,
    win <= 30 ms."""
    import numpy as np
    print('\nchecking every variant against the original (ms; lag + = onsets after the grid, shift + = later than the original)')
    header = f"{'track':7}{'offset':>7}{'tempo':>8}{'duration':>10}{'expected':>10}{'lag':>5}{'win':>5}{'shift':>7}{'drift':>7}{'result':>8}{'MB':>7}"
    print(header); lines = [header]
    all_ok = True; failures = []
    for key in args.tracks:
        g = grid(key)
        entry = manifest.get(key)
        if not entry:
            print(f'{key}: nothing built yet'); continue
        orig = MUSIC / entry['variants'][0]['file']
        env0, dur0 = onset_env_ms(orig)
        wins = check_windows(g['beats'], dur0)
        lag0, wl0 = lag_profile(env0, g['beats'], 1.0, wins)
        entry['variants'][0].update(duration=round(dur0, 4), lag_ms=lag0)
        line = f"{key:7}{0:>7}{1.0:>8.4f}{dur0:>10.2f}{dur0:>10.2f}{lag0:>5}{0:>5}{'-':>7}{'-':>7}{'ref':>8}{orig.stat().st_size / 1e6:>7.2f}"
        print(line, flush=True); lines.append(line)
        for row in entry['variants'][1:]:
            path = MUSIC / row['file']
            if not path.exists():
                row['check'] = 'missing'; all_ok = False; failures.append(f"{key} {row['offset']:+d}: missing")
                line = f"{key:7}{row['offset']:>7}{row['tempo']:>8.4f}{'-':>10}{dur0 / row['tempo']:>10.2f}{'-':>5}{'-':>5}{'-':>7}{'-':>7}{'missing':>8}{'-':>7}"
                print(line, flush=True); lines.append(line); continue
            env, dur = onset_env_ms(path)
            lag, wl = lag_profile(env, g['beats'], row['tempo'], wins)
            shift, ws = envelope_shift(env0, env, row['tempo'], wins)
            wdiff = int(np.abs(wl - wl0).max()); drift = float(np.abs(ws - shift).max())
            why = []
            if abs(dur - dur0 / row['tempo']) > 0.060: why.append(f'duration off by {dur - dur0 / row["tempo"]:+.3f} s')
            if abs(lag - lag0) > 20: why.append(f'lag {lag - lag0:+d} ms from the original')
            bad = [f'{wins[i][0]:.0f}-{wins[i][1]:.0f} s: {int(wl[i])} vs {int(wl0[i])}' for i in range(len(wins)) if abs(wl[i] - wl0[i]) > 30]
            if bad: why.append('window lag ' + ', '.join(bad))
            ok = not why; all_ok &= ok
            if why: failures.append(f"{key} {row['offset']:+d}: " + '; '.join(why))
            row.update(duration=round(dur, 4), lag_ms=int(lag), shift_ms=round(shift, 1), drift_ms=round(drift, 1), check='pass' if ok else 'FAIL')
            line = (f"{key:7}{row['offset']:>7}{row['tempo']:>8.4f}{dur:>10.2f}{dur0 / row['tempo']:>10.2f}{lag:>5}{wdiff:>5}{shift:>7.1f}{drift:>7.1f}"
                    f"{row['check']:>8}{path.stat().st_size / 1e6:>7.2f}")
            print(line, flush=True); lines.append(line)
    save_manifest(manifest)
    total = sum(p.stat().st_size for p in OUT.glob('*.mp3')) / 1e6
    print(f'\n{"all variants pass" if all_ok else "SOME VARIANTS FAIL"}; variants/ holds {total:.1f} MB of mp3')
    for f in failures:
        print('  ' + f)
    return all_ok, lines


def main():
    ap = argparse.ArgumentParser(description=(__doc__ or '').split('\n\nUsage')[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--tracks', nargs='+', default=list(TRACKS), choices=list(TRACKS))
    ap.add_argument('--offsets', nargs='+', type=int, default=OFFSETS, choices=OFFSETS)
    ap.add_argument('--check', action='store_true', help='only verify the existing variants and refresh the manifest')
    ap.add_argument('--no-check', action='store_true', help='build without the verification pass')
    ap.add_argument('--force', action='store_true', help='rebuild variants that already exist')
    ap.add_argument('--engine', default='auto', choices=['auto', 'rubberband-cli', 'ffmpeg-rubberband', 'atempo'])
    ap.add_argument('--rubberband', help='path to rubberband.exe (else RUBBERBAND env var, PATH, tools/rubberband*/)')
    ap.add_argument('--transients', default='crisp', choices=['crisp', 'mixed', 'smooth'], help='ffmpeg rubberband filter only')
    ap.add_argument('--work', default=os.path.join(tempfile.gettempdir(), 'swing5_variants'), help='folder for the WAV intermediates')
    ap.add_argument('--jobs', type=int, default=max(1, min(4, (os.cpu_count() or 2) // 2)))
    args = ap.parse_args()
    manifest = load_manifest()
    if not args.check:
        engine = pick_engine(args)
        print('engine:', engine['label'])
        manifest = build(args, engine)
    if not args.no_check:
        ok, _ = check(args, manifest)
        sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()

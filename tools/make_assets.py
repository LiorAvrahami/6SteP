"""Builds the non-code assets of the 6SteP app from the dance and music project: skeletons, compositions, tracks with
their beat grids and tempo variants, and the metronome sounds. Run from anywhere: python tools/make_assets.py
(the launcher icon is made by design/make_icon.py)."""
import os, json, shutil, math, wave, struct
import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
SW = os.path.dirname(HERE)   # the project root: dance/, music/ and tools/ (this file) live here
R = SW                       # the Android project shares that root (app/, design/)
A = os.path.join(R, 'app', 'src', 'main', 'assets')
RES = os.path.join(R, 'app', 'src', 'main', 'res')
for d in ['dance/beatgrids', 'music']:
    os.makedirs(os.path.join(A, d), exist_ok=True)
for d in ['raw']:
    os.makedirs(os.path.join(RES, d), exist_ok=True)

# ---- dance data: skeletons as they are; compositions with the "daily exercise" name the app looks for
# an element still being built in the editor (no steps for a dancer, a bad count label) must not reach the app
import sys as _sys
_sys.path.insert(0, os.path.join(SW, 'dance'))
import model as _model
_unfinished = [f"{e['name']} [from {e['start_state']}, leader free {e.get('leader_free_start', 'L')}]: {_model.element_problem(e)}"
               for e in _model.load()['elements'] if _model.element_problem(e)]
if _unfinished:
    _sys.exit('REFUSING: elements not finished in the editor would reach the app:\n  ' + '\n  '.join(_unfinished))
# the app reads one file: assembled from dance/skeletons/ (one file per element)
with open(os.path.join(A, 'dance', 'skeletons.json'), 'w', encoding='utf-8') as _f:
    json.dump(_model.load(), _f, indent=1, ensure_ascii=False); _f.write('\n')
json.load(open(os.path.join(SW, 'dance', 'compositions.json'), encoding='utf-8'))   # fails here on broken JSON
shutil.copyfile(os.path.join(SW, 'dance', 'compositions.json'), os.path.join(A, 'dance', 'compositions.json'))   # as written: revisions, quiet entries

# ---- music and beat grids
TR = [
    ('hot', 'Hot Swing - Kevin MacLeod - 5min loop (144bpm, CC BY)', 'Hot Swing', 'Kevin MacLeod (incompetech.com)', 'CC BY 4.0'),
    ('sunday', '15 - Laughing Manny - Sunday Reflection (127bpm)', 'Sunday Reflection', 'Laughing Manny & The Swinging Dicks', 'CC BY-NC-SA'),
    ('slow', '16 - Laughing Manny - Slow Your Roll (123bpm)', 'Slow Your Roll', 'Laughing Manny & The Swinging Dicks', 'CC BY-NC-SA'),
]
tracks = []
for key, base, title, artist, lic in TR:
    shutil.copyfile(os.path.join(SW, 'music', 'chosen', base + '.mp3'), os.path.join(A, 'music', key + '.mp3'))
    g = json.load(open(os.path.join(SW, 'music', 'chosen', base + ' - beatgrid.json'), encoding='utf-8'))
    slim = {k: g[k] for k in ('bpm', 'beat', 't0', 'swing', 'duration', 'beats', 'bars') if k in g}
    json.dump(slim, open(os.path.join(A, 'dance', 'beatgrids', key + '.json'), 'w'))
    diff = {'slow': 'hard: the tempo moves, the band plays with the beat', 'sunday': 'steady', 'hot': 'steady (sequenced)'}.get(key, 'steady')
    tracks.append({'key': key, 'title': title, 'artist': artist, 'licence': lic, 'bpm': round(g['bpm'], 1), 'duration': g['duration'], 'file': 'music/' + key + '.mp3', 'grid': 'dance/beatgrids/' + key + '.json',
                   'difficulty': diff, 'grid_kind': g.get('grid', 'fixed')})
# ---- the Spotify sync test page's beat map (2026-10-29): "Like It Is" is played in the user's own Spotify, so only its
# grid ships, never the audio. The tap-corrected grid: beats from 79 to 90 s moved to the user's taps.
_g = json.load(open(os.path.join(SW, 'music', 'spotify_test', 'Like It Is - beatgrid (tap-corrected).json'), encoding='utf-8'))
json.dump({k: _g[k] for k in ('bpm', 'beat', 't0', 'swing', 'duration', 'beats', 'bars') if k in _g},
          open(os.path.join(A, 'dance', 'beatgrids', 'spotify_like_it_is.json'), 'w'))

# ---- tempo variants: the same music time-stretched offline (music/tools/make_variants.py, Rubber Band R3), pitch unchanged.
# Offset 0 is the original file; a beat of the track's grid lands at beats[i] / tempo seconds in a variant file.
VAR = os.path.join(SW, 'music', 'variants')
manifest = json.load(open(os.path.join(VAR, 'variants.json'), encoding='utf-8')) if os.path.exists(os.path.join(VAR, 'variants.json')) else {}
for t in tracks:
    key = t['key']; vs = []
    for v in sorted(manifest.get(key, {}).get('variants', []), key=lambda v: v['offset']):
        if v.get('check', 'pass') != 'pass':
            print('  skipping a variant that failed its check:', key, v['offset']); continue
        if v['offset'] == 0:
            vs.append({'offset': 0, 'bpm': t['bpm'], 'tempo': 1.0, 'file': t['file']}); continue
        src = os.path.join(SW, 'music', v['file'].replace('/', os.sep)); dst_name = f"{key}_{v['offset']}.mp3"
        shutil.copyfile(src, os.path.join(A, 'music', dst_name))
        vs.append({'offset': v['offset'], 'bpm': round(v['bpm'], 2), 'tempo': round(v['tempo'], 6), 'file': 'music/' + dst_name})
    if not any(v['offset'] == 0 for v in vs): vs.insert(0, {'offset': 0, 'bpm': t['bpm'], 'tempo': 1.0, 'file': t['file']})
    t['variants'] = vs
json.dump({'tracks': tracks}, open(os.path.join(A, 'dance', 'tracks.json'), 'w'), indent=1)

# ---- metronome sounds: a snare drum (the 1) and a closed hi-hat (the & and the 2), synthesised, 44.1 kHz 16-bit mono
def wav(path, samples):
    s = np.clip(samples, -1, 1)
    with wave.open(path, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(44100)
        w.writeframes((s * 32767).astype('<i2').tobytes())
sr = 44100
def finish(x, peak_db, fade_out):
    """1 ms linear fade-in and a raised-cosine fade-out (seconds) that ends on exactly 0, then the peak set to peak_db dBFS."""
    i, o = int(sr * 0.001), int(sr * fade_out)
    x[:i] *= np.arange(i) / i
    x[-o:] *= 0.5 + 0.5 * np.cos(np.linspace(0, np.pi, o))
    return x / np.abs(x).max() * 10 ** (peak_db / 20)
bp = lambda lo, hi, x: signal.sosfiltfilt(signal.butter(2, [lo, hi], 'bandpass', fs=sr, output='sos'), x)
# snare: a drum body of two decaying partials (185 and 340 Hz, as in the 808) whose pitch starts a fifth high and settles
# within 10 ms, plus the "snares": white noise from 1.5 kHz up with a 2.5-5 kHz crack, a sharp 6 ms snap and a 38 ms tail.
# The noise carries 4 dB more energy than the body, which is what separates a snare from a tom.
ts = np.arange(int(sr * 0.2)) / sr
def partial(f0, tau): return np.sin(2 * np.pi * f0 * (ts + 0.5 * 0.003 * (1 - np.exp(-ts / 0.003)))) * np.exp(-ts / tau)
body = partial(185, 0.028) + 0.7 * partial(340, 0.02)
white = np.random.RandomState(6).randn(len(ts))
snares = (bp(1500, 9000, white) + 0.5 * bp(2500, 5000, white)) * (np.exp(-ts / 0.038) + 0.6 * np.exp(-ts / 0.006))
snares *= np.sqrt(np.sum(body ** 2) / np.sum(snares ** 2)) * 10 ** (4 / 20)
wav(os.path.join(RES, 'raw', 'snare.wav'), finish(body + snares, -1, 0.02))
# closed hi-hat, the 808 recipe: six square waves summed, high-passed at 7 kHz and band-passed 8-11 kHz (the metallic
# cluster), then a fast decay (11 ms time constant, -40 dB after ~50 ms) with an extra 2 ms snap; a bit quieter than the snare
tb = np.arange(int(sr * 0.3)) / sr
bank = sum(signal.square(2 * np.pi * f * tb) for f in (205.3, 304.4, 369.6, 522.7, 540.0, 800.0))
metal = bp(8000, 11000, signal.sosfiltfilt(signal.butter(4, 7000, 'high', fs=sr, output='sos'), bank))
th = np.arange(int(sr * 0.075)) / sr
hat = metal[len(tb) // 2:len(tb) // 2 + len(th)] * (np.exp(-th / 0.011) + 0.5 * np.exp(-th / 0.002))
wav(os.path.join(RES, 'raw', 'hihat.wav'), finish(hat, -4, 0.015))
for old in ('thump.wav', 'tick.wav'):  # the previous kick-like thump and sine tick
    if os.path.exists(os.path.join(RES, 'raw', old)): os.remove(os.path.join(RES, 'raw', old))

# ---- the launcher icon is not made here: design/make_icon.py draws it (the user's design, since v11). The five
# workshop options in design/icons/option*.png were drawn by an earlier version of this script and are kept as they are.

print('assets ready:', len(tracks), 'tracks,', sum(os.path.getsize(os.path.join(A, 'music', f)) for f in os.listdir(os.path.join(A, 'music'))) // 1048576, 'MB of music in', len(os.listdir(os.path.join(A, 'music'))), 'files')

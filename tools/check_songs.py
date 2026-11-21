"""Checks that the app recognises a listener's own copy of a song the way the tools expect (the song menu's "Upload").

The app makes a song's onset curve in Kotlin (shared/.../Onsets.kt); the curves that ship with the app are made in
Python (music/tools/onset_curve.py). This runs both on the three bundled songs and compares them frame by frame, and
has the Kotlin code recognise copies of the songs as a listener might have them (re-compressed, with silence added at
the start, with the start cut off) and refuse files that are not the song (the same tune at another speed, a shorter
cut of the same recording).

Run: python3 tools/check_songs.py        (needs ffmpeg, numpy and gradle; exit code 1 when something is wrong)
"""
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'music', 'tools'))
import numpy as np
import onset_curve as oc

SONGS = {
    'slow': 'music/chosen/16 - Laughing Manny - Slow Your Roll (123bpm).mp3',
    'sunday': 'music/chosen/15 - Laughing Manny - Sunday Reflection (127bpm).mp3',
    'hot': 'music/chosen/Hot Swing - Kevin MacLeod - 5min loop (144bpm, CC BY).mp3',
}
# (label, the song it is a copy of, file extension, ffmpeg arguments, the shift the copy must show in seconds)
COPIES = [
    ('MP3 128 kbit/s', 'slow', 'mp3', ['-c:a', 'libmp3lame', '-b:a', '128k'], 0.0),
    ('AAC 96 kbit/s', 'sunday', 'm4a', ['-c:a', 'aac', '-b:a', '96k'], 0.0),
    ('Opus 64 kbit/s', 'slow', 'ogg', ['-c:a', 'libopus', '-b:a', '64k'], 0.0),
    ('MP3 64 kbit/s, one channel, 22 kHz', 'sunday', 'mp3', ['-c:a', 'libmp3lame', '-b:a', '64k', '-ac', '1', '-ar', '22050'], 0.0),
    ('0.7337 s of silence added at the start', 'slow', 'wav', ['-af', 'adelay=733.7:all=1'], 0.7337),
    ('the first 0.4013 s cut off', 'sunday', 'wav', ['-ss', '0.4013'], -0.4013),
]
# files that are not one of the songs
OTHERS = [
    ('Hot Swing 10 BPM faster (the same tune at another speed)', 'music/variants/hot_10.mp3'),
    ('Slow Your Roll 10 BPM slower', 'music/variants/slow_-10.mp3'),
    ('Hot Swing, the 50 s original (the same beginning, another length)', 'music/chosen/Hot Swing - Kevin MacLeod - original 50s (144bpm, CC BY).mp3'),
]


def raw(path, out):
    """the file's sound as the app gets it from the browser: one channel at 48 kHz, 32-bit floats"""
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', path, '-ac', '1', '-ar', str(oc.FS), '-f', 'f32le', out], check=True)
    return out


def main():
    tmp = tempfile.mkdtemp(prefix='sixstep_songs_')
    fixture = {'songs': [], 'copies': []}
    for key, rel in SONGS.items():
        pcm = raw(os.path.join(ROOT, rel), os.path.join(tmp, key + '.f32'))
        y = np.fromfile(pcm, dtype=np.float32).astype(np.float64)
        scale, q = oc.quantise(oc.detrend(oc.flux(y, oc.FS)))
        fixture['songs'].append({'name': key, 'pcm': pcm, 'scale': scale, 'values': q.tolist()})
        print('the Python tool: %-7s %6.1f s, %d frames' % (key, len(y) / oc.FS, len(q)))
    for n, (label, key, ext, args, shift) in enumerate(COPIES):
        made = os.path.join(tmp, 'copy%d.%s' % (n, ext))
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', os.path.join(ROOT, SONGS[key]), '-vn', *args, made], check=True)
        fixture['copies'].append({'label': key + ': ' + label, 'song': key, 'shift': shift, 'pcm': raw(made, os.path.join(tmp, 'copy%d.f32' % n))})
    for n, (label, rel) in enumerate(OTHERS):
        fixture['copies'].append({'label': label, 'song': '', 'shift': 0.0, 'pcm': raw(os.path.join(ROOT, rel), os.path.join(tmp, 'other%d.f32' % n))})
    path = os.path.join(tmp, 'onsets_fixture.json')
    json.dump(fixture, open(path, 'w'))
    print('%d songs, %d copies and %d other files -> %s' % (len(SONGS), len(COPIES), len(OTHERS), path))
    env = dict(os.environ, SIXSTEP_ONSETS_FIXTURE=path, SIXSTEP_SKIP_ANDROID='1')
    cmd = ['gradle', '--no-daemon', '-q', ':shared:jvmTest', '--tests', 'org.lior.sixstep.OnsetsTest', '--rerun-tasks', '-i'] if '-v' in sys.argv else \
          ['gradle', '--no-daemon', '-q', ':shared:jvmTest', '--tests', 'org.lior.sixstep.OnsetsTest', '--rerun-tasks']
    done = subprocess.run(cmd, cwd=ROOT, env=env)
    for f in os.listdir(tmp):
        os.remove(os.path.join(tmp, f))
    os.rmdir(tmp)
    if done.returncode != 0:
        sys.exit('THE KOTLIN CODE AND THE PYTHON TOOL DO NOT AGREE (see the test report in shared/build/reports/tests)')
    print('the Kotlin curves are the Python tool\'s; every copy was recognised with its shift, no other file was taken for a song')


if __name__ == '__main__':
    main()

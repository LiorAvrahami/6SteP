# 6SteP

A swing-dance practice app for Android: the daily exercise danced on the music, a top view of both dancers' feet, and an
exploration floor to step through every element beat by beat.

The APK is built by GitHub Actions on every push to `main` and attached to a release:
**https://github.com/LiorAvrahami/6SteP/releases/latest** — download `6SteP.apk` on the phone and open it.

The dance data (`app/src/main/assets/dance/`) comes from the Swing5 project's skeletons and compositions.

## Music

- "Hot Swing" — Kevin MacLeod (incompetech.com), CC BY 4.0, looped to 5:15.
- "Sunday Reflection" and "Slow Your Roll" — Laughing Manny & The Swinging Dicks, CC BY-NC-SA. Non-commercial use only.

The snare and closed hi-hat of the metronome are synthesised, not recorded (snare on the odd counts, hi-hat on the swung "&"
and the even counts).

Every track also ships time-stretched to -40, -30, -20, -10, +10 and +20 BPM with the pitch unchanged (Rubber Band R3,
rendered offline by the Swing5 project's `music/tools/make_variants.py`; 96 kbps mono). The beat grid of a track is scaled
by the same factor in the app, so the steps and the metronome stay on the beat at every tempo.

## While a composition plays

Tap: pause / continue. Swipe left: the next annotation block. Swipe right: replay this block, or the previous one when this
one just began (2 s rule). Swipe down: the options bar, with the track to play next, the snare and hi-hat on or off, the
tempo (the BPM offset, kept for the whole session), and "explore", which pauses and opens the element being danced in the
exploration floor at the same beat; back returns to the paused workout. The app holds audio focus for the session and
keeps its own volume while another app asks to duck it; the music dips to 60 percent under a spoken annotation and comes
back right after it.

## Signing

`app/keystore/sixstep.p12` is committed so that every build is signed with the same key and each APK installs over the
previous one. Anyone with this repository can sign an app that installs over 6SteP, so keep the repository private if that
matters.

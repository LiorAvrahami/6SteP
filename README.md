# 6SteP

A swing-dance practice app for Android: the daily exercise danced on the music, a top view of both dancers' feet, and an
exploration floor to step through every element beat by beat.

The APK is built by GitHub Actions on every push to `main` and attached to a release:
**https://github.com/LiorAvrahami/6SteP/releases/latest** — download `6SteP.apk` on the phone and open it.

The dance data (`app/src/main/assets/dance/`) comes from the Swing5 project's skeletons and compositions.

## Music

- "Hot Swing" — Kevin MacLeod (incompetech.com), CC BY 4.0, looped to 5:15.
- "Sunday Reflection" and "Slow Your Roll" — Laughing Manny & The Swinging Dicks, CC BY-NC-SA. Non-commercial use only.

The thump and tick of the metronome are synthesised, not recorded.

## Signing

`app/keystore/sixstep.p12` is committed so that every build is signed with the same key and each APK installs over the
previous one. Anyone with this repository can sign an app that installs over 6SteP, so keep the repository private if that
matters.

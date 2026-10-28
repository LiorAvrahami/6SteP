# 6SteP

A swing-dance practice app for Android: the daily exercise danced on the music, a top view of both dancers' feet, and
every element, building block and composition to step through beat by beat.

The APK is built by GitHub Actions on every push to `main` and attached to a release:
**https://github.com/LiorAvrahami/6SteP/releases/latest** — download `6SteP.apk` on the phone and open it.

The dance data (`app/src/main/assets/dance/`) comes from the Swing5 project's skeletons and compositions. A shipped
composition carries a `revision`; a newer revision replaces the copy on the phone unless the user edited that copy there
(the phone has no composition editor since the 2026-10-28 redesign; copies edited before still load).
An entry of a composition in parentheses, such as `(recapture underarm pass)`, is quiet: danced, but not announced and
not a stop for the swipes (a bridge between two elements that do not fit together, or a warm-up).

## The screens (the user's layout draft, `design/app_layout.free`, 2026-10-28)

- **Launcher**: the daily exercise (with how long it takes at the chosen song and tempo), explore, and a little floor that
  dances the daily exercise at 1.5 times its speed; the heart (thanks and links), settings, and help (two explanations
  with arrows where the little floor is).
- **Explore**: three groups that fold (Elements, Compositions, Building Blocks). Every row shows three pictures of
  consecutive steps, centred on the step of the piece that stands out most (the pose least like the poses of all the
  elements); drag a row sideways to move through its steps. A tap opens the dance floor on that step.
- **The dance floor**, one for everything (the daily exercise included). Swipe left or right: the next or previous step,
  in flow mode (the feet travel there) or schema mode (dashed lines from where each foot came). Swipe down: the bars; a
  tap on the floor closes them. Double tap or play: play (the bars close); a tap while playing pauses; a swipe while
  playing jumps to the next part, or back to the start of this one (the previous one in its first 2 s).
  Top bar: play/pause, the tempo (the song's tempo variants), the song (shuffle, or one song first), mute (music, drums
  and voice), flow/schema, edit (not on the phone yet). Bottom bar: every step, the current one framed, the annotations
  pointing at where they start; a tap on a step goes there. Compositions play once to the end; elements and building
  blocks loop. The app holds audio focus while it plays and keeps its own volume while another app asks to duck it; the
  music dips to 60 percent under a spoken annotation and comes back right after it.
- **Settings**: the snare and hi-hat over the music, the snare, the hi-hat, reading the annotations aloud.

Icons: [Lucide](https://lucide.dev) (ISC licence), converted by `design/make_ui_icons.py` from `design/lucide/`. Font:
[Inter](https://rsms.me/inter/) (SIL Open Font License). Both licences ship in `app/src/main/assets/licenses/`.
Before pushing app changes, `python tools/check_app.py` checks resources, ids and leftovers (there is no Android SDK on
the PC: GitHub compiles).

## Music

- "Hot Swing" — Kevin MacLeod (incompetech.com), CC BY 4.0, looped to 5:15.
- "Sunday Reflection" and "Slow Your Roll" — Laughing Manny & The Swinging Dicks, CC BY-NC-SA. Non-commercial use only.

The snare and closed hi-hat of the metronome are synthesised, not recorded (snare on the odd counts, hi-hat on the swung "&"
and the even counts).

Every track also ships time-stretched to -40, -30, -20, -10, +10 and +20 BPM with the pitch unchanged (Rubber Band R3,
rendered offline by the Swing5 project's `music/tools/make_variants.py`; 96 kbps mono). The beat grid of a track is scaled
by the same factor in the app, so the steps and the metronome stay on the beat at every tempo.

## Signing

`app/keystore/sixstep.p12` is committed so that every build is signed with the same key and each APK installs over the
previous one. Anyone with this repository can sign an app that installs over 6SteP, so keep the repository private if that
matters.

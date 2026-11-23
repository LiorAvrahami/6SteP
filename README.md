# 6SteP

A swing-dance practice app, for Android and for the browser: the daily exercise danced on the music, a top view of both
dancers' feet, and every element, building block and composition to step through beat by beat.

- **Android**: the APK is built by GitHub Actions on every push to `main` and attached to a release:
  **https://github.com/LiorAvrahami/6SteP/releases/latest** — download `6SteP.apk` on the phone and open it.
- **Web**: the same build publishes the page at **https://lioravrahami.github.io/6SteP/** (any phone or computer). On a
  phone it is sideways and takes the full screen at the first tap, wherever that tap lands (a browser gives a page the
  full screen only at a tap, never when it loads), and again at the next tap whenever it lost it; the launcher's
  fourth small button leaves the full screen for good, or takes it back. "Add to Home screen" in the browser's menu
  makes it open like an app, full screen from the first moment.

## One engine, two fronts (since 2026-11-20)

| Module | What it is |
|---|---|
| `shared/` | The dance engine, in Kotlin that knows nothing of Android or of the browser: the dance data, the composer, the timeline, the music's beat grids and plan, the drawing (shoes, arrows, the floor's camera, the strip of steps) through a small canvas interface, the dance floor's whole logic (stepping, play, jumps, annotations), the explore list, the texts. |
| `app/` | The Android app. Its drawing, its sound and its screens around the dance floor are still its own code; the dance data, the composer, the timeline, the beat grids and (since 2026-11-21) the dance floor's whole logic come from `shared/`. |
| `web/` | The web front, in Kotlin compiled to JavaScript: screens and sound for the browser, everything else from `shared/`. |

- `gradle :shared:jvmTest` tests the engine: everything shipped can be danced, and the shared texts say what the Android
  app's say. `python tools/check_engine.py` also compares the engine with the Python model (`dance/model.py`), step by
  step, and `python tools/check_songs.py` the recognition of a listener's own song file with the Python tool that makes
  the shipped curves.
- `gradle :web:jsBrowserDistribution` writes the page to `web/build/dist/js/productionExecutable` (serve that folder
  with any static web server to try it).
- `SIXSTEP_SKIP_ANDROID=1` in the environment leaves the Android app out of the build, where Google's package site and
  the Android SDK are not at hand; the engine and the web front then still build and test.
- `.github/workflows/check.yml` builds a working branch (`claude/**`) without publishing anything; `build.yml` builds
  `main`: the tests, the signed APK as a release, and the web front to the `gh-pages` branch.

The dance data (`app/src/main/assets/dance/`) comes from the Swing5 project's skeletons and compositions. A shipped
composition carries a `revision`; a newer revision replaces the copy on the phone unless the user edited that copy there
(the phone has no composition editor since the 2026-10-28 redesign; copies edited before still load).
An entry of a composition in parentheses, such as `(recapture underarm pass)`, is quiet: danced, but not announced and
not a stop for the swipes (a bridge between two elements that do not fit together, or a warm-up).

## The screens (the user's layout draft, `design/app_layout.free`, 2026-10-28)

- **Launcher**: the daily exercise (with how long it takes at the chosen song and tempo), explore, and a little floor that
  dances the "quick recap" (every element once, in the order they were learned) at 1.5 times its speed; the heart (thanks and links), settings, and help (two explanations
  with arrows where the little floor is); on the web page a fourth small button, the full screen.
- **Explore**: three groups that fold. Elements: the user's own list, in their order, whether each is an element or a
  building block underneath (rock step, rock triple triple, triple step, side to side, follower underarm and back,
  follower underarm pass, rock back cutoff, lead underarm pass, circle, follower underarm to arrow); Compositions, with
  the quick recap; Building Blocks: everything else. An element's versions are pills under its name when they all fit,
  otherwise a raised button with a count badge that opens a list. Every row shows three pictures of
  consecutive steps, centred on the step of the piece that stands out most (the pose least like the poses of all the
  elements); drag a row sideways to move through its steps. A tap opens the dance floor on the first step of the
  version shown.
- **The dance floor**, one for everything (the daily exercise included). Swipe left or right: the next or previous step.
  The feet travel there in a third of a beat, whatever the step, moving as they do when the dance plays; once they
  are there a dashed arrow shows the path each foot that stepped came by, with a small round arrow in the dancer's
  colour beside it when the foot turns more than a quarter turn, showing which way (one way of stepping since
  2026-11-17; before, a button chose between a "flow" and a "schema" mode).
  A swipe down or up brings the bars, and a swipe down or up sends them away. A tap on the floor plays (the bars go), and a tap while it plays pauses, at
  once: every tap is one, there is no double tap. A swipe while it plays jumps to the next part, or back to the start
  of this one (the previous one in its first 2 s). In a composition the dancers get to the part by a lead-in of four
  beats: they stand where the part starts, no foot moving, and shift their weight from leg to leg in the rhythm of
  two triple steps; then the part begins. The title says where it leads ("triple step — lead-in to send out and
  bring back"). A switch on the settings page turns the lead-in off.
  Top bar: play/pause, the tempo (the song's tempo variants), the song ("Play all": the songs in their stored order,
  round and round, which is what plays unless something else is chosen; one song over and over; or "Shuffle play"), mute
  (music, drums and voice), back. Every step has one place in the music (the plan
  of the songs along the dance), and play always starts afresh from the step on the screen: a new player sought to that
  place. Bottom bar: every step, the current one framed, the annotations
  pointing at where they start; a tap on a step goes there. Compositions play once to the end; elements and building
  blocks loop. While a composition plays, the name of each part comes up 2 beats before the part starts and goes away
  just as it starts; the voice reads it with how many times it repeats, the screen writes only the name. After a
  swipe the part's name comes up for the last 2 beats of the lead-in, written and not read aloud. The voice is
  Google Translate's, fetched as a short clip for every phrase, in the app and on the web page alike; the phone's or
  the browser's own voice reads when Google does not answer. The app holds audio focus while it plays and keeps its own volume while another app asks to duck it; the
  music dips to 60 percent under a spoken annotation and comes back right after it.
  **Hints:** what nothing on the screen says is shown to a new user by a hand (a closed hand, the index finger out,
  red lines on white), one hint at a time, for three things. The hand hovers two seconds where the gesture starts,
  then presses: for the tap that pauses or plays, three rings go out from the fingertip over a second; for a swipe,
  two circles come round the fingertip and it slides, the circles with it and a trail behind, then lifts. The swipe
  down brings the top bar in 36 dp with a little red arrow on it, and comes 1 s after a pause; the swipe to the side
  (a step on or back) comes 1 s after the swipe down. None for the bottom bar. Each movement 200 to 500 ms; none in the first 8 s after a dance
  opens; a hint waits until the screen has been left alone for a few seconds, then comes again and again, a random
  2 to 5 s after each showing, until the gesture is used; any touch makes it go; still pictures where the
  phone asks for less motion. Each goes for good the first time the user does the thing (a flag in
  the app's preferences or the browser's storage); a button in Settings brings them all back. A piece opens with its
  bars away.
- **Heart**: upright, as the user drew it (the rest of the app is sideways): the note and the email button. The
  draft's two other buttons, the website and "Buy me a Beer", are built and hidden for now (`Texts.HEART_MORE`).
- **Settings**: the lead-in after a swipe while a composition plays (on unless switched off: then a swipe goes straight
  to the part, 2 beats ahead of it); then the snare and hi-hat over the music, the snare, the hi-hat, reading the
  annotations aloud; and a button that shows the dance floor's hints again.

Icons: [Lucide](https://lucide.dev) (ISC licence), converted by `design/make_ui_icons.py` from `design/lucide/`, for the
Android app and for the web page alike. Font:
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

**The listener's own songs (the web page, since v62).** Other songs do not ship: the app knows their beats, and the
listener adds their own copy. The song menu's "Upload…" opens a window that lists those songs (for now "Like It Is") and
a button that opens the device's file chooser. The page listens to the chosen file, recognises the song by its rhythm
(its onset curve: `shared/.../Onsets.kt`, `music/tools/onset_curve.py`), finds where the music starts in this copy, and
keeps the file on the device; from then on it is one of the songs, at its own tempo only. A file that is not a known
song leads to an email that asks for it to be mapped. What the app knows of such a song is in `music/mapped/` (its beat
grid and its onset curve, never its audio).

## Signing

`app/keystore/sixstep.p12` is committed so that every build is signed with the same key and each APK installs over the
previous one. Anyone with this repository can sign an app that installs over 6SteP, so keep the repository private if that
matters.

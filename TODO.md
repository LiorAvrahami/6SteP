# TODO

Work the user decided to do later. Add to it with the `/todo` command (`.claude/commands/todo.md`), which writes a todo
down without doing it. When one is finished, move it to "Done" with the date and the release number.

## Open

### Feet that move around each other, not through each other
- **Added:** 2026-10-19, by the user ("that is a todo that we will need to settle later", 2026-10-22).
- **What:** "during the dance the feet go through each other instead of moving around each other ... this needs to be a
  calculated offline (not in real time)". A moving foot should travel on a path around the other feet, and turn the way
  the step really turns, instead of a straight line that turns the short way round.
- **Context:**
  - Today every foot travels in a straight line and turns by the smallest angle (`Timeline.kt`, `dance/render.py`, the
    editor's port). An audit on 2026-10-19 found 30 of 107 moving steps passing through another foot, and 4 turns drawn
    the wrong way round (three of them 180-degree turns in the circle). The positions have changed since; rerun it.
  - The user's rules: a foot with weight on it never moves, not even to pivot; a moving foot keeps about 4 cm clear of
    every other foot; where a foot lands never changes.
  - The step codes already hold most of the information: the digit with L or R gives each step's turn, `X` crosses in
    front of the standing foot and `H` hooks behind it. The lurklurk notes are in `reference/lurklurk/lindyhop.zip`
    (git-ignored, copyright) for anything the codes leave open.
  - The plan proposed on 2026-10-19 (not agreed yet): a script in `dance/` computes one curve per moving foot and the
    turn direction, into a generated file kept apart from `skeletons.json` (with a fingerprint of each step, so stale
    entries show); `check.py` gets a sweep test along the curves; the storyboards draw the curves; then `render.py`, the
    editor and the app use them.
  - A hard example (user 2026-10-22: "the follower recapture is example of hard to solve feet collision"): in the
    recapture underarm pass she turns left under the raised hands, and her own feet pass through each other several
    times. The 2026-10-19 audit, made before the spacing changes, found three of her steps going through her standing
    foot: her right foot on 3 and 5&, her left on 5. Use it as a test case for whatever method is chosen.
- **Questions for later:** whether the plan above is the way to do it.

### Hands
- **Added:** 2026-10-19, by the user ("the hands is a whole research project that I don't want to get into now").
- **What:** which hands are held, where, and when, for every element, and drawing the arms again.
- **Context:** the hand data and the reach check (now a warning) are still in the skeletons; nothing draws them
  (`SHOW_HANDS` / `showHands` in `dance/draw.py` and the editor; the app's arm drawing, off since v17, was removed in
  the 2026-10-28 redesign, while its hand data, `Timeline.handsAt` and the composer's hand changes are kept).

### New elements: side to side, pass to arrow, arrow to side, mess around
- **Added:** 2026-10-22, by the user.
- **What:** "add elements side to side, pass to arrow, arrow to side ... add mess around". Four new elements to author as
  skeletons, so that they can be explored and used in compositions.
- **Context:** elements are written in `dance/author_skeletons.py` (run `python dance/diff.py` first), checked with
  `python dance/check.py --storyboard`, and reach the app through `tools/make_assets.py`. An element exists once per
  starting condition (state and the leader's free foot). The states defined today are `side`, `facing` and
  `reversed_side`; there is no "arrow" state yet. The lurklurk notes are in `reference/lurklurk/lindyhop.zip`.
- **Questions for later:** what "arrow" is (a position, and where each dancer stands and faces in it); what "side to
  side" is and which state it starts and ends in; which lurklurk page, if any, each element follows; whether "mess
  around" is danced by both, and from which position.

### Rewrite the circle
- **Added:** 2026-10-22, by the user.
- **What:** "rewrite circle". Author the circle element again rather than adjusting the current one.
- **Context:** today's circle follows lurklurk's Lindy Circle (`m_lindy_circle`), 8 beats from face to face to side by
  side, with counts 5 and 6 exempt from the collision rule as a close hold. It was placed for a 70 cm open position and
  adapted to the 31 cm one in v17 by moving it 10 cm closer from count 3 (`CIRCLE_DY`) and giving two of her steps their
  own spots (`CIRCLE_SPOTS`), all in `dance/author_skeletons.py`. The user removed the circle from the daily exercise on
  2026-10-19; the sub-composition "pass+circle" still uses it.
- **Questions for later:** what is wrong with the current circle, and how the user dances it (start, end, turn, where
  each dancer goes).

### From the first maintainer's backlog (handover section 9)
- Exercises graded by the sections of Slow Your Roll where the beat moves (harder) or does not (easier).
- A licence agreement with Laughing Manny before any public release; more real-band saxophone swing tracks.
- On-device tempo stretching (`PlaybackParams`) as the small-APK alternative to the shipped tempo variants.
- Elements not built yet: Charleston, anything from `reversed_side`, the lead underarm pass from side by side.

### The Android app takes its screens' drawing from the shared engine
- **Added:** 2026-11-20, by the maintainer (the user: "try to have the shared part cover as much as possible").
- **Done so far:** since 2026-11-21 (v66) the Android dance floor runs the shared `DanceFloor`: `DanceFloorActivity` is
  its screen (a `FloorHost`), `Playback` its `FloorPlayer`. That switch was compiled on GitHub and read through, never
  run on a phone by the maintainer: if the Android dance floor behaves oddly, look there first.
- **What is left:** `FloorView` and `StepStrip` still hold their own copies of the drawing that `shared/` has
  (`FeetPainter`, `FloorPicture`, `StripPainter`), and `ExploreActivity` its own copy of `ExploreModel`. Switch them
  over, so that a change is made once: an Android `Draw` on `Canvas`, the views as thin shells. The sound last
  (handover section 10).
- **Context:** until this is done, a change to the floor's drawing or to the explore list has to be made in both
  places.

### Adding songs: what is left
- **Added:** 2026-11-19 and 2026-11-20, by the user; the web page got "Upload…" on 2026-11-21 (v62, handover section 10).
- **What is left:** the same in the Android app (a file picker, the file's sound through `MediaCodec`, a copy in the
  app's storage, `Playback` taking tracks from there; the recognition is shared code). A way to remove an added song.
  More songs than "Like It Is" (the user sends the recording, the maintainer maps it: handover section 10). The
  artist of "Like It Is", for the window's list.
- **Not yet seen by anybody:** the real "Like It Is" being recognised (the session has its beat map and onset curve,
  not its sound). If the user's own file is refused, ask which words the window showed: "sounds like" means the
  curve half fits (another cut or release), "does not know" means it does not fit at all.

### The web front: what the Android app has and the page does not yet
- **Added:** 2026-11-20, by the maintainer.
- **What:** working without a connection once opened (a service worker). On an iPhone, the Google voice (its browser
  starts an audio element only from a tap, so the page falls back to the browser's own voice there; untried).
  (Done 2026-11-21: sideways and full screen, and an icon and a name for "add to home screen"; then the whole look of
  the app, screen by screen, with the help view's arrows.)
- **The other way round:** the Android app's own menus, switches and messages are Android's plain ones, and the page
  copies them. If the user wants them in the app's own look (black outlines, the pink highlight), change both.

## Done

- **2026-11-21, v73:** a swipe up or down on the dance floor brings the bars when they are away and sends them away
  when they are shown (the user: "swiping up or down opens them and swiping up or down closes them").
- **2026-11-21, v72:** the hints after the user tried them: one quick animation (a swipe: two) every 4 seconds, four
  to five times quicker than v71's; the hint to play waits 4 s after a pause; a piece opens with its bars away and
  hints at the swipe down; Settings has "Show the dance floor's hints again".
- **2026-11-21, v71:** hints on the dance floor (the user: "the most minimalistic addition to the interface that will
  make it so that people will engage with it in the ways that they can"): five small pictures, one at a time, each
  with a flag that goes up for good when its gesture is used (handover section 6, "The hints"). Open with the user:
  whether the swipe to the left and right on the floor and the swipe up get hints too (asked on v71, not answered).
- **2026-11-21, v70:** the two face-to-face triple steps are counted 3, 3&, 4 and 5, 5&, 6 (they were written 1, 1&, 2;
  the user: "fix it"). Only the labels changed.
- **2026-11-21, v69:** the web page reads with Google's voice, as the Android app (the browser's own voice when
  Google does not answer); the lead-in after a swipe is two triple steps on the spot, four beats, instead of the rock
  triple triples of v66 (the user: "just 2 triple steps ... completely in place ... more like a pause"); "Rock
  Tripple Tripple facing" moved from the compositions to the building blocks; the underarm bringback became the
  follower underarm pass from face to face to side by side (only its name changed).
- **2026-11-21, v66:** "A lead-in of two rock triple triples after a jump while playing" (asked for on 2026-11-19, the
  user's answers of 2026-11-20, asked for again on 2026-11-21): after a swipe while a composition plays, the dancers
  dance two rock triple triples on the spot where the part starts, in its start position, and flow into it (one
  where only 6 beats of dance come before the part; handover section 6, "The dance floor"). Still a guess:
  compositions only (elements and building blocks loop and jump as before). Also in v66: a tap plays as well as
  pauses (no double tap); no edit button on the dance floor; the heart note without its second paragraph, the
  website and beer buttons hidden for now; the web page takes the full screen by itself at any tap, with the
  launcher's button to leave it; the Android dance floor on the shared `DanceFloor`.
- **2026-11-21, v67:** the lead-in's title names the part it leads to, from the swipe's first moment.
- **2026-11-21, v68:** the lead-in can be switched off on the settings page (the user: "make this feature toggleable
  in the settings so that it's possible to disable it"); off, a swipe goes straight to the part as before v66.
- **2026-11-21, v62 (web page):** "Adding songs: the listener's own files": the song menu's "Upload…", the window
  that lists the supported songs, the file chooser, the recognition by onset curve, the email for an unknown song.
- **2026-11-21, v61:** one tap pauses at once; "Play all" first and "Shuffle play" last in the song menu, the songs
  in the user's order; the voice says "yo-yo"; the web page laid out as the app, the heart page's website and beer
  buttons and the help view's arrows included.

- **2026-11-16, v40:** "Underarm circle: fix the foot placement": placed by the user.
- **2026-11-16, v40:** "Yoyo": YOYO (12 beats, side to side), written by the user from the "send out and bring
  back" copy.
- **2026-10-28, v19 (the redesign):** "the exploration floor's play honouring the snare and hi-hat toggles" (first
  maintainer's backlog) no longer applies: there is one dance floor, it plays on the music, the settings page chooses
  the drums and the mute button silences everything.

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

### A lead-in of two rock triple triples after a jump while playing
- **Added:** 2026-11-19, by the user.
- **What:** "if I go back or forwards, like during play, not when it's paused ... and it goes to the next or the previous
  annotation, then start by adding twice rock triple triple in place." After a swipe while a composition plays, the
  dancers first dance two rock triple triples on the spot, and only then the part starts. The user asked for an
  assessment first: "Don't add it now ... This is a future feature that I want you to think about."
- **Context** (the assessment given on 2026-11-19):
  - Today a swipe while playing lands 2 beats before the part (`skip()` and `jumpLead` in `DanceFloorActivity`), in the
    last beats of the element before it.
  - Checked with `dance/model.py`: every part of both compositions starts with the leader's left foot free. Parts that
    start side by side (8 of the exercise's 11): two rock triple triples take 12 beats and end exactly where they
    started. Parts that start face to face (follower underarm pass, lead underarm pass, tak turn): no triple step
    stays face to face, so there is no rock triple triple there; two of them side by side and then a quiet follower
    underarm pass (18 beats) can be composed. With the lead-in composed in front, the dance from the part on is the
    composition's own (the same versions; after one move and turn of the whole, the steps differ by 0.000 cm), so the
    picture can return to the composition's timeline without a jump.
  - What it takes in the app: compose the lead-in when a swipe lands (the existing composer); draw those beats from a
    second timeline, moved and turned onto the part's place, then go back to the main one; rework `skip()` (where each
    swipe lands, and which part counts as the current one for "back"); small things: the title and the bottom bar
    during the lead-in, a pause during it, a part with fewer beats before it than the lead-in needs (start from the
    beginning), the annotation still 2 beats before the part. The music needs nothing: it starts 12 or 18 beats earlier
    in the song. About one working session and a round of testing on the phone; the jump logic is where mistakes are
    likeliest.
  - If the engine moves into shared code for a web version first (handover section 10), write this once, there.
- **Answered by the user, 2026-11-20:** for parts that start face to face, use the rock step and the triple steps
  from face to face that they added that day ("I added one. its in GitHub. Use it instead"): two rock triple triples
  face to face compose now, 12 beats, ending face to face with the left foot free, the feet 8.3 cm from where they
  started. Only after swipes, not after a tap on a step in the bottom bar; the part's name is still not read aloud
  after a jump ("yes").
- **Where:** in `shared/src/commonMain/kotlin/org/lior/sixstep/DanceFloor.kt` (`skip()`), once for both fronts; and in
  `app/.../DanceFloorActivity.kt` too if the Android app has not been switched to the shared dance floor by then.
- **Still a guess:** compositions only (elements and building blocks loop and have no annotations).

### The Android app takes its screens' drawing and its dance floor from the shared engine
- **Added:** 2026-11-20, by the maintainer (the user: "try to have the shared part cover as much as possible").
- **What:** `FloorView`, `StepStrip` and `DanceFloorActivity` still hold their own copies of what `shared/` now has
  (`FeetPainter`, `FloorPicture`, `StripPainter`, `DanceFloor`, `ExploreModel`), which the web front uses. Switch the
  Android app over, so that a change is made once: an Android `Draw` on `Canvas`, the views as thin shells, the
  activity as a `FloorHost`, `Playback` as a `FloorPlayer`. The sound last (handover section 10).
- **Context:** until this is done, a change to the dance floor has to be made in both places.

### Adding songs: the listener's own files
- **Added:** 2026-11-19 and 2026-11-20, by the user.
- **What:** "there will be a button in the options to add songs, when clicked this will open a file selection page and
  when a song is selected it is hashed and compared to the songs that the app mapped out their steps ... if the song
  is not mapped then the user is instructed to send it to my email (and write the email and add a button that opens
  an email with the text "hi, please map out the song named *** for me, thanks!"". "added songs don't change tempo."
- **Context:** the recognition is prototyped and tested (`music/tools/song_match_proto.py`, handover section 10): the
  length plus the song's pattern of loudness. Still to do: port it to `shared/`, keep the fingerprints of the mapped
  songs with their beat grids, the button and the file picker in both fronts, where an added song is kept (the web
  page can lose it after a week on an iPhone), and how the user maps a song that is sent to them (the beat annotator).

### The web front: what the Android app has and the page does not yet
- **Added:** 2026-11-20, by the maintainer.
- **What:** the help view's arrows on the launcher, the heart page's website and beer buttons, working without a
  connection once opened (a service worker), the voice of the Android app (the page uses the browser's own voice).
  (Done 2026-11-21: sideways and full screen, and an icon and a name for "add to home screen".)

## Done

- **2026-11-16, v40:** "Underarm circle: fix the foot placement": placed by the user.
- **2026-11-16, v40:** "Yoyo": YOYO (12 beats, side to side), written by the user from the "send out and bring
  back" copy.
- **2026-10-28, v19 (the redesign):** "the exploration floor's play honouring the snare and hi-hat toggles" (first
  maintainer's backlog) no longer applies: there is one dance floor, it plays on the music, the settings page chooses
  the drums and the mute button silences everything.

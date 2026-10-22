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
  (`SHOW_HANDS` / `showHands` in `dance/draw.py`, `FloorView.kt` and the editor).

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
- The exploration floor's play honouring the snare and hi-hat toggles.
- Elements not built yet: Charleston, anything from `reversed_side`, the lead underarm pass from side by side.

## Done

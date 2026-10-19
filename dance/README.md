# dance/ — footwork skeletons, rule checks, video, editor

Built 2026-10-17, revised 2026-10-18 from the user's feedback. Sources: `handover-what-i-want.md`, `handover-questions-and-answers.md`,
`handover-reading-the-notes.md`, `footwork-sources.md` and the lurklurk notes in `reference/lurklurk/site-with-codes/`.

## What is here

| File | Role |
| --- | --- |
| `skeletons.json` | **The element data.** Every element's steps: count, dancer, foot, code, landing (x, y, heading), source tag, note. The editor writes this file. |
| `skeletons.baseline.json` | The same file as the agent last wrote it. Never touched by the editor. `diff.py` compares the two. |
| `compositions.json` | Compositions and sub-compositions: named lists of entries (an element, a sub-composition such as RTT, or either "x N"); edited in the app. |
| `author_skeletons.py` | The agent's authoring script: regenerates **both** skeleton files from placements written in code with their source notes. Do not run it after editing in the editor without first folding the edits back in (it overwrites). |
| `model.py` | Shared model: units, rule constants, facing states, element lookup, composing a sequence of elements into a world timeline, compositions. |
| `check.py` | Checks every skeleton against the hard rules and the notation; `--storyboard` draws one PNG per skeleton into `out/` for review by eye. Exit 1 on any error. |
| `render.py` | Element names or `--composition NAME` -> MP4 with the music, timed on the track's beat grid. |
| `draw.py` | Foot silhouettes with heading arrows, bodies, arms (shared by the storyboards and the video). |
| `diff.py` | Prints the user's edits (current vs baseline) step by step; `--accept` moves the baseline forward. |
| `editor/server.py`, `editor/index.html` | The web app on <http://127.0.0.1:8771> : element editor and composition viewer. |
| `out/` | Storyboards and rendered videos (generated; safe to delete). |

## Commands

```
python dance/check.py --storyboard                      # rules + out/storyboard_<element>_<state>_<free>.png
python dance/render.py --list                           # elements that exist, by start state and free foot
python dance/render.py --composition "rock double triple step" --track hot
python dance/render.py "rock step" "left triple step" "right triple step" --start side --track sunday --out dance/out/x.mp4
python dance/editor/server.py                           # then open http://127.0.0.1:8771
python dance/diff.py                                    # what the user changed in the editor (the user's message)
python dance/diff.py --accept                           # after the agent has responded to the diff
python dance/author_skeletons.py                        # regenerate skeletons.json AND the baseline from the authoring script
```

Needs Python 3.11 with Pillow, and ffmpeg on the PATH (the video). Nothing else.

## Elements (2026-10-18)

One element = one name; it has one skeleton per **starting condition** (start state, which of the leader's feet is free), and
the app's dropdown picks the variant. In a composition the starting condition of an element is dictated by the end of the
previous one, so the composer picks the variant itself and refuses a sequence that has none.

| Element | Starting conditions | Beats | Built from |
| --- | --- | --- | --- |
| rock step | side L/R, facing L/R | 2 | lurklurk counts 1-2; back step 30 cm (guess); the right-foot variants are mirrors ("same for rock step") |
| triple step | side L/R, facing L/R | 2 | 6-beat rhythm; side by side it drifts 10 cm to the side of the free foot (user); facing it is m_closed_basic's side-close-side |
| rock back cutoff | side L | 6 | leader placed by the user in the editor: both feet back, lunge across her path, back onto his own line 45 cm ahead facing back; she swings round behind him onto his right (agent); both end facing back |
| follower underarm pass | side L, facing L | 6 | from side: user Q2, confirmed 2026-10-18 (she turns clockwise); from facing: she turns anticlockwise as in m_changing_places (FR1L, TL, FR1L, BL1L, TR, BL1L) while he follows her round from behind (a step forward turned 90 right and a triple, twice; user 2026-10-18) |
| lead underarm pass | facing L | 6 | Changing Places with the roles swapped (user Q3), confirmed 2026-10-18 |
| recapture underarm pass | facing L | 6 | user 2026-10-18 (working name), brings the couple back to side by side; he rocks and triples in place. Since 2026-10-19 she turns LEFT (user): her codes are m_changing_places' follower (FR1L, TL, FR1L, BL1L, TR, BL1L), she faces east on 4 and backs round onto his right |
| circle | facing L | 8 | m_lindy_circle, hinge-out exit |

## Conventions

- **Units**: cm. Natural stance 20 cm between a dancer's feet (user). Foot silhouette 26 x 10 cm with a 9 cm arrow out of the toe
  showing where the foot faces (drawing only).
- **Frame**: each skeleton is written in the frame of its start: the leader's left foot at (0, 0), heading 0 = +y = the leader's
  forward, +x = his right. Heading in degrees, clockwise seen from above (lurklurk's `R` is positive).
- **Facing states** (`states` in the JSON) fix all four feet relative to the leader's left foot:
  `side` (follower on his right, both facing the same way, 45 cm between body centres: agent guess),
  `facing` (open hold, 70 cm between centres: user Q5), `reversed_side` (follower on his left; defined, no element leads there now).
  Each state also lists its default hand connections and that the leader starts with his left foot free (6-beat_rhythm.html).
- **Lookup key** for a skeleton: (element name, start state, which of the leader's feet is free) = the element and its starting condition.
- **Counts**: musical labels (`1`..`8`, `3&`). A two-beat triple keeps its own numbering (5, 5&, 6). The `&` lands at the
  track's measured swing fraction of the beat (0.68 on Hot Swing, 0.69 and 0.71 on the two Laughing Manny tracks).
- **Codes**: lurklurk notation (`eFL2R` = small forward step on the left turning 90 right; `Df`/`Db` diagonal, `X` cross in front,
  `T` close, `R` replace). `check.py` verifies that the feet alternate, that a replace lands where the foot already was, and that
  every heading change equals the code's rotation digit.
- **Source tags** on every step (`src`): `notes+guess` = feet, count, direction, rotation from the named lurklurk page, the
  distance is the agent's; `user+guess` = the shape from the user's words, the distance the agent's; `guess` = the agent's own.
- **Hands** (`hands` list per element: changes at a count, in force until the next change): `LL-FR` leader's left hand holds
  the follower's right; `LR-back` his right hand on her back; `FL-rshoulder` her left hand on his right shoulder; `LR-lhip`
  his right hand on her left hip; `LL-FR^F` the joined hands raised with the follower passing under them (`^L`: the leader).
  An empty list means no connection. Arms are drawn shoulder -> elbow -> hand; each has a maximum length (65 cm: agent guess)
  and no minimum, so the elbow is a slight outward bend. A red arm in the storyboard means the hand is out of reach.
- **Weight and the free foot**: a state fixes the weighted feet and the headings strictly; the free foot's resting place is
  nominal. `check.py` warns if an element leaves a free foot more than 30 cm from its spot; the next element moves it from
  where it actually is.

## The rules and how they are checked (`check.py`)

1. A weighted foot never moves or rotates: by construction (a step moves only the stepping foot) and by the replace check.
2. A dancer's two feet are never more than 70 cm apart: checked at every count.
3. Foot collision circles (diameter 20) of the two dancers never overlap: checked at every count.
4. Body circles (diameter 20 at the midpoint of the feet) never overlap: checked at every count.
5. Exemption (user Q6): steps marked `exempt` where the notes explicitly describe a close hold (the circle's counts 5 and 6).
   The report lists every exempted overlap; only the circle's count 6 (bodies 19 cm) uses it.
6. Hands in reach, end state reached (the follower's weighted foot matches the end state's geometry to 3 cm / 10 degrees),
   counts span the declared number of beats.

A failed check means the placements are wrong. There is no nudging, repulsion or correction layer anywhere; fix the data
or ask the user.

## The video (`render.py`)

- A foot lands exactly on its count. Its weight then ramps up over 0.3 beat (or 45 percent of the gap to the dancer's next
  step if that is shorter) while the other foot's weight ramps down. A foot travels only while its weight is zero: it is
  released when the other foot has taken the weight and arrives on its own count, with an ease-in-out. A replace does not
  travel. Opacity = weight, drawn over a grey silhouette at 0.2 (user Q7).
- Body circle = midpoint of the two feet, heading = mean of the feet's headings. Shoulders 18 cm either side.
- Camera: fixed if the whole sequence fits at 3.6 px/cm or more; otherwise it follows the couple, smoothed over 3 s.
- The dance starts on the first bar of the track that begins at or after 3 s (`--start-bar N` overrides); the start position is
  shown until then. The video ends one beat after the last step; the audio is cut there. If the dance runs past the end of the
  track the video stops where the music does and the renderer says so (Sunday Reflection is 2:34, Slow Your Roll about 2:10,
  Hot Swing 5:15). Rendering runs at about 40 frames per second, so a four-minute video takes about three minutes.
- Top left: the element being danced; top right: the count.

## The web app (`editor/`)

`python dance/editor/server.py`, open <http://127.0.0.1:8771> . Two modes, switched at the top of the right panel:

- **elements**: pick an element and its starting condition (the dropdown); the floor shows the selected steps (feet in the
  dancer's colour, the count beyond the toe arrow), where every foot stood before the first selected step (faint grey), the
  start state (outlines), and the bodies after the last selected step. Click selects a step, shift-click extends the range,
  up/down move the selection by one and wrap round, shift+up/down grow it at that edge, ctrl+shift+up/down shrink it.
  Left-drag moves a selected foot (1 cm), right-drag rotates it (5 degrees, alt for 1). Ctrl-z undoes. Wheel zooms, dragging
  empty floor pans, "fit view" resets. "save edits" writes `skeletons.json`; "show diff" and "run check" run the two scripts.
- **compositions**: pick, create ("new composition"), edit (name, start state, repeat, add / reorder / remove entries with
  an "x N" count) and delete compositions, then "save compositions" writes `compositions.json`. An entry is an element, a
  **sub-composition** (a named list of elements that other compositions use by name, e.g. RTT = rock step, triple step,
  triple step; the checkbox turns a composition into one) or either "x N". Every row shows the starting and ending condition
  of the whole entry as dictated by the previous one; the add list only offers what can follow. The map strip lists the
  composed elements with their sub-composition group; the floor and the step flow show the composed timeline, read-only.
  "5 minutes exercise" is the daily routine (user 2026-10-18): RTT x12, follower passes, RTT, lead passes, recapture, RTT,
  pass+recapture x8, RTT, cutoffs x12, RTT, pass+circle x6, RTT; 558 beats = 3:52 on Hot Swing.
- **play** (space bar too): the music runs on continuously (the whole track, looped, with a one-bar count-in) and the
  element or composition is danced on it, in the browser, with the same weight and travel model as `render.py`. If the
  dance alone would not loop (shorter than a bar, or ending with the other foot free) the server pads it with triple steps
  picked by the composer from the end condition, named "triple step filler" on screen. A loop that closes (same state and
  free foot at its end) is chained for the whole track, so the dance continues from where the dancers are with no jump,
  and the camera follows the couple when the chain does not fit the floor. An element whose end state differs from its
  start (the passes, the circle) restarts from its start position each time, and the status line says so.
- **render video**: runs `render.py` on the chosen track and plays the mp4 in the corner of the floor. The file lands in `out/`.

Edits are a message: the agent reads `python dance/diff.py` first and responds to it before changing anything, then `--accept`
moves the baseline. The page's logic is covered by a Node test with a stubbed DOM (selection, dragging, rotating, undo, save,
variants, compositions editing, the timeline port against `render.py`'s numbers).

## Known gaps

- Elements that do not exist yet: follower underarm pass from facing (user: she turns 270), lead underarm pass from side by
  side, anything Charleston, anything from reversed side.
- The lurklurk pages give no distances. Every distance here is the agent's guess, marked as such.

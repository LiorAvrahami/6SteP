# dance/ — footwork skeletons, rule checks, video, editor

Built 2026-10-17, revised 2026-10-18 from the user's feedback. **Since 2026-10-25 the user writes every element by hand in
the web editor** (`python RunWeb.py`); the lurklurk notes are disconnected (the step codes, source tags and notes are gone
from the data), and the elements and compositions the 5 minutes exercise does not use are set aside in `old_elements/`.

## What is here

| File | Role |
| --- | --- |
| `skeletons/` | **The element data, one file per element** (since 2026-10-25, so that several people can edit different elements and git merges them): `elements/<name>__<start state>__<free foot>.json` holds one element's steps (count, dancer, foot, landing x, y, heading), `_states.json` the facing states. `model.load()` assembles the usual document; `model.save()` writes back only what changed. The editor writes it after every edit; `tools/make_assets.py` assembles one `skeletons.json` for the app. |
| `compositions.json` | Compositions and sub-compositions: named lists of entries (an element, a sub-composition such as RTT, or either "x N"); edited in the editor. |
| `old_elements/` | The elements and compositions set aside on 2026-10-25, with the retired lurklurk authoring script, its baseline and `diff.py`. See its README. |
| `model.py` | Shared model: units, rule constants, facing states, element lookup, composing a sequence of elements into a world timeline, compositions. |
| `check.py` | Checks every skeleton against the hard rules; `--storyboard` draws one PNG per skeleton into `out/` for review by eye. Exit 1 on any error. |
| `render.py` | Element names or `--composition NAME` -> MP4 with the music, timed on the track's beat grid. |
| `draw.py` | Feet as shoe soles (no arrow or letter, as in the app), bodies, arms (shared by the storyboards and the video). |
| `shoe.py` | The shoe-sole outline every picture uses: `draw.py`, `design/make_icon.py`, and a Kotlin copy in the app's `FloorView.kt`. |
| `editor/server.py`, `editor/index.html` | The web app on <http://127.0.0.1:8771> : element editor and composition viewer. |
| `out/` | Storyboards and rendered videos (generated; safe to delete). |

## Commands

```
python dance/check.py --storyboard                      # rules + out/storyboard_<element>_<state>_<free>.png
python dance/render.py --list                           # elements that exist, by start state and free foot
python dance/render.py --composition "rock double triple step" --track hot
python dance/render.py "rock step" "left triple step" "right triple step" --start side --track sunday --out dance/out/x.mp4
python RunWeb.py                                        # the editor: starts the server and opens http://127.0.0.1:8771
```

Needs Python 3.11 with Pillow, and ffmpeg on the PATH (the video). Nothing else.

## Elements (2026-10-18; set aside 2026-10-25)

Since 2026-10-25 `skeletons.json` holds only the elements the 5 minutes exercise uses: rock step (side L), triple step
(side L and R), rock back cutoff, follower underarm pass (side and facing), lead underarm pass, recapture underarm pass. The
others (rock step side R and facing, triple step facing, circle) are in `old_elements/skeletons.json`. The "built from" column
below is history: the lurklurk codes it names are no longer in the data.

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

- **Units**: cm. Natural stance 20 cm between a dancer's feet (user). Feet are drawn as shoe soles 26 x 10 cm (`shoe.py`): the
  pointed toe shows where the foot faces and sits on the big-toe side (drawing only; the editor still draws arrows).
- **Frame**: each skeleton is written in the frame of its start: the leader's left foot at (0, 0), heading 0 = +y = the leader's
  forward, +x = his right. Heading in degrees, clockwise seen from above (lurklurk's `R` is positive).
- **Facing states** (`states` in the JSON) fix all four feet relative to the leader's left foot:
  `side` (follower on his right, both facing the same way, his right shoe and her left shoe 4 cm apart = 34 cm between body centres, user 2026-10-19; it was 45, an agent guess),
  `facing` (open hold, the facing shoes 5 cm apart toe to toe = 31 cm between centres, user 2026-10-19; it was 70, user Q5), `reversed_side` (follower on his left; defined, no element leads there now).
  Each state also lists its default hand connections and that the leader starts with his left foot free (6-beat_rhythm.html).
- **Lookup key** for a skeleton: (element name, start state, which of the leader's feet is free) = the element and its starting condition.
- **Counts**: musical labels (`1`..`8`, `3&`). A two-beat triple keeps its own numbering (5, 5&, 6). The `&` lands at the
  track's measured swing fraction of the beat (0.68 on Hot Swing, 0.69 and 0.71 on the two Laughing Manny tracks).
- **A step** is count, dancer, foot, x, y, heading, and nothing else (since 2026-10-25; the lurklurk codes, source tags and
  notes are gone). `check.py` verifies that the feet alternate.
- **Hands** (`hands` list per element: changes at a count, in force until the next change): `LL-FR` leader's left hand holds
  the follower's right; `LR-back` his right hand on her back; `FL-rshoulder` her left hand on his right shoulder; `LR-lhip`
  his right hand on her left hip; `LL-FR^F` the joined hands raised with the follower passing under them (`^L`: the leader).
  An empty list means no connection. Arms are drawn shoulder -> elbow -> hand; each has a maximum length (65 cm: agent guess)
  and no minimum, so the elbow is a slight outward bend. Since 2026-10-19 the arms and the body circles are not drawn
  (`draw.SHOW_HANDS`, `draw.SHOW_BODIES`; the app and the editor have the same switches): hands are set aside for now.
- **Weight and the free foot**: a state fixes the weighted feet and the headings strictly; the free foot's resting place is
  nominal. `check.py` warns if an element leaves a free foot more than 30 cm from its spot; the next element moves it from
  where it actually is.

## The rules and how they are checked (`check.py`)

1. A weighted foot never moves or rotates: by construction (a step moves only the stepping foot).
2. A dancer's two feet are never more than 70 cm apart: checked at every count.
3. The shoe outlines of the two dancers (`shoe.py`) stay at least 4 cm apart (`model.MIN_GAP`): checked at every count with
   `shoe.gap`. User 2026-10-19; until then 20 cm circles on the foot centres, which forbade the 4 cm side gap.
4. Body circles (diameter 20 at the midpoint of the feet) never overlap: checked at every count.
5. Exemption (user Q6): steps marked `exempt` where the notes explicitly describe a close hold (the circle's counts 5 and 6).
   The report lists every exempted overlap; only the circle's count 6 (bodies 19 cm) uses it.
6. Hands in reach (a warning since 2026-10-19: hands are set aside), end state reached (the follower's weighted foot matches the end state's geometry to 3 cm / 10 degrees),
   counts span the declared number of beats.

A failed check means the placements are wrong. There is no nudging, repulsion or correction layer anywhere; fix the data
or ask the user.

## The video (`render.py`)

- Before the first step both dancers stand in the neutral stance with the weight on both feet; the weight moves onto the
  foot that stays over 0.3 beat, ending as the first foot leaves (user 2026-10-19). The storyboard's start panel shows it.
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

`python RunWeb.py` starts the server (ending any older one on the port) and opens <http://127.0.0.1:8771>. Two tabs at the
top of the right panel. **Every edit is saved to disk at once** (`skeletons/`, `compositions.json`); there is no save
button and no other version. The page knows the version of the files it read: when they changed meanwhile (another person,
the agent, another tab), the server refuses the save and the page merges its own changed elements into the newer files; with
nothing unsaved it picks up such a change by itself. Before saves the server keeps dated copies in `out/backups/` (also of
any save it refuses). The page warns when the server runs older code than the files on disk.

- **elements**: pick an element and its starting condition (the dropdown). The selection goes **count by count**: a click on a
  row selects the leader's and the follower's steps of that count (the feet step together), ctrl-click one foot alone,
  shift-click extends to the clicked count; up/down move to the previous or next count (wrapping round; the arrow keys never
  change a dropdown), shift+up/down grow the selection by a count, ctrl+shift+up/down shrink it. The floor shows each moving
  foot in grey where it was, with a dashed line to where it lands, the feet that stay in light grey with a coloured outline,
  and the feet of the last selected count with a dark outline. Left-drag moves a selected foot (1 cm); right-drag turns it
  **about the centre of its heel** (5 degrees, alt for 1; the heel stays, the toe swings). Ctrl-z undoes everything. Wheel
  zooms, dragging empty floor pans, "fit view" resets. "check" shows `check.py`'s output; "?" the help.
  **Writing elements**: "new element…" makes an element, or a new starting condition of an existing one when given its name
  (where it starts: state and free foot; where it ends; the steps: none, or a copy of any skeleton, moved rigidly to the new
  start and mirrored onto the other feet when the free foot differs). "edit…" changes the name and the states; "delete"
  deletes one starting condition (after a confirm that names the compositions that would stop composing). A turning step shows a curved arrow at the toe of the foot's previous place (clockwise or anticlockwise, with
  the degrees); a click on it makes the foot turn the other way round to the same landing. Headings keep their winding
  (since 2026-10-25 a heading of 370 is not 10: a foot turns by the raw difference, in the editor, the videos and the app;
  the composer carries a foot's full turns across element boundaries). Elements made before that are converted once, when
  the server starts or the page loads, so that every foot still turns the short way as it did (`model.unwrap_element`, the
  `winding` mark on each element); the server refuses saves from a page older than that. Right-click
  a count for a menu: insert a count after it (one beat later, each dancer's free foot in place; later counts move later
  just enough to stay a beat after it), insert a triple after it, make it half a count after the previous one, remove it; on the last
  count also "put her last step where the end position expects it" (her weighted foot where the end state puts it in the frame
  of the leader's last step, as check.py checks; her heading keeps its winding);
  right-click the column titles to insert a count before the first; "add triple" inserts a whole triple step (three
  counts N, N&, N+1: the free foot, the other foot, the free foot again, in place); "follower mirrors leader" (a checkbox, saved
  with the element as `mirror`): ticked, every change to a leader step also puts her matching foot in the reflected place,
  across the line halfway between them at the start (his left foot on her right); changes to her steps do not touch his;
  ticking and unticking change nothing by themselves. "½ after previous" turns the selected
  count into a half count after the previous one and moves every later count by the same amount; "remove steps" (or Delete) removes the
  selection; double-click a count to change it (every selected step of that count), the who cell to switch the foot. Steps
  stay sorted by count and `beats` follows the counts. The server refuses to save a duplicate (name, state, free foot), an
  unknown state or a bad count label; an element with no steps for a dancer is kept but cannot be played or shipped
  (`model.element_problem`; `tools/make_assets.py` refuses it).
- **compositions**: pick, create ("new composition"), edit (name, start state, repeat, add / reorder / remove entries with
  an "x N" count) and delete compositions; the file is saved as you go. An entry is an element, a **sub-composition** (a
  named list of elements that other compositions use by name, e.g. RTT = rock step, triple step, triple step; the checkbox
  turns a composition into one) or either "x N". Every row shows the starting and ending condition of the whole entry as
  dictated by the previous one; the add list only offers what can follow. The map strip lists the composed elements with
  their sub-composition group; the floor and the step flow show the composed timeline, read-only. "5 minutes exercise" is
  the daily routine (582 beats = 4:03 on Hot Swing); it is the only composition since 2026-10-25.
- **play** (space bar too): the music runs on continuously (the whole track, looped, with a one-bar count-in) and the
  element or composition is danced on it, in the browser, with the same weight and travel model as `render.py`. If the
  dance alone would not loop (ending with the other foot free) the server pads it with triple steps
  picked by the composer from the end condition, named "triple step filler" on screen. A loop that closes (same state and
  free foot at its end) is chained for the whole track, so the dance continues from where the dancers are with no jump,
  and the camera follows the couple when the chain does not fit the floor. An element whose end state differs from its
  start (the passes) restarts from its start position each time, and the status line says so.
- **render video**: runs `render.py` on the chosen track and plays the mp4 in the corner of the floor. The file lands in `out/`.

The page's logic is covered by a Node test with a stubbed DOM (`node dance/editor/test/test_editor.js`: selection by count,
dragging, turning about the heel, undo, saving, variants, elements and steps editing, compositions editing, the timeline port
against `render.py`'s numbers). It reads frozen copies of the data in `editor/test/`, so editing the live data never changes
what it expects.

## Known gaps

- Elements that do not exist yet: lead underarm pass from side by side, anything Charleston, anything from reversed side.
  The user writes them by hand in the editor.

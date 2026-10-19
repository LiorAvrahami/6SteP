# Handover: reading and learning the Lindy Hop notes

Written 2026-10-17 for the next agent. This is everything learned about the written sources and the user's
rules for the dance. The user's answers to the open questions are in `handover-questions-and-answers.md`.

## 1. Sources

**Primary: David Drysdale, "Lindy Hop notes", lurklurk.org/lindyhop.** 2006-2008, all rights reserved,
personal-use copies allowed with acknowledgement. Every move page lists each beat for leader (M) and
follower (W) with a step code and a sentence of description. This is the ground truth for footwork.

- Local copy of the whole site: `reference/lurklurk/lindyhop.zip` (261 files). Pages are plain HTML.
- Index: https://lurklurk.org/lindyhop/index.html . Notation key: https://lurklurk.org/lindyhop/notation.html
- Rhythm pages: `8-beat_rhythm.html`, `6-beat_rhythm.html`, `charleston_rhythm.html`
- Position and connection pages: `m_dance_positions.html`, `m_hand_holds.html`, `lead_and_follow.html`
- Move pages used so far: `m_closed_basic`, `m_swing_out`, `m_lindy_turn`, `m_lindy_circle`,
  `m_swing_out_in_place`, `m_changing_places`, `m_changing_places_with_leader_turn_anticlockwise`,
  `m_changing_places_with_leader_turn_clockwise`, `m_turn_under`, `m_sugar_push`, `m_side_charleston`,
  `m_back_charleston`, `m_hand-to-hand_charleston`, `m_charleston_basic`, `swing_out_variations`.
  Many more move pages exist in the zip (all `m_*.html`).

**Secondary** (corroboration only): Wikibooks "Swing Dancing" (Lindy Hop, Charleston, East Coast Swing /
Starting Out; CC BY-SA), Wikipedia "East Coast Swing" and "Charleston (dance)", the 2005 Wikipedia
"Swingout" text at academickids.com (rotation per two counts), Reading Museum "Learn the swingout" PDF.
Dead ends: Swungover (no per-count posts), dancetime.com and hsds.org (404), wikiHow (blocks fetching),
libraryofdance.org (video only), Arthur Murray scans on archive.org (lending only, not verified public
domain), no 1940s footprint booklet found. Full detail in `footwork-sources.md`.

### Extracting a move page from the zip

```python
import zipfile, re, html
z = zipfile.ZipFile('reference/lurklurk/lindyhop.zip')
t = z.read('m_changing_places.html').decode('utf-8', 'replace')
t = re.sub(r'<script.*?</script>|<style.*?</style>', '', t, flags=re.S)
t = re.sub(r'<br\s*/?>|</p>|</li>|</tr>|</h\d>|</div>', '\n', t); t = re.sub(r'<[^>]+>', ' ', t); t = html.unescape(t)
for line in t.split('\n'):
    if re.match(r'\s*(\d|&) \. ', line): print(line.strip())
```
Each beat line has the form `N . M:code W:code <description>` (N is a beat number or `&`). Print with
`PYTHONIOENCODING=utf-8` on Windows; the pages contain non-ASCII characters.

## 2. The notation (lurklurk, adapted from Craig Hutchinson's "Swing Dancer" manual)

A step code is `[who:][type][size][direction]FOOT[rotation]`.

| Part | Values | Meaning |
|---|---|---|
| who | `M` leader, `W` follower, `B` both, `S` solo | only in the page text, e.g. `M:BL W:BR` |
| type | (none) step, `T` together/close, `R` replace, `H` hook behind, `X` cross in front, `Tw` twist step, `K` kick, hitch, point, touch, sweep also appear in text | step/close/replace **transfer weight**; kick, hitch, point, touch, sweep **do not** |
| size | `e` small ("slightly") | no number is given anywhere; sizes are qualitative |
| direction | `B` back, `F` forward, `S` side (toward the stepping foot's own side), `Df` diagonally forward | relative to the dancer |
| FOOT | `L`, `R` | the foot that acts |
| rotation | digit then `L` or `R`, digit = multiples of 45 degrees | `R` = clockwise seen from above; `2R` = 90 degrees right |

Examples: `BL` back on the left. `RR` replace weight onto the right (foot does not move). `eSL` small
step to the side on the left. `TR` right closes to the left. `HR2R` right hooks behind the left,
turning 90 right. `XL4R` left crosses in front, turning 180 right. `TweFR` twist step slightly forward
on the right. `RL1L` replace onto the left turning 45 left.

Conventions stated on the rhythm pages: the leader starts with weight on the right foot (left free);
the follower starts with weight on the left (right free). Every weighted step changes which foot is
free, so the sequence of feet in a move is fixed by the notation, not a choice.

Counts: beats 1..6 or 1..8; `&` is the swung middle of a triple step. The notes do not quantify the
swing; the measured music (Hot Swing, Sweeter Vermouth) places the offbeat at about 0.67-0.71 of the beat,
a 2:1 triplet. See `music/README.md`.

**What the notation does not say** (do not guess these silently): how far a
"step" or a "slight step" travels, where a turning step lands relative to the standing foot, whether a
"replace" involves any turn of the planted foot, and any body position. Those must come from the user
or from watching the move, not from the text.

**Reading rotation** (user's framing, 2026-10-17, to be confirmed with them): a rotation on a step is the
difference between the landing foot's orientation and the orientation the dancer was standing in. The
planted foot cannot rotate, so after a turning step the two feet point in different directions and the
next step of the other foot either carries its own rotation or closes and matches. Lurklurk's sequences
read that way: `FL2R, TR1R, FL1R` spreads 180 degrees over three landings.

## 3. Positions and hand connection (from `m_dance_positions`, `m_hand_holds`, `lead_and_follow`)

- Closed: face to face; leader's right hand on the follower's left shoulder blade; his left hand holds her
  right hand at shoulder height, out to his left; her left hand on his right shoulder.
- Side: follower on the leader's right, both facing the same way; his right arm around her back, her left
  hand on his right shoulder. **This is how the user dances the basic.**
- Open: facing, a short distance apart, only his left hand holding her right at about waist height,
  elbows slightly bent, "springy" tension matched by both.
- Behind, Wrap, Sweetheart: described on the positions page, not used yet.
- Grip, left-to-right: his thumb on top, index and middle fingers bent to point right, other fingers
  folded; her palm down, fingers curled around his two fingers; no thumb grip. Right-to-right is the
  mirror.
- Leads: push or pull at waist height for direction; raise her right hand above her right shoulder and
  push for a clockwise turn; raise it in front of her face for anticlockwise; in closed, lifting one side
  of her body with the right arm puts her weight on the other foot. During a swingout from open: only the
  hand hold on 1-2; on 3 his right hand goes to her back and her left hand to his shoulder; he pulls with
  the right hand on 4-5 and releases immediately after 5; hand hold only from 5 to 8.

## 4. Per-beat transcriptions (verbatim codes from lurklurk)

### Closed Basic (6 beats)
```
1 M:BL    W:BR1R     3 M:eSL W:eSR    & M:TR W:TL    4 M:eSL W:eSR
2 M:RR    W:RL1L     5 M:eSR W:eSL    & M:TL W:TR    6 M:eSR W:eSL
```
Rock step then two side-close-side triples. Follower turns slightly right on 1 and back left on 2.

### Swing Out, from closed (8 beats)
```
1 M:BL W:BR      2 M:RR1R W:RL     3 M:SL2R W:eFR    & M:TR1R W:RL    4 M:SL W:FR
5 M:HR2R W:FL2R  6 M:SL2R W:BR2R   7 M:eBR W:eBL     & M:TL W:TR      8 M:RR W:RL
```
Hands: he pulls with the right hand on 4 and lets go immediately after; 5 he hooks back out of her way.

### Lindy Turn = swingout from open (8 beats)
```
1 M:BL W:TweFR   2 M:RR W:TwFL     3 M:FL2R W:FR2R   & M:TR1R W:TL1R  4 M:FL1R W:FR1R
5 M:HR2R W:FL2R  6 M:SL2R W:HR2R   7 M:eBR W:eBL     & M:TL W:TR      8 M:RR W:RL
```
By the end of 4 the couple faces the opposite way to the start. Follower alternative on 6: cross in front
instead of hooking behind. Some styles keep the follower facing away until 6 or 7.

### Lindy Circle, open to closed (8 beats)
```
1 M:BL W:TweFR   2 M:RR W:TwFL     3 M:FL2R W:FR2R   & M:TR1R W:TL1R  4 M:FL1R W:FR1R
5 M:eFR2R W:DfL2R  6 M:SL2R W:XR2R  7 M:BR W:BL2R    & M:TL W:TR1R    8 M:eBR W:eBL1R
```
Rotation totals: leader 8 x 45 = 360; follower 12 x 45 = 540. Both step backward together on 7-8 in
closed hold. "Stepping in between the follower's legs" on 5: the notes describe a close hold here.

### Changing Places, open to open (6 beats). The user says this is their **follower underarm pass**.
```
1 M:BL W:BR      2 M:RR W:RL       3 M:eFL1R W:FR1L   & M:TR W:TL      4 M:eFL1R W:FR1L
5 M:eFR1R W:BL1L & M:TL W:TR       6 M:TR1R W:BL1L
```
Leader lifts his left hand on 3, in front of her face on &, she steps under her right hand on 4; he drops
the hand as she passes on 5; they end facing each other, places swapped. Leader 4 x 45 right = 180;
follower 4 x 45 left = 180.

### Changing Places with Leader Turn Anticlockwise (6 beats): leader steps under his own arm on 5
```
1-4 as Changing Places
5 M:eFR2L W:eBL1L   & M:TL4L W:TR   6 M:TR W:BL1L
```
### Changing Places with Leader Turn Clockwise (6 beats)
```
1-4 as Changing Places
5 M:HR4R W:eBL1L    & M:TL4R W:TR   6 M:TR2R W:BL1L
```
The user's guess for their **lead underarm pass** is Changing Places with the roles mirrored (leader
takes the follower's travelling path under the arm); these two pages are the alternative reading.

### Turn Under = tuck turn, closed to open (6 beats)
```
1 M:BL W:BR1R    2 M:RR W:RL1L     3 M:eSL W:eSR    & M:TR W:TL    4 M:eSL W:eSR
5 M:RR W:XL4R    & M:TL W:TR2R     6 M:RR W:RL2R
```
Follower turns 360 right (180 + 90 + 90) under the arm; leader's feet stay almost in place.

### Side Charleston, 8 beats, side by side (follower on the leader's right)
```
1 M:BL W:BR  2 M:RR W:RL  3 kick L fwd / kick R fwd  4 M:eFL W:eFR (weight)
5 kick R fwd / kick L fwd  6 hitch R / hitch L (no weight)  7 kick R back / kick L back  8 M:eBR W:eBL (weight)
```
Weight changes only on 1, 2, 4, 8. Reading Museum variant: 5-6 become two weight changes in place.

### Back (tandem) Charleston, both on the same feet, leader behind
```
1 point L back  2 hitch L  3 kick L fwd  4 L closes (weight)  5 kick R fwd  6 hitch R  7 kick R back  8 R closes (weight)
```
### Hand-to-hand Charleston (unison): 1 kick R fwd turning 90 L, 2 hitch R turning 90 R, 3 kick R fwd, 4 R closes, 5-8 the same on the left with opposite turns; hands change on 4 and 8.
### Charleston Basic (solo 1920s): 1 point L back, 2 sweep L fwd, 3 step L fwd, 4 sweep R fwd, 5 point R fwd, 6 sweep R back, 7 step R back, 8 sweep L back; weight changes only on 3 and 7.
### Sugar Push (West Coast style, open)
```
Leader:   1 BL  2 TR  3 HL  & RR  4 TL  5 HR  & eFL  6 eFR
Follower: 1 FR  2 TL  3 HR  & RL  4 TR  5 HL  & eBR  6 eBL
```

## 5. The user's dance and rules (stated 2026-10-16/17, authoritative over the notes)

- Basic is danced **side by side, follower on the leader's right**, both facing the same way. Elements
  exist that lead to facing each other; facing is not the start state. Only two facing states are known
  so far: side by side and facing each other; Charleston may add "one behind the other".
- Element vocabulary: rock step, triple step, "rock back cutoff" (= lurklurk Closed Changing Places, see the Q&A file),
  follower underarm pass (= Changing Places), lead underarm pass ("similar but mirrored"), circle, and a
  triple step facing each other moving side to side. The rock step before an underarm pass is **part of**
  the pass, not a separate element.
- The user judged lurklurk's side-close-side triple "more accurate" than a forward-then-in-place triple.
- No confirmed skeleton of the follower underarm pass exists; the user's one-sentence description of it is
  in `handover-questions-and-answers.md` (Q2).
- Model rules the user wants: an element is a list of steps; each step moves one foot from one point to
  another and transfers weight; those points are the skeleton; how the free foot travels is unimportant for
  now. A weighted foot never moves or rotates. A dancer's two feet are never further apart than 3x, where
  x is the distance between the leader's feet at the start. Collision rule: circles of diameter x on each
  foot and on each dancer's midpoint; the leader's and follower's circles never intersect. Every element
  ends in a facing state, and the facing state strictly fixes the other feet relative to the leader's left
  foot; the lookup key for an element is {name, starting facing state}; positions are relative to the
  leader's starting orientation and the follower's orientation is deduced from it.
- Edits the user makes in a visual editor are a message: keep a baseline copy and show the diff.
- Music for timing: run `music/tools/beatgrid.py` on a track to get its beat grid JSON (tempo, beat 0, downbeats, swing); the Hot Swing loop grid is in `music/chosen/`. See the "Beat grid tool" section of `music/README.md`.

## 6. Working principle

The notes give feet, counts, weight and rotation; everything else (distances, where a turning step lands,
body position) must be placed from a confirmed description and reviewed by eye at readable size. A failed
constraint means the data is wrong, not that a correction mechanism is missing: do not add nudges or
repulsion layers to make checks pass; fix the positions or ask the user.

## 7. Files

- `footwork-sources.md`: the fuller sources write-up with consensus tables and the grading of an early
  intuition against the sources.
- `reference/lurklurk/lindyhop.zip`: the notes.
- `music/`: looped tracks, beat analysis, candidate list.
- `handover-questions-and-answers.md`: the user's answers on the cutoff, the passes, the circle, distances and drawing.

## 8. Update 2026-10-17 (evening): the questions above were answered

The user answered the open questions in an advisory session; see `handover-questions-and-answers.md`. In
short: "rock back cutoff" is lurklurk's Closed Changing Places and ends with the follower on the leader's
LEFT (a reversed side position); the follower underarm pass from side by side has her going under the
raised left arm turning clockwise 180 while he stays put; the lead underarm pass has him going under the
joined hands on her right side turning 180 left; the circle ends side by side with her on his right; open
position is 70 cm; the collision rule relaxes only where the notes explicitly describe a close hold.

"""Authoring of the skeletons: the agent's placements built from the lurklurk notes and the user's answers.

Run:  python dance/author_skeletons.py        -> writes dance/skeletons.json AND dance/skeletons.baseline.json
Do NOT run it after the user has edited skeletons.json in the editor without first folding their edits back
into this file (see dance/diff.py): it overwrites both files.

Every step carries a lurklurk-style code and a 'src' tag:
  notes+guess = feet, count, direction and rotation come from the lurklurk page named on the element; the distance is the agent's
  user+guess  = the shape comes from the user's words (handover-questions-and-answers.md or the 2026-10-18 feedback); the distance is the agent's
  guess       = the agent's own placement, no written source
Coordinates: cm, local to the element's start frame (leader's left foot at (0,0), heading 0 = +y, clockwise positive).
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import SKELETONS, BASELINE, save


def S(count, dancer, foot, code, x, y, h, src='notes+guess', note='', exempt=False):
    d = {'count': str(count), 'dancer': dancer, 'foot': foot, 'code': code,
         'x': float(x), 'y': float(y), 'heading': float(h), 'src': src}
    if note:
        d['note'] = note
    if exempt:
        d['exempt'] = True
    return d


# Hand connections. "<dancer><hand>-<target>": L=leader, F=follower; hand L/R; target = the partner's hand ("FR" = follower's
# right hand) or a point on the partner: back, lshoulder, rshoulder, lhip, rhip. A trailing ^F or ^L means the joined hands are
# raised and the follower (F) or the leader (L) passes under them.
SIDE_HANDS = ['LR-back', 'FL-rshoulder']            # m_dance_positions: Side
REVERSED_SIDE_HANDS = ['LL-back', 'FR-lshoulder']   # m_closed_changing_places count 6
OPEN_HANDS = ['LL-FR']                              # m_dance_positions: Open
CLOSED_HANDS = ['LL-FR', 'LR-back', 'FL-rshoulder']

# face to face: the facing feet 20 cm apart toe to toe (user 2026-10-19), so with 26 cm feet their centres are 46 cm apart
FOOT_LEN = 26
FACE = 20 + FOOT_LEN

STATES = {
    'side': {'desc': "Side by side, both facing the same way, follower on the leader's right (the user's basic position). 45 cm between body centres (agent guess).",
             'feet': {'L.L': (0, 0, 0), 'L.R': (20, 0, 0), 'F.L': (45, 0, 0), 'F.R': (65, 0, 0)}, 'leader_free': 'L', 'hands': SIDE_HANDS},
    'facing': {'desc': f"Facing each other, open hold, leader's left hand holding the follower's right. {FACE:.0f} cm between body centres: a 20 cm gap between the toes of the facing feet (user 2026-10-19; it was 70, user Q5).",
               'feet': {'L.L': (0, 0, 0), 'L.R': (20, 0, 0), 'F.L': (20, FACE, 180), 'F.R': (0, FACE, 180)}, 'leader_free': 'L', 'hands': OPEN_HANDS},
    'reversed_side': {'desc': "Side by side, follower on the leader's LEFT, his left arm around her back (lurklurk Closed Changing Places; user Q1). 45 cm between centres.",
                      'feet': {'L.L': (0, 0, 0), 'L.R': (20, 0, 0), 'F.L': (-45, 0, 0), 'F.R': (-25, 0, 0)}, 'leader_free': 'L', 'hands': REVERSED_SIDE_HANDS},
}

elements = []


def E(name, start, end, beats, source, notes, steps, hands=None, leader_free_start='L'):
    elements.append({'name': name, 'start_state': start, 'end_state': end, 'beats': beats, 'leader_free_start': leader_free_start,
                     'source': source, 'notes': notes, 'steps': steps, 'hands': hands or []})


# ---------------------------------------------------------------- rock step (2 beats): one element, variants by start state and free foot (user 2026-10-18: merge)
SIDE_LIKE = [('side', 45, 65)]   # reversed_side (follower on his left) is defined as a state but no element leads there since the 2026-10-18 cutoff rebuild
for st, flx, frx in SIDE_LIKE:
    E('rock step', st, st, 2, 'm_closed_basic / m_closed_changing_places counts 1-2 (M:BL W:BR, M:RR W:RL); side position per the user',
      "Back step length 30 cm is a guess. Weight ends on the leader's right / follower's left; the free feet stay back until the next element moves them.",
      [S(1, 'L', 'L', 'BL', 0, -30, 0), S(1, 'F', 'R', 'BR', frx, -30, 0),
       S(2, 'L', 'R', 'RR', 20, 0, 0), S(2, 'F', 'L', 'RL', flx, 0, 0)])
    E('rock step', st, st, 2, 'mirror of the rock step on the other feet (user 2026-10-18: "same for rock step"); no lurklurk page starts a rock step on the right',
      "Only reachable when the leader's right foot is free, i.e. after a left triple step.",
      [S(1, 'L', 'R', 'BR', 20, -30, 0, 'user+guess'), S(1, 'F', 'L', 'BL', flx, -30, 0, 'user+guess'),
       S(2, 'L', 'L', 'RL', 0, 0, 0, 'user+guess'), S(2, 'F', 'R', 'RR', frx, 0, 0, 'user+guess')], leader_free_start='R')
E('rock step', 'facing', 'facing', 2, 'm_changing_places counts 1-2 (M:BL W:BR, M:RR W:RL) from open position',
  f'Back step 30 cm (guess). Bodies separate to about {FACE + 60:.0f} cm on count 1; both arms stay within reach.',
  [S(1, 'L', 'L', 'BL', 0, -30, 0), S(1, 'F', 'R', 'BR', 0, FACE + 30, 180),
   S(2, 'L', 'R', 'RR', 20, 0, 0), S(2, 'F', 'L', 'RL', 20, FACE, 180)])
E('rock step', 'facing', 'facing', 2, 'mirror of the rock step on the other feet (user 2026-10-18)', 'Parity variant: the right foot rocks back.',
  [S(1, 'L', 'R', 'BR', 20, -30, 0, 'user+guess'), S(1, 'F', 'L', 'BL', 20, FACE + 30, 180, 'user+guess'),
   S(2, 'L', 'L', 'RL', 0, 0, 0, 'user+guess'), S(2, 'F', 'R', 'RR', 0, FACE, 180, 'user+guess')], leader_free_start='R')

# ---------------------------------------------------------------- triple step side by side (2 beats): in place, drifting 10 cm left (left foot free) or right (right foot free)
DRIFT = 10.0
for st, flx, frx in SIDE_LIKE:
    E('triple step', st, st, 2, "Feet/counts: 6-beat_rhythm.html (L-R-L / R-L-R). Shape: user 2026-10-18: the left triple step drifts slightly to the left; the first step brings the rocked-back foot home (user, footwork-sources.md section 1).",
      f"The whole couple shifts {DRIFT:.0f} cm to the leader's left: the first step lands {DRIFT:.0f} cm left of home, the close lands beside it, the third step replaces in place.",
      [S('3', 'L', 'L', 'eSL', -DRIFT, 0, 0, 'user+guess', 'forward from the rock step and a little left'), S('3', 'F', 'R', 'eSR', frx - DRIFT, 0, 0, 'user+guess'),
       S('3&', 'L', 'R', 'TR', 20 - DRIFT, 0, 0, 'user+guess'), S('3&', 'F', 'L', 'TL', flx - DRIFT, 0, 0, 'user+guess'),
       S('4', 'L', 'L', 'RL', -DRIFT, 0, 0, 'user+guess'), S('4', 'F', 'R', 'RR', frx - DRIFT, 0, 0, 'user+guess')])
    E('triple step', st, st, 2, 'as the left-foot triple step on the other feet, drifting slightly to the right (user 2026-10-18)',
      f"The couple shifts {DRIFT:.0f} cm to the leader's right; after a left and a right triple the couple is back where it started.",
      [S('5', 'L', 'R', 'eSR', 20 + DRIFT, 0, 0, 'user+guess'), S('5', 'F', 'L', 'eSL', flx + DRIFT, 0, 0, 'user+guess'),
       S('5&', 'L', 'L', 'TL', DRIFT, 0, 0, 'user+guess'), S('5&', 'F', 'R', 'TR', frx + DRIFT, 0, 0, 'user+guess'),
       S('6', 'L', 'R', 'RR', 20 + DRIFT, 0, 0, 'user+guess'), S('6', 'F', 'L', 'RL', flx + DRIFT, 0, 0, 'user+guess')], leader_free_start='R')

# ---------------------------------------------------------------- triple step facing (2 beats): the notes' side-close-side
E('triple step', 'facing', 'facing', 2, 'm_closed_basic counts 3&4: M:eSL W:eSR, M:TR W:TL, M:eSL W:eSR (side-close-side), judged "more accurate" by the user for the facing case',
  "Small side step = 10 cm, the close lands with the feet 10 cm apart (together). The couple shifts 20 cm to the leader's left; the right triple brings it back.",
  [S('3', 'L', 'L', 'eSL', -10, 0, 0), S('3', 'F', 'R', 'eSR', -10, FACE, 180),
   S('3&', 'L', 'R', 'TR', 0, 0, 0), S('3&', 'F', 'L', 'TL', 0, FACE, 180),
   S('4', 'L', 'L', 'eSL', -20, 0, 0), S('4', 'F', 'R', 'eSR', -20, FACE, 180)])
E('triple step', 'facing', 'facing', 2, 'm_closed_basic counts 5&6: M:eSR W:eSL, M:TL W:TR, M:eSR W:eSL',
  "Mirror of the left triple: shifts the couple 20 cm to the leader's right.",
  [S('5', 'L', 'R', 'eSR', 30, 0, 0), S('5', 'F', 'L', 'eSL', 30, FACE, 180),
   S('5&', 'L', 'L', 'TL', 20, 0, 0), S('5&', 'F', 'R', 'TR', 20, FACE, 180),
   S('6', 'L', 'R', 'eSR', 40, 0, 0), S('6', 'F', 'L', 'eSL', 40, FACE, 180)], leader_free_start='R')

# ---------------------------------------------------------------- rock back cutoff (6 beats), side -> side, the couple turned 180 and moved forward
# Every placement below is the user's, made in the editor on 2026-10-18 (both dancers). The agent's earlier version (he lunges
# to (58,20), she swings round behind him to (-25,45)) was replaced by this tighter one. Codes are the agent's description of
# each step; the user's headings are not on the 45-degree grid, so the notation checks only warn for these steps.
E('rock back cutoff', 'side', 'side', 6,
  "User's placements in the editor (2026-10-18): rock step with both feet back; the left foot cuts across her path turning about 80 right; the right matches and "
  "the triple is nearly in place; the right foot goes back onto the line facing back; the left matches; a small back step. She stays through count 3, slides left on 3&, "
  "closes on 4, comes forward-left round behind him on 5 turning right, back on 5& and 6, ending on his right, both facing back.",
  "As placed, the couple ends 35 cm apart (the side state says 45) and her left foot is 15 cm from his right (the rule says 20): open questions for the user. "
  "Her 5& step is 83 cm (she is dragged to her final spot in one step).",
  [S(1, 'L', 'L', 'BL', 0, -30, 0, 'user'), S(1, 'F', 'R', 'BR', 65, -30, 0, 'user'),
   S(2, 'L', 'R', 'BR', 21, -31, 0, 'user', 'right foot back as well'), S(2, 'F', 'L', 'BL', 44, -29, 0, 'user', 'left foot back as well'),
   S(3, 'L', 'L', 'DfL2R', 52, 19, 80, 'user', 'the cutoff: across her path'), S(3, 'F', 'R', 'RR', 65, -30, 5, 'user', 'cut off: stays, turned a little'),
   S('3&', 'L', 'R', 'FR2R', 38, 1, 155, 'user', 'right matches'), S('3&', 'F', 'L', 'SL', 31, -23, 20, 'user', 'slides left'),
   S(4, 'L', 'L', 'SL', 61, 8, 150, 'user', 'a small step'), S(4, 'F', 'R', 'TR', 53, -29, 20, 'user', 'right closes'),
   S(5, 'L', 'R', 'BR1R', 32, 23, 180, 'user', 'right foot back onto the line, facing back'), S(5, 'F', 'L', 'DfL2R', 14, 12, 125, 'user', 'forward-left round behind him, turning right'),
   S('5&', 'L', 'L', 'DbL', 50, 31, 180, 'user', 'left matches'), S('5&', 'F', 'R', 'BR1R', -6, 29, 180, 'user', 'back, 83 cm, to her final spot'),
   S(6, 'L', 'R', 'eBR', 31, 35, 180, 'user', 'a small back step; she is on his right'), S(6, 'F', 'L', 'BL', 16, 37, 180, 'user', 'back, beside him')],
  hands=[])

# ---------------------------------------------------------------- follower underarm pass (6 beats), side -> facing   (user 2026-10-18: correct; own-feet overlap at the end fixed)
E('follower underarm pass', 'side', 'facing', 6, 'Counts and feet: m_changing_places (rock step, triple, triple). Shape: user Q2: from side by side she goes under the raised left arm and turns CLOCKWISE 180 in the two triples, ending in front of the leader facing him; the leader stays put. The cross step on 4 is written the way m_turn_under writes her turn (XL4R there). Confirmed by the user 2026-10-18.',
  "She walks forward and across his front (3, 3&), crosses her right foot in front turning 90 R under the hands (4), then backs round the remaining 90 (5, 5&) and settles facing him (6). Headings 0, 0, 90, 135, 180, 180. "
  "He takes her right hand in his left on count 1 (which hands are held in side position is an open question), lifts it on 3, lowers it on 5, and triples in place. Her last three landings are at least 20 cm apart (fix of 2026-10-18).",
  [S(1, 'L', 'L', 'BL', 0, -30, 0), S(1, 'F', 'R', 'BR', 65, -30, 0),
   S(2, 'L', 'R', 'RR', 20, 0, 0), S(2, 'F', 'L', 'RL', 45, 0, 0),
   S(3, 'L', 'L', 'eFL', 0, 0, 0, 'guess', 'leader stays put (user)'), S(3, 'F', 'R', 'FR', 54, 18, 0, 'user+guess', 'forward; his hand lifts'),
   S('3&', 'L', 'R', 'TR', 20, 0, 0, 'guess'), S('3&', 'F', 'L', 'TL', 38, 30, 0, 'user+guess', 'a travelling close, across his front'),
   S(4, 'L', 'L', 'RL', 0, 0, 0, 'guess'), S(4, 'F', 'R', 'XR2R', 22, 42, 90, 'user+guess', 'right crosses in front, turning 90 R under the hands'),
   S(5, 'L', 'R', 'RR', 20, 0, 0, 'guess'), S(5, 'F', 'L', 'BL1R', 16, 64, 135, 'user+guess', 'backing round, hand lowered'),
   S('5&', 'L', 'L', 'TL', 0, 0, 0, 'guess'), S('5&', 'F', 'R', 'TR1R', 0, FACE, 180, 'user+guess'),
   S(6, 'L', 'R', 'RR', 20, 0, 0, 'guess'), S(6, 'F', 'L', 'eFL', 20, FACE, 180, 'user+guess', 'settles facing him, open hold')],
  hands=[{'count': '1', 'hands': ['LL-FR', 'LR-back']}, {'count': '3', 'hands': ['LL-FR^F']}, {'count': '5', 'hands': OPEN_HANDS}])

# ---------------------------------------------------------------- follower underarm pass from facing (6 beats), facing -> facing, the couple's line turned 90 right and moved
E('follower underarm pass', 'facing', 'facing', 6,
  "User 2026-10-18: for the leader 'dancing from behind her': rock step, one step forward turned 90 right and a triple there, one more step forward turned 90 right and a triple there. "
  "The follower turns ANTICLOCKWISE here (user 2026-10-18, correcting the first build), which is lurklurk's Changing Places follower: W: FR1L, TL, FR1L, BL1L, TR, BL1L. They end face to face again.",
  "Leader: 3 steps 30 cm forward turning to face east and triples in place behind her; 5 steps 30 cm east turning to face south and triples in place. "
  "Follower: forward-left under the hands turning left (3, 3&, 4: she faces east with him behind her), then backs round to his new front turning left (5, 5&, 6), 70 cm in front of him. "
  "Hands: open hold, raised with her under them on 3&-4, open again from 5. Her steps are 40-52 cm (his first is 65 from the rocked-back foot). Every distance is a guess.",
  [S(1, 'L', 'L', 'BL', 0, -30, 0), S(1, 'F', 'R', 'BR', 0, FACE + 30, 180),
   S(2, 'L', 'R', 'RR', 20, 0, 0), S(2, 'F', 'L', 'RL', 20, FACE, 180),
   S(3, 'L', 'L', 'FL2R', 10, 24, 90, 'user+guess', 'one step forward, turned 90 right, behind her'), S(3, 'F', 'R', 'FR1L', 38, 34, 135, 'notes+guess', 'forward, turning left; the hands lift'),
   S('3&', 'L', 'R', 'TR', 10, 4, 90, 'user+guess'), S('3&', 'F', 'L', 'TL', 46, 54, 135, 'notes+guess', 'left closes, under the hands'),
   S(4, 'L', 'L', 'RL', 10, 24, 90, 'user+guess', 'triple in place'), S(4, 'F', 'R', 'FR1L', 62, 34, 90, 'notes+guess', 'forward, now facing east with him behind her'),
   S(5, 'L', 'R', 'FR2R', 40, 18, 180, 'user+guess', 'one more step forward, turned 90 right'), S(5, 'F', 'L', 'BL1L', 62, -6, 45, 'notes+guess', 'backing round to his new front; hands lowered'),
   S('5&', 'L', 'L', 'TL', 60, 18, 180, 'user+guess'), S('5&', 'F', 'R', 'TR', 76, -20, 45, 'notes+guess'),
   S(6, 'L', 'R', 'RR', 40, 18, 180, 'user+guess', 'triple in place, facing her'), S(6, 'F', 'L', 'BL1L', 40, 18 - FACE, 0, 'notes+guess', 'in front of him, facing him')],
  hands=[{'count': '3&', 'hands': ['LL-FR^F']}, {'count': '5', 'hands': OPEN_HANDS}])

# ---------------------------------------------------------------- recapture underarm pass (6 beats), facing -> side   (user 2026-10-19: she turns LEFT)
E('recapture underarm pass', 'facing', 'side', 6,
  "User 2026-10-18: 'for the follower it is basically a follower underarm pass and for the leader it is just rock step, triple step'; it brings the couple back from face to face to side by side. "
  "Name is the user's working name. User 2026-10-19: she turns LEFT (anticlockwise). Her codes are therefore lurklurk's Changing Places follower, as in the follower underarm pass from facing: "
  "W: FR1L, TL, FR1L, BL1L, TR, BL1L (4 x 45 left = 180). The first build (2026-10-18) had her turning clockwise with the codes of the pass from side by side.",
  "She steps forward and to her left under the raised hands turning left (3, 3&), steps forward again turning left so that she faces away from him, east (4), "
  "then backs round to his right side turning left (5, 5&, 6) and ends there facing his way. He rocks and triples in place. "
  "Hands: open hold on 1-3, raised with her under them on 3&, open hold again on 4 once she is through, side hold from 5 (his right arm around her back). All distances are guesses.",
  [S(1, 'L', 'L', 'BL', 0, -30, 0), S(1, 'F', 'R', 'BR', 0, FACE + 30, 180),
   S(2, 'L', 'R', 'RR', 20, 0, 0), S(2, 'F', 'L', 'RL', 20, FACE, 180),
   S(3, 'L', 'L', 'eFL', 0, 0, 0, 'guess', 'leader stays put'), S(3, 'F', 'R', 'FR1L', 30, 24, 135, 'notes+guess', 'forward, turning left; his hand lifts'),
   S('3&', 'L', 'R', 'TR', 20, 0, 0, 'guess'), S('3&', 'F', 'L', 'TL', 44, 38, 135, 'notes+guess', 'left closes, under the hands'),
   S(4, 'L', 'L', 'RL', 0, 0, 0, 'guess'), S(4, 'F', 'R', 'FR1L', 58, 24, 90, 'notes+guess', 'forward, turning left: she is through and faces east, away from him; the hands come down'),
   S(5, 'L', 'R', 'RR', 20, 0, 0, 'guess'), S(5, 'F', 'L', 'BL1L', 42, 10, 45, 'notes+guess', 'backing round to his right side; his right arm takes her back'),
   S('5&', 'L', 'L', 'TL', 0, 0, 0, 'guess'), S('5&', 'F', 'R', 'TR', 64, 6, 45, 'notes+guess'),
   S(6, 'L', 'R', 'RR', 20, 0, 0, 'guess'), S(6, 'F', 'L', 'BL1L', 45, 0, 0, 'notes+guess', 'on his right, side position')],
  hands=[{'count': '3&', 'hands': ['LL-FR^F']}, {'count': '4', 'hands': OPEN_HANDS}, {'count': '5', 'hands': SIDE_HANDS}])

# ---------------------------------------------------------------- lead underarm pass (6 beats), facing -> facing, places swapped   (user 2026-10-18: perfect)
E('lead underarm pass', 'facing', 'facing', 6, "m_changing_places with the roles swapped (user Q3): the leader takes the follower's part, travelling under the joined hands on the follower's RIGHT side and turning 180 LEFT; she takes the leader's part, small steps turning 180 RIGHT, facing him as he passes. Places swapped. Confirmed by the user 2026-10-18.",
  "Leader (the notes' follower codes on the other feet): forward 1L, close, forward 1L, back 1L, close, back 1L; ends weight R, 70 cm beyond his start. Follower (the notes' leader codes): slightly forward 1R, close, slightly forward 1R, slightly forward 1R, close, close 1R; her 'small' steps are 8-37 cm because she has to cover 70 cm to keep the 70 cm open distance. Distances are guesses; they pass with 32 cm between body centres.",
  [S(1, 'L', 'L', 'BL', 0, -30, 0), S(1, 'F', 'R', 'BR', 0, FACE + 30, 180),
   S(2, 'L', 'R', 'RR', 20, 0, 0), S(2, 'F', 'L', 'RL', 20, FACE, 180),
   S(3, 'L', 'L', 'FL1L', -16, 12, 315, 'user+guess', 'lifts the joined hands'), S(3, 'F', 'R', 'eFR1R', 30, 28, 225, 'user+guess'),
   S('3&', 'L', 'R', 'TR', -4, 26, 315, 'user+guess'), S('3&', 'F', 'L', 'TL', 46, 14, 225, 'user+guess', 'a travelling close'),
   S(4, 'L', 'L', 'FL1L', -20, 32, 270, 'user+guess', 'under the hands, on her right side, facing away'), S(4, 'F', 'R', 'eFR1R', 30, 34, 270, 'user+guess', 'facing him as he passes'),
   S(5, 'L', 'R', 'BR1L', -16, 52, 225, 'user+guess', 'hands lowered'), S(5, 'F', 'L', 'eFL1R', 24, 12, 315, 'user+guess'),
   S('5&', 'L', 'L', 'TL', -6, 26, 225, 'user+guess'), S('5&', 'F', 'R', 'TR', 38, 26, 315, 'user+guess'),
   S(6, 'L', 'R', 'eBR1L', 0, FACE, 180, 'user+guess', 'facing her'), S(6, 'F', 'L', 'TL1R', 0, 0, 0, 'user+guess', 'in his old place, facing him')],
  hands=[{'count': '3', 'hands': ['LL-FR^L']}, {'count': '5', 'hands': OPEN_HANDS}])

# ---------------------------------------------------------------- circle = lurklurk Lindy Circle (8 beats), facing -> side
E('circle', 'facing', 'side', 8, "m_lindy_circle (all codes and rotations). End: side by side, follower on the leader's right (user Q4); the notes' 7-8 \"closed hold\" read as the hinge-out of m_dance_positions (closed -> side), which is her 180 R on 7&8.",
  "Clockwise circle about the point between the dancers; the radius shrinks from 35 to 24 cm on count 3 and 20 cm from 3& (close hold). "
  "Counts 5 and 6 are the notes' close hold ('between the legs'): marked exempt from the collision rule (user Q6); with these distances only count 6 needs it (bodies 19 cm). "
  "The 7&8 hinge-out to side position makes her steps long (27-50 cm) and leaves her free right foot 31 cm ahead of its side-position spot. Every distance is a guess.",
  [S(1, 'L', 'L', 'BL', 0, -30, 0), S(1, 'F', 'R', 'TweFR', 0, FACE - 6, 180, note='twist step slightly forward'),
   S(2, 'L', 'R', 'RR', 20, 0, 0), S(2, 'F', 'L', 'TwFL', 20, FACE - 10, 180, note='twist step forward'),
   S(3, 'L', 'L', 'FL2R', -11, 33, 90, note='forward and off to his left; right hand to her back'), S(3, 'F', 'R', 'FR2R', 31, 57, 270, note='left hand to his right shoulder'),
   S('3&', 'L', 'R', 'TR1R', -20, 28, 135), S('3&', 'F', 'L', 'TL1R', 8, 20, 315, note='a travelling close: 30 cm'),
   S(4, 'L', 'L', 'FL1R', 20, 55, 180, note='facing the opposite way to the start'), S(4, 'F', 'R', 'FR1R', 20, 15, 0),
   S(5, 'L', 'R', 'eFR2R', 30, 45, 270, note='between her feet (close hold in the notes); 53 cm of travel for the trailing foot', exempt=True), S(5, 'F', 'L', 'DfL2R', -10, 45, 90, exempt=True),
   S(6, 'L', 'L', 'SL2R', 0, 15, 0, exempt=True), S(6, 'F', 'R', 'XR2R', 10, 38, 180, note='crosses in front, between his feet (close hold in the notes)', exempt=True),
   S(7, 'L', 'R', 'BR', 20, 20, 0), S(7, 'F', 'L', 'BL2R', 40, 38, 270, note='hinging out to his right: 50 cm'),
   S('7&', 'L', 'L', 'TL', 0, 20, 0), S('7&', 'F', 'R', 'TR1R', 59, 45, 315, note='swings around: 50 cm'),
   S(8, 'L', 'R', 'eBR', 20, 15, 0), S(8, 'F', 'L', 'eBL1R', 45, 15, 0, note='side position, on his right')],
  hands=[{'count': '3', 'hands': CLOSED_HANDS}, {'count': '7', 'hands': SIDE_HANDS}])

data = {
    '_about': 'Swing5 footwork skeletons. cm; local frame per element: leader left foot at (0,0), heading 0 = +y, clockwise positive. '
              'Generated by dance/author_skeletons.py; the editor writes this file; skeletons.baseline.json is the pre-edit copy.',
    'units': 'cm', 'stance': 20, 'max_spread': 70, 'foot_diam': 20, 'body_diam': 20,
    'states': STATES, 'elements': elements,
}

if __name__ == '__main__':
    import json as _json
    if '--force' not in sys.argv and os.path.exists(SKELETONS) and os.path.exists(BASELINE):
        with open(SKELETONS, encoding='utf-8') as f1, open(BASELINE, encoding='utf-8') as f2:
            if _json.load(f1) != _json.load(f2):
                print('REFUSING: dance/skeletons.json differs from the baseline, so it holds edits made in the editor that this script would')
                print('overwrite. Run `python dance/diff.py`, fold the edits into this script, then rerun (or rerun with --force to discard them).')
                sys.exit(2)
    save(data, SKELETONS)
    save(data, BASELINE)
    print(f'wrote {len(elements)} skeletons to {SKELETONS} and {BASELINE}')

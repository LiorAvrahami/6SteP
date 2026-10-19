# Handover: what the user wants (requirements only)

Written 2026-10-17. This states the goal and the user's requirements as given. It says nothing about how
anything should be implemented.
Related: `handover-reading-the-notes.md` (the sources and the notation), `footwork-sources.md`,
`swing-app-handover.md` (the app idea and the music), `music/README.md`.

## The long-term goal

An app that runs a guided five-minute solo practice session for swing dance, mostly Lindy Hop, with swing music that ships inside the app. Music is settled: three tracks used interchangeably, all in `music/chosen/`: "Sunday Reflection" (127 BPM) and
"Slow Your Roll" (123 BPM) by Laughing Manny & The Swinging Dicks (CC BY-NC-SA, a deal with the band is needed
before commercial release), and the looped 5-minute "Hot Swing" by Kevin MacLeod (144 BPM, CC BY). See `music/README.md`.

## The current task: a script that turns a series of elements into a video

**Input:** a sequence of element names, for example rock step, triple step, triple step, follower
underarm pass, circle. **Output:** a video with the music, showing the footwork from above.

What the video shows:

- Top view of the dance floor. Only feet and hand connections are visible: four foot silhouettes, the leader's two in blue and
  the follower's two in pink, left and right distinguishable. each hand is two lines connected at the elbow, leaders are blue followers pink. these lines have maximum length but no minimum length. if the hand is not used then it need not be rendered. the hands are meant to connect the leader and follower, so the connection are what matters and then the elbow location can be figured out in rendering.
- Opacity of a foot is the weight on it. A foot fades in as it takes weight and fades out as it gives it
  up. After one foot moves, the next foot moves; it may land in the same place, but at the least it
  fades out and in because the weight changes on every step.
- The music plays and the feet are drawn in time with it. Counts follow the music's beat; a triple step is
  swung (the "&" lands late, roughly two thirds through the beat).
- The name of the element currently being danced is shown.
- Starting position should be given. it could be side by side both facing the same way, follower on the leader's right. or it could be the dancers facing each other holding one hand at a disntace (leader's left hand holding followers right)

How the dance is modelled, in the user's words:

- An element is made of steps. Each step moves one foot from one point to another and transfers the weight. These points are the skeleton of the element. How the foot without weight travels between points is important only for rendering and is not a part of the skelton; the skeleton comes first and the actual paths are built from it later. this is the 2D skeliton on where in 2d every step lands and when. note that this is information that is compiled from the lurklurk.org lindy hop notes, where he supplies abstract description of what the steps and transitions look like in compact abstract terms (not physical locations in 2D) we can think of this as the DNA of the element, the 2D locations of the steps as the skeleton and the transitions as the soft tissue around them, note that the transitions also depend to some extent of the descriptions in the DNA, but importantly they must respect the hard constraints outlined by the skeleton.
- in order for us to be able to connect different elements, let us decide that Every element ends in some facing state, for example the dancers facing each other is a facing state or the dancers facing the same way side to side is a facing state. and the facing state strictly sets the position and orientation of the other three feet relative to the leader's left foot. Known facing states: side by side, and facing each other; Charleston may add one behind the other. Some facing states do not allow some elements, and some elements play out differently depending on the starting facing state, so the lookup key for an element's skeleton is {element name, starting facing state}. Skeletons are applied relative to the leader's starting orientation; the follower's orientation follows from the leader's and the facing state.
- Elements to cover first: rock step, triple step, rock back cutoff, follower underarm pass, circle,
  lead underarm pass, and a triple step done facing each other moving side to side. The rock step that
  precedes an underarm pass is part of the pass.
- Footwork should come from written sources (the lurklurk notes). The follower underarm pass is lurklurk's Changing Places; the lead underarm pass is roughly the same with the roles mirrored. "Rock back cutoff" might not be in the notes under this name, basically starting from side to side for the leader it is left foot back then right foot back then in tripple step beats the leader moves the left foot front and right to cut off the follower's path forward, then moves the right foot to match the left, complete remainder to tripple step in place, then move right leg back and to the left moving back to the origional line that you started on, translated in the origional farwards direction but actually rotated by 180 degrees. the follower does similar steps but mirrored. its bets if you find something similar to this in the notes, it would be surprising to me if it's not there.

Hard Rules:

- A foot that carries weight does not move and does not rotate.
- denote the distance between feet in natural stance (shoulder width apart) as 20cm.
- a dancer's two feet are never more then 70 cm apart.
- dancers should not collide. there are several ways to model this that we need to think of, basically I think every foot can be modeled as having a collision circle with diameter 20cm and these circle don't collide. also between the feet there is an imaginary circle with diameter 20cm, this is the body, the leader and follower's body should also not touch.

every element should be checked to pass these rules.

## The visual editor: a way of talking to the agent

The user wants to correct footwork by hand rather than describe coordinates in words, and wants those
corrections to reach the agent as a readable message. The requirements for the editor were: a local web
app where the user picks an element and sees its skeleton on a dancefloor view; each foot placement can be
moved by dragging with the left mouse button and rotated by dragging with the right; several steps are
shown at once; a step flow on the right lists every step, and a range of steps can be selected and is what
the floor shows (click, shift-click, up and down arrows move the selection by one step, shift with the
arrows grows it at that edge, and the selection wraps around so the last and first steps can be shown
together); a "save edits" button writes the skeleton back to disk; and a copy of the skeleton as it was
before the user's edits is kept so the agent can isolate exactly what changed and treat it as the user's
reply. One way that meets these requirements: a tiny Python HTTP server on 127.0.0.1 (port 8771, since 8765
is taken on this PC) serving one HTML page with a canvas, reading and writing a JSON file of skeletons, plus
a baseline copy and a command that prints the per-step differences. Any other way that meets the same
requirements is fine.

## Working agreements

- Explain uncertainty plainly: which parts come from a source, which are the agent's guesses.
- When the user edits in the editor, read the diff first and respond to it before changing anything.

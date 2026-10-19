# Music

**Current state (2026-10-17):** the app uses the three tracks in `music/chosen/`, interchangeably: Laughing Manny "Sunday Reflection" (127), Laughing Manny "Slow Your Roll" (123), and Kevin MacLeod "Hot Swing" (144, the 5:15 loop built below). Everything below this note is the history of how they were chosen. All folders it mentions (`raw/`, `out/`, `candidates/`, `sweep_*/`, the Hot Swing loop plans and the sweep scripts) were moved out of `music/` during cleanup and are not needed. Still live in `music/tools/`: `swingratio.py` (swing test), `jump_tracked.py` (beat-tracked structure map), `build.py` and `verify.py` (splicing and checking loops).

## Beat grid tool: where every count of a track is (`tools/beatgrid.py`)

Footwork has to be placed on the music's counts, so each track needs a beat grid: the time of beat 0, the
beat length, which beats are downbeats, and how swung the offbeat is. `tools/beatgrid.py` measures that
and writes it as JSON next to the track.

```
python -P tools/beatgrid.py "chosen/<track>.mp3" 144 --first-downbeat 0.418 --out "chosen/<track> - beatgrid.json"
```
Arguments: the file, a rough BPM (optional), `--first-downbeat SECONDS` (strongly recommended, see below), `--out`.

How it measures:
1. Rough beats from librosa's beat tracker.
2. **Comb search** for the exact tempo: every tempo within 2 BPM of the rough one, in 0.01 then 0.001 BPM steps, and every phase in 2 ms steps; the grid `t0 + k * beat` that collects the most onset energy wins. This matters on swing: melody notes pushed just before the beat drag a snap-and-fit tempo off by about 0.1 BPM, which is half a beat of drift over five minutes. The comb search is immune to that and reports 144.03 BPM for the Hot Swing loop with 7 ms scatter.
3. **Downbeats**: the four bar phases are scored by onset energy, but on syncopated music that guess is unreliable, so pass `--first-downbeat` with the time of a downbeat you can hear (the tool prints the time of the first sound, which is usually it). The JSON records whether the downbeat was given or guessed.
4. **Swing**: where the offbeat onsets sit inside the beat, 0.50 straight, 0.67 triplet. Hot Swing is 0.68.

How to read the JSON: count k (0-based beat index) happens at `t = t0 + k * beat`; the swung "&" after beat k happens at `t0 + (k + swing) * beat`; `beats[]` and `bars[]` list every beat and bar-start time; `first_onset` is when the music actually begins; beats before it are silence. Bars are 4 beats, an 8-count phrase is 2 bars; a 6-count element simply spans a bar and a half.

Current grids in `chosen/`:

| File | BPM | beat 0 | first sound = downbeat | swing | scatter |
|---|---|---|---|---|---|
| Hot Swing, 5min loop | 144.03 | 0.014 s | 0.418 s (beat 1) | 0.68 | 7 ms |
| Hot Swing, original 50 s | 143.95 (shorter file, less precise) | 0.046 s | 1.701 s (beat 4) | 0.67 | 10 ms |

The loop file's intro is shorter than the original's; dance on the loop from its bar 2 (3.76 s) at the earliest, or from whichever bar the count-in should end on. Older scripts in `tools/` (`swingratio.py`, `jump_tracked.py`) are the swing test and the structure map used when the loops were built.

---

# History: looped 5-minute practice tracks (superseded)

Work done 2026-10-16. Both tracks are Kevin MacLeod, incompetech.com, CC BY 4.0 (edits allowed, credit required).

## The two source tracks

| | Sweeter Vermouth (user's #1) | Hot Swing (user's #2) |
|---|---|---|
| ISRC | USUAN1100712 | USUAN1100202 |
| Original length | 24.7 s | 50.1 s |
| Measured tempo | 155.985 BPM (dead steady, 4 ms jitter) | 144.03 BPM (dead steady) |
| Swing ratio | triplet swing | triplet swing (offbeat at 283 ms + 133 ms, a 2:1 ratio) |
| Instruments (per incompetech) | tenor sax, alto sax, trumpets, vibraphone, trombones, drums | bass, saxes, piano, drums, clarinet, trombone, trumpet |
| Form | 1-beat pickup, then 16 bars: phrase 1 (bars 0-7, a 4-bar riff played twice), phrase 2 (bars 8-13), closing hit in bar 14, ring-out bar 15 | 2-bar intro, A (8 bars, ends with a 2-beat break and pickup), B (8 bars, louder "change-up"), A' (8 bars, opens like A, last 3 bars turn toward the ending), 2-bar ending + ring |
| Grid | first beat (pickup) at 0.042 s, bar 0 starts on beat 1 | first beat at 1.706 s, bars start on beat 0 |

Note on Hot Swing: a naive beat tracker reports a ~100 ms "jump" at 29.5 s. It is not a tempo change. In A' the melody anticipates every beat on the swung offbeat, and the onset detector locks on to those pushes. One 144.03 BPM grid fits the whole piece.

## How the loops were chosen

Every bar was described by chroma + MFCC + loudness. For a candidate jump "after beat i, continue at beat j", the score is how different beats j..j+3 are from the beats i+1..i+4 that originally followed (Infinite-Jukebox style). Lower is better; a natural beat-to-beat step scores about 0.18-0.20.

| Seam | Score |
|---|---|
| Hot Swing: end of B back to start of A | **0.089** (better than an average natural step) |
| Hot Swing: end of A' back to start of A | 0.27 (rejected: A' ends differently) |
| Sweeter Vermouth: end of bar 11 to start of bar 4 | 0.38 |
| Sweeter Vermouth: end of bar 7 back to the pickup | 0.40 |
| Sweeter Vermouth: end of bar 13 to bar 6 | 0.50 (rejected) |

Splices are 20 ms equal-power crossfades that END exactly on the downbeat, so the downbeat transient is never smeared. No time-stretching was needed because both renders are MIDI-steady.

## Output files (music/out)

| File | Structure | Length |
|---|---|---|
| Hot Swing - 5min loop | intro, (A B) x 11, A', original ending | 5:15 |
| Hot Swing - seamless loop body (A+B, 16 bars) | A B, with the wrap crossfade baked into the file end. Loop it natively in the app. | 26.66 s |
| Sweeter Vermouth - 5min loop (16-bar cycle) | [pickup + bars 0-11 + bars 4-7] x 12, then the full original so the real ending closes it | 5:20 |
| Sweeter Vermouth - 5min loop (8-bar cycle) | [pickup + bars 0-7] x 24, then the full original. Less variety, one seam type. Audition both. | 5:20 |
| Sweeter Vermouth - seamless loop body (16 bars) | the 16-bar cycle, wrap crossfade baked in | 24.62 s |

Each exists as WAV (48 kHz, 16-bit) and MP3 320k. Verification on the rendered 5-minute files: tempo fit 155.985 / 144.05 BPM across the whole file, no drift, and no seam has a sample jump beyond the track's own loudest transients.

Known caveat: Sweeter Vermouth is only 25 s of material, so 5 minutes of it means hearing the riff about 60 times. Fine for a POC, tiring for daily use. Hot Swing has 3 distinct 8-bar sections and repeats much better.

## Reproducing or changing the loops

```
cd music
python -P tools/build.py "raw/Hot Swing.mp3" plans/hs_5min.json out/x.wav
```
Plans are JSON: `bpm`, `t0` (time of beat 0), `xf_ms`, and `segments` as `[start_beat, end_beat_exclusive]` pairs (`null` end = to end of file). Change the repeat count to change the length. Other scripts: `jump.py` (per-beat map + best backward jumps), `seamscore.py` (score specific seams), `verify.py` (tempo fit + click check on a render). Run them with `python -P`.

## Credits to show in the app

> "Sweeter Vermouth" and "Hot Swing" by Kevin MacLeod (incompetech.com). Licensed under Creative Commons: By Attribution 4.0 License, http://creativecommons.org/licenses/by/4.0/ . Edited (looped and extended) for this app.

## Incompetech catalogue scan

`tools/incompetech_pieces.json` is the site's full catalogue (1,443 tracks, with BPM, length and instruments). Filtering for sax + jazz/swing + 100-200 BPM leaves only these two plus **Malt Shop Bop** (148 BPM, 2:46, saxes + piano, "bouncy, bright", filed under oldies/rock rather than jazz; downloaded to raw/ for audition). Nothing else on the site fits.

## Web search for longer sax swing tracks (2026-10-16)

Swept: Free Music Archive (all ~525 Big Band/Swing tracks plus keyword searches), Audionautix, OpenGameArt, ccMixter, Internet Archive, Wikimedia Commons, soundimage.org, Purple Planet, Bensound, Fesliyan, Mixkit, Scott Holmes, Twin Musicom (gone), TeknoAXE, Komiku, Jamendo (all swing hits are NC-ND), Bandcamp (every swing band is all-rights-reserved), US military bands.

**Result: no free-license track matches "Sweeter Vermouth but 4 minutes long".** Saxophone could not be confirmed by ear on any of these; sax claims come from tags only. The plausible ones are copied to `music/candidates/` for listening, licence in the filename. Tempos below are my own tempogram measurement over the first 90 s.

| Candidate | Licence | Length | Tempo | Notes |
|---|---|---|---|---|
| Jason Shaw (Audionautix), Jumpin' Boogie Woogie | CC BY 4.0, verified | 3:02 | 172 | Human-made 2011, "Jason Shaw Trio". Probably piano/bass/drums, sax doubtful. Fast. |
| Jason Shaw, Big Swing Band | CC BY 4.0 | 1:50 | 117 or 235 | Author says ~223 BPM with horns. Too fast for Lindy, Balboa tempo. Best style match on that site. |
| Jason Shaw, Boogie Woogie Bed | CC BY 4.0 | 2:27 | 172 | Background bed, likely no horns. |
| Jason Shaw, Panama Hat (no vocal) | CC BY 4.0 | 1:49 | 144 | Filed under jazz, "bouncy, humorous". Might be Latin novelty. Listen. |
| Kevin MacLeod, Malt Shop Bop | CC BY | 2:46 | 144-152 | Saxes + piano, 50s rock-and-roll feel rather than swing. |
| Beat Mekanik, Swing Party | CC BY 4.0, verified | 2:30 | 117 | 2023 DAW "modern swing", may lean electro. |
| Eric Matyas, Big Band Man / Shufflin' | credit-in-app licence / CC BY 3.0 | 1:38 / 1:10 | 152 | Sample-based. Shufflin' is loopable. |
| Doge, Catchy Swing | CC0 | 1:00 | 117 | Tagged saxophone, bass, drums. Loopable game track. |
| Matthew Pablo, A Conversation with Saul | CC BY 3.0 | 1:36 | 90 shuffle | The only confirmed real sax recording. Too slow for Lindy, fine as a slow-swing warm-up. |
| Stefan Kartenberg, My Misspent Youth | CC BY 3.0 | 3:55 | 152 | Swing, but male vocals. |
| tcarisland, Midnight on Miller Street | CC BY 4.0 | 3:25 | 96 | Big band arrangement, minor key, slow. |
| US Army Blues, Walk That Dog (FMA) | Public Domain Mark | 3:45 | ? | Live 18-piece big band with sax section, composer uncredited. Not downloaded. |

Rejected on licence (NC, NC-SA or NC-ND): coolkid "Batter Up!", Laughing Manny, Serge Quadrado, The Swing Ninjas, Maxim Novak "Dixieland", Ketsa, Marrach, Aunt Jamamas, every Jamendo swing act (Nargo, Bulbasound, Swing Project, Joe Bennett, StudioKolomna, Frank Schröter). Bensound and Purple Planet terms do not clearly allow app bundling. Fesliyan is non-commercial only.

### Realistic paths to a real 5-minute sax track
1. Keep the loops built above. Hot Swing repeats well. This is enough for the POC.
2. Commission or buy: a licensed stock track from a paid library (Artlist, Epidemic, Pond5 single-track licences) solves "sax + 5 min + steady Lindy beat" in an afternoon for roughly $20-50 per track, and the licence covers apps.
3. Ask a local swing band or a session sax player for a CC BY recording of an original riff tune at 130, 145 and 160 BPM. Dancers' communities do this for practice material.
4. Generate a bed and overdub a real sax player. Avoid shipping AI-only tracks given the user's reaction to the Alex Morgan material.

## Swing-ratio check of every candidate (2026-10-16)

`tools/swingratio.py` measures where offbeat onsets fall inside the beat, relative to the beat peak. 0.50 is straight eighths, about 0.60 a light shuffle, 0.67 triplet swing (what Lindy triple-steps want), 0.75 hard swing. Measured over the first 120 s.

| File | BPM | Offbeat position | Feel |
|---|---|---|---|
| raw/Hot Swing.mp3 | 144 | 0.71 | triplet swing |
| raw/Sweeter Vermouth.mp3 | 156 | 0.62 | light-to-triplet (manual reading 0.64) |
| candidates/Audionautix - Big Swing Band | 236 | 0.68 | triplet swing, far too fast |
| candidates/Audionautix - Boogie Woogie Bed | 170 | 0.40 (ambiguous, grid locked on the offbeat; probably ~0.60) | shuffle |
| candidates/Audionautix - Jumpin Boogie Woogie | 170 | 0.72 | triplet swing |
| candidates/Audionautix - Panama Hat no vocal | 140 | 0.60 | light shuffle |
| candidates/Beat Mekanik - Swing Party | 120 | 0.57 | light shuffle |
| candidates/Doge - Catchy Swing | 120 | 0.66 | triplet swing |
| candidates/Eric Matyas - Big Band Man | 152 | 0.68 | triplet swing |
| candidates/Eric Matyas - Big Band Swingin | 200 | 0.73 | hard swing, too fast |
| candidates/Eric Matyas - Shufflin | 156 | 0.71 | triplet swing |
| candidates/Kevin MacLeod - Malt Shop Bop | 146 | 0.65 | triplet swing |
| candidates/Matthew Pablo - A Conversation with Saul | 90 | 0.64 | triplet swing, slow |
| candidates/Stefan Kartenberg - My Misspent Youth | 150 | 0.67 | triplet swing, vocals |
| candidates/tcarisland - Midnight on Miller Street | 96 | 0.57 | light shuffle, slow |

## Round 2 search with the swing test applied (2026-10-16, evening)

Goal: more tracks like Hot Swing and Sweeter Vermouth. Clear triplet swing, 120-160 BPM, not piano-led. Licence filter relaxed on the user's request: NC/ND tracks are listed separately as "needs a licence deal".

Swept and machine-tested: 136 incompetech tracks (every jazz or horn-led track at 115-165 BPM), 38 app-safe web tracks, 61 relaxed-licence web tracks. Raw results: `sweep_incompetech/_results.tsv`, `sweep_web/_results.tsv`, `sweep_web_nc/_results.tsv`. Shortlisted files are in `candidates/app-safe/` and `candidates/needs-licence-deal/` with BPM in the filename.

Caveat on the test: a "straight" reading at 95-130 BPM can be a half-tempo lock. Re-testing the suspicious ones at double tempo did not rescue any of them, so the readings below stand. Sax presence is still from tags, not ears.

### App-safe (CC0 / CC BY / CC BY-SA)

| Track | BPM | Offbeat | Length | Notes |
|---|---|---|---|---|
| Sonny Young, A Night in Casablanca | 140 | 0.66 | 2:39 | CC BY 4.0. Horn-led dixieland/swing. **Likely algorithmically generated** (2022 library upload, tracks share identical lengths to 0.01 s). |
| Sonny Young, Midnight Romance in New Orleans | 140 | 0.70 | 2:08 | same album |
| Sonny Young, All Saints in Dixieland / Her Spells / New Orleans Love / Where's My Sweetheart | 165 | 0.67-0.69 | 2:30 | same source, fast end of the range |
| Sonny Young, Mississippi Love | 165 | 0.66 | 1:52 | same |
| Sonny Young, Kansas City Blues / My New Baby / Those Days in Dixie | 110 | 0.67-0.71 | 3:44 | same, slow |
| Johnny Pumphandle, Travelin' Well | 154 | 0.66 | 2:45 | CC BY 3.0, 2007 home-studio jump blues, MIDI-ish |
| David R Merry, Dance Floor Boogie 44 | 160 | 0.67 | 4:00 | CC BY 3.0, harmonica jump blues |
| VJ_Memes, Norleans Lovas | 160 | 0.67 | 4:15 | CC BY-SA 3.0 ccMixter remix, New Orleans flavour |
| JeffSpeed68, Chances | 140 | 0.67 | 4:31 | CC BY 3.0, swing with vocals |
| Kevin MacLeod, DarxieLand | 165 | 0.72 | 2:26 | CC BY, trumpet, tuba, banjo dixieland |
| Kevin MacLeod, NoGoodLayabout | 124 | 0.74 | 3:06 | CC BY, clarinet over piano trio |
| Kevin MacLeod, George Street Shuffle | 153 | 0.74 | 4:28 | CC BY, vibraphone and piano, no horns |
| Kevin MacLeod, Malt Shop Bop | 146 | 0.65 | 2:46 | CC BY, saxes, 50s feel |
| Mr Smith, Swing Out | 115 | 0.63 | 1:44 | CC BY 4.0, guitar-led, slow |
| Yubatake, Old Mo | 115 | 0.67 | 2:47 | CC BY 4.0, trombone music-hall, slow |

Straight or too fast, app-safe: Mr Smith Bounce / Swing U Sinners (straight), Squire Tuck (straight-ish), Sascha Ende Summer Swing 2025 (125, straight), Nostalgic Series 002 (194), HoliznaCC0 Swingin Richards (190), Swingalin (200), MintoDog Jazzy Battle Theme (130 but only light shuffle 0.58), Spring Spring Jazz Lost in Time (101, light), Umplix Jazz (138, light 0.62), Hot Club Hold The Bacon (151, light 0.62), Enhanced Reality (148, light), GumboMix (tracker failed).

### Needs a licence deal (NC / ND) but swings in range

| Track | BPM | Offbeat | Length | Licence | Contact |
|---|---|---|---|---|---|
| Laughing Manny & The Swinging Dicks, Sunday Reflection | 127 | 0.68 | 2:34 | CC BY-NC-SA 4.0 | **Real big band.** FMA contact form on the artist page |
| Laughing Manny, Slow Your Roll | 123 | 0.72 | 2:09 | CC BY-NC-SA 4.0 | same. Their faster tracks are 180-196 BPM. |
| Serge Quadrado, Gypsy Swing | 148 | 0.67 | 2:09 | CC BY-NC 4.0 | dkmoris@gmail.com |
| Régis V. Gronoff, Initial Approaches | 144 | 0.74 | 3:45 | CC BY-NC-SA 3.0 | via Jamendo; tagged saxophone |
| StudioKolomna, Retro Jazz Saxophone | 150 | 0.63 | 2:27 | CC BY-NC-ND 3.0 | via Jamendo; sax |
| Maxim Novak, Dixieland | 155 | 0.69 | 2:41 | CC BY-NC-ND 4.0 | FMA |
| GingerTom, The Stroll | 149 | 0.64 | 3:37 | CC BY-ND 3.0 | via Jamendo; big-band library style |
| Dee Yan-Key, Swing | 146 | 0.63 | 5:03 | CC BY-NC-SA 4.0 | djahnke@t-online.de; MIDI-realised |
| NeyMan, Cabaret | 120 | 0.63 | 2:41 | CC BY-NC-ND 3.0 | via Jamendo; sax, brass |
| Joe Bennett, Swing Liza Baby | 120 | 0.65 | 2:30 | CC BY-NC-ND 3.0 | via Jamendo |
| Lobo Loco, Happy Afternoon Boogie | 145 | 0.57 light | 1:37 | CC BY-NC-ND 4.0 | musik@musikbrause.de, sells licences; all four Lobo Loco sax tracks read as light shuffle, not triplet |
| Boom Boom Beckett, When You Leave | 146 | 0.59 light | 4:08 | CC BY-NC-ND 3.0 | via Jamendo; sax |

Swings but too fast: Latché Swing "Swing 39" (200), Swing Ninjas "Blue Drag" (~197), Laughing Manny "Bring In The Horns" (196) and "Let's Hang" (180), Jazzaria (190), Boom Boom Beckett "Zompie" (194), Nargo "Winter Swing Tale" (172), Gronoff "Poor Gal" (170), Tri-Tachyon "Kleptotonic Swing" (186). Straight or lounge: Blue Dot Sessions Speakeasy 1959 (all four tested read straight), Ménage Quad, Swing Project, TheHypnotunez, Nectar, Underscore Orkestra (light at best).

Not testable headlessly: Pixabay (browser download only; most swing uploads there are labelled AI), Rampage Swing on Bandcamp (all rights reserved, name-your-price; the strongest live-band match by description, alto/tenor sax, trumpet, trombone, clarinet; rampageswing.com), Tom Feldmann's cartoon big band pack on itch.io (CC BY 4.0, itch download flow).

## User scores for the round-2 shortlist (2026-10-17, 1-10 compatibility)

7: Laughing Manny "Sunday Reflection" (127), Laughing Manny "Slow Your Roll" (123). Both CC BY-NC-SA, real big band, would need a deal.
6: Sonny Young "All Saints in Dixieland" (165), Sonny Young "New Orleans Love" (165), Johnny Pumphandle "Travelin' Well" (154), Régis Gronoff "Initial Approaches" (144, NC).
5: Sonny Young "Her Spells" (165), JeffSpeed68 "Chances" (140), Kevin MacLeod "DarxieLand" (165).
4: Sonny Young "Mississippi Love". 3: VJ_Memes "Norleans Lovas". 2: Sonny Young "Where's My Sweetheart". 0: George Street Shuffle (scored 2026-10-17).
0 (rejected): both Sonny Young 140 BPM tracks, Dance Floor Boogie 44, NoGoodLayabout, Serge Quadrado, StudioKolomna, Maxim Novak, GingerTom, Dee Yan-Key, NeyMan, Joe Bennett, Lobo Loco, Boom Boom Beckett.

Takeaway: the real big band (Laughing Manny) scored highest; the 6s sit at 144-165 BPM. The user says several of these are better than Hot Swing; Hot Swing and Sweeter Vermouth have not yet been scored on this scale, so the scores are not calibrated against them.

## Decision 2026-10-17: start with Laughing Manny #15 and #16, used interchangeably

The user picked shortlist numbers 15 and 16 as the working practice tracks, to be used interchangeably in the app:

| # | Track | BPM | Offbeat | Length | File |
|---|---|---|---|---|---|
| 15 | Laughing Manny & The Swinging Dicks, Sunday Reflection | 127 | 0.68 | 2:34 | candidates/needs-licence-deal/Laughing Manny - Sunday Reflection (CC BY-NC-SA 4.0) [127bpm, real big band].mp3 |
| 16 | Laughing Manny & The Swinging Dicks, Slow Your Roll | 123 | 0.72 | 2:09 | candidates/needs-licence-deal/Laughing Manny - Slow Your Roll (CC BY-NC-SA 4.0) [123bpm, real big band].mp3 |

Source: album "Swinging Without Words" on Free Music Archive, https://freemusicarchive.org/music/laughing-manny-the-swinging-dicks/swinging-without-words . Licence CC BY-NC-SA 4.0: fine for the private POC, NOT for a paid or ad-supported app. Before shipping, contact the band through the FMA contact form (https://freemusicarchive.org/music/laughing-manny-the-swinging-dicks/contact) and agree a licence. Credit line meanwhile: "Sunday Reflection" / "Slow Your Roll" by Laughing Manny & The Swinging Dicks, CC BY-NC-SA 4.0.

Both are shorter than a 5-minute session, so they get the same loop treatment as Hot Swing (see below once built). The user also scored Hot Swing's rivals: several shortlist tracks are better than Hot Swing in their view.

## Tracked beat grids for the live-band tracks (2026-10-17)

`tools/beatgrid.py` fits one tempo to the whole file, which is right for sequenced music (the Hot Swing loop:
7 ms scatter) and wrong for a band that drifts: on "Sunday Reflection" the single-tempo grid sat on 40 percent
of the onset energy that the tracked beats sit on, and got worse through the track. `tools/beattrack.py`
keeps one measured time per beat (librosa beat tracking, lightly smoothed, snapped to onset peaks within 25 ms)
and writes the same JSON layout with `"grid": "tracked"`; `beats[]` is then NOT uniform and is used directly.

```
python -P tools/beattrack.py "chosen/15 - Laughing Manny - Sunday Reflection (127bpm).mp3" 127 --out "chosen/15 - Laughing Manny - Sunday Reflection (127bpm) - beatgrid.json"
```

| File | Grid | median BPM | interval scatter | swing | onset score |
|---|---|---|---|---|---|
| 15 Sunday Reflection | tracked, 321 beats | 126.82 | 15 ms | 0.69 | 0.371 (fixed grid: 0.154) |
| 16 Slow Your Roll | tracked, 262 beats | 123.34 | 20 ms | 0.71 | 0.142 (quiet track; worth a listen) |

The downbeat phase of both was guessed from onset energy (unreliable on swing); pass `--first-downbeat
SECONDS` if the counts feel a beat off. Each grid sits next to its track as `<track> - beatgrid.json`.

## Beat annotation by ear (2026-10-19)

`music/tools/beat_annotator/server.py` (port 8772) shows a mel spectrogram of each track; Space plays, Enter marks a beat as it
comes, `1` marks a downbeat, and every mark autosaves to `<track> - taps.json`. `grid_from_taps.py <key>` turns the marks into
`<track> - beatgrid.json` (grid `annotated`: constant tempo between consecutive marks, the running period beyond them) and
plots the tempo and phase in `analysis_<key>.png`; the automatic grid is kept as `<track> - beatgrid.analysis.json`.

Result so far: Hot Swing and Sunday Reflection sit on their automatic grids. **Slow Your Roll is the hard track**: 34 marks
over 0-112 s give a base tempo of 122.7 BPM that the band bends on purpose, down to 107-111 BPM around 58-63 s and up to
138-142 BPM around 83-87 s, so a fixed metronome would be up to 0.8 beat off; the automatic tracker had followed it to
within 170 ms. The app's grid for this track is the annotated one.

## Tempo variants for the app (2026-10-19)

The app lets the dancer shift a track's tempo by -40, -30, -20, -10, +10 or +20 BPM. `tools/make_variants.py` renders those
shifts offline with the pitch unchanged into `variants/<key>_<offset>.mp3` (keys hot, sunday, slow; mono, 96 kbps, 48 kHz,
no tags) and `variants/variants.json` (per track: base_bpm, engine, and for every offset bpm, tempo, file, duration and the
check result). A variant at tempo t = (bpm + offset) / bpm has every beat of the track's grid at beats[i] / t, so the app
scales the grid instead of needing a new one.

Engine: the Rubber Band 4.0.0 command-line tool, R3 ("--fine") engine in offline mode, the best time-stretcher there is for
music; downloaded from https://breakfastquay.com/rubberband/ (rubberband-4.0.0-gpl-executable-windows.zip). It is GPL, but
it only runs on the PC: nothing of it ships in the app. The script falls back to ffmpeg's rubberband filter (R2) and then to
atempo, and records which engine it used. ffmpeg decodes and encodes; its gapless mp3 decode matches librosa's sample for
sample, so the grids keep their origin and nothing is trimmed. The mono mix is (L+R)/2 (ffmpeg's own `-ac 1` adds 3 dB),
and the few peaks the phase vocoder pushes above -0.45 dBFS are tamed by a 30 ms look-ahead limiter instead of lowering the
whole file, so a variant plays as loud as its original.

```
python -P tools/make_variants.py --rubberband "<unzipped folder>\rubberband.exe"   # build what is missing, then verify
python -P tools/make_variants.py --check                                           # verify only, refresh the manifest
```

Verification (`--check`): the onset strength of each file (librosa, 22050 Hz, hop 256, on a 1 ms axis) is correlated with
a Gaussian-smoothed (8 ms) impulse train on the scaled grid, over the whole file (lag searched in -100..+100 ms) and in 15 s
windows (searched within 80 ms of the file's lag, because the swing "&" makes a second peak 130-140 ms before the beat that
comes within reach once the tempo changes, and wins in windows where the offbeats are accented); a variant passes when its
duration is within 60 ms of original / t, its lag is within 20 ms of the original's and every window lag is within 30 ms of
the original's window lag. "shift" is a second, grid-free test: the variant's onset envelope mapped back onto the original's
timeline against the original's envelope (ms of the variant's time, + = later); "drift" is the largest window deviation from
that shift, so it would show any non-linearity of the stretch.

Result of 2026-10-19 (Rubber Band 4.0.0 R3; ms; win = largest window deviation from the original's window lag):

| track | offset | tempo | duration s | lag | win | shift | drift | result | MB |
|---|---|---|---|---|---|---|---|---|---|
| hot | 0 | 1.0000 | 314.97 | 6 | 0 | - | - | ref | 12.60 |
| hot | -40 | 0.7223 | 436.08 | -6 | 13 | -12.5 | 1.4 | pass | 5.23 |
| hot | -30 | 0.7917 | 397.84 | -2 | 9 | -8.8 | 1.3 | pass | 4.77 |
| hot | -20 | 0.8611 | 365.76 | 1 | 6 | -4.6 | 1.2 | pass | 4.39 |
| hot | -10 | 0.9306 | 338.47 | 4 | 3 | -2.1 | 1.1 | pass | 4.06 |
| hot | +10 | 1.0694 | 294.52 | 9 | 4 | 2.8 | 0.9 | pass | 3.53 |
| hot | +20 | 1.1389 | 276.56 | 10 | 5 | 4.4 | 0.9 | pass | 3.32 |
| sunday | 0 | 1.0000 | 154.68 | 3 | 0 | - | - | ref | 3.71 |
| sunday | -40 | 0.6846 | 225.94 | -12 | 16 | -14.6 | 1.5 | pass | 2.71 |
| sunday | -30 | 0.7634 | 202.61 | -7 | 11 | -10.5 | 1.3 | pass | 2.43 |
| sunday | -20 | 0.8423 | 183.64 | -4 | 8 | -7.1 | 1.2 | pass | 2.20 |
| sunday | -10 | 0.9211 | 167.92 | 1 | 3 | -3.3 | 1.1 | pass | 2.02 |
| sunday | +10 | 1.0789 | 143.37 | 6 | 4 | 2.8 | 0.0 | pass | 1.72 |
| sunday | +20 | 1.1577 | 133.61 | 8 | 6 | 4.3 | 0.9 | pass | 1.60 |
| slow | 0 | 1.0000 | 129.84 | 36 | 0 | - | - | ref | 3.12 |
| slow | -40 | 0.6743 | 192.56 | 36 | 16 | -17.8 | 3.0 | pass | 2.31 |
| slow | -30 | 0.7557 | 171.81 | 36 | 11 | -10.6 | 1.3 | pass | 2.06 |
| slow | -20 | 0.8371 | 155.10 | 36 | 11 | -6.0 | 1.2 | pass | 1.86 |
| slow | -10 | 0.9186 | 141.35 | 36 | 6 | -2.2 | 1.1 | pass | 1.70 |
| slow | +10 | 1.0814 | 120.06 | 36 | 7 | 2.8 | 0.9 | pass | 1.44 |
| slow | +20 | 1.1629 | 111.66 | 37 | 11 | 5.2 | 0.9 | pass | 1.34 |

All 18 variants pass, 48.7 MB in all. Every duration is original / t to the 10 ms shown and the stretch is linear (drift
3 ms at worst), so beats[i] / t is the right grid. A variant's lag stays within 15 ms of its original's (within 1 ms on
the hard track) and no window moves more than 16 ms. "shift" grows with the stretch (-18 ms at -40 BPM, +5 ms at +20):
a phase vocoder spreads each attack over a little more time and the first part of that spread comes early, which pulls the
envelope's centre earlier while the beat peak itself stays put; the ear gets a slightly softer attack, not an early beat.
Slow Your Roll's annotated grid sits 36 ms ahead of the onset peaks throughout (the sequenced Hot Swing: 6 ms), and that
carries over unchanged. The limiter only had work on the Hot Swing variants (21-91 samples each, peaks up to 1.21, dips of
1-2 dB for about 60 ms) and once, by 0.1 dB, on slow +10. The Rubber Band binary was unzipped in the session's temp folder;
download it again for a rebuild (or drop it in `tools/rubberband*/`, where the script looks).

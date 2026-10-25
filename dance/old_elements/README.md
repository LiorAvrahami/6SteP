# old_elements/

Set aside on 2026-10-25, when the user decided to write every element by hand in the web editor and to disconnect the
lurklurk notes from the project. Nothing here is read by the app, the editor or the tools.

| File | What |
| --- | --- |
| `skeletons.json` | The elements the 5 minutes exercise does not use (rock step side R and facing L/R, triple step facing L/R, circle), exactly as they were: with their lurklurk codes, sources and notes. To bring one back, copy its object into the `elements` list of `dance/skeletons.json` (the editor shows it on reload). |
| `compositions.json` | The compositions and sub-compositions the exercise does not use (rock double triple step, rock triple step facing, all elements demo, test; pass+circle). Bring one back the same way, into `dance/compositions.json`. |
| `all_elements_2026-10-25.json` | All 14 elements as they were on that day (the last `skeletons.baseline.json`), with every lurklurk field. |
| `author_skeletons.py` | Retired: the agent's script that generated the skeletons from the lurklurk codes. It no longer runs (model.py has no baseline any more), on purpose: only the editor writes `dance/skeletons.json`. |
| `diff.py` | Retired with it: it compared the skeletons with that baseline. There is one version now, the one on disk. |

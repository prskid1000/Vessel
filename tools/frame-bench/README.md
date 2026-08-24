# frame-bench

Ground-truthed test scenes and artefact metrics for the frame interpolation
pipeline, run on a laptop.

## Why this exists

Testing a shader change on the device costs about ten minutes: build, install,
launch, three minutes of menus, reach the scene, play, record, analyse. Worse,
every run is a different scene at a different camera speed, so two algorithms can
never be compared on the same input. Metro's subtitles are on screen for two or
three seconds, which cannot be captured on purpose at all.

The first attempt to measure the subtitle fix that way read 10.6% ghost spill
before the change and 27.0% after — on recordings of different lengths of
different action. That number measured nothing, and read backwards.

## The rule this file exists to enforce

**Every artefact needs its own metric, and the obvious metric is usually blind to
it.** This has now been demonstrated four separate times:

- **RMS error cannot see waviness.** A wavy edge and a straight one hold the same
  ink in nearly the same places. The difference is geometry.
- **A waviness metric cannot see ghosting.** A displaced duplicate of a subtitle
  is perfectly straight.
- **RMS error cannot see ghosting either.** It called the fix that removes 81% of
  the ghost a net loss of eight to one, because it averages a legible duplicate
  subtitle against eight hundred thousand untouched pixels.
- **A frame counter cannot see a dropped frame.** Two presents into one refresh
  are both counted and only one is shown.

So each scene below ships with the metric that can see its own artefact, and a
change is judged by that metric — not by the mean.

## The scenes

Each builds a pair of frames from a real 1280x720 game frame with an exactly
known motion, so the correct interpolated frame is known and error is measurable
rather than argued. The motion field is not invented either: `bench.estimate` is
an 8x8 exhaustive block match on luma, which is what `GL_QCOM_motion_estimation`
does in hardware, so blocks reach the same compromises they reach on device and
artefacts appear for the same reasons rather than by construction.

| file | scene | artefact | metric |
|---|---|---|---|
| `bench.py` | static text over a pan | subtitle ghosting | ink appearing where the truth has none |
| `zigzag.py` | a beam and the wall behind it, at different depths | waviness on straight edges | RMS deviation of the edge from the line it should be |
| `gradient.py` | a ground plane, motion ramping with depth | staircasing of smooth motion | second difference of the field down the rows |

`interp.py` is `InterpolateMaterial` reimplemented in numpy, step for step.

### The contract

**`interp.py` must stay a faithful port of the GLSL.** Anything proved here is
transcribed back unchanged; if the two drift, every number this produces is
fiction. That is the one maintenance hazard in this directory.

## Running

    python run_bench.py       # the text scene, every static-suppression variant
    python zigzag.py          # waviness, against the ground truth
    python gradient.py        # imported by the others; builds the ramp scene

## Analysing a real recording

    python artefacts.py recording.mp4

Reports stutter, smearing, ghosting and invented content from a screen
recording. Everything except stutter needs real and synthesised frames told
apart, and **that cannot be done by inference** — it was tried. `screenrecord`
captures at the panel's rate while the compositor presents at its own, so the two
are not locked and "every Nth captured frame" is not the real one. Over a
30-frame window a 4x periodicity looked clean at 10% sharper; over the whole
1216-frame recording it washed out to 0.4% and the classification was worthless.

Set `FG_LOG=mark` in the container's environment table before recording. The
interpolation shader then stamps sixteen magenta pixels in one corner, which no
real frame carries, and the split becomes exact. Without it the tool says which
metrics it could not establish rather than guessing them.

## What this has already caught

- **Two "fixes" that were regressions.** Both improved the subtitle and tripled
  the error across the rest of the frame (scene 1.12 to 3.03 levels), which no
  amount of looking at recordings had revealed.
- **The reasoning behind them.** "Did this pixel change between the two real
  frames" cannot separate a static overlay from a flat wall, because neither
  changes.
- **Three plausible ideas, in minutes each.** A 3x3 mean of the field (worse at
  every strength), a componentwise median (half as good as the vector one), and
  smoothing along the edge as the aperture reading suggests (worse than doing
  nothing, at 11.0, 12.1 and 13.5 against 9.54).
- **Why the median filter could only ever run twice**, and that anchoring it to
  the matcher's own output removes the ceiling entirely — 9.54 px of waviness to
  0.013, which is the ground truth's own figure.
- **That a wrong objection was wrong.** Ten median passes were held back to six on
  the theory that they would staircase real parallax; `gradient.py` was built to
  catch exactly that and showed banding *falling* with passes instead.
- **That perfect motion knowledge makes the background beside a subtitle four
  times worse** (25.85 against 6.25), because that background is occluded by the
  overlay in both frames and was never photographed.

## What none of this can see

**The desktop mouse cursor.** Two pacing changes were reverted because four
recordings showed no movement in the hold histogram — 25.6%, 27.0%, 24.5%,
27.0% of pictures held for the correct two refreshes. What the histogram did
not say, and what the user reported immediately, is that those same builds
visibly distorted the cursor on the Wine desktop.

A cursor is on the order of a thousand pixels in a million. Every metric in
this directory averages over the frame or over the moving part of it, so a
badly displaced cursor moves `ghosting` and `invented content` by roughly
nothing. The frame-average blindness that made RMS error reject the subtitle
fix is not specific to image metrics: it applied here to a *timing* change,
where nothing in the tooling was even looking at the image.

Two consequences worth keeping:

- **A small, fast, high-contrast object on a still background is the worst case
  for interpolation and the best case for spotting it by eye.** The desktop
  cursor is a free test scene that is always available and needs no game.
- **Agreement between a metric and a person is worth more than either.** The
  histogram said "no change" and the eye said "worse"; both pointed at reverting
  the same two changes, for different reasons, and only the second one would
  have justified reverting on its own.

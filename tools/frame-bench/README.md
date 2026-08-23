# frame-bench

A ground-truthed test bench for the frame interpolation shader, run on a laptop.

## Why

Testing a shader change on the device costs about ten minutes: build, install,
launch, three minutes of menus, reach the scene, play, record, analyse. Worse,
every run is a different scene at a different camera speed, so two algorithms
can never be compared on the same input. Metro's subtitles are on screen for two
or three seconds, which cannot be captured on purpose at all.

The first attempt to measure the subtitle ghosting fix that way read 10.6% ghost
spill before the change and 27.0% after -- on recordings of different lengths of
different action. That number measured nothing, and read backwards.

## What it does

`bench.py` builds the pair from a real 1280x720 game frame, shifted by an exactly
known vector, with static text composited on both at the same position -- which
is what a subtitle is. Because the shift is known, the correct interpolated frame
is known: background at half the shift, text where it was. So error is measurable
rather than arguable, separately inside the text and outside it.

The motion field is not invented. `bench.estimate` is an 8x8 exhaustive block
match on luma, which is what `GL_QCOM_motion_estimation` does in hardware, so
blocks straddling the subtitle reach the same compromise they reach on the device
and the artefact appears for the same reason rather than by construction.

`interp.py` is `InterpolateMaterial` in numpy, step for step.

## The contract

**`interp.py` must stay a faithful port of the GLSL.** Anything proved here is
transcribed back unchanged; if the two drift, results here stop meaning anything.

## Running

    python run_bench.py

## What it has already caught

Two fixes that improved the text and silently tripled the error everywhere else
(scene 1.12 -> 3.03 levels), and the reasoning error behind both: "did this pixel
change between the two real frames" cannot separate a static overlay from a flat
wall, because neither changes. It also established that perfect motion knowledge
makes the background beside a subtitle four times *worse*, since that background
is occluded by the overlay in both frames and was never photographed.

# The input screen, redesigned

Written 2026-08-11, after the tabs came out and the result was confusing.

## What is wrong, precisely

The screen shows **one controller under two mental models**, and never says they
are the same thing.

- The **overlay half** is free-form. Controls have positions, sizes, opacity;
  you add and remove them. It answers "what is on my glass".
- The **pad half** is a fixed table of 24 rows. You cannot add or remove a row;
  you can only bind it. It answers "what does each control send".

A user reading top to bottom sees a d-pad in a picture, then `Add a control`,
then a *second* fixed list containing d-pad rows they cannot delete. Nothing
explains that the glass d-pad **is** row `D-pad up`, or why one is editable and
the other is not. Merging the tabs put the seam in the middle of one scroll
instead of hiding it behind a tab, which made it visible rather than fixed.

Two smaller faults fall out of the same seam:

- **`Add a control` asks for no name.** A placed control is drawn with a label,
  and the label comes from its binding — so a control you add is unnamed until
  you bind it, and unnamed on the glass until then.
- **Two vocabularies.** The overlay says *Button, Stick, Look pad*; the pad says
  *A, L2, D-pad up*. The same object has two names depending on which half you
  are looking at.

## The idea

**One list of controls. Every row is a control. A row can be on the glass, on
the pad, or both.**

That is the whole redesign. It is already true of the data — a `TouchControl`
carries `pad`/`padStick` and borrows the pad table's binding — and the screen is
the last place still pretending otherwise.

### The row

| Field | Meaning |
|---|---|
| **Name** | What is drawn on the glass. Defaults to the pad control's name (`A`, `L2`), editable for one you placed. This is the missing field. |
| **Sends** | A key, a mouse button, a pad control, or itself. One picker, one vocabulary. |
| **On the glass** | A toggle. Off means bindable but not drawn — which is what the 24 pad rows are today. |
| **Where / size** | Only when it is on the glass. |

A control that is *both* shows all four. A physical-only control shows the first
two. Nothing is a special case; the fixed 24 become rows whose **On the glass**
happens to be off, and `Add a control` makes a row with it on.

### The screen

1. **The map.** The real overlay, one picture, tap a control to select it.
   `Arrange the overlay` opens the full-screen editor.
2. **The selected control.** The row above, expanded.
3. **Every control.** One list, grouped: on the glass first, then the rest.
4. **Settings.** Show overlay, opacity, deadzone, look speed, stick roles, Learn.
5. **Profile.** Name and the list.

### The header

No dropdown. It is a profile *name* plus the actions, because the dropdown was
doing selection while a list below did the same selection differently. The list
in §5 selects; the header names and acts: new, duplicate, delete, import,
export.

## What must not be lost

Everything that exists today keeps working: stick roles including `Pad`, the
deadzone and look-speed sliders, Learn, the key catalogue, stock layouts, reset
to default, import/export through SAF, the full-screen arranger, per-control
opacity, and the built-in default being undeletable.

## Why now, and the risk

The screen has taken eleven changes today and each was locally right. The
confusion is structural and will not yield to a twelfth. But note that **the pad
still delivers nothing to the guest** — `bus_vessel.c` builds a HID device and
applies no state to it — so this redesign should be built against input that
works, or it will be tuned against a controller that cannot be tested.

*Do the bridge first. Then this, in one pass, from this document.*

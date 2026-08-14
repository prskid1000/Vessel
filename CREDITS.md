# Credits

Vessel is an integration project. Almost all of the hard engineering it depends
on was done by other people, and most of it is still being done — several of
these components are moving weekly. This file exists so that is never unclear.

## Core components

| Project | Authors | What it does here |
|---|---|---|
| [Wine](https://www.winehq.org/) | Wine developers, Alexandre Julliard | Implements the Windows API. Everything else is in service of this. |
| [Wine ARM64EC branch](https://github.com/bylaws/wine) | Billy Laws (bylaws) | The ARM64EC work that makes native-ARM64 Wine with translated x86 apps possible. |
| [FEX-Emu](https://github.com/FEX-Emu/FEX) | FEX-Emu team | x86/x86-64 → ARM64 translation; our primary engine, loaded into Wine as PE DLLs. |
| [Mesa / Turnip](https://gitlab.freedesktop.org/mesa/mesa) | Mesa developers; freedreno by Rob Clark, Danylo Piliaiev, Connor Abbott | Open-source Vulkan driver for Adreno. |
| [Turnip gen8 branch](https://github.com/whitebelyash/mesa-unified) | whitebelyash and contributors | Adreno 8xx support ahead of mainline — the only reason this works on an Adreno 829. |
| [DXVK](https://github.com/doitsujin/dxvk) | Philip Rebohle and contributors | Direct3D 9/10/11 → Vulkan. |
| [vkd3d-proton](https://github.com/HansKristian-Work/vkd3d-proton) | Hans-Kristian Arntzen and contributors | Direct3D 12 → Vulkan. |
| [Proton](https://github.com/ValveSoftware/Proton) | Valve | Wine patches that make games work. |
| [GStreamer](https://gstreamer.freedesktop.org/) | GStreamer developers | Wine's media stack. `winegstreamer.so` is the whole implementation behind Media Foundation, DirectShow and the decoder MFTs — Wine itself contains no demuxer and no codec. |
| [FFmpeg](https://ffmpeg.org/) | FFmpeg developers | The decoders, reached through gst-libav, and the demuxer behind `winedmo.so`. |
| [GLib](https://gitlab.gnome.org/GNOME/glib) | GNOME developers | GStreamer's object system, and therefore a hard dependency of the above. |

## Android integration lineage

Vessel's container/X-server integration descends from the Winlator family. The
chain of work it builds on:

| Project | Author | Contribution |
|---|---|---|
| [Winlator](https://github.com/brunodev85/winlator) | Bruno Rodrigues (brunodev85) | The original — Wine containers on Android with a built-in X server. |
| [Winlator Cmod](https://github.com/coffincolors/winlator) | coffincolors | Fork with substantial container and compatibility work. |
| [Winlator Bionic](https://github.com/Pipetto-crypto/winlator) | Pipetto-crypto | The bionic-native lineage and ARM64EC containers; also `libadrenotools`. |
| [Winlator-Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) | StevenMXZ | Aggressive component currency, Vulkan renderer rewrite, and the gen8 Turnip CI builds. |
| [libadrenotools](https://github.com/bylaws/libadrenotools) | Billy Laws (bylaws) | Loading custom GPU drivers on Android without root. |
| [AdrenoToolsDrivers](https://github.com/K11MCH1/AdrenoToolsDrivers) | K11MCH1 | Packaged Turnip driver builds. |

### Code shipped inside the app

Three pieces of other people's work are compiled into the APK itself.

**libadrenotools**, by Billy Laws (bylaws), BSD-2-Clause, vendored at commit
`8fae8ce` into `app/src/main/cpp/adrenotools/` with its `linkernsbypass`
submodule. It is the reason a custom Vulkan driver can be loaded on Android
without root, and there is no alternative to it that does not involve rooting the
phone: Turnip builds as an Android Vulkan HAL, so only the platform loader can
load it, and only libadrenotools' `android_dlopen_ext` interposer can make that
loader pick a driver out of app storage. Without it Vessel's chip-tuned Mesa
build is a file on disk that nothing ever calls. `app/src/main/cpp/adrenotools/README.md`
records what was taken and what was left.

**Phosphor Icons**, by Tobias Fried and Helena Zhang, MIT. Every glyph in the
interface is a Phosphor *regular* path, transcribed as its `d` attribute into
`app/src/main/java/app/vessel/ui/components/VIcons.kt` from
`@phosphor-icons/core` 2.1.1 and verified against the package. The paths are
copied unaltered; nothing else of the project is used. It is transcription rather
than a dependency because Phosphor ships no Compose artifact, and the Material
alternative is several thousand glyphs and about a megabyte of dex for the two
dozen this app draws.

Vessel's display backend **is** Winlator's X server, vendored rather than
reimplemented: the X11 server, the GL compositor, the socket connector and the
`libwinlator` JNI, from
[`brunodev85/winlator-app`](https://github.com/brunodev85/winlator-app) at
commit `ca3d735`, under LGPL-2.1. It lives at
`app/src/main/java/com/winlator/` with upstream's package names intact;
`app/src/main/java/com/winlator/README.md` records what changed. This is the
single largest piece of other people's work inside the APK.

## Prior art we learned from

- [Hangover](https://github.com/AndreRH/hangover) — the original Wine + emulator-on-ARM combination.
- [GameNative / proton-wine](https://github.com/GameNative/proton-wine) — arm64ec Proton builds and the `.wcp` packaging convention.
- [Cassia](https://github.com/casuallyblue/cassia) — Wine + DXVK + FEX on Android.

## A note on forks

Where Vessel vendors or adapts code from any project above, the original
copyright headers and license are kept intact, and the adaptation is recorded
in `docs/LICENSING.md`. If you are one of the authors listed here and want
attribution changed or code removed, open an issue and it will be handled.

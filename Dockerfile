# Reproducible build environment for every Vessel native component.
#
# One image builds all of them: Box64 and Turnip via the Android NDK, and
# FEX/Wine/DXVK/vkd3d as Windows PE via llvm-mingw. Pinning the toolchains here
# is what makes "it built on my machine" stop being a variable.
#
#   docker build -t vessel-build .
#   docker run --rm -v "$PWD:/src" -v vessel-work:/work vessel-build ./build/box64.sh
#
# The named volume for /work matters on Windows: object files must not be
# written across the bind mount or builds crawl. See docs/BUILDING.md.

FROM ubuntu:24.04

# Keep these in sync with native/pins.env.
ARG ANDROID_NDK_VERSION=r29
ARG LLVM_MINGW_VERSION=20250910

# llvm-mingw asset naming has varied between releases. It is a full URL ARG so
# that a changed naming scheme is a one-line fix rather than a Dockerfile edit.
# VERIFY on first build: that this asset exists for the pinned version.
ARG LLVM_MINGW_URL=https://github.com/mstorsjo/llvm-mingw/releases/download/${LLVM_MINGW_VERSION}/llvm-mingw-${LLVM_MINGW_VERSION}-ucrt-ubuntu-22.04-x86_64.tar.xz

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
      # core build tooling
      build-essential cmake ninja-build pkg-config git curl wget ca-certificates \
      file patch unzip xz-utils zstd python3 python3-pip python3-setuptools \
      # meson, for Mesa / DXVK / vkd3d
      meson python3-mako python3-yaml python3-packaging \
      # Mesa codegen and shader tooling
      bison flex glslang-tools libarchive-tools gettext \
      # Wine's host-side tools
      autoconf automake libtool \
    && rm -rf /var/lib/apt/lists/*

# --- Android NDK -------------------------------------------------------------
# r29 is required. Android's clang 19 (r28c) is built from a pre-oryon LLVM
# snapshot and rejects -mtune=oryon-1 despite the version number; r29's clang 21
# accepts it. The check below tests the actual flag, not the version.
RUN set -eux; \
    curl -fSL -o /tmp/ndk.zip \
      "https://dl.google.com/android/repository/android-ndk-${ANDROID_NDK_VERSION}-linux.zip"; \
    unzip -q /tmp/ndk.zip -d /opt; \
    rm /tmp/ndk.zip; \
    mv "/opt/android-ndk-${ANDROID_NDK_VERSION}" /opt/android-ndk

ENV ANDROID_NDK_HOME=/opt/android-ndk
ENV ANDROID_NDK_ROOT=/opt/android-ndk

# Fail the image build, not every component build, if this NDK cannot target the
# core we tune for.
#
# This tests the flag rather than the version on purpose: r28c advertises clang
# 19.0.1 yet rejects -mtune=oryon-1, because Android builds clang from an LLVM
# snapshot that predates the oryon-1 definition. A version comparison passes
# there and then fails at compile time, which is the worst place to find out.
RUN set -eux; \
    B=/opt/android-ndk/toolchains/llvm/prebuilt/linux-x86_64/bin; \
    "$B/clang" --version | head -1; \
    printf 'int main(void){return 0;}\n' > /tmp/probe.c; \
    "$B/clang" --target=aarch64-linux-android35 -mtune=oryon-1 -c /tmp/probe.c -o /tmp/probe.o \
      || { echo "ERROR: this NDK rejects -mtune=oryon-1, which Box64's SD8EG5 preset requires."; \
           echo "Supported CPUs:"; "$B/clang" --target=aarch64-linux-android35 --print-supported-cpus 2>&1 | tail -20; \
           exit 1; }; \
    rm -f /tmp/probe.c /tmp/probe.o; \
    echo "oryon-1 tuning supported"

# --- llvm-mingw --------------------------------------------------------------
# Provides aarch64-w64-mingw32 and, critically, arm64ec-w64-mingw32.
RUN set -eux; \
    curl -fSL -o /tmp/mingw.tar.xz "${LLVM_MINGW_URL}"; \
    mkdir -p /opt/llvm-mingw; \
    tar -xJf /tmp/mingw.tar.xz -C /opt/llvm-mingw --strip-components=1; \
    rm /tmp/mingw.tar.xz

ENV LLVM_MINGW_HOME=/opt/llvm-mingw
ENV PATH=/opt/llvm-mingw/bin:$PATH

# ARM64EC is the whole basis of the Universal container profile. If this
# toolchain cannot target it, everything downstream silently degrades, so the
# image refuses to build instead.
RUN set -eux; \
    test -x /opt/llvm-mingw/bin/arm64ec-w64-mingw32-clang \
      || { echo "ERROR: llvm-mingw ${LLVM_MINGW_VERSION} has no arm64ec target"; exit 1; }

# --- widl ----------------------------------------------------------------------
# vkd3d-proton generates its COM headers from .idl at configure time and hard
# requires widl, the Wine IDL compiler:
#   meson.build:76: ERROR: Program 'widl-stable widl-mingw-tools-fallback' not found
# On Debian/Ubuntu it comes from mingw-w64-tools, not from any wine package.
#
# Installed as its own late layer on purpose: adding it to the apt block at the
# top would invalidate the NDK layer and re-download 700 MB.
# Note the binary is prefixed — the package installs x86_64-w64-mingw32-widl
# and i686-w64-mingw32-widl, never a plain `widl`. vkd3d's meson looks for
# `widl`, then `widl-stable`, then `widl-mingw-tools-fallback`, and upstream
# documents that last name as a cross-file hook; build/vkd3d.sh points it at the
# binary below rather than relying on a symlink existing here.
RUN apt-get update && apt-get install -y --no-install-recommends \
      mingw-w64-tools \
    && rm -rf /var/lib/apt/lists/* \
    && test -x /usr/bin/x86_64-w64-mingw32-widl

# --- FreeType, for Wine's host-side font tools -----------------------------------
# Wine ships its core bitmap fonts (System, Fixedsys, Terminal, Courier, ...) as
# .fon files generated at build time by tools/sfnt2fon from the bundled TTFs.
# That tool is a HOST tool, so it needs the HOST's FreeType — the aarch64 one in
# the Android sysroot is no use to it. Built without it, sfnt2fon compiles to a
# stub whose whole body is an error message, and the cross build dies late:
#   tools/sfnt2fon/sfnt2fon needs to be built with FreeType support
#   make: *** [fonts/coue1255.fon] Error 1
# after an hour of work, because fonts/ is near the end of the build.
#
# Late layer on purpose: adding it to the apt block at the top would invalidate
# the NDK layer and re-download 700 MB.
RUN apt-get update && apt-get install -y --no-install-recommends \
      libfreetype-dev \
    && rm -rf /var/lib/apt/lists/* \
    && pkg-config --exists freetype2

# --- Meson ---------------------------------------------------------------------
# Ubuntu 24.04 ships meson 1.3.2 and Mesa requires >= 1.4, so the apt version
# cannot configure Turnip at all. pip's copy lands in /usr/local/bin and shadows
# it; the apt package stays only because removing it here would invalidate the
# NDK layer above and re-download 700 MB for nothing.
#
# --break-system-packages is needed because 24.04 marks the system Python as
# externally managed (PEP 668). This is a single-purpose build image, so
# installing into the system interpreter is fine.
RUN pip3 install --no-cache-dir --break-system-packages 'meson>=1.8.0' \
    && meson --version

# Object files land here; mount a volume over it.
ENV VESSEL_WORK_DIR=/work
RUN mkdir -p /work

WORKDIR /src
ENTRYPOINT ["/bin/bash", "-c", "exec \"$@\"", "--"]
CMD ["bash"]

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

# Object files land here; mount a volume over it.
ENV VESSEL_WORK_DIR=/work
RUN mkdir -p /work

WORKDIR /src
ENTRYPOINT ["/bin/bash", "-c", "exec \"$@\"", "--"]
CMD ["bash"]

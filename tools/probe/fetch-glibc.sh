#!/usr/bin/env bash
# Extract the glibc artifacts probes 1 and 4 need from an arm64 Ubuntu image.
#
#   ./tools/probe/fetch-glibc.sh
#
# `docker create` materialises the image's layers without executing a single
# instruction in them, so no arm64 container is ever run and no qemu-user
# emulation is involved. Nothing here is a component and nothing is shipped:
# out/ is not packaged, and docs/LINUX-MODE.md §9.5 is why redistributing a
# distro image is a separate, unbounded problem.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$REPO/out/linuxprobe/rootfs"
IMAGE="${1:-ubuntu:24.04}"
CTR=vessel-glibc-extract

rm -rf "$OUT"
mkdir -p "$OUT/lib" "$OUT/usr/lib/aarch64-linux-gnu" "$OUT/bin"

docker pull --platform linux/arm64 "$IMAGE"
docker rm -f "$CTR" >/dev/null 2>&1 || true
docker create --platform linux/arm64 --name "$CTR" "$IMAGE" >/dev/null

cp_out() { MSYS_NO_PATHCONV=1 docker cp "$CTR:$1" "$2"; }

cp_out /usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 "$OUT/lib/"
cp_out /usr/lib/aarch64-linux-gnu/libc.so.6             "$OUT/usr/lib/aarch64-linux-gnu/"
# `ls` needs these two as well, and that is the point of shipping it: a
# three-deep DT_NEEDED graph resolved by glibc's own loader is a much stronger
# result than one program that needs libc alone.
cp_out /usr/lib/aarch64-linux-gnu/libselinux.so.1        "$OUT/usr/lib/aarch64-linux-gnu/"
cp_out /usr/lib/aarch64-linux-gnu/libpcre2-8.so.0.11.2   "$OUT/usr/lib/aarch64-linux-gnu/libpcre2-8.so.0"

# Three real, stock, dynamically linked Ubuntu programs. `echo` is the smallest
# thing in the image that prints, which is exactly what probe 4 has to show;
# `uname` proves the guest can read the kernel it is running on; `ls` proves a
# non-trivial dependency graph and getdents both work.
cp_out /bin/echo  "$OUT/bin/"
cp_out /bin/uname "$OUT/bin/"
cp_out /bin/ls    "$OUT/bin/"

docker rm -f "$CTR" >/dev/null

echo
echo "extracted from $IMAGE into $OUT:"
find "$OUT" -type f -printf '  %-56p %s bytes\n' 2>/dev/null || find "$OUT" -type f

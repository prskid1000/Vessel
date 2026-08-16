#ifndef COPY_POOL_H
#define COPY_POOL_H

#include <stdint.h>

/*
 * VESSEL: one rectangular pixel copy, split into row bands across a small
 * persistent worker pool. New file; upstream Winlator has no counterpart.
 * See src/copy_pool.c for why it exists and what it is allowed to assume.
 *
 * Everything is in bytes, not pixels, and the caller has already applied its
 * own x/y offsets to `dst` and `src`. `rowBytes` is what each row copies;
 * `dstStride`/`srcStride` are what each row advances by. When all three are
 * equal the region is contiguous and each band collapses to a single memcpy,
 * which is the fast path the per-row loop in drawable.c had before this.
 *
 * **Synchronous.** Returns only when every band is copied, on every path
 * including the single-threaded fallbacks. Callers hold locks and lifetimes
 * across it (Drawable.copyArea's dma-buf sync bracket, PresentExtension's
 * idle-notify ordering) and none of that is weakened by this file.
 */
void copyPoolCopyRows(uint8_t *dst, int dstStride, const uint8_t *src, int srcStride,
                      int rowBytes, int rows);

#endif

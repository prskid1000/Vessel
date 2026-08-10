/*
 * How much does running x86 code here actually cost?
 *
 * Built three ways from this one file — ARM64, x86-64 and x86-32 — and run
 * through Wine on the phone. The ARM64 build runs natively and is the control;
 * the other two go through FEX. The number that matters is never a single time,
 * it is the ratio between a translated build and the native one, because that
 * ratio is the only part of the measurement a warm phone cannot invent.
 *
 * Four sections, chosen because they stress different parts of a translator
 * rather than because they look like a workload:
 *
 *   int     dependent integer ALU chain. Almost pure instruction throughput,
 *           and the case a JIT should get closest to native on.
 *   branch  a data-dependent branch the predictor cannot learn. Translated
 *           code pays here twice, once for the mispredict and once for
 *           whatever the JIT emitted around the compare.
 *   mem     strided reads and writes over a buffer larger than L2. Mostly the
 *           memory system, so it should be nearly translation-independent —
 *           if this one is far off native, something is wrong with the
 *           addressing path, not with the core.
 *   float   double-precision FMA chain. Exercises the SSE-to-NEON mapping.
 *
 * Every section ends in a checksum that is printed and must match across all
 * three architectures. A translator that is fast because it skipped the work
 * would otherwise read as a win. If the checksums differ, the timings are void.
 *
 * The float checksum only compares if the build disables FP contraction — see
 * -ffp-contract=off in tools/device-bench.sh. Without it clang fuses
 * `z*0.5 + x*y` into a single FMA on ARM64 and emits a separate multiply and
 * add on x86, which is two roundings instead of one. Both are correct and the
 * results differ in the last place, so the checksum reports a mismatch that has
 * nothing to do with FEX. That happened on the first run of this benchmark.
 *
 * Deliberately no threads and no syscalls in the timed regions: this measures
 * translation, and a benchmark that spends its time in the kernel measures the
 * kernel. Wine startup and the graphics path are timed separately by
 * tools/device-bench.sh.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#if defined(_WIN32)
#include <windows.h>
static double now_ms(void)
{
    LARGE_INTEGER f, t;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&t);
    return (double)t.QuadPart * 1000.0 / (double)f.QuadPart;
}
#else
static double now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1.0e6;
}
#endif

/* 8 MiB — past this chip's L2 either way, so `mem` is a memory measurement. */
#define BUF_WORDS (2u * 1024u * 1024u)

/* volatile so the compiler cannot hoist the checksum out of the timed loop. */
static volatile unsigned long long sink;

static unsigned long long bench_int(unsigned iterations)
{
    unsigned long long a = 0x243f6a8885a308d3ULL, b = 0x13198a2e03707344ULL;
    for (unsigned i = 0; i < iterations; i++) {
        a = a * 6364136223846793005ULL + 1442695040888963407ULL;
        b ^= a >> 29;
        b = b * 0x9e3779b97f4a7c15ULL;
        a += b >> 17;
    }
    return a ^ b;
}

/* The same LCG shape as bench_int, in 32-bit arithmetic.
 *
 * **This section exists because `int` cannot be compared across bitnesses and
 * was being compared anyway.** Every operand in bench_int is 64-bit, so on i686
 * clang has to synthesize each 64x64 multiply from three 32-bit multiplies and
 * each 64-bit shift from a shld/shr pair: the i686 build runs several times the
 * guest instructions per iteration for identical work. The "x86-32 costs 2.28x"
 * headline is that lowering — 560.6/244.1 against *native ARM64* — and not a
 * WoW64 or FEX tax.
 *
 * The control was already sitting in the same baseline and nobody read it:
 * `x86_64 float 403.2` and `i686 float 403.2`, bit-identical, both scalar SSE2.
 * `branch` and `mem` are *faster* on i686 than on x86-64 and than on native
 * ARM64. Identical guest work costs the same through WoW64 as through ARM64EC.
 *
 * So this row is the honest x86-32 translation number: 32-bit operands, which
 * a 32-bit target lowers one-to-one. */
static unsigned long long bench_int32(unsigned iterations)
{
    unsigned a = 0x85a308d3u, b = 0x03707344u;
    for (unsigned i = 0; i < iterations; i++) {
        a = a * 1664525u + 1013904223u;
        b ^= a >> 13;
        b = b * 0x9e3779b9u;
        a += b >> 7;
    }
    return ((unsigned long long)a << 32) | b;
}

static unsigned long long bench_branch(const unsigned *data, unsigned n, unsigned rounds)
{
    unsigned long long acc = 0;
    for (unsigned r = 0; r < rounds; r++)
        for (unsigned i = 0; i < n; i++) {
            /* Data-dependent and unlearnable: the values are from an LCG. */
            if (data[i] & 1u) acc += data[i];
            else if (data[i] & 2u) acc ^= data[i];
            else acc -= data[i] >> 3;
        }
    return acc;
}

static unsigned long long bench_mem(unsigned *buf, unsigned n, unsigned rounds)
{
    unsigned long long acc = 0;
    /* 16 words = one 64-byte line at 4-byte words, so every read is a new line. */
    const unsigned stride = 16;
    for (unsigned r = 0; r < rounds; r++) {
        for (unsigned i = 0; i < n; i += stride) {
            acc += buf[i];
            buf[i] = (unsigned)(acc >> 7);
        }
    }
    return acc;
}

static unsigned long long bench_float(unsigned iterations)
{
    double x = 1.0000001, y = 0.9999999, z = 0.0;
    for (unsigned i = 0; i < iterations; i++) {
        z = z * 0.5 + x * y;
        x = x * 1.0000000001 + 1e-9;
        y = y * 0.9999999999 - 1e-9;
    }
    /* Bit pattern, not a rounded value: the checksum has to catch a difference
       in the last place, which is exactly where an FPU mapping bug would show. */
    unsigned long long bits;
    memcpy(&bits, &z, sizeof bits);
    return bits;
}

int main(int argc, char **argv)
{
    /* One scale knob so a slower device can still finish, and so the script can
       shorten a smoke run. Times are only comparable at the same scale, which
       is why it is printed on every line. */
    unsigned scale = (argc > 1) ? (unsigned)strtoul(argv[1], NULL, 10) : 1;
    if (scale == 0) scale = 1;

    const int bits = (int)(sizeof(void *) * 8);
    unsigned *buf = malloc((size_t)BUF_WORDS * sizeof *buf);
    if (!buf) {
        printf("CPUBENCH bits=%d result=FAIL msg=out of memory\n", bits);
        return 1;
    }

    unsigned seed = 0x1234567u;
    for (unsigned i = 0; i < BUF_WORDS; i++) {
        seed = seed * 1103515245u + 12345u;
        buf[i] = seed;
    }

    double t0, t1;
    unsigned long long c;

    /* Iteration counts are calibrated so every section lands in the 150-250 ms
       range on this phone. That is not cosmetic: the first run of this
       benchmark had `mem` at 3.9 ms and `branch` at 15.6 ms, and at those
       durations the x86 builds came out *faster than native* — 0.66x — which is
       not a result, it is the clock resolution and one scheduling hiccup. A
       section too short to measure produces a confident number and no
       information. */
    t0 = now_ms(); c = bench_int(40000000u * scale);              t1 = now_ms();
    sink ^= c;
    printf("CPUBENCH bits=%d scale=%u section=int ms=%.1f checksum=%llu\n",
           bits, scale, t1 - t0, c);

    /* Directly after int, so the two are adjacent in every result file and the
       comparison that matters is impossible to miss. */
    t0 = now_ms(); c = bench_int32(40000000u * scale);            t1 = now_ms();
    sink ^= c;
    printf("CPUBENCH bits=%d scale=%u section=int32 ms=%.1f checksum=%llu\n",
           bits, scale, t1 - t0, c);

    t0 = now_ms(); c = bench_branch(buf, 1u << 20, 120u * scale); t1 = now_ms();
    sink ^= c;
    printf("CPUBENCH bits=%d scale=%u section=branch ms=%.1f checksum=%llu\n",
           bits, scale, t1 - t0, c);

    /* After branch, which reads buf but does not write it, so both start from
       the same contents. mem mutates, so nothing may be timed after it. */
    t0 = now_ms(); c = bench_mem(buf, BUF_WORDS, 500u * scale);   t1 = now_ms();
    sink ^= c;
    printf("CPUBENCH bits=%d scale=%u section=mem ms=%.1f checksum=%llu\n",
           bits, scale, t1 - t0, c);

    t0 = now_ms(); c = bench_float(60000000u * scale);            t1 = now_ms();
    sink ^= c;
    printf("CPUBENCH bits=%d scale=%u section=float ms=%.1f checksum=%llu\n",
           bits, scale, t1 - t0, c);

    free(buf);
    printf("CPUBENCH bits=%d scale=%u result=OK\n", bits, scale);
    return 0;
}

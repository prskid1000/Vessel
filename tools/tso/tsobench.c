/*
 * Is FEX's fast TSO path actually fast on Oryon?
 *
 * FEX emits LDAPUR/STLUR (FEAT_LRCPC2) for x86 loads and stores when the host
 * reports the feature, because those are meant to be cheaper than a barrier.
 * Arm erratum 3877900 says otherwise on some cores:
 *
 *   LDAPUR, LDAPURB, LDAPURH instructions have stricter memory ordering than
 *   required ... execute with full Load-Acquire ordering instead of the relaxed
 *   ordering described in the pseudocode.
 *
 * FEX disables the path on eight Arm-designed cores for exactly that reason
 * (Source/Common/HostFeatures.cpp:457, mirroring LLVM PR #124274). The blocklist
 * is gated on `Implementer_ARM`, and Oryon is Qualcomm (0x51), so the path stays
 * on here — and nobody has measured whether it should.
 *
 * This is the measurement. It is a *relative* benchmark and only means anything
 * when the same binary is run twice, once with
 *
 *   FEX_HOSTFEATURES=disablelrcpc2
 *
 * and once without. If disabling the "fast" path makes it faster, LDAPUR is
 * over-ordered on this core and Vessel should be turning it off.
 *
 * Build x86-64 (the point is that FEX translates it) and ARM64 as the control:
 * the ARM64 build runs natively, so its time should not move at all between the
 * two runs. If it does, the measurement is noise and not a result.
 *
 * ---
 *
 * There are two timed phases, because there are two knobs and each is blind to
 * the other's workload.
 *
 *   ms=            aligned byte traffic. This is the LRCPC2 question, and the
 *                  number the 289.3/348.8 result in docs/ARCHITECTURE.md came
 *                  from — the field name is kept so that comparison still holds.
 *   ms_unaligned=  deliberately misaligned 64-bit traffic. This is the
 *                  FEX_HALFBARRIERTSOENABLED question.
 *
 * **The second phase exists because the first cannot answer the second.**
 * `HalfBarrierTSOEnabled` backpatches *unaligned* loads and stores to
 * half-barrier atomics, and every access in the aligned phase is a single byte
 * — which is naturally aligned by definition. Run against phase one alone the
 * knob is a no-op, and two identical numbers would read as "measured, makes no
 * difference" when the truth is "never exercised".
 */

#include <stdio.h>
#include <stdlib.h>
#include <windows.h>

/* 64 KB — comfortably inside L1/L2 on this core. The aim is to measure the
 * ordering instructions, not DRAM: with a working set that misses cache, memory
 * latency swamps the very thing being compared. */
#define BUF_BYTES (64u * 1024u)
#define ITERATIONS 4000u

/* One byte, so every 64-bit access in the second phase is misaligned and one in
 * two of them also straddles a 16-byte boundary — the case FEX's backpatch and
 * its split-lock handling both care about. Eight times the passes because each
 * step moves eight times the bytes, which keeps the two phases roughly equal in
 * duration and therefore equally readable. */
#define UNALIGNED_OFFSET 1u
#define UNALIGNED_ITERATIONS (ITERATIONS * 8u)

int main(int argc, char **argv)
{
    /* volatile so the compiler cannot hoist, vectorise or eliminate the traffic.
     * Every one of these accesses has to survive into the x86 instruction stream
     * for FEX to have something to translate. */
    volatile unsigned char *buf = malloc(BUF_BYTES);
    volatile unsigned long long *wide;
    LARGE_INTEGER freq, start, mid, end;
    unsigned long long sum = 0;
    unsigned int iter, i, wide_count;
    double ms, ms_unaligned;

    if (!buf) return 1;
    for (i = 0; i < BUF_BYTES; i++) buf[i] = (unsigned char)i;

    QueryPerformanceFrequency(&freq);
    QueryPerformanceCounter(&start);

    for (iter = 0; iter < ITERATIONS; iter++) {
        /* A load and a store per step, because TSO constrains both and FEX
         * emits a different instruction for each (LDAPUR vs STLUR). Stepping by
         * 1 keeps it dense; the dependency through `sum` stops the loop being
         * reordered into something that no longer resembles the original. */
        for (i = 0; i < BUF_BYTES; i++) {
            sum += buf[i];
            buf[i] = (unsigned char)(sum >> 3);
        }
    }

    QueryPerformanceCounter(&mid);

    /* Phase two. The cast is deliberately to an odd address: both x86-64 and
     * AArch64 permit unaligned ordinary loads and stores, and an unaligned one
     * is the only kind FEX backpatches. */
    wide = (volatile unsigned long long *)(buf + UNALIGNED_OFFSET);
    wide_count = (BUF_BYTES - UNALIGNED_OFFSET) / (unsigned int)sizeof(unsigned long long);

    for (iter = 0; iter < UNALIGNED_ITERATIONS; iter++) {
        for (i = 0; i < wide_count; i++) {
            sum += wide[i];
            wide[i] = sum >> 3;
        }
    }

    QueryPerformanceCounter(&end);
    ms = (double)(mid.QuadPart - start.QuadPart) * 1000.0 / (double)freq.QuadPart;
    ms_unaligned = (double)(end.QuadPart - mid.QuadPart) * 1000.0 / (double)freq.QuadPart;

    /* The checksum is printed so a run that was optimised away, or that faulted
     * partway, cannot be mistaken for a fast one. It covers both phases, so a
     * silently skipped second phase shows up here too. */
    printf("TSOBENCH bits=%d ms=%.1f ms_unaligned=%.1f checksum=%llu\n",
           (int)(sizeof(void *) * 8), ms, ms_unaligned, sum);
    fflush(stdout);
    free((void *)buf);
    (void)argc; (void)argv;
    return 0;
}

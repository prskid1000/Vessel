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
 */

#include <stdio.h>
#include <stdlib.h>
#include <windows.h>

/* 64 KB — comfortably inside L1/L2 on this core. The aim is to measure the
 * ordering instructions, not DRAM: with a working set that misses cache, memory
 * latency swamps the very thing being compared. */
#define BUF_BYTES (64u * 1024u)
#define ITERATIONS 4000u

int main(int argc, char **argv)
{
    /* volatile so the compiler cannot hoist, vectorise or eliminate the traffic.
     * Every one of these accesses has to survive into the x86 instruction stream
     * for FEX to have something to translate. */
    volatile unsigned char *buf = malloc(BUF_BYTES);
    LARGE_INTEGER freq, start, end;
    unsigned long long sum = 0;
    unsigned int iter, i;
    double ms;

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

    QueryPerformanceCounter(&end);
    ms = (double)(end.QuadPart - start.QuadPart) * 1000.0 / (double)freq.QuadPart;

    /* The checksum is printed so a run that was optimised away, or that faulted
     * partway, cannot be mistaken for a fast one. */
    printf("TSOBENCH bits=%d ms=%.1f checksum=%llu\n",
           (int)(sizeof(void *) * 8), ms, sum);
    fflush(stdout);
    free((void *)buf);
    (void)argc; (void)argv;
    return 0;
}

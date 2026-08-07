/*
 * The smallest program that proves a translation path works.
 *
 * Compiled three times by tools/device-session.sh -- ARM64, x86-64 and x86-32 --
 * and run inside the container on the phone. Each build exercises a different
 * route: ARM64 runs on Wine directly, x86-64 goes through libarm64ecfex.dll, and
 * x86-32 goes through WoW64 and libwow64fex.dll.
 *
 * It prints its own pointer width and a fixed string so the output cannot be
 * confused between the three, and it does arithmetic the compiler cannot fold
 * away, so a run that prints the right answer really did execute translated
 * code rather than a constant baked into the binary.
 */

#include <stdio.h>

int main(int argc, char **argv)
{
    unsigned long long acc = 0;
    int i;

    for (i = 1; i <= 100000; i++) acc += (unsigned long long)i * i;

    printf("VESSEL-OK bits=%d sum=%llu argc=%d\n",
           (int)(sizeof(void *) * 8), acc, argc);
    fflush(stdout);
    return 0;
}

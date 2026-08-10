/* Does AAudio blocking-write work from a forked app-uid process on this device?
 *
 * Vessel's guest audio is silent in a way no amount of reading the driver has
 * explained. Measured 2026-08-10: `wineoss.drv` opens a stream, writes a full
 * buffer, the stream reports AAUDIO_STREAM_STATE_STARTED, and
 * AAudioStream_getFramesRead() never advances — AudioFlinger's own dump shows
 * the track with `Active: no` and a server position of zero. Requesting audio
 * focus from the app process changed nothing.
 *
 * That leaves two possibilities with opposite fixes, and reading cannot
 * separate them:
 *
 *   1. Wine's driver is doing something wrong that this probe will not do.
 *   2. A stream opened this way, from a bare process forked by the app rather
 *      than from the app's own Android process, never plays on this device.
 *
 * So this program does exactly what the driver does — same sharing mode, same
 * performance mode, same blocking write with a zero timeout — and nothing else.
 * If it is audible, the driver is at fault. If it is silent with the same
 * frozen counters, the driver is exonerated and the problem is where the stream
 * is opened from.
 *
 * Deliberately does NOT use a data callback. The callback path is the one that
 * usually works, so using it here would prove nothing about the path Wine takes.
 *
 *   ./tools/audio/run-tone.sh
 */

#include <aaudio/AAudio.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define RATE 48000
#define CHANNELS 2
#define SECONDS 3
#define CHUNK 240 /* 5 ms, comfortably under one burst */

static void report(const char *what, AAudioStream *s)
{
    printf("VESSEL-TONE %-14s state=%-28s written=%lld read=%lld xrun=%d\n",
           what,
           AAudio_convertStreamStateToText(AAudioStream_getState(s)),
           (long long)AAudioStream_getFramesWritten(s),
           (long long)AAudioStream_getFramesRead(s),
           AAudioStream_getXRunCount(s));
    fflush(stdout);
}

static int opt_setbuf;      /* mimic the driver's setBufferSizeInFrames(capacity) */
static int opt_predecessor;  /* mimic oss_test_connect opening a stream first */
static int opt_rate = RATE;

int main(int argc, char **argv)
{
    AAudioStream *ghost = NULL;
    int a;

    for (a = 1; a < argc; a++) {
        if (!strcmp(argv[a], "--setbuf")) opt_setbuf = 1;
        else if (!strcmp(argv[a], "--predecessor")) opt_predecessor = 1;
        else if (!strncmp(argv[a], "--rate=", 7)) opt_rate = atoi(argv[a] + 7);
    }

    AAudioStreamBuilder *builder = NULL;
    AAudioStream *stream = NULL;
    aaudio_result_t res;
    int16_t buf[CHUNK * CHANNELS];
    double phase = 0.0;
    int chunks, i;

    if (opt_predecessor) {
        /* oss_test_connect opens a stream purely to enumerate. If it is not
         * closed, or if holding it changes what the next open returns, that is
         * a difference between this probe and the driver worth ruling out. */
        AAudioStreamBuilder *gb = NULL;
        if (AAudio_createStreamBuilder(&gb) == AAUDIO_OK) {
            AAudioStreamBuilder_setDirection(gb, AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setPerformanceMode(gb, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            AAudioStreamBuilder_openStream(gb, &ghost);
            AAudioStreamBuilder_delete(gb);
            printf("VESSEL-TONE predecessor stream open=%d\n", ghost != NULL);
        }
    }

    res = AAudio_createStreamBuilder(&builder);
    if (res != AAUDIO_OK) {
        printf("VESSEL-TONE result=FAIL stage=builder %s\n", AAudio_convertResultToText(res));
        return 1;
    }

    /* Every one of these matches patches/wine/0008 exactly. */
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(builder, opt_rate);
    AAudioStreamBuilder_setChannelCount(builder, CHANNELS);

    res = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (res != AAUDIO_OK) {
        printf("VESSEL-TONE result=FAIL stage=open %s\n", AAudio_convertResultToText(res));
        return 1;
    }

    printf("VESSEL-TONE opened rate=%d channels=%d format=%d burst=%d capacity=%d perf=%d sharing=%d\n",
           AAudioStream_getSampleRate(stream), AAudioStream_getChannelCount(stream),
           AAudioStream_getFormat(stream), AAudioStream_getFramesPerBurst(stream),
           AAudioStream_getBufferCapacityInFrames(stream),
           AAudioStream_getPerformanceMode(stream), AAudioStream_getSharingMode(stream));
    if (opt_setbuf) {
        int32_t cap = AAudioStream_getBufferCapacityInFrames(stream);
        int32_t got = AAudioStream_setBufferSizeInFrames(stream, cap);
        printf("VESSEL-TONE setBufferSizeInFrames(%d) -> %d\n", cap, got);
    }
    report("after-open", stream);

    res = AAudioStream_requestStart(stream);
    printf("VESSEL-TONE requestStart %s\n", AAudio_convertResultToText(res));
    report("after-start", stream);

    /* The driver does not wait here today; this probe does, so that a failure
     * cannot be blamed on the missing wait that has already been fixed. */
    {
        aaudio_stream_state_t state = AAUDIO_STREAM_STATE_STARTING;
        AAudioStream_waitForStateChange(stream, AAUDIO_STREAM_STATE_STARTING, &state,
                                        500 * 1000000LL);
        printf("VESSEL-TONE settled state=%s\n", AAudio_convertStreamStateToText(state));
    }

    chunks = (RATE * SECONDS) / CHUNK;
    for (i = 0; i < chunks; i++) {
        int f;
        for (f = 0; f < CHUNK; f++) {
            /* 440 Hz, half scale. Wine outputs at full scale and Android owns
             * the volume, so this does the same and does not attenuate. */
            int16_t v = (int16_t)(16000.0 * sin(phase));
            phase += 2.0 * M_PI * 440.0 / RATE;
            if (phase > 2.0 * M_PI) phase -= 2.0 * M_PI;
            buf[f * CHANNELS] = v;
            buf[f * CHANNELS + 1] = v;
        }

        /* Zero timeout, exactly as the driver writes. */
        res = AAudioStream_write(stream, buf, CHUNK, 0);
        if (res < 0) {
            printf("VESSEL-TONE result=FAIL stage=write %s\n", AAudio_convertResultToText(res));
            AAudioStream_close(stream);
            return 1;
        }
        if ((i % 100) == 0)
            report("writing", stream);
        /* A zero-timeout write returns short when the buffer is full; without a
         * pause this spins and proves nothing about drain rate. */
        usleep(4000);
    }

    report("after-writes", stream);
    AAudioStream_requestStop(stream);
    report("after-stop", stream);

    {
        /* Read before the close. Reading a closed stream is a use-after-free,
         * and doing it aborted the first run with "Pure virtual function
         * called!" — harmless to the measurement, fatal to the exit code. */
        int64_t drained = AAudioStream_getFramesRead(stream);
        AAudioStream_close(stream);
        if (ghost) AAudioStream_close(ghost);
        printf("VESSEL-TONE result=%s drained=%lld\n",
               drained > 0 ? "PASS" : "FAIL never-drained", (long long)drained);
    }
    return 0;
}

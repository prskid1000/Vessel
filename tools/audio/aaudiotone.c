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

/* --setbuf mimics the driver's setBufferSizeInFrames(capacity); --setbuf=N asks
 * for a specific size, which is how the size is shown to be the thing that
 * decides whether a fixed queue ever starts playing. -1 means "capacity". */
static int opt_setbuf;
static int opt_predecessor;  /* mimic oss_test_connect opening a stream first */
static int opt_rate = RATE;

/* --stall=N: write N frames and then stop writing, polling the counters.
 *
 * The plain probe writes continuously for three seconds, and that is precisely
 * what the driver does NOT do. oss_write_data refuses to queue more than three
 * periods — 1440 frames at this rate — and then returns without a trace until
 * AAudio reports some of them consumed. So "keep writing until it plays" is a
 * behaviour the probe had and the driver never has, and every earlier PASS here
 * was measuring the wrong program.
 *
 * With this switch the probe stops at N frames and just watches, which is the
 * driver's actual shape: queue a fixed amount, then wait for the device to take
 * it. If framesRead never leaves zero, the device is waiting for more than N
 * frames before it will start, and the driver's ceiling is the bug.
 */
static int opt_stall;

int main(int argc, char **argv)
{
    AAudioStream *ghost = NULL;
    int a;

    for (a = 1; a < argc; a++) {
        if (!strcmp(argv[a], "--setbuf")) opt_setbuf = -1;
        else if (!strncmp(argv[a], "--setbuf=", 9)) opt_setbuf = atoi(argv[a] + 9);
        else if (!strcmp(argv[a], "--predecessor")) opt_predecessor = 1;
        else if (!strncmp(argv[a], "--rate=", 7)) opt_rate = atoi(argv[a] + 7);
        /* --stall=buf rather than a number, because the device renegotiates its
         * burst and capacity between runs — 868/1736 on one open and 1736/3472
         * on the next — so a hard-coded frame count silently stops being the
         * comparison you meant to make. */
        else if (!strcmp(argv[a], "--stall=buf")) opt_stall = -1;
        else if (!strncmp(argv[a], "--stall=", 8)) opt_stall = atoi(argv[a] + 8);
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
        int32_t want = opt_setbuf < 0
                     ? AAudioStream_getBufferCapacityInFrames(stream)
                     : opt_setbuf;
        int32_t got = AAudioStream_setBufferSizeInFrames(stream, want);
        printf("VESSEL-TONE setBufferSizeInFrames(%d) -> %d\n", want, got);
    }
    printf("VESSEL-TONE buffer size in effect %d\n",
           AAudioStream_getBufferSizeInFrames(stream));
    if (opt_stall < 0)
        opt_stall = AAudioStream_getBufferSizeInFrames(stream) + CHUNK;
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

    chunks = opt_stall ? (opt_stall + CHUNK - 1) / CHUNK : (RATE * SECONDS) / CHUNK;
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

    if (opt_stall) {
        /* Two seconds of watching a queue nobody asked us to top up. The driver
         * spends its whole run here, once per period, deciding there is nothing
         * to do. Twenty samples at 100 ms so a late start is still visible as a
         * start rather than as a flat line. */
        for (i = 0; i < 20; i++) {
            usleep(100000);
            printf("VESSEL-TONE stalled t=%4dms written=%lld read=%lld state=%s\n",
                   (i + 1) * 100,
                   (long long)AAudioStream_getFramesWritten(stream),
                   (long long)AAudioStream_getFramesRead(stream),
                   AAudio_convertStreamStateToText(AAudioStream_getState(stream)));
            fflush(stdout);
        }
    }

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

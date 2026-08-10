/* Play a tone from inside the container, with no UI and no game.
 *
 * The only way to hear Vessel's guest audio was to launch a program and click
 * something: `winecfg` -> Audio -> Test Sound, driven by five synthetic
 * Ctrl+Tabs and a Space, taking about a minute a run, or Metro, taking three.
 * This is the same question asked in two seconds.
 *
 * It uses `waveOut` rather than XAudio2 or WASAPI directly because waveOut is
 * the shortest path that still goes through the whole stack under test:
 * winmm -> mmdevapi -> wineoss.drv -> AAudio. A failure here is a failure of
 * the thing being debugged and not of a layer that happens to sit above it.
 *
 * It prints the play position as it goes, so a silent run still says whether
 * the device consumed the samples. That distinction is the entire point: a
 * position that advances with no sound is a routing or volume problem, and a
 * position stuck at zero is the driver never being drained — which is the bug
 * this file was written to chase.
 *
 *   ./tools/audio/run-guest-tone.sh
 */

#include <windows.h>
#include <mmsystem.h>
#include <stdio.h>
#include <math.h>

#define RATE      48000
#define CHANNELS  2
#define SECONDS   3
#define BLOCKS    6                      /* 0.5 s each, queued as a ring */
#define FRAMES    (RATE * SECONDS / BLOCKS)

static short buffers[BLOCKS][FRAMES * CHANNELS];
static WAVEHDR headers[BLOCKS];

static void position(HWAVEOUT out, const char *what)
{
    MMTIME t;
    MMRESULT r;

    memset(&t, 0, sizeof(t));
    t.wType = TIME_SAMPLES;
    r = waveOutGetPosition(out, &t, sizeof(t));
    printf("VESSEL-GUESTTONE %-12s mmr=%u type=%u samples=%lu\n",
           what, (unsigned)r, (unsigned)t.wType, (unsigned long)t.u.sample);
    fflush(stdout);
}

int main(void)
{
    WAVEFORMATEX fmt;
    HWAVEOUT out = NULL;
    MMRESULT r;
    double phase = 0.0;
    int b, f, i;

    printf("VESSEL-GUESTTONE devices=%u\n", (unsigned)waveOutGetNumDevs());
    fflush(stdout);

    memset(&fmt, 0, sizeof(fmt));
    fmt.wFormatTag      = WAVE_FORMAT_PCM;
    fmt.nChannels       = CHANNELS;
    fmt.nSamplesPerSec  = RATE;
    fmt.wBitsPerSample  = 16;
    fmt.nBlockAlign     = (WORD)(fmt.nChannels * fmt.wBitsPerSample / 8);
    fmt.nAvgBytesPerSec = fmt.nSamplesPerSec * fmt.nBlockAlign;

    r = waveOutOpen(&out, WAVE_MAPPER, &fmt, 0, 0, CALLBACK_NULL);
    if (r != MMSYSERR_NOERROR) {
        printf("VESSEL-GUESTTONE result=FAIL stage=open mmr=%u\n", (unsigned)r);
        return 1;
    }
    printf("VESSEL-GUESTTONE opened\n");
    fflush(stdout);

    for (b = 0; b < BLOCKS; b++) {
        for (f = 0; f < FRAMES; f++) {
            /* Full scale minus a little headroom. Wine outputs at the level the
             * program asks for and Android owns the volume, so this does not
             * attenuate to be polite. */
            short v = (short)(20000.0 * sin(phase));
            phase += 2.0 * 3.14159265358979 * 440.0 / RATE;
            if (phase > 2.0 * 3.14159265358979) phase -= 2.0 * 3.14159265358979;
            buffers[b][f * CHANNELS]     = v;
            buffers[b][f * CHANNELS + 1] = v;
        }
        memset(&headers[b], 0, sizeof(headers[b]));
        headers[b].lpData         = (LPSTR)buffers[b];
        headers[b].dwBufferLength = sizeof(buffers[b]);
        r = waveOutPrepareHeader(out, &headers[b], sizeof(headers[b]));
        if (r != MMSYSERR_NOERROR) {
            printf("VESSEL-GUESTTONE result=FAIL stage=prepare mmr=%u\n", (unsigned)r);
            return 1;
        }
    }

    position(out, "before");

    for (b = 0; b < BLOCKS; b++) {
        r = waveOutWrite(out, &headers[b], sizeof(headers[b]));
        if (r != MMSYSERR_NOERROR) {
            printf("VESSEL-GUESTTONE result=FAIL stage=write mmr=%u block=%d\n",
                   (unsigned)r, b);
            return 1;
        }
    }
    printf("VESSEL-GUESTTONE queued %d blocks of %d frames\n", BLOCKS, FRAMES);
    fflush(stdout);

    /* Sampled rather than waited on: a WAVEHDR that never comes back is the
     * failure being investigated, so blocking on it would hang instead of
     * reporting. */
    /* Fifteen seconds rather than four. Not because the tone is that long, but
     * because the interesting question is what the *driver's* timer thread is
     * doing, and answering it means catching the process alive from outside
     * with `ps -T`. A four-second run was gone before a second adb call could
     * land on it twice running. */
    for (i = 0; i < 30; i++) {
        Sleep(500);
        if ((i % 4) == 0)
            position(out, "playing");
    }

    {
        int done = 0;
        for (b = 0; b < BLOCKS; b++)
            if (headers[b].dwFlags & WHDR_DONE) done++;
        printf("VESSEL-GUESTTONE blocks_done=%d of %d\n", done, BLOCKS);
    }

    waveOutReset(out);
    for (b = 0; b < BLOCKS; b++)
        waveOutUnprepareHeader(out, &headers[b], sizeof(headers[b]));
    waveOutClose(out);

    {
        MMTIME t;
        memset(&t, 0, sizeof(t));
        t.wType = TIME_SAMPLES;
        printf("VESSEL-GUESTTONE result=see-positions-above\n");
    }
    return 0;
}

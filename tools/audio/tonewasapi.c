/* Play a tone the way a game plays one, from inside the container.
 *
 * tonewin.c proved the winmm path and is audible on the device. The game is
 * still silent, and the two do not take the same road. `waveOut` is a push
 * interface Wine services from its own timer; a game reaches audio through
 * XAudio2, which on Wine is FAudio, which is an IAudioClient in shared mode,
 * driven by an event, writing 32-bit float. Between those two descriptions sit
 * a different format, a different buffer discipline and a different thread
 * doing the writing, and a bug can live in any of them while `waveOut` plays
 * perfectly.
 *
 * So this is the second half of the same question tonewin.c asks. It is
 * deliberately the shape FAudio uses rather than the shape that is easiest to
 * write:
 *
 *   - shared mode, because that is what a game gets;
 *   - AUDCLNT_STREAMFLAGS_EVENTCALLBACK, so the render thread is woken by the
 *     driver's timer rather than polling, which is the path through
 *     oss_timer_loop's NtSetEvent that nothing has yet exercised;
 *   - 32-bit float by default, because FAudio mixes in float and asks for it.
 *
 *   --mix     use GetMixFormat unchanged instead of asking for float
 *   --timer   poll GetCurrentPadding instead of waiting on the event
 *
 * Those two switches exist so that a failure can be attributed. If --float
 * fails and --mix plays, the format conversion is at fault; if the event path
 * fails and --timer plays, the driver is not signalling. Printing the position
 * as it goes keeps tonewin.c's distinction intact: a position that advances
 * with no sound is routing or volume, a position stuck at zero is the device
 * never draining.
 *
 *   ./tools/audio/run-guest-tone.sh --wasapi
 */

#define COBJMACROS
#define INITGUID

#include <windows.h>
#include <initguid.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <stdio.h>
#include <math.h>

/* Spelled out rather than taken from ksmedia.h: INITGUID only emits the GUIDs
 * a header actually declares with DEFINE_GUID, and these two are declared
 * extern by mmreg.h without any object in llvm-mingw's import libraries to
 * satisfy them, so the link fails on the symbol rather than the value. Local
 * names, so nothing can collide with the extern declarations still in scope. */
static const GUID SUBTYPE_PCM =
    {0x00000001,0x0000,0x0010,{0x80,0x00,0x00,0xaa,0x00,0x38,0x9b,0x71}};
static const GUID SUBTYPE_FLOAT =
    {0x00000003,0x0000,0x0010,{0x80,0x00,0x00,0xaa,0x00,0x38,0x9b,0x71}};

#define SECONDS   3
#define TONE_HZ   440.0
/* 200 ms. Long enough that a missed wakeup is a gap rather than a stutter, and
 * short enough that the run is over in three seconds. */
#define BUFFER_HNS 2000000

static const char *fmt_name(const WAVEFORMATEX *f)
{
    const WAVEFORMATEXTENSIBLE *fx = (const WAVEFORMATEXTENSIBLE *)f;

    if (f->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) return "float";
    if (f->wFormatTag == WAVE_FORMAT_PCM) return "pcm";
    if (f->wFormatTag == WAVE_FORMAT_EXTENSIBLE) {
        if (IsEqualGUID(&fx->SubFormat, &SUBTYPE_FLOAT))
            return "extensible-float";
        if (IsEqualGUID(&fx->SubFormat, &SUBTYPE_PCM))
            return "extensible-pcm";
        return "extensible-other";
    }
    return "other";
}

static void dump_fmt(const char *what, const WAVEFORMATEX *f)
{
    printf("VESSEL-WASAPI %-10s %s ch=%u rate=%lu bits=%u align=%u\n",
           what, fmt_name(f), (unsigned)f->nChannels,
           (unsigned long)f->nSamplesPerSec, (unsigned)f->wBitsPerSample,
           (unsigned)f->nBlockAlign);
    fflush(stdout);
}

/* Full scale minus a little headroom, in whichever format the client got.
 * Android owns the volume, so this does not attenuate to be polite. */
static void fill(BYTE *dst, UINT32 frames, const WAVEFORMATEX *f, double *phase)
{
    const WAVEFORMATEXTENSIBLE *fx = (const WAVEFORMATEXTENSIBLE *)f;
    int is_float = f->wFormatTag == WAVE_FORMAT_IEEE_FLOAT ||
                   (f->wFormatTag == WAVE_FORMAT_EXTENSIBLE &&
                    IsEqualGUID(&fx->SubFormat, &SUBTYPE_FLOAT));
    UINT32 i;
    WORD c;

    for (i = 0; i < frames; i++) {
        double s = sin(*phase);
        *phase += 2.0 * 3.14159265358979 * TONE_HZ / f->nSamplesPerSec;
        if (*phase > 2.0 * 3.14159265358979) *phase -= 2.0 * 3.14159265358979;

        for (c = 0; c < f->nChannels; c++) {
            if (is_float)
                ((float *)dst)[i * f->nChannels + c] = (float)(0.61 * s);
            else
                ((short *)dst)[i * f->nChannels + c] = (short)(20000.0 * s);
        }
    }
}

int main(int argc, char **argv)
{
    IMMDeviceEnumerator *enumerator = NULL;
    IMMDevice *device = NULL;
    IAudioClient *client = NULL;
    IAudioRenderClient *render = NULL;
    IAudioClock *clock = NULL;
    WAVEFORMATEX *mix = NULL, *want = NULL, *closest = NULL;
    WAVEFORMATEXTENSIBLE floatfmt;
    HANDLE event = NULL;
    HRESULT hr;
    UINT32 bufsize = 0, padding, avail;
    UINT64 freq = 0, pos = 0;
    BYTE *data;
    double phase = 0.0;
    DWORD flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK;
    int use_mix = 0, use_timer = 0, i, ticks, wakeups = 0, starved = 0;

    for (i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--mix")) use_mix = 1;
        else if (!strcmp(argv[i], "--timer")) use_timer = 1;
    }
    if (use_timer) flags = 0;

    hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hr)) { printf("VESSEL-WASAPI result=FAIL stage=coinit hr=%08lx\n", (unsigned long)hr); return 1; }

    hr = CoCreateInstance(&CLSID_MMDeviceEnumerator, NULL, CLSCTX_ALL,
                          &IID_IMMDeviceEnumerator, (void **)&enumerator);
    if (FAILED(hr)) { printf("VESSEL-WASAPI result=FAIL stage=enumerator hr=%08lx\n", (unsigned long)hr); return 1; }

    hr = IMMDeviceEnumerator_GetDefaultAudioEndpoint(enumerator, eRender, eConsole, &device);
    if (FAILED(hr)) { printf("VESSEL-WASAPI result=FAIL stage=endpoint hr=%08lx\n", (unsigned long)hr); return 1; }

    hr = IMMDevice_Activate(device, &IID_IAudioClient, CLSCTX_ALL, NULL, (void **)&client);
    if (FAILED(hr)) { printf("VESSEL-WASAPI result=FAIL stage=activate hr=%08lx\n", (unsigned long)hr); return 1; }

    hr = IAudioClient_GetMixFormat(client, &mix);
    if (FAILED(hr)) { printf("VESSEL-WASAPI result=FAIL stage=getmixformat hr=%08lx\n", (unsigned long)hr); return 1; }
    dump_fmt("mix", mix);

    if (use_mix) {
        want = mix;
    } else {
        /* What FAudio asks for: 32-bit float at the device's rate and channel
         * count. Built as EXTENSIBLE because that is what a real client sends. */
        memset(&floatfmt, 0, sizeof(floatfmt));
        floatfmt.Format.wFormatTag      = WAVE_FORMAT_EXTENSIBLE;
        floatfmt.Format.nChannels       = mix->nChannels;
        floatfmt.Format.nSamplesPerSec  = mix->nSamplesPerSec;
        floatfmt.Format.wBitsPerSample  = 32;
        floatfmt.Format.nBlockAlign     = (WORD)(floatfmt.Format.nChannels * 4);
        floatfmt.Format.nAvgBytesPerSec = floatfmt.Format.nSamplesPerSec * floatfmt.Format.nBlockAlign;
        floatfmt.Format.cbSize          = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
        floatfmt.Samples.wValidBitsPerSample = 32;
        floatfmt.dwChannelMask          = mix->nChannels == 2 ? 3 : 0;
        floatfmt.SubFormat              = SUBTYPE_FLOAT;
        want = &floatfmt.Format;
    }
    dump_fmt("want", want);

    hr = IAudioClient_IsFormatSupported(client, AUDCLNT_SHAREMODE_SHARED, want, &closest);
    printf("VESSEL-WASAPI IsFormatSupported hr=%08lx closest=%s\n",
           (unsigned long)hr, closest ? "yes" : "none");
    if (closest) dump_fmt("closest", closest);
    fflush(stdout);

    hr = IAudioClient_Initialize(client, AUDCLNT_SHAREMODE_SHARED, flags,
                                 BUFFER_HNS, 0, want, NULL);
    printf("VESSEL-WASAPI Initialize hr=%08lx flags=%08lx\n",
           (unsigned long)hr, (unsigned long)flags);
    fflush(stdout);
    if (FAILED(hr)) { printf("VESSEL-WASAPI result=FAIL stage=initialize hr=%08lx\n", (unsigned long)hr); return 1; }

    if (!use_timer) {
        event = CreateEventW(NULL, FALSE, FALSE, NULL);
        hr = IAudioClient_SetEventHandle(client, event);
        if (FAILED(hr)) { printf("VESSEL-WASAPI result=FAIL stage=setevent hr=%08lx\n", (unsigned long)hr); return 1; }
    }

    hr = IAudioClient_GetBufferSize(client, &bufsize);
    printf("VESSEL-WASAPI buffer frames=%u\n", (unsigned)bufsize);
    fflush(stdout);

    hr = IAudioClient_GetService(client, &IID_IAudioRenderClient, (void **)&render);
    if (FAILED(hr)) { printf("VESSEL-WASAPI result=FAIL stage=renderclient hr=%08lx\n", (unsigned long)hr); return 1; }

    if (SUCCEEDED(IAudioClient_GetService(client, &IID_IAudioClock, (void **)&clock)))
        IAudioClock_GetFrequency(clock, &freq);

    /* Pre-roll the whole buffer, exactly as a game does before Start(). */
    hr = IAudioRenderClient_GetBuffer(render, bufsize, &data);
    if (FAILED(hr)) { printf("VESSEL-WASAPI result=FAIL stage=prebuffer hr=%08lx\n", (unsigned long)hr); return 1; }
    fill(data, bufsize, want, &phase);
    IAudioRenderClient_ReleaseBuffer(render, bufsize, 0);

    hr = IAudioClient_Start(client);
    printf("VESSEL-WASAPI Start hr=%08lx\n", (unsigned long)hr);
    fflush(stdout);
    if (FAILED(hr)) { printf("VESSEL-WASAPI result=FAIL stage=start hr=%08lx\n", (unsigned long)hr); return 1; }

    ticks = (SECONDS * 1000) / 50;
    for (i = 0; i < ticks; i++) {
        if (use_timer) {
            Sleep(50);
        } else if (WaitForSingleObject(event, 200) == WAIT_OBJECT_0) {
            wakeups++;
        } else {
            /* The driver never signalled. Counted rather than fatal, because
             * "the event path is dead but the audio plays anyway" is a real
             * outcome and worth seeing. */
            starved++;
        }

        if (FAILED(IAudioClient_GetCurrentPadding(client, &padding))) break;
        avail = bufsize - padding;
        if (avail > 0 && SUCCEEDED(IAudioRenderClient_GetBuffer(render, avail, &data))) {
            fill(data, avail, want, &phase);
            IAudioRenderClient_ReleaseBuffer(render, avail, 0);
        }

        if ((i % 10) == 0) {
            pos = 0;
            if (clock) IAudioClock_GetPosition(clock, &pos, NULL);
            printf("VESSEL-WASAPI playing padding=%u pos=%llu freq=%llu wakeups=%d starved=%d\n",
                   (unsigned)padding, (unsigned long long)pos,
                   (unsigned long long)freq, wakeups, starved);
            fflush(stdout);
        }
    }

    pos = 0;
    if (clock) IAudioClock_GetPosition(clock, &pos, NULL);
    IAudioClient_Stop(client);

    printf("VESSEL-WASAPI final pos=%llu freq=%llu wakeups=%d starved=%d\n",
           (unsigned long long)pos, (unsigned long long)freq, wakeups, starved);
    printf("VESSEL-WASAPI result=%s\n", pos > 0 ? "advanced" : "FAIL never-advanced");
    fflush(stdout);
    return 0;
}

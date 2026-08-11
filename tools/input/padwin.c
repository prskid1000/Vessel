/*
 * padwin — does a gamepad exist inside the container?
 *
 * The question this answers is not "is a controller paired to the phone". It is
 * the one a Windows game actually asks, three different ways, and the three can
 * disagree:
 *
 *   XInput      XInputGetCapabilities / XInputGetState on the four slots.
 *               What every modern game uses, and the only one that reports a
 *               pad as a *pad* rather than as an axis collection.
 *   DirectInput IDirectInput8::EnumDevices over DI8DEVCLASS_GAMECTRL. What
 *               older titles use, and what a game that predates XInput has.
 *   winmm       joyGetNumDevs / joyGetPosEx. The oldest of the three, and the
 *               cheapest tell that the HID stack has enumerated anything at all.
 *
 * All three are reached through LoadLibrary rather than an import table, so a
 * DLL that is missing from the prefix prints a line instead of refusing to
 * start the program — which is the failure this is most likely to be run into.
 *
 * Output is machine-greppable and prefixed VESSEL-GUESTPAD, the same shape
 * tools/audio/tonewin.c uses, so run-guest-pad.sh can grep for it.
 *
 * Built and run by tools/input/run-guest-pad.sh. It is deliberately a console
 * program with no window: it joins a live session and must not put anything on
 * the desktop it is measuring.
 */

#include <windows.h>
#include <stdio.h>

#define OUT(...) do { printf("VESSEL-GUESTPAD " __VA_ARGS__); fflush(stdout); } while (0)

/* ---- XInput, declared here rather than included ---------------------------
 *
 * The header that ships with a toolchain pins one version of the API, and the
 * whole point of this probe is to try several. The two structs have been
 * stable since XInput 1.1 and are ABI, not a header detail.
 */

typedef struct {
    WORD  wButtons;
    BYTE  bLeftTrigger;
    BYTE  bRightTrigger;
    SHORT sThumbLX;
    SHORT sThumbLY;
    SHORT sThumbRX;
    SHORT sThumbRY;
} PAD_GAMEPAD;

typedef struct {
    DWORD       dwPacketNumber;
    PAD_GAMEPAD Gamepad;
} PAD_STATE;

typedef struct {
    BYTE        Type;
    BYTE        SubType;
    WORD        Flags;
    PAD_GAMEPAD Gamepad;
    struct { WORD wLeftMotorSpeed; WORD wRightMotorSpeed; } Vibration;
} PAD_CAPS;

typedef DWORD (WINAPI *pfnGetState)(DWORD, PAD_STATE *);
typedef DWORD (WINAPI *pfnGetCaps)(DWORD, DWORD, PAD_CAPS *);

/* The five names Wine builds, newest first. A game links whichever its SDK
 * shipped with, so a bridge that only replaced one of them would work for some
 * titles and not others — which is worth knowing before writing one. */
static const char *XINPUT_DLLS[] = {
    "xinput1_4.dll", "xinput1_3.dll", "xinput9_1_0.dll",
    "xinput1_2.dll", "xinput1_1.dll",
};

static int probe_xinput(void)
{
    int found = 0;
    for (size_t i = 0; i < sizeof(XINPUT_DLLS) / sizeof(*XINPUT_DLLS); i++) {
        const char *name = XINPUT_DLLS[i];
        HMODULE mod = LoadLibraryA(name);
        if (!mod) {
            OUT("xinput dll=%s load=FAILED err=%lu\n", name, GetLastError());
            continue;
        }
        pfnGetCaps caps = (pfnGetCaps)(void *)GetProcAddress(mod, "XInputGetCapabilities");
        pfnGetState state = (pfnGetState)(void *)GetProcAddress(mod, "XInputGetState");
        OUT("xinput dll=%s load=ok caps=%s state=%s\n",
            name, caps ? "yes" : "MISSING", state ? "yes" : "MISSING");
        if (!caps || !state) continue;

        for (DWORD slot = 0; slot < 4; slot++) {
            PAD_CAPS c;
            ZeroMemory(&c, sizeof(c));
            DWORD rc = caps(slot, 0 /* XINPUT_FLAG_ALL */, &c);
            if (rc != ERROR_SUCCESS) {
                /* 1167 is ERROR_DEVICE_NOT_CONNECTED, which is the expected
                 * answer on a build with no bus driver backend. Printed rather
                 * than skipped, because "nothing at all" and "connected but
                 * silent" are different bugs. */
                OUT("xinput dll=%s slot=%lu caps=rc%lu\n", name, slot, rc);
                continue;
            }
            found = 1;
            OUT("xinput dll=%s slot=%lu CONNECTED type=%u subtype=%u flags=%u\n",
                name, slot, c.Type, c.SubType, c.Flags);
            PAD_STATE s;
            ZeroMemory(&s, sizeof(s));
            if (state(slot, &s) == ERROR_SUCCESS) {
                OUT("xinput dll=%s slot=%lu state packet=%lu buttons=0x%04x "
                    "lt=%u rt=%u lx=%d ly=%d rx=%d ry=%d\n",
                    name, slot, s.dwPacketNumber, s.Gamepad.wButtons,
                    s.Gamepad.bLeftTrigger, s.Gamepad.bRightTrigger,
                    s.Gamepad.sThumbLX, s.Gamepad.sThumbLY,
                    s.Gamepad.sThumbRX, s.Gamepad.sThumbRY);
            }
        }
    }
    return found;
}

/* ---- DirectInput ---------------------------------------------------------- */

typedef struct { DWORD d1; WORD d2, d3; BYTE d4[8]; } PAD_GUID;

/* IID_IDirectInput8A. Written out rather than linked from dxguid so the probe
 * has no static library dependency at all. */
static const PAD_GUID IID_DI8A =
    { 0xBF798030, 0x483A, 0x4DA2, { 0xAA, 0x99, 0x5D, 0x64, 0xED, 0x36, 0x97, 0x00 } };

#define DI8DEVCLASS_GAMECTRL 4
#define DIEDFL_ATTACHEDONLY  0x00000001

typedef struct {
    DWORD    dwSize;
    PAD_GUID guidInstance;
    PAD_GUID guidProduct;
    DWORD    dwDevType;
    CHAR     tszInstanceName[260];
    CHAR     tszProductName[260];
    PAD_GUID guidFFDriver;
    WORD     wUsagePage;
    WORD     wUsage;
} PAD_DIDEVICEINSTANCEA;

typedef HRESULT (WINAPI *pfnDI8Create)(HINSTANCE, DWORD, const PAD_GUID *, void **, void *);

/* Only the three slots of IDirectInput8A's vtable this needs. Laid out as
 * IUnknown's three plus CreateDevice and EnumDevices, which is the documented
 * order and has never moved. */
struct DI8Vtbl {
    HRESULT (WINAPI *QueryInterface)(void *, const PAD_GUID *, void **);
    ULONG   (WINAPI *AddRef)(void *);
    ULONG   (WINAPI *Release)(void *);
    HRESULT (WINAPI *CreateDevice)(void *, const PAD_GUID *, void **, void *);
    HRESULT (WINAPI *EnumDevices)(void *, DWORD,
                                  BOOL (WINAPI *)(const PAD_DIDEVICEINSTANCEA *, void *),
                                  void *, DWORD);
};

struct DI8 { struct DI8Vtbl *lpVtbl; };

static int di_count;

static BOOL WINAPI on_device(const PAD_DIDEVICEINSTANCEA *inst, void *ctx)
{
    (void)ctx;
    di_count++;
    OUT("dinput device=\"%s\" product=\"%s\" type=0x%08lx\n",
        inst->tszInstanceName, inst->tszProductName, inst->dwDevType);
    return TRUE;
}

static int probe_dinput(void)
{
    HMODULE mod = LoadLibraryA("dinput8.dll");
    if (!mod) {
        OUT("dinput dll=dinput8.dll load=FAILED err=%lu\n", GetLastError());
        return 0;
    }
    pfnDI8Create create = (pfnDI8Create)(void *)GetProcAddress(mod, "DirectInput8Create");
    if (!create) {
        OUT("dinput DirectInput8Create=MISSING\n");
        return 0;
    }
    struct DI8 *di = NULL;
    /* 0x0800 is DIRECTINPUT_VERSION for DirectInput 8. */
    HRESULT hr = create(GetModuleHandleA(NULL), 0x0800, &IID_DI8A, (void **)&di, NULL);
    if (hr != S_OK || !di) {
        OUT("dinput DirectInput8Create hr=0x%08lx\n", (unsigned long)hr);
        return 0;
    }
    di_count = 0;
    hr = di->lpVtbl->EnumDevices(di, DI8DEVCLASS_GAMECTRL, on_device, NULL,
                                 DIEDFL_ATTACHEDONLY);
    OUT("dinput enum hr=0x%08lx devices=%d\n", (unsigned long)hr, di_count);
    di->lpVtbl->Release(di);
    return di_count;
}

/* ---- winmm ---------------------------------------------------------------- */

static int probe_winmm(void)
{
    HMODULE mod = LoadLibraryA("winmm.dll");
    if (!mod) {
        OUT("winmm load=FAILED err=%lu\n", GetLastError());
        return 0;
    }
    UINT (WINAPI *num)(void) = (UINT (WINAPI *)(void))(void *)GetProcAddress(mod, "joyGetNumDevs");
    MMRESULT (WINAPI *pos)(UINT, JOYINFO *) =
        (MMRESULT (WINAPI *)(UINT, JOYINFO *))(void *)GetProcAddress(mod, "joyGetPos");
    if (!num || !pos) {
        OUT("winmm joyGetNumDevs=%s joyGetPos=%s\n",
            num ? "yes" : "MISSING", pos ? "yes" : "MISSING");
        return 0;
    }
    UINT slots = num();
    OUT("winmm slots=%u\n", slots);
    int attached = 0;
    for (UINT i = 0; i < slots && i < 16; i++) {
        JOYINFO info;
        ZeroMemory(&info, sizeof(info));
        MMRESULT rc = pos(i, &info);
        if (rc != JOYERR_NOERROR) continue;
        attached++;
        OUT("winmm slot=%u ATTACHED x=%lu y=%lu z=%lu buttons=0x%08lx\n",
            i, info.wXpos, info.wYpos, info.wZpos, info.wButtons);
    }
    return attached;
}

int main(int argc, char **argv)
{
    /* Seconds to keep sampling. A pad that Wine enumerates late — or one a
     * bridge only publishes once the app has seen a packet — would be missed
     * by a single pass. Default is short enough that the script is still a
     * seconds-long thing. */
    int seconds = 3;
    if (argc > 1) seconds = atoi(argv[1]);
    if (seconds < 1) seconds = 1;

    OUT("begin build=" __DATE__ " " __TIME__ " seconds=%d\n", seconds);

    int xi = probe_xinput();
    int di = probe_dinput();
    int mm = probe_winmm();

    OUT("summary xinput=%d dinput=%d winmm=%d\n", xi, di, mm);

    /* If XInput found nothing, keep asking: the interesting failure to rule out
     * is a pad that arrives a second after the program starts, which a single
     * pass would report as absent. */
    if (!xi) {
        HMODULE mod = LoadLibraryA("xinput1_4.dll");
        pfnGetState state = mod
            ? (pfnGetState)(void *)GetProcAddress(mod, "XInputGetState")
            : NULL;
        if (state) {
            for (int t = 0; t < seconds * 4; t++) {
                Sleep(250);
                for (DWORD slot = 0; slot < 4; slot++) {
                    PAD_STATE s;
                    ZeroMemory(&s, sizeof(s));
                    if (state(slot, &s) != ERROR_SUCCESS) continue;
                    OUT("xinput LATE slot=%lu packet=%lu buttons=0x%04x\n",
                        slot, s.dwPacketNumber, s.Gamepad.wButtons);
                    xi = 1;
                }
                if (xi) break;
            }
        }
    }

    OUT("end xinput=%d\n", xi);
    return 0;
}

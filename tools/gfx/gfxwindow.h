/*
 * The one thing D3D9, D3D8 and OpenGL need that D3D10/11/12 do not: an HWND.
 *
 * D3D11CreateDevice and D3D12CreateDevice take no window — you can render to a
 * texture and never touch the presentation path. D3D9 and D3D8 cannot: the
 * device is created *from* a focus window and a presentation parameters block,
 * and WGL has nowhere to put a context except a window's DC. So those three
 * probes stand or fall on Wine having a working display driver.
 *
 * On the phone today it does not. Wine is built with winex11.drv and the X11
 * client libraries ship inside the Wine package, but nothing is listening on
 * DISPLAY yet — the in-app X server is being wired to a host surface
 * separately. With no display, win32u loads no driver and every window creation
 * fails at the first call.
 *
 * That failure is reported as BLOCKED, not FAIL, and the distinction is the
 * whole reason this header exists: "there is no X server" and "DXVK's D3D9 is
 * broken" produce the same NULL from CreateWindowEx, and conflating them would
 * make the suite lie in whichever direction is currently convenient.
 */

#ifndef VESSEL_GFXWINDOW_H
#define VESSEL_GFXWINDOW_H

#include "gfxprobe.h"

#define GFX_WNDCLASS "VesselGfxProbe"

static LRESULT CALLBACK gfx_wndproc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    return DefWindowProcA(hwnd, msg, wp, lp);
}

/*
 * A client area exactly GFX_W x GFX_H, so a backbuffer readback and a render
 * target readback sample the same pixels.
 *
 * The window is created WS_POPUP and never shown. It still needs a real display
 * driver behind it — an invisible window is not an offscreen one — but not
 * showing it keeps the probe from fighting a compositor once the X path is
 * live, and keeps it from stealing focus from whatever else is on screen.
 *
 * Returns NULL on failure, with *reason set to a short stage name so the caller
 * can label the BLOCKED line.
 */
static HWND gfx_window(const char **reason)
{
    WNDCLASSEXA wc;
    RECT rect;
    HWND hwnd;

    *reason = "registerclass";

    memset(&wc, 0, sizeof(wc));
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = gfx_wndproc;
    wc.hInstance = GetModuleHandleA(NULL);
    wc.lpszClassName = GFX_WNDCLASS;
    /* Failure here is benign only if the class already exists, which it cannot:
     * each probe is its own process. Any other failure is the USER32/win32u
     * side refusing to come up, and that is the display problem. */
    if (!RegisterClassExA(&wc))
        return NULL;

    *reason = "createwindow";

    /* AdjustWindowRect so the *client* area is 64x64 rather than the outer
     * frame; with WS_POPUP and no border they coincide, but not relying on that
     * means the probe keeps working if the style ever changes. */
    rect.left = 0;
    rect.top = 0;
    rect.right = GFX_W;
    rect.bottom = GFX_H;
    AdjustWindowRect(&rect, WS_POPUP, FALSE);

    hwnd = CreateWindowExA(0, GFX_WNDCLASS, "vessel-gfx", WS_POPUP,
                           0, 0, rect.right - rect.left, rect.bottom - rect.top,
                           NULL, NULL, wc.hInstance, NULL);
    if (!hwnd)
        return NULL;

    *reason = NULL;
    return hwnd;
}

/* GetLastError as a printable number, for the BLOCKED message. Wine sets
 * ERROR_CLASS_DOES_NOT_EXIST / ERROR_CANNOT_FIND_WND_CLASS here when the driver
 * is absent, and prints "no driver could be loaded" to stderr, which the runner
 * captures alongside. */
static DWORD gfx_window_error(void)
{
    return GetLastError();
}

#endif /* VESSEL_GFXWINDOW_H */

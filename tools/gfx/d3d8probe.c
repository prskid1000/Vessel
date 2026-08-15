/*
 * D3D8 through DXVK — needs a window, same as D3D9.
 *
 * Cheap to add because DXVK's d3d8.dll is a translation layer onto its own
 * d3d9, so most of what this exercises is already covered. It earns its place
 * anyway: the D3D8 interfaces are genuinely different at the top (an FVF is set
 * through SetVertexShader, render targets are locked rather than copied), and
 * that translation layer is the part with nothing else testing it.
 *
 * Blocked today for exactly the reason D3D9 is: CreateDevice takes an HWND.
 */

#include "gfxprobe.h"
#include "gfxwindow.h"

#include <d3d8.h>

#define API "d3d8"

typedef IDirect3D8 *(WINAPI *PFN_DIRECT3DCREATE8)(UINT);

#define SX(ndc) (((ndc) + 1.0f) * 0.5f * (float)GFX_W)
#define SY(ndc) ((1.0f - (ndc)) * 0.5f * (float)GFX_H)

struct vtx {
    float x, y, z, rhw;
    D3DCOLOR color;
};

int main(void)
{
    gfx_report_machine(API);
    HMODULE d3d8_dll = NULL;
    PFN_DIRECT3DCREATE8 create8;
    IDirect3D8 *d3d = NULL;
    IDirect3DDevice8 *device = NULL;
    IDirect3DSurface8 *rt = NULL;
    D3DPRESENT_PARAMETERS pp;
    D3DADAPTER_IDENTIFIER8 ident;
    D3DCAPS8 caps;
    D3DLOCKED_RECT locked;
    D3DVIEWPORT8 vp;
    HWND hwnd;
    const char *reason = NULL;
    unsigned in, out_a, out_b;
    HRESULT hr;
    int verdict, blocked = 0;

    static const struct vtx verts[3] = {
        { SX(GFX_TRI_X0), SY(GFX_TRI_Y0), 0.5f, 1.0f, 0xffff0000 },
        { SX(GFX_TRI_X1), SY(GFX_TRI_Y1), 0.5f, 1.0f, 0xffff0000 },
        { SX(GFX_TRI_X2), SY(GFX_TRI_Y2), 0.5f, 1.0f, 0xffff0000 },
    };

    d3d8_dll = gfx_load_ex(API, "d3d8.dll", &blocked);
    if (!d3d8_dll) return blocked ? GFX_EXIT_BLOCKED : GFX_EXIT_FAIL;
    create8 = (PFN_DIRECT3DCREATE8)(void *)GetProcAddress(d3d8_dll, "Direct3DCreate8");
    if (!create8)
        return gfx_fail(API, "getprocaddress", E_FAIL, "d3d8.dll exports no Direct3DCreate8");

    /* Checked BEFORE Direct3DCreate8, not after it fails.
     *
     * When DXVK cannot build a Vulkan instance it throws DxvkError out of this
     * very call, and because the entry point is `extern "C"` with no handler,
     * libc++abi calls std::terminate and the process dies — no return value, no
     * chance to print anything. Measured on device: the probe produced no output
     * at all until this check moved above the call. */
    if (gfx_wsi_ready() == 0)
        return gfx_blocked_no_wsi(API, "direct3dcreate8");

    d3d = create8(D3D_SDK_VERSION);
    if (!d3d)
        return gfx_fail(API, "direct3dcreate8", E_FAIL, "Direct3DCreate8 returned NULL");

    memset(&ident, 0, sizeof(ident));
    if (SUCCEEDED(IDirect3D8_GetAdapterIdentifier(d3d, D3DADAPTER_DEFAULT, 0, &ident)))
        gfx_info(API, "adapter=\"%s\" driver=\"%s\" vendor=0x%04x device=0x%04x",
                 ident.Description, ident.Driver, ident.VendorId, ident.DeviceId);

    memset(&caps, 0, sizeof(caps));
    if (SUCCEEDED(IDirect3D8_GetDeviceCaps(d3d, D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, &caps)))
        gfx_info(API, "feature_level=vs_%u_%u/ps_%u_%u max_texture=%ux%u",
                 (caps.VertexShaderVersion >> 8) & 0xff, caps.VertexShaderVersion & 0xff,
                 (caps.PixelShaderVersion >> 8) & 0xff, caps.PixelShaderVersion & 0xff,
                 caps.MaxTextureWidth, caps.MaxTextureHeight);

    hwnd = gfx_window(&reason);
    if (!hwnd) {
        verdict = gfx_blocked(API, reason,
                              "no window (err=%lu) — Wine has no display driver; "
                              "D3D8 cannot create a device without one",
                              (unsigned long)gfx_window_error());
        goto out;
    }

    memset(&pp, 0, sizeof(pp));
    pp.BackBufferWidth = GFX_W;
    pp.BackBufferHeight = GFX_H;
    pp.BackBufferFormat = D3DFMT_X8R8G8B8;
    pp.BackBufferCount = 1;
    pp.SwapEffect = D3DSWAPEFFECT_DISCARD;
    pp.hDeviceWindow = hwnd;
    pp.Windowed = TRUE;

    hr = IDirect3D8_CreateDevice(d3d, D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, hwnd,
                                 D3DCREATE_HARDWARE_VERTEXPROCESSING, &pp, &device);
    if (FAILED(hr)) {
        hr = IDirect3D8_CreateDevice(d3d, D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, hwnd,
                                     D3DCREATE_SOFTWARE_VERTEXPROCESSING, &pp, &device);
        if (FAILED(hr)) { verdict = gfx_fail(API, "createdevice", hr, "CreateDevice(HAL)"); goto out; }
        gfx_info(API, "note=software_vertex_processing");
    }

    /* Lockable=TRUE, and locked directly. D3D8 has no GetRenderTargetData — its
     * readback primitive is CopyRects into a CreateImageSurface, whose rules
     * about matching formats and pools are a trap of their own. A lockable
     * render target is the shorter road to the same three pixels. */
    hr = IDirect3DDevice8_CreateRenderTarget(device, GFX_W, GFX_H, D3DFMT_A8R8G8B8,
                                             D3DMULTISAMPLE_NONE, TRUE, &rt);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createrendertarget", hr, "CreateRenderTarget"); goto out; }

    /* D3D8's SetRenderTarget takes the depth surface in the same call; NULL
     * detaches it, which is what a depth-less probe wants. */
    hr = IDirect3DDevice8_SetRenderTarget(device, rt, NULL);
    if (FAILED(hr)) { verdict = gfx_fail(API, "setrendertarget", hr, "SetRenderTarget"); goto out; }

    vp.X = 0; vp.Y = 0; vp.Width = GFX_W; vp.Height = GFX_H; vp.MinZ = 0.0f; vp.MaxZ = 1.0f;
    IDirect3DDevice8_SetViewport(device, &vp);

    IDirect3DDevice8_SetRenderState(device, D3DRS_CULLMODE, D3DCULL_NONE);
    IDirect3DDevice8_SetRenderState(device, D3DRS_ZENABLE, D3DZB_FALSE);
    IDirect3DDevice8_SetRenderState(device, D3DRS_ALPHABLENDENABLE, FALSE);
    IDirect3DDevice8_SetRenderState(device, D3DRS_LIGHTING, FALSE);

    hr = IDirect3DDevice8_Clear(device, 0, NULL, D3DCLEAR_TARGET, GFX_CLEAR, 1.0f, 0);
    if (FAILED(hr)) { verdict = gfx_fail(API, "clear", hr, "Clear"); goto out; }

    hr = IDirect3DDevice8_BeginScene(device);
    if (FAILED(hr)) { verdict = gfx_fail(API, "beginscene", hr, "BeginScene"); goto out; }
    /* In D3D8 an FVF code *is* the vertex shader handle — there is no SetFVF. */
    IDirect3DDevice8_SetVertexShader(device, D3DFVF_XYZRHW | D3DFVF_DIFFUSE);
    hr = IDirect3DDevice8_DrawPrimitiveUP(device, D3DPT_TRIANGLELIST, 1, verts, sizeof(verts[0]));
    IDirect3DDevice8_EndScene(device);
    if (FAILED(hr)) { verdict = gfx_fail(API, "drawprimitiveup", hr, "DrawPrimitiveUP"); goto out; }

    hr = IDirect3DSurface8_LockRect(rt, &locked, NULL, D3DLOCK_READONLY);
    if (FAILED(hr)) { verdict = gfx_fail(API, "lockrect", hr, "LockRect"); goto out; }

    in    = gfx_pixel_bgra(locked.pBits, locked.Pitch, GFX_IN_X, GFX_IN_Y);
    out_a = gfx_pixel_bgra(locked.pBits, locked.Pitch, GFX_OUT_A_X, GFX_OUT_A_Y);
    out_b = gfx_pixel_bgra(locked.pBits, locked.Pitch, GFX_OUT_B_X, GFX_OUT_B_Y);
    IDirect3DSurface8_UnlockRect(rt);

    verdict = gfx_verdict(API, in, out_a, out_b);

out:
    if (rt) IDirect3DSurface8_Release(rt);
    if (device) IDirect3DDevice8_Release(device);
    if (d3d) IDirect3D8_Release(d3d);
    return verdict;
}

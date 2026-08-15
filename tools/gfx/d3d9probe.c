/*
 * D3D9 through DXVK — needs a window, and today there is none.
 *
 * Unlike D3D10/11/12, a D3D9 device cannot be created without an HWND: the
 * focus window and the presentation parameters are arguments to CreateDevice,
 * not something you can opt out of. So this probe is written, built and run,
 * and on a device with no X server it stops at gfx_window() and reports
 * BLOCKED. The moment the display path lands it should start passing with no
 * change here — which is the reason to write it now rather than later.
 *
 * The drawing deliberately uses no shaders. D3DFVF_XYZRHW gives pre-transformed
 * vertices in render-target pixel coordinates and D3DFVF_DIFFUSE gives a flat
 * colour, so the whole thing goes through DXVK's fixed-function emulation —
 * which is a large, D3D9-specific piece of DXVK that none of the other probes
 * touch, and the part most likely to be wrong.
 */

#include "gfxprobe.h"
#include "gfxwindow.h"

#include <d3d9.h>

#define API "d3d9"

typedef IDirect3D9 *(WINAPI *PFN_DIRECT3DCREATE9)(UINT);

/* D3DFVF_XYZRHW is in *pixels* of the current render target, not NDC, so the
 * shared triangle is converted once here. (x+1)/2*W and (1-y)/2*H — the y flip
 * is D3D's screen space being top-down. */
#define SX(ndc) (((ndc) + 1.0f) * 0.5f * (float)GFX_W)
#define SY(ndc) ((1.0f - (ndc)) * 0.5f * (float)GFX_H)

struct vtx {
    float x, y, z, rhw;
    D3DCOLOR color;
};

int main(void)
{
    gfx_report_machine(API);
    HMODULE d3d9_dll = NULL;
    PFN_DIRECT3DCREATE9 create9;
    IDirect3D9 *d3d = NULL;
    IDirect3DDevice9 *device = NULL;
    IDirect3DSurface9 *rt = NULL, *sysmem = NULL;
    D3DPRESENT_PARAMETERS pp;
    D3DADAPTER_IDENTIFIER9 ident;
    D3DCAPS9 caps;
    D3DLOCKED_RECT locked;
    D3DVIEWPORT9 vp;
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

    d3d9_dll = gfx_load_ex(API, "d3d9.dll", &blocked);
    if (!d3d9_dll) return blocked ? GFX_EXIT_BLOCKED : GFX_EXIT_FAIL;
    create9 = (PFN_DIRECT3DCREATE9)(void *)GetProcAddress(d3d9_dll, "Direct3DCreate9");
    if (!create9)
        return gfx_fail(API, "getprocaddress", E_FAIL, "d3d9.dll exports no Direct3DCreate9");

    /* Checked BEFORE Direct3DCreate9, not after it fails.
     *
     * When DXVK cannot build a Vulkan instance it throws DxvkError out of this
     * very call, and because the entry point is `extern "C"` with no handler,
     * libc++abi calls std::terminate and the process dies — no return value, no
     * chance to print anything. Measured on device: the probe produced no output
     * at all until this check moved above the call. */
    if (gfx_wsi_ready() == 0)
        return gfx_blocked_no_wsi(API, "direct3dcreate9");

    d3d = create9(D3D_SDK_VERSION);
    if (!d3d)
        return gfx_fail(API, "direct3dcreate9", E_FAIL, "Direct3DCreate9 returned NULL");

    memset(&ident, 0, sizeof(ident));
    if (SUCCEEDED(IDirect3D9_GetAdapterIdentifier(d3d, D3DADAPTER_DEFAULT, 0, &ident)))
        gfx_info(API, "adapter=\"%s\" driver=\"%s\" vendor=0x%04x device=0x%04x",
                 ident.Description, ident.Driver, ident.VendorId, ident.DeviceId);

    memset(&caps, 0, sizeof(caps));
    if (SUCCEEDED(IDirect3D9_GetDeviceCaps(d3d, D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, &caps)))
        gfx_info(API, "feature_level=vs_%u_%u/ps_%u_%u max_texture=%ux%u",
                 (caps.VertexShaderVersion >> 8) & 0xff, caps.VertexShaderVersion & 0xff,
                 (caps.PixelShaderVersion >> 8) & 0xff, caps.PixelShaderVersion & 0xff,
                 caps.MaxTextureWidth, caps.MaxTextureHeight);

    hwnd = gfx_window(&reason);
    if (!hwnd) {
        verdict = gfx_blocked(API, reason,
                              "no window (err=%lu) — Wine has no display driver; "
                              "D3D9 cannot create a device without one",
                              (unsigned long)gfx_window_error());
        goto out;
    }

    memset(&pp, 0, sizeof(pp));
    pp.BackBufferWidth = GFX_W;
    pp.BackBufferHeight = GFX_H;
    /* X8R8G8B8 for the *swapchain* because that is what a windowed device is
     * guaranteed to accept. The probe does not read the backbuffer — it renders
     * to its own A8R8G8B8 surface below, so the undefined X byte never reaches
     * the comparison. */
    pp.BackBufferFormat = D3DFMT_X8R8G8B8;
    pp.BackBufferCount = 1;
    pp.SwapEffect = D3DSWAPEFFECT_DISCARD;
    pp.hDeviceWindow = hwnd;
    pp.Windowed = TRUE;

    hr = IDirect3D9_CreateDevice(d3d, D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, hwnd,
                                 D3DCREATE_HARDWARE_VERTEXPROCESSING, &pp, &device);
    if (FAILED(hr)) {
        /* Retry with software vertex processing before calling it a failure:
         * on a device that reports no hardware T&L this is the difference
         * between "DXVK is broken" and "the caps say otherwise". */
        hr = IDirect3D9_CreateDevice(d3d, D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, hwnd,
                                     D3DCREATE_SOFTWARE_VERTEXPROCESSING, &pp, &device);
        if (FAILED(hr)) { verdict = gfx_fail(API, "createdevice", hr, "CreateDevice(HAL)"); goto out; }
        gfx_info(API, "note=software_vertex_processing");
    }

    /* A dedicated A8R8G8B8 render target rather than the backbuffer: it makes
     * the alpha channel defined, so the readback can be compared byte for byte
     * against the same constants every other probe uses. */
    hr = IDirect3DDevice9_CreateRenderTarget(device, GFX_W, GFX_H, D3DFMT_A8R8G8B8,
                                             D3DMULTISAMPLE_NONE, 0, FALSE, &rt, NULL);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createrendertarget", hr, "CreateRenderTarget"); goto out; }

    hr = IDirect3DDevice9_SetRenderTarget(device, 0, rt);
    if (FAILED(hr)) { verdict = gfx_fail(API, "setrendertarget", hr, "SetRenderTarget"); goto out; }

    vp.X = 0; vp.Y = 0; vp.Width = GFX_W; vp.Height = GFX_H; vp.MinZ = 0.0f; vp.MaxZ = 1.0f;
    IDirect3DDevice9_SetViewport(device, &vp);

    /* Cull nothing and ignore depth: the triangle's winding and Z are not what
     * this measures, and either could turn a working renderer into a blue
     * screenful of clear colour. */
    IDirect3DDevice9_SetRenderState(device, D3DRS_CULLMODE, D3DCULL_NONE);
    IDirect3DDevice9_SetRenderState(device, D3DRS_ZENABLE, D3DZB_FALSE);
    IDirect3DDevice9_SetRenderState(device, D3DRS_ALPHABLENDENABLE, FALSE);
    IDirect3DDevice9_SetRenderState(device, D3DRS_LIGHTING, FALSE);

    hr = IDirect3DDevice9_Clear(device, 0, NULL, D3DCLEAR_TARGET, GFX_CLEAR, 1.0f, 0);
    if (FAILED(hr)) { verdict = gfx_fail(API, "clear", hr, "Clear"); goto out; }

    hr = IDirect3DDevice9_BeginScene(device);
    if (FAILED(hr)) { verdict = gfx_fail(API, "beginscene", hr, "BeginScene"); goto out; }
    IDirect3DDevice9_SetFVF(device, D3DFVF_XYZRHW | D3DFVF_DIFFUSE);
    hr = IDirect3DDevice9_DrawPrimitiveUP(device, D3DPT_TRIANGLELIST, 1, verts, sizeof(verts[0]));
    IDirect3DDevice9_EndScene(device);
    if (FAILED(hr)) { verdict = gfx_fail(API, "drawprimitiveup", hr, "DrawPrimitiveUP"); goto out; }

    /* GetRenderTargetData into a SYSTEMMEM surface is D3D9's readback path and
     * the one real applications use; a lockable render target would work too
     * but is a path DXVK optimises differently, so this stays on the common
     * road. */
    hr = IDirect3DDevice9_CreateOffscreenPlainSurface(device, GFX_W, GFX_H, D3DFMT_A8R8G8B8,
                                                      D3DPOOL_SYSTEMMEM, &sysmem, NULL);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createoffscreenplainsurface", hr, "sysmem surface"); goto out; }

    hr = IDirect3DDevice9_GetRenderTargetData(device, rt, sysmem);
    if (FAILED(hr)) { verdict = gfx_fail(API, "getrendertargetdata", hr, "GetRenderTargetData"); goto out; }

    hr = IDirect3DSurface9_LockRect(sysmem, &locked, NULL, D3DLOCK_READONLY);
    if (FAILED(hr)) { verdict = gfx_fail(API, "lockrect", hr, "LockRect"); goto out; }

    /* D3DFMT_A8R8G8B8 is stored little-endian as B,G,R,A — the same bytes as
     * DXGI_FORMAT_B8G8R8A8_UNORM, so the shared reader applies unchanged. */
    in    = gfx_pixel_bgra(locked.pBits, locked.Pitch, GFX_IN_X, GFX_IN_Y);
    out_a = gfx_pixel_bgra(locked.pBits, locked.Pitch, GFX_OUT_A_X, GFX_OUT_A_Y);
    out_b = gfx_pixel_bgra(locked.pBits, locked.Pitch, GFX_OUT_B_X, GFX_OUT_B_Y);
    IDirect3DSurface9_UnlockRect(sysmem);

    verdict = gfx_verdict(API, in, out_a, out_b);

out:
    if (sysmem) IDirect3DSurface9_Release(sysmem);
    if (rt) IDirect3DSurface9_Release(rt);
    if (device) IDirect3DDevice9_Release(device);
    if (d3d) IDirect3D9_Release(d3d);
    return verdict;
}

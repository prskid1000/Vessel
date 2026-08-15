/*
 * D3D11 through DXVK, rendering to a texture with no swapchain.
 *
 * This was written as the one probe guaranteed to run today, on the reasoning
 * that D3D11CreateDevice with a NULL swapchain description needs no window. The
 * D3D part of that is true. It still does not run, and the reason is worth
 * stating at the top of the file because it was a genuine surprise:
 *
 *   DXVK enables VK_KHR_win32_surface at *instance* creation unconditionally,
 *   whether or not anything will ever be presented. Wine only advertises that
 *   extension when a display driver is loaded. With no X server the extension
 *   is absent, vkCreateInstance returns VK_ERROR_EXTENSION_NOT_PRESENT, and
 *   D3D11CreateDevice fails with E_FAIL before it ever looks at the GPU.
 *
 * So "headless D3D11" is headless as far as D3D is concerned and not headless
 * as far as DXVK is concerned. gfx_wsi_ready() detects exactly that case and
 * the probe reports BLOCKED, because nothing here is wrong with DXVK.
 *
 * WINEDLLOVERRIDES must name d3d11 and dxgi as native or Wine's builtins win
 * and this quietly measures wined3d instead. The probe cannot detect that from
 * the inside — a wined3d device answers every call here — so the runner sets
 * the overrides and this file prints the adapter string, which is the only
 * thing that would look different.
 */

#include "gfxprobe.h"

#include <d3d11.h>
#include <dxgi.h>
#include <d3dcompiler.h>

#define API "d3d11"

typedef HRESULT(WINAPI *PFN_D3D11_CREATE_DEVICE)(IDXGIAdapter *, D3D_DRIVER_TYPE, HMODULE, UINT,
                                                 const D3D_FEATURE_LEVEL *, UINT, UINT,
                                                 ID3D11Device **, D3D_FEATURE_LEVEL *,
                                                 ID3D11DeviceContext **);

typedef HRESULT(WINAPI *PFN_D3DCOMPILE)(const void *, SIZE_T, const char *, const D3D_SHADER_MACRO *,
                                        ID3DInclude *, const char *, const char *, UINT, UINT,
                                        ID3DBlob **, ID3DBlob **);

static const char *feature_level_name(D3D_FEATURE_LEVEL fl)
{
    switch (fl) {
    case D3D_FEATURE_LEVEL_9_1:  return "9_1";
    case D3D_FEATURE_LEVEL_9_2:  return "9_2";
    case D3D_FEATURE_LEVEL_9_3:  return "9_3";
    case D3D_FEATURE_LEVEL_10_0: return "10_0";
    case D3D_FEATURE_LEVEL_10_1: return "10_1";
    case D3D_FEATURE_LEVEL_11_0: return "11_0";
    case D3D_FEATURE_LEVEL_11_1: return "11_1";
    case D3D_FEATURE_LEVEL_12_0: return "12_0";
    case D3D_FEATURE_LEVEL_12_1: return "12_1";
    default: return "?";
    }
}

/* Compile one stage and turn a compiler diagnostic into something the log can
 * carry. vkd3d-shader's HLSL front end is what is doing this work under Wine's
 * d3dcompiler_47, and when it refuses a shader the error text is the finding —
 * dropping it would leave "PS would not compile" and nothing to act on. */
static ID3DBlob *compile(PFN_D3DCOMPILE d3d_compile, const char *src, size_t len,
                         const char *target, HRESULT *hr_out)
{
    ID3DBlob *code = NULL, *errors = NULL;
    HRESULT hr = d3d_compile(src, len, NULL, NULL, NULL, "main", target, 0, 0, &code, &errors);

    *hr_out = hr;
    if (errors) {
        if (ID3D10Blob_GetBufferSize(errors))
            gfx_info(API, "compiler(%s): %.400s", target, (const char *)ID3D10Blob_GetBufferPointer(errors));
        ID3D10Blob_Release(errors);
    }
    if (FAILED(hr)) return NULL;
    return code;
}

int main(void)
{
    gfx_report_machine(API);
    HMODULE d3d11 = NULL, compiler = NULL;
    PFN_D3D11_CREATE_DEVICE create_device;
    PFN_D3DCOMPILE d3d_compile;
    ID3D11Device *device = NULL;
    ID3D11DeviceContext *ctx = NULL;
    D3D_FEATURE_LEVEL level = 0;
    IDXGIDevice *dxgi_device = NULL;
    IDXGIAdapter *adapter = NULL;
    ID3DBlob *vs_code = NULL, *ps_code = NULL;
    ID3D11VertexShader *vs = NULL;
    ID3D11PixelShader *ps = NULL;
    ID3D11InputLayout *layout = NULL;
    ID3D11Buffer *vb = NULL;
    ID3D11Texture2D *rt = NULL, *staging = NULL;
    ID3D11RenderTargetView *rtv = NULL;
    D3D11_MAPPED_SUBRESOURCE mapped;
    D3D11_TEXTURE2D_DESC td;
    D3D11_BUFFER_DESC bd;
    D3D11_SUBRESOURCE_DATA init;
    D3D11_INPUT_ELEMENT_DESC element;
    D3D11_VIEWPORT vp;
    UINT stride = 2 * sizeof(float), offset = 0;
    const float clear[4] = { 0.0f, 0.0f, 1.0f, 1.0f }; /* GFX_CLEAR, as RGBA floats */
    unsigned in, out_a, out_b;
    HRESULT hr;
    int verdict, blocked = 0;

    /* Feature levels highest first: D3D11CreateDevice walks the list and takes
     * the first the adapter supports, so this asks "what is the best you have"
     * rather than pinning a level DXVK might not reach on this GPU. */
    static const D3D_FEATURE_LEVEL levels[] = {
        D3D_FEATURE_LEVEL_12_1, D3D_FEATURE_LEVEL_12_0,
        D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0,
        D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0,
    };

    d3d11 = gfx_load_ex(API, "d3d11.dll", &blocked);
    if (!d3d11) return blocked ? GFX_EXIT_BLOCKED : GFX_EXIT_FAIL;
    create_device = (PFN_D3D11_CREATE_DEVICE)(void *)GetProcAddress(d3d11, "D3D11CreateDevice");
    if (!create_device)
        return gfx_fail(API, "getprocaddress", E_FAIL, "d3d11.dll exports no D3D11CreateDevice");

    hr = create_device(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, 0,
                       levels, ARRAYSIZE(levels), D3D11_SDK_VERSION,
                       &device, &level, &ctx);
    if (FAILED(hr)) {
        /* Distinguish "DXVK is broken" from "DXVK could not get a Vulkan
         * instance because there is no display driver" — see gfx_wsi_ready. */
        if (gfx_wsi_ready() == 0)
            return gfx_blocked_no_wsi(API, "createdevice");
        return gfx_fail(API, "createdevice", hr, "D3D11CreateDevice(HARDWARE) failed");
    }

    gfx_info(API, "feature_level=%s", feature_level_name(level));

    /* The adapter name is how we find out whether DXVK is really in the path.
     * DXVK reports the Vulkan device name; wined3d reports something else. */
    if (SUCCEEDED(ID3D11Device_QueryInterface(device, &IID_IDXGIDevice, (void **)&dxgi_device)) &&
        SUCCEEDED(IDXGIDevice_GetAdapter(dxgi_device, &adapter))) {
        DXGI_ADAPTER_DESC desc;
        char name[512];
        if (SUCCEEDED(IDXGIAdapter_GetDesc(adapter, &desc)))
            gfx_info(API, "adapter=\"%s\" vendor=0x%04x device=0x%04x vram=%lluMB",
                     gfx_narrow(desc.Description, name, sizeof(name)),
                     desc.VendorId, desc.DeviceId,
                     (unsigned long long)(desc.DedicatedVideoMemory >> 20));
    }

    /* Wine's builtin d3dcompiler_47, i.e. vkd3d-shader's HLSL compiler. It is
     * loaded by name and late so that a compiler problem is reported as a
     * compiler problem — the device above already proved DXVK came up. */
    compiler = gfx_load(API, "d3dcompiler_47.dll");
    if (!compiler) { verdict = GFX_EXIT_FAIL; goto out; }
    d3d_compile = (PFN_D3DCOMPILE)(void *)GetProcAddress(compiler, "D3DCompile");
    if (!d3d_compile) { verdict = gfx_fail(API, "getprocaddress", E_FAIL, "no D3DCompile"); goto out; }

    vs_code = compile(d3d_compile, GFX_VS_SRC, sizeof(GFX_VS_SRC) - 1, "vs_4_0", &hr);
    if (!vs_code) { verdict = gfx_fail(API, "compile_vs", hr, "vs_4_0 would not compile"); goto out; }
    ps_code = compile(d3d_compile, GFX_PS_SRC, sizeof(GFX_PS_SRC) - 1, "ps_4_0", &hr);
    if (!ps_code) { verdict = gfx_fail(API, "compile_ps", hr, "ps_4_0 would not compile"); goto out; }

    hr = ID3D11Device_CreateVertexShader(device, ID3D10Blob_GetBufferPointer(vs_code),
                                         ID3D10Blob_GetBufferSize(vs_code), NULL, &vs);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createvertexshader", hr, "CreateVertexShader"); goto out; }
    hr = ID3D11Device_CreatePixelShader(device, ID3D10Blob_GetBufferPointer(ps_code),
                                        ID3D10Blob_GetBufferSize(ps_code), NULL, &ps);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createpixelshader", hr, "CreatePixelShader"); goto out; }

    memset(&element, 0, sizeof(element));
    element.SemanticName = "POSITION";
    element.Format = DXGI_FORMAT_R32G32_FLOAT;
    element.InputSlotClass = D3D11_INPUT_PER_VERTEX_DATA;
    hr = ID3D11Device_CreateInputLayout(device, &element, 1,
                                        ID3D10Blob_GetBufferPointer(vs_code),
                                        ID3D10Blob_GetBufferSize(vs_code), &layout);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createinputlayout", hr, "CreateInputLayout"); goto out; }

    memset(&bd, 0, sizeof(bd));
    bd.ByteWidth = sizeof(GFX_VERTS);
    bd.Usage = D3D11_USAGE_IMMUTABLE;
    bd.BindFlags = D3D11_BIND_VERTEX_BUFFER;
    memset(&init, 0, sizeof(init));
    init.pSysMem = GFX_VERTS;
    hr = ID3D11Device_CreateBuffer(device, &bd, &init, &vb);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createbuffer", hr, "vertex buffer"); goto out; }

    /* B8G8R8A8 rather than R8G8B8A8 so the mapped bytes are literally B,G,R,A
     * and gfx_pixel_bgra needs no per-API special case. */
    memset(&td, 0, sizeof(td));
    td.Width = GFX_W;
    td.Height = GFX_H;
    td.MipLevels = 1;
    td.ArraySize = 1;
    td.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    td.SampleDesc.Count = 1;
    td.Usage = D3D11_USAGE_DEFAULT;
    td.BindFlags = D3D11_BIND_RENDER_TARGET;
    hr = ID3D11Device_CreateTexture2D(device, &td, NULL, &rt);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createtexture2d", hr, "render target"); goto out; }

    /* A separate STAGING copy is the only way to get D3D11 pixels onto the CPU:
     * a texture cannot be both a render target and CPU-readable. */
    td.Usage = D3D11_USAGE_STAGING;
    td.BindFlags = 0;
    td.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    hr = ID3D11Device_CreateTexture2D(device, &td, NULL, &staging);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createtexture2d", hr, "staging texture"); goto out; }

    hr = ID3D11Device_CreateRenderTargetView(device, (ID3D11Resource *)rt, NULL, &rtv);
    if (FAILED(hr)) { verdict = gfx_fail(API, "creatertv", hr, "CreateRenderTargetView"); goto out; }

    memset(&vp, 0, sizeof(vp));
    vp.Width = (FLOAT)GFX_W;
    vp.Height = (FLOAT)GFX_H;
    vp.MaxDepth = 1.0f;

    ID3D11DeviceContext_OMSetRenderTargets(ctx, 1, &rtv, NULL);
    ID3D11DeviceContext_RSSetViewports(ctx, 1, &vp);
    ID3D11DeviceContext_ClearRenderTargetView(ctx, rtv, clear);
    ID3D11DeviceContext_IASetInputLayout(ctx, layout);
    ID3D11DeviceContext_IASetVertexBuffers(ctx, 0, 1, &vb, &stride, &offset);
    ID3D11DeviceContext_IASetPrimitiveTopology(ctx, D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    ID3D11DeviceContext_VSSetShader(ctx, vs, NULL, 0);
    ID3D11DeviceContext_PSSetShader(ctx, ps, NULL, 0);
    ID3D11DeviceContext_Draw(ctx, 3, 0);

    ID3D11DeviceContext_CopyResource(ctx, (ID3D11Resource *)staging, (ID3D11Resource *)rt);
    /* Map on a staging resource without D3D11_MAP_FLAG_DO_NOT_WAIT blocks until
     * the copy has retired, so no explicit fence or Flush is needed. */
    hr = ID3D11DeviceContext_Map(ctx, (ID3D11Resource *)staging, 0, D3D11_MAP_READ, 0, &mapped);
    if (FAILED(hr)) { verdict = gfx_fail(API, "map", hr, "Map(staging)"); goto out; }

    in    = gfx_pixel_bgra(mapped.pData, (int)mapped.RowPitch, GFX_IN_X, GFX_IN_Y);
    out_a = gfx_pixel_bgra(mapped.pData, (int)mapped.RowPitch, GFX_OUT_A_X, GFX_OUT_A_Y);
    out_b = gfx_pixel_bgra(mapped.pData, (int)mapped.RowPitch, GFX_OUT_B_X, GFX_OUT_B_Y);
    ID3D11DeviceContext_Unmap(ctx, (ID3D11Resource *)staging, 0);

    verdict = gfx_verdict(API, in, out_a, out_b);

out:
    if (rtv) ID3D11RenderTargetView_Release(rtv);
    if (staging) ID3D11Texture2D_Release(staging);
    if (rt) ID3D11Texture2D_Release(rt);
    if (vb) ID3D11Buffer_Release(vb);
    if (layout) ID3D11InputLayout_Release(layout);
    if (ps) ID3D11PixelShader_Release(ps);
    if (vs) ID3D11VertexShader_Release(vs);
    if (ps_code) ID3D10Blob_Release(ps_code);
    if (vs_code) ID3D10Blob_Release(vs_code);
    if (adapter) IDXGIAdapter_Release(adapter);
    if (dxgi_device) IDXGIDevice_Release(dxgi_device);
    if (ctx) ID3D11DeviceContext_Release(ctx);
    if (device) ID3D11Device_Release(device);
    return verdict;
}

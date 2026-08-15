/*
 * D3D10 through DXVK, rendering to a texture with no swapchain.
 *
 * Blocked on the display path today for the same reason as D3D11 and D3D12 —
 * DXVK's instance needs VK_KHR_win32_surface. See d3d11probe.c.
 *
 * Cheap to add next to the D3D11 probe and worth having, because the D3D10 path
 * is not simply "D3D11 with fewer features": DXVK ships only d3d10core.dll, and
 * the D3D10CreateDevice the application calls lives in Wine's *builtin*
 * d3d10.dll, which wraps it. So this probe tests a mixed builtin/native stack
 * that no other probe covers, and a failure here with D3D11 passing points at
 * that seam rather than at DXVK.
 *
 * Note the override list must therefore contain d3d10core and must NOT contain
 * d3d10 — forcing d3d10 native would ask for a DLL that DXVK does not build.
 *
 * Headless like D3D11: the device is created with no swapchain.
 */

#include "gfxprobe.h"

#include <d3d10_1.h>
#include <d3d10.h>
#include <dxgi.h>
#include <d3dcompiler.h>

#define API "d3d10"

typedef HRESULT(WINAPI *PFN_D3D10_CREATE_DEVICE)(IDXGIAdapter *, D3D10_DRIVER_TYPE, HMODULE, UINT,
                                                 UINT, ID3D10Device **);

typedef HRESULT(WINAPI *PFN_D3DCOMPILE)(const void *, SIZE_T, const char *, const D3D_SHADER_MACRO *,
                                        ID3DInclude *, const char *, const char *, UINT, UINT,
                                        ID3DBlob **, ID3DBlob **);

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
    HMODULE d3d10 = NULL, compiler = NULL;
    PFN_D3D10_CREATE_DEVICE create_device;
    PFN_D3DCOMPILE d3d_compile;
    ID3D10Device *device = NULL;
    IDXGIDevice *dxgi_device = NULL;
    IDXGIAdapter *adapter = NULL;
    ID3DBlob *vs_code = NULL, *ps_code = NULL;
    ID3D10VertexShader *vs = NULL;
    ID3D10PixelShader *ps = NULL;
    ID3D10InputLayout *layout = NULL;
    ID3D10Buffer *vb = NULL;
    ID3D10Texture2D *rt = NULL, *staging = NULL;
    ID3D10RenderTargetView *rtv = NULL;
    D3D10_MAPPED_TEXTURE2D mapped;
    D3D10_TEXTURE2D_DESC td;
    D3D10_BUFFER_DESC bd;
    D3D10_SUBRESOURCE_DATA init;
    D3D10_INPUT_ELEMENT_DESC element;
    D3D10_VIEWPORT vp;
    UINT stride = 2 * sizeof(float), offset = 0;
    const float clear[4] = { 0.0f, 0.0f, 1.0f, 1.0f };
    unsigned in, out_a, out_b;
    HRESULT hr;
    int verdict, blocked = 0;

    d3d10 = gfx_load_ex(API, "d3d10.dll", &blocked);
    if (!d3d10) return blocked ? GFX_EXIT_BLOCKED : GFX_EXIT_FAIL;
    create_device = (PFN_D3D10_CREATE_DEVICE)(void *)GetProcAddress(d3d10, "D3D10CreateDevice");
    if (!create_device)
        return gfx_fail(API, "getprocaddress", E_FAIL, "d3d10.dll exports no D3D10CreateDevice");

    hr = create_device(NULL, D3D10_DRIVER_TYPE_HARDWARE, NULL, 0, D3D10_SDK_VERSION, &device);
    if (FAILED(hr)) {
        if (gfx_wsi_ready() == 0)
            return gfx_blocked_no_wsi(API, "createdevice");
        return gfx_fail(API, "createdevice", hr, "D3D10CreateDevice(HARDWARE) failed");
    }

    /* D3D10 has no feature levels; the version is the level. Saying so keeps
     * the info lines uniform across probes. */
    gfx_info(API, "feature_level=10_0");

    if (SUCCEEDED(ID3D10Device_QueryInterface(device, &IID_IDXGIDevice, (void **)&dxgi_device)) &&
        SUCCEEDED(IDXGIDevice_GetAdapter(dxgi_device, &adapter))) {
        DXGI_ADAPTER_DESC desc;
        char name[512];
        if (SUCCEEDED(IDXGIAdapter_GetDesc(adapter, &desc)))
            gfx_info(API, "adapter=\"%s\" vendor=0x%04x device=0x%04x",
                     gfx_narrow(desc.Description, name, sizeof(name)), desc.VendorId, desc.DeviceId);
    }

    compiler = gfx_load(API, "d3dcompiler_47.dll");
    if (!compiler) { verdict = GFX_EXIT_FAIL; goto out; }
    d3d_compile = (PFN_D3DCOMPILE)(void *)GetProcAddress(compiler, "D3DCompile");
    if (!d3d_compile) { verdict = gfx_fail(API, "getprocaddress", E_FAIL, "no D3DCompile"); goto out; }

    vs_code = compile(d3d_compile, GFX_VS_SRC, sizeof(GFX_VS_SRC) - 1, "vs_4_0", &hr);
    if (!vs_code) { verdict = gfx_fail(API, "compile_vs", hr, "vs_4_0 would not compile"); goto out; }
    ps_code = compile(d3d_compile, GFX_PS_SRC, sizeof(GFX_PS_SRC) - 1, "ps_4_0", &hr);
    if (!ps_code) { verdict = gfx_fail(API, "compile_ps", hr, "ps_4_0 would not compile"); goto out; }

    hr = ID3D10Device_CreateVertexShader(device, ID3D10Blob_GetBufferPointer(vs_code),
                                         ID3D10Blob_GetBufferSize(vs_code), &vs);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createvertexshader", hr, "CreateVertexShader"); goto out; }
    hr = ID3D10Device_CreatePixelShader(device, ID3D10Blob_GetBufferPointer(ps_code),
                                        ID3D10Blob_GetBufferSize(ps_code), &ps);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createpixelshader", hr, "CreatePixelShader"); goto out; }

    memset(&element, 0, sizeof(element));
    element.SemanticName = "POSITION";
    element.Format = DXGI_FORMAT_R32G32_FLOAT;
    element.InputSlotClass = D3D10_INPUT_PER_VERTEX_DATA;
    hr = ID3D10Device_CreateInputLayout(device, &element, 1,
                                        ID3D10Blob_GetBufferPointer(vs_code),
                                        ID3D10Blob_GetBufferSize(vs_code), &layout);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createinputlayout", hr, "CreateInputLayout"); goto out; }

    memset(&bd, 0, sizeof(bd));
    bd.ByteWidth = sizeof(GFX_VERTS);
    bd.Usage = D3D10_USAGE_IMMUTABLE;
    bd.BindFlags = D3D10_BIND_VERTEX_BUFFER;
    memset(&init, 0, sizeof(init));
    init.pSysMem = GFX_VERTS;
    hr = ID3D10Device_CreateBuffer(device, &bd, &init, &vb);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createbuffer", hr, "vertex buffer"); goto out; }

    memset(&td, 0, sizeof(td));
    td.Width = GFX_W;
    td.Height = GFX_H;
    td.MipLevels = 1;
    td.ArraySize = 1;
    td.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    td.SampleDesc.Count = 1;
    td.Usage = D3D10_USAGE_DEFAULT;
    td.BindFlags = D3D10_BIND_RENDER_TARGET;
    hr = ID3D10Device_CreateTexture2D(device, &td, NULL, &rt);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createtexture2d", hr, "render target"); goto out; }

    td.Usage = D3D10_USAGE_STAGING;
    td.BindFlags = 0;
    td.CPUAccessFlags = D3D10_CPU_ACCESS_READ;
    hr = ID3D10Device_CreateTexture2D(device, &td, NULL, &staging);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createtexture2d", hr, "staging texture"); goto out; }

    hr = ID3D10Device_CreateRenderTargetView(device, (ID3D10Resource *)rt, NULL, &rtv);
    if (FAILED(hr)) { verdict = gfx_fail(API, "creatertv", hr, "CreateRenderTargetView"); goto out; }

    memset(&vp, 0, sizeof(vp));
    vp.Width = GFX_W;
    vp.Height = GFX_H;
    vp.MaxDepth = 1.0f;

    /* D3D10 has no separate immediate context — the device *is* the context. */
    ID3D10Device_OMSetRenderTargets(device, 1, &rtv, NULL);
    ID3D10Device_RSSetViewports(device, 1, &vp);
    ID3D10Device_ClearRenderTargetView(device, rtv, clear);
    ID3D10Device_IASetInputLayout(device, layout);
    ID3D10Device_IASetVertexBuffers(device, 0, 1, &vb, &stride, &offset);
    ID3D10Device_IASetPrimitiveTopology(device, D3D10_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    ID3D10Device_VSSetShader(device, vs);
    ID3D10Device_PSSetShader(device, ps);
    ID3D10Device_Draw(device, 3, 0);

    ID3D10Device_CopyResource(device, (ID3D10Resource *)staging, (ID3D10Resource *)rt);
    hr = ID3D10Texture2D_Map(staging, 0, D3D10_MAP_READ, 0, &mapped);
    if (FAILED(hr)) { verdict = gfx_fail(API, "map", hr, "Map(staging)"); goto out; }

    in    = gfx_pixel_bgra(mapped.pData, (int)mapped.RowPitch, GFX_IN_X, GFX_IN_Y);
    out_a = gfx_pixel_bgra(mapped.pData, (int)mapped.RowPitch, GFX_OUT_A_X, GFX_OUT_A_Y);
    out_b = gfx_pixel_bgra(mapped.pData, (int)mapped.RowPitch, GFX_OUT_B_X, GFX_OUT_B_Y);
    ID3D10Texture2D_Unmap(staging, 0);

    verdict = gfx_verdict(API, in, out_a, out_b);

out:
    if (rtv) ID3D10RenderTargetView_Release(rtv);
    if (staging) ID3D10Texture2D_Release(staging);
    if (rt) ID3D10Texture2D_Release(rt);
    if (vb) ID3D10Buffer_Release(vb);
    if (layout) ID3D10InputLayout_Release(layout);
    if (ps) ID3D10PixelShader_Release(ps);
    if (vs) ID3D10VertexShader_Release(vs);
    if (ps_code) ID3D10Blob_Release(ps_code);
    if (vs_code) ID3D10Blob_Release(vs_code);
    if (adapter) IDXGIAdapter_Release(adapter);
    if (dxgi_device) IDXGIDevice_Release(dxgi_device);
    if (device) ID3D10Device_Release(device);
    return verdict;
}

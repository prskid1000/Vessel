/*
 * D3D12 through vkd3d, rendering to a texture with no swapchain.
 *
 * D3D12CreateDevice takes an adapter and nothing else — no window, no
 * swapchain. That much is true, but the adapter comes from DXGI, DXGI here is
 * DXVK's, and DXVK cannot build a Vulkan instance without VK_KHR_win32_surface,
 * which Wine only offers when a display driver is loaded. So this is blocked on
 * the X server today for the same non-obvious reason D3D11 is; see d3d11probe.c
 * and gfx_wsi_ready().
 *
 * It is also the probe with the most moving parts, and that is the point: D3D12 has no immediate context to hide
 * behind, so a pass here means the command queue, the command allocator, an
 * explicit resource barrier, a root signature, a full PSO and a fence all
 * survived the trip through vkd3d to Vulkan and back.
 *
 * vkd3d ships two DLLs. d3d12.dll is the thin front end and d3d12core.dll holds
 * the implementation; both must be native in WINEDLLOVERRIDES or the front end
 * finds Wine's builtin core and the version numbers disagree.
 *
 * The readback deserves a note: D3D12 will not copy a texture straight into a
 * mappable buffer, it copies into a *placed footprint* whose row pitch must be
 * a multiple of D3D12_TEXTURE_DATA_PITCH_ALIGNMENT (256). At 64 pixels of
 * BGRA8 the natural pitch is exactly 256, which is why the probe is 64 wide and
 * why nothing here has to deal with padding between rows.
 */

#include "gfxprobe.h"

#include <d3d12.h>
#include <dxgi1_4.h>
#include <d3dcompiler.h>

#define API "d3d12"

typedef HRESULT(WINAPI *PFN_D3D12_CREATE_DEVICE_T)(IUnknown *, D3D_FEATURE_LEVEL, REFIID, void **);
typedef HRESULT(WINAPI *PFN_D3D12_SERIALIZE_ROOT_SIGNATURE_T)(const D3D12_ROOT_SIGNATURE_DESC *,
                                                              D3D_ROOT_SIGNATURE_VERSION,
                                                              ID3DBlob **, ID3DBlob **);
typedef HRESULT(WINAPI *PFN_CREATE_DXGI_FACTORY1)(REFIID, void **);
typedef HRESULT(WINAPI *PFN_D3DCOMPILE)(const void *, SIZE_T, const char *, const D3D_SHADER_MACRO *,
                                        ID3DInclude *, const char *, const char *, UINT, UINT,
                                        ID3DBlob **, ID3DBlob **);

static const char *feature_level_name(D3D_FEATURE_LEVEL fl)
{
    switch (fl) {
    case D3D_FEATURE_LEVEL_11_0: return "11_0";
    case D3D_FEATURE_LEVEL_11_1: return "11_1";
    case D3D_FEATURE_LEVEL_12_0: return "12_0";
    case D3D_FEATURE_LEVEL_12_1: return "12_1";
    default: return "?";
    }
}

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

/* The rest of the file is long but linear; the CD3DX12 helpers that would
 * normally shrink it are C++ only, so every descriptor is filled by hand. */
static void heap_props(D3D12_HEAP_PROPERTIES *hp, D3D12_HEAP_TYPE type)
{
    memset(hp, 0, sizeof(*hp));
    hp->Type = type;
    hp->CPUPageProperty = D3D12_CPU_PAGE_PROPERTY_UNKNOWN;
    hp->MemoryPoolPreference = D3D12_MEMORY_POOL_UNKNOWN;
    hp->CreationNodeMask = 1;
    hp->VisibleNodeMask = 1;
}

static void buffer_desc(D3D12_RESOURCE_DESC *rd, UINT64 size)
{
    memset(rd, 0, sizeof(*rd));
    rd->Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
    rd->Width = size;
    rd->Height = 1;
    rd->DepthOrArraySize = 1;
    rd->MipLevels = 1;
    rd->Format = DXGI_FORMAT_UNKNOWN;
    rd->SampleDesc.Count = 1;
    rd->Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
}

int main(void)
{
    gfx_report_machine(API);
    HMODULE d3d12_dll = NULL, dxgi_dll = NULL, compiler = NULL;
    PFN_D3D12_CREATE_DEVICE_T create_device;
    PFN_D3D12_SERIALIZE_ROOT_SIGNATURE_T serialize_rs;
    PFN_CREATE_DXGI_FACTORY1 create_factory;
    PFN_D3DCOMPILE d3d_compile;

    IDXGIFactory1 *factory = NULL;
    IDXGIAdapter1 *adapter = NULL;
    ID3D12Device *device = NULL;
    ID3D12CommandQueue *queue = NULL;
    ID3D12CommandAllocator *allocator = NULL;
    ID3D12GraphicsCommandList *list = NULL;
    ID3D12RootSignature *root_sig = NULL;
    ID3D12PipelineState *pso = NULL;
    ID3D12DescriptorHeap *rtv_heap = NULL;
    ID3D12Resource *rt = NULL, *vbuf = NULL, *readback = NULL;
    ID3D12Fence *fence = NULL;
    ID3DBlob *vs_code = NULL, *ps_code = NULL, *rs_code = NULL;
    HANDLE fence_event = NULL;

    D3D12_COMMAND_QUEUE_DESC qd;
    D3D12_DESCRIPTOR_HEAP_DESC hd;
    D3D12_HEAP_PROPERTIES hp;
    D3D12_RESOURCE_DESC rd;
    D3D12_CLEAR_VALUE clear_value;
    D3D12_ROOT_SIGNATURE_DESC rsd;
    D3D12_GRAPHICS_PIPELINE_STATE_DESC pd;
    D3D12_INPUT_ELEMENT_DESC element;
    D3D12_VERTEX_BUFFER_VIEW vbv;
    D3D12_CPU_DESCRIPTOR_HANDLE rtv;
    D3D12_VIEWPORT vp;
    D3D12_RECT scissor;
    D3D12_RESOURCE_BARRIER barrier;
    D3D12_TEXTURE_COPY_LOCATION dst, src;
    D3D12_RANGE read_range, no_write;
    const float clear[4] = { 0.0f, 0.0f, 1.0f, 1.0f };
    void *mapped = NULL;
    unsigned in, out_a, out_b;
    HRESULT hr;
    int verdict, blocked = 0;

    d3d12_dll = gfx_load_ex(API, "d3d12.dll", &blocked);
    if (!d3d12_dll) return blocked ? GFX_EXIT_BLOCKED : GFX_EXIT_FAIL;
    create_device = (PFN_D3D12_CREATE_DEVICE_T)(void *)GetProcAddress(d3d12_dll, "D3D12CreateDevice");
    serialize_rs = (PFN_D3D12_SERIALIZE_ROOT_SIGNATURE_T)(void *)GetProcAddress(d3d12_dll, "D3D12SerializeRootSignature");
    if (!create_device || !serialize_rs)
        return gfx_fail(API, "getprocaddress", E_FAIL, "d3d12.dll is missing entry points");

    /* An explicit adapter rather than NULL. NULL asks the runtime to pick, and
     * on a system with one GPU that is the same thing — but enumerating gets us
     * the adapter description to print, and IDXGIAdapter1 tells us whether
     * something handed us a software adapter, which would explain a pass that
     * means nothing. */
    dxgi_dll = gfx_load_ex(API, "dxgi.dll", &blocked);
    if (!dxgi_dll) return blocked ? GFX_EXIT_BLOCKED : GFX_EXIT_FAIL;
    create_factory = (PFN_CREATE_DXGI_FACTORY1)(void *)GetProcAddress(dxgi_dll, "CreateDXGIFactory1");
    if (!create_factory)
        return gfx_fail(API, "getprocaddress", E_FAIL, "dxgi.dll exports no CreateDXGIFactory1");

    /* DXGI here is DXVK's, and DXVK builds its Vulkan instance inside the
     * factory — so this is where the missing WSI extension surfaces for D3D12,
     * one call earlier than it does for D3D11. */
    hr = create_factory(&IID_IDXGIFactory1, (void **)&factory);
    if (FAILED(hr)) {
        if (gfx_wsi_ready() == 0)
            return gfx_blocked_no_wsi(API, "createfactory");
        return gfx_fail(API, "createfactory", hr, "CreateDXGIFactory1 failed");
    }

    hr = IDXGIFactory1_EnumAdapters1(factory, 0, &adapter);
    if (FAILED(hr)) {
        gfx_fail(API, "enumadapters", hr, "no DXGI adapter 0");
        IDXGIFactory1_Release(factory);
        return GFX_EXIT_FAIL;
    } else {
        DXGI_ADAPTER_DESC1 desc;
        char name[512];
        if (SUCCEEDED(IDXGIAdapter1_GetDesc1(adapter, &desc))) {
            gfx_info(API, "adapter=\"%s\" vendor=0x%04x device=0x%04x software=%d",
                     gfx_narrow(desc.Description, name, sizeof(name)),
                     desc.VendorId, desc.DeviceId,
                     (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) ? 1 : 0);
            /* The number a D3D12 title sizes its heaps from. vkd3d has no DXGI
             * of its own — this comes from DXVK's, which sums the driver's
             * device-local heaps — so this line and the Vulkan probe's should
             * agree, and if they do not the break is in DXGI rather than in the
             * driver. */
            gfx_emit("VESSEL-HW api=%s bits=%d vram_mib=%llu shared_mib=%llu",
                   API, gfx_bits(),
                   (unsigned long long)(desc.DedicatedVideoMemory >> 20),
                   (unsigned long long)(desc.SharedSystemMemory >> 20));
            gfx_flush();
        }
    }

    hr = create_device((IUnknown *)adapter, D3D_FEATURE_LEVEL_11_0, &IID_ID3D12Device, (void **)&device);
    if (FAILED(hr)) {
        verdict = gfx_fail(API, "createdevice", hr, "D3D12CreateDevice(11_0) failed");
        goto out;
    }

    {
        /* Ask the device which of these it can actually do. 11_0 is the floor
         * for D3D12 and what the device was created at; the answer here is what
         * vkd3d thinks the Vulkan driver can support. */
        static const D3D_FEATURE_LEVEL wanted[] = {
            D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_11_1,
            D3D_FEATURE_LEVEL_12_0, D3D_FEATURE_LEVEL_12_1,
        };
        D3D12_FEATURE_DATA_FEATURE_LEVELS fl;
        D3D12_FEATURE_DATA_D3D12_OPTIONS opts;

        memset(&fl, 0, sizeof(fl));
        fl.NumFeatureLevels = ARRAYSIZE(wanted);
        fl.pFeatureLevelsRequested = wanted;
        if (SUCCEEDED(ID3D12Device_CheckFeatureSupport(device, D3D12_FEATURE_FEATURE_LEVELS, &fl, sizeof(fl))))
            gfx_info(API, "feature_level=%s", feature_level_name(fl.MaxSupportedFeatureLevel));

        memset(&opts, 0, sizeof(opts));
        if (SUCCEEDED(ID3D12Device_CheckFeatureSupport(device, D3D12_FEATURE_D3D12_OPTIONS, &opts, sizeof(opts))))
            gfx_info(API, "resource_binding_tier=%d resource_heap_tier=%d tiled_resources_tier=%d",
                     (int)opts.ResourceBindingTier, (int)opts.ResourceHeapTier,
                     (int)opts.TiledResourcesTier);
    }

    memset(&qd, 0, sizeof(qd));
    qd.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    hr = ID3D12Device_CreateCommandQueue(device, &qd, &IID_ID3D12CommandQueue, (void **)&queue);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createcommandqueue", hr, "CreateCommandQueue"); goto out; }

    hr = ID3D12Device_CreateCommandAllocator(device, D3D12_COMMAND_LIST_TYPE_DIRECT,
                                             &IID_ID3D12CommandAllocator, (void **)&allocator);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createallocator", hr, "CreateCommandAllocator"); goto out; }

    /* --- shaders and the pipeline ---------------------------------------- */

    compiler = gfx_load(API, "d3dcompiler_47.dll");
    if (!compiler) { verdict = GFX_EXIT_FAIL; goto out; }
    d3d_compile = (PFN_D3DCOMPILE)(void *)GetProcAddress(compiler, "D3DCompile");
    if (!d3d_compile) { verdict = gfx_fail(API, "getprocaddress", E_FAIL, "no D3DCompile"); goto out; }

    /* 5_0 rather than 4_0: D3D12 requires shader model 5.0 as its floor, and
     * DXBC-TPF at 5_0 is what vkd3d-shader's SPIR-V back end is built around. */
    vs_code = compile(d3d_compile, GFX_VS_SRC, sizeof(GFX_VS_SRC) - 1, "vs_5_0", &hr);
    if (!vs_code) { verdict = gfx_fail(API, "compile_vs", hr, "vs_5_0 would not compile"); goto out; }
    ps_code = compile(d3d_compile, GFX_PS_SRC, sizeof(GFX_PS_SRC) - 1, "ps_5_0", &hr);
    if (!ps_code) { verdict = gfx_fail(API, "compile_ps", hr, "ps_5_0 would not compile"); goto out; }

    /* An empty root signature. The shaders bind nothing, so the only thing this
     * has to say is that an input layout is allowed — without that flag the PSO
     * creation below is rejected. */
    memset(&rsd, 0, sizeof(rsd));
    rsd.Flags = D3D12_ROOT_SIGNATURE_FLAG_ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT;
    hr = serialize_rs(&rsd, D3D_ROOT_SIGNATURE_VERSION_1, &rs_code, NULL);
    if (FAILED(hr)) { verdict = gfx_fail(API, "serializerootsignature", hr, "D3D12SerializeRootSignature"); goto out; }
    hr = ID3D12Device_CreateRootSignature(device, 0, ID3D10Blob_GetBufferPointer(rs_code),
                                          ID3D10Blob_GetBufferSize(rs_code),
                                          &IID_ID3D12RootSignature, (void **)&root_sig);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createrootsignature", hr, "CreateRootSignature"); goto out; }

    memset(&element, 0, sizeof(element));
    element.SemanticName = "POSITION";
    element.Format = DXGI_FORMAT_R32G32_FLOAT;
    element.InputSlotClass = D3D12_INPUT_CLASSIFICATION_PER_VERTEX_DATA;

    memset(&pd, 0, sizeof(pd));
    pd.pRootSignature = root_sig;
    pd.VS.pShaderBytecode = ID3D10Blob_GetBufferPointer(vs_code);
    pd.VS.BytecodeLength = ID3D10Blob_GetBufferSize(vs_code);
    pd.PS.pShaderBytecode = ID3D10Blob_GetBufferPointer(ps_code);
    pd.PS.BytecodeLength = ID3D10Blob_GetBufferSize(ps_code);
    /* memset gave us BlendEnable=FALSE and every blend factor 0, which is not a
     * legal blend state even when blending is off — the runtime validates the
     * factors regardless. Fill in the documented defaults. */
    pd.BlendState.RenderTarget[0].SrcBlend = D3D12_BLEND_ONE;
    pd.BlendState.RenderTarget[0].DestBlend = D3D12_BLEND_ZERO;
    pd.BlendState.RenderTarget[0].BlendOp = D3D12_BLEND_OP_ADD;
    pd.BlendState.RenderTarget[0].SrcBlendAlpha = D3D12_BLEND_ONE;
    pd.BlendState.RenderTarget[0].DestBlendAlpha = D3D12_BLEND_ZERO;
    pd.BlendState.RenderTarget[0].BlendOpAlpha = D3D12_BLEND_OP_ADD;
    pd.BlendState.RenderTarget[0].LogicOp = D3D12_LOGIC_OP_NOOP;
    pd.BlendState.RenderTarget[0].RenderTargetWriteMask = D3D12_COLOR_WRITE_ENABLE_ALL;
    pd.SampleMask = 0xffffffffu;
    pd.RasterizerState.FillMode = D3D12_FILL_MODE_SOLID;
    /* Cull nothing. The winding of the shared triangle is not the thing under
     * test, and a culled triangle would fail as a colour mismatch, which reads
     * like a rendering bug rather than a geometry convention. */
    pd.RasterizerState.CullMode = D3D12_CULL_MODE_NONE;
    pd.RasterizerState.DepthClipEnable = TRUE;
    pd.DepthStencilState.DepthEnable = FALSE;
    pd.DepthStencilState.StencilEnable = FALSE;
    pd.InputLayout.pInputElementDescs = &element;
    pd.InputLayout.NumElements = 1;
    pd.PrimitiveTopologyType = D3D12_PRIMITIVE_TOPOLOGY_TYPE_TRIANGLE;
    pd.NumRenderTargets = 1;
    pd.RTVFormats[0] = DXGI_FORMAT_B8G8R8A8_UNORM;
    pd.DSVFormat = DXGI_FORMAT_UNKNOWN;
    pd.SampleDesc.Count = 1;

    hr = ID3D12Device_CreateGraphicsPipelineState(device, &pd, &IID_ID3D12PipelineState, (void **)&pso);
    if (FAILED(hr)) { verdict = gfx_fail(API, "creategraphicspipelinestate", hr, "PSO creation"); goto out; }

    /* --- resources -------------------------------------------------------- */

    heap_props(&hp, D3D12_HEAP_TYPE_UPLOAD);
    buffer_desc(&rd, sizeof(GFX_VERTS));
    hr = ID3D12Device_CreateCommittedResource(device, &hp, D3D12_HEAP_FLAG_NONE, &rd,
                                              D3D12_RESOURCE_STATE_GENERIC_READ, NULL,
                                              &IID_ID3D12Resource, (void **)&vbuf);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createresource", hr, "upload vertex buffer"); goto out; }

    /* An UPLOAD-heap buffer is CPU visible for its whole life, so the vertices
     * can be written straight in with no staging copy and no barrier. */
    read_range.Begin = 0;
    read_range.End = 0;
    hr = ID3D12Resource_Map(vbuf, 0, &read_range, &mapped);
    if (FAILED(hr)) { verdict = gfx_fail(API, "map", hr, "Map(vertex buffer)"); goto out; }
    memcpy(mapped, GFX_VERTS, sizeof(GFX_VERTS));
    ID3D12Resource_Unmap(vbuf, 0, NULL);
    mapped = NULL;

    vbv.BufferLocation = ID3D12Resource_GetGPUVirtualAddress(vbuf);
    vbv.SizeInBytes = sizeof(GFX_VERTS);
    vbv.StrideInBytes = 2 * sizeof(float);

    heap_props(&hp, D3D12_HEAP_TYPE_DEFAULT);
    memset(&rd, 0, sizeof(rd));
    rd.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    rd.Width = GFX_W;
    rd.Height = GFX_H;
    rd.DepthOrArraySize = 1;
    rd.MipLevels = 1;
    rd.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    rd.SampleDesc.Count = 1;
    rd.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
    rd.Flags = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET;

    /* The optimized clear value must match the ClearRenderTargetView colour or
     * the runtime warns and some drivers take a slow path; matching it also
     * means a mismatch in the readback cannot be blamed on this. */
    memset(&clear_value, 0, sizeof(clear_value));
    clear_value.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    memcpy(clear_value.Color, clear, sizeof(clear));

    hr = ID3D12Device_CreateCommittedResource(device, &hp, D3D12_HEAP_FLAG_NONE, &rd,
                                              D3D12_RESOURCE_STATE_RENDER_TARGET, &clear_value,
                                              &IID_ID3D12Resource, (void **)&rt);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createresource", hr, "render target"); goto out; }

    heap_props(&hp, D3D12_HEAP_TYPE_READBACK);
    buffer_desc(&rd, (UINT64)D3D12_TEXTURE_DATA_PITCH_ALIGNMENT * GFX_H);
    hr = ID3D12Device_CreateCommittedResource(device, &hp, D3D12_HEAP_FLAG_NONE, &rd,
                                              D3D12_RESOURCE_STATE_COPY_DEST, NULL,
                                              &IID_ID3D12Resource, (void **)&readback);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createresource", hr, "readback buffer"); goto out; }

    memset(&hd, 0, sizeof(hd));
    hd.Type = D3D12_DESCRIPTOR_HEAP_TYPE_RTV;
    hd.NumDescriptors = 1;
    hr = ID3D12Device_CreateDescriptorHeap(device, &hd, &IID_ID3D12DescriptorHeap, (void **)&rtv_heap);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createdescriptorheap", hr, "RTV heap"); goto out; }

    rtv = ID3D12DescriptorHeap_GetCPUDescriptorHandleForHeapStart(rtv_heap);
    ID3D12Device_CreateRenderTargetView(device, rt, NULL, rtv);

    /* --- record ----------------------------------------------------------- */

    hr = ID3D12Device_CreateCommandList(device, 0, D3D12_COMMAND_LIST_TYPE_DIRECT, allocator, pso,
                                        &IID_ID3D12GraphicsCommandList, (void **)&list);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createcommandlist", hr, "CreateCommandList"); goto out; }

    memset(&vp, 0, sizeof(vp));
    vp.Width = (FLOAT)GFX_W;
    vp.Height = (FLOAT)GFX_H;
    vp.MaxDepth = 1.0f;
    scissor.left = 0;
    scissor.top = 0;
    scissor.right = GFX_W;
    scissor.bottom = GFX_H;

    ID3D12GraphicsCommandList_SetGraphicsRootSignature(list, root_sig);
    ID3D12GraphicsCommandList_RSSetViewports(list, 1, &vp);
    ID3D12GraphicsCommandList_RSSetScissorRects(list, 1, &scissor);
    ID3D12GraphicsCommandList_OMSetRenderTargets(list, 1, &rtv, FALSE, NULL);
    ID3D12GraphicsCommandList_ClearRenderTargetView(list, rtv, clear, 0, NULL);
    ID3D12GraphicsCommandList_IASetPrimitiveTopology(list, D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    ID3D12GraphicsCommandList_IASetVertexBuffers(list, 0, 1, &vbv);
    ID3D12GraphicsCommandList_DrawInstanced(list, 3, 1, 0, 0);

    memset(&barrier, 0, sizeof(barrier));
    barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    barrier.Transition.pResource = rt;
    barrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    barrier.Transition.StateBefore = D3D12_RESOURCE_STATE_RENDER_TARGET;
    barrier.Transition.StateAfter = D3D12_RESOURCE_STATE_COPY_SOURCE;
    ID3D12GraphicsCommandList_ResourceBarrier(list, 1, &barrier);

    memset(&dst, 0, sizeof(dst));
    dst.pResource = readback;
    dst.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
    dst.PlacedFootprint.Offset = 0;
    dst.PlacedFootprint.Footprint.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    dst.PlacedFootprint.Footprint.Width = GFX_W;
    dst.PlacedFootprint.Footprint.Height = GFX_H;
    dst.PlacedFootprint.Footprint.Depth = 1;
    dst.PlacedFootprint.Footprint.RowPitch = D3D12_TEXTURE_DATA_PITCH_ALIGNMENT;

    memset(&src, 0, sizeof(src));
    src.pResource = rt;
    src.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
    src.SubresourceIndex = 0;

    ID3D12GraphicsCommandList_CopyTextureRegion(list, &dst, 0, 0, 0, &src, NULL);

    hr = ID3D12GraphicsCommandList_Close(list);
    if (FAILED(hr)) { verdict = gfx_fail(API, "closecommandlist", hr, "Close"); goto out; }

    ID3D12CommandQueue_ExecuteCommandLists(queue, 1, (ID3D12CommandList *const *)&list);

    /* --- wait, then read -------------------------------------------------- */

    hr = ID3D12Device_CreateFence(device, 0, D3D12_FENCE_FLAG_NONE, &IID_ID3D12Fence, (void **)&fence);
    if (FAILED(hr)) { verdict = gfx_fail(API, "createfence", hr, "CreateFence"); goto out; }
    fence_event = CreateEventA(NULL, FALSE, FALSE, NULL);
    if (!fence_event) { verdict = gfx_fail(API, "createevent", E_FAIL, "CreateEvent"); goto out; }

    hr = ID3D12CommandQueue_Signal(queue, fence, 1);
    if (FAILED(hr)) { verdict = gfx_fail(API, "signal", hr, "queue Signal"); goto out; }
    if (ID3D12Fence_GetCompletedValue(fence) < 1) {
        ID3D12Fence_SetEventOnCompletion(fence, 1, fence_event);
        /* Ten seconds is far more than a 64x64 triangle needs; the timeout
         * exists so a lost device hangs the probe rather than the runner. */
        if (WaitForSingleObject(fence_event, 10000) != WAIT_OBJECT_0) {
            verdict = gfx_fail(API, "fencewait", E_FAIL, "GPU did not signal within 10s");
            goto out;
        }
    }

    /* Reading the whole buffer, writing none of it — the two ranges say so, and
     * a wrong no_write range is a classic way to lose a readback on a discrete
     * GPU where the runtime flushes on Unmap. */
    read_range.Begin = 0;
    read_range.End = (SIZE_T)D3D12_TEXTURE_DATA_PITCH_ALIGNMENT * GFX_H;
    hr = ID3D12Resource_Map(readback, 0, &read_range, &mapped);
    if (FAILED(hr)) { verdict = gfx_fail(API, "map", hr, "Map(readback)"); goto out; }

    in    = gfx_pixel_bgra(mapped, D3D12_TEXTURE_DATA_PITCH_ALIGNMENT, GFX_IN_X, GFX_IN_Y);
    out_a = gfx_pixel_bgra(mapped, D3D12_TEXTURE_DATA_PITCH_ALIGNMENT, GFX_OUT_A_X, GFX_OUT_A_Y);
    out_b = gfx_pixel_bgra(mapped, D3D12_TEXTURE_DATA_PITCH_ALIGNMENT, GFX_OUT_B_X, GFX_OUT_B_Y);

    no_write.Begin = 0;
    no_write.End = 0;
    ID3D12Resource_Unmap(readback, 0, &no_write);
    mapped = NULL;

    verdict = gfx_verdict(API, in, out_a, out_b);

out:
    if (fence_event) CloseHandle(fence_event);
    if (fence) ID3D12Fence_Release(fence);
    if (list) ID3D12GraphicsCommandList_Release(list);
    if (rtv_heap) ID3D12DescriptorHeap_Release(rtv_heap);
    if (readback) ID3D12Resource_Release(readback);
    if (rt) ID3D12Resource_Release(rt);
    if (vbuf) ID3D12Resource_Release(vbuf);
    if (pso) ID3D12PipelineState_Release(pso);
    if (root_sig) ID3D12RootSignature_Release(root_sig);
    if (rs_code) ID3D10Blob_Release(rs_code);
    if (ps_code) ID3D10Blob_Release(ps_code);
    if (vs_code) ID3D10Blob_Release(vs_code);
    if (allocator) ID3D12CommandAllocator_Release(allocator);
    if (queue) ID3D12CommandQueue_Release(queue);
    if (device) ID3D12Device_Release(device);
    if (adapter) IDXGIAdapter1_Release(adapter);
    if (factory) IDXGIFactory1_Release(factory);
    return verdict;
}

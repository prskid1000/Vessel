/*
 * How long does one flip cost, on this phone, through the path we actually
 * ship?
 *
 * Every other probe in this directory answers "did the GPU draw". None of them
 * touch presentation: D3D11CreateDevice takes no window, so the whole suite runs
 * headless and says nothing about the swapchain. This one is the opposite — it
 * does the smallest possible amount of drawing (one ClearRenderTargetView) and
 * then measures Present, so that what it reports is the present path and not a
 * shader.
 *
 * It exists because the shipping present path copies a whole frame on the CPU
 * every flip and nobody had put a number on that. Written so the same binary
 * measures whatever path the environment selects, which is how the sw,
 * sw+linear and zero-copy paths can be compared without rebuilding anything:
 *
 *   MESA_VK_WSI_DEBUG=sw        the shipping path (GPU blit + xcb_put_image)
 *   MESA_VK_WSI_DEBUG=sw,linear one copy fewer — the image is the mapped buffer
 *   (unset, on a DRI3-capable build)  zero copy
 *
 * Output, one greppable line:
 *
 *   VESSEL-PRESENT api=d3d11 w=1280 h=720 frames=300 result=PASS
 *      total_ms=... mean_ms=... p50_ms=... p95_ms=... min_ms=... max_ms=... fps=...
 *
 * `mean` is the honest headline; p95 is the one that decides whether a game
 * stutters. Both are reported because a mean under budget with a p95 over it is
 * a different product from a mean under budget with a p95 under it.
 */

#define COBJMACROS
#define WIDL_C_INLINE_WRAPPERS
#define WIN32_LEAN_AND_MEAN

#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <d3d11.h>
#include <dxgi.h>

#define DEFAULT_W 1280
#define DEFAULT_H 720
#define DEFAULT_FRAMES 300
#define WARMUP 30

static int g_w = DEFAULT_W, g_h = DEFAULT_H, g_frames = DEFAULT_FRAMES;
static int g_sync = 0; /* Present() sync interval; 0 = as fast as possible */

static LRESULT CALLBACK wndproc(HWND h, UINT m, WPARAM w, LPARAM l)
{
    return DefWindowProcA(h, m, w, l);
}

static void blocked(const char *stage, unsigned long err)
{
    printf("VESSEL-PRESENT api=d3d11 result=BLOCKED stage=%s err=%lu\n", stage, err);
}

static int cmp_double(const void *a, const void *b)
{
    double x = *(const double *)a, y = *(const double *)b;
    return x < y ? -1 : x > y ? 1 : 0;
}

int main(int argc, char **argv)
{
    HMODULE d3d11;
    PFN_D3D11_CREATE_DEVICE_AND_SWAP_CHAIN create;
    WNDCLASSEXA wc;
    RECT rect;
    HWND hwnd;
    DXGI_SWAP_CHAIN_DESC scd;
    IDXGISwapChain *swap = NULL;
    ID3D11Device *device = NULL;
    ID3D11DeviceContext *ctx = NULL;
    ID3D11Texture2D *back = NULL;
    ID3D11RenderTargetView *rtv = NULL;
    D3D_FEATURE_LEVEL got_level;
    static const D3D_FEATURE_LEVEL levels[] = {
        D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1,
    };
    LARGE_INTEGER freq, t0, t1;
    double *samples;
    double total = 0.0, mean, p50, p95, lo = 1e30, hi = 0.0;
    HRESULT hr;
    int i;

    setvbuf(stdout, NULL, _IONBF, 0);

    for (i = 1; i < argc; i++) {
        if (!strncmp(argv[i], "--width=", 8))       g_w = atoi(argv[i] + 8);
        else if (!strncmp(argv[i], "--height=", 9)) g_h = atoi(argv[i] + 9);
        else if (!strncmp(argv[i], "--frames=", 9)) g_frames = atoi(argv[i] + 9);
        else if (!strncmp(argv[i], "--sync=", 7))   g_sync = atoi(argv[i] + 7);
    }
    if (g_frames < 10) g_frames = 10;

    printf("VESSEL-PRESENT-INFO %dx%d frames=%d sync=%d wsi=\"%s\"\n",
           g_w, g_h, g_frames, g_sync,
           getenv("MESA_VK_WSI_DEBUG") ? getenv("MESA_VK_WSI_DEBUG") : "(unset)");

    /* Loaded by name, not imported: a probe that imports d3d11 statically never
     * reaches main() when the DLL is missing, and prints nothing the runner can
     * tell apart from a crash. Same reasoning as gfxprobe.h. */
    d3d11 = LoadLibraryA("d3d11.dll");
    if (!d3d11) { blocked("loadlibrary-d3d11", GetLastError()); return 1; }
    create = (PFN_D3D11_CREATE_DEVICE_AND_SWAP_CHAIN)(void *)
        GetProcAddress(d3d11, "D3D11CreateDeviceAndSwapChain");
    if (!create) { blocked("getprocaddress", GetLastError()); return 1; }

    memset(&wc, 0, sizeof(wc));
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = wndproc;
    wc.hInstance = GetModuleHandleA(NULL);
    wc.lpszClassName = "VesselPresentBench";
    if (!RegisterClassExA(&wc)) { blocked("registerclass", GetLastError()); return 1; }

    /* A real, shown window. An invisible one would still exercise the swapchain,
     * but the X server composites only mapped windows, and the server-side half
     * of a flip is exactly what is under measurement here. */
    rect.left = 0; rect.top = 0; rect.right = g_w; rect.bottom = g_h;
    AdjustWindowRect(&rect, WS_OVERLAPPEDWINDOW, FALSE);
    hwnd = CreateWindowExA(0, wc.lpszClassName, "vessel present bench",
                           WS_OVERLAPPEDWINDOW, 0, 0,
                           rect.right - rect.left, rect.bottom - rect.top,
                           NULL, NULL, wc.hInstance, NULL);
    if (!hwnd) { blocked("createwindow", GetLastError()); return 1; }
    ShowWindow(hwnd, SW_SHOW);
    UpdateWindow(hwnd);

    memset(&scd, 0, sizeof(scd));
    scd.BufferCount = 2;
    scd.BufferDesc.Width = g_w;
    scd.BufferDesc.Height = g_h;
    scd.BufferDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    scd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    scd.OutputWindow = hwnd;
    scd.SampleDesc.Count = 1;
    scd.Windowed = TRUE;
    scd.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;

    hr = create(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, 0, levels,
                (UINT)(sizeof(levels) / sizeof(levels[0])), D3D11_SDK_VERSION,
                &scd, &swap, &device, &got_level, &ctx);
    if (FAILED(hr) || !swap) {
        printf("VESSEL-PRESENT api=d3d11 result=FAIL stage=createdevice hr=0x%08lx\n",
               (unsigned long)hr);
        return 1;
    }

    {
        IDXGIDevice *dxgi_device = NULL;
        IDXGIAdapter *adapter = NULL;
        DXGI_ADAPTER_DESC ad;
        if (SUCCEEDED(ID3D11Device_QueryInterface(device, &IID_IDXGIDevice,
                                                  (void **)&dxgi_device)) &&
            SUCCEEDED(IDXGIDevice_GetAdapter(dxgi_device, &adapter)) &&
            SUCCEEDED(IDXGIAdapter_GetDesc(adapter, &ad))) {
            printf("VESSEL-PRESENT-INFO adapter=\"%ls\" featurelevel=0x%04x\n",
                   ad.Description, (unsigned)got_level);
        }
        if (adapter) IDXGIAdapter_Release(adapter);
        if (dxgi_device) IDXGIDevice_Release(dxgi_device);
    }

    hr = IDXGISwapChain_GetBuffer(swap, 0, &IID_ID3D11Texture2D, (void **)&back);
    if (FAILED(hr)) {
        printf("VESSEL-PRESENT api=d3d11 result=FAIL stage=getbuffer hr=0x%08lx\n",
               (unsigned long)hr);
        return 1;
    }
    hr = ID3D11Device_CreateRenderTargetView(device, (ID3D11Resource *)back, NULL, &rtv);
    if (FAILED(hr)) {
        printf("VESSEL-PRESENT api=d3d11 result=FAIL stage=creatertv hr=0x%08lx\n",
               (unsigned long)hr);
        return 1;
    }

    QueryPerformanceFrequency(&freq);
    samples = calloc((size_t)g_frames, sizeof(double));
    if (!samples) return 1;

    /* Warm-up frames are thrown away, not averaged in. The first Present builds
     * the swapchain's images, compiles DXVK's blit pipeline and faults in every
     * page of the shared buffer; including that in a mean would make the whole
     * measurement a function of how many frames were run. */
    for (i = 0; i < WARMUP + g_frames; i++) {
        MSG msg;
        FLOAT colour[4];
        double ms;

        while (PeekMessageA(&msg, NULL, 0, 0, PM_REMOVE)) {
            TranslateMessage(&msg);
            DispatchMessageA(&msg);
        }

        /* The colour changes every frame so nothing downstream — DXVK, the X
         * server's damage tracking, the compositor — can decide the frame is
         * identical and skip work. A benchmark that measures a skipped flip is
         * measuring nothing. */
        colour[0] = (float)(i % 60) / 60.0f;
        colour[1] = (float)((i * 7) % 60) / 60.0f;
        colour[2] = (float)((i * 13) % 60) / 60.0f;
        colour[3] = 1.0f;

        QueryPerformanceCounter(&t0);
        ID3D11DeviceContext_ClearRenderTargetView(ctx, rtv, colour);
        hr = IDXGISwapChain_Present(swap, (UINT)g_sync, 0);
        QueryPerformanceCounter(&t1);

        if (FAILED(hr)) {
            printf("VESSEL-PRESENT api=d3d11 result=FAIL stage=present frame=%d hr=0x%08lx\n",
                   i, (unsigned long)hr);
            return 1;
        }

        ms = (double)(t1.QuadPart - t0.QuadPart) * 1000.0 / (double)freq.QuadPart;
        if (i >= WARMUP) {
            samples[i - WARMUP] = ms;
            total += ms;
            if (ms < lo) lo = ms;
            if (ms > hi) hi = ms;
        } else if (i == 0) {
            printf("VESSEL-PRESENT-INFO first_present_ms=%.2f\n", ms);
        }
    }

    mean = total / g_frames;
    qsort(samples, (size_t)g_frames, sizeof(double), cmp_double);
    p50 = samples[g_frames / 2];
    p95 = samples[(int)((double)g_frames * 0.95)];

    printf("VESSEL-PRESENT api=d3d11 w=%d h=%d frames=%d result=PASS "
           "total_ms=%.1f mean_ms=%.3f p50_ms=%.3f p95_ms=%.3f min_ms=%.3f max_ms=%.3f fps=%.1f\n",
           g_w, g_h, g_frames, total, mean, p50, p95, lo, hi,
           mean > 0.0 ? 1000.0 / mean : 0.0);

    /* Deliberately no teardown. Releasing the swapchain on a path that is being
     * investigated for crashing at swapchain time would turn a clean result line
     * into a crash after it, and the numbers above are the deliverable. */
    return 0;
}

/*
 * Shared scaffolding for the Vessel graphics probes.
 *
 * Every probe in this directory answers the same question for a different API:
 * *did the GPU actually draw?* Creating a device proves nothing — DXVK will hand
 * you an ID3D11Device long before a single Vulkan queue submission has
 * succeeded, and wined3d will hand you one too, which is worse because it looks
 * identical. So each probe clears a 64x64 render target to blue, draws one red
 * triangle, reads the pixels back, and checks three of them by value.
 *
 * The geometry is shared deliberately: one NDC triangle, one set of sample
 * points, so the same three-pixel verdict is meaningful across D3D8 through
 * D3D12 and OpenGL, and a difference between two probes is the API rather than
 * the test.
 *
 * Everything is loaded with LoadLibrary/GetProcAddress rather than an import
 * library. That is not stylistic: if d3d11.dll cannot be resolved, a probe that
 * imported it statically never reaches main() and prints nothing at all, which
 * the runner cannot tell apart from a crash. Dynamic loading turns "the DLL is
 * missing or the override is wrong" into a labelled, greppable line.
 */

#ifndef VESSEL_GFXPROBE_H
#define VESSEL_GFXPROBE_H

/* Both are needed together. COBJMACROS turns on the C call macros; without
 * WIDL_C_INLINE_WRAPPERS the macros for methods that return a struct by value
 * (ID3D12DescriptorHeap::GetCPUDescriptorHandleForHeapStart, and only that one
 * here) expand to a deliberate compile error telling you to define this. */
#define COBJMACROS
#define WIDL_C_INLINE_WRAPPERS
#define WIN32_LEAN_AND_MEAN

#include <windows.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* 64x64: large enough that the three sample points sit far from any triangle
 * edge (so a half-pixel rasterisation difference cannot flip the verdict), and
 * small enough that a D3D12 readback row pitch is exactly the 256-byte
 * D3D12_TEXTURE_DATA_PITCH_ALIGNMENT with no padding to reason about. */
#define GFX_W 64
#define GFX_H 64

/* Colours as 0xAARRGGBB, which is how the probes print them. Blue and red were
 * picked because they land in different channels of a BGRA buffer, so a
 * byte-order mistake in the readback shows up as a wrong colour rather than as
 * a plausible one. */
#define GFX_CLEAR 0xff0000ffu /* opaque blue  — the clear colour */
#define GFX_TRI   0xffff0000u /* opaque red   — the triangle    */

/*
 * The triangle, in normalised device coordinates.
 *
 * Wide at the bottom, apex near the top. Chosen so that the same three sample
 * points work whichever way up the API stores its framebuffer: D3D reads rows
 * top-down, glReadPixels reads them bottom-up, and both (1,1) and (62,62) fall
 * outside this triangle either way while (32,32) is comfortably inside it.
 */
#define GFX_TRI_X0 (-0.8f)
#define GFX_TRI_Y0 (-0.8f)
#define GFX_TRI_X1 (0.0f)
#define GFX_TRI_Y1 (0.9f)
#define GFX_TRI_X2 (0.8f)
#define GFX_TRI_Y2 (-0.8f)

#define GFX_IN_X 32
#define GFX_IN_Y 32
#define GFX_OUT_A_X 1
#define GFX_OUT_A_Y 1
#define GFX_OUT_B_X 62
#define GFX_OUT_B_Y 62

/* Exit codes. The runner greps the text, not these, but a human running one
 * probe by hand gets something meaningful from $?. */
#define GFX_EXIT_PASS    0
#define GFX_EXIT_MISMATCH 1
#define GFX_EXIT_FAIL    2
#define GFX_EXIT_BLOCKED 3

/*
 * Every helper below is used by some probes and not others — glprobe never
 * reads a BGRA buffer, the headless probes never report BLOCKED — so -Wall's
 * unused-function warning fires on every build for helpers that are perfectly
 * correct. Marking them keeps the build silent, which is the only state in
 * which a *new* warning is worth reading.
 */
#define GFX_HELPER static __attribute__((unused))

/* --- reporting ------------------------------------------------------------ */

/* Every probe prints exactly one VESSEL-GFX line and any number of
 * VESSEL-GFX-INFO lines. The runner greps the former for a verdict and echoes
 * the latter so we learn what the driver claims to be. */

GFX_HELPER void gfx_flush(void)
{
    fflush(stdout);
    fflush(stderr);
}

GFX_HELPER void gfx_info(const char *api, const char *fmt, ...)
{
    va_list ap;
    printf("VESSEL-GFX-INFO api=%s ", api);
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    gfx_flush();
}

GFX_HELPER int gfx_bits(void)
{
    return (int)(sizeof(void *) * 8);
}

/* What this process was told the machine is.
 *
 * **Why every probe prints it rather than one of them.** The container's
 * Hardware settings are applied at the layer each number is *derived from* —
 * the CPU count and the memory size come from Wine, and video memory comes from
 * the driver's heap, which DXVK sums for DXGI, vkd3d reports through DXVK's
 * DXGI, and Zink sums again for GL. That is a chain of derivations, and a chain
 * is exactly the kind of thing that is true in three places and quietly false
 * in the fourth. So each API's probe says what *it* sees, in one grep-able
 * line, and a run of tools/device-graphics.sh produces the whole matrix.
 *
 * The two numbers here are the ones every probe can answer without an API:
 * the per-API video memory figure is printed by whichever probe has one, beside
 * this line. `dwNumberOfProcessors` is what a game reads to size a thread pool,
 * and `ullTotalPhys` is what it reads to size a streaming budget. */
GFX_HELPER void gfx_report_machine(const char *api)
{
    SYSTEM_INFO si;
    MEMORYSTATUSEX ms;

    memset(&si, 0, sizeof(si));
    GetSystemInfo(&si);

    memset(&ms, 0, sizeof(ms));
    ms.dwLength = sizeof(ms);
    if (!GlobalMemoryStatusEx(&ms))
        ms.ullTotalPhys = 0;

    printf("VESSEL-HW api=%s bits=%d cpus=%lu ram_mib=%llu\n",
           api, gfx_bits(), (unsigned long)si.dwNumberOfProcessors,
           (unsigned long long)(ms.ullTotalPhys >> 20));
    gfx_flush();
}

/* PASS only if all three pixels are exactly right. "Exactly" is on purpose:
 * these are UNORM8 clears and a flat-shaded triangle, so there is no filtering,
 * no blending and no gamma in the path. A value that is close but not equal
 * means something is converting, and we want to be told. */
GFX_HELPER int gfx_verdict(const char *api, unsigned in, unsigned out_a, unsigned out_b)
{
    int pass = (in == GFX_TRI) && (out_a == GFX_CLEAR) && (out_b == GFX_CLEAR);

    printf("VESSEL-GFX api=%s bits=%d result=%s in=%08x out_a=%08x out_b=%08x "
           "want_in=%08x want_out=%08x\n",
           api, gfx_bits(), pass ? "PASS" : "MISMATCH",
           in, out_a, out_b, GFX_TRI, GFX_CLEAR);
    gfx_flush();
    return pass ? GFX_EXIT_PASS : GFX_EXIT_MISMATCH;
}

/* A real failure: the API said no. hr is printed even when it is S_OK, because
 * "stage=map hr=0x00000000" tells you the call succeeded and the data was
 * wrong, which is a different bug from the call failing. */
GFX_HELPER int gfx_fail(const char *api, const char *stage, HRESULT hr, const char *fmt, ...)
{
    va_list ap;
    printf("VESSEL-GFX api=%s bits=%d result=FAIL stage=%s hr=0x%08lx msg=",
           api, gfx_bits(), stage, (unsigned long)hr);
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    gfx_flush();
    return GFX_EXIT_FAIL;
}

/* Not a failure of the thing under test: a prerequisite outside it is missing.
 * Today that is always "there is no display", which is what separates the D3D9,
 * D3D8 and OpenGL probes from the headless ones. The runner counts these
 * separately so a missing X server never reads as a broken DXVK. */
GFX_HELPER int gfx_blocked(const char *api, const char *stage, const char *fmt, ...)
{
    va_list ap;
    printf("VESSEL-GFX api=%s bits=%d result=BLOCKED stage=%s msg=",
           api, gfx_bits(), stage);
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    gfx_flush();
    return GFX_EXIT_BLOCKED;
}

/* --- pixel access --------------------------------------------------------- */

/* All the D3D probes render to DXGI_FORMAT_B8G8R8A8_UNORM (or D3DFMT_X8R8G8B8,
 * which is the same bytes), so a pixel is B,G,R,A in memory and this
 * reassembles it into the 0xAARRGGBB the constants are written in. */
GFX_HELPER unsigned gfx_pixel_bgra(const void *base, int pitch, int x, int y)
{
    const unsigned char *p = (const unsigned char *)base + (size_t)y * (size_t)pitch + (size_t)x * 4;
    return ((unsigned)p[3] << 24) | ((unsigned)p[2] << 16) | ((unsigned)p[1] << 8) | (unsigned)p[0];
}

/* glReadPixels(GL_RGBA) instead. Kept separate rather than parameterised so
 * neither path can silently acquire the other's byte order. */
GFX_HELPER unsigned gfx_pixel_rgba(const void *base, int pitch, int x, int y)
{
    const unsigned char *p = (const unsigned char *)base + (size_t)y * (size_t)pitch + (size_t)x * 4;
    return ((unsigned)p[3] << 24) | ((unsigned)p[0] << 16) | ((unsigned)p[1] << 8) | (unsigned)p[2];
}

/* --- misc ----------------------------------------------------------------- */

/* Adapter names come back as UTF-16 and the log is a byte pipe. */
GFX_HELPER const char *gfx_narrow(const WCHAR *w, char *buf, int len)
{
    if (!w) { buf[0] = '\0'; return buf; }
    if (!WideCharToMultiByte(CP_UTF8, 0, w, -1, buf, len, NULL, NULL))
        snprintf(buf, (size_t)len, "<unconvertible>");
    return buf;
}

/*
 * A module the probe cannot continue without.
 *
 * Two failure codes here mean very different things and the caller must be able
 * to tell them apart:
 *
 *   ERROR_MOD_NOT_FOUND (126)  the DLL, or something it imports, is not there.
 *   ERROR_BAD_EXE_FORMAT (193) the DLL is there and is the wrong machine.
 *
 * 193 is the one that surprises people. DXVK, vkd3d and Zink are built with
 * arm64ec-w64-mingw32-clang, which emits a *pure* ARM64EC image — machine type
 * AMD64 with CHPE metadata — not an ARM64X image with both halves linked in the
 * way Wine builds its own DLLs. A pure-ARM64 process therefore cannot load
 * them, while an x86-64 process (which on this stack *is* an ARM64EC process)
 * loads them fine. That is a property of how the packages are built, not a
 * fault, so it is reported as BLOCKED with its own stage rather than as a
 * failure of the component.
 *
 * Returns NULL having already printed the verdict line; *blocked is set when
 * the caller should exit BLOCKED rather than FAIL.
 */
GFX_HELPER HMODULE gfx_load_ex(const char *api, const char *dll, int *blocked)
{
    HMODULE h = LoadLibraryA(dll);
    DWORD err;

    if (blocked) *blocked = 0;
    if (h) return h;

    err = GetLastError();
    if (err == ERROR_BAD_EXE_FORMAT) {
        if (blocked) *blocked = 1;
        gfx_blocked(api, "arch",
                    "%s is the wrong machine for a %d-bit %s process — the DXVK/vkd3d/Zink "
                    "packages are pure ARM64EC, which only an ARM64EC (x86-64) process can load",
                    dll, (int)(sizeof(void *) * 8),
                    sizeof(void *) == 8 ? "native ARM64" : "i386");
        return NULL;
    }
    gfx_fail(api, "loadlibrary", (HRESULT)(DWORD_PTR)err, "cannot load %s", dll);
    return NULL;
}

/* The common case, where the caller has nothing useful to do with the
 * distinction and just stops. */
GFX_HELPER HMODULE gfx_load(const char *api, const char *dll)
{
    int ignored;
    return gfx_load_ex(api, dll, &ignored);
}

/*
 * Is Wine's Vulkan able to offer window-system integration?
 *
 * This exists because of a measured surprise. `D3D11CreateDevice` needs no
 * window, so the D3D10/11/12 probes were expected to run headless — but DXVK
 * enables VK_KHR_win32_surface unconditionally at *instance* creation, whether
 * or not anything will ever be presented, and Wine only advertises that
 * extension when a display driver is loaded. With no X server the extension is
 * absent, vkCreateInstance returns VK_ERROR_EXTENSION_NOT_PRESENT (-7), and
 * every DXVK entry point fails before it can look at the GPU.
 *
 * So DXVK is blocked by the display path exactly as D3D9 and OpenGL are, just
 * for a less obvious reason. Detecting it here is what stops the whole suite
 * reading as "DXVK is broken" when nothing is wrong with DXVK.
 *
 * Returns 1 = present, 0 = absent, -1 = could not tell.
 */
GFX_HELPER int gfx_wsi_ready(void)
{
    typedef int(__stdcall * PFN_enum_exts)(const char *, unsigned int *, void *);
    struct { char name[256]; unsigned int spec; } exts[128];
    HMODULE vk;
    PFN_enum_exts enum_exts;
    unsigned int n = 128, i;
    int result = 0;

    vk = LoadLibraryA("vulkan-1.dll");
    if (!vk) return -1;
    enum_exts = (PFN_enum_exts)(void *)GetProcAddress(vk, "vkEnumerateInstanceExtensionProperties");
    if (!enum_exts) return -1;
    if (enum_exts(NULL, &n, exts) < 0) return -1;

    for (i = 0; i < n && i < 128; i++)
        if (!strcmp(exts[i].name, "VK_KHR_win32_surface")) { result = 1; break; }
    return result;
}

/* Print the BLOCKED line for the case above. Kept in one place so all five
 * DXVK/vkd3d probes word it identically and a reader can see at a glance that
 * they share one cause. */
GFX_HELPER int gfx_blocked_no_wsi(const char *api, const char *stage)
{
    return gfx_blocked(api, stage,
                       "Vulkan does not advertise VK_KHR_win32_surface, so DXVK/vkd3d cannot "
                       "create a Vulkan instance at all. Wine only offers that extension when a "
                       "display driver is loaded, and there is no X server yet — this is the "
                       "display path, not the renderer");
}

/* --- HLSL ----------------------------------------------------------------- */

/*
 * One vertex shader and one pixel shader, shared by the D3D10/11/12 probes.
 *
 * Deliberately the dullest shader pair that still exercises the input
 * assembler: a POSITION attribute passed through, and a constant colour out.
 * No SV_VertexID (which would let us drop the vertex buffer) because that is a
 * system-value semantic and this is compiled at runtime by Wine's
 * d3dcompiler_47, i.e. by vkd3d-shader's HLSL front end — the point of the
 * probe is DXVK and vkd3d, so the shader must not be the interesting part.
 */
GFX_HELPER const char GFX_VS_SRC[] =
    "float4 main(float2 pos : POSITION) : SV_Position\n"
    "{\n"
    "    return float4(pos, 0.0, 1.0);\n"
    "}\n";

GFX_HELPER const char GFX_PS_SRC[] =
    "float4 main() : SV_Target\n"
    "{\n"
    "    return float4(1.0, 0.0, 0.0, 1.0);\n"
    "}\n";

/* The three vertices as the vertex buffer sees them: two floats each. */
GFX_HELPER const float GFX_VERTS[6] = {
    GFX_TRI_X0, GFX_TRI_Y0,
    GFX_TRI_X1, GFX_TRI_Y1,
    GFX_TRI_X2, GFX_TRI_Y2,
};

#endif /* VESSEL_GFXPROBE_H */

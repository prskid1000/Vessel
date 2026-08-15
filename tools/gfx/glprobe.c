/*
 * Desktop OpenGL through Mesa/Zink — needs a window, and today there is none.
 *
 * Zink replaces opengl32.dll wholesale: it is not a Direct3D translation layer
 * but a GL implementation that emits Vulkan, so WGL comes from the same DLL.
 * WGL has no headless entry point in its base form — a context is created from
 * a device context, and a device context comes from a window — so this probe is
 * blocked for the same reason D3D9 and D3D8 are, and will unblock the same way.
 *
 * Two things this probe is specifically watching for beyond "did it draw":
 *
 *   - GL_RENDERER. Zink reports "zink Vulkan <device>"; Wine's builtin
 *     opengl32 reports something else entirely. That string is how we know the
 *     override took and which implementation answered.
 *   - The 32-bit case. The Zink package ships system32/opengl32.dll only —
 *     there is no i386 build — so an i686 run of this probe has no native
 *     opengl32 to load at all. That is a packaging gap, not a rendering
 *     failure, and it reports as such: gfx_load names the DLL it could not get.
 *
 * Everything is fetched with GetProcAddress. GL 1.1 entry points are real
 * exports of opengl32.dll (unlike extensions, which need wglGetProcAddress), so
 * immediate-mode drawing needs no extension machinery — which is why the probe
 * draws with glBegin/glEnd rather than a VBO and a shader. Nothing here is
 * about modern GL; it is about whether a triangle reaches a framebuffer.
 */

#include "gfxprobe.h"
#include "gfxwindow.h"

#include <GL/gl.h>

#define API "opengl"

/* Prefixed pointer names: GL/gl.h already declares the unprefixed symbols, and
 * this file must not accidentally bind to the import library. */
typedef HGLRC(WINAPI *PFN_wglCreateContext)(HDC);
typedef BOOL(WINAPI *PFN_wglMakeCurrent)(HDC, HGLRC);
typedef BOOL(WINAPI *PFN_wglDeleteContext)(HGLRC);
typedef const GLubyte *(WINAPI *PFN_glGetString)(GLenum);
typedef void(WINAPI *PFN_glGetIntegerv)(GLenum, GLint *);
typedef void(WINAPI *PFN_glViewport)(GLint, GLint, GLsizei, GLsizei);
typedef void(WINAPI *PFN_glClearColor)(GLfloat, GLfloat, GLfloat, GLfloat);
typedef void(WINAPI *PFN_glClear)(GLbitfield);
typedef void(WINAPI *PFN_glDisable)(GLenum);
typedef void(WINAPI *PFN_glBegin)(GLenum);
typedef void(WINAPI *PFN_glEnd)(void);
typedef void(WINAPI *PFN_glColor4f)(GLfloat, GLfloat, GLfloat, GLfloat);
typedef void(WINAPI *PFN_glVertex3f)(GLfloat, GLfloat, GLfloat);
typedef void(WINAPI *PFN_glFinish)(void);
typedef void(WINAPI *PFN_glReadBuffer)(GLenum);
typedef void(WINAPI *PFN_glPixelStorei)(GLenum, GLint);
typedef void(WINAPI *PFN_glReadPixels)(GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, void *);
typedef GLenum(WINAPI *PFN_glGetError)(void);

static PFN_wglCreateContext p_wglCreateContext;
static PFN_wglMakeCurrent p_wglMakeCurrent;
static PFN_wglDeleteContext p_wglDeleteContext;
static PFN_glGetString p_glGetString;
static PFN_glViewport p_glViewport;
static PFN_glClearColor p_glClearColor;
static PFN_glClear p_glClear;
static PFN_glDisable p_glDisable;
static PFN_glBegin p_glBegin;
static PFN_glEnd p_glEnd;
static PFN_glColor4f p_glColor4f;
static PFN_glVertex3f p_glVertex3f;
static PFN_glFinish p_glFinish;
static PFN_glReadBuffer p_glReadBuffer;
static PFN_glPixelStorei p_glPixelStorei;
static PFN_glReadPixels p_glReadPixels;
static PFN_glGetError p_glGetError;
static PFN_glGetIntegerv p_glGetIntegerv;

static int bind_all(HMODULE gl)
{
    struct { const char *name; void **slot; } table[] = {
        { "wglCreateContext", (void **)&p_wglCreateContext },
        { "wglMakeCurrent",   (void **)&p_wglMakeCurrent },
        { "wglDeleteContext", (void **)&p_wglDeleteContext },
        { "glGetString",      (void **)&p_glGetString },
        { "glGetIntegerv",    (void **)&p_glGetIntegerv },
        { "glViewport",       (void **)&p_glViewport },
        { "glClearColor",     (void **)&p_glClearColor },
        { "glClear",          (void **)&p_glClear },
        { "glDisable",        (void **)&p_glDisable },
        { "glBegin",          (void **)&p_glBegin },
        { "glEnd",            (void **)&p_glEnd },
        { "glColor4f",        (void **)&p_glColor4f },
        { "glVertex3f",       (void **)&p_glVertex3f },
        { "glFinish",         (void **)&p_glFinish },
        { "glReadBuffer",     (void **)&p_glReadBuffer },
        { "glPixelStorei",    (void **)&p_glPixelStorei },
        { "glReadPixels",     (void **)&p_glReadPixels },
        { "glGetError",       (void **)&p_glGetError },
    };
    size_t i;

    for (i = 0; i < sizeof(table) / sizeof(table[0]); i++) {
        *table[i].slot = (void *)GetProcAddress(gl, table[i].name);
        if (!*table[i].slot) {
            gfx_fail(API, "getprocaddress", E_FAIL, "opengl32.dll has no %s", table[i].name);
            return 0;
        }
    }
    return 1;
}

static const char *gl_string(GLenum name)
{
    const GLubyte *s = p_glGetString(name);
    return s ? (const char *)s : "<null>";
}

int main(void)
{
    gfx_report_machine(API);
    HMODULE gl = NULL;
    HWND hwnd = NULL;
    HDC dc = NULL;
    HGLRC ctx = NULL;
    PIXELFORMATDESCRIPTOR pfd;
    int format;
    unsigned char *pixels = NULL;
    const char *reason = NULL;
    unsigned in, out_a, out_b;
    GLenum err;
    int verdict;

    gl = gfx_load(API, "opengl32.dll");
    if (!gl) return GFX_EXIT_FAIL;
    if (!bind_all(gl)) return GFX_EXIT_FAIL;

    hwnd = gfx_window(&reason);
    if (!hwnd)
        return gfx_blocked(API, reason,
                           "no window (err=%lu) — WGL has no way to make a context "
                           "without a device context, and a device context needs a window",
                           (unsigned long)gfx_window_error());

    dc = GetDC(hwnd);
    if (!dc) { verdict = gfx_blocked(API, "getdc", "GetDC returned NULL"); goto out; }

    memset(&pfd, 0, sizeof(pfd));
    pfd.nSize = sizeof(pfd);
    pfd.nVersion = 1;
    /* PFD_DOUBLEBUFFER because that is what a real application asks for and
     * what the driver tunes for; the probe reads GL_BACK before any swap, so
     * nothing has to be presented. */
    pfd.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
    pfd.iPixelType = PFD_TYPE_RGBA;
    pfd.cColorBits = 32;
    pfd.cAlphaBits = 8;

    format = ChoosePixelFormat(dc, &pfd);
    if (!format) { verdict = gfx_blocked(API, "choosepixelformat", "no matching pixel format"); goto out; }
    if (!SetPixelFormat(dc, format, &pfd)) {
        verdict = gfx_blocked(API, "setpixelformat", "SetPixelFormat failed (err=%lu)",
                              (unsigned long)GetLastError());
        goto out;
    }

    ctx = p_wglCreateContext(dc);
    if (!ctx) {
        verdict = gfx_fail(API, "wglcreatecontext", (HRESULT)(DWORD_PTR)GetLastError(),
                           "wglCreateContext failed");
        goto out;
    }
    if (!p_wglMakeCurrent(dc, ctx)) {
        verdict = gfx_fail(API, "wglmakecurrent", (HRESULT)(DWORD_PTR)GetLastError(),
                           "wglMakeCurrent failed");
        goto out;
    }

    /* What GL was told about video memory.
     *
     * Two extensions say it and neither is guaranteed: GL_NVX_gpu_memory_info
     * reports dedicated VRAM in KiB, GL_ATI_meminfo reports free VBO memory.
     * Mesa exposes them from Gallium's query_memory_info, which Zink implements
     * by summing the same device-local Vulkan heaps DXVK sums for DXGI -- so
     * this figure and the Vulkan probe's should agree, and this is the line
     * that proves the container's VRAM setting reaches OpenGL rather than only
     * the D3D layers. Absent is reported as absent, not as zero. */
    {
        /* GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX / VBO_FREE_MEMORY_ATI */
        enum { NVX_DEDICATED = 0x9047, ATI_VBO_FREE = 0x87FB };
        const GLubyte *ext = p_glGetString(GL_EXTENSIONS);
        const char *exts = ext ? (const char *)ext : "";
        GLint kib = -1;

        if (p_glGetIntegerv && strstr(exts, "GL_NVX_gpu_memory_info")) {
            p_glGetIntegerv(NVX_DEDICATED, &kib);
        } else if (p_glGetIntegerv && strstr(exts, "GL_ATI_meminfo")) {
            GLint four[4] = { -1, -1, -1, -1 };
            p_glGetIntegerv(ATI_VBO_FREE, four);
            kib = four[0];
        }
        if (kib >= 0)
            gfx_emit("VESSEL-HW api=%s bits=%d vram_mib=%d", API, gfx_bits(), (int)(kib / 1024));
        else
            gfx_emit("VESSEL-HW api=%s bits=%d vram_mib=absent", API, gfx_bits());
        gfx_flush();
        while (p_glGetError && p_glGetError() != 0) { }
    }

    /* GL_RENDERER is the whole reason for these three lines: it is the only
     * place the implementation names itself, and "zink Vulkan ..." versus
     * anything else decides whether this probe measured Zink. */
    gfx_info(API, "vendor=\"%s\"", gl_string(GL_VENDOR));
    gfx_info(API, "renderer=\"%s\"", gl_string(GL_RENDERER));
    gfx_info(API, "feature_level=\"%s\"", gl_string(GL_VERSION));

    p_glViewport(0, 0, GFX_W, GFX_H);
    p_glDisable(GL_DEPTH_TEST);
    p_glDisable(GL_CULL_FACE);
    p_glDisable(GL_BLEND);
    p_glClearColor(0.0f, 0.0f, 1.0f, 1.0f);
    p_glClear(GL_COLOR_BUFFER_BIT);

    /* No projection or modelview matrix is set, so the fixed-function pipeline
     * uses identity for both and glVertex3f coordinates are already NDC — the
     * same numbers the D3D10/11/12 vertex buffer holds. */
    p_glBegin(GL_TRIANGLES);
    p_glColor4f(1.0f, 0.0f, 0.0f, 1.0f);
    p_glVertex3f(GFX_TRI_X0, GFX_TRI_Y0, 0.0f);
    p_glVertex3f(GFX_TRI_X1, GFX_TRI_Y1, 0.0f);
    p_glVertex3f(GFX_TRI_X2, GFX_TRI_Y2, 0.0f);
    p_glEnd();
    p_glFinish();

    err = p_glGetError();
    if (err != GL_NO_ERROR) {
        verdict = gfx_fail(API, "gldraw", (HRESULT)err, "glGetError() = 0x%04x after the draw", err);
        goto out;
    }

    pixels = (unsigned char *)malloc((size_t)GFX_W * GFX_H * 4);
    if (!pixels) { verdict = gfx_fail(API, "malloc", E_OUTOFMEMORY, "readback buffer"); goto out; }

    /* GL_BACK explicitly: the default read buffer for a double-buffered context
     * is GL_BACK, but saying so costs nothing and removes one assumption.
     * glPixelStorei(GL_PACK_ALIGNMENT, 1) because the default is 4 and a
     * 64-pixel RGBA row is already a multiple of 4 — again, an assumption
     * removed rather than relied on. */
    p_glReadBuffer(GL_BACK);
    p_glPixelStorei(GL_PACK_ALIGNMENT, 1);
    p_glReadPixels(0, 0, GFX_W, GFX_H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

    err = p_glGetError();
    if (err != GL_NO_ERROR) {
        verdict = gfx_fail(API, "glreadpixels", (HRESULT)err, "glGetError() = 0x%04x after readback", err);
        goto out;
    }

    /* glReadPixels rows run bottom-up, which is why the shared sample points
     * were chosen symmetric enough not to care: (32,32) is inside the triangle
     * either way and both corners are outside it either way. */
    in    = gfx_pixel_rgba(pixels, GFX_W * 4, GFX_IN_X, GFX_IN_Y);
    out_a = gfx_pixel_rgba(pixels, GFX_W * 4, GFX_OUT_A_X, GFX_OUT_A_Y);
    out_b = gfx_pixel_rgba(pixels, GFX_W * 4, GFX_OUT_B_X, GFX_OUT_B_Y);

    verdict = gfx_verdict(API, in, out_a, out_b);

out:
    free(pixels);
    if (ctx) { p_wglMakeCurrent(NULL, NULL); p_wglDeleteContext(ctx); }
    if (dc) ReleaseDC(hwnd, dc);
    if (hwnd) DestroyWindow(hwnd);
    return verdict;
}

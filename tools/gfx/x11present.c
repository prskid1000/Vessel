// SPDX-License-Identifier: LGPL-2.1-or-later
// Part of Vessel.
//
// A Vulkan swapchain against Vessel's own X server, with no Wine anywhere.
//
// The D3D11 benchmark (tools/gfx/presentbench.c) has four layers under it —
// FEX, Wine, winevulkan, DXVK — and when it died inside
// `vkGetPhysicalDeviceSurfaceCapabilitiesKHR` with an access violation there
// was no way to say which of them was at fault. This probe is the same question
// with all four removed: a bionic ELF, running as the app's uid, that opens the
// app's X server over the abstract socket with the *same* libxcb the driver
// links against, creates a real window, and drives Turnip's X11 WSI directly.
//
// It answers three things nothing else can:
//
//   1. Does Turnip's x11 WSI work against this X server at all, and at which
//      call does it stop?
//   2. What does one flip actually cost, end to end, including the Java X
//      server's own work — the half tools/gfx/wsiprobe.c cannot see?
//   3. Does `MESA_VK_WSI_DEBUG=sw,linear` beat `sw`, measured rather than
//      reasoned?
//
// Output: one `VESSEL-X11PRESENT ` line per stage, and a timing line at the end.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <time.h>
#include <unistd.h>

#include <xcb/xcb.h>

#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_XCB_KHR
#include <vulkan/vulkan_core.h>
#include <vulkan/vulkan_xcb.h>

static int WIDTH = 1280, HEIGHT = 720, FRAMES = 300;

static double now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

static void fail(const char *stage, int code)
{
    printf("VESSEL-X11PRESENT result=FAIL stage=%s code=%d\n", stage, code);
    exit(1);
}

/* The loader, through libadrenotools, exactly as tools/gfx/wsiprobe.c does it.
 * Duplicated rather than shared because these two probes are built and pushed
 * independently and a shared object between them would be a third thing to
 * keep in step. */
static void *load_loader(void)
{
    const char *hooks = getenv("ADRENOTOOLS_HOOKS_PATH");
    const char *driver = getenv("ADRENOTOOLS_DRIVER_PATH");
    const char *name = getenv("ADRENOTOOLS_DRIVER_NAME");
    char path[1152];
    void *at, *handle;
    void *(*open_libvulkan)(int, int, const char *, const char *, const char *,
                            const char *, const char *, void **);

    /* VESSEL_VK_ICD is the whole point of this probe's second half: a Turnip
     * built with `-Dplatforms=x11` (no android) is an ordinary ICD with real
     * exports, so it can be driven with nothing between it and us. Android's
     * platform loader implements the WSI itself against ANativeWindow, and
     * handing it an X11 surface is a null dereference inside libvulkan.so — so
     * "no loader at all" is not a shortcut here, it is the only way the X11 WSI
     * can be reached on this device. */
    const char *icd = getenv("VESSEL_VK_ICD");
    if (icd && *icd) {
        void *h = dlopen(icd, RTLD_NOW | RTLD_LOCAL);
        if (!h) { printf("  dlopen(%s) failed: %s\n", icd, dlerror()); return NULL; }
        printf("VESSEL-X11PRESENT loader=icd path=%s\n", icd);
        return h;
    }

    if (!hooks || !driver || !name)
        return dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);

    snprintf(path, sizeof(path), "%s%slibadrenotools.so", hooks,
             hooks[strlen(hooks) - 1] == '/' ? "" : "/");
    at = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!at) at = dlopen("libadrenotools.so", RTLD_NOW | RTLD_LOCAL);
    if (!at) { printf("  no libadrenotools: %s\n", dlerror()); return dlopen("libvulkan.so", RTLD_NOW); }
    *(void **)&open_libvulkan = dlsym(at, "adrenotools_open_libvulkan");
    if (!open_libvulkan) return dlopen("libvulkan.so", RTLD_NOW);
    /* 1 == ADRENOTOOLS_DRIVER_CUSTOM */
    handle = open_libvulkan(RTLD_NOW, 1, NULL, hooks, driver, name, NULL, NULL);
    if (!handle) { printf("  adrenotools returned NULL\n"); return dlopen("libvulkan.so", RTLD_NOW); }
    return handle;
}

int main(int argc, char **argv)
{
    xcb_connection_t *conn;
    xcb_screen_t *screen;
    xcb_window_t window;
    void *lib;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkGetDeviceProcAddr gdpa;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice phys;
    VkDevice device = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkQueue queue;
    uint32_t queue_family = 0, count;
    VkResult r;
    const char *display = getenv("DISPLAY") ? getenv("DISPLAY") : ":0";

    setvbuf(stdout, NULL, _IOLBF, 0);
    for (int i = 1; i < argc; i++) {
        if (!strncmp(argv[i], "--frames=", 9)) FRAMES = atoi(argv[i] + 9);
        else if (!strncmp(argv[i], "--width=", 8)) WIDTH = atoi(argv[i] + 8);
        else if (!strncmp(argv[i], "--height=", 9)) HEIGHT = atoi(argv[i] + 9);
    }
    printf("VESSEL-X11PRESENT-INFO display=%s %dx%d frames=%d wsi=\"%s\"\n",
           display, WIDTH, HEIGHT, FRAMES,
           getenv("MESA_VK_WSI_DEBUG") ? getenv("MESA_VK_WSI_DEBUG") : "(unset)");

    /* --- the X server ------------------------------------------------------ */
    conn = xcb_connect(display, NULL);
    if (!conn || xcb_connection_has_error(conn))
        fail("xcb_connect", conn ? xcb_connection_has_error(conn) : -1);
    screen = xcb_setup_roots_iterator(xcb_get_setup(conn)).data;
    if (!screen) fail("no-screen", 0);
    printf("VESSEL-X11PRESENT connected root=0x%x %ux%u depth=%u\n",
           screen->root, screen->width_in_pixels, screen->height_in_pixels,
           screen->root_depth);

    window = xcb_generate_id(conn);
    {
        uint32_t mask = XCB_CW_BACK_PIXEL | XCB_CW_EVENT_MASK;
        uint32_t values[2] = { screen->black_pixel, XCB_EVENT_MASK_EXPOSURE };
        xcb_create_window(conn, XCB_COPY_FROM_PARENT, window, screen->root,
                          0, 0, (uint16_t)WIDTH, (uint16_t)HEIGHT, 0,
                          XCB_WINDOW_CLASS_INPUT_OUTPUT, screen->root_visual,
                          mask, values);
        xcb_map_window(conn, window);
        xcb_flush(conn);
    }
    printf("VESSEL-X11PRESENT window=0x%x mapped\n", window);

    /* --- Vulkan ------------------------------------------------------------ */
    lib = load_loader();
    if (!lib) fail("dlopen-vulkan", 0);
    gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
    if (!gipa) fail("no-gipa", 0);

#define IPA(n) ((PFN_##n)gipa(instance, #n))
    {
        PFN_vkEnumerateInstanceExtensionProperties enum_ext =
            (PFN_vkEnumerateInstanceExtensionProperties)gipa(NULL, "vkEnumerateInstanceExtensionProperties");
        uint32_t n = 0;
        int have_xcb = 0;
        enum_ext(NULL, &n, NULL);
        VkExtensionProperties *props = calloc(n, sizeof(*props));
        enum_ext(NULL, &n, props);
        for (uint32_t i = 0; i < n; i++)
            if (!strcmp(props[i].extensionName, VK_KHR_XCB_SURFACE_EXTENSION_NAME)) have_xcb = 1;
        printf("VESSEL-X11PRESENT instance_ext VK_KHR_xcb_surface=%s (%u total)\n",
               have_xcb ? "yes" : "NO", n);
        free(props);
        if (!have_xcb) fail("no-xcb-surface", 0);
    }

    {
        const char *exts[] = { VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_XCB_SURFACE_EXTENSION_NAME };
        VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                                  .pApplicationName = "vessel-x11present",
                                  .apiVersion = VK_API_VERSION_1_1 };
        VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                                     .pApplicationInfo = &app,
                                     .enabledExtensionCount = 2,
                                     .ppEnabledExtensionNames = exts };
        PFN_vkCreateInstance ci = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
        r = ci(&ici, NULL, &instance);
        if (r != VK_SUCCESS) fail("vkCreateInstance", r);
    }

    {
        VkPhysicalDevice devices[8];
        count = 8;
        if (IPA(vkEnumeratePhysicalDevices)(instance, &count, devices) != VK_SUCCESS || !count)
            fail("no-physical-device", 0);
        phys = devices[0];
        VkPhysicalDeviceDriverProperties dp = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES };
        VkPhysicalDeviceProperties2 p2 = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, .pNext = &dp };
        IPA(vkGetPhysicalDeviceProperties2)(phys, &p2);
        printf("VESSEL-X11PRESENT device=\"%s\" driver_id=%u info=\"%s\"\n",
               p2.properties.deviceName, dp.driverID, dp.driverInfo);
    }

    {
        VkXcbSurfaceCreateInfoKHR sci = { .sType = VK_STRUCTURE_TYPE_XCB_SURFACE_CREATE_INFO_KHR,
                                          .connection = conn, .window = window };
        r = IPA(vkCreateXcbSurfaceKHR)(instance, &sci, NULL, &surface);
        if (r != VK_SUCCESS) fail("vkCreateXcbSurfaceKHR", r);
        printf("VESSEL-X11PRESENT surface created\n");
    }

    {
        VkQueueFamilyProperties fam[16];
        uint32_t nfam = 16;
        IPA(vkGetPhysicalDeviceQueueFamilyProperties)(phys, &nfam, fam);
        for (uint32_t i = 0; i < nfam; i++) {
            VkBool32 sup = VK_FALSE;
            IPA(vkGetPhysicalDeviceSurfaceSupportKHR)(phys, i, surface, &sup);
            printf("VESSEL-X11PRESENT queue %u graphics=%d present=%d\n", i,
                   !!(fam[i].queueFlags & VK_QUEUE_GRAPHICS_BIT), sup);
            if ((fam[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && sup) { queue_family = i; break; }
        }
    }

    /* The call the D3D11 run died in. Reaching past it here says the fault is
     * Wine's plumbing; dying here says it is Mesa's X11 WSI. */
    VkSurfaceCapabilitiesKHR caps;
    r = IPA(vkGetPhysicalDeviceSurfaceCapabilitiesKHR)(phys, surface, &caps);
    if (r != VK_SUCCESS) fail("vkGetPhysicalDeviceSurfaceCapabilitiesKHR", r);
    printf("VESSEL-X11PRESENT caps extent=%ux%u minImageCount=%u maxImageCount=%u usage=0x%x\n",
           caps.currentExtent.width, caps.currentExtent.height,
           caps.minImageCount, caps.maxImageCount, caps.supportedUsageFlags);

    VkSurfaceFormatKHR format = { VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR };
    {
        VkSurfaceFormatKHR formats[32];
        count = 32;
        IPA(vkGetPhysicalDeviceSurfaceFormatsKHR)(phys, surface, &count, formats);
        printf("VESSEL-X11PRESENT formats=%u first=%d\n", count, count ? formats[0].format : -1);
        if (count) format = formats[0];
    }

    {
        float pri = 1.0f;
        const char *dexts[] = { VK_KHR_SWAPCHAIN_EXTENSION_NAME };
        VkDeviceQueueCreateInfo q = { .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                      .queueFamilyIndex = queue_family, .queueCount = 1,
                                      .pQueuePriorities = &pri };
        VkDeviceCreateInfo dci = { .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                                   .queueCreateInfoCount = 1, .pQueueCreateInfos = &q,
                                   .enabledExtensionCount = 1, .ppEnabledExtensionNames = dexts };
        r = IPA(vkCreateDevice)(phys, &dci, NULL, &device);
        if (r != VK_SUCCESS) fail("vkCreateDevice", r);
    }
    gdpa = IPA(vkGetDeviceProcAddr);
#define DPA(n) ((PFN_##n)gdpa(device, #n))
    DPA(vkGetDeviceQueue)(device, queue_family, 0, &queue);

    {
        uint32_t want = caps.minImageCount < 2 ? 2 : caps.minImageCount;
        if (caps.maxImageCount && want > caps.maxImageCount) want = caps.maxImageCount;
        VkSwapchainCreateInfoKHR sci = {
            .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
            .surface = surface,
            .minImageCount = want,
            .imageFormat = format.format,
            .imageColorSpace = format.colorSpace,
            .imageExtent = { (uint32_t)WIDTH, (uint32_t)HEIGHT },
            .imageArrayLayers = 1,
            .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
            .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
            .preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR,
            .compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
            .presentMode = VK_PRESENT_MODE_IMMEDIATE_KHR,
            .clipped = VK_TRUE,
        };
        r = DPA(vkCreateSwapchainKHR)(device, &sci, NULL, &swapchain);
        if (r != VK_SUCCESS) {
            /* IMMEDIATE may not be offered; FIFO always is. */
            sci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
            r = DPA(vkCreateSwapchainKHR)(device, &sci, NULL, &swapchain);
        }
        if (r != VK_SUCCESS) fail("vkCreateSwapchainKHR", r);
        printf("VESSEL-X11PRESENT swapchain created images=%u mode=%d\n", want, sci.presentMode);
    }

    VkImage images[8];
    count = 8;
    DPA(vkGetSwapchainImagesKHR)(device, swapchain, &count, images);
    printf("VESSEL-X11PRESENT swapchain images=%u\n", count);

    /* --- the flip loop ------------------------------------------------------ */
    //
    // One clear per frame, submitted and waited on, then presented. Deliberately
    // the smallest possible amount of GPU work: what is being timed is the
    // present, and any real rendering would hide it.
    VkCommandPool pool;
    VkCommandBuffer cb[8];
    VkSemaphore acquired, rendered;
    {
        VkCommandPoolCreateInfo pci = { .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
                                        .queueFamilyIndex = queue_family };
        DPA(vkCreateCommandPool)(device, &pci, NULL, &pool);
        VkCommandBufferAllocateInfo ai = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                                           .commandPool = pool,
                                           .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                                           .commandBufferCount = count };
        DPA(vkAllocateCommandBuffers)(device, &ai, cb);
        VkSemaphoreCreateInfo si = { .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
        DPA(vkCreateSemaphore)(device, &si, NULL, &acquired);
        DPA(vkCreateSemaphore)(device, &si, NULL, &rendered);
    }

    double *samples = calloc((size_t)FRAMES, sizeof(double));
    double total = 0, lo = 1e30, hi = 0;
    const int warmup = 20;

    for (int f = 0; f < warmup + FRAMES; f++) {
        uint32_t idx = 0;
        double t0 = now_ms();

        r = DPA(vkAcquireNextImageKHR)(device, swapchain, UINT64_MAX, acquired, VK_NULL_HANDLE, &idx);
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) fail("vkAcquireNextImageKHR", r);

        VkCommandBufferBeginInfo bi = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT };
        DPA(vkBeginCommandBuffer)(cb[idx], &bi);
        VkImageSubresourceRange range = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
        VkImageMemoryBarrier to_dst = { .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED, .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = images[idx], .subresourceRange = range,
            .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT };
        DPA(vkCmdPipelineBarrier)(cb[idx], VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                  VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &to_dst);
        /* A different colour every frame so nothing downstream can decide the
         * frame is unchanged and skip the work being measured. */
        VkClearColorValue colour = { .float32 = { (f % 60) / 60.0f, ((f * 7) % 60) / 60.0f,
                                                  ((f * 13) % 60) / 60.0f, 1.0f } };
        DPA(vkCmdClearColorImage)(cb[idx], images[idx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                  &colour, 1, &range);
        VkImageMemoryBarrier to_present = to_dst;
        to_present.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        to_present.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        to_present.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        to_present.dstAccessMask = 0;
        DPA(vkCmdPipelineBarrier)(cb[idx], VK_PIPELINE_STAGE_TRANSFER_BIT,
                                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, NULL, 0, NULL, 1, &to_present);
        DPA(vkEndCommandBuffer)(cb[idx]);

        VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
            .waitSemaphoreCount = 1, .pWaitSemaphores = &acquired, .pWaitDstStageMask = &wait_stage,
            .commandBufferCount = 1, .pCommandBuffers = &cb[idx],
            .signalSemaphoreCount = 1, .pSignalSemaphores = &rendered };
        DPA(vkQueueSubmit)(queue, 1, &submit, VK_NULL_HANDLE);

        VkPresentInfoKHR pi = { .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
            .waitSemaphoreCount = 1, .pWaitSemaphores = &rendered,
            .swapchainCount = 1, .pSwapchains = &swapchain, .pImageIndices = &idx };
        r = DPA(vkQueuePresentKHR)(queue, &pi);
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) fail("vkQueuePresentKHR", r);
        DPA(vkQueueWaitIdle)(queue);

        double ms = now_ms() - t0;
        if (f == 0) printf("VESSEL-X11PRESENT-INFO first_frame_ms=%.2f\n", ms);
        if (f >= warmup) {
            samples[f - warmup] = ms;
            total += ms;
            if (ms < lo) lo = ms;
            if (ms > hi) hi = ms;
        }
    }

    for (int i = 1; i < FRAMES; i++) {           /* insertion sort, FRAMES is small */
        double v = samples[i]; int j = i - 1;
        while (j >= 0 && samples[j] > v) { samples[j + 1] = samples[j]; j--; }
        samples[j + 1] = v;
    }
    printf("VESSEL-X11PRESENT result=PASS wsi=\"%s\" %dx%d frames=%d "
           "mean_ms=%.3f p50_ms=%.3f p95_ms=%.3f min_ms=%.3f max_ms=%.3f fps=%.1f\n",
           getenv("MESA_VK_WSI_DEBUG") ? getenv("MESA_VK_WSI_DEBUG") : "(unset)",
           WIDTH, HEIGHT, FRAMES, total / FRAMES, samples[FRAMES / 2],
           samples[(int)(FRAMES * 0.95)], lo, hi, 1000.0 / (total / FRAMES));

    /* Left standing on purpose: tearing the swapchain down is a separate code
     * path with its own failure modes, and the numbers above are the point. */
    return 0;
}

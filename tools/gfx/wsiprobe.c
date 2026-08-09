// SPDX-License-Identifier: LGPL-2.1-or-later
// Part of Vessel.
//
// The measurements that decide how Vessel presents a frame.
//
// Today every flip copies a whole frame on the CPU: Mesa's X11 WSI is compiled
// with only its software half, so `MESA_VK_WSI_DEBUG=sw` selects
// x11_present_to_x11_sw(), which xcb_put_image()s a mapped host buffer. Whether
// that is merely slow or catastrophic, and whether either zero-copy route is
// open at all, comes down to four facts about *this* device that nobody had
// measured:
//
//   1. Can the app's uid open and allocate from /dev/dma_heap/system? If not,
//      "the client allocates and exports" is dead, because that is the only
//      direction KGSL supports (kgsl_bo_export_dmabuf can only re-export an fd
//      it imported; VkExportMemoryAllocateInfo goes through the dma-heap).
//   2. Does Turnip expose a memory type that is both HOST_COHERENT and
//      HOST_CACHED? wsi_select_host_memory_type() asks for exactly that pair
//      when wsi->sw is set. If no type has HOST_CACHED, the CPU read that
//      xcb_put_image performs is uncached and the sw path can never be fast.
//   3. What does a full-frame CPU read of that memory actually cost at
//      1280x720? Every later claim needs this baseline.
//   4. Can Turnip import an AHardwareBuffer's dma-buf fd and bind a LINEAR
//      image to it? That is the whole of the BufferFromPixmap route, and the X
//      server side of it already exists.
//
// Run as the app's uid, through /system/bin/linker64, the same shape as the
// Wine unix side — see tools/device-vulkan.sh for why that distinction matters.
//
// Every result is one `VESSEL-WSI ` line so a script can grep it, plus human
// text. Exit status is 0 if the probe ran to completion, 2 if it could not get
// a Vulkan device at all; individual measurements failing is a *result*, not an
// error.

#include <errno.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>

#include <android/hardware_buffer.h>
#include <linux/dma-heap.h>

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>

/* AHardwareBuffer_getNativeHandle is in libandroid but not in the NDK headers;
 * gpu_image.c in the vendored X server declares it the same way. This probe
 * needs it for the same reason DRI3 BufferFromPixmap does: the dma-buf fd is
 * the first fd of the native handle and there is no public accessor. */
struct vessel_native_handle {
    int version;
    int numFds;
    int numInts;
    int data[0];
};
extern const struct vessel_native_handle *AHardwareBuffer_getNativeHandle(
    const AHardwareBuffer *buffer);

#define WIDTH 1280
#define HEIGHT 720
#define BPP 4

/* ---------------------------------------------------------------- reporting */

static int failures = 0;

static void head(const char *title)
{
    printf("\n== %s ==\n", title);
}

static double now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

/* -------------------------------------------------------------- 1. dma-heap */

/* The heaps Turnip's tu_knl_kgsl.cc tries, in its order. */
static const char *const HEAPS[] = {
    "/dev/dma_heap/system",
    "/dev/dma_heap/qcom,system",
    "/dev/ion",
};

static int probe_dma_heaps(void)
{
    int exported_fd = -1;

    head("1. /dev/dma_heap — can this uid allocate a dma-buf at all?");

    for (size_t i = 0; i < sizeof(HEAPS) / sizeof(HEAPS[0]); i++) {
        const char *path = HEAPS[i];
        int fd, rc;
        struct dma_heap_allocation_data alloc;

        /* O_RDONLY is what Mesa uses; O_RDWR is tried too so a permissions
         * failure can be told apart from a mode failure. */
        fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            int rdonly_errno = errno;
            int fd2 = open(path, O_RDWR | O_CLOEXEC);
            if (fd2 < 0) {
                printf("VESSEL-WSI dmaheap path=%s open=FAIL rdonly_errno=%d(%s) "
                       "rdwr_errno=%d(%s)\n",
                       path, rdonly_errno, strerror(rdonly_errno), errno, strerror(errno));
                continue;
            }
            fd = fd2;
            printf("  %s opened O_RDWR only (O_RDONLY: %s)\n", path, strerror(rdonly_errno));
        }

        memset(&alloc, 0, sizeof(alloc));
        alloc.len = (uint64_t)WIDTH * HEIGHT * BPP;
        alloc.fd_flags = O_RDWR | O_CLOEXEC;
        rc = ioctl(fd, DMA_HEAP_IOCTL_ALLOC, &alloc);
        if (rc < 0) {
            printf("VESSEL-WSI dmaheap path=%s open=OK alloc=FAIL errno=%d(%s)\n",
                   path, errno, strerror(errno));
            close(fd);
            continue;
        }

        printf("VESSEL-WSI dmaheap path=%s open=OK alloc=OK bytes=%llu fd=%d\n",
               path, (unsigned long long)alloc.len, (int)alloc.fd);
        if (exported_fd < 0)
            exported_fd = (int)alloc.fd;
        else
            close((int)alloc.fd);
        close(fd);
    }

    if (exported_fd < 0)
        printf("  no heap allocated. VkExportMemoryAllocateInfo cannot work for this uid,\n"
               "  so 'the Vulkan client allocates and the X server imports' is closed.\n");
    return exported_fd;
}

/* ------------------------------------------------- 2. AHardwareBuffer shapes */

struct ahb_result {
    AHardwareBuffer *buffer;
    uint32_t stride;
    int fd;
};

static void probe_ahb(const char *label, uint64_t usage, uint32_t format,
                      struct ahb_result *out)
{
    AHardwareBuffer_Desc desc;
    AHardwareBuffer *buffer = NULL;
    AHardwareBuffer_Desc got;
    const struct vessel_native_handle *handle;
    int rc;

    memset(out, 0, sizeof(*out));
    out->fd = -1;

    memset(&desc, 0, sizeof(desc));
    desc.width = WIDTH;
    desc.height = HEIGHT;
    desc.layers = 1;
    desc.format = format;
    desc.usage = usage;

    rc = AHardwareBuffer_allocate(&desc, &buffer);
    if (rc != 0 || !buffer) {
        printf("VESSEL-WSI ahb %s alloc=FAIL rc=%d\n", label, rc);
        return;
    }

    memset(&got, 0, sizeof(got));
    AHardwareBuffer_describe(buffer, &got);

    handle = AHardwareBuffer_getNativeHandle(buffer);
    printf("VESSEL-WSI ahb %s alloc=OK stride=%u fds=%d ints=%d packed=%s\n",
           label, got.stride, handle ? handle->numFds : -1,
           handle ? handle->numInts : -1,
           got.stride == WIDTH ? "tight" : "padded");

    if (handle && handle->numFds > 0) {
        out->fd = handle->data[0];
        /* The size of the underlying dma-buf says whether gralloc gave us a
         * plain linear surface or a compressed (UBWC) one with metadata: a
         * linear RGBA 1280x720 is stride*height*4 exactly. */
        off_t size = lseek(out->fd, 0, SEEK_END);
        printf("  %s dmabuf fd=%d size=%lld (linear would be %lld)\n", label,
               out->fd, (long long)size,
               (long long)got.stride * HEIGHT * BPP);
    }

    out->buffer = buffer;
    out->stride = got.stride;
}

/* ------------------------------------------------------------- 3. Vulkan */

#define VK_FN(name) PFN_##name name

struct vk {
    void *lib;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkGetDeviceProcAddr gdpa;
    VkInstance instance;
    VkPhysicalDevice phys;
    VkDevice device;
    uint32_t queue_family;
    VkQueue queue;
    VkPhysicalDeviceMemoryProperties mem;

    VK_FN(vkCreateDevice);
    VK_FN(vkAllocateMemory);
    VK_FN(vkFreeMemory);
    VK_FN(vkMapMemory);
    VK_FN(vkUnmapMemory);
    VK_FN(vkCreateImage);
    VK_FN(vkDestroyImage);
    VK_FN(vkGetImageMemoryRequirements);
    VK_FN(vkGetImageSubresourceLayout);
    VK_FN(vkBindImageMemory);
    VK_FN(vkCreateBuffer);
    VK_FN(vkDestroyBuffer);
    VK_FN(vkGetBufferMemoryRequirements);
    VK_FN(vkBindBufferMemory);
    VK_FN(vkCreateCommandPool);
    VK_FN(vkAllocateCommandBuffers);
    VK_FN(vkBeginCommandBuffer);
    VK_FN(vkEndCommandBuffer);
    VK_FN(vkCmdPipelineBarrier);
    VK_FN(vkCmdCopyImageToBuffer);
    VK_FN(vkQueueSubmit);
    VK_FN(vkQueueWaitIdle);
    VK_FN(vkGetDeviceQueue);
};

static const char *mem_flag_string(VkMemoryPropertyFlags f, char *buf, size_t n)
{
    buf[0] = '\0';
    if (f & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)  strncat(buf, "DEVICE_LOCAL|", n - strlen(buf) - 1);
    if (f & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)  strncat(buf, "HOST_VISIBLE|", n - strlen(buf) - 1);
    if (f & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) strncat(buf, "HOST_COHERENT|", n - strlen(buf) - 1);
    if (f & VK_MEMORY_PROPERTY_HOST_CACHED_BIT)   strncat(buf, "HOST_CACHED|", n - strlen(buf) - 1);
    if (f & VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT) strncat(buf, "LAZY|", n - strlen(buf) - 1);
    if (!buf[0]) snprintf(buf, n, "(none)");
    else buf[strlen(buf) - 1] = '\0';
    return buf;
}

/* Mesa's wsi_select_memory_type, reimplemented so the answer here is the answer
 * the WSI would get rather than a guess at it. */
static int select_memory_type(const struct vk *vk, VkMemoryPropertyFlags req,
                              VkMemoryPropertyFlags deny, uint32_t type_bits)
{
    for (uint32_t i = 0; i < vk->mem.memoryTypeCount; i++) {
        if (!(type_bits & (1u << i))) continue;
        VkMemoryPropertyFlags f = vk->mem.memoryTypes[i].propertyFlags;
        if ((f & req) != req) continue;
        if (f & deny) continue;
        return (int)i;
    }
    return -1;
}

static void *load_loader(void)
{
    const char *hooks = getenv("ADRENOTOOLS_HOOKS_PATH");
    const char *driver = getenv("ADRENOTOOLS_DRIVER_PATH");
    const char *name = getenv("ADRENOTOOLS_DRIVER_NAME");
    char path[1152];
    void *at, *handle;
    void *(*open_libvulkan)(int, int, const char *, const char *, const char *,
                            const char *, const char *, void **);

    if (!hooks || !driver || !name || !*hooks || !*driver || !*name) {
        printf("  no ADRENOTOOLS_* in the environment; falling back to the system loader\n");
        return dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    }

    snprintf(path, sizeof(path), "%s%slibadrenotools.so", hooks,
             hooks[strlen(hooks) - 1] == '/' ? "" : "/");
    at = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!at) at = dlopen("libadrenotools.so", RTLD_NOW | RTLD_LOCAL);
    if (!at) {
        printf("  dlopen(%s) failed: %s — using the system loader\n", path, dlerror());
        return dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    }

    *(void **)&open_libvulkan = dlsym(at, "adrenotools_open_libvulkan");
    if (!open_libvulkan) {
        printf("  libadrenotools exports no adrenotools_open_libvulkan\n");
        return dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    }

    /* 1 == ADRENOTOOLS_DRIVER_CUSTOM (adrenotools/priv.h), spelled out so this
     * file does not need the adrenotools headers. Getting the value wrong is
     * not a loud failure: driver.cpp refuses a customDriverDir with the flag
     * clear and returns nullptr, which reads exactly like a broken driver. */
    handle = open_libvulkan(RTLD_NOW, 1, NULL, hooks, driver, name, NULL, NULL);
    if (!handle) {
        printf("  adrenotools_open_libvulkan returned NULL — using the system loader\n");
        return dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    }
    return handle;
}

static int vk_init(struct vk *vk)
{
    VkApplicationInfo app;
    VkInstanceCreateInfo ici;
    VkDeviceQueueCreateInfo qci;
    VkDeviceCreateInfo dci;
    VkPhysicalDevice devices[8];
    VkPhysicalDeviceProperties2 props2;
    VkPhysicalDeviceDriverProperties driver_props;
    VkQueueFamilyProperties families[16];
    uint32_t count, family_count = 16;
    uint32_t ext_count = 0;
    VkExtensionProperties *exts = NULL;
    float priority = 1.0f;
    const char *want[] = {
        VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
        VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME,
        VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME,
        /* Spelled out rather than taken from the macro: that one lives in
         * vulkan_android.h, which only appears with VK_USE_PLATFORM_ANDROID_KHR,
         * and pulling the Android platform header in would drag in a surface
         * API this probe has no use for. */
        "VK_ANDROID_external_memory_android_hardware_buffer",
        VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
        VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
        VK_KHR_MAINTENANCE_1_EXTENSION_NAME,
        VK_KHR_BIND_MEMORY_2_EXTENSION_NAME,
        VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
        VK_EXT_EXTERNAL_MEMORY_HOST_EXTENSION_NAME,
    };
    const char *enable[16];
    uint32_t enable_count = 0;
    PFN_vkEnumeratePhysicalDevices enum_devices;
    PFN_vkGetPhysicalDeviceProperties2 get_props2;
    PFN_vkGetPhysicalDeviceMemoryProperties get_mem;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties get_families;
    PFN_vkEnumerateDeviceExtensionProperties enum_exts;
    PFN_vkCreateInstance create_instance;
    PFN_vkEnumerateInstanceVersion enum_version;
    uint32_t api = VK_API_VERSION_1_0;

    memset(vk, 0, sizeof(*vk));

    vk->lib = load_loader();
    if (!vk->lib) { printf("  no Vulkan loader: %s\n", dlerror()); return 0; }

    vk->gipa = (PFN_vkGetInstanceProcAddr)dlsym(vk->lib, "vkGetInstanceProcAddr");
    if (!vk->gipa) { printf("  loader exports no vkGetInstanceProcAddr\n"); return 0; }

    enum_version = (PFN_vkEnumerateInstanceVersion)vk->gipa(NULL, "vkEnumerateInstanceVersion");
    if (enum_version) enum_version(&api);

    memset(&app, 0, sizeof(app));
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "vessel-wsiprobe";
    app.apiVersion = api;

    memset(&ici, 0, sizeof(ici));
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo = &app;

    create_instance = (PFN_vkCreateInstance)vk->gipa(NULL, "vkCreateInstance");
    if (create_instance(&ici, NULL, &vk->instance) != VK_SUCCESS) {
        printf("  vkCreateInstance failed\n");
        return 0;
    }

    enum_devices = (PFN_vkEnumeratePhysicalDevices)vk->gipa(vk->instance, "vkEnumeratePhysicalDevices");
    count = 8;
    if (enum_devices(vk->instance, &count, devices) != VK_SUCCESS || !count) {
        printf("  no physical device\n");
        return 0;
    }
    vk->phys = devices[0];

    get_props2 = (PFN_vkGetPhysicalDeviceProperties2)vk->gipa(vk->instance, "vkGetPhysicalDeviceProperties2");
    memset(&driver_props, 0, sizeof(driver_props));
    driver_props.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
    memset(&props2, 0, sizeof(props2));
    props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
    props2.pNext = &driver_props;
    if (get_props2) get_props2(vk->phys, &props2);

    printf("VESSEL-WSI device name=\"%s\" driver_id=%u driver=\"%s\" info=\"%s\" "
           "api=%u.%u.%u optimalBufferCopyRowPitchAlignment=%llu nonCoherentAtomSize=%llu\n",
           props2.properties.deviceName, driver_props.driverID,
           driver_props.driverName, driver_props.driverInfo,
           VK_API_VERSION_MAJOR(props2.properties.apiVersion),
           VK_API_VERSION_MINOR(props2.properties.apiVersion),
           VK_API_VERSION_PATCH(props2.properties.apiVersion),
           (unsigned long long)props2.properties.limits.optimalBufferCopyRowPitchAlignment,
           (unsigned long long)props2.properties.limits.nonCoherentAtomSize);

    get_mem = (PFN_vkGetPhysicalDeviceMemoryProperties)vk->gipa(vk->instance, "vkGetPhysicalDeviceMemoryProperties");
    get_mem(vk->phys, &vk->mem);

    get_families = (PFN_vkGetPhysicalDeviceQueueFamilyProperties)vk->gipa(vk->instance, "vkGetPhysicalDeviceQueueFamilyProperties");
    get_families(vk->phys, &family_count, families);
    vk->queue_family = 0;
    for (uint32_t i = 0; i < family_count; i++)
        if (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { vk->queue_family = i; break; }

    /* Which of the extensions the two candidate routes need are actually here. */
    enum_exts = (PFN_vkEnumerateDeviceExtensionProperties)vk->gipa(vk->instance, "vkEnumerateDeviceExtensionProperties");
    enum_exts(vk->phys, NULL, &ext_count, NULL);
    exts = calloc(ext_count, sizeof(*exts));
    enum_exts(vk->phys, NULL, &ext_count, exts);
    for (size_t i = 0; i < sizeof(want) / sizeof(want[0]); i++) {
        int found = 0;
        for (uint32_t j = 0; j < ext_count; j++)
            if (!strcmp(exts[j].extensionName, want[i])) { found = 1; break; }
        printf("VESSEL-WSI ext %-58s %s\n", want[i], found ? "yes" : "NO");
        if (found && enable_count < 16) enable[enable_count++] = want[i];
    }
    free(exts);

    memset(&qci, 0, sizeof(qci));
    qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = vk->queue_family;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;

    memset(&dci, 0, sizeof(dci));
    dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = enable_count;
    dci.ppEnabledExtensionNames = enable;

    vk->vkCreateDevice = (PFN_vkCreateDevice)vk->gipa(vk->instance, "vkCreateDevice");
    if (vk->vkCreateDevice(vk->phys, &dci, NULL, &vk->device) != VK_SUCCESS) {
        printf("  vkCreateDevice failed\n");
        return 0;
    }

    vk->gdpa = (PFN_vkGetDeviceProcAddr)vk->gipa(vk->instance, "vkGetDeviceProcAddr");
#define LOAD(n) vk->n = (PFN_##n)vk->gdpa(vk->device, #n)
    LOAD(vkAllocateMemory); LOAD(vkFreeMemory); LOAD(vkMapMemory); LOAD(vkUnmapMemory);
    LOAD(vkCreateImage); LOAD(vkDestroyImage); LOAD(vkGetImageMemoryRequirements);
    LOAD(vkGetImageSubresourceLayout); LOAD(vkBindImageMemory);
    LOAD(vkCreateBuffer); LOAD(vkDestroyBuffer); LOAD(vkGetBufferMemoryRequirements);
    LOAD(vkBindBufferMemory); LOAD(vkCreateCommandPool); LOAD(vkAllocateCommandBuffers);
    LOAD(vkBeginCommandBuffer); LOAD(vkEndCommandBuffer); LOAD(vkCmdPipelineBarrier);
    LOAD(vkCmdCopyImageToBuffer); LOAD(vkQueueSubmit); LOAD(vkQueueWaitIdle);
    LOAD(vkGetDeviceQueue);
#undef LOAD
    vk->vkGetDeviceQueue(vk->device, vk->queue_family, 0, &vk->queue);
    return 1;
}

/* ------------------------------------------------------------- bandwidth */

/* The three motions the sw present path makes over a frame, timed separately so
 * the expensive one is named rather than inferred:
 *
 *   write   the application rendering into a mapped linear image (the
 *           `sw,linear` case) — or the GPU writing it, which this cannot time.
 *   read    what xcb_put_image does: it hands libxcb a pointer, and the whole
 *           frame is read out of that mapping on its way into the socket.
 *   copy    read + write to ordinary heap memory, which is what a staging copy
 *           costs if one is added.
 */

/* Nothing here may be optimised away, and nothing may be measured warm.
 *
 * Both traps were live in the first version of this file and both produced
 * flattering nonsense: clang deleted a memcpy into a malloc'd buffer nothing
 * read, giving "354 GB/s", and a 3.6 MB frame re-read five times in a row sits
 * in the system-level cache, which no real flip ever gets. So the destination is
 * consumed, and a scratch walk larger than any cache on this part is done
 * *outside* the timed region before each cold pass. */
#define EVICT_BYTES (64u << 20)
static uint8_t *evict_buf;
static volatile uint64_t global_sink;

static void evict_caches(void)
{
    if (!evict_buf) {
        evict_buf = malloc(EVICT_BYTES);
        if (!evict_buf) return;
        memset(evict_buf, 1, EVICT_BYTES);
    }
    uint64_t acc = 0;
    const uint64_t *p = (const uint64_t *)evict_buf;
    for (size_t i = 0; i < EVICT_BYTES / 8; i += 8) acc += p[i];
    global_sink += acc;
}

static double time_reps(int reps, void (*body)(void *), void *ctx, int cold)
{
    double best = 1e30;
    for (int r = 0; r < reps; r++) {
        if (cold) evict_caches();
        double t0 = now_ms();
        body(ctx);
        double t1 = now_ms();
        if (t1 - t0 < best) best = t1 - t0;
    }
    return best;
}

struct bw_ctx { volatile void *map; void *heap; size_t bytes; };

static void body_write(void *v)
{
    struct bw_ctx *c = v;
    memset((void *)c->map, 0x5a, c->bytes);
}

static void body_read(void *v)
{
    struct bw_ctx *c = v;
    const volatile uint64_t *p = (const volatile uint64_t *)c->map;
    uint64_t acc = 0;
    for (size_t i = 0; i < c->bytes / 8; i++) acc += p[i];
    global_sink += acc;
}

static void body_copyout(void *v)
{
    struct bw_ctx *c = v;
    memcpy(c->heap, (const void *)c->map, c->bytes);
    /* Consumed, or the whole copy is dead code. */
    global_sink += ((const uint64_t *)c->heap)[c->bytes / 8 - 1];
}

static void report_bw(const char *label, const char *what, double ms, size_t bytes)
{
    printf("VESSEL-WSI bw %s %s ms=%.2f MBps=%.0f\n", label, what, ms,
           (double)bytes / (ms / 1000.0) / 1e6);
}

static void bandwidth(const char *label, volatile void *map, size_t bytes)
{
    struct bw_ctx c = { map, malloc(bytes), bytes };
    const int reps = 7;

    if (!c.heap) return;

    /* Fault every page in first: that is a one-off cost, not what a steady-state
     * flip pays, and leaving it in the first sample would swamp everything. */
    memset((void *)map, 0x5a, bytes);

    report_bw(label, "write",       time_reps(reps, body_write,   &c, 0), bytes);
    report_bw(label, "read_warm",   time_reps(reps, body_read,    &c, 0), bytes);
    report_bw(label, "read_cold",   time_reps(reps, body_read,    &c, 1), bytes);
    report_bw(label, "copyout_cold",time_reps(reps, body_copyout, &c, 1), bytes);

    free(c.heap);
}

/* ------------------------------------------------------- linear image test */

static void probe_linear_image(struct vk *vk)
{
    VkImageCreateInfo ici;
    VkImage image = VK_NULL_HANDLE;
    VkMemoryRequirements reqs;
    VkSubresourceLayout layout;
    VkMemoryAllocateInfo mai;
    VkMemoryDedicatedAllocateInfo dedicated;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void *map = NULL;
    int host_type, coherent_only;
    char flags[128];
    VkResult r;

    head("4. MESA_VK_WSI_DEBUG=sw,linear — a LINEAR colour attachment, mapped");

    memset(&ici, 0, sizeof(ici));
    ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = VK_FORMAT_B8G8R8A8_UNORM;
    ici.extent.width = WIDTH;
    ici.extent.height = HEIGHT;
    ici.extent.depth = 1;
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    /* Exactly what the WSI asks for: LINEAR tiling, and the usage set a
     * swapchain image carries once wsi_configure_cpu_image has forced it. */
    ici.tiling = VK_IMAGE_TILING_LINEAR;
    ici.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    r = vk->vkCreateImage(vk->device, &ici, NULL, &image);
    if (r != VK_SUCCESS) {
        printf("VESSEL-WSI linear create=FAIL VkResult=%d\n", (int)r);
        printf("  sw,linear cannot work: Turnip will not make a linear colour attachment.\n");
        failures++;
        return;
    }

    vk->vkGetImageMemoryRequirements(vk->device, image, &reqs);
    vk->vkGetImageSubresourceLayout(vk->device, image,
        &(VkImageSubresource){ .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT }, &layout);

    /* wsi_select_host_memory_type with wsi->sw set. */
    host_type = select_memory_type(vk, VK_MEMORY_PROPERTY_HOST_COHERENT_BIT |
                                       VK_MEMORY_PROPERTY_HOST_CACHED_BIT, 0,
                                   reqs.memoryTypeBits);
    coherent_only = select_memory_type(vk, VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0,
                                       reqs.memoryTypeBits);

    printf("VESSEL-WSI linear create=OK size=%llu align=%llu typeBits=0x%x "
           "rowPitch=%llu offset=%llu wsi_host_type=%d coherent_type=%d\n",
           (unsigned long long)reqs.size, (unsigned long long)reqs.alignment,
           reqs.memoryTypeBits, (unsigned long long)layout.rowPitch,
           (unsigned long long)layout.offset, host_type, coherent_only);

    if (host_type < 0) {
        printf("  no HOST_COHERENT+HOST_CACHED type accepts this image. "
               "wsi_select_host_memory_type would return -1 and the WSI would fall over.\n");
        host_type = coherent_only;
        if (host_type < 0) { vk->vkDestroyImage(vk->device, image, NULL); failures++; return; }
        printf("  falling back to type %d for the measurement below (%s)\n", host_type,
               mem_flag_string(vk->mem.memoryTypes[host_type].propertyFlags, flags, sizeof(flags)));
    }

    memset(&dedicated, 0, sizeof(dedicated));
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.image = image;
    memset(&mai, 0, sizeof(mai));
    mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.pNext = &dedicated;
    mai.allocationSize = reqs.size;
    mai.memoryTypeIndex = (uint32_t)host_type;

    r = vk->vkAllocateMemory(vk->device, &mai, NULL, &memory);
    if (r != VK_SUCCESS) {
        printf("VESSEL-WSI linear alloc=FAIL VkResult=%d\n", (int)r);
        vk->vkDestroyImage(vk->device, image, NULL);
        failures++;
        return;
    }
    if (vk->vkBindImageMemory(vk->device, image, memory, 0) != VK_SUCCESS) {
        printf("VESSEL-WSI linear bind=FAIL\n");
        failures++;
    }
    if (vk->vkMapMemory(vk->device, memory, 0, VK_WHOLE_SIZE, 0, &map) != VK_SUCCESS) {
        printf("VESSEL-WSI linear map=FAIL\n");
        failures++;
    } else {
        printf("  mapped %llu bytes of memory type %d (%s)\n",
               (unsigned long long)reqs.size, host_type,
               mem_flag_string(vk->mem.memoryTypes[host_type].propertyFlags, flags, sizeof(flags)));
        /* Only the visible part of the frame is what put_image reads. */
        bandwidth("linear", map, (size_t)layout.rowPitch * HEIGHT);
        vk->vkUnmapMemory(vk->device, memory);
    }

    vk->vkFreeMemory(vk->device, memory, NULL);
    vk->vkDestroyImage(vk->device, image, NULL);
}

/* --------------------------------------------------- today's shipping path */

static void probe_buffer_blit(struct vk *vk)
{
    VkImageCreateInfo ici;
    VkBufferCreateInfo bci;
    VkImage image = VK_NULL_HANDLE;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkMemoryRequirements ireqs, breqs;
    VkMemoryAllocateInfo mai;
    VkDeviceMemory imem = VK_NULL_HANDLE, bmem = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBufferAllocateInfo cbai;
    VkCommandBuffer cb = VK_NULL_HANDLE;
    VkCommandBufferBeginInfo cbbi;
    VkBufferImageCopy region;
    VkImageMemoryBarrier barrier;
    VkSubmitInfo submit;
    void *map = NULL;
    int dev_type, host_type;
    char flags[128];
    double t0, t1;
    const int reps = 20;

    head("5. what ships today — OPTIMAL image, GPU blit into a mapped buffer");

    memset(&ici, 0, sizeof(ici));
    ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = VK_FORMAT_B8G8R8A8_UNORM;
    ici.extent.width = WIDTH; ici.extent.height = HEIGHT; ici.extent.depth = 1;
    ici.mipLevels = 1; ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk->vkCreateImage(vk->device, &ici, NULL, &image) != VK_SUCCESS) {
        printf("VESSEL-WSI blit image=FAIL\n"); failures++; return;
    }
    vk->vkGetImageMemoryRequirements(vk->device, image, &ireqs);
    dev_type = select_memory_type(vk, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, ireqs.memoryTypeBits);
    if (dev_type < 0) dev_type = select_memory_type(vk, 0, 0, ireqs.memoryTypeBits);

    memset(&mai, 0, sizeof(mai));
    mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.allocationSize = ireqs.size;
    mai.memoryTypeIndex = (uint32_t)dev_type;
    vk->vkAllocateMemory(vk->device, &mai, NULL, &imem);
    vk->vkBindImageMemory(vk->device, image, imem, 0);

    memset(&bci, 0, sizeof(bci));
    bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bci.size = (VkDeviceSize)WIDTH * HEIGHT * BPP;
    bci.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    if (vk->vkCreateBuffer(vk->device, &bci, NULL, &buffer) != VK_SUCCESS) {
        printf("VESSEL-WSI blit buffer=FAIL\n"); failures++; return;
    }
    vk->vkGetBufferMemoryRequirements(vk->device, buffer, &breqs);
    host_type = select_memory_type(vk, VK_MEMORY_PROPERTY_HOST_COHERENT_BIT |
                                       VK_MEMORY_PROPERTY_HOST_CACHED_BIT, 0,
                                   breqs.memoryTypeBits);
    if (host_type < 0)
        host_type = select_memory_type(vk, VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0,
                                       breqs.memoryTypeBits);
    mai.allocationSize = breqs.size;
    mai.memoryTypeIndex = (uint32_t)host_type;
    vk->vkAllocateMemory(vk->device, &mai, NULL, &bmem);
    vk->vkBindBufferMemory(vk->device, buffer, bmem, 0);
    vk->vkMapMemory(vk->device, bmem, 0, VK_WHOLE_SIZE, 0, &map);

    printf("VESSEL-WSI blit image_type=%d buffer_type=%d (%s)\n", dev_type, host_type,
           mem_flag_string(vk->mem.memoryTypes[host_type].propertyFlags, flags, sizeof(flags)));

    vk->vkCreateCommandPool(vk->device,
        &(VkCommandPoolCreateInfo){ .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                    .queueFamilyIndex = vk->queue_family }, NULL, &pool);
    memset(&cbai, 0, sizeof(cbai));
    cbai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbai.commandPool = pool;
    cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbai.commandBufferCount = 1;
    vk->vkAllocateCommandBuffers(vk->device, &cbai, &cb);

    memset(&cbbi, 0, sizeof(cbbi));
    cbbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    vk->vkBeginCommandBuffer(cb, &cbbi);
    memset(&barrier, 0, sizeof(barrier));
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vk->vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &barrier);
    memset(&region, 0, sizeof(region));
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.layerCount = 1;
    region.imageExtent.width = WIDTH;
    region.imageExtent.height = HEIGHT;
    region.imageExtent.depth = 1;
    vk->vkCmdCopyImageToBuffer(cb, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1, &region);
    vk->vkEndCommandBuffer(cb);

    memset(&submit, 0, sizeof(submit));
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cb;

    /* One submit to warm the pipeline, then a timed run. This is the GPU half of
     * today's cost — the copy from the tiled render target into the buffer that
     * xcb_put_image later reads. */
    vk->vkQueueSubmit(vk->queue, 1, &submit, VK_NULL_HANDLE);
    vk->vkQueueWaitIdle(vk->queue);
    t0 = now_ms();
    for (int r = 0; r < reps; r++) {
        vk->vkQueueSubmit(vk->queue, 1, &submit, VK_NULL_HANDLE);
        vk->vkQueueWaitIdle(vk->queue);
    }
    t1 = now_ms();
    printf("VESSEL-WSI blit gpu_copy ms=%.2f\n", (t1 - t0) / reps);

    if (map) bandwidth("blitbuf", map, (size_t)WIDTH * HEIGHT * BPP);

    vk->vkUnmapMemory(vk->device, bmem);
    vk->vkFreeMemory(vk->device, bmem, NULL);
    vk->vkFreeMemory(vk->device, imem, NULL);
    vk->vkDestroyBuffer(vk->device, buffer, NULL);
    vk->vkDestroyImage(vk->device, image, NULL);
}

/* ------------------------------------------------- dma-buf / AHB import test */

static void probe_import(struct vk *vk, const char *label, int fd, uint32_t stride)
{
    PFN_vkGetMemoryFdPropertiesKHR get_props;
    VkMemoryFdPropertiesKHR fd_props;
    VkImportMemoryFdInfoKHR import;
    VkMemoryAllocateInfo mai;
    VkMemoryDedicatedAllocateInfo dedicated;
    VkExternalMemoryImageCreateInfo emici;
    VkImageCreateInfo ici;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkMemoryRequirements reqs;
    VkSubresourceLayout layout;
    off_t size;
    int type;
    VkResult r;
    int dup_fd;

    printf("\n  -- import %s (fd=%d stride=%u) --\n", label, fd, stride);
    if (fd < 0) { printf("VESSEL-WSI import %s result=SKIP reason=no-fd\n", label); return; }

    get_props = (PFN_vkGetMemoryFdPropertiesKHR)vk->gdpa(vk->device, "vkGetMemoryFdPropertiesKHR");
    if (!get_props) {
        printf("VESSEL-WSI import %s result=FAIL reason=no-vkGetMemoryFdPropertiesKHR\n", label);
        return;
    }

    memset(&fd_props, 0, sizeof(fd_props));
    fd_props.sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR;
    r = get_props(vk->device, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, fd, &fd_props);
    if (r != VK_SUCCESS) {
        printf("VESSEL-WSI import %s result=FAIL stage=fdProperties VkResult=%d\n", label, (int)r);
        return;
    }
    printf("VESSEL-WSI import %s fdProperties typeBits=0x%x\n", label, fd_props.memoryTypeBits);

    /* A LINEAR image whose width covers the gralloc stride, which is what the
     * BufferFromPixmap route would bind: the server reports stride in bytes and
     * the client has no modifier to work from. */
    memset(&emici, 0, sizeof(emici));
    emici.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    emici.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;

    memset(&ici, 0, sizeof(ici));
    ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ici.pNext = &emici;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = VK_FORMAT_B8G8R8A8_UNORM;
    ici.extent.width = stride ? stride : WIDTH;
    ici.extent.height = HEIGHT;
    ici.extent.depth = 1;
    ici.mipLevels = 1; ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_LINEAR;
    ici.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    r = vk->vkCreateImage(vk->device, &ici, NULL, &image);
    if (r != VK_SUCCESS) {
        printf("VESSEL-WSI import %s result=FAIL stage=createImage VkResult=%d\n", label, (int)r);
        return;
    }
    vk->vkGetImageMemoryRequirements(vk->device, image, &reqs);
    vk->vkGetImageSubresourceLayout(vk->device, image,
        &(VkImageSubresource){ .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT }, &layout);

    size = lseek(fd, 0, SEEK_END);
    printf("  image wants %llu bytes (rowPitch %llu); the dma-buf is %lld\n",
           (unsigned long long)reqs.size, (unsigned long long)layout.rowPitch, (long long)size);

    type = select_memory_type(vk, 0, 0, reqs.memoryTypeBits & fd_props.memoryTypeBits);
    if (type < 0) {
        printf("VESSEL-WSI import %s result=FAIL stage=memoryType "
               "image=0x%x fd=0x%x intersection=0\n", label,
               reqs.memoryTypeBits, fd_props.memoryTypeBits);
        vk->vkDestroyImage(vk->device, image, NULL);
        return;
    }

    /* The import consumes the fd on success, so hand over a dup and keep ours. */
    dup_fd = dup(fd);
    memset(&import, 0, sizeof(import));
    import.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR;
    import.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
    import.fd = dup_fd;
    memset(&dedicated, 0, sizeof(dedicated));
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.image = image;
    import.pNext = &dedicated;
    memset(&mai, 0, sizeof(mai));
    mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.pNext = &import;
    mai.allocationSize = reqs.size;
    mai.memoryTypeIndex = (uint32_t)type;

    r = vk->vkAllocateMemory(vk->device, &mai, NULL, &memory);
    if (r != VK_SUCCESS && size > 0 && (VkDeviceSize)size != reqs.size) {
        /* The image's memory requirement can exceed the dma-buf by a page of
         * driver padding, and an import cannot grow someone else's buffer. Mesa
         * allocates the buffer's own size in that case, so try that before
         * calling the route closed. */
        printf("  allocationSize=%llu was refused (VkResult %d); retrying with the "
               "dma-buf's own %lld\n", (unsigned long long)reqs.size, (int)r,
               (long long)size);
        mai.allocationSize = (VkDeviceSize)size;
        r = vk->vkAllocateMemory(vk->device, &mai, NULL, &memory);
    }
    if (r != VK_SUCCESS) {
        printf("VESSEL-WSI import %s result=FAIL stage=allocateMemory VkResult=%d\n",
               label, (int)r);
        close(dup_fd);
        vk->vkDestroyImage(vk->device, image, NULL);
        return;
    }

    r = vk->vkBindImageMemory(vk->device, image, memory, 0);
    if (r != VK_SUCCESS) {
        printf("VESSEL-WSI import %s result=FAIL stage=bindImageMemory VkResult=%d\n",
               label, (int)r);
        vk->vkFreeMemory(vk->device, memory, NULL);
        vk->vkDestroyImage(vk->device, image, NULL);
        return;
    }

    printf("VESSEL-WSI import %s result=OK memoryType=%d rowPitch=%llu\n", label, type,
           (unsigned long long)layout.rowPitch);
    printf("  a Turnip image is bound to an imported dma-buf. This is the whole of\n"
           "  the zero-copy present path's client side.\n");

    vk->vkFreeMemory(vk->device, memory, NULL);
    vk->vkDestroyImage(vk->device, image, NULL);
}

/* ------------------------------------------------------------------- main */

int main(void)
{
    struct vk vk;
    struct ahb_result ahb_gpu, ahb_cpu, ahb_both;
    int heap_fd;
    char flags[128];

    setvbuf(stdout, NULL, _IOLBF, 0);
    printf("vessel wsiprobe — %dx%d B8G8R8A8 (%d bytes a frame)\n",
           WIDTH, HEIGHT, WIDTH * HEIGHT * BPP);

    heap_fd = probe_dma_heaps();

    head("2. AHardwareBuffer — what gralloc hands back, and in what layout");
    /* Exactly what gpu_image.c asks for today. */
    probe_ahb("gpu_color_output", AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT,
              AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, &ahb_gpu);
    /* What it would have to ask for so gralloc cannot choose UBWC. */
    probe_ahb("gpu+cpu_rw",
              AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
              AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
              AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
              AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
              AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, &ahb_both);
    probe_ahb("cpu_write_only", AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
              AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, &ahb_cpu);

    head("3. Vulkan — which driver, and what memory it offers");
    if (!vk_init(&vk)) {
        printf("\nno Vulkan device; the measurements below cannot be taken\n");
        return 2;
    }

    for (uint32_t i = 0; i < vk.mem.memoryTypeCount; i++) {
        printf("VESSEL-WSI memtype idx=%u heap=%u flags=%s\n", i,
               vk.mem.memoryTypes[i].heapIndex,
               mem_flag_string(vk.mem.memoryTypes[i].propertyFlags, flags, sizeof(flags)));
    }
    for (uint32_t i = 0; i < vk.mem.memoryHeapCount; i++) {
        printf("VESSEL-WSI memheap idx=%u size=%lluMB flags=0x%x\n", i,
               (unsigned long long)(vk.mem.memoryHeaps[i].size >> 20),
               vk.mem.memoryHeaps[i].flags);
    }
    {
        int cached = select_memory_type(&vk, VK_MEMORY_PROPERTY_HOST_COHERENT_BIT |
                                             VK_MEMORY_PROPERTY_HOST_CACHED_BIT, 0, ~0u);
        printf("VESSEL-WSI hostcached available=%s type=%d\n", cached >= 0 ? "yes" : "NO", cached);
        if (cached < 0)
            printf("  IOCOHERENT is off on this kernel: every mapped frame the CPU reads is\n"
                   "  uncached, and the sw present path pays for it once per flip.\n");
    }

    probe_linear_image(&vk);
    probe_buffer_blit(&vk);

    head("6. importing someone else's buffer — the zero-copy candidate");
    probe_import(&vk, "dmaheap", heap_fd, WIDTH);
    probe_import(&vk, "ahb_gpu", ahb_gpu.fd, ahb_gpu.stride);
    probe_import(&vk, "ahb_gpu_cpu", ahb_both.fd, ahb_both.stride);

    printf("\nVESSEL-WSI done failures=%d\n", failures);
    return 0;
}

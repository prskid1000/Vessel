// SPDX-License-Identifier: LGPL-2.1-or-later
// Part of Vessel. See include/vessel_vulkan_driver.h for why this exists.

#include <dlfcn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <vulkan/vulkan_core.h>

#include <adrenotools/driver.h>
#include <adrenotools/priv.h>

#include "vessel_vulkan_driver.h"

/* The header carries these as plain numbers so it can be included without
 * Vulkan. This is where they are held to the real enum. */
_Static_assert(VESSEL_VK_DRIVER_ID_MESA_TURNIP == (uint32_t)VK_DRIVER_ID_MESA_TURNIP,
               "VESSEL_VK_DRIVER_ID_MESA_TURNIP disagrees with VkDriverId");
_Static_assert(VESSEL_VK_DRIVER_ID_QUALCOMM_PROPRIETARY ==
                   (uint32_t)VK_DRIVER_ID_QUALCOMM_PROPRIETARY,
               "VESSEL_VK_DRIVER_ID_QUALCOMM_PROPRIETARY disagrees with VkDriverId");

/**
 * The soname of the platform loader.
 *
 * Not a path. `dlopen` by soname is what every other Vulkan client on the device
 * does, so it is also what reproduces their result — including the case where
 * something else has already loaded a copy.
 */
#define SYSTEM_LIBVULKAN "libvulkan.so"

/**
 * libadrenotools, as it is named in the APK's native library directory.
 *
 * Loaded by `dlopen` rather than linked, for one reason that matters more than
 * it looks: this same translation unit is compiled into a standalone binary by
 * `tools/vulkan/build.sh` and run as a plain process, where the library sits
 * somewhere else entirely. Making the dependency late keeps both callers on one
 * code path, so the thing the device tool proves is the thing the app ships.
 */
#define ADRENOTOOLS_SONAME "libadrenotools.so"

typedef void *(*pfn_adrenotools_open_libvulkan)(int, int, const char *, const char *,
                                                const char *, const char *, const char *,
                                                void **);

static void set_error(vessel_vk_driver *out, const char *fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    vsnprintf(out->error, sizeof(out->error), fmt, args);
    va_end(args);
    out->ok = 0;
}

static void copy_string(char *dst, size_t size, const char *src)
{
    if (!src) {
        dst[0] = '\0';
        return;
    }
    snprintf(dst, size, "%s", src);
}

/** `dir` with exactly one trailing separator, into `buffer`. */
static const char *with_separator(const char *dir, char *buffer, size_t size)
{
    size_t length;

    if (!dir) return NULL;
    length = strlen(dir);
    if (length && dir[length - 1] == '/') return dir;
    snprintf(buffer, size, "%s/", dir);
    return buffer;
}

void vessel_vk_format_version(uint32_t version, char *buffer, size_t size)
{
    snprintf(buffer, size, "%u.%u.%u", VK_API_VERSION_MAJOR(version),
             VK_API_VERSION_MINOR(version), VK_API_VERSION_PATCH(version));
}

int vessel_vk_driver_is_turnip(const vessel_vk_driver *driver)
{
    if (!driver || !driver->ok) return 0;
    if (driver->has_driver_properties)
        return driver->driver_id == VESSEL_VK_DRIVER_ID_MESA_TURNIP;
    /* No VkPhysicalDeviceDriverProperties: fall back to the device name, which
     * Turnip prefixes with "Turnip". Reported as a weaker answer rather than as
     * the same answer — has_driver_properties is in the struct for that reason. */
    return strncmp(driver->device_name, "Turnip", 6) == 0;
}

/**
 * Everything after "we have a Vulkan loader handle".
 *
 * Shared by both probes on purpose: the two differ only in how the handle was
 * obtained, and a separate query path for each is how "the custom one reports
 * something the system one does not" becomes an artefact of the reporting code
 * rather than of the driver.
 */
static void probe_handle(void *handle, vessel_vk_driver *out)
{
    PFN_vkGetInstanceProcAddr get_instance_proc_addr;
    PFN_vkCreateInstance create_instance;
    PFN_vkDestroyInstance destroy_instance;
    PFN_vkEnumeratePhysicalDevices enumerate_physical_devices;
    PFN_vkEnumerateInstanceVersion enumerate_instance_version;
    PFN_vkGetPhysicalDeviceProperties get_properties;
    PFN_vkGetPhysicalDeviceProperties2 get_properties2;
    PFN_vkGetPhysicalDeviceMemoryProperties get_memory_properties;

    VkPhysicalDeviceDriverProperties driver_properties;
    VkPhysicalDeviceProperties2 properties2;
    VkPhysicalDeviceProperties properties;
    VkApplicationInfo application;
    VkInstanceCreateInfo create;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice devices[8];
    uint32_t device_count = 0;
    uint32_t instance_version = VK_API_VERSION_1_0;
    VkResult result;

    get_instance_proc_addr = (PFN_vkGetInstanceProcAddr)dlsym(handle, "vkGetInstanceProcAddr");
    if (!get_instance_proc_addr) {
        /* An ICD exports the loader interface instead, and nothing else. The two
         * differ only in what a NULL instance may return, which is the four
         * global commands either way -- everything below asks for those and then
         * asks the instance for the rest, so one path serves both. */
        get_instance_proc_addr =
            (PFN_vkGetInstanceProcAddr)dlsym(handle, "vk_icdGetInstanceProcAddr");
    }
    if (!get_instance_proc_addr) {
        set_error(out, "exports neither vkGetInstanceProcAddr nor vk_icdGetInstanceProcAddr: %s",
                  dlerror());
        return;
    }

    /* Global commands come from a NULL instance. A loader that refuses these is
     * not a Vulkan loader, whatever it is. */
    create_instance = (PFN_vkCreateInstance)get_instance_proc_addr(NULL, "vkCreateInstance");
    if (!create_instance) {
        set_error(out, "vkGetInstanceProcAddr(NULL, \"vkCreateInstance\") returned NULL");
        return;
    }

    enumerate_instance_version =
        (PFN_vkEnumerateInstanceVersion)get_instance_proc_addr(NULL, "vkEnumerateInstanceVersion");
    if (enumerate_instance_version && enumerate_instance_version(&instance_version) != VK_SUCCESS)
        instance_version = VK_API_VERSION_1_0;

    /* Ask for no more than the loader admits to. Naming a higher apiVersion than
     * the implementation supports is VK_ERROR_INCOMPATIBLE_DRIVER, which would
     * read as "the driver is broken" when it only means we asked wrong. */
    memset(&application, 0, sizeof(application));
    application.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application.pApplicationName = "vessel-driver-probe";
    application.apiVersion = instance_version;

    memset(&create, 0, sizeof(create));
    create.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create.pApplicationInfo = &application;

    result = create_instance(&create, NULL, &instance);
    if (result != VK_SUCCESS) {
        set_error(out, "vkCreateInstance failed (VkResult %d)", (int)result);
        return;
    }

    enumerate_physical_devices =
        (PFN_vkEnumeratePhysicalDevices)get_instance_proc_addr(instance, "vkEnumeratePhysicalDevices");
    destroy_instance = (PFN_vkDestroyInstance)get_instance_proc_addr(instance, "vkDestroyInstance");
    get_properties =
        (PFN_vkGetPhysicalDeviceProperties)get_instance_proc_addr(instance, "vkGetPhysicalDeviceProperties");
    get_properties2 =
        (PFN_vkGetPhysicalDeviceProperties2)get_instance_proc_addr(instance, "vkGetPhysicalDeviceProperties2");
    if (!get_properties2) {
        get_properties2 = (PFN_vkGetPhysicalDeviceProperties2)get_instance_proc_addr(
            instance, "vkGetPhysicalDeviceProperties2KHR");
    }

    if (!enumerate_physical_devices || !get_properties) {
        set_error(out, "the instance resolves no vkEnumeratePhysicalDevices");
        goto done;
    }

    device_count = (uint32_t)(sizeof(devices) / sizeof(devices[0]));
    result = enumerate_physical_devices(instance, &device_count, devices);
    if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
        set_error(out, "vkEnumeratePhysicalDevices failed (VkResult %d)", (int)result);
        goto done;
    }
    out->device_count = device_count;
    if (device_count == 0) {
        /* The specific failure libadrenotools' own documentation warns about:
         * a valid handle, a working instance, and no GPU behind it, because the
         * hook directory was wrong. Worth saying in those words. */
        set_error(out, "the instance enumerated 0 physical devices");
        goto done;
    }

    memset(&properties, 0, sizeof(properties));
    get_properties(devices[0], &properties);
    copy_string(out->device_name, sizeof(out->device_name), properties.deviceName);
    out->api_version = properties.apiVersion;
    out->driver_version = properties.driverVersion;
    out->vendor_id = properties.vendorID;
    out->device_id = properties.deviceID;

    /* driverName/driverID need VkPhysicalDeviceDriverProperties, which is core
     * in 1.2 and an extension before it. Absent is a legitimate answer and is
     * reported as such rather than filled in from the device name. */
    if (get_properties2 && instance_version >= VK_API_VERSION_1_1) {
        memset(&driver_properties, 0, sizeof(driver_properties));
        driver_properties.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;

        memset(&properties2, 0, sizeof(properties2));
        properties2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
        properties2.pNext = &driver_properties;

        get_properties2(devices[0], &properties2);
        if (driver_properties.driverID != 0) {
            out->has_driver_properties = 1;
            out->driver_id = (uint32_t)driver_properties.driverID;
            copy_string(out->driver_name, sizeof(out->driver_name), driver_properties.driverName);
            copy_string(out->driver_info, sizeof(out->driver_info), driver_properties.driverInfo);
        }
    }

    /* The heaps, for the reason given in the header: this is the number a
     * container's VRAM setting has to move, and every layer above only
     * paraphrases it. */
    get_memory_properties = (PFN_vkGetPhysicalDeviceMemoryProperties)
        get_instance_proc_addr(instance, "vkGetPhysicalDeviceMemoryProperties");
    if (get_memory_properties) {
        VkPhysicalDeviceMemoryProperties memory;
        uint32_t i;

        memset(&memory, 0, sizeof(memory));
        get_memory_properties(devices[0], &memory);
        out->heap_count = memory.memoryHeapCount;
        for (i = 0; i < memory.memoryHeapCount; i++) {
            out->heap_total_bytes += memory.memoryHeaps[i].size;
            if (memory.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT)
                out->device_local_bytes += memory.memoryHeaps[i].size;
        }
    }

    out->ok = 1;

done:
    if (destroy_instance && instance != VK_NULL_HANDLE) destroy_instance(instance, NULL);
}

void vessel_vk_probe_system(vessel_vk_driver *out)
{
    void *handle;

    memset(out, 0, sizeof(*out));
    out->source = VESSEL_VK_SOURCE_SYSTEM;

    handle = dlopen(SYSTEM_LIBVULKAN, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        set_error(out, "dlopen(%s) failed: %s", SYSTEM_LIBVULKAN, dlerror());
        return;
    }
    probe_handle(handle, out);
    /* Deliberately not dlclose()d. The platform loader keeps per-process global
     * state and a second probe in the same process must reach the same copy. */
}

void vessel_vk_probe_icd(const char *icd_path, vessel_vk_driver *out)
{
    void *handle;

    memset(out, 0, sizeof(*out));
    out->source = VESSEL_VK_SOURCE_ICD;

    if (!icd_path || !*icd_path) {
        set_error(out, "no ICD path given");
        return;
    }

    /* No loader, no namespace surgery: an ICD is an ordinary shared library and
     * this is the whole of loading one. Its own dependencies -- libxcb and
     * friends for the x11 WSI -- resolve from the directory it sits in, which is
     * on the search path in the app process and put there explicitly for the
     * Wine one. See patches/wine/0009. */
    handle = dlopen(icd_path, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        set_error(out, "dlopen(%s) failed: %s", icd_path, dlerror());
        return;
    }

    if (!dlsym(handle, "vk_icdGetInstanceProcAddr")) {
        /* A HAL build reaches here whenever one is installed. Saying which shape
         * the file is beats "it did not load": the caller's next move is the
         * adrenotools probe, and that is only correct for a HAL. */
        set_error(out, "%s exports no vk_icdGetInstanceProcAddr — this is a HAL build, "
                       "not an ICD", icd_path);
        return;
    }

    probe_handle(handle, out);
}

void vessel_vk_probe_adrenotools(const char *hooks_dir,
                                 const char *driver_dir,
                                 const char *driver_name,
                                 vessel_vk_driver *out)
{
    char hooks_buffer[1024];
    char driver_buffer[1024];
    char library_path[1152];
    const char *hooks;
    const char *driver;
    void *adrenotools;
    pfn_adrenotools_open_libvulkan open_libvulkan;
    void *handle;

    memset(out, 0, sizeof(*out));
    out->source = VESSEL_VK_SOURCE_ADRENOTOOLS;

    if (!hooks_dir || !driver_dir || !driver_name || !*hooks_dir || !*driver_dir || !*driver_name) {
        /* Three fields or none — the state libadrenotools handles by falling
         * through to the stock driver without a word. */
        set_error(out, "no custom driver configured (hooks/driver directory or name missing)");
        return;
    }

    hooks = with_separator(hooks_dir, hooks_buffer, sizeof(hooks_buffer));
    driver = with_separator(driver_dir, driver_buffer, sizeof(driver_buffer));

    snprintf(library_path, sizeof(library_path), "%s" ADRENOTOOLS_SONAME, hooks);
    adrenotools = dlopen(library_path, RTLD_NOW | RTLD_LOCAL);
    if (!adrenotools) {
        /* Second chance by soname, for the in-process caller where the APK's
         * library directory is already on the search path. */
        adrenotools = dlopen(ADRENOTOOLS_SONAME, RTLD_NOW | RTLD_LOCAL);
    }
    if (!adrenotools) {
        set_error(out, "dlopen(%s) failed: %s", library_path, dlerror());
        return;
    }

    open_libvulkan =
        (pfn_adrenotools_open_libvulkan)dlsym(adrenotools, "adrenotools_open_libvulkan");
    if (!open_libvulkan) {
        set_error(out, "%s exports no adrenotools_open_libvulkan", ADRENOTOOLS_SONAME);
        return;
    }

    handle = open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM,
                            NULL /* tmpLibDir: API >= 29 uses memfd */,
                            hooks, driver, driver_name,
                            NULL /* fileRedirectDir */, NULL /* userMappingHandle */);
    if (!handle) {
        /* adrenotools_open_libvulkan returns NULL for eight different reasons and
         * sets no error of its own. The two that actually happen are named here
         * because the alternative is a bare "it returned NULL". */
        set_error(out,
                  "adrenotools_open_libvulkan returned NULL — either linkernsbypass "
                  "could not reach the linker's namespace API on this Android build, "
                  "or %s%s is not there", driver, driver_name);
        return;
    }

    probe_handle(handle, out);
}

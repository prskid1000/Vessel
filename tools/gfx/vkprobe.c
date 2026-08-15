/*
 * What Vulkan driver is actually underneath, as seen from inside Wine.
 *
 * This is the ground truth for every other probe in this directory. DXVK,
 * vkd3d and Zink all report an "adapter", but they report whatever the Vulkan
 * driver told them, so if this probe says the driver is Qualcomm's blob then
 * every adapter string in the suite is describing the blob. Vessel ships Turnip
 * precisely because that blob is missing extensions DXVK wants, so knowing
 * which one answered is not a detail.
 *
 * It draws nothing — it is not a rendering test and does not print a
 * PASS/MISMATCH verdict. It enumerates, prints, and exits.
 *
 * The Vulkan types are declared here by hand rather than by including
 * vulkan_core.h. That is a deliberate trade: the probe then depends on nothing
 * outside llvm-mingw, and the only structure it reads fields out of
 * (VkPhysicalDeviceProperties) has a frozen layout whose interesting members
 * all precede the large embedded VkPhysicalDeviceLimits — which is why the
 * declaration below can end in an oversized opaque tail instead of transcribing
 * a hundred limit fields that would then have to be kept correct.
 */

#include "gfxprobe.h"

#define API "vulkan"

typedef void *VkInstance_T;
typedef void *VkPhysicalDevice_T;
typedef VkInstance_T VkInstance;
typedef VkPhysicalDevice_T VkPhysicalDevice;
typedef int VkResult;

#define VK_SUCCESS 0
#define VK_INCOMPLETE 5

#define VK_STRUCTURE_TYPE_APPLICATION_INFO 0
#define VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO 1
#define VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2 1000059001
#define VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES 1000196000

#define VK_MAX_PHYSICAL_DEVICE_NAME_SIZE 256
#define VK_UUID_SIZE 16
#define VK_MAX_DRIVER_NAME_SIZE 256
#define VK_MAX_DRIVER_INFO_SIZE 256

typedef struct VkApplicationInfo {
    unsigned int sType;
    const void *pNext;
    const char *pApplicationName;
    unsigned int applicationVersion;
    const char *pEngineName;
    unsigned int engineVersion;
    unsigned int apiVersion;
} VkApplicationInfo;

typedef struct VkInstanceCreateInfo {
    unsigned int sType;
    const void *pNext;
    unsigned int flags;
    const VkApplicationInfo *pApplicationInfo;
    unsigned int enabledLayerCount;
    const char *const *ppEnabledLayerNames;
    unsigned int enabledExtensionCount;
    const char *const *ppEnabledExtensionNames;
} VkInstanceCreateInfo;

/* Everything up to and including pipelineCacheUUID is exact. `limits_and_sparse`
 * stands in for VkPhysicalDeviceLimits + VkPhysicalDeviceSparseProperties,
 * which together are around 530 bytes; 2048 is generous on purpose. Reading a
 * short buffer would be a bug, over-reserving one costs a page of stack. */
typedef struct VkPhysicalDeviceProperties {
    unsigned int apiVersion;
    unsigned int driverVersion;
    unsigned int vendorID;
    unsigned int deviceID;
    unsigned int deviceType;
    char deviceName[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE];
    unsigned char pipelineCacheUUID[VK_UUID_SIZE];
    unsigned char limits_and_sparse[2048];
} VkPhysicalDeviceProperties;

typedef struct VkPhysicalDeviceProperties2 {
    unsigned int sType;
    void *pNext;
    VkPhysicalDeviceProperties properties;
} VkPhysicalDeviceProperties2;

typedef struct VkConformanceVersion {
    unsigned char major, minor, subminor, patch;
} VkConformanceVersion;

typedef struct VkPhysicalDeviceDriverProperties {
    unsigned int sType;
    void *pNext;
    unsigned int driverID;
    char driverName[VK_MAX_DRIVER_NAME_SIZE];
    char driverInfo[VK_MAX_DRIVER_INFO_SIZE];
    VkConformanceVersion conformanceVersion;
} VkPhysicalDeviceDriverProperties;

typedef struct VkExtensionProperties {
    char extensionName[256];
    unsigned int specVersion;
} VkExtensionProperties;

/* The memory properties, spelled out for the same reason as everything above:
 * this file declares what it uses rather than including the Vulkan headers, so
 * the probe builds against three toolchains with nothing installed. The counts
 * and the array sizes are the spec's fixed maxima, so the struct is exact
 * rather than generous — a short buffer here would be read by the driver. */
#define VK_MAX_MEMORY_TYPES 32
#define VK_MAX_MEMORY_HEAPS 16
#define VK_MEMORY_HEAP_DEVICE_LOCAL_BIT 0x00000001u

typedef struct VkMemoryType {
    unsigned int propertyFlags;
    unsigned int heapIndex;
} VkMemoryType;

typedef struct VkMemoryHeap {
    unsigned long long size;
    unsigned int flags;
} VkMemoryHeap;

typedef struct VkPhysicalDeviceMemoryProperties {
    unsigned int memoryTypeCount;
    VkMemoryType memoryTypes[VK_MAX_MEMORY_TYPES];
    unsigned int memoryHeapCount;
    VkMemoryHeap memoryHeaps[VK_MAX_MEMORY_HEAPS];
} VkPhysicalDeviceMemoryProperties;

typedef void *(__stdcall *PFN_vkGetInstanceProcAddr)(VkInstance, const char *);
typedef VkResult(__stdcall *PFN_vkEnumerateInstanceExtensionProperties)(const char *, unsigned int *,
                                                                        VkExtensionProperties *);
typedef VkResult(__stdcall *PFN_vkCreateInstance)(const VkInstanceCreateInfo *, const void *, VkInstance *);
typedef void(__stdcall *PFN_vkDestroyInstance)(VkInstance, const void *);
typedef VkResult(__stdcall *PFN_vkEnumeratePhysicalDevices)(VkInstance, unsigned int *, VkPhysicalDevice *);
typedef void(__stdcall *PFN_vkGetPhysicalDeviceProperties)(VkPhysicalDevice, VkPhysicalDeviceProperties *);
typedef void(__stdcall *PFN_vkGetPhysicalDeviceProperties2)(VkPhysicalDevice, VkPhysicalDeviceProperties2 *);
typedef void(__stdcall *PFN_vkGetPhysicalDeviceMemoryProperties)(VkPhysicalDevice, VkPhysicalDeviceMemoryProperties *);
typedef VkResult(__stdcall *PFN_vkEnumerateInstanceVersion)(unsigned int *);

/*
 * The exact instance extension set DXVK 2.7 asks for.
 *
 * Measured, not guessed: this is what DXVK prints as "Enabled instance
 * extensions" immediately before `DxvkInstance::createInstance: Failed to
 * create Vulkan instance` on this device. A bare vkCreateInstance with no
 * extensions succeeds here, so the difference between the two is the entire
 * reason every D3D probe fails, and naming which of the four is missing is the
 * difference between a usable finding and "DXVK does not work".
 */
static const char *const DXVK_INSTANCE_EXTS[] = {
    "VK_KHR_surface",
    "VK_KHR_win32_surface",
    "VK_KHR_get_surface_capabilities2",
    "VK_EXT_surface_maintenance1",
};

static const char *device_type_name(unsigned int t)
{
    switch (t) {
    case 0: return "other";
    case 1: return "integrated";
    case 2: return "discrete";
    case 3: return "virtual";
    case 4: return "cpu";
    default: return "unknown";
    }
}

int main(void)
{
    gfx_report_machine(API);
    HMODULE vk;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkCreateInstance create_instance;
    PFN_vkDestroyInstance destroy_instance;
    PFN_vkEnumeratePhysicalDevices enum_devices;
    PFN_vkGetPhysicalDeviceProperties get_props;
    PFN_vkGetPhysicalDeviceProperties2 get_props2;
    PFN_vkGetPhysicalDeviceMemoryProperties get_mem_props;
    PFN_vkEnumerateInstanceVersion enum_version;
    VkApplicationInfo app;
    VkInstanceCreateInfo ci;
    VkInstance instance = NULL;
    VkPhysicalDevice devices[8];
    unsigned int count = 8, loader_version = 0, i;
    VkResult vr;

    /* vulkan-1.dll is Wine's PE-side loader; it thunks into winevulkan.dll and
     * then into the unix winevulkan.so, which dlopens the platform's
     * libvulkan.so. Loading it by name rather than importing it means a Wine
     * build without winevulkan reports cleanly instead of failing to start. */
    vk = gfx_load(API, "vulkan-1.dll");
    if (!vk) return GFX_EXIT_FAIL;

    gipa = (PFN_vkGetInstanceProcAddr)(void *)GetProcAddress(vk, "vkGetInstanceProcAddr");
    create_instance = (PFN_vkCreateInstance)(void *)GetProcAddress(vk, "vkCreateInstance");
    if (!gipa || !create_instance)
        return gfx_fail(API, "getprocaddress", E_FAIL, "vulkan-1.dll has no entry points");

    /* Ask for the loader's version before asking for 1.1 features. A 1.0-only
     * loader returns VK_ERROR_INCOMPATIBLE_DRIVER from vkCreateInstance if
     * apiVersion names something it does not know, so this is not cosmetic. */
    enum_version = (PFN_vkEnumerateInstanceVersion)(void *)GetProcAddress(vk, "vkEnumerateInstanceVersion");
    if (enum_version && enum_version(&loader_version) != VK_SUCCESS)
        loader_version = 0;
    if (loader_version)
        gfx_info(API, "loader_api=%u.%u.%u",
                 (loader_version >> 22) & 0x7f, (loader_version >> 12) & 0x3ff, loader_version & 0xfff);
    else
        gfx_info(API, "loader_api=1.0 (no vkEnumerateInstanceVersion)");

    /* Which instance extensions does Wine's Vulkan actually advertise? On this
     * device the answer decides everything downstream, because DXVK treats its
     * four as mandatory and aborts the instance if any is absent. */
    {
        PFN_vkEnumerateInstanceExtensionProperties enum_exts =
            (PFN_vkEnumerateInstanceExtensionProperties)(void *)
                GetProcAddress(vk, "vkEnumerateInstanceExtensionProperties");
        VkExtensionProperties exts[128];
        unsigned int n = 128, j, k;

        if (enum_exts && enum_exts(NULL, &n, exts) >= VK_SUCCESS) {
            gfx_info(API, "instance_extensions=%u", n);
            for (k = 0; k < sizeof(DXVK_INSTANCE_EXTS) / sizeof(DXVK_INSTANCE_EXTS[0]); k++) {
                int found = 0;
                for (j = 0; j < n; j++)
                    if (!strcmp(exts[j].extensionName, DXVK_INSTANCE_EXTS[k])) { found = 1; break; }
                gfx_info(API, "dxvk_needs %-34s %s", DXVK_INSTANCE_EXTS[k],
                         found ? "present" : "MISSING");
            }
        } else {
            gfx_info(API, "instance_extensions=unavailable");
        }
    }

    memset(&app, 0, sizeof(app));
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "vessel-vkprobe";
    app.pEngineName = "vessel";
    /* VK_MAKE_API_VERSION(0,1,1,0) when the loader admits to 1.1 or better;
     * 1.0.0 otherwise. 1.1 is what unlocks vkGetPhysicalDeviceProperties2 and
     * therefore the driverName that identifies Turnip. */
    app.apiVersion = (loader_version >= ((1u << 22) | (1u << 12))) ? ((1u << 22) | (1u << 12)) : (1u << 22);

    memset(&ci, 0, sizeof(ci));
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app;

    /* No extensions requested: this probe never presents anything, and asking
     * for VK_KHR_surface here would make a missing WSI look like a missing
     * driver. DXVK asks for surface extensions itself, and if that is what
     * fails, its own probe is where it should show up. */
    vr = create_instance(&ci, NULL, &instance);
    if (vr != VK_SUCCESS)
        return gfx_fail(API, "createinstance", (HRESULT)vr, "vkCreateInstance returned %d", vr);

    destroy_instance = (PFN_vkDestroyInstance)gipa(instance, "vkDestroyInstance");
    enum_devices = (PFN_vkEnumeratePhysicalDevices)gipa(instance, "vkEnumeratePhysicalDevices");
    get_props = (PFN_vkGetPhysicalDeviceProperties)gipa(instance, "vkGetPhysicalDeviceProperties");
    get_props2 = (PFN_vkGetPhysicalDeviceProperties2)gipa(instance, "vkGetPhysicalDeviceProperties2");
    get_mem_props = (PFN_vkGetPhysicalDeviceMemoryProperties)gipa(instance, "vkGetPhysicalDeviceMemoryProperties");
    if (!get_props2)
        get_props2 = (PFN_vkGetPhysicalDeviceProperties2)gipa(instance, "vkGetPhysicalDeviceProperties2KHR");

    if (!enum_devices || !get_props)
        return gfx_fail(API, "getinstanceprocaddr", E_FAIL, "instance has no enumeration entry points");

    vr = enum_devices(instance, &count, devices);
    if ((vr != VK_SUCCESS && vr != VK_INCOMPLETE) || count == 0) {
        /* This is the interesting failure. It means Wine reached a Vulkan
         * loader but the loader found no ICD — on Android that is the system
         * loader refusing, and it is exactly what a DXVK "no adapters" error
         * decomposes into. */
        gfx_fail(API, "enumeratephysicaldevices", (HRESULT)vr,
                 "%u devices (vr=%d) — nothing to render on", count, vr);
        if (destroy_instance) destroy_instance(instance, NULL);
        return GFX_EXIT_FAIL;
    }

    for (i = 0; i < count; i++) {
        VkPhysicalDeviceProperties props;
        memset(&props, 0, sizeof(props));

        /* The heap the container's VRAM setting moves. Printed per GPU because
         * this is the figure DXVK sums for DedicatedVideoMemory and Zink sums
         * for GL, so a mismatch between this line and the D3D or GL probe's
         * line localises the break to one layer rather than to "graphics". */
        if (get_mem_props) {
            VkPhysicalDeviceMemoryProperties mem;
            unsigned int h;
            unsigned long long local_bytes = 0;

            memset(&mem, 0, sizeof(mem));
            get_mem_props(devices[i], &mem);
            for (h = 0; h < mem.memoryHeapCount; h++)
                if (mem.memoryHeaps[h].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT)
                    local_bytes += (unsigned long long)mem.memoryHeaps[h].size;
            printf("VESSEL-HW api=%s bits=%d gpu=%u heaps=%u vram_mib=%llu\n",
                   API, gfx_bits(), i, mem.memoryHeapCount, local_bytes >> 20);
            gfx_flush();
        }

        if (get_props2) {
            VkPhysicalDeviceDriverProperties driver;
            VkPhysicalDeviceProperties2 p2;

            memset(&driver, 0, sizeof(driver));
            driver.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
            memset(&p2, 0, sizeof(p2));
            p2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
            p2.pNext = &driver;

            get_props2(devices[i], &p2);
            props = p2.properties;

            /* driverName is the only string that distinguishes Mesa's Turnip
             * from Qualcomm's driver — deviceName is "Adreno (TM) 829" either
             * way, so reading deviceName alone would not answer the question. */
            gfx_info(API, "gpu%u driver_id=%u driver=\"%s\" info=\"%s\" conformance=%u.%u.%u.%u",
                     i, driver.driverID, driver.driverName, driver.driverInfo,
                     driver.conformanceVersion.major, driver.conformanceVersion.minor,
                     driver.conformanceVersion.subminor, driver.conformanceVersion.patch);
        } else {
            get_props(devices[i], &props);
        }

        gfx_info(API, "gpu%u name=\"%s\" type=%s api=%u.%u.%u driver_version=0x%08x "
                      "vendor=0x%04x device=0x%08x",
                 i, props.deviceName, device_type_name(props.deviceType),
                 (props.apiVersion >> 22) & 0x7f, (props.apiVersion >> 12) & 0x3ff,
                 props.apiVersion & 0xfff, props.driverVersion,
                 props.vendorID, props.deviceID);
    }

    /*
     * Finally, reproduce DXVK's instance creation exactly.
     *
     * Listing the extensions above says what is advertised; this says what
     * happens when you ask for them together, which is not the same question —
     * an extension can be enumerated and still be refused. VkResult -7 is
     * VK_ERROR_EXTENSION_NOT_PRESENT and -9 is VK_ERROR_INCOMPATIBLE_DRIVER.
     */
    {
        VkInstance dxvk_like = NULL;
        VkInstanceCreateInfo dci;
        VkResult dvr;

        memset(&dci, 0, sizeof(dci));
        dci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        dci.pApplicationInfo = &app;
        dci.enabledExtensionCount = sizeof(DXVK_INSTANCE_EXTS) / sizeof(DXVK_INSTANCE_EXTS[0]);
        dci.ppEnabledExtensionNames = DXVK_INSTANCE_EXTS;

        dvr = create_instance(&dci, NULL, &dxvk_like);
        gfx_info(API, "dxvk_style_instance vr=%d (%s)", dvr,
                 dvr == VK_SUCCESS ? "created" : "REFUSED — this is why every DXVK probe fails");
        if (dvr == VK_SUCCESS && destroy_instance)
            destroy_instance(dxvk_like, NULL);
    }

    printf("VESSEL-GFX api=%s bits=%d result=PASS gpus=%u\n", API, gfx_bits(), count);
    gfx_flush();

    if (destroy_instance) destroy_instance(instance, NULL);
    return GFX_EXIT_PASS;
}

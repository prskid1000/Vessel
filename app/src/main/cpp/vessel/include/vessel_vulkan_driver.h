// SPDX-License-Identifier: LGPL-2.1-or-later
// Part of Vessel.
//
// "Which Vulkan driver actually answered?" — asked of a real VkInstance, not of
// a file on disk.
//
// The distinction is the whole point of this file. `dist/turnip-*.wcp` being
// installed says nothing about whether Turnip ran: `libvulkan_freedreno.so`
// exports one symbol, `HMI`, so it is reachable only through Android's Vulkan
// loader, and only when libadrenotools has hooked `android_dlopen_ext` to
// redirect the loader's HAL lookup at it. Every failure in that chain is silent
// — the stock Qualcomm blob answers instead and nothing says so. So the only
// honest report is the one that creates an instance and reads
// VkPhysicalDeviceDriverProperties back off it.

#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Longest string this struct will carry. VK_MAX_DRIVER_INFO_SIZE is 256. */
#define VESSEL_VK_STRING_MAX 256

/** How the Vulkan loader that answered was obtained. */
typedef enum {
    /** Plain `dlopen("libvulkan.so")` — whatever the platform loads by default. */
    VESSEL_VK_SOURCE_SYSTEM = 0,
    /** `adrenotools_open_libvulkan()` with ADRENOTOOLS_DRIVER_CUSTOM. */
    VESSEL_VK_SOURCE_ADRENOTOOLS = 1,
    /** A plain `dlopen` of an ICD, driven through `vk_icdGetInstanceProcAddr`. */
    VESSEL_VK_SOURCE_ICD = 2,
} vessel_vk_source;

/**
 * One driver's answer, or the reason there is none.
 *
 * `ok == 0` always comes with a non-empty `error`, and every string field is
 * NUL-terminated and empty rather than stale when it could not be read. There is
 * no field here that is guessed: `driver_id`/`driver_name`/`driver_info` come
 * from VkPhysicalDeviceDriverProperties when the implementation supports it, and
 * `has_driver_properties` says whether it did.
 */
typedef struct {
    int ok;
    vessel_vk_source source;
    char error[VESSEL_VK_STRING_MAX];

    /** VkPhysicalDeviceProperties.deviceName, e.g. "Turnip Adreno (TM) 829". */
    char device_name[VESSEL_VK_STRING_MAX];
    /** VkPhysicalDeviceDriverProperties.driverName, e.g. "turnip". */
    char driver_name[VESSEL_VK_STRING_MAX];
    /** VkPhysicalDeviceDriverProperties.driverInfo, e.g. "Mesa 26.3.0-devel". */
    char driver_info[VESSEL_VK_STRING_MAX];

    /** VkDriverId. 8 is VK_DRIVER_ID_QUALCOMM_PROPRIETARY, 12 is MESA_TURNIP. */
    uint32_t driver_id;
    int has_driver_properties;

    uint32_t api_version;
    uint32_t driver_version;
    uint32_t vendor_id;
    uint32_t device_id;

    /** How many physical devices the instance enumerated. Zero is a real answer. */
    uint32_t device_count;

    /* Memory heaps of the first physical device.
     *
     * Here because it is the quantity every layer above derives its idea of
     * "video memory" from -- DXVK sums the DEVICE_LOCAL heaps for
     * DedicatedVideoMemory, vkd3d reports through DXVK's DXGI, and Zink sums
     * the same heaps for GL. A container's VRAM setting is applied to the
     * driver, so this is where it has to be checked; reading it back from a
     * game only says what the game was told at the end of a long chain.
     *
     * Zero when the entry point was missing, which is reported as absent rather
     * than as a device with no memory. */
    uint32_t heap_count;
    uint64_t device_local_bytes;
    uint64_t heap_total_bytes;
} vessel_vk_driver;

/**
 * VkDriverId values this project names.
 *
 * Spelled out rather than taken from `vulkan_core.h` so this header stands alone
 * for callers that never include Vulkan — the Kotlin side compares against these
 * same numbers. They are asserted against the real enum in vulkan_driver.c, so a
 * wrong value is a compile error rather than a driver silently reported as
 * "other". (12 is BROADCOM_PROPRIETARY, which is what this said first.)
 */
#define VESSEL_VK_DRIVER_ID_QUALCOMM_PROPRIETARY 8u
#define VESSEL_VK_DRIVER_ID_MESA_TURNIP 18u

/**
 * Ask the platform's own Vulkan loader.
 *
 * In an app process this is the stock vendor driver, and that is the "before"
 * half of any claim this project makes about Turnip.
 */
void vessel_vk_probe_system(vessel_vk_driver *out);

/**
 * Ask a loader obtained through libadrenotools, with a custom driver.
 *
 * @param hooks_dir  MUST be `applicationInfo.nativeLibraryDir` — libadrenotools
 *                   loads `libmain_hook.so` and `libhook_impl.so` from here by
 *                   name, and it is also the driver namespace's default library
 *                   path, which is how the driver's own `libc++_shared.so`
 *                   resolves. Trailing separator optional.
 * @param driver_dir Directory holding the driver `.so`. Must be app-private
 *                   storage: `dlopen` refuses a library any other uid can write.
 * @param driver_name e.g. `libvulkan_freedreno.so`.
 *
 * Never returns the system driver dressed up as ours. If the hook does not take,
 * `out->driver_id` is whatever really answered, and the caller is expected to
 * compare it — which is what `vessel_vk_driver_is_turnip` is for.
 */
void vessel_vk_probe_adrenotools(const char *hooks_dir,
                                 const char *driver_dir,
                                 const char *driver_name,
                                 vessel_vk_driver *out);

/**
 * Ask an ICD directly, with no loader in front of it.
 *
 * @param icd_path Absolute path of the driver `.so`.
 *
 * This is the shape the Wine side uses (`patches/wine/0009`) and the only one
 * that can present to a window: the Android platform loader keeps the WSI
 * surface layer for itself and understands only surfaces it made for an
 * `ANativeWindow`. Fails with a message saying so when handed a HAL build, which
 * is the caller's signal to try [vessel_vk_probe_adrenotools] instead.
 */
void vessel_vk_probe_icd(const char *icd_path, vessel_vk_driver *out);

/** True only when Mesa/Turnip is the thing that answered. */
int vessel_vk_driver_is_turnip(const vessel_vk_driver *driver);

/** `1.4.295`-style rendering of a packed Vulkan version into `buffer`. */
void vessel_vk_format_version(uint32_t version, char *buffer, size_t size);

#ifdef __cplusplus
}
#endif

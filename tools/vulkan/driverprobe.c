// SPDX-License-Identifier: LGPL-2.1-or-later
// Part of Vessel.
//
// The standalone half of the driver probe: same code the app runs, in a plain
// process started the way Wine is started.
//
// That last part is the reason this exists rather than only the JNI probe. The
// app runs inside ART, in a classloader linker namespace, with libadrenotools
// sitting in the same directory it was loaded from — none of which is true of
// the Wine unix side, which is exec'd through /system/bin/linker64 and gets the
// unrestricted default namespace. libadrenotools' whole mechanism is namespace
// surgery, so "it works in the app" is not evidence that it works where the
// guest's Vulkan calls actually happen. This binary answers that question in the
// environment that matters.
//
// Output is one `VESSEL-VK ` line per probe so a script can grep it, plus human
// text. Exit status: 0 if the requested driver answered, 1 if it did not.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "vessel_vulkan_driver.h"

static const char *driver_id_name(uint32_t id)
{
    switch (id) {
    case VESSEL_VK_DRIVER_ID_QUALCOMM_PROPRIETARY: return "QUALCOMM_PROPRIETARY";
    case VESSEL_VK_DRIVER_ID_MESA_TURNIP:          return "MESA_TURNIP";
    default:                                       return "other";
    }
}

static void report(const char *label, const vessel_vk_driver *d)
{
    char api[32];
    char driver_version[32];

    printf("\n== %s ==\n", label);
    if (!d->ok) {
        printf("VESSEL-VK source=%s result=FAIL msg=%s\n",
               d->source == VESSEL_VK_SOURCE_ADRENOTOOLS ? "adrenotools" : "system", d->error);
        printf("  failed: %s\n", d->error);
        return;
    }

    vessel_vk_format_version(d->api_version, api, sizeof(api));
    /* driverVersion is vendor-encoded, so it is printed raw as well as decoded:
     * Mesa packs it as major.minor.patch, Qualcomm does not. */
    vessel_vk_format_version(d->driver_version, driver_version, sizeof(driver_version));

    printf("VESSEL-VK source=%s result=OK driver_id=%u driver=\"%s\" device=\"%s\" "
           "api=%s driver_version=%s(0x%08x) vendor=0x%04x turnip=%s\n",
           d->source == VESSEL_VK_SOURCE_ADRENOTOOLS ? "adrenotools" : "system",
           d->driver_id,
           d->has_driver_properties ? d->driver_name : "(no VkPhysicalDeviceDriverProperties)",
           d->device_name, api, driver_version, d->driver_version, d->vendor_id,
           vessel_vk_driver_is_turnip(d) ? "yes" : "no");

    printf("  device          %s\n", d->device_name);
    printf("  driverID        %u (%s)%s\n", d->driver_id, driver_id_name(d->driver_id),
           d->has_driver_properties ? "" : "  [not reported]");
    if (d->has_driver_properties) {
        printf("  driverName      %s\n", d->driver_name);
        printf("  driverInfo      %s\n", d->driver_info);
    }
    printf("  apiVersion      %s\n", api);
    printf("  driverVersion   %s (0x%08x)\n", driver_version, d->driver_version);
    printf("  vendorID        0x%04x   deviceID 0x%08x\n", d->vendor_id, d->device_id);
    printf("  physicalDevices %u\n", d->device_count);
}

int main(int argc, char **argv)
{
    vessel_vk_driver system_driver;
    vessel_vk_driver custom;
    const char *hooks_dir = NULL;
    const char *driver_dir = NULL;
    const char *driver_name = NULL;

    if (argc == 4) {
        hooks_dir = argv[1];
        driver_dir = argv[2];
        driver_name = argv[3];
    } else if (argc == 1) {
        /* The same three variables the session sets, so this can be run inside a
         * session's environment with no arguments and measure exactly what the
         * guest would get. */
        hooks_dir = getenv("ADRENOTOOLS_HOOKS_PATH");
        driver_dir = getenv("ADRENOTOOLS_DRIVER_PATH");
        driver_name = getenv("ADRENOTOOLS_DRIVER_NAME");
    } else {
        fprintf(stderr, "usage: %s [<hooksDir> <driverDir> <driverName>]\n", argv[0]);
        fprintf(stderr, "  with no arguments, reads ADRENOTOOLS_HOOKS_PATH, "
                        "ADRENOTOOLS_DRIVER_PATH and ADRENOTOOLS_DRIVER_NAME\n");
        return 2;
    }

    vessel_vk_probe_system(&system_driver);
    report("system Vulkan loader (what the phone answers by default)", &system_driver);

    if (!hooks_dir || !driver_dir || !driver_name) {
        printf("\nno custom driver requested; nothing to compare against\n");
        return 1;
    }

    vessel_vk_probe_adrenotools(hooks_dir, driver_dir, driver_name, &custom);
    report("libadrenotools + custom driver", &custom);

    if (!custom.ok) {
        printf("\nthe custom driver did NOT load; every Vulkan call would go to "
               "the driver reported above it\n");
        return 1;
    }
    if (!vessel_vk_driver_is_turnip(&custom)) {
        printf("\nlibadrenotools returned a working loader but the driver behind it "
               "is still driverID %u — the hook did not take\n", custom.driver_id);
        return 1;
    }

    printf("\nthe custom driver answered: %s / %s\n", custom.device_name,
           custom.has_driver_properties ? custom.driver_info : "(no driverInfo)");
    return 0;
}

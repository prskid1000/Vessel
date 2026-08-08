// SPDX-License-Identifier: LGPL-2.1-or-later
// Part of Vessel.
//
// JNI for app.vessel.data.GpuProbe. The only thing in this file that is not
// marshalling is the decision to answer with a fixed-length String[] rather than
// by constructing a Kotlin object from C: a field-signature drift then shows up
// as a short array (which the Kotlin side reports) instead of as a JNI abort.

#include <jni.h>
#include <stdio.h>
#include <string.h>

#include "vessel_vulkan_driver.h"

/** Must match GpuProbe.kt's `VulkanRecord` indices, and nothing else may. */
enum {
    FIELD_OK = 0,
    FIELD_SOURCE,
    FIELD_ERROR,
    FIELD_DEVICE_NAME,
    FIELD_DRIVER_NAME,
    FIELD_DRIVER_INFO,
    FIELD_DRIVER_ID,
    FIELD_HAS_DRIVER_PROPERTIES,
    FIELD_API_VERSION,
    FIELD_DRIVER_VERSION,
    FIELD_DRIVER_VERSION_RAW,
    FIELD_VENDOR_ID,
    FIELD_DEVICE_COUNT,
    FIELD_IS_TURNIP,
    FIELD_COUNT,
};

static void put(JNIEnv *env, jobjectArray array, int index, const char *value)
{
    jstring string = (*env)->NewStringUTF(env, value ? value : "");
    if (!string) return;
    (*env)->SetObjectArrayElement(env, array, index, string);
    (*env)->DeleteLocalRef(env, string);
}

static void put_u32(JNIEnv *env, jobjectArray array, int index, uint32_t value)
{
    char buffer[32];
    snprintf(buffer, sizeof(buffer), "%u", value);
    put(env, array, index, buffer);
}

static jobjectArray to_array(JNIEnv *env, const vessel_vk_driver *driver)
{
    char api[32];
    char driver_version[32];
    char raw[32];
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray array;

    if (!string_class) return NULL;
    array = (*env)->NewObjectArray(env, FIELD_COUNT, string_class, NULL);
    if (!array) return NULL;

    vessel_vk_format_version(driver->api_version, api, sizeof(api));
    vessel_vk_format_version(driver->driver_version, driver_version, sizeof(driver_version));
    snprintf(raw, sizeof(raw), "0x%08x", driver->driver_version);

    put(env, array, FIELD_OK, driver->ok ? "1" : "0");
    put(env, array, FIELD_SOURCE,
        driver->source == VESSEL_VK_SOURCE_ADRENOTOOLS ? "adrenotools" : "system");
    put(env, array, FIELD_ERROR, driver->error);
    put(env, array, FIELD_DEVICE_NAME, driver->device_name);
    put(env, array, FIELD_DRIVER_NAME, driver->driver_name);
    put(env, array, FIELD_DRIVER_INFO, driver->driver_info);
    put_u32(env, array, FIELD_DRIVER_ID, driver->driver_id);
    put(env, array, FIELD_HAS_DRIVER_PROPERTIES, driver->has_driver_properties ? "1" : "0");
    put(env, array, FIELD_API_VERSION, api);
    put(env, array, FIELD_DRIVER_VERSION, driver_version);
    put(env, array, FIELD_DRIVER_VERSION_RAW, raw);
    put_u32(env, array, FIELD_VENDOR_ID, driver->vendor_id);
    put_u32(env, array, FIELD_DEVICE_COUNT, driver->device_count);
    put(env, array, FIELD_IS_TURNIP, vessel_vk_driver_is_turnip(driver) ? "1" : "0");

    return array;
}

JNIEXPORT jobjectArray JNICALL
Java_app_vessel_data_GpuProbe_nativeProbeSystemVulkan(JNIEnv *env, jobject thiz)
{
    vessel_vk_driver driver;
    (void)thiz;
    vessel_vk_probe_system(&driver);
    return to_array(env, &driver);
}

JNIEXPORT jobjectArray JNICALL
Java_app_vessel_data_GpuProbe_nativeProbeCustomVulkan(JNIEnv *env, jobject thiz,
                                                      jstring hooks_dir, jstring driver_dir,
                                                      jstring driver_name)
{
    vessel_vk_driver driver;
    const char *hooks = NULL;
    const char *dir = NULL;
    const char *name = NULL;

    (void)thiz;

    if (hooks_dir) hooks = (*env)->GetStringUTFChars(env, hooks_dir, NULL);
    if (driver_dir) dir = (*env)->GetStringUTFChars(env, driver_dir, NULL);
    if (driver_name) name = (*env)->GetStringUTFChars(env, driver_name, NULL);

    vessel_vk_probe_adrenotools(hooks, dir, name, &driver);

    if (hooks) (*env)->ReleaseStringUTFChars(env, hooks_dir, hooks);
    if (dir) (*env)->ReleaseStringUTFChars(env, driver_dir, dir);
    if (name) (*env)->ReleaseStringUTFChars(env, driver_name, name);

    return to_array(env, &driver);
}

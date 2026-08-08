# libvesselgpu — "which Vulkan driver actually answered?", asked from Kotlin.
#
# Small on purpose. The interesting code is vulkan_driver.c, and it is written so
# that tools/vulkan/build.sh can compile the exact same translation unit into a
# standalone binary: the device tool and the app must not be able to disagree
# about what loaded.

add_library(vesselgpu SHARED
            src/vulkan_driver.c
            src/jni_gpu_probe.c)

target_include_directories(vesselgpu PRIVATE include)

# adrenotools is here for its two headers only. libadrenotools.so is dlopen'ed at
# runtime, not linked -- see the comment on ADRENOTOOLS_SONAME in vulkan_driver.c
# for why the dependency is deliberately late.
target_include_directories(vesselgpu PRIVATE ../adrenotools/include)

target_compile_options(vesselgpu PRIVATE -O2 -Wall -Wextra)

# No `vulkan` in this list, and that is the whole point: linking libvulkan would
# bind this library to the platform loader at load time, which is precisely the
# binding the custom-driver path exists to avoid. Vulkan is reached only through
# a dlopen'ed handle.
target_link_libraries(vesselgpu log)

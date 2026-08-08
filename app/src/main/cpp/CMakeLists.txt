cmake_minimum_required(VERSION 3.22.1)

# CXX as well as C since libadrenotools landed: driver.cpp, hook_impl.cpp and
# linkernsbypass are C++, and a C-only project silently produces no CXX
# toolchain for them.
project(vessel_native C CXX)

# libwinlator: the JNI half of the vendored X server.
add_subdirectory(winlator)

# libadrenotools + its hook objects. Vendored; see adrenotools/README.md.
#
# These are in the APK rather than in a .wcp for a reason that is not about
# convenience: libadrenotools' hook libraries are found by name in
# `applicationInfo.nativeLibraryDir`, and that directory is also the linker
# namespace's default library path, which is how the custom driver's own
# libc++_shared.so resolves. There is no other directory with those properties.
add_subdirectory(adrenotools)

# libvesselgpu: the Vulkan driver probe behind app.vessel.data.GpuProbe.
add_subdirectory(vessel)

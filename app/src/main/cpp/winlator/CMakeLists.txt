cmake_minimum_required(VERSION 3.22.1)

# VESSEL: `project(Winlator C)`, where upstream writes `Project(Winlator)`.
# Naming the language stops CMake enabling CXX as well and looking for a C++
# compiler this sub-project has no source for; `libwinlator` is pure C. The
# capital P is CMake-insensitive and only changed to match the rest of the file.
project(Winlator C)

# VESSEL: -Wimplicit-function-declaration is upstream's, and it is load-bearing
# rather than tidiness — clang 15 and later make that an error, and this code
# calls a handful of Android internals that no NDK header declares.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

include_directories(include)

# VESSEL: upstream also compiles gpu_helper.c (a Vulkan probe plus the shared
# EGLContext that libgladiorenderer needs) and wine_registry_editor.c. Vessel
# probes the GPU from Kotlin and edits the registry from the container layer,
# and there is no gladio, so neither is here.
add_library(winlator SHARED
            src/drawable.c
            src/gpu_image.c
            src/sysvshared_memory.c
            src/xconnector_epoll.c
            src/xinput_stream.c
            src/xoutput_stream.c
            src/arrays.c
            src/ring_buffer.c)

target_link_libraries(winlator
                      log
                      android
                      jnigraphics
                      EGL
                      GLESv2
                      GLESv3)

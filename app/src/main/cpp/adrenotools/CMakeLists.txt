# libadrenotools — vendored. See README.md in this directory.
#
# Upstream builds this with its own CMakeLists plus a linkernsbypass submodule.
# We build it from one file instead, because upstream's version makes decisions
# that are the *application's* to make here:
#
#   - `add_library(adrenotools ...)` with no explicit type, so it is STATIC
#     unless BUILD_SHARED_LIBS is on. Vessel needs it SHARED, and for a reason
#     that is not cosmetic: the Wine unix side `dlopen`s it by name out of the
#     APK's nativeLibraryDir (see patches/wine/0006). A static library cannot be
#     dlopen'ed, and linking it into libvesselgpu.so would only expose it to the
#     app process, which is the half that was never the problem.
#   - `src/bcenabler.cpp` and its generated `gen/bcenabler_patch.h` + hand-written
#     AArch64 patch stub. That is the BCn-texture-support enabler for the *stock*
#     Qualcomm blob; it has nothing to do with loading a driver, and it is the one
#     part of upstream that patches vendor machine code. Left out deliberately.
#
# Everything else is upstream's source, unmodified.

# The hooks are dlopen'ed BY SONAME from the namespace adrenotools creates, so
# their file names are load-bearing: driver.cpp asks for exactly
# "libhook_impl.so" and "libmain_hook.so", and hook_impl.cpp asks for
# "libfile_redirect_hook.so" and "libgsl_alloc_hook.so". Renaming a target here
# makes adrenotools fail at runtime with nothing but a logcat line to say so.

add_library(linkernsbypass STATIC
    lib/linkernsbypass/android_linker_ns.cpp
    lib/linkernsbypass/elf_soname_patcher.cpp)
target_include_directories(linkernsbypass PUBLIC lib/linkernsbypass)
target_compile_options(linkernsbypass PRIVATE -Wall -Wextra)
set_property(TARGET linkernsbypass PROPERTY POSITION_INDEPENDENT_CODE ON)

add_library(adrenotools SHARED src/driver.cpp)
target_include_directories(adrenotools PUBLIC include)
target_include_directories(adrenotools PRIVATE . src)
target_compile_options(adrenotools PRIVATE -Wall -Wextra)
target_link_libraries(adrenotools android log linkernsbypass)

# hook_impl carries the actual hook bodies; the three *_hook libraries are only
# interposition stubs that override a symbol and forward into it.
add_library(hook_impl SHARED src/hook/hook_impl.cpp)
target_include_directories(hook_impl PRIVATE include src/hook)
target_compile_options(hook_impl PRIVATE -Wall -Wextra)
target_link_libraries(hook_impl linkernsbypass log)
set_target_properties(hook_impl PROPERTIES CXX_VISIBILITY_PRESET hidden)

# `-z global` is what puts these in the namespace's LD_PRELOAD list when they are
# dlopen'ed RTLD_GLOBAL, which is the entire mechanism: without it the override of
# android_dlopen_ext never takes effect and the stock driver loads with no error.
foreach(hook main_hook file_redirect_hook gsl_alloc_hook)
    add_library(${hook} SHARED src/hook/${hook}.c)
    target_include_directories(${hook} PRIVATE include src/hook)
    target_compile_options(${hook} PRIVATE -Wall -Wextra)
    target_link_options(${hook} PRIVATE -z global)
    target_link_libraries(${hook} hook_impl)
    set_target_properties(${hook} PROPERTIES C_VISIBILITY_PRESET hidden)
endforeach()

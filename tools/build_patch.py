#!/usr/bin/env python3
import subprocess
import os
import glob

REPO_SRC = "/src/native/wine"
PATCHES_DIR = "/src/patches/wine"

# 1. Reset and clean git tree in native/wine
subprocess.run(["git", "-C", REPO_SRC, "reset", "--hard", "19d8528d64db0090f59779386b962e6a9ca40c38"], check=True)
subprocess.run(["git", "-C", REPO_SRC, "clean", "-fdx"], check=True)
subprocess.run(["git", "-C", REPO_SRC, "config", "user.email", "vessel@local"], check=True)
subprocess.run(["git", "-C", REPO_SRC, "config", "user.name", "Vessel Build"], check=True)

# 2. Apply patches 0002..0052
patches = sorted(glob.glob(f"{PATCHES_DIR}/00*.patch"))
for p in patches:
    if "0053" in p:
        continue
    print(f"Applying {os.path.basename(p)}...")
    subprocess.run(["git", "-C", REPO_SRC, "apply", p], check=True)

# 3. Commit state after 0052
subprocess.run(["git", "-C", REPO_SRC, "add", "-A"], check=True)
subprocess.run(["git", "-C", REPO_SRC, "commit", "-m", "applied 0002..0052"], check=True)

# 4. Modify virtual.c for 0053: both 1.5TB clamp AND 8MB minimum thread stack
virtual_c_path = f"{REPO_SRC}/dlls/ntdll/unix/virtual.c"
with open(virtual_c_path, "r") as f:
    content = f.read()

# Part A: 1.5TB V8 memory clamp
target_mem = """        if ((type & MEM_COMMIT) && commit_granularity_mask() != page_mask)
            size = ROUND_SIZE( base, size, commit_granularity_mask() );"""

addition_mem = """        if ((type & MEM_COMMIT) && commit_granularity_mask() != page_mask)
            size = ROUND_SIZE( base, size, commit_granularity_mask() );

        /* Vessel: Handle Chromium / V8 Sandbox 1.5TB virtual memory reservations
         * on memory-constrained (e.g. 39-bit VA) Android ARM64 kernels.
         *
         * Modern V8 in 64-bit builds (Chromium, Electron, VS Code) calls
         * VirtualAlloc(NULL, 0x15800000000, MEM_RESERVE, PAGE_NOACCESS) to create
         * a 1.47 TB (or 512 GB) pointer-compression sandbox cage. On Android
         * kernels with 39-bit virtual addressing (512 GB total user space), this
         * is physically larger than the entire userland address space, causing
         * map_view to return STATUS_NO_MEMORY and fatally crash the process.
         *
         * V8 only ever commits a few hundred MB to 4 GB inside this reservation.
         * Clamping oversized pure reservations (> 8 GB) to 8 GB allows map_view
         * to succeed within the available address space.
         */
        if ((type & MEM_RESERVE) && !(type & MEM_COMMIT) && !base && size > ((SIZE_T)8 * 1024 * 1024 * 1024))
        {
            WARN( "oversized reservation of 0x%lx bytes clamped to 8 GB for Chromium/V8\\n", size );
            size = (SIZE_T)8 * 1024 * 1024 * 1024;
        }"""

if target_mem not in content:
    raise ValueError("Target mem not found in virtual.c!")

content = content.replace(target_mem, addition_mem, 1)

# Part B: Raise thread stack floor to 8 MB for ARM64EC & V8
target_stack = """    size = max( reserve_size, commit_size );
    if (size < 1024 * 1024) size = 1024 * 1024;  /* Xlib needs a large stack */
    size = ROUND_SIZE( 0, size, granularity_mask );"""

addition_stack = """    size = max( reserve_size, commit_size );
    /* Vessel: ARM64EC translation frames and V8/Electron recursive workloads require
     * a generous stack (at least 8 MB). Default 256KB-1MB stacks overflow quickly under
     * ARM64EC_NT_XCONTEXT exception dispatching. */
    if (size < 8 * 1024 * 1024) size = 8 * 1024 * 1024;
    size = ROUND_SIZE( 0, size, granularity_mask );"""

if target_stack not in content:
    raise ValueError("Target stack not found in virtual.c!")

content = content.replace(target_stack, addition_stack, 1)

with open(virtual_c_path, "w") as f:
    f.write(content)

# Part C: Modify thread.c to expand chpev2_stack_size from 256KB to at least 8MB
thread_c_path = f"{REPO_SRC}/dlls/ntdll/unix/thread.c"
with open(thread_c_path, "r") as f:
    thread_content = f.read()

target_chpe = """        CHPE_V2_CPU_AREA_INFO *cpu_area;
        const SIZE_T chpev2_stack_size = 0x40000;

        /* emulator stack */"""

addition_chpe = """        CHPE_V2_CPU_AREA_INFO *cpu_area;
        /* Vessel: Expand CHPE v2 emulator stack from 256 KB to at least 8 MB
         * (or image MaximumStackSize). Guest x64 code under FEX runs on this
         * emulator stack, and 256 KB overflows immediately during deep recursion,
         * V8 compilation, and SEH dispatching. */
        const SIZE_T chpev2_stack_size = max( (SIZE_T)8 * 1024 * 1024, max( reserve_size, commit_size ) );

        /* emulator stack */"""

if target_chpe not in thread_content:
    raise ValueError("Target chpe not found in thread.c!")

thread_content = thread_content.replace(target_chpe, addition_chpe, 1)

with open(thread_c_path, "w") as f:
    f.write(thread_content)

# 5. Generate 0053 patch diff
diff = subprocess.run(["git", "-C", REPO_SRC, "diff", "dlls/ntdll/unix/virtual.c", "dlls/ntdll/unix/thread.c"], capture_output=True, text=True, check=True).stdout

header = """ntdll: clamp oversized virtual memory reservations and ensure adequate thread stack for ARM64EC / V8.

1. Modern Chromium and Electron (VS Code, Discord, browsers) allocate a 1.47 TB
(0x15800000000) or 512 GB (0x8000000000) MEM_RESERVE virtual memory cage for
V8 pointer compression sandbox security. On Android ARM64 kernels built with
39-bit virtual addressing (512 GB total user space), this is physically larger
than the entire address space and crashes with STATUS_NO_MEMORY. Clamping pure
MEM_RESERVE requests (> 8 GB) to 8 GB allows map_view() to succeed.

2. In ARM64EC translation under FEX, exception dispatches push ARM64EC_NT_XCONTEXT
frames (3.3 KB each), and guest x64 code executes on the CHPE v2 emulator stack.
The default CHPE stack was hardcoded to only 256 KB (0x40000), which overflows
instantly during Electron / V8 JIT recursive execution and CreateWindowEx.
Raising chpev2_stack_size and the virtual_alloc_thread_stack floor to 8 MB
provides ample headroom.

"""

patch_file = f"{PATCHES_DIR}/0053-ntdll-clamp-oversized-virtual-memory-reservations-on-android.patch"
with open(patch_file, "w") as f:
    f.write(header + diff)

print("0053 patch generated successfully.")

# 6. Verify 0053 applies cleanly
subprocess.run(["git", "-C", REPO_SRC, "checkout", "dlls/ntdll/unix/virtual.c", "dlls/ntdll/unix/thread.c"], check=True)
subprocess.run(["git", "-C", REPO_SRC, "apply", "--check", patch_file], check=True)
print("0053 apply check: PASSED!")

# 7. Clean tree back to upstream pin
subprocess.run(["git", "-C", REPO_SRC, "reset", "--hard", "19d8528d64db0090f59779386b962e6a9ca40c38"], check=True)
subprocess.run(["git", "-C", REPO_SRC, "clean", "-fdx"], check=True)
print("All done and tree restored!")

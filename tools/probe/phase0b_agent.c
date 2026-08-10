/*
 * Phase 0b of docs/LINUX-MODE.md §7: run the Phase 0 probes from the *app's own
 * process*, so they execute in u:r:untrusted_app with Seccomp 2 instead of
 * u:r:runas_app with Seccomp 0.
 *
 * Why an agent and not an APK change
 * ----------------------------------
 * The measurement Phase 0b needs is "a child of the app process", because a
 * forked/exec'd child inherits both the app's SELinux domain (no type
 * transition is defined for untrusted_app on system_file) and its seccomp
 * filter (filters survive execve). Adding a debug hook to the app would get
 * there too, and would cost an APK rebuild and an install on a device that is
 * in use.
 *
 * A debuggable app already has one supported way in: JVMTI. `am attach-agent`
 * makes ART dlopen this library *inside* the app process and call
 * Agent_OnAttach. Everything this file does after that point is what a debug
 * hook in Kotlin would have done — fork, redirect to a log, exec — so the
 * fidelity is the same and nothing in the shipped app changes.
 *
 *   adb shell run-as app.vessel sh -c 'cat … > linuxprobe/phase0b_agent.so'
 *   adb shell am attach-agent app.vessel \
 *       /data/data/app.vessel/linuxprobe/phase0b_agent.so=/data/data/app.vessel/linuxprobe/phase0b_run.sh
 *
 * The options string after `=` is the script to run; its output lands in
 * "<script>.out". Driven by ./tools/probe/phase0b.sh.
 *
 * Rules this file holds to, because it runs inside an app someone else is using:
 *   - it never blocks the calling thread. ART calls Agent_OnAttach on a real
 *     app thread; a waitpid there would freeze the UI. Double-fork, reap the
 *     intermediate immediately, and let init reap the worker.
 *   - it touches no JVMTI state at all — no capabilities, no callbacks, no
 *     class redefinition. It is a dlopen and a fork.
 *   - it cannot fail loudly. Every error path returns JNI_OK; the evidence that
 *     it ran is the log file existing, not an exception in the app.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include <android/log.h>

#define TAG "vessel-phase0b"

static const char DEFAULT_SCRIPT[] = "/data/data/app.vessel/linuxprobe/phase0b_run.sh";

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *vm, char *options, void *reserved)
{
    char script[512];
    char logpath[560];
    pid_t first;
    int st = 0;

    (void)vm;
    (void)reserved;

    snprintf(script, sizeof script, "%s",
             (options && *options) ? options : DEFAULT_SCRIPT);
    snprintf(logpath, sizeof logpath, "%s.out", script);

    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "attached in pid %d; running %s -> %s",
                        (int)getpid(), script, logpath);

    first = fork();
    if (first < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "fork failed: %s", strerror(errno));
        return JNI_OK;
    }
    if (first == 0) {
        /* Intermediate child: fork again so the worker is reparented to init and
         * the app never has to reap it. */
        pid_t worker = fork();
        if (worker == 0) {
            int fd;
            setsid();
            fd = open(logpath, O_WRONLY | O_CREAT | O_TRUNC, 0666);
            if (fd >= 0) { dup2(fd, 1); dup2(fd, 2); if (fd > 2) close(fd); }
            fd = open("/dev/null", O_RDONLY);
            if (fd >= 0) { dup2(fd, 0); if (fd > 0) close(fd); }
            /* /system/bin/sh is system_file and untrusted_app execs it without a
             * domain transition — the same shape as WineLaunch.linkerArgv's
             * /system/bin/linker64. The script, and everything it starts, is
             * therefore in the app's domain under the app's filter. */
            execl("/system/bin/sh", "sh", script, (char *)NULL);
            _exit(127);
        }
        _exit(0);
    }
    waitpid(first, &st, 0);   /* returns at once: the intermediate just _exit(0)s */
    return JNI_OK;
}

JNIEXPORT void JNICALL Agent_OnUnload(JavaVM *vm)
{
    (void)vm;
}

package com.winlator.xconnector;

import com.winlator.core.FileUtils;

import java.io.File;

public class UnixSocketConfig {
    public static final String SYSVSHM_SERVER_PATH = "/tmp/.sysvshm/SM0";
    public static final String ALSA_SERVER_PATH = "/tmp/.sound/AS0";
    public static final String PULSE_SERVER_PATH = "/tmp/.sound/PS0";
    public static final String XSERVER_PATH = "/tmp/.X11-unix/X0";
    public static final String VIRGL_SERVER_PATH = "/tmp/.virgl/V0";
    public static final String VORTEK_SERVER_PATH = "/tmp/.vortek/V0";
    public final String path;

    private UnixSocketConfig(String path) {
        this.path = path;
    }

    public static UnixSocketConfig create(String rootPath, String relativePath) {
        File socketFile = new File(rootPath, relativePath);

        String dirname = FileUtils.getDirname(relativePath);
        if (dirname.lastIndexOf("/") > 0) {
            File socketDir = new File(rootPath, FileUtils.getDirname(relativePath));
            FileUtils.delete(socketDir);
            socketDir.mkdirs();
        }
        else socketFile.delete();

        return new UnixSocketConfig(socketFile.getPath());
    }

    /**
     * VESSEL: the same well-known name, in the abstract namespace.
     *
     * <p>{@link #create} puts the socket under a root that the guest sees as
     * "/", which works because Winlator's guest runs inside a proot rootfs.
     * Vessel's guest is an ordinary child process of the app, so there is no
     * root to relocate {@link #XSERVER_PATH} into and Android has no /tmp for it
     * to land in either. The abstract namespace has no filesystem, so the guest's
     * unmodified libxcb — which tries {@code "\0/tmp/.X11-unix/X0"} before the
     * filesystem path — finds the server with no configuration at all.
     *
     * <p>The '@' prefix is the convention {@code createServerSocket} in
     * {@code xconnector_epoll.c} reads; it is not part of the name on the wire.
     * Nothing is deleted or created on disk here, which is the other half of the
     * point: an abstract name cannot go stale, so a session killed mid-flight
     * leaves nothing behind for the next bind to trip over.
     */
    public static UnixSocketConfig createAbstract(String name) {
        return new UnixSocketConfig("@" + name);
    }
}

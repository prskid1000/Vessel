package com.winlator.xserver.extensions;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class Extension {
    public static final byte START_MAJOR_OPCODE = -100;
    private final byte majorOpcode;
    protected final XServer xServer;

    public Extension(XServer xServer, byte majorOpcode) {
        this.xServer = xServer;
        this.majorOpcode = majorOpcode;
    }

    public abstract String getName();

    public byte getMajorOpcode() {
        return majorOpcode;
    }

    public byte getFirstErrorId() {
        return 0;
    }

    public byte getFirstEventId() {
        return 0;
    }

    public abstract void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError;

    /**
     * VESSEL: releases whatever this extension is holding on behalf of a client
     * that has gone away. A no-op by default, so an extension that owns nothing
     * per-client needs no change.
     *
     * <p>Called from {@link XClient#freeResources()}, under {@code lockAll()},
     * after the core resources have been freed and before the connection's
     * streams are destroyed.
     *
     * <p>This exists because the core managers are not the whole story. An
     * extension's state is keyed on client-generated XIDs just as a pixmap is,
     * and {@code ResourceIDs.free()} hands a disconnecting client's id base
     * straight back to the next one — so anything left behind is a collision
     * waiting for the next connection rather than a slow leak. Measured: a
     * second {@code tools/gfx/run-x11present.sh --wsi dri3} run against a live
     * session failed with {@code BadIdChoice} on its first swapchain pixmap.
     */
    public void freeClientResources(XClient client) {}
}

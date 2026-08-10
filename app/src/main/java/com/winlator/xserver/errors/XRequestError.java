package com.winlator.xserver.errors;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_ERROR;

import android.util.Log;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.XClient;

import java.io.IOException;

public class XRequestError extends Exception  {
    /**
     * VESSEL: one tag for every "this server refused something" line.
     *
     * It lives here rather than on an extension because this class is the one
     * place every refusal passes through, and a tag owned by DRI3 would be the
     * wrong home for a `BadWindow` from a core request.
     */
    public static final String PROTO_TAG = "VesselXProto";

    private final byte code;
    private final int data;

    public XRequestError(int code, int data) {
        this.code = (byte)code;
        this.data = data;
    }

    public byte getCode() {
        return code;
    }

    public int getData() {
        return data;
    }

    public void sendError(XClient client, byte opcode) throws IOException {
        // VESSEL: say what is being refused, and to which request.
        //
        // This is the single choke point for every X error this server sends to
        // any client, and until now it was silent. An X client is not obliged
        // to report an error it did not ask about — Mesa turns several into a
        // flat VK_ERROR_SURFACE_LOST_KHR — so a request refused here could end
        // a swapchain, or a session, with no record anywhere of what was asked.
        // That is precisely how zero-copy presented as an unexplained
        // `vkCreateSwapchainKHR` failure; see docs/TODO.md, "Zero-copy
        // present", and modifications 17 and 18.
        //
        // Both opcodes, because either alone is ambiguous: the major names the
        // extension (they are allocated from -100 upward at runtime) and the
        // minor names the request within it.
        Log.w(PROTO_TAG, getClass().getSimpleName() + " (code " + code + ", data " + data +
                ") for major opcode " + opcode + ", minor " + client.getRequestData());

        XOutputStream outputStream = client.getOutputStream();
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_ERROR);
            outputStream.writeByte(code);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(data);
            outputStream.writeShort(client.getRequestData());
            outputStream.writeByte(opcode);
            outputStream.writePad(21);
        }
    }
}

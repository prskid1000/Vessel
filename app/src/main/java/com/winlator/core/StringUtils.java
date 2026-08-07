// VESSEL: subset of upstream com/winlator/core/StringUtils.java, holding only
// the members the vendored X server reaches. Upstream's file is a grab bag for
// the whole Winlator app and carries an android.content.Context dependency.
package com.winlator.core;

import java.nio.charset.Charset;

public class StringUtils {
    public static String removeEndSlash(String value) {
        while (value.endsWith("/") || value.endsWith("\\")) value = value.substring(0, value.length() - 1);
        return value;
    }

    public static String fromANSIString(byte[] bytes) {
        return fromANSIString(bytes, null);
    }

    public static String fromANSIString(byte[] bytes, Charset charset) {
        String value = charset != null ? new String(bytes, charset) : new String(bytes);
        int indexOfNull = value.indexOf('\0');
        return indexOfNull != -1 ? value.substring(0, indexOfNull) : value;
    }
}

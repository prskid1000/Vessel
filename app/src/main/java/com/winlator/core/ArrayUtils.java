// VESSEL: subset of upstream com/winlator/core/ArrayUtils.java. Only the two
// concat() overloads are reachable from the X server (Property.java). The rest
// of upstream's file pulls in org.json, which makes any test that touches this
// class fail on the JVM with "not mocked".
package com.winlator.core;

import java.util.Arrays;

public abstract class ArrayUtils {
    public static byte[] concat(byte[]... elements) {
        byte[] result = Arrays.copyOf(elements[0], elements[0].length);
        for (int i = 1; i < elements.length; i++) {
            byte[] newArray = Arrays.copyOf(result, result.length + elements[i].length);
            System.arraycopy(elements[i], 0, newArray, result.length, elements[i].length);
            result = newArray;
        }
        return result;
    }

    @SafeVarargs
    public static <T> T[] concat(T[]... elements) {
        T[] result = Arrays.copyOf(elements[0], elements[0].length);
        for (int i = 1; i < elements.length; i++) {
            T[] newArray = Arrays.copyOf(result, result.length + elements[i].length);
            System.arraycopy(elements[i], 0, newArray, result.length, elements[i].length);
            result = newArray;
        }
        return result;
    }
}

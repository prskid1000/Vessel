// VESSEL: subset of upstream com/winlator/core/FileUtils.java. Only the socket
// directory teardown used by UnixSocketConfig is kept; upstream's file also
// does asset extraction, tar handling and content-URI IO, none of which the X
// server touches.
package com.winlator.core;

import java.io.File;
import java.nio.file.Files;

public abstract class FileUtils {
    public static boolean isSymlink(File file) {
        return Files.isSymbolicLink(file.toPath());
    }

    public static boolean delete(File targetFile) {
        if (targetFile == null) return false;
        if (targetFile.isDirectory()) {
            if (!isSymlink(targetFile)) if (!clear(targetFile)) return false;
        }
        return targetFile.delete();
    }

    public static boolean clear(File targetFile) {
        if (targetFile == null) return false;
        if (targetFile.isDirectory()) {
            File[] files = targetFile.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!delete(file)) return false;
                }
            }
        }
        return true;
    }

    public static String getDirname(String path) {
        if (path == null) return "";
        path = StringUtils.removeEndSlash(path);
        int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        // VESSEL: upstream does an unguarded substring(0, index), which throws
        // on a path with no separator at all.
        return index > 0 ? path.substring(0, index) : "";
    }
}

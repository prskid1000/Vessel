// VESSEL: subset of upstream com/winlator/core/AppUtils.java. runDelayed() is
// the only member the X server calls (Keyboard.java, for the synthetic key
// release behind a dead-key press). Upstream's file is Activity/UI plumbing.
package com.winlator.core;

import java.util.Timer;
import java.util.TimerTask;

public abstract class AppUtils {
    public static void runDelayed(final Runnable callback, long delay) {
        if (callback == null) return;
        (new Timer()).schedule(new TimerTask() {
            @Override
            public void run() {
                callback.run();
            }
        }, delay);
    }
}

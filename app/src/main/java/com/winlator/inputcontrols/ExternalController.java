// VESSEL: subset of upstream com/winlator/inputcontrols/ExternalController.java.
// Keyboard.java asks only "is this device a gamepad?", so that it can decline
// to translate gamepad key events into X keysyms. Upstream's class is the whole
// controller-profile and binding model, which Vessel does not have.
package com.winlator.inputcontrols;

import android.view.InputDevice;

public abstract class ExternalController {
    public static boolean isGameController(InputDevice device) {
        if (device == null) return false;
        String name = device.getName();
        if (name != null) {
            String lowerName = name.toLowerCase();
            if (lowerName.contains("uinput-fpc") || lowerName.contains("goodix_fp") || lowerName.contains("uinput-")) {
                return false;
            }
        }
        int sources = device.getSources();
        return !device.isVirtual() && ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
    }
}

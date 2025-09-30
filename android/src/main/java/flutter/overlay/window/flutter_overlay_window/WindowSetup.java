package flutter.overlay.window.flutter_overlay_window;

import android.view.Gravity;

final class WindowSetup {
    private WindowSetup() {}

    // Size (-1999 means "use default")
    static int width  = -1999; // match parent
    static int height = -1999; // full screen (computed)

    // Positioning / gravity
    static int gravity = Gravity.TOP | Gravity.LEFT;
    static String positionGravity = "auto"; // auto|left|right|none

    // Dragging
    static boolean enableDrag = true;

    // Notification text
    static String overlayTitle   = "Overlay running";
    static String overlayContent = "Tap the bubble to open Johar Ride";
    static int notificationVisibility = android.app.Notification.VISIBILITY_PUBLIC;

    // For API parity with older code paths
    static void setFlag(String any) {
        // no-op: we always use touchable flags in this build
    }
}
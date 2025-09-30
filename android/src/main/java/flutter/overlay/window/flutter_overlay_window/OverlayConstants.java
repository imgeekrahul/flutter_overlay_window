package flutter.overlay.window.flutter_overlay_window;

final class OverlayConstants {
    private OverlayConstants() {}

    // Flutter
    static final String CACHED_TAG = "jr_overlay_engine";
    static final String OVERLAY_TAG = "flutter.overlay.window/overlay";
    static final String MESSENGER_TAG = "flutter.overlay.window/messenger";

    // Notification / Service
    static final String CHANNEL_ID = "jr_overlay_channel";
    static final int NOTIFICATION_ID = 42421;

    // Intent extras & helpers
    static final int DEFAULT_XY = -1999;

    // Close extra key
    static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";
}

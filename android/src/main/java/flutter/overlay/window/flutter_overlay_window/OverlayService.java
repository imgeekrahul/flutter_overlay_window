package flutter.overlay.window.flutter_overlay_window;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import io.flutter.FlutterInjector;
import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.FlutterEngineGroup;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodChannel;

public class OverlayService extends Service implements View.OnTouchListener {

    private static final int DEFAULT_NAV_BAR_HEIGHT_DP = 48;
    private static final int DEFAULT_STATUS_BAR_HEIGHT_DP = 25;

    public static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";

    private static OverlayService instance;
    public static boolean isRunning = false;

    private WindowManager windowManager = null;
    private FlutterView flutterView;
    private MethodChannel flutterChannel;
    private BasicMessageChannel<Object> overlayMessageChannel;

    // Make the window CLICKABLE by default
    private static final int TOUCHABLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

    // Optional: pass-through mode (not used unless you call updateFlag("passthrough"))
    private static final int PASSTHROUGH_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

    private final Handler mAnimationHandler = new Handler();
    private float lastX, lastY;
    private int lastYPosition;
    private boolean dragging;
    private final Point szWindow = new Point();
    private Timer mTrayAnimationTimer;
    private TrayAnimationTimerTask mTrayTimerTask;

    private Resources mResources;
    private Integer mStatusBarHeight = -1;
    private Integer mNavigationBarHeight = -1;

    private static final int CLICK_TOLERANCE_PX = 10;
    private long downTime = 0;
    private float downX = 0f, downY = 0f;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        Log.d("OverlayService", "Destroying overlay service");
        if (windowManager != null && flutterView != null) {
            try { windowManager.removeView(flutterView); } catch (Throwable ignored) {}
            try { flutterView.detachFromFlutterEngine(); } catch (Throwable ignored) {}
        }
        windowManager = null;
        flutterView = null;
        isRunning = false;

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(OverlayConstants.NOTIFICATION_ID);
        instance = null;

        super.onDestroy();
    }

    private void openMainApp() {
        Context ctx = getApplicationContext();
        try {
          Intent launch = ctx.getPackageManager()
              .getLaunchIntentForPackage(ctx.getPackageName());
          if (launch != null) {
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
              | Intent.FLAG_ACTIVITY_CLEAR_TOP
              | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            ctx.startActivity(launch);
            return;
          }
        } catch (Exception e) {
          Log.e("OverlayService", "startActivity failed", e);
        }
        // Fallback for OEMs/Android 10+ that block direct background starts:
        showWakeupNotification();
    }
      
    private void showWakeupNotification() {
        // High-priority, full-screen intent to bring MainActivity forward
        Intent i = new Intent(this, com.joharride.driver.MainActivity.class)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ? PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;
      
        PendingIntent fsPi = PendingIntent.getActivity(this, 101, i, piFlags);
      
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle("Opening Johar Ride…")
            .setContentText("Tap if it doesn’t open automatically")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL) // helps OEMs allow full-screen
            .setFullScreenIntent(fsPi, true)
            .setAutoCancel(true)
            .setOngoing(false);
      
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(OverlayConstants.NOTIFICATION_ID + 1, b.build());
    }
      

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Keep running after swipe-away (START_STICKY + stopWithTask=false do most of the work)
        // You can optionally reschedule yourself here if some OEM kills you aggressively.
        super.onTaskRemoved(rootIntent);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.e("OverlayService", "onStartCommand: intent is null");
            return START_STICKY;
        }

        mResources = getApplicationContext().getResources();

        int startX = intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY);
        int startY = intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY);
        boolean isCloseWindow = intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false);

        if (isCloseWindow) {
            if (windowManager != null && flutterView != null) {
                try { windowManager.removeView(flutterView); } catch (Throwable ignored) {}
                try { flutterView.detachFromFlutterEngine(); } catch (Throwable ignored) {}
            }
            windowManager = null;
            flutterView = null;
            isRunning = false;
            return START_STICKY;
        }

        // If there’s an old view, remove it (don’t stop service)
        if (windowManager != null && flutterView != null) {
            try { windowManager.removeView(flutterView); } catch (Throwable ignored) {}
            try { flutterView.detachFromFlutterEngine(); } catch (Throwable ignored) {}
            windowManager = null;
            flutterView = null;
        }

        isRunning = true;
        Log.d("OverlayService", "Service started");

        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        if (engine != null) {
            try { engine.getLifecycleChannel().appIsResumed(); } catch (Throwable ignored) {}
        }

        // Forward messages to the plugin messenger if present
        if (overlayMessageChannel != null) {
            overlayMessageChannel.setMessageHandler((message, reply) -> {
                if (WindowSetup.messenger != null) {
                    try { WindowSetup.messenger.send(message); } catch (Throwable ignored) {}
                }
            });
        }

        // Create / attach the view
        flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
        flutterView.attachToFlutterEngine(FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG));
        flutterView.setFitsSystemWindows(true);
        flutterView.setFocusable(true);
        flutterView.setFocusableInTouchMode(true);
        flutterView.setBackgroundColor(Color.TRANSPARENT);

        // Tap → open app
        flutterView.setOnClickListener(v -> {
            Log.d("OverlayService", "Bubble tapped → openMainApp()");
            openMainApp();
        });

        // Drag if enabled
        if (WindowSetup.enableDrag) {
            flutterView.setOnTouchListener(this);
        }

        // Handle method calls from Dart (only the ones we actually use)
        if (flutterChannel != null) {
            flutterChannel.setMethodCallHandler((call, result) -> {
                switch (call.method) {
                    case "updateFlag": {
                        String flag = String.valueOf(call.argument("flag"));
                        updateOverlayFlag(result, flag);
                        break;
                    }
                    case "updateOverlayPosition": {
                        Integer x = call.argument("x");
                        Integer y = call.argument("y");
                        moveOverlay(x != null ? x : 0, y != null ? y : 0, result);
                        break;
                    }
                    case "resizeOverlay": {
                        Integer w = call.argument("width");
                        Integer h = call.argument("height");
                        Boolean enableDrag = call.argument("enableDrag");
                        resizeOverlay(w != null ? w : -1, h != null ? h : -1, enableDrag != null && enableDrag, result);
                        break;
                    }
                    case "openApp": {
                        openMainApp();
                        result.success(null);
                        break;
                    }
                    default:
                        result.notImplemented();
                        break;
                }
            });
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Window size for snap animation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            windowManager.getDefaultDisplay().getSize(szWindow);
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(dm);
            szWindow.set(dm.widthPixels, dm.heightPixels);
        }

        int dx = startX == OverlayConstants.DEFAULT_XY ? 0 : startX;
        int dy = startY == OverlayConstants.DEFAULT_XY ? -statusBarHeightPx() : startY;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowSetup.width == -1999 ? WindowManager.LayoutParams.MATCH_PARENT : WindowSetup.width,
                WindowSetup.height != -1999 ? WindowSetup.height : screenHeight(),
                0,
                -statusBarHeightPx(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                TOUCHABLE_FLAGS, // ← IMPORTANT: clickable overlay
                PixelFormat.TRANSLUCENT
        );
        params.gravity = WindowSetup.gravity;

        windowManager.addView(flutterView, params);
        moveOverlay(dx, dy, null);

        // Useful to verify flags at runtime
        Log.d("OverlayService", "params.flags=0x" + Integer.toHexString(params.flags));
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Ensure an engine exists (and cache it)
        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        if (engine == null) {
            Log.e("OverlayService", "No cached FlutterEngine — creating one for overlayMain");
            FlutterEngineGroup group = new FlutterEngineGroup(this);
            DartExecutor.DartEntrypoint entryPoint = new DartExecutor.DartEntrypoint(
                    FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                    "overlayMain"
            );
            engine = group.createAndRunEngine(this, entryPoint);
            FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, engine);
        }

        // Channels
        flutterChannel = new MethodChannel(engine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
        overlayMessageChannel = new BasicMessageChannel<>(engine.getDartExecutor(), OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);

        // Foreground notification → keeps the service alive
        createNotificationChannel();

        Intent notificationIntent = new Intent(this, com.joharride.driver.MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);

        int pendingFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

        final int notifyIcon = getDrawableResourceId("mipmap", "launcher");
        Notification notification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                .setContentTitle(WindowSetup.overlayTitle)
                .setContentText(WindowSetup.overlayContent)
                .setSmallIcon(notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon)
                .setContentIntent(contentIntent)
                .setVisibility(WindowSetup.notificationVisibility)
                .build();

        startForeground(OverlayConstants.NOTIFICATION_ID, notification);
        instance = this;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    OverlayConstants.CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    // ---------- Window operations ----------

    private void updateOverlayFlag(MethodChannel.Result result, String flag) {
        if (windowManager == null || flutterView == null) {
            if (result != null) result.success(false);
            return;
        }
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
        if ("passthrough".equalsIgnoreCase(flag)) {
            params.flags = PASSTHROUGH_FLAGS; // not clickable
        } else {
            params.flags = TOUCHABLE_FLAGS;   // clickable (default)
        }
        windowManager.updateViewLayout(flutterView, params);
        if (result != null) result.success(true);
    }

    private void resizeOverlay(int width, int height, boolean enableDrag, MethodChannel.Result result) {
        if (windowManager == null || flutterView == null) {
            if (result != null) result.success(false);
            return;
        }
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
        params.width = (width == -1999 || width == -1) ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(width);
        params.height = (height != 1999 && height != -1) ? dpToPx(height) : height;
        WindowSetup.enableDrag = enableDrag;
        windowManager.updateViewLayout(flutterView, params);
        if (result != null) result.success(true);
    }

    private void moveOverlay(int x, int y, MethodChannel.Result result) {
        if (windowManager == null || flutterView == null) {
            if (result != null) result.success(false);
            return;
        }
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
        params.x = (x == -1999 || x == -1) ? -1 : dpToPx(x);
        params.y = dpToPx(y);
        windowManager.updateViewLayout(flutterView, params);
        if (result != null) result.success(true);
    }

    public static Map<String, Double> getCurrentPosition() {
        if (instance != null && instance.flutterView != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
            Map<String, Double> m = new HashMap<>();
            m.put("x", instance.pxToDp(params.x));
            m.put("y", instance.pxToDp(params.y));
            return m;
        }
        return null;
    }

    public static boolean moveOverlay(int x, int y) {
        if (instance != null && instance.flutterView != null && instance.windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
            params.x = (x == -1999 || x == -1) ? -1 : instance.dpToPx(x);
            params.y = instance.dpToPx(y);
            instance.windowManager.updateViewLayout(instance.flutterView, params);
            return true;
        }
        return false;
    }

    // ---------- Touch / drag with tap-to-open ----------

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (windowManager == null || !WindowSetup.enableDrag) return false;

        WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                downTime = android.os.SystemClock.elapsedRealtime();
                lastX = downX = event.getRawX();
                lastY = downY = event.getRawY();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - lastX;
                float dy = event.getRawY() - lastY;
                if (!dragging && (dx * dx + dy * dy) < 25) return false; // ignore tiny jitter

                lastX = event.getRawX();
                lastY = event.getRawY();

                boolean invertX = (WindowSetup.gravity == (Gravity.TOP | Gravity.RIGHT))
                        || (WindowSetup.gravity == (Gravity.CENTER | Gravity.RIGHT))
                        || (WindowSetup.gravity == (Gravity.BOTTOM | Gravity.RIGHT));
                boolean invertY = (WindowSetup.gravity == (Gravity.BOTTOM | Gravity.LEFT))
                        || (WindowSetup.gravity == Gravity.BOTTOM)
                        || (WindowSetup.gravity == (Gravity.BOTTOM | Gravity.RIGHT));

                int xx = params.x + ((int) dx * (invertX ? -1 : 1));
                int yy = params.y + ((int) dy * (invertY ? -1 : 1));
                params.x = xx;
                params.y = yy;

                windowManager.updateViewLayout(flutterView, params);
                dragging = true;
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float totalDx = Math.abs(event.getRawX() - downX);
                float totalDy = Math.abs(event.getRawY() - downY);
                long dt = android.os.SystemClock.elapsedRealtime() - downTime;

                // Quick, tiny movement → treat as TAP: open app
                if (!dragging && totalDx < CLICK_TOLERANCE_PX && totalDy < CLICK_TOLERANCE_PX && dt < 250) {
                    Log.d("OverlayService", "Tap detected → openMainApp()");
                    openMainApp();
                    return true;
                }

                lastYPosition = params.y;
                if (!"none".equals(WindowSetup.positionGravity)) {
                    windowManager.updateViewLayout(flutterView, params);
                    mTrayTimerTask = new TrayAnimationTimerTask();
                    mTrayAnimationTimer = new Timer();
                    mTrayAnimationTimer.schedule(mTrayTimerTask, 0, 25);
                }
                return true;
            }
        }
        return false;
    }

    private class TrayAnimationTimerTask extends TimerTask {
        int mDestX;
        int mDestY;
        final WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();

        public TrayAnimationTimerTask() {
            super();
            mDestY = lastYPosition;
            switch (WindowSetup.positionGravity) {
                case "auto":
                    mDestX = (params.x + (flutterView.getWidth() / 2)) <= szWindow.x / 2
                            ? 0 : szWindow.x - flutterView.getWidth();
                    return;
                case "left":
                    mDestX = 0;
                    return;
                case "right":
                    mDestX = szWindow.x - flutterView.getWidth();
                    return;
                default:
                    mDestX = params.x;
                    mDestY = params.y;
                    break;
            }
        }

        @Override
        public void run() {
            mAnimationHandler.post(() -> {
                params.x = (2 * (params.x - mDestX)) / 3 + mDestX;
                params.y = (2 * (params.y - mDestY)) / 3 + mDestY;
                if (windowManager != null) windowManager.updateViewLayout(flutterView, params);
                if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) {
                    try { cancel(); } catch (Throwable ignored) {}
                    try { mTrayAnimationTimer.cancel(); } catch (Throwable ignored) {}
                }
            });
        }
    }

    // ---------- helpers ----------

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private int screenHeight() {
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        return inPortrait()
                ? dm.heightPixels + statusBarHeightPx() + navigationBarHeightPx()
                : dm.heightPixels + statusBarHeightPx();
    }

    private int statusBarHeightPx() {
        if (mStatusBarHeight == -1) {
            int id = mResources.getIdentifier("status_bar_height", "dimen", "android");
            mStatusBarHeight = id > 0 ? mResources.getDimensionPixelSize(id) : dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
        }
        return mStatusBarHeight;
    }

    private int navigationBarHeightPx() {
        if (mNavigationBarHeight == -1) {
            int id = mResources.getIdentifier("navigation_bar_height", "dimen", "android");
            mNavigationBarHeight = id > 0 ? mResources.getDimensionPixelSize(id) : dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
        }
        return mNavigationBarHeight;
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                (float) dp,
                mResources.getDisplayMetrics()
        );
    }

    private double pxToDp(int px) {
        return (double) px / mResources.getDisplayMetrics().density;
    }

    private boolean inPortrait() {
        return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }
}
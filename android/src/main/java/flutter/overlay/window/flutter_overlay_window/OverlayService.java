package flutter.overlay.window.flutter_overlay_window;

import android.app.ActivityManager;
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

    private static final String TAG = "OverlayService";

    private final int DEFAULT_NAV_BAR_HEIGHT_DP = 48;
    private final int DEFAULT_STATUS_BAR_HEIGHT_DP = 25;

    private Integer mStatusBarHeight = -1;
    private Integer mNavigationBarHeight = -1;
    private Resources mResources;

    private static OverlayService instance;
    public static boolean isRunning = false;

    private WindowManager windowManager = null;
    private FlutterView flutterView;
    private MethodChannel flutterChannel;
    private BasicMessageChannel<Object> overlayMessageChannel;

    private static final int TOUCHABLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

    private static final float MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f;

    private final Handler mAnimationHandler = new Handler();
    private float lastX, lastY;
    private int lastYPosition;
    private boolean dragging;
    private final Point szWindow = new Point();
    private Timer mTrayAnimationTimer;
    private TrayAnimationTimerTask mTrayTimerTask;

    private static final int CLICK_TOLERANCE_PX = 10;
    private long downTime = 0;
    private float downX = 0f, downY = 0f;

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        Log.d(TAG, "Destroying the overlay window service");
        if (windowManager != null && flutterView != null) {
            try { windowManager.removeView(flutterView); } catch (Throwable ignored) {}
            windowManager = null;
            try { flutterView.detachFromFlutterEngine(); } catch (Throwable ignored) {}
            flutterView = null;
        }
        isRunning = false;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(OverlayConstants.NOTIFICATION_ID);
        instance = null;
        super.onDestroy();
    }

    /** Bring task to front if present; else relaunch the app from launcher. */
    private void openOrBringMainApp() {
        try {
            final String appId = getApplicationContext().getPackageName();
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

            boolean brought = false;
            if (am != null) {
                try {
                    for (ActivityManager.AppTask task : am.getAppTasks()) {
                        if (task.getTaskInfo() != null &&
                                task.getTaskInfo().baseIntent != null &&
                                task.getTaskInfo().baseIntent.getComponent() != null &&
                                appId.equals(task.getTaskInfo().baseIntent.getComponent().getPackageName())) {
                            task.moveToFront();
                            brought = true;
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            if (!brought) {
                Intent launch = getPackageManager().getLaunchIntentForPackage(appId);
                if (launch != null) {
                    launch.setAction(Intent.ACTION_MAIN);
                    launch.addCategory(Intent.CATEGORY_LAUNCHER);
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(launch);
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "openOrBringMainApp() failed", e);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.e(TAG, "onStartCommand: Intent is null!");
            return START_STICKY;
        }

        mResources = getApplicationContext().getResources();

        boolean isCloseWindow = intent.getBooleanExtra(OverlayConstants.INTENT_EXTRA_IS_CLOSE_WINDOW, false);
        if (isCloseWindow) {
            if (windowManager != null && flutterView != null) {
                try { windowManager.removeView(flutterView); } catch (Throwable ignored) {}
                windowManager = null;
                try { flutterView.detachFromFlutterEngine(); } catch (Throwable ignored) {}
            }
            isRunning = false;
            return START_STICKY;
        }

        if (windowManager != null && flutterView != null) {
            try { windowManager.removeView(flutterView); } catch (Throwable ignored) {}
            windowManager = null;
            try { flutterView.detachFromFlutterEngine(); } catch (Throwable ignored) {}
        }

        isRunning = true;
        Log.d(TAG, "Service started");

        // Ensure a FlutterEngine for the overlay entrypoint
        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        if (engine == null) {
            FlutterEngineGroup group = new FlutterEngineGroup(this);
            DartExecutor.DartEntrypoint entryPoint = new DartExecutor.DartEntrypoint(
                    FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                    "overlayMain" // Dart entrypoint
            );
            engine = group.createAndRunEngine(this, entryPoint);
            FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, engine);
        } else {
            try { engine.getLifecycleChannel().appIsResumed(); } catch (Throwable ignored) {}
        }

        // Channels
        flutterChannel = new MethodChannel(engine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
        overlayMessageChannel = new BasicMessageChannel<>(
                engine.getDartExecutor(), OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);

        // Allow Dart to tell us to open the app
        flutterChannel.setMethodCallHandler((call, result) -> {
            switch (call.method) {
                case "openApp":
                    openOrBringMainApp();
                    result.success(null);
                    break;
                case "updateFlag": {
                    String flag = String.valueOf(call.argument("flag"));
                    updateOverlayFlag(result, flag);
                    break;
                }
                case "updateOverlayPosition": {
                    Integer x = call.argument("x");
                    Integer y = call.argument("y");
                    moveOverlay(x == null ? 0 : x, y == null ? 0 : y, result);
                    break;
                }
                case "resizeOverlay": {
                    Integer w = call.argument("width");
                    Integer h = call.argument("height");
                    Boolean drag = call.argument("enableDrag");
                    resizeOverlay(w == null ? -1 : w, h == null ? -1 : h, drag != null && drag, result);
                    break;
                }
                default:
                    result.notImplemented();
            }
        });

        // View
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
        flutterView.attachToFlutterEngine(engine);
        flutterView.setFitsSystemWindows(true);
        flutterView.setFocusable(true);
        flutterView.setFocusableInTouchMode(true);
        flutterView.setBackgroundColor(Color.TRANSPARENT);
        flutterView.setOnTouchListener(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            windowManager.getDefaultDisplay().getSize(szWindow);
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(dm);
            szWindow.set(dm.widthPixels, dm.heightPixels);
        }

        int startX = intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY);
        int startY = intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY);
        int dx = startX == OverlayConstants.DEFAULT_XY ? 0 : startX;
        int dy = startY == OverlayConstants.DEFAULT_XY ? -statusBarHeightPx() : startY;

        int bubble = dpToPx(56);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            (WindowSetup.width  == -1999 || WindowSetup.width  == -1) ? bubble : dpToPx(WindowSetup.width),
            (WindowSetup.height == -1999 || WindowSetup.height == -1) ? bubble : dpToPx(WindowSetup.height),
            0,
            0,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            TOUCHABLE_FLAGS,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = (WindowSetup.gravity == 0) ? (Gravity.BOTTOM | Gravity.RIGHT) : WindowSetup.gravity;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
        }
        params.gravity = WindowSetup.gravity;

        try {
            windowManager.addView(flutterView, params);
            moveOverlay(dx, dy, null);
          } catch (Throwable t) {
            Log.e(TAG, "addView failed", t);
          }

        return START_STICKY;
    }

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
            mStatusBarHeight = (id > 0) ? mResources.getDimensionPixelSize(id) : dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
        }
        return mStatusBarHeight;
    }

    private int navigationBarHeightPx() {
        if (mNavigationBarHeight == -1) {
            int id = mResources.getIdentifier("navigation_bar_height", "dimen", "android");
            mNavigationBarHeight = (id > 0) ? mResources.getDimensionPixelSize(id) : dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
        }
        return mNavigationBarHeight;
    }

    private void updateOverlayFlag(MethodChannel.Result result, String flag) {
        if (windowManager != null && flutterView != null) {
            WindowSetup.setFlag(flag);
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            p.flags = TOUCHABLE_FLAGS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) p.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
            windowManager.updateViewLayout(flutterView, p);
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void resizeOverlay(int width, int height, boolean enableDrag, MethodChannel.Result result) {
        if (windowManager != null && flutterView != null) {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            p.width  = (width == -1999 || width == -1) ? -1 : dpToPx(width);
            p.height = (height != 1999  || height != -1) ? dpToPx(height) : height;
            WindowSetup.enableDrag = enableDrag;
            windowManager.updateViewLayout(flutterView, p);
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void moveOverlay(int x, int y, MethodChannel.Result result) {
        if (windowManager != null && flutterView != null) {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            p.x = (x == -1999 || x == -1) ? -1 : dpToPx(x);
            p.y = dpToPx(y);
            windowManager.updateViewLayout(flutterView, p);
            if (result != null) result.success(true);
        } else {
            if (result != null) result.success(false);
        }
    }

    public static Map<String, Double> getCurrentPosition() {
        if (instance != null && instance.flutterView != null) {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
            Map<String, Double> pos = new HashMap<>();
            pos.put("x", instance.pxToDp(p.x));
            pos.put("y", instance.pxToDp(p.y));
            return pos;
        }
        return null;
    }

    public static boolean moveOverlay(int x, int y) {
        if (instance != null && instance.flutterView != null && instance.windowManager != null) {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
            p.x = (x == -1999 || x == -1) ? -1 : instance.dpToPx(x);
            p.y = instance.dpToPx(y);
            instance.windowManager.updateViewLayout(instance.flutterView, p);
            return true;
        }
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Pre-warm engine
        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        if (engine == null) {
            FlutterEngineGroup group = new FlutterEngineGroup(this);
            DartExecutor.DartEntrypoint entryPoint = new DartExecutor.DartEntrypoint(
                    FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                    "overlayMain"
            );
            engine = group.createAndRunEngine(this, entryPoint);
            FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, engine);
        }

        // Foreground notification
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, com.joharride.driver.MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        int pendingFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ? PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

        final int notifyIcon = getDrawableResourceId("mipmap", "launcher");
        Notification notification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                .setContentTitle(WindowSetup.overlayTitle)
                .setContentText(WindowSetup.overlayContent)
                .setSmallIcon(notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon)
                .setContentIntent(pendingIntent)
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
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(ch);
        }
    }

    private int getDrawableResourceId(String resType, String name) {
        return getApplicationContext().getResources()
                .getIdentifier(String.format("ic_%s", name), resType, getApplicationContext().getPackageName());
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_DIP, (float) dp, mResources.getDisplayMetrics());
    }

    private double pxToDp(int px) {
        return (double) px / mResources.getDisplayMetrics().density;
    }

    private boolean inPortrait() {
        return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    /** Drag + tap detection; tap opens/relaunches app */
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (windowManager == null) return false;

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
                if (!dragging && dx * dx + dy * dy < 25) return false;

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

                if (!dragging && totalDx < CLICK_TOLERANCE_PX && totalDy < CLICK_TOLERANCE_PX && dt < 250) {
                    openOrBringMainApp();
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
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();

        public TrayAnimationTimerTask() {
            super();
            mDestY = lastYPosition;
            switch (WindowSetup.positionGravity) {
                case "auto":
                    mDestX = (params.x + (flutterView.getWidth() / 2)) <= szWindow.x / 2 ? 0 : szWindow.x - flutterView.getWidth();
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
                    if (mTrayAnimationTimer != null) mTrayAnimationTimer.cancel();
                }
            });
        }
    }
}
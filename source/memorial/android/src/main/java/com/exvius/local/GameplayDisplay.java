package com.exvius.local;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Window;
import android.view.WindowManager;

final class GameplayDisplay implements Application.ActivityLifecycleCallbacks {
    private static final String GAME_ACTIVITY = "com.square_enix.android_googleplay.FFBEWW.Lapis";
    private static final int KEEP_SCREEN_ON = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
    private static final long DIM_DELAY_MS = 120_000;
    private static final float AUTO_BRIGHTNESS = 0.15f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable dim = this::dimScreen;
    private Activity foreground;
    private boolean keepAwake;
    private boolean fullAuto;
    private boolean addedKeepScreenOn;
    private boolean dimPending;
    private Float previousBrightness;

    GameplayDisplay(Application app) {
        app.registerActivityLifecycleCallbacks(this);
    }

    void setMode(boolean awake, boolean auto) {
        ui.post(() -> {
            keepAwake = awake;
            fullAuto = awake && auto;
            apply();
        });
    }

    private void apply() {
        if (foreground == null) return;
        Window window = foreground.getWindow();
        if (!keepAwake) {
            restore(window);
            return;
        }
        if ((window.getAttributes().flags & KEEP_SCREEN_ON) == 0) {
            window.addFlags(KEEP_SCREEN_ON);
            addedKeepScreenOn = true;
        }
        if (!fullAuto) {
            restoreBrightness(window);
        } else if (!dimPending && previousBrightness == null) {
            dimPending = true;
            ui.postDelayed(dim, DIM_DELAY_MS);
        }
    }

    private void dimScreen() {
        dimPending = false;
        if (foreground == null || !keepAwake || !fullAuto) return;
        Window window = foreground.getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        previousBrightness = attributes.screenBrightness;
        float current = previousBrightness;
        if (current < 0) {
            current = Settings.System.getInt(foreground.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, 255) / 255f;
        }
        attributes.screenBrightness = Math.min(current, AUTO_BRIGHTNESS);
        window.setAttributes(attributes);
    }

    private void restoreBrightness(Window window) {
        ui.removeCallbacks(dim);
        dimPending = false;
        if (previousBrightness != null) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.screenBrightness = previousBrightness;
            window.setAttributes(attributes);
            previousBrightness = null;
        }
    }

    private void restore(Window window) {
        restoreBrightness(window);
        if (addedKeepScreenOn) window.clearFlags(KEEP_SCREEN_ON);
        addedKeepScreenOn = false;
    }

    @Override public void onActivityResumed(Activity activity) {
        if (!GAME_ACTIVITY.equals(activity.getClass().getName())) return;
        foreground = activity;
        apply();
    }

    @Override public void onActivityPaused(Activity activity) {
        if (foreground != activity) return;
        restore(activity.getWindow());
        foreground = null;
    }

    @Override public void onActivityDestroyed(Activity activity) { onActivityPaused(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}

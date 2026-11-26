package top.niunaijun.blackbox.utils.auth;

import android.app.Activity;
import android.util.Log;

import java.util.Locale;

import top.niunaijun.blackbox.core.ZoroRuntimeLogger;

/**
 * Diagnostics only.
 *
 * The old version read plaintext credentials from /sdcard/twitter_creds.txt and
 * injected JavaScript into Twitter's WebView. That is removed: it was web login,
 * handled passwords unacceptably, and is not needed now that the game's own
 * in-app OAuthActivity is allowed to run.
 */
public final class TwitterWebViewAutoLogin {
    private static final String TAG = "TwitterWebViewAutoLogin";

    private TwitterWebViewAutoLogin() {
    }

    public static void startIfNeeded(Activity activity) {
        if (activity == null) {
            return;
        }
        String name = activity.getClass().getName();
        if (name.toLowerCase(Locale.ROOT).contains("twitter")
                || name.toLowerCase(Locale.ROOT).contains("oauth")) {
            String message = "in-app OAuth activity started: " + name;
            Log.d(TAG, message);
            try {
                ZoroRuntimeLogger.log("twitter-login", message);
            } catch (Throwable ignored) {
            }
        }
    }
}

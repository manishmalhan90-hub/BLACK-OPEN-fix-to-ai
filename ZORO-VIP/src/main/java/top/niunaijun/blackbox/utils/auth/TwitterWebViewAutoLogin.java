package top.niunaijun.blackbox.utils.auth;

import android.app.Activity;
import android.util.Log;

import java.util.Locale;

/**
 * Diagnostics only.
 *
 * <p>The old version read plaintext credentials from /sdcard/twitter_creds.txt
 * and injected JavaScript into Twitter's WebView. That code is gone: it was
 * web login, handled passwords unacceptably, and is not used by the current
 * SDK/app login path. This class now only records which Twitter activity the
 * game created so a fresh login attempt is visible in the uploaded logs.</p>
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
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("twitter") || lower.contains("oauth") || lower.contains("customtab")) {
            String message = "Twitter auth activity created: " + name;
            TwitterLoginRedirect.log(message);
            Log.d(TAG, message);
        }
    }
}

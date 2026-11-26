package top.niunaijun.blackbox.utils.auth;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.util.Locale;

import top.niunaijun.blackbox.core.ZoroRuntimeLogger;

/**
 * Login-flow diagnostics only.
 *
 * <p>The old version read plaintext credentials from /sdcard/twitter_creds.txt
 * and injected JavaScript into Twitter's WebView. That code is gone. This class
 * now records which Twitter/Facebook web activity the game created, its intent
 * extras and the effective IMSDK config so web login errors can be diagnosed
 * from the dedicated zoro_login.txt digest.</p>
 */
public final class TwitterWebViewAutoLogin {

    private static final String TAG = "LoginWebDiagnostics";
    private static String sLastLog;
    private static long sLastLogTime;

    private TwitterWebViewAutoLogin() {
    }

    public static void startIfNeeded(Activity activity) {
        if (activity == null) {
            return;
        }
        String name = activity.getClass().getName();
        String lower = name.toLowerCase(Locale.ROOT);
        boolean loginActivity = lower.contains("twitter")
                || lower.contains("facebook")
                || lower.contains("oauth")
                || lower.contains("customtab")
                || lower.contains("webloginactivity");
        if (!loginActivity) {
            return;
        }

        WebLoginBridge.register(activity, activity.getClassLoader());
        // A Facebook result can outlive the virtual process after an ART abort.
        // Recreate the IMSDK listener in this activity and replay that result.
        WebLoginBridge.consumePendingForFacebookActivity(activity);

        Intent intent = activity.getIntent();
        Bundle extras = intent == null ? null : intent.getExtras();
        String channel = extras == null ? "" : String.valueOf(extras.get("channel"));
        String appId = extras == null ? "" : String.valueOf(extras.get("app_id"));
        String data = intent == null || intent.getDataString() == null
                ? "" : intent.getDataString();
        String webUrl = extras == null ? null : extras.getString("url");
        String host = "(none)";
        if (!data.isEmpty()) {
            try {
                String parsed = android.net.Uri.parse(data).getHost();
                if (parsed != null) {
                    host = parsed;
                }
            } catch (Throwable ignored) {
                host = "(invalid)";
            }
        }

        StringBuilder sb = new StringBuilder("login activity created: ").append(name);
        if (!channel.isEmpty()) {
            sb.append(" | channel=").append(channel);
        }
        if (!appId.isEmpty()) {
            sb.append(" | app_id=").append(appId);
        }
        sb.append(" | host=").append(host);
        if (webUrl != null && !webUrl.isEmpty()) {
            try {
                String webHost = android.net.Uri.parse(webUrl).getHost();
                sb.append(" | web_url_host=").append(webHost == null ? "(none)" : webHost);
            } catch (Throwable ignored) {
                sb.append(" | web_url_host=(invalid)");
            }
            // Never log the full OAuth URL: it contains request tokens.
        }
        if (lower.contains("itopcustomtabactivity")) {
            sb.append(" | route=custom-tab-proxy");
        } else if (lower.contains("webloginactivity")) {
            sb.append(" | route=built-in-WebView");
        }
        log(sb.toString());
        log("effective web login config: " + TwitterLoginModeFix.snapshot(activity.getClassLoader()));
        if (lower.contains("itopcustomtabactivity")) {
            log("custom-tab proxy still active; IMSDK browser switch was not honoured on this attempt");
        }
    }

    private static void log(String message) {
        if (message == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (message.equals(sLastLog) && now - sLastLogTime < 1500L) {
            return;
        }
        sLastLog = message;
        sLastLogTime = now;
        ZoroRuntimeLogger.logLogin("login-activity", message);
        Log.d(TAG, message);
    }
}

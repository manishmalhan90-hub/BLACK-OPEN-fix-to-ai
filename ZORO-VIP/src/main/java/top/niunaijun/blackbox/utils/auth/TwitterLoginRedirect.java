package top.niunaijun.blackbox.utils.auth;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Scanner;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.ZoroRuntimeLogger;

/**
 * Twitter/X login compatibility layer for the virtual process.
 *
 * The game uses TwitterKit's OAuthActivity, which is an IN-APP WebView (not the
 * browser). Modern X no longer ships the legacy SingleSignOnActivity, so
 * blocking OAuthActivity made login fail with ActivityNotFoundException.
 *
 * Default behaviour is now to ALLOW the game's own in-app OAuthActivity.
 * Legacy app SSO is attempted only when /sdcard/twitter_mode.txt contains
 * "app" AND com.twitter.android/.SingleSignOnActivity is actually installed.
 */
public final class TwitterLoginRedirect {

    private static final String TAG = "TwitterLoginRedirect";
    private static final String LOG_PATH = "/sdcard/twitter_block.log";
    private static final String TWITTER_APP_PKG = "com.twitter.android";
    private static final String SSO_CLASS = TWITTER_APP_PKG + ".SingleSignOnActivity";
    private static final String MODE_FILE = "/sdcard/twitter_mode.txt";

    private TwitterLoginRedirect() {
    }

    /** Legacy app SSO is opt-in only. Default = keep the game's in-app OAuth. */
    private static boolean appModeEnabled() {
        try {
            File f = new File(MODE_FILE);
            if (!f.exists()) {
                return false;
            }
            Scanner s = new Scanner(f).useDelimiter("\\A");
            String v = s.hasNext() ? s.next().trim().toLowerCase(Locale.ROOT) : "";
            s.close();
            return v.equals("app") || v.equals("sso");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean legacySsoAvailable() {
        try {
            Intent sso = new Intent().setComponent(new ComponentName(TWITTER_APP_PKG, SSO_CLASS));
            return BlackBoxCore.getContext().getPackageManager()
                    .resolveActivity(sso, PackageManager.MATCH_DEFAULT_ONLY) != null;
        } catch (Throwable t) {
            log("legacy SSO check failed: " + t);
            return false;
        }
    }

    /** Return true only when we can really hand off to the legacy Twitter app. */
    public static boolean shouldIntercept(Intent intent) {
        if (!appModeEnabled()) {
            return false;
        }
        if (!legacySsoAvailable()) {
            log("legacy Twitter SSO activity missing -> allow game in-app OAuthActivity");
            return false;
        }
        if (intent == null) {
            return false;
        }
        try {
            ComponentName cn = intent.getComponent();
            if (cn != null) {
                String cls = cn.getClassName().toLowerCase(Locale.ROOT);
                if (cls.contains("oauthactivity")
                        || cls.contains("twitter") && (cls.contains("webview")
                        || cls.contains("auth") || cls.contains("login"))) {
                    log("shouldIntercept: " + cn.flattenToString());
                    return true;
                }
            }
            String data = intent.getDataString() == null ? "" : intent.getDataString().toLowerCase(Locale.ROOT);
            if ((data.contains("twitter.com") || data.contains("api.twitter.com"))
                    && (data.contains("oauth") || data.contains("authorize"))) {
                log("shouldIntercept: twitter oauth URL");
                return true;
            }
        } catch (Throwable t) {
            log("shouldIntercept error: " + t);
        }
        return false;
    }

    /** Redirect to the real Twitter SSO activity only when it exists. */
    public static void intercept(Intent original) {
        log("redirecting to legacy Twitter SSO activity");
        try {
            Intent sso = new Intent().setComponent(new ComponentName(TWITTER_APP_PKG, SSO_CLASS));
            sso.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Bundle extras = original == null ? null : original.getExtras();
            if (extras != null) {
                sso.putExtras(extras);
                Object authConfig = extras.get("auth_config");
                if (authConfig instanceof Bundle) {
                    Bundle cfg = (Bundle) authConfig;
                    String consumerKey = firstString(cfg, "consumerKey", "consumer_key", "ck");
                    String consumerSecret = firstString(cfg, "consumerSecret", "consumer_secret", "cs");
                    if (consumerKey != null) {
                        sso.putExtra("ck", consumerKey);
                    }
                    if (consumerSecret != null) {
                        sso.putExtra("cs", consumerSecret);
                    }
                }
            }
            if (original != null && original.getData() != null) {
                sso.setData(original.getData());
            }
            BlackBoxCore.getContext().startActivity(sso);
            log("started legacy SSO component");
        } catch (Throwable t) {
            log("legacy SSO redirect failed: " + t);
        }
    }

    private static String firstString(Bundle bundle, String... keys) {
        for (String key : keys) {
            Object value = bundle.get(key);
            if (value instanceof String && !((String) value).isEmpty()) {
                return (String) value;
            }
        }
        return null;
    }

    private static void log(String msg) {
        try {
            File f = new File(LOG_PATH);
            FileWriter w = new FileWriter(f, true);
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
            w.write("[" + ts + "] " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
        Log.d(TAG, msg);
        try {
            ZoroRuntimeLogger.log("twitter-login", msg);
        } catch (Throwable ignored) {
        }
    }
}

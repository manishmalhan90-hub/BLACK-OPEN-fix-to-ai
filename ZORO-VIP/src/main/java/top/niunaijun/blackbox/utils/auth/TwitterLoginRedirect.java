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
import java.util.concurrent.atomic.AtomicBoolean;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.ZoroRuntimeLogger;

/**
 * Twitter/X login compatibility layer for the virtual process.
 *
 * <p>Current X releases no longer export the legacy
 * {@code com.twitter.android/.SingleSignOnActivity} that TwitterKit used for
 * true "logged in inside the X app" SSO. The game therefore always falls back
 * to TwitterKit's own OAuthActivity when that activity is missing.</p>
 *
 * <p>Default behaviour is "sdk": let the game's in-app TwitterKit flow run,
 * never inject plaintext credentials, and never force the old system-webview
 * login. {@code /sdcard/twitter_mode.txt} may contain:</p>
 *
 * <ul>
 *   <li>{@code sdk} (default) - allow the game in-app OAuthActivity.</li>
 *   <li>{@code app} / {@code sso} - only if the legacy X SSO activity really
 *       exists; otherwise fall back to the in-app OAuthActivity.</li>
 *   <li>{@code web} - diagnostics only, leaves legacy web/custom-tab login
 *       untouched.</li>
 * </ul>
 */
public final class TwitterLoginRedirect {

    private static final String TAG = "TwitterLoginRedirect";
    private static final String LOG_PATH = "/sdcard/twitter_block.log";
    private static final String TWITTER_APP_PKG = "com.twitter.android";
    private static final String SSO_CLASS = TWITTER_APP_PKG + ".SingleSignOnActivity";
    private static final String MODE_FILE = "/sdcard/twitter_mode.txt";
    private static final String MODE_SDK = "sdk";
    private static final String MODE_APP = "app";
    private static final String MODE_SSO = "sso";
    private static final String MODE_WEB = "web";

    private static final AtomicBoolean sEnvironmentLogged = new AtomicBoolean(false);
    private static String sLastLog;
    private static long sLastLogTime;

    private TwitterLoginRedirect() {
    }

    /** Returns the selected login mode, lowercased. Default = "sdk". */
    public static String mode() {
        try {
            File f = new File(MODE_FILE);
            if (!f.exists()) {
                return MODE_SDK;
            }
            Scanner s = new Scanner(f).useDelimiter("\\A");
            String v = s.hasNext() ? s.next().trim().toLowerCase(Locale.ROOT) : "";
            s.close();
            if (v.isEmpty()) {
                return MODE_SDK;
            }
            return v;
        } catch (Throwable t) {
            return MODE_SDK;
        }
    }

    private static boolean appModeEnabled() {
        String mode = mode();
        return MODE_APP.equals(mode) || MODE_SSO.equals(mode);
    }

    private static boolean webModeEnabled() {
        return MODE_WEB.equals(mode()) || "system".equals(mode()) || "webview".equals(mode());
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

    private static boolean twitterAppInstalled() {
        try {
            BlackBoxCore.getContext().getPackageManager().getPackageInfo(TWITTER_APP_PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Log the environment once so a fresh login attempt can be diagnosed. */
    public static void logEnvironment() {
        if (!sEnvironmentLogged.compareAndSet(false, true)) {
            return;
        }
        String mode = mode();
        log("environment: mode=" + mode + ", X app installed=" + twitterAppInstalled()
                + ", legacy SSO activity=" + legacySsoAvailable());
        if (appModeEnabled() && !legacySsoAvailable()) {
            log("legacy X SSO unavailable; using game in-app OAuthActivity (no plaintext credentials)");
        }
    }

    /** Observe a Twitter auth intent without changing it. */
    public static void observe(Intent intent) {
        if (!isTwitterAuthIntent(intent)) {
            return;
        }
        String component = intent.getComponent() == null
                ? "(no component)" : intent.getComponent().flattenToString();
        Bundle extras = intent.getExtras();
        String channel = extras == null ? "" : String.valueOf(extras.get("channel"));
        String data = intent.getDataString() == null ? "" : intent.getDataString();
        String host = data;
        try {
            android.net.Uri uri = android.net.Uri.parse(data);
            if (uri.getHost() != null) {
                host = uri.getHost();
            }
        } catch (Throwable ignored) {
        }
        log("observed Twitter auth intent: " + component
                + " | channel=" + channel
                + (data.isEmpty() ? "" : " | host=" + host));
        if (appModeEnabled() && !legacySsoAvailable()) {
            log("X app lacks legacy SSO activity; falling back to game in-app OAuthActivity");
        } else if (webModeEnabled()) {
            log("legacy web/custom-tab mode enabled by " + MODE_FILE);
        } else {
            log("default sdk mode: allowing game in-app OAuthActivity");
        }
    }

    private static boolean isTwitterAuthIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            ComponentName cn = intent.getComponent();
            if (cn != null) {
                String cls = cn.getClassName().toLowerCase(Locale.ROOT);
                if (cls.contains("oauthactivity") || cls.contains("twitterwebactivity")
                        || cls.contains("itopcustomtabactivity")
                        || cls.contains("twitter") && (cls.contains("webview")
                        || cls.contains("auth") || cls.contains("login"))) {
                    return true;
                }
            }
            String data = intent.getDataString() == null ? "" : intent.getDataString().toLowerCase(Locale.ROOT);
            if ((data.contains("twitter.com") || data.contains("x.com"))
                    && (data.contains("oauth") || data.contains("authorize"))) {
                return true;
            }
        } catch (Throwable t) {
            log("intent inspection failed: " + t);
        }
        return false;
    }

    /** Return true only when we can really hand off to the legacy X app. */
    public static boolean shouldIntercept(Intent intent) {
        if (!appModeEnabled()) {
            return false;
        }
        if (!isTwitterAuthIntent(intent)) {
            return false;
        }
        if (!legacySsoAvailable()) {
            log("legacy Twitter SSO activity missing -> allow game in-app OAuthActivity");
            return false;
        }
        log("shouldIntercept: " + (intent.getComponent() == null
                ? "twitter oauth URL" : intent.getComponent().flattenToString()));
        return true;
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

    public static void log(String msg) {
        if (msg == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (msg.equals(sLastLog) && now - sLastLogTime < 2000L) {
            return;
        }
        sLastLog = msg;
        sLastLogTime = now;
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

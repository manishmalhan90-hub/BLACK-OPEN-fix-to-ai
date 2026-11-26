package top.niunaijun.blackbox.utils.auth;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Forces the game's IMSDK Twitter login mode to its SDK/app path.
 *
 * <p>The game reads {@code IMSDK_TWITTER_LOGIN_USING_WEB} from its in-memory
 * IMSDK config cache. When that value is true, login2Channel() chooses the
 * system Custom Tab/WebView flow, which is the old web login we removed.
 * Setting it to false makes the game use TwitterKit's own in-app flow
 * (SSOActivity when available, otherwise OAuthActivity).</p>
 *
 * <p>The previous build only forced the cache when the custom-tab activity was
 * already created, i.e. after the login path had been chosen. This version
 * initialises the IMSDK config early and keeps a lightweight watchdog that
 * re-applies the values, including after a server config refresh overwrites
 * the cache.</p>
 *
 * <p>This only edits in-memory config values. It never touches credentials,
 * never writes tokens, and does not bypass any anti-cheat check.</p>
 */
public final class TwitterLoginModeFix {

    private static final String TAG = "TwitterLoginModeFix";
    private static final String CONFIG_CLASS = "com.itop.imsdk.android.api.config.IMSDKConfig";
    private static final String KEY_USE_WEB = "IMSDK_TWITTER_LOGIN_USING_WEB";
    private static final String KEY_MODE_SWITCH = "IMSDK_TWITTER_LOGIN_MODE_SWITCH";
    private static final String KEY_PRIOR_MODE = "IMSDK_TWITTER_PRIOR_LOGIN_MODE";
    private static final String KEY_BROWSER_LOGIN = "IMSDK_TWITTER_USE_BROWSER_LOGIN";
    private static final long WATCHDOG_DELAY_MS = 1000L;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean sScheduled = new AtomicBoolean(false);
    private static final AtomicBoolean sInitDone = new AtomicBoolean(false);
    private static final AtomicBoolean sInitLogged = new AtomicBoolean(false);
    private static final AtomicBoolean sLogged = new AtomicBoolean(false);
    private static final AtomicBoolean sEffectiveLogged = new AtomicBoolean(false);

    private TwitterLoginModeFix() {
    }

    public static void apply(Context context) {
        TwitterLoginRedirect.logEnvironment();
        String mode = TwitterLoginRedirect.mode();
        if ("web".equals(mode) || "system".equals(mode) || "webview".equals(mode)) {
            return;
        }
        ClassLoader cl = classLoader(context);
        initializeIfNeeded(context, cl);
        force(cl);
        scheduleWatchdog(context, cl);
    }

    private static ClassLoader classLoader(Context context) {
        ClassLoader cl = context == null ? null : context.getClassLoader();
        return cl != null ? cl : TwitterLoginModeFix.class.getClassLoader();
    }

    /** Create the game's IMSDK config object before the first login tap. */
    private static void initializeIfNeeded(Context context, ClassLoader cl) {
        if (sInitDone.get() || !(context instanceof Activity) || cl == null) {
            return;
        }
        try {
            Class<?> config = Class.forName(CONFIG_CLASS, false, cl);
            Method init = config.getDeclaredMethod("initialize", Activity.class);
            init.setAccessible(true);
            init.invoke(null, (Activity) context);
            sInitDone.set(true);
            if (sInitLogged.compareAndSet(false, true)) {
                TwitterLoginRedirect.log("IMSDK config initialized early by loader watchdog");
            }
        } catch (Throwable ignored) {
            // This context may be the loader app itself; the game context will retry.
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean force(ClassLoader cl) {
        try {
            if (cl == null) {
                return false;
            }
            Class<?> config = Class.forName(CONFIG_CLASS, false, cl);
            Field implField = config.getDeclaredField("mConfigImpl");
            implField.setAccessible(true);
            Object impl = implField.get(null);
            if (impl == null) {
                return false;
            }
            int updated = 0;
            String[] fields = {"mUserConfigCache", "mConfigCache"};
            for (String name : fields) {
                try {
                    Field cacheField = impl.getClass().getDeclaredField(name);
                    cacheField.setAccessible(true);
                    Object value = cacheField.get(impl);
                    if (!(value instanceof Map)) {
                        continue;
                    }
                    Map cache = (Map) value;
                    cache.put(KEY_USE_WEB, "false");
                    cache.put(KEY_MODE_SWITCH, "0");
                    cache.put(KEY_PRIOR_MODE, "2");
                    cache.put(KEY_BROWSER_LOGIN, "0");
                    updated++;
                } catch (Throwable ignored) {
                    // One cache map may not exist yet; the other is enough.
                }
            }
            if (updated == 0) {
                return false;
            }
            if (sLogged.compareAndSet(false, true)) {
                String message = "IMSDK Twitter mode forced to SDK/app: " + KEY_USE_WEB
                        + "=false, " + KEY_MODE_SWITCH + "=0, " + KEY_PRIOR_MODE
                        + "=2, " + KEY_BROWSER_LOGIN + "=0";
                TwitterLoginRedirect.log(message);
                Log.d(TAG, message);
            }
            if (sEffectiveLogged.compareAndSet(false, true)) {
                String effective = effectiveUseWeb(cl);
                String message = "effective IMSDK_TWITTER_LOGIN_USING_WEB=" + effective;
                TwitterLoginRedirect.log(message);
                Log.d(TAG, message);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String effectiveUseWeb(ClassLoader cl) {
        try {
            Class<?> config = Class.forName(CONFIG_CLASS, false, cl);
            Method m = config.getDeclaredMethod("getOrMetaData", String.class, String.class, boolean.class);
            m.setAccessible(true);
            return String.valueOf(m.invoke(null, KEY_USE_WEB, KEY_USE_WEB, true));
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * Keep forcing the value. IMSDK can replace its cache when a server config
     * response arrives, so a one-shot write is not enough.
     */
    private static void scheduleWatchdog(final Context context, final ClassLoader fallback) {
        if (!sScheduled.compareAndSet(false, true)) {
            return;
        }
        final WeakReference<Context> ref = context == null ? null : new WeakReference<Context>(context);
        HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                Context ctx = ref == null ? null : ref.get();
                if (ctx == null) {
                    sScheduled.set(false);
                    return;
                }
                ClassLoader cl = classLoader(ctx);
                if (cl == null) {
                    cl = fallback;
                }
                initializeIfNeeded(ctx, cl);
                force(cl);
                HANDLER.postDelayed(this, WATCHDOG_DELAY_MS);
            }
        }, 200L);
    }
}

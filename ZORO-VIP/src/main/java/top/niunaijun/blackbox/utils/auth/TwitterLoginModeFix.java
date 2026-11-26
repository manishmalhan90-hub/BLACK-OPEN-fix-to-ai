package top.niunaijun.blackbox.utils.auth;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import top.niunaijun.blackbox.core.ZoroRuntimeLogger;

/**
 * Forces both Twitter/X and Facebook login to the game's built-in web flow.
 *
 * <p>This supersedes the earlier SDK/app handoff. The game reads these values
 * from the in-memory IMSDK config cache. With them forced early:</p>
 *
 * <ul>
 *   <li>{@code IMSDK_TWITTER_LOGIN_USING_WEB=true} keeps Twitter in web mode.</li>
 *   <li>{@code IMSDK_TWITTER_LOGIN_MODE_SWITCH=0} prevents a switch to the X app.</li>
 *   <li>{@code IMSDK_TWITTER_PRIOR_LOGIN_MODE=1} selects web on first launch.</li>
 *   <li>{@code IMSDK_TWITTER_USE_BROWSER_LOGIN=0} avoids Chrome Custom Tabs,
 *       whose external redirect back into the virtual process was never
 *       delivered; the built-in WebView handles the callback itself.</li>
 *   <li>{@code IMSDK_FACEBOOK_LOGIN_USING_WEB=true} keeps Facebook in web mode.</li>
 *   <li>{@code IMSDK_FACEBOOK_USE_BROWSER_LOGIN=0} applies the same WebView fix
 *       to Facebook.</li>
 * </ul>
 *
 * <p>The config is initialised before the first login tap and re-applied on a
 * short watchdog because a server config refresh can replace the cache.</p>
 *
 * <p>This only edits in-memory config values. It never touches credentials,
 * never writes tokens, and does not bypass any anti-cheat check.</p>
 */
public final class TwitterLoginModeFix {

    private static final String TAG = "WebLoginModeFix";
    private static final String CONFIG_CLASS = "com.itop.imsdk.android.api.config.IMSDKConfig";
    private static final long WATCHDOG_DELAY_MS = 500L;
    private static final long LOGIN_WATCHDOG_DELAY_MS = 3000L;
    private static final long LOGIN_PAUSED_DELAY_MS = 30000L;

    private static final String[][] FORCED_VALUES = {
            {"IMSDK_TWITTER_LOGIN_USING_WEB", "true"},
            {"IMSDK_TWITTER_LOGIN_MODE_SWITCH", "0"},
            {"IMSDK_TWITTER_PRIOR_LOGIN_MODE", "1"},
            {"IMSDK_TWITTER_USE_BROWSER_LOGIN", "0"},
            {"IMSDK_FACEBOOK_LOGIN_USING_WEB", "true"},
            {"IMSDK_FACEBOOK_USE_BROWSER_LOGIN", "0"}
    };

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean sScheduled = new AtomicBoolean(false);
    private static final AtomicBoolean sInitDone = new AtomicBoolean(false);
    private static final AtomicBoolean sInitLogged = new AtomicBoolean(false);
    private static final AtomicBoolean sLogged = new AtomicBoolean(false);
    private static final AtomicBoolean sEffectiveLogged = new AtomicBoolean(false);
    private static final AtomicBoolean sBridgeLogged = new AtomicBoolean(false);
    private static volatile boolean sLoginActive;

    private static WeakReference<Activity> sLastActivity = new WeakReference<Activity>(null);

    private TwitterLoginModeFix() {
    }

    public static void apply(Context context) {
        if (context instanceof Activity) {
            sLastActivity = new WeakReference<Activity>((Activity) context);
        }
        TwitterLoginRedirect.logEnvironment();
        ClassLoader cl = classLoader(context);
        WebLoginBridge.register(context, cl);
        WebLoginBridge.startPendingConsumer(context);
        if (sBridgeLogged.compareAndSet(false, true)) {
            ZoroRuntimeLogger.logLogin("web-login-fix",
                    "isolated web login bridge v18 active (:login_web, shared pending result, facebook listener replay, game relaunch)");
        }
        initializeIfNeeded(context, cl);
        force(cl);
        scheduleWatchdog(context, cl);
    }

    /** Slow the config watchdog while WebView owns the UI; less allocation/GC churn. */
    public static void setLoginActive(boolean active) {
        sLoginActive = active;
    }

    private static ClassLoader classLoader(Context context) {
        ClassLoader cl = context == null ? null : context.getClassLoader();
        return cl != null ? cl : TwitterLoginModeFix.class.getClassLoader();
    }

    /** Create the game's IMSDK config object before the first login tap. */
    private static void initializeIfNeeded(Context context, ClassLoader cl) {
        if (sInitDone.get() || cl == null) {
            return;
        }
        Activity activity = sLastActivity.get();
        if (activity == null) {
            return;
        }
        try {
            Class<?> config = Class.forName(CONFIG_CLASS, false, cl);
            Method init = config.getDeclaredMethod("initialize", Activity.class);
            init.setAccessible(true);
            init.invoke(null, activity);
            sInitDone.set(true);
            if (sInitLogged.compareAndSet(false, true)) {
                String message = "IMSDK config initialized early by loader web login fix";
                ZoroRuntimeLogger.logLogin("web-login-fix", message);
                Log.d(TAG, message);
            }
        } catch (Throwable ignored) {
            // The game may create its own config later; the watchdog retries.
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
                    for (String[] entry : FORCED_VALUES) {
                        cache.put(entry[0], entry[1]);
                    }
                    updated++;
                } catch (Throwable ignored) {
                    // One cache may not exist yet; the other is enough.
                }
            }
            if (updated == 0) {
                return false;
            }
            if (sLogged.compareAndSet(false, true)) {
                StringBuilder sb = new StringBuilder("web login forced for both channels:");
                for (String[] entry : FORCED_VALUES) {
                    sb.append(' ').append(entry[0]).append('=').append(entry[1]);
                }
                String message = sb.toString();
                ZoroRuntimeLogger.logLogin("web-login-fix", message);
                Log.d(TAG, message);
            }
            if (sEffectiveLogged.compareAndSet(false, true)) {
                String message = "effective web login config: " + snapshot(cl);
                ZoroRuntimeLogger.logLogin("web-login-fix", message);
                Log.d(TAG, message);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Read back the effective values for the dedicated login log. */
    public static String snapshot(ClassLoader cl) {
        StringBuilder sb = new StringBuilder();
        try {
            if (cl == null) {
                return "unknown";
            }
            Class<?> config = Class.forName(CONFIG_CLASS, false, cl);
            for (String[] entry : FORCED_VALUES) {
                String value;
                try {
                    Method m = config.getDeclaredMethod("getOrMetaData", String.class,
                            String.class, boolean.class);
                    m.setAccessible(true);
                    value = String.valueOf(m.invoke(null, entry[0], entry[0], false));
                } catch (Throwable ignored) {
                    try {
                        Method m = config.getDeclaredMethod("getOrMetaData", String.class,
                                String.class, int.class);
                        m.setAccessible(true);
                        value = String.valueOf(m.invoke(null, entry[0], entry[0], 0));
                    } catch (Throwable ignored2) {
                        value = "unknown";
                    }
                }
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(entry[0]).append('=').append(value);
            }
        } catch (Throwable t) {
            return "unavailable: " + t;
        }
        return sb.toString();
    }

    /**
     * Keep forcing the values. IMSDK can replace its cache when a server config
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
                // While the OAuth WebView owns the screen, stop reflecting on
                // the game config entirely. Less allocation/GC churn in the
                // virtual process means less chance of the ART mremap abort.
                if (sLoginActive) {
                    HANDLER.postDelayed(this, LOGIN_PAUSED_DELAY_MS);
                    return;
                }
                ClassLoader cl = classLoader(ctx);
                if (cl == null) {
                    cl = fallback;
                }
                initializeIfNeeded(ctx, cl);
                force(cl);
                HANDLER.postDelayed(this, sLoginActive ? LOGIN_WATCHDOG_DELAY_MS : WATCHDOG_DELAY_MS);
            }
        }, 200L);
    }
}

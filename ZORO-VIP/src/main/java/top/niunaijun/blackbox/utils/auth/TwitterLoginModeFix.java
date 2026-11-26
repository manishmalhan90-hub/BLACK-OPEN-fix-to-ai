package top.niunaijun.blackbox.utils.auth;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import top.niunaijun.blackbox.core.ZoroRuntimeLogger;

/**
 * Forces the game's IMSDK Twitter login mode to its SDK/app path.
 *
 * <p>The game reads {@code IMSDK_TWITTER_LOGIN_USING_WEB} from its in-memory
 * IMSDK config cache. When that value is true, login2Channel() chooses the
 * system Custom Tab/WebView flow, which is the old web login we removed.
 * Setting it to false makes the game use TwitterKit's own in-app flow
 * (SSOActivity when available, otherwise OAuthActivity).</p>
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

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean sScheduled = new AtomicBoolean(false);
    private static final AtomicBoolean sLogged = new AtomicBoolean(false);
    private static volatile boolean sApplied;

    private TwitterLoginModeFix() {
    }

    public static void apply(Context context) {
        TwitterLoginRedirect.logEnvironment();
        String mode = TwitterLoginRedirect.mode();
        if ("web".equals(mode) || "system".equals(mode) || "webview".equals(mode)) {
            return;
        }
        ClassLoader cl = context == null ? null : context.getClassLoader();
        if (cl == null) {
            cl = TwitterLoginModeFix.class.getClassLoader();
        }
        if (force(cl)) {
            sApplied = true;
            return;
        }
        scheduleRetry(context);
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
                try {
                    ZoroRuntimeLogger.log("twitter-login", message);
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void scheduleRetry(final Context context) {
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
                if (!sApplied && !force(ctx.getClassLoader())) {
                    HANDLER.postDelayed(this, 1000L);
                    return;
                }
                sApplied = true;
                sScheduled.set(false);
            }
        }, 300L);
    }
}

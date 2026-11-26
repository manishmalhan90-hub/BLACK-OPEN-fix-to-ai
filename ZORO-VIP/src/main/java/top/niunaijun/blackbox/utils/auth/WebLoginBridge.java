package top.niunaijun.blackbox.utils.auth;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import top.niunaijun.blackbox.core.ZoroRuntimeLogger;

/**
 * Moves web login out of the virtualised game process.
 *
 * <p>The game used to create a WebView inside {@code com.google:p0}. The device
 * aborted that process in ART's MarkCompact collector during login. This bridge
 * asks the loader's main-process provider to launch a clean {@code :login_web}
 * process instead. That process only hosts the login WebView and sends the JSON
 * result back to the virtual process, where it is delivered to the game's own
 * IMSDK/Twitter callback.</p>
 *
 * <p>No credentials are injected, no tokens are logged, and no anti-cheat code
 * is touched. Only the login UI process and the existing callbacks are used.</p>
 */
public final class WebLoginBridge {

    private static final String TAG = "WebLoginBridge";
    static final String ACTION_RESULT = "top.niunaijun.blackbox.weblogin.RESULT";
    private static final String PROVIDER_METHOD = "START_WEB_LOGIN";

    private static final AtomicBoolean sReceiverRegistered = new AtomicBoolean(false);
    private static final AtomicBoolean sLoginActive = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile ClassLoader sGameClassLoader;
    private static BroadcastReceiver sReceiver;

    private WebLoginBridge() {
    }

    /** Called from the virtual process instrumentation. */
    public static boolean redirectIfNeeded(Context context, Activity activity, Intent intent,
                                           int requestCode) {
        if (context == null || intent == null) {
            return false;
        }
        if (sGameClassLoader == null) {
            sGameClassLoader = activity != null ? activity.getClassLoader() : context.getClassLoader();
        }
        // Make sure the result receiver exists even if the early app hook was missed.
        register(context, sGameClassLoader);

        String className = intent.getComponent() == null ? "" : intent.getComponent().getClassName();
        boolean twitter = "com.itop.twitterwrapper.TwitterWebActivity".equals(className);
        boolean facebook = "com.itop.imsdk.android.login.apple.WebLoginActivity".equals(className);
        if (!twitter && !facebook) {
            return false;
        }

        String url = intent.getStringExtra("url");
        if (twitter && (url == null || url.isEmpty())) {
            log("twitter login skipped: empty url");
            return false;
        }
        if (facebook) {
            url = buildFacebookUrl(context, activity, intent);
            if (url == null || url.isEmpty()) {
                log("facebook login skipped: url unavailable");
                return false;
            }
        }

        Bundle extras = new Bundle();
        extras.putString("kind", twitter ? "twitter" : "facebook");
        extras.putString("url", url);
        extras.putString("virtual_package", activity != null
                ? activity.getPackageName() : context.getPackageName());
        extras.putString("channel", intent.getStringExtra("channel"));
        extras.putInt("channel_id", intent.getIntExtra("channel_id", 0));
        extras.putString("app_id", intent.getStringExtra("app_id"));
        extras.putInt("request_code", requestCode);

        String host = "(none)";
        try {
            String parsed = Uri.parse(url).getHost();
            if (parsed != null) {
                host = parsed;
            }
        } catch (Throwable ignored) {
            host = "(invalid)";
        }
        log("redirecting " + (twitter ? "twitter" : "facebook")
                + " web login to isolated process | host=" + host + " | request=" + requestCode);

        try {
            // The virtual context reports the game package (com.pubg.imobile).
            // The provider lives in the host app (com.google), so always resolve
            // the authority from the host context, never from the virtual one.
            String hostPackage = hostPackage(context);
            String authority = hostPackage + ".SystemCallProvider";
            Uri uri = new Uri.Builder().scheme("content").authority(authority).build();
            context.getContentResolver().call(uri, PROVIDER_METHOD, null, extras);
            sLoginActive.set(true);
            TwitterLoginModeFix.setLoginActive(true);
            log("isolated login launched via " + authority);
            return true;
        } catch (Throwable t) {
            log("isolated login launch failed: " + t + "; clearing virtual WebView cache before fallback");
            clearVirtualWebViewCache(context);
            return false;
        }
    }

    /** Resolve the real loader package, not the virtualised game package. */
    private static String hostPackage(Context fallback) {
        try {
            Context host = top.niunaijun.blackbox.BlackBoxCore.getContext();
            if (host != null) {
                String pkg = host.getPackageName();
                if (pkg != null && !pkg.isEmpty()) {
                    return pkg;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            String pkg = top.niunaijun.blackbox.BlackBoxCore.getHostPkg();
            if (pkg != null && !pkg.isEmpty()) {
                return pkg;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "com.google";
    }

    /** Last-resort cleanup if the isolated host could not be started. */
    private static void clearVirtualWebViewCache(Context context) {
        try {
            Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
            android.webkit.WebView.clearCache(app);
            android.webkit.WebStorage.getInstance().deleteAllData();
            log("virtual WebView cache + storage cleared before fallback");
        } catch (Throwable t) {
            log("virtual WebView cache clear failed: " + t);
        }
    }

    /** Register the result receiver inside the virtual process. */
    public static void register(Context context, ClassLoader classLoader) {
        if (context == null) {
            return;
        }
        if (classLoader != null) {
            sGameClassLoader = classLoader;
        }
        if (!sReceiverRegistered.compareAndSet(false, true)) {
            return;
        }
        sReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                deliver(intent);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_RESULT);
        Context app = context.getApplicationContext();
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(sReceiver, filter, null, MAIN, Context.RECEIVER_NOT_EXPORTED);
            } else if (Build.VERSION.SDK_INT >= 26) {
                app.registerReceiver(sReceiver, filter, null, MAIN);
            } else {
                app.registerReceiver(sReceiver, filter);
            }
        } catch (Throwable t) {
            sReceiverRegistered.set(false);
            log("result receiver registration failed: " + t);
        }
    }

    private static void deliver(Intent intent) {
        if (intent == null) {
            return;
        }
        String kind = intent.getStringExtra("kind");
        String json = intent.getStringExtra("result_json");
        int code = intent.getIntExtra("result_code", -1);
        String msg = intent.getStringExtra("result_msg");
        sLoginActive.set(false);
        TwitterLoginModeFix.setLoginActive(false);

        ClassLoader cl = sGameClassLoader;
        if (cl == null) {
            log("result dropped: game classloader unavailable");
            return;
        }
        try {
            if ("twitter".equals(kind)) {
                if (json != null && !json.isEmpty()) {
                    invokeTwitterResult(cl, json);
                    log("twitter web result delivered to IMSDK callback");
                } else {
                    Intent result = new Intent();
                    result.putExtra("msg", msg == null ? "" : msg);
                    invokeTwitterFailure(cl, code, result);
                    log("twitter web cancellation delivered (code=" + code + ")");
                }
            } else if ("facebook".equals(kind)) {
                if (json != null && !json.isEmpty()) {
                    invokeFacebookResult(cl, json);
                    log("facebook web result delivered to IMSDK callback");
                } else {
                    invokeFacebookFailure(cl, code, msg);
                    log("facebook web cancellation delivered (code=" + code + ")");
                }
            }
        } catch (Throwable t) {
            ZoroRuntimeLogger.logThrowable("login-bridge", t);
            log("result delivery failed: " + t);
        }
    }

    private static void invokeTwitterResult(ClassLoader cl, String json) throws Exception {
        Class<?> wrapper = Class.forName("o5.a", true, cl);
        Method method = wrapper.getDeclaredMethod("f", String.class);
        method.setAccessible(true);
        method.invoke(null, json);
    }

    private static void invokeTwitterFailure(ClassLoader cl, int code, Intent result) throws Exception {
        Class<?> wrapper = Class.forName("o5.a", true, cl);
        Method method = wrapper.getDeclaredMethod("k", boolean.class, int.class, Intent.class);
        method.setAccessible(true);
        method.invoke(null, true, code, result);
    }

    private static void invokeFacebookResult(ClassLoader cl, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        int code = root.getInt("code");
        Object listener = facebookListener(cl);
        if (listener == null) {
            log("facebook result dropped: listener unavailable");
            return;
        }
        if (code == 0) {
            JSONObject msgObject = root.getJSONObject("msg");
            Map<String, String> map = new TreeMap<String, String>();
            Iterator<String> keys = msgObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, msgObject.getString(key));
            }
            invokeListener(listener, "onNotify", map);
        } else {
            Object result = newIMSDKResult(cl, 9999, code, root.optString("msg"));
            invokeListener(listener, "onResult", result);
        }
        clearFacebookListener(cl);
    }

    private static void invokeFacebookFailure(ClassLoader cl, int code, String msg) throws Exception {
        Object listener = facebookListener(cl);
        if (listener == null) {
            log("facebook cancellation dropped: listener unavailable");
            return;
        }
        Object result = newIMSDKResult(cl, code, code, msg == null ? "" : msg);
        invokeListener(listener, "onResult", result);
        clearFacebookListener(cl);
    }

    private static Object facebookListener(ClassLoader cl) throws Exception {
        Class<?> activity = Class.forName("com.itop.imsdk.android.login.apple.WebLoginActivity", false, cl);
        Field field = activity.getDeclaredField("mListener");
        field.setAccessible(true);
        return field.get(null);
    }

    private static void clearFacebookListener(ClassLoader cl) {
        try {
            Class<?> activity = Class.forName("com.itop.imsdk.android.login.apple.WebLoginActivity", false, cl);
            Field field = activity.getDeclaredField("mListener");
            field.setAccessible(true);
            field.set(null, null);
        } catch (Throwable ignored) {
            // Best effort: the original activity also simply clears this field.
        }
    }

    private static Object newIMSDKResult(ClassLoader cl, int imsdkCode, int thirdCode, String msg)
            throws Exception {
        Class<?> resultClass = Class.forName("com.itop.imsdk.android.api.IMSDKResult", true, cl);
        try {
            return resultClass.getConstructor(int.class, int.class, String.class)
                    .newInstance(imsdkCode, thirdCode, msg);
        } catch (NoSuchMethodException ignored) {
            return resultClass.getConstructor(int.class, String.class).newInstance(imsdkCode, msg);
        }
    }

    private static void invokeListener(Object listener, String name, Object argument) throws Exception {
        Method[] methods = listener.getClass().getMethods();
        Method exact = null;
        Method fallback = null;
        for (Method method : methods) {
            if (!name.equals(method.getName()) || method.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> parameter = method.getParameterTypes()[0];
            if (argument != null && parameter.isAssignableFrom(argument.getClass())) {
                exact = method;
                break;
            }
            fallback = method;
        }
        Method target = exact != null ? exact : fallback;
        if (target == null) {
            throw new NoSuchMethodException(name);
        }
        target.setAccessible(true);
        target.invoke(listener, argument);
    }

    /** Rebuild the same URL the IMSDK WebLoginActivity would have built. */
    private static String buildFacebookUrl(Context context, Activity activity, Intent intent) {
        try {
            ClassLoader cl = sGameClassLoader != null ? sGameClassLoader : context.getClassLoader();
            Class<?> config = Class.forName("com.itop.imsdk.android.api.config.IMSDKConfig", true, cl);
            Method get = config.getDeclaredMethod("getOrMetaData", String.class, String.class);
            get.setAccessible(true);
            String base = String.valueOf(get.invoke(null, "IMSDK_WEB_LOGIN_BASE_URL",
                    "IMSDK_WEB_LOGIN_BASE_URL"));

            Class<?> device = Class.forName("com.itop.imsdk.android.tools.DeviceUtils", true, cl);
            String gameId = callStaticString(device, "getGameId");
            String installId = callStaticStringWithContext(device, "getInstallId", context);
            String brand = callStaticString(device, "getBrand");
            String model = callStaticString(device, "getModel");
            String osVersion = callStaticString(device, "getSysVersion");

            Class<?> seqClass = Class.forName("com.itop.imsdk.android.tools.SeqIdGenerator", true, cl);
            Method getInstance = seqClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object seq = getInstance.invoke(null);
            Method current = seqClass.getDeclaredMethod("getCurrentSeqId");
            current.setAccessible(true);
            String seqId = String.valueOf(current.invoke(seq));

            String channel = intent.getStringExtra("channel") == null
                    ? "" : intent.getStringExtra("channel");
            int channelId = intent.getIntExtra("channel_id", 0);
            String appId = intent.getStringExtra("app_id") == null
                    ? "" : intent.getStringExtra("app_id");
            String virtualPackage = activity != null
                    ? activity.getPackageName() : context.getPackageName();
            String extra = "channelid=" + channelId + "&channel=" + channel
                    + "&app_id=" + appId + "&intlstate=" + UUID.randomUUID().toString();

            StringBuilder sb = new StringBuilder(base);
            sb.append("?gameid=").append(nullToEmpty(gameId));
            sb.append("&sdk_version=1.0.0&extra_params=").append(URLEncoder.encode(extra, "UTF-8"));
            sb.append("&did=").append(nullToEmpty(installId));
            sb.append("&seq=").append(nullToEmpty(seqId));
            sb.append("&brand=").append(URLEncoder.encode(nullToEmpty(brand), "UTF-8"));
            sb.append("&model=").append(URLEncoder.encode(nullToEmpty(model), "UTF-8"));
            sb.append("&os_version=").append(URLEncoder.encode(nullToEmpty(osVersion), "UTF-8"));
            sb.append("&package_name=").append(nullToEmpty(virtualPackage));
            return sb.toString();
        } catch (Throwable t) {
            log("facebook url build failed: " + t);
            return null;
        }
    }

    private static String callStaticString(Class<?> clazz, String name) throws Exception {
        Method method = clazz.getDeclaredMethod(name);
        method.setAccessible(true);
        Object value = method.invoke(null);
        return value == null ? "" : String.valueOf(value);
    }

    private static String callStaticStringWithContext(Class<?> clazz, String name, Context context)
            throws Exception {
        Method method = clazz.getDeclaredMethod(name, Context.class);
        method.setAccessible(true);
        Object value = method.invoke(null, context);
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static boolean isLoginActive() {
        return sLoginActive.get();
    }

    private static void log(String message) {
        ZoroRuntimeLogger.logLogin("login-bridge", message);
        Log.d(TAG, message);
    }
}

package top.niunaijun.blackbox.utils.auth;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    public static final String EXTRA_VIRTUAL_PID = "virtual_pid";
    private static final String PROVIDER_METHOD = "START_WEB_LOGIN";
    private static final String RESTORE_METHOD = "RESTORE_GAME";
    private static final String DONE_METHOD = "WEB_LOGIN_DONE";
    private static final String PENDING_FILE = "weblogin_pending.json";
    private static final long PENDING_MAX_AGE_MS = 10L * 60L * 1000L;
    private static final long PENDING_POLL_MS = 400L;

    private static final AtomicBoolean sReceiverRegistered = new AtomicBoolean(false);
    private static final AtomicBoolean sLoginActive = new AtomicBoolean(false);
    private static final AtomicBoolean sResultConsumed = new AtomicBoolean(false);
    private static final AtomicBoolean sPendingPolling = new AtomicBoolean(false);
    private static final Object PENDING_LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile ClassLoader sGameClassLoader;
    private static volatile Context sAppContext;
    private static volatile int sPendingFailures;
    private static BroadcastReceiver sReceiver;
    private static final Runnable PENDING_POLL = new Runnable() {
        @Override
        public void run() {
            try {
                Context context = sAppContext;
                if (context != null) {
                    consumePending(context);
                }
            } catch (Throwable ignored) {
                // Never let a stale result break the polling loop.
            }
            MAIN.postDelayed(this, PENDING_POLL_MS);
        }
    };

    private WebLoginBridge() {
    }

    /** Called from the virtual process instrumentation. */
    public static boolean redirectIfNeeded(Context context, Activity activity, Intent intent,
                                           int requestCode) {
        if (context == null || intent == null) {
            return false;
        }
        // Refresh on every attempt: the virtual activity classloader can change
        // after the game process is restarted by the crash guard.
        if (activity != null && activity.getClassLoader() != null) {
            sGameClassLoader = activity.getClassLoader();
        } else if (sGameClassLoader == null) {
            sGameClassLoader = context.getClassLoader();
        }
        // Make sure the result receiver exists even if the early app hook was missed.
        register(context, sGameClassLoader);

        String className = intent.getComponent() == null ? "" : intent.getComponent().getClassName();
        boolean twitter = "com.itop.twitterwrapper.TwitterWebActivity".equals(className);
        boolean facebook = "com.itop.imsdk.android.login.apple.WebLoginActivity".equals(className);
        if (!twitter && !facebook) {
            return false;
        }
        // The game can restart its own WebActivity while the isolated result is
        // still in flight. Suppress that second launch instead of clearing the
        // stored result and opening a fresh WebView.
        if (sLoginActive.get() || hasPendingResult(context)) {
            log("duplicate web login suppressed while isolated result is pending | kind="
                    + (twitter ? "twitter" : "facebook"));
            return true;
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

        // A new tap starts a new OAuth attempt; never replay an older result.
        clearPending(context);
        Bundle extras = new Bundle();
        extras.putString("kind", twitter ? "twitter" : "facebook");
        extras.putString("url", url);
        extras.putInt(EXTRA_VIRTUAL_PID, Process.myPid());
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

        // A new tap starts a new OAuth attempt, so any result from the previous
        // attempt must not be replayed. v16 never freezes the game process.
        sResultConsumed.set(false);
        sLoginActive.set(true);
        TwitterLoginModeFix.setLoginActive(true);
        try {
            // The virtual context reports the game package (com.pubg.imobile) and
            // can also carry a different uid/user. Calling the provider with that
            // resolver produced "Calling uid ... doesn't match source uid ...".
            // Always call through the loader's own host context.
            String authority = hostPackage(context) + ".SystemCallProvider";
            callHostProvider(context, extras);
            log("isolated login launched via " + authority);
            return true;
        } catch (Throwable t) {
            sLoginActive.set(false);
            TwitterLoginModeFix.setLoginActive(false);
            log("isolated login launch failed: " + t + "; clearing virtual WebView cache before fallback");
            clearVirtualWebViewCache(context);
            return false;
        }
    }

    /**
     * Host-process fallback for intents that never reach the virtual
     * instrumentation hook. It only starts the isolated WebView; result
     * delivery stays with the receiver registered in the virtual process.
     */
    public static boolean launchHostForTwitter(Context hostContext, Intent intent) {
        if (hostContext == null || intent == null) {
            return false;
        }
        String className = intent.getComponent() == null ? "" : intent.getComponent().getClassName();
        if (!"com.itop.twitterwrapper.TwitterWebActivity".equals(className)) {
            return false;
        }
        // The isolated WebView writes its result file before broadcasting. While
        // that file is fresh, the game's duplicate WebActivity start must not
        // open a second WebView task or reveal the loader launcher.
        if (hasPendingResult(hostContext)) {
            log("host-side duplicate web login suppressed while result is pending");
            return true;
        }
        String url = intent.getStringExtra("url");
        if (TextUtils.isEmpty(url)) {
            log("host-side twitter launch skipped: empty url");
            return false;
        }
        clearPending(hostContext);
        Bundle extras = new Bundle();
        extras.putString("kind", "twitter");
        extras.putString("url", url);
        extras.putInt(EXTRA_VIRTUAL_PID, 0);
        extras.putString("virtual_package", hostContext.getPackageName());
        extras.putString("channel", intent.getStringExtra("channel"));
        extras.putInt("channel_id", intent.getIntExtra("channel_id", 0));
        extras.putString("app_id", intent.getStringExtra("app_id"));
        extras.putInt("request_code", 0);
        log("host-side redirecting twitter web login | host=" + safeUrlHost(url));
        try {
            String authority = hostPackage(hostContext) + ".SystemCallProvider";
            callHostProvider(hostContext, extras);
            log("host-side isolated login launched via " + authority);
            return true;
        } catch (Throwable t) {
            log("host-side isolated login launch failed: " + t);
            return false;
        }
    }

    /** Call the loader provider as the loader uid, never as a virtual uid. */
    private static void callHostProvider(Context fallback, Bundle extras) throws Throwable {
        String authority = hostPackage(fallback) + ".SystemCallProvider";
        Uri uri = new Uri.Builder().scheme("content").authority(authority).build();
        Context host = null;
        try {
            host = top.niunaijun.blackbox.BlackBoxCore.getContext();
        } catch (Throwable ignored) {
            // Fall back to the supplied context below.
        }
        ContentResolver resolver = host != null ? host.getContentResolver()
                : (fallback != null ? fallback.getContentResolver() : null);
        if (resolver == null) {
            throw new IllegalStateException("host content resolver unavailable");
        }
        resolver.call(uri, PROVIDER_METHOD, null, extras);
    }

    private static String safeUrlHost(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? "(none)" : host;
        } catch (Throwable t) {
            return "(invalid)";
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
        android.webkit.WebView temporary = null;
        try {
            Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
            temporary = new android.webkit.WebView(app);
            temporary.clearCache(true);
            android.webkit.WebStorage.getInstance().deleteAllData();
            log("virtual WebView cache + storage cleared before fallback");
        } catch (Throwable t) {
            log("virtual WebView cache clear failed: " + t);
        } finally {
            if (temporary != null) {
                try {
                    temporary.destroy();
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    /** Register the result receiver inside the virtual process. */
    public static void register(Context context, ClassLoader classLoader) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        sAppContext = app != null ? app : context;
        if (classLoader != null) {
            sGameClassLoader = classLoader;
        }
        if (!sReceiverRegistered.compareAndSet(false, true)) {
            return;
        }
        sReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (ctx != null && sAppContext == null) {
                    Context app = ctx.getApplicationContext();
                    sAppContext = app != null ? app : ctx;
                }
                deliver(intent);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_RESULT);
        startPendingConsumer(app != null ? app : context);
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

    /** Deliver to the game callback. Returns true only when a callback was invoked. */
    private static boolean deliver(Intent intent) {
        if (intent == null) {
            return false;
        }
        if (sResultConsumed.get()) {
            log("duplicate result ignored after successful delivery");
            return true;
        }
        String kind = intent.getStringExtra("kind");
        String json = intent.getStringExtra("result_json");
        int code = intent.getIntExtra("result_code", -1);
        String msg = intent.getStringExtra("result_msg");

        ClassLoader cl = sGameClassLoader;
        if (cl == null) {
            log("result dropped: game classloader unavailable");
            return false;
        }
        boolean delivered = false;
        try {
            if ("twitter".equals(kind)) {
                if (json != null && !json.isEmpty()) {
                    invokeTwitterResult(cl, json);
                    delivered = true;
                    log("twitter web result delivered to IMSDK callback");
                } else {
                    Intent result = new Intent();
                    result.putExtra("msg", msg == null ? "" : msg);
                    invokeTwitterFailure(cl, code, result);
                    delivered = true;
                    log("twitter web cancellation delivered (code=" + code + ")");
                }
            } else if ("facebook".equals(kind)) {
                if (json != null && !json.isEmpty()) {
                    delivered = invokeFacebookResult(cl, json);
                    if (delivered) {
                        log("facebook web result delivered to IMSDK callback");
                    }
                } else {
                    delivered = invokeFacebookFailure(cl, code, msg);
                    if (delivered) {
                        log("facebook web cancellation delivered (code=" + code + ")");
                    }
                }
            }
        } catch (Throwable t) {
            ZoroRuntimeLogger.logThrowable("login-bridge", t);
            log("result delivery failed: " + t);
        }
        if (delivered) {
            sResultConsumed.set(true);
            sLoginActive.set(false);
            TwitterLoginModeFix.setLoginActive(false);
            sPendingFailures = 0;
            clearPending(sAppContext);
            notifyHostLoginDone(sAppContext);
            restoreGame(sAppContext);
        }
        return delivered;
    }

    /** The isolated process stores every result before broadcasting it. */
    public static void savePending(Context context, Intent result) {
        if (context == null || result == null) {
            return;
        }
        synchronized (PENDING_LOCK) {
            File file = pendingFile(context);
            if (file == null) {
                return;
            }
            try {
                JSONObject pending = new JSONObject();
                String kind = result.getStringExtra("kind");
                pending.put("kind", kind == null ? "" : kind);
                String json = result.getStringExtra("result_json");
                if (json != null && !json.isEmpty()) {
                    pending.put("result_json", json);
                } else {
                    pending.put("result_code", result.getIntExtra("result_code", -1));
                    String msg = result.getStringExtra("result_msg");
                    pending.put("result_msg", msg == null ? "" : msg);
                }
                pending.put("ts", System.currentTimeMillis());
                File temp = new File(file.getParentFile(), file.getName() + ".tmp");
                FileOutputStream out = new FileOutputStream(temp, false);
                try {
                    out.write(pending.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } finally {
                    out.close();
                }
                if (file.exists() && !file.delete()) {
                    log("old pending result could not be removed");
                }
                if (!temp.renameTo(file)) {
                    log("pending result rename failed");
                }
                log("pending result stored | kind=" + pending.getString("kind")
                        + " | len=" + (json == null ? 0 : json.length()));
            } catch (Throwable t) {
                ZoroRuntimeLogger.logThrowable("login-bridge", t);
                log("pending result store failed: " + t);
            }
        }
    }

    /** Retry delivery of a stored result after the virtual process resumes. */
    public static void consumePending(Context context) {
        if (context == null) {
            return;
        }
        synchronized (PENDING_LOCK) {
            File file = pendingFile(context);
            if (file == null || !file.exists()) {
                return;
            }
            try {
                byte[] bytes = new byte[(int) file.length()];
                FileInputStream in = new FileInputStream(file);
                try {
                    int read = in.read(bytes);
                    if (read != bytes.length) {
                        return;
                    }
                } finally {
                    in.close();
                }
                JSONObject pending = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                long ts = pending.optLong("ts", 0L);
                long now = System.currentTimeMillis();
                if (ts <= 0L || ts > now || now - ts > PENDING_MAX_AGE_MS) {
                    clearPending(context);
                    log("stale pending result discarded");
                    return;
                }
                Intent result = new Intent(ACTION_RESULT);
                result.putExtra("kind", pending.optString("kind"));
                String json = pending.optString("result_json");
                if (!TextUtils.isEmpty(json)) {
                    result.putExtra("result_json", json);
                } else {
                    result.putExtra("result_code", pending.optInt("result_code", -1));
                    result.putExtra("result_msg", pending.optString("result_msg"));
                }
                if (!deliver(result)) {
                    sPendingFailures++;
                    if (sPendingFailures > 50) {
                        clearPending(context);
                        sPendingFailures = 0;
                        log("pending result discarded after repeated delivery failures");
                    }
                } else {
                    sPendingFailures = 0;
                }
            } catch (Throwable t) {
                log("pending result read failed: " + t);
            }
        }
    }

    public static void startPendingConsumer(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        Context target = app != null ? app : context;
        if (target == null) {
            return;
        }
        sAppContext = target;
        if (!sPendingPolling.compareAndSet(false, true)) {
            return;
        }
        MAIN.postDelayed(PENDING_POLL, PENDING_POLL_MS);
        log("pending result retry started");
    }

    /** True while a fresh isolated result is waiting to be consumed. */
    private static boolean hasPendingResult(Context context) {
        try {
            File file = pendingFile(context);
            if (file == null || !file.exists()) {
                return false;
            }
            JSONObject pending = new JSONObject(new String(
                    readAll(file), StandardCharsets.UTF_8));
            long ts = pending.optLong("ts", 0L);
            long now = System.currentTimeMillis();
            return ts > 0L && ts <= now && now - ts <= PENDING_MAX_AGE_MS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static byte[] readAll(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] bytes = new byte[(int) file.length()];
            int read = in.read(bytes);
            if (read != bytes.length) {
                throw new java.io.IOException("short pending result read");
            }
            return bytes;
        } finally {
            in.close();
        }
    }

    /**
     * Tell the loader process that the result was delivered. The host-side
     * ActivityStack checks the same pending file to suppress duplicate launches,
     * so the ack is sent through the host provider instead of a local delete.
     */
    private static void notifyHostLoginDone(Context context) {
        try {
            String authority = hostPackage(context) + ".SystemCallProvider";
            Uri uri = new Uri.Builder().scheme("content").authority(authority).build();
            ContentResolver resolver = hostResolver(context);
            if (resolver != null) {
                resolver.call(uri, DONE_METHOD, null, new Bundle());
            }
        } catch (Throwable t) {
            log("host result ack failed: " + t);
        }
    }

    private static ContentResolver hostResolver(Context context) {
        Context host = null;
        try {
            host = top.niunaijun.blackbox.BlackBoxCore.getContext();
        } catch (Throwable ignored) {
            // Use the supplied context below.
        }
        Context target = host != null ? host : context;
        return target == null ? null : target.getContentResolver();
    }

    public static void clearPending(Context context) {
        synchronized (PENDING_LOCK) {
            File file = pendingFile(context);
            if (file != null && file.exists() && !file.delete()) {
                log("pending result clear failed");
            }
        }
    }

    private static File pendingFile(Context context) {
        if (context == null) {
            return null;
        }
        Context app = context.getApplicationContext();
        Context target = app != null ? app : context;
        File dir = target.getFilesDir();
        return dir == null ? null : new File(dir, PENDING_FILE);
    }

    /** Ask the host process to bring the existing virtual game task forward. */
    public static void restoreGame(Context context) {
        try {
            String authority = hostPackage(context) + ".SystemCallProvider";
            Uri uri = new Uri.Builder().scheme("content").authority(authority).build();
            Context host = null;
            try {
                host = top.niunaijun.blackbox.BlackBoxCore.getContext();
            } catch (Throwable ignored) {
                // Use the supplied context below.
            }
            ContentResolver resolver = host != null ? host.getContentResolver()
                    : (context != null ? context.getContentResolver() : null);
            if (resolver == null) {
                return;
            }
            Bundle args = new Bundle();
            if (context != null) {
                args.putString("virtual_package", context.getPackageName());
            }
            resolver.call(uri, RESTORE_METHOD, null, args);
            log("virtual game task restore requested");
        } catch (Throwable t) {
            log("virtual game task restore failed: " + t);
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

    private static boolean invokeFacebookResult(ClassLoader cl, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        int code = root.getInt("code");
        Object listener = facebookListener(cl);
        if (listener == null) {
            log("facebook result dropped: listener unavailable");
            return false;
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
        return true;
    }

    private static boolean invokeFacebookFailure(ClassLoader cl, int code, String msg) throws Exception {
        Object listener = facebookListener(cl);
        if (listener == null) {
            log("facebook cancellation dropped: listener unavailable");
            return false;
        }
        Object result = newIMSDKResult(cl, code, code, msg == null ? "" : msg);
        invokeListener(listener, "onResult", result);
        clearFacebookListener(cl);
        return true;
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

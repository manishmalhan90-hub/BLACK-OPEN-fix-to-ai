package top.niunaijun.blackbox.utils.auth;

import android.os.Process;
import android.util.Log;

import top.niunaijun.blackbox.core.ZoroRuntimeLogger;

/**
 * Stability guard for the virtual game process during isolated web login.
 *
 * <p>On this device the game process aborts inside ART MarkCompact roughly one
 * minute after launch. The login WebView now runs in {@code :login_web}, but the
 * virtual process still needs to stay alive until the JSON result is delivered
 * to its own IMSDK callback. The host process briefly freezes the virtual
 * process while the web page is owned by the isolated process, then resumes it
 * as soon as the result is stored and ready to deliver.</p>
 *
 * <p>This only changes process scheduling. No anti-cheat or game logic is
 * touched, and no login data is logged here. v15 adds the matching
 * {@code libMCoreEsp} signal helpers and a hard timeout that always resumes the
 * game process if the web page never returns.</p>
 */
public final class WebLoginProcessGuard {

    private static final String TAG = "WebLoginGuard";

    private WebLoginProcessGuard() {
    }

    /** Implemented by libMCoreEsp in the loader APK. */
    public static native void freeze(int pid);

    /** Implemented by libMCoreEsp in the loader APK. */
    public static native void resume(int pid);

    public static void freezeIfDifferent(int pid) {
        if (pid <= 0 || pid == Process.myPid()) {
            return;
        }
        try {
            freeze(pid);
            log("virtual process frozen for isolated login | pid=" + pid);
        } catch (Throwable t) {
            log("virtual process freeze unavailable: " + t);
        }
    }

    public static void resumeIfDifferent(int pid) {
        if (pid <= 0 || pid == Process.myPid()) {
            return;
        }
        try {
            resume(pid);
            log("virtual process resumed for result delivery | pid=" + pid);
        } catch (Throwable t) {
            log("virtual process resume unavailable: " + t);
        }
    }

    private static void log(String message) {
        ZoroRuntimeLogger.logLogin("login-guard", message);
        Log.d(TAG, message);
    }
}

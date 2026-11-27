package top.niunaijun.blackbox.utils.auth;

/**
 * Compatibility stub for the old process-freeze experiment.
 *
 * <p>v15 used SIGSTOP/SIGCONT while the isolated WebView owned the screen. On
 * the device under test a stopped virtual process stopped answering Binder,
 * which produced an ANR/SIGQUIT dump and then a SIGKILL before (or just after)
 * the login result arrived. v16 leaves the game process running normally and
 * relies only on the stored result plus the existing IMSDK callbacks.</p>
 *
 * <p>The methods remain so older callers do not break, but they are intentionally
 * no-ops. No signals are sent and no login data is logged here.</p>
 */
public final class WebLoginProcessGuard {

    private WebLoginProcessGuard() {
    }

    /** Intentionally disabled since v16. */
    public static void freezeIfDifferent(int pid) {
        // no-op
    }

    /** Intentionally disabled since v16. */
    public static void resumeIfDifferent(int pid) {
        // no-op
    }
}

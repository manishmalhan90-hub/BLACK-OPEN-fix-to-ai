package top.niunaijun.blackbox.core;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Runtime diagnostics logger.
 *
 * Writes Java exceptions, native library load failures and the device logcat
 * (which includes debuggerd/tombstone output for native crashes) to a single
 * file: Download/zoro.txt. The file rotates to Download/zoro.1.txt after 5 MB.
 *
 * Extra digests:
 *  - Download/zoro_crash.txt: fatal lines only.
 *  - Download/zoro_detect.txt: detection-related evidence only (logging only,
 *    no hiding or bypassing).
 *  - Download/zoro_tombstone.txt: raw logcat crash buffer, when available.
 *
 * This is logging only - it does not hide, alter or bypass anything.
 */
public final class ZoroRuntimeLogger {
    private static final String TAG = "ZoroRuntimeLogger";
    private static final String FILE_NAME = "zoro.txt";
    private static final String BACKUP_NAME = "zoro.1.txt";
    private static final String CRASH_FILE_NAME = "zoro_crash.txt";
    private static final String DETECT_FILE_NAME = "zoro_detect.txt";
    private static final String TOMBSTONE_FILE_NAME = "zoro_tombstone.txt";
    private static final long MAX_FILE_SIZE = 5L * 1024L * 1024L;

    private static final Object LOCK = new Object();
    private static File sLogFile;
    private static String sProcess = "unknown";
    private static boolean sStarted;

    private ZoroRuntimeLogger() {
    }

    public static synchronized void init(Context context, String processName) {
        if (sStarted || context == null) {
            return;
        }
        sStarted = true;
        sProcess = processName == null ? context.getPackageName() : processName;
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloads != null && !downloads.exists()) {
                downloads.mkdirs();
            }
            sLogFile = new File(downloads, FILE_NAME);
            append("=== ZORO runtime log started | pid=" + android.os.Process.myPid()
                    + " | process=" + sProcess + " | " + now() + " ===");
            createDiagnosticsFile(CRASH_FILE_NAME,
                    now() + " === fatal digest started | " + sProcess + " ===\n");
            createDiagnosticsFile(DETECT_FILE_NAME,
                    now() + " === detection evidence started | logging only ===\n");
            createDiagnosticsFile(TOMBSTONE_FILE_NAME,
                    now() + " === native crash buffer started ===\n");
            startLogcatReader();
            startCrashReader();
        } catch (Throwable t) {
            Log.w(TAG, "Could not initialise runtime log file", t);
        }
    }

    public static void log(String source, String message) {
        append(now() + " [" + sProcess + "/" + android.os.Process.myPid() + "] " + source + ": " + message);
    }

    public static void logThrowable(String source, Throwable throwable) {
        if (throwable == null) {
            return;
        }
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        printWriter.println(now() + " [" + sProcess + "/" + android.os.Process.myPid() + "] EXCEPTION [" + source + "]");
        throwable.printStackTrace(printWriter);
        printWriter.flush();
        append(stringWriter.toString());
    }

    private static void append(String text) {
        writeTo(sLogFile, text);
    }

    private static void appendToFile(String fileName, String text) {
        if (fileName == null || text == null) {
            return;
        }
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            writeTo(new File(downloads, fileName), text);
        } catch (Throwable ignored) {
            // Diagnostics must never affect the host application.
        }
    }

    private static void createDiagnosticsFile(String fileName, String header) {
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(downloads, fileName);
            if (!file.exists()) {
                writeTo(file, header);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never affect the host application.
        }
    }

    private static void writeTo(File file, String text) {
        if (file == null || text == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                File dir = file.getParentFile();
                if (dir != null && !dir.exists()) {
                    dir.mkdirs();
                }
                int length = text.getBytes("UTF-8").length;
                if (file.exists() && file.length() + length > MAX_FILE_SIZE) {
                    String backupName = FILE_NAME.equals(file.getName())
                            ? BACKUP_NAME
                            : BACKUP_NAME + "." + file.getName();
                    File backup = new File(file.getParentFile(), backupName);
                    if (backup.exists()) {
                        backup.delete();
                    }
                    file.renameTo(backup);
                }
                FileWriter writer = new FileWriter(file, true);
                try {
                    writer.write(text);
                    if (!text.endsWith("\n")) {
                        writer.write("\n");
                    }
                } finally {
                    writer.close();
                }
            } catch (Throwable ignored) {
                // Logging must never break the host application.
            }
        }
    }

    private static void startLogcatReader() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = readLogcat(new String[]{"logcat", "-b", "main,system,crash", "-v", "threadtime"},
                        FILE_NAME, true);
                if (!ok) {
                    readLogcat(new String[]{"logcat", "-v", "threadtime"}, FILE_NAME, true);
                }
            }
        }, "zoro-runtime-logger").start();
    }

    /** Reads the crash buffer separately so tombstones are not lost in the main log stream. */
    private static void startCrashReader() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = readLogcat(new String[]{"logcat", "-b", "crash", "-v", "threadtime"},
                        TOMBSTONE_FILE_NAME, false);
                if (!ok) {
                    appendToFile(TOMBSTONE_FILE_NAME,
                            now() + " [tombstone] crash buffer is not readable on this device");
                }
            }
        }, "zoro-crash-reader").start();
    }

    private static boolean readLogcat(String[] command, String targetFile, boolean flag) {
        java.lang.Process process = null;
        boolean receivedLine = false;
        try {
            process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"), 8192);
            String line;
            while ((line = reader.readLine()) != null) {
                receivedLine = true;
                // Native crashes (debuggerd/tombstones) and fatal Java exceptions
                // end up in logcat, so they land in the same file.
                writeToNamed(targetFile, "[logcat] " + line);
                if (flag) {
                    maybeFlagCrash(line);
                }
            }
        } catch (Throwable t) {
            log("logcat", "unavailable: " + t);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return receivedLine;
    }

    private static void writeToNamed(String fileName, String text) {
        if (FILE_NAME.equals(fileName)) {
            append(text);
        } else {
            appendToFile(fileName, text);
        }
    }

    /** Keeps short, dedicated digests of fatal and detection-related lines. */
    private static void maybeFlagCrash(String line) {
        if (line == null) {
            return;
        }
        String lower = line.toLowerCase(Locale.US);
        boolean fatal = lower.contains("fatal exception")
                || lower.contains(" passed to free")
                || lower.contains("app died")
                || lower.contains("tombstone")
                || lower.contains("fatal signal")
                || lower.contains("androidruntime")
                || lower.contains("abort message");
        if (fatal) {
            String flagged = now() + " [!!CRASH!!] " + line;
            appendToFile(CRASH_FILE_NAME, flagged);
        }
        maybeFlagDetection(line, lower);
    }

    /**
     * Records evidence that may explain why the game behaves differently inside
     * the container. This is diagnostic-only: it never hides or modifies the
     * values the game reads.
     */
    private static void maybeFlagDetection(String line, String lower) {
        if (line == null || lower == null || !isDetectionLine(lower)) {
            return;
        }
        appendToFile(DETECT_FILE_NAME, now() + " [!!DETECT!!] " + line);
    }

    private static boolean isDetectionLine(String lower) {
        if (lower.contains("libsigchain:")
                && (lower.contains("system+") || lower.contains("libbgmi.so"))) {
            return true;
        }
        if (lower.contains("nativecore:")
                && (lower.contains("systempropertieshook") || lower.contains("addiorule")
                || lower.contains("enableio") || lower.contains("register class"))) {
            return true;
        }
        if (lower.contains("avc: denied")
                && (lower.contains("/dev/pts") || lower.contains("untrusted_app"))) {
            return true;
        }
        if (lower.contains("invalid path: /storage/emulated/0/sdcard")
                || lower.contains("libgameextimpl.so")
                || lower.contains("hook error")
                || lower.contains("missing package names; no storage volumes available")
                || lower.contains("access denied finding property")) {
            return true;
        }
        if (lower.contains("libanogs.so") || lower.contains("libcrashkit.so")
                || lower.contains("[crashkit")) {
            return true;
        }
        if (lower.contains("connection to service is blocked")
                || lower.contains("failed to get conditional properties")
                || lower.contains("not connected to service")) {
            return true;
        }
        return lower.contains("unknown bits set in runtime_flags")
                || lower.contains("attempt to load writable file");
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}

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
 * This is logging only - it does not hide, alter or bypass anything.
 */
public final class ZoroRuntimeLogger {
    private static final String TAG = "ZoroRuntimeLogger";
    private static final String FILE_NAME = "zoro.txt";
    private static final String BACKUP_NAME = "zoro.1.txt";
    private static final String CRASH_FILE_NAME = "zoro_crash.txt";
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
            startLogcatReader();
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
        synchronized (LOCK) {
            File file = sLogFile;
            if (file == null || text == null) {
                return;
            }
            try {
                File dir = file.getParentFile();
                if (dir != null && !dir.exists()) {
                    dir.mkdirs();
                }
                if (file.exists() && file.length() + text.getBytes("UTF-8").length > MAX_FILE_SIZE) {
                    File backup = new File(file.getParentFile(), BACKUP_NAME);
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
                boolean ok = readLogcat(new String[]{"logcat", "-b", "main,system,crash", "-v", "threadtime"});
                if (!ok) {
                    readLogcat(new String[]{"logcat", "-v", "threadtime"});
                }
            }
        }, "zoro-runtime-logger").start();
    }

    private static boolean readLogcat(String[] command) {
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
                append("[logcat] " + line);
                maybeFlagCrash(line);
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

    /** Keeps a short, dedicated digest of fatal lines for fast diagnosis. */
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
        if (!fatal) {
            return;
        }
        String flagged = now() + " [!!CRASH!!] " + line;
        append(flagged);
        synchronized (LOCK) {
            try {
                File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File digest = new File(downloads, CRASH_FILE_NAME);
                FileWriter writer = new FileWriter(digest, true);
                try {
                    writer.write(flagged);
                    if (!flagged.endsWith("\n")) {
                        writer.write("\n");
                    }
                } finally {
                    writer.close();
                }
            } catch (Throwable ignored) {
                // Diagnostics must never affect the host application.
            }
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}

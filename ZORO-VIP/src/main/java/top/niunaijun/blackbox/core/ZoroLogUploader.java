package top.niunaijun.blackbox.core;

import android.content.Context;
import android.os.Environment;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Uploads a diagnostics file from Download/ to a GitHub repository using the
 * Contents API. The token is supplied by the calling app at runtime; it is
 * never stored in this library.
 */
public final class ZoroLogUploader {
    public interface Callback {
        void onResult(boolean success, String message);
    }

    private static final String API = "https://api.github.com";
    private static final Object UPLOAD_LOCK = new Object();

    private ZoroLogUploader() {
    }

    public static void upload(final Context context, final String token, final String owner,
                              final String repo, final String branch, final String localFileName,
                              final String remotePath, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean[] ok = new boolean[]{false};
                final String[] message = new String[]{"Unknown error"};
                try {
                    if (token == null || token.length() == 0) {
                        throw new IllegalArgumentException("GitHub token is empty");
                    }
                    File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File file = new File(downloads, localFileName);
                    if (!file.exists()) {
                        throw new IllegalStateException("Log file not found: " + file.getAbsolutePath());
                    }

                    // Serialising GET+PUT prevents two sync threads from using the
                    // same stale SHA. A fresh SHA on retry also handles conflicts
                    // caused by commits made while the app was running.
                    synchronized (UPLOAD_LOCK) {
                        byte[] bytes = readAll(file);
                        JSONObject body = new JSONObject();
                        body.put("message", "Update " + remotePath + " from device");
                        body.put("branch", branch);
                        body.put("content", Base64.encodeToString(bytes, Base64.DEFAULT));

                        int code = 0;
                        String error = "";
                        for (int attempt = 0; attempt < 3; attempt++) {
                            String sha = getExistingSha(owner, repo, branch, remotePath, token);
                            if (sha != null) {
                                body.put("sha", sha);
                            } else {
                                body.remove("sha");
                            }
                            String[] result = putFile(owner, repo, remotePath, token,
                                    body.toString().getBytes("UTF-8"));
                            code = Integer.parseInt(result[0]);
                            if (code >= 200 && code < 300) {
                                break;
                            }
                            error = result[1];
                            if (code != 409) {
                                break;
                            }
                        }
                        ok[0] = code >= 200 && code < 300;
                        message[0] = ok[0]
                                ? "Uploaded " + remotePath + " to " + owner + "/" + repo
                                : "GitHub returned HTTP " + code
                                + (error == null || error.trim().isEmpty() ? "" : " - " + error.trim());
                    }
                } catch (Throwable t) {
                    message[0] = t.getMessage() == null ? t.toString() : t.getMessage();
                }

                if (callback != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onResult(ok[0], message[0]);
                        }
                    });
                }
            }
        }, "zoro-log-upload").start();
    }

    private static String getExistingSha(String owner, String repo, String branch,
                                         String path, String token) throws Exception {
        String url = API + "/repos/" + owner + "/" + repo + "/contents/" + path
                + "?ref=" + URLEncoder.encode(branch, "UTF-8")
                + "&t=" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        applyHeaders(connection, token);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        try {
            int code = connection.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Lookup failed: HTTP " + code);
            }
            JSONObject json = new JSONObject(readString(connection.getInputStream()));
            return json.optString("sha", null);
        } finally {
            connection.disconnect();
        }
    }

    /** Returns {HTTP code, short response body} so 409 conflicts can be diagnosed. */
    private static String[] putFile(String owner, String repo, String path, String token, byte[] body)
            throws Exception {
        String url = API + "/repos/" + owner + "/" + repo + "/contents/" + path;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        applyHeaders(connection, token);
        try {
            OutputStream out = connection.getOutputStream();
            try {
                out.write(body);
            } finally {
                out.close();
            }
            int code = connection.getResponseCode();
            InputStream errorStream = connection.getErrorStream();
            String error = errorStream == null ? "" : readString(errorStream);
            if (error.length() > 300) {
                error = error.substring(0, 300);
            }
            return new String[]{String.valueOf(code), error};
        } finally {
            connection.disconnect();
        }
    }

    private static void applyHeaders(HttpURLConnection connection, String token) {
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "ZORO-Runtime-Logger");
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
    }

    private static byte[] readAll(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String readString(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } finally {
            reader.close();
        }
    }
}

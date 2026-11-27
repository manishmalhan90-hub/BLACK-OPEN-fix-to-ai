package top.niunaijun.blackbox.utils.auth;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebStorage;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

import top.niunaijun.blackbox.core.ZoroRuntimeLogger;

/**
 * WebView host that runs in the isolated {@code :login_web} process.
 *
 * <p>The virtual game process used to abort in ART MarkCompact while this
 * WebView was alive. Hosting the same page in a clean process keeps the game
 * process untouched. The JSON result is sent back with a package-scoped
 * broadcast and delivered to the game's own IMSDK/Twitter callback.</p>
 *
 * <p>v13 keeps the OAuth flow inside the WebView: popups and {@code intent://}
 * redirects from the X login page are folded back into the same WebView, a
 * URL-delivered JSON result is accepted as a fallback, and a Retry button is
 * available if the current 2026 login page stops responding. v19 stores the
 * result plus its request code in a shared loader directory before broadcasting,
 * so the receiver or the pending-result poller can still deliver it after a
 * virtual-process restart. The game process is never stopped. No credentials
 * are injected and no token or full OAuth URL is ever logged.</p>
 */
public final class TwitterWebHostActivity extends Activity {

    private static final String TAG = "WebLoginHost";
    private static final long STUCK_CHECK_MS = 20000L;
    private static final long LOGIN_TIMEOUT_MS = 10L * 60L * 1000L;
    private static boolean sDataDirSet;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private WebView mWebView;
    private ProgressBar mProgress;
    private Button mRetry;
    private String mKind = "twitter";
    private String mUrl;
    private String mVirtualPackage = "com.pubg.imobile";
    private boolean mFinished;
    private boolean mPageFinished;
    private int mLoadAttempts;
    private int mVirtualPid;
    private int mRequestCode = 1;
    private Runnable mStuckCheck;
    private Runnable mTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Must run before any WebView is created in this process. The main
        // com.google process already owns the default WebView data directory.
        configureWebViewDataDirectory();
        super.onCreate(savedInstanceState);
        mKind = getIntent() == null ? "twitter" : getIntent().getStringExtra("kind");
        if (!"facebook".equals(mKind)) {
            mKind = "twitter";
        }
        mUrl = getIntent() == null ? null : getIntent().getStringExtra("url");
        mVirtualPid = getIntent() == null ? 0
                : getIntent().getIntExtra(WebLoginBridge.EXTRA_VIRTUAL_PID, 0);
        mRequestCode = getIntent() == null ? 1
                : getIntent().getIntExtra("request_code", 1);
        if (getIntent() != null && !TextUtils.isEmpty(
                getIntent().getStringExtra("virtual_package"))) {
            mVirtualPackage = getIntent().getStringExtra("virtual_package");
        }
        if (TextUtils.isEmpty(mUrl)) {
            log("host started without url");
            sendFailure(codeForClose(), messageForClose());
            return;
        }

        log("host started | kind=" + mKind + " | host=" + safeHost(mUrl)
                + " | virtual_pid=" + mVirtualPid);
        scheduleTimeout();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0xFFF5F5F5);

        TextView title = new TextView(this);
        title.setText(mKind.equals("facebook") ? "Facebook login" : "Twitter / X login");
        title.setGravity(Gravity.CENTER);
        title.setTextSize(16f);
        title.setTextColor(0xFF333333);
        title.setPadding(0, 24, 0, 16);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setIndeterminate(false);
        mProgress.setMax(100);
        root.addView(mProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mWebView = new WebView(this);
        mWebView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        configureWebView(mWebView);
        root.addView(mWebView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mRetry = new Button(this);
        mRetry.setText("Retry");
        mRetry.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartLoginPage();
            }
        });
        buttons.addView(mRetry);

        Button close = new Button(this);
        close.setText("Close");
        close.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendFailure(codeForClose(), messageForClose());
            }
        });
        buttons.addView(close);
        root.addView(buttons);

        setContentView(root);
        prepareAndLoad();
    }

    /** Give :login_web its own WebView data directory before WebView is created. */
    private static void configureWebViewDataDirectory() {
        if (sDataDirSet) {
            return;
        }
        sDataDirSet = true;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                WebView.setDataDirectorySuffix("login_web");
                log("webview data dir suffix set to login_web");
            } else {
                log("webview data dir suffix skipped (old Android)");
            }
        } catch (Throwable t) {
            log("webview data dir suffix failed: " + t);
        }
    }

    /**
     * Clears WebView cache, storage and cookies, then loads the OAuth page only
     * after cookie clearing has completed. A stale session was one reason the
     * current 2026 login page could stop at the password step.
     */
    private void prepareAndLoad() {
        if (mWebView == null || TextUtils.isEmpty(mUrl)) {
            return;
        }
        try {
            mWebView.clearCache(true);
            WebStorage.getInstance().deleteAllData();
            log("webview cache + storage cleared");
        } catch (Throwable t) {
            log("webview cache clear failed: " + t);
        }

        final CookieManager cookies = CookieManager.getInstance();
        if (cookies == null) {
            loadAndWatch();
            return;
        }
        try {
            cookies.removeSessionCookies(null);
            cookies.removeAllCookies(new ValueCallback<Boolean>() {
                @Override
                public void onReceiveValue(Boolean value) {
                    try {
                        cookies.setAcceptCookie(true);
                        if (Build.VERSION.SDK_INT >= 21) {
                            cookies.setAcceptThirdPartyCookies(mWebView, true);
                        }
                    } catch (Throwable t) {
                        log("cookie restore failed: " + t);
                    }
                    log("webview cookies cleared");
                    loadAndWatch();
                }
            });
        } catch (Throwable t) {
            log("webview cookie clear failed: " + t);
            loadAndWatch();
        }
    }

    private void loadAndWatch() {
        if (mFinished || mWebView == null || TextUtils.isEmpty(mUrl)) {
            return;
        }
        mWebView.loadUrl(mUrl);
        scheduleStuckCheck();
    }

    private void restartLoginPage() {
        if (mFinished) {
            return;
        }
        mLoadAttempts = 0;
        mPageFinished = false;
        log("retry requested | host=" + safeHost(mUrl));
        prepareAndLoad();
    }

    private void scheduleStuckCheck() {
        cancelStuckCheck();
        mStuckCheck = new Runnable() {
            @Override
            public void run() {
                if (mFinished || mPageFinished || mWebView == null) {
                    return;
                }
                if (mLoadAttempts == 0) {
                    mLoadAttempts++;
                    log("initial page load stalled, retrying once | host=" + safeHost(mUrl));
                    mWebView.reload();
                    scheduleStuckCheck();
                } else {
                    log("page still loading, Retry button available | host=" + safeHost(mUrl));
                }
            }
        };
        mMain.postDelayed(mStuckCheck, STUCK_CHECK_MS);
    }

    private void cancelStuckCheck() {
        if (mStuckCheck != null) {
            mMain.removeCallbacks(mStuckCheck);
            mStuckCheck = null;
        }
    }

    /**
     * A user can leave the OAuth page open forever. Always release the frozen
     * game process and report a clean cancellation rather than leaving it
     * stopped in memory.
     */
    private void scheduleTimeout() {
        cancelTimeout();
        mTimeout = new Runnable() {
            @Override
            public void run() {
                if (mFinished) {
                    return;
                }
                log("login timed out; releasing virtual process");
                sendFailure(9999, "login timed out");
            }
        };
        mMain.postDelayed(mTimeout, LOGIN_TIMEOUT_MS);
    }

    private void cancelTimeout() {
        if (mTimeout != null) {
            mMain.removeCallbacks(mTimeout);
            mTimeout = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void configureWebView(WebView webView) {
        WebSettings settings = webView.getSettings();
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
            }
        } catch (Throwable t) {
            log("third-party cookie enable failed: " + t);
        }
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        // Match the game's original WebSettings: no cache reuse during OAuth.
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setDatabaseEnabled(true);
        settings.setSavePassword(true);
        settings.setBuiltInZoomControls(false);
        settings.setTextZoom(100);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                mPageFinished = false;
                if (mProgress != null) {
                    mProgress.setVisibility(View.VISIBLE);
                }
                log("page started | host=" + safeHost(url));
                scheduleStuckCheck();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                mPageFinished = true;
                cancelStuckCheck();
                if (mProgress != null) {
                    mProgress.setProgress(100);
                    mProgress.setVisibility(View.GONE);
                }
                log("page finished | host=" + safeHost(url));
                extractAndSendResult(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) {
                    return false;
                }
                return handleUrl(view, request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(view, url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request == null || !request.isForMainFrame()) {
                    return;
                }
                int code = error == null ? -1 : error.getErrorCode();
                String url = request.getUrl() == null ? "" : request.getUrl().toString();
                log("web error | code=" + code + " | host=" + safeHost(url));
                if (mLoadAttempts < 2) {
                    mLoadAttempts++;
                    mMain.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!mFinished && mWebView != null) {
                                mWebView.reload();
                                scheduleStuckCheck();
                            }
                        }
                    }, 1000L);
                    return;
                }
                sendFailure(9999, "web error " + code);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (mProgress != null) {
                    mProgress.setProgress(newProgress);
                    mProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                if (resultMsg == null || mWebView == null) {
                    return false;
                }
                WebView popup = new WebView(TwitterWebHostActivity.this);
                popup.getSettings().setJavaScriptEnabled(true);
                popup.getSettings().setDomStorageEnabled(true);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView popupView, String url) {
                        handlePopupUrl(url);
                        return true;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView popupView,
                                                            WebResourceRequest request) {
                        handlePopupUrl(request == null || request.getUrl() == null
                                ? null : request.getUrl().toString());
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                log("popup moved into main login webview");
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                // Console text can contain OAuth JSON. Never copy it into logs.
                return true;
            }
        });

        // The game page calls one of these JS interfaces with its result JSON.
        webView.addJavascriptInterface(new ResultInterface("twitter"), "TwitterWebLogin");
        webView.addJavascriptInterface(new ResultInterface("facebook"), "WebLogin");
    }

    /** Keep popups, intent redirects and custom schemes inside this WebView. */
    private boolean handleUrl(WebView view, String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        if (extractAndSendResult(url)) {
            return true;
        }
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("intent:")) {
            String target = intentTarget(url);
            if (target != null && (target.startsWith("http://") || target.startsWith("https://"))) {
                log("intent redirect kept inside webview | host=" + safeHost(target));
                view.loadUrl(target);
            } else {
                log("unsupported intent redirect ignored");
            }
            return true;
        }
        if (lower.startsWith("tel:") || lower.startsWith("mailto:")
                || lower.startsWith("sms:") || lower.startsWith("market:")) {
            return true;
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            log("custom scheme ignored | scheme=" + scheme(url));
            return true;
        }
        return false;
    }

    private void handlePopupUrl(String url) {
        if (!TextUtils.isEmpty(url) && extractAndSendResult(url)) {
            return;
        }
        if (!TextUtils.isEmpty(url) && mWebView != null) {
            log("popup url moved to main webview | host=" + safeHost(url));
            mWebView.loadUrl(url);
            scheduleStuckCheck();
        }
    }

    /** Accept a JSON login result delivered in a navigation URL as a fallback. */
    private boolean extractAndSendResult(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        String[] keys = {"result", "result_json", "json", "loginResult",
                "oauth_result", "data"};
        for (String key : keys) {
            String value = parameterValue(url, key);
            if (TextUtils.isEmpty(value) || value.indexOf('{') < 0) {
                continue;
            }
            try {
                new JSONObject(value);
                log("login result captured from navigation");
                sendJson(value);
                return true;
            } catch (Throwable ignored) {
                String decoded = Uri.decode(value);
                try {
                    new JSONObject(decoded);
                    log("login result captured from navigation");
                    sendJson(decoded);
                    return true;
                } catch (Throwable ignoredAgain) {
                    // Not a result parameter; keep loading.
                }
            }
        }
        return false;
    }

    private static String parameterValue(String url, String key) {
        int question = url.indexOf('?');
        int hash = url.indexOf('#');
        int start;
        if (question >= 0 && (hash < 0 || question < hash)) {
            start = question + 1;
        } else if (hash >= 0) {
            start = hash + 1;
        } else {
            return null;
        }
        String query = url.substring(start);
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if (key.equals(Uri.decode(pair.substring(0, equals)))) {
                return Uri.decode(pair.substring(equals + 1));
            }
        }
        return null;
    }

    private static String intentTarget(String url) {
        int hash = url.indexOf('#');
        if (hash < 0) {
            return null;
        }
        String[] parts = url.substring(hash + 1).split(";");
        for (String part : parts) {
            if (part.startsWith("S=")) {
                return Uri.decode(part.substring(2));
            }
        }
        return null;
    }

    private static String scheme(String url) {
        int colon = url.indexOf(':');
        return colon > 0 ? url.substring(0, colon).toLowerCase(Locale.US) : "(none)";
    }

    private final class ResultInterface {
        private final String mResultKind;

        ResultInterface(String resultKind) {
            mResultKind = resultKind;
        }

        @JavascriptInterface
        public void onResult(String json) {
            if (TextUtils.isEmpty(json)) {
                sendFailure(9999, "login result is empty");
                return;
            }
            log("login result received | kind=" + mResultKind + " | length=" + json.length());
            sendJson(json);
        }
    }

    private void sendJson(String json) {
        if (mFinished) {
            return;
        }
        Intent result = new Intent(WebLoginBridge.ACTION_RESULT);
        result.setPackage(getPackageName());
        result.putExtra("kind", mKind);
        result.putExtra("request_code", mRequestCode);
        result.putExtra(WebLoginBridge.EXTRA_OWNER_PID, mVirtualPid);
        result.putExtra("result_json", json);
        deliverResult(result);
    }

    private void sendFailure(int code, String message) {
        if (mFinished) {
            return;
        }
        Intent result = new Intent(WebLoginBridge.ACTION_RESULT);
        result.setPackage(getPackageName());
        result.putExtra("kind", mKind);
        result.putExtra("request_code", mRequestCode);
        result.putExtra(WebLoginBridge.EXTRA_OWNER_PID, mVirtualPid);
        result.putExtra("result_code", code);
        result.putExtra("result_msg", message == null ? "" : message);
        deliverResult(result);
    }

    /**
     * Store first, then broadcast. The receiver in the virtual process delivers
     * the JSON to the game's own callback; WebLoginBridge also polls the stored
     * copy in a shared directory so a virtual-process restart cannot lose the
     * result. v19 never freezes the game process and carries the game request
     * code so cancellations reach o5.a.k(boolean,int,int,Intent). If the game
     * task was removed after an ART abort, ask the host to bring it back and
     * relaunch its process before this WebView goes away.
     */
    private void deliverResult(final Intent result) {
        if (mFinished) {
            return;
        }
        mFinished = true;
        cancelStuckCheck();
        cancelTimeout();
        WebLoginBridge.savePending(this, result);
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    sendBroadcast(result);
                } catch (Throwable t) {
                    log("result broadcast failed: " + t);
                }
                WebLoginBridge.restoreGame(TwitterWebHostActivity.this, mVirtualPackage);
            }
        }, 80L);
        finish();
    }

    private int codeForClose() {
        return "facebook".equals(mKind) ? 2 : 0;
    }

    private String messageForClose() {
        return "facebook".equals(mKind) ? "User click close button" : "user click close";
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
            return;
        }
        sendFailure(codeForClose(), "facebook".equals(mKind)
                ? "User click back button" : "user click close");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mWebView != null) {
            mWebView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWebView != null) {
            mWebView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        cancelStuckCheck();
        cancelTimeout();
        if (!mFinished) {
            // System destroyed the page without a result; the virtual process
            // keeps running normally and the user can retry from the game.
            mFinished = true;
        }
        if (mWebView != null) {
            mWebView.removeAllViews();
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }

    private static String safeHost(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? "(none)" : host;
        } catch (Throwable t) {
            return "(invalid)";
        }
    }

    private static void log(String message) {
        ZoroRuntimeLogger.logLogin("login-host", message);
        Log.d(TAG, message);
    }
}

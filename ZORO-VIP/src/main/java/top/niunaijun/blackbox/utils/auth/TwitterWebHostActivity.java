package top.niunaijun.blackbox.utils.auth;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
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

import top.niunaijun.blackbox.core.ZoroRuntimeLogger;

/**
 * WebView host that runs in the isolated {@code :login_web} process.
 *
 * <p>The virtual game process used to abort in ART MarkCompact while this
 * WebView was alive. Hosting the same page in a clean process keeps the game
 * process untouched. The JSON result is sent back with a package-scoped
 * broadcast and delivered to the game's existing IMSDK callback.</p>
 */
public final class TwitterWebHostActivity extends Activity {

    private static final String TAG = "WebLoginHost";
    private static boolean sDataDirSet;

    private WebView mWebView;
    private ProgressBar mProgress;
    private String mKind = "twitter";
    private boolean mFinished;

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
        String url = getIntent() == null ? null : getIntent().getStringExtra("url");
        if (TextUtils.isEmpty(url)) {
            log("host started without url");
            sendFailure(codeForClose(), messageForClose());
            return;
        }

        log("host started | kind=" + mKind + " | host=" + safeHost(url));

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
        // Fresh WebView state for every login attempt. No tokens are logged.
        clearLoginCache();
        configureWebView(mWebView);
        root.addView(mWebView);

        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendFailure(codeForClose(), messageForClose());
            }
        });
        root.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        mWebView.loadUrl(url);
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

    /** Clears WebView cache and cookies before loading the OAuth page. */
    private void clearLoginCache() {
        try {
            if (mWebView != null) {
                mWebView.clearCache(true);
            }
            WebStorage.getInstance().deleteAllData();
            log("webview cache + storage cleared");
        } catch (Throwable t) {
            log("webview cache clear failed: " + t);
        }
        try {
            CookieManager cookies = CookieManager.getInstance();
            if (cookies != null) {
                cookies.removeAllCookies(null);
                cookies.removeSessionCookies(null);
                cookies.setAcceptCookie(true);
                log("webview cookies cleared");
            }
        } catch (Throwable t) {
            log("webview cookie clear failed: " + t);
        }
    }

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
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDatabaseEnabled(true);
        settings.setSavePassword(true);
        settings.setBuiltInZoomControls(false);
        settings.setTextZoom(100);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (mProgress != null) {
                    mProgress.setProgress(100);
                    mProgress.setVisibility(View.GONE);
                }
                log("page finished | host=" + safeHost(url));
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request == null || !request.isForMainFrame()) {
                    return;
                }
                int code = error == null ? -1 : error.getErrorCode();
                String message = error == null ? "" : String.valueOf(error.getDescription());
                log("web error | code=" + code);
                sendFailure(9999, message);
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
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                // Console text can contain OAuth JSON. Never copy it into logs.
                return true;
            }
        });

        // The game page calls one of these JS interfaces with its result JSON.
        webView.addJavascriptInterface(new ResultInterface("twitter"), "TwitterWebLogin");
        webView.addJavascriptInterface(new ResultInterface("facebook"), "WebLogin");
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
        mFinished = true;
        Intent result = new Intent(WebLoginBridge.ACTION_RESULT);
        result.setPackage(getPackageName());
        result.putExtra("kind", mKind);
        result.putExtra("result_json", json);
        sendBroadcast(result);
        finish();
    }

    private void sendFailure(int code, String message) {
        if (mFinished) {
            return;
        }
        mFinished = true;
        Intent result = new Intent(WebLoginBridge.ACTION_RESULT);
        result.setPackage(getPackageName());
        result.putExtra("kind", mKind);
        result.putExtra("result_code", code);
        result.putExtra("result_msg", message == null ? "" : message);
        sendBroadcast(result);
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

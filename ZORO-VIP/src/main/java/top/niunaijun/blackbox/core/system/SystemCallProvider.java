package top.niunaijun.blackbox.core.system;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BundleCompat;

/**
 * Created by @RIYAZXERO on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class SystemCallProvider extends ContentProvider {
    public static final String TAG = "SystemCallProvider";

    @Override
    public boolean onCreate() {
        return initSystem();
    }

    private boolean initSystem() {
        BlackBoxSystem.getSystem().startup();
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if ("START_WEB_LOGIN".equals(method)) {
            // extras contain the auth URL; never print them.
            Slog.d(TAG, "call: START_WEB_LOGIN");
            launchWebLogin(extras);
            return new Bundle();
        }
        Slog.d(TAG, "call: " + method + ", " + extras);
        if ("VM".equals(method)) {
            Bundle bundle = new Bundle();
            if (extras != null) {
                String name = extras.getString("_G_|_server_name_");
                BundleCompat.putBinder(bundle, "_G_|_server_", ServiceManager.getService(name));
            }
            return bundle;
        }
        return super.call(method, arg, extras);
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    /**
     * Starts the WebView host in the loader's own {@code :login_web} process.
     * Called by the virtual process through this provider so the login WebView
     * never shares ART heap space with the game.
     */
    private void launchWebLogin(Bundle extras) {
        if (extras == null || getContext() == null) {
            return;
        }
        try {
            Intent intent = new Intent();
            intent.setComponent(new android.content.ComponentName(getContext().getPackageName(),
                    "top.niunaijun.blackbox.utils.auth.TwitterWebHostActivity"));
            intent.putExtras(extras);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Throwable t) {
            Slog.d(TAG, "launchWebLogin failed: " + t);
        }
    }
}

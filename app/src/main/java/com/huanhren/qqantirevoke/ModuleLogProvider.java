package com.huanhren.qqantirevoke;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

public final class ModuleLogProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        Context context = getContext();
        if (context == null) {
            result.putBoolean(ModulePrefs.LOG_RESULT_OK, false);
            result.putString(ModulePrefs.LOG_RESULT_ERROR, "provider context unavailable");
            return result;
        }
        if (!isTrustedCaller(context)) {
            result.putBoolean(ModulePrefs.LOG_RESULT_OK, false);
            result.putString(ModulePrefs.LOG_RESULT_ERROR, "untrusted caller uid=" + Binder.getCallingUid());
            return result;
        }

        try {
            if (ModulePrefs.LOG_METHOD_APPEND.equals(method)) {
                String line = extras == null ? null : extras.getString(ModulePrefs.LOG_EXTRA_LINE);
                ModuleLogStore.append(context, line);
                result.putBoolean(ModulePrefs.LOG_RESULT_OK, true);
            } else if (ModulePrefs.LOG_METHOD_READ.equals(method)) {
                result.putBoolean(ModulePrefs.LOG_RESULT_OK, true);
                result.putString(ModulePrefs.LOG_RESULT_TEXT, ModuleLogStore.readAll(context));
            } else if (ModulePrefs.LOG_METHOD_CLEAR.equals(method)) {
                ModuleLogStore.clear(context);
                result.putBoolean(ModulePrefs.LOG_RESULT_OK, true);
            } else {
                result.putBoolean(ModulePrefs.LOG_RESULT_OK, false);
                result.putString(ModulePrefs.LOG_RESULT_ERROR, "unknown method: " + method);
            }
        } catch (Throwable throwable) {
            result.putBoolean(ModulePrefs.LOG_RESULT_OK, false);
            result.putString(ModulePrefs.LOG_RESULT_ERROR, throwable.toString());
        }
        return result;
    }

    private static boolean isTrustedCaller(Context context) {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid()) {
            return true;
        }
        PackageManager packageManager = context.getPackageManager();
        String[] packages = packageManager.getPackagesForUid(callingUid);
        if (packages == null) {
            return false;
        }
        for (String packageName : packages) {
            if (ModulePrefs.QQ_PACKAGE.equals(packageName)
                    || ModulePrefs.MODULE_PACKAGE.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("Use ContentProvider.call()");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Use ContentProvider.call()");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Use ContentProvider.call()");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("Use ContentProvider.call()");
    }
}

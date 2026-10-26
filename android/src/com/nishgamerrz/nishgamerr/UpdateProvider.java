package com.nishgamerrz.nishgamerr;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands the downloaded update to the system installer.
 *
 * Modern Android refuses file:// URIs across apps, and this build has no
 * support library, so this is a five-method provider that serves exactly one
 * file: the apk in the app's own cache directory. It is not exported and only
 * the installer gets a temporary read grant.
 */
public final class UpdateProvider extends ContentProvider {

    public static Uri uri() {
        return Uri.parse("content://" + Updater.AUTHORITY + "/" + Updater.APK_NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) throw new FileNotFoundException("no context");
        File apk = new File(new File(context.getCacheDir(), "update"), Updater.APK_NAME);
        if (!apk.isFile()) throw new FileNotFoundException("no downloaded update");
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

package tv.simple.recorder;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Файл, в который пишется видео. Умеет открывать его в публичных папках
 * (Download, Movies, DCIM), в папке, выбранной через системный выбор, и
 * в папке приложения как запасной вариант.
 */
final class OutputTarget {
    private static final String TAG = "OutputTarget";

    private static final int KIND_MEDIASTORE = 1;
    private static final int KIND_DOCUMENT = 2;
    private static final int KIND_FILE = 3;

    private final Context context;
    private final int kind;
    private final Uri uri;
    private final File file;

    final ParcelFileDescriptor pfd;
    /** Понятный путь для показа пользователю. */
    final String displayPath;
    /** true, если выбранная папка недоступна и файл попал в папку приложения. */
    final boolean usedFallback;

    private OutputTarget(Context context, int kind, Uri uri, File file,
                         ParcelFileDescriptor pfd, String displayPath, boolean usedFallback) {
        this.context = context.getApplicationContext();
        this.kind = kind;
        this.uri = uri;
        this.file = file;
        this.pfd = pfd;
        this.displayPath = displayPath;
        this.usedFallback = usedFallback;
    }

    private static String newFileName() {
        return "TV_Recorder_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".mp4";
    }

    /** Файл в папке приложения: запасной вариант, если выбранное место не принимает запись. */
    static OutputTarget createFallback(Context context) throws IOException {
        return createInAppFolder(context, newFileName());
    }

    static OutputTarget create(Context context) throws IOException {
        String name = newFileName();
        String location = Prefs.getLocation(context);
        try {
            if (Prefs.LOC_CUSTOM.equals(location)) {
                return createInTree(context, name);
            }
            return createInPublicDir(context, name, location);
        } catch (Exception e) {
            Log.w(TAG, "Selected location is unavailable, using app folder", e);
            return createInAppFolder(context, name);
        }
    }

    private static OutputTarget createInPublicDir(Context context, String name, String dir)
            throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, dir);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = Prefs.LOC_DOWNLOAD.equals(dir)
                    ? MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            Uri created = resolver.insert(collection, values);
            if (created == null) {
                throw new IOException("MediaStore insert returned null");
            }
            try {
                ParcelFileDescriptor pfd = resolver.openFileDescriptor(created, "rw");
                if (pfd == null) {
                    throw new IOException("Cannot open " + created);
                }
                return new OutputTarget(context, KIND_MEDIASTORE, created, null, pfd,
                        dir + "/" + name, false);
            } catch (IOException | RuntimeException e) {
                try {
                    resolver.delete(created, null, null);
                } catch (Exception ignored) {
                    // не страшно
                }
                throw e;
            }
        }

        // Android 8–9: пишем прямо в общую папку, нужно разрешение WRITE_EXTERNAL_STORAGE.
        File base = Environment.getExternalStoragePublicDirectory(dir);
        if (!base.exists() && !base.mkdirs()) {
            throw new IOException("Cannot create " + base);
        }
        File target = new File(base, name);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(target,
                ParcelFileDescriptor.MODE_READ_WRITE
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE);
        return new OutputTarget(context, KIND_FILE, null, target, pfd, dir + "/" + name, false);
    }

    private static OutputTarget createInTree(Context context, String name) throws IOException {
        String saved = Prefs.getTreeUri(context);
        if (saved == null) {
            throw new IOException("No folder selected");
        }
        Uri tree = Uri.parse(saved);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        ContentResolver resolver = context.getContentResolver();
        Uri doc = DocumentsContract.createDocument(resolver, parent, "video/mp4", name);
        if (doc == null) {
            throw new IOException("Cannot create a document in " + tree);
        }
        try {
            ParcelFileDescriptor pfd = resolver.openFileDescriptor(doc, "rw");
            if (pfd == null) {
                throw new IOException("Cannot open " + doc);
            }
            String label = Prefs.getTreeLabel(context);
            return new OutputTarget(context, KIND_DOCUMENT, doc, null, pfd,
                    (label.isEmpty() ? "" : label + "/") + name, false);
        } catch (IOException | RuntimeException e) {
            try {
                DocumentsContract.deleteDocument(resolver, doc);
            } catch (Exception ignored) {
                // не страшно
            }
            throw e;
        }
    }

    private static OutputTarget createInAppFolder(Context context, String name)
            throws IOException {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        File target = new File(dir, name);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(target,
                ParcelFileDescriptor.MODE_READ_WRITE
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE);
        return new OutputTarget(context, KIND_FILE, null, target, pfd,
                target.getAbsolutePath(), true);
    }

    /** Закрывает файл. При success=false удаляет его, иначе делает видимым для других приложений. */
    void finish(boolean success) {
        try {
            pfd.close();
        } catch (IOException e) {
            Log.w(TAG, "close failed", e);
        }
        try {
            if (!success) {
                discard();
                return;
            }
            if (kind == KIND_MEDIASTORE && Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            } else if (kind == KIND_FILE) {
                MediaScannerConnection.scanFile(context,
                        new String[]{file.getAbsolutePath()}, new String[]{"video/mp4"}, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "finish failed", e);
        }
    }

    private void discard() throws IOException {
        ContentResolver resolver = context.getContentResolver();
        if (kind == KIND_MEDIASTORE) {
            resolver.delete(uri, null, null);
        } else if (kind == KIND_DOCUMENT) {
            DocumentsContract.deleteDocument(resolver, uri);
        } else if (file != null && !file.delete()) {
            Log.w(TAG, "Cannot delete " + file);
        }
    }
}

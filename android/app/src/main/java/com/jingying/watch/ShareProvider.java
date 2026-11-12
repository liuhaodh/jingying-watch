package com.jingying.watch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * 分享作品 tar 用的只读 ContentProvider。
 *
 * <p>项目不依赖 androidx，没有 FileProvider 可用，所以这里自己实现一个最小版本：
 * 只暴露 {@code getCacheDir()/share/} 下的 .tar 文件，不导出、不接受写入，
 * 接收方只能靠 ACTION_SEND 的临时 URI 授权读取，外部 App 无法枚举目录。
 */
public final class ShareProvider extends ContentProvider {

    public static final String AUTHORITY = "com.jingying.watch.share";
    public static final String MIME = "application/x-tar";

    public static Uri uriFor(String fileName) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(fileName)
                .build();
    }

    public static File dir(Context context) {
        File root = new File(context.getCacheDir(), "share");
        if (!root.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        }
        return root;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode == null || !mode.startsWith("r")) {
            throw new FileNotFoundException("分享文件只支持只读");
        }
        return ParcelFileDescriptor.open(resolve(uri),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            File file = resolve(uri);
            cursor.addRow(new Object[]{file.getName(), file.length()});
        } catch (FileNotFoundException ignored) {
            // 非法 URI：给空游标，别让接收方因为 null 崩掉
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return MIME;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("分享目录只读");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("分享目录只读");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException("分享目录只读");
    }

    /** 只接受单个 .tar 文件名，并确认解析后的路径没有越出 share 目录。 */
    private File resolve(Uri uri) throws FileNotFoundException {
        if (uri == null) {
            throw new FileNotFoundException("空的分享地址");
        }
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 1) {
            throw new FileNotFoundException("非法分享路径");
        }
        String name = parts.get(0);
        if (!name.toLowerCase(java.util.Locale.US).endsWith(".tar")
                || name.contains("/") || name.contains("\\")
                || name.contains("..") || name.startsWith(".")) {
            throw new FileNotFoundException("非法分享文件名");
        }
        File root = dir(getContext());
        File file = new File(root, name);
        try {
            String expected = root.getCanonicalPath() + File.separator;
            if (!file.getCanonicalPath().startsWith(expected) || !file.isFile()) {
                throw new FileNotFoundException("分享文件不存在");
            }
        } catch (IOException exc) {
            throw new FileNotFoundException("分享路径不可用");
        }
        return file;
    }
}

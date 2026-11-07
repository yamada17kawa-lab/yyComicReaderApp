package com.comic.reader;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class StorageHelper {
    public static final String ROOT_PATH = Environment.getExternalStorageDirectory().getPath();
    private static final NaturalOrderComparator comparator = new NaturalOrderComparator();

    // 判断是否是 Android/data 或 Android/obb 等受保护路径
    public static boolean isProtectedPath(String path) {
        if (path == null) return false;
        return path.equals(ROOT_PATH + "/Android/data")
                || path.startsWith(ROOT_PATH + "/Android/data/")
                || path.equals(ROOT_PATH + "/Android/obb")
                || path.startsWith(ROOT_PATH + "/Android/obb/")
                || path.contains("/Android/data")
                || path.contains("/Android/obb");
    }

    // 将普通文件路径转换为底层 SAF Tree / Document Uri
    public static Uri pathToSafUri(String path) {
        String halfPath = path.replace(ROOT_PATH + "/", "").replace(ROOT_PATH, "");
        if (halfPath.startsWith("/")) halfPath = halfPath.substring(1);
        String[] segments = halfPath.split("/");

        Uri.Builder uriBuilder = new Uri.Builder()
                .scheme("content")
                .authority("com.android.externalstorage.documents")
                .appendPath("tree");

        String treeDocId;
        String docId;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String sub = (segments.length > 1 && !segments[1].isEmpty()) ? segments[1] : "data";
            treeDocId = "primary:A\u200Bndroid/" + sub;
            docId = "primary:A\u200Bndroid/" + halfPath.replace("Android/", "");
        } else {
            String sub = (segments.length > 1 && !segments[1].isEmpty()) ? segments[1] : "data";
            treeDocId = "primary:Android/" + sub;
            docId = "primary:" + halfPath;
        }

        uriBuilder.appendPath(treeDocId);
        uriBuilder.appendPath("document");
        uriBuilder.appendPath(docId);

        return uriBuilder.build();
    }

    // 检查是否已经具有对该路径或其父级 data 目录的访问授权 (SAF 或 Shizuku)
    public static boolean hasAccessPermission(Context context, String path) {
        if (!isProtectedPath(path)) return true;

        // 1. 如果 Shizuku 已授权，直接拥有最高穿透权限
        if (ShizukuBridge.hasShizukuPermission()) return true;

        // 2. 检查 SAF 授权
        List<android.content.UriPermission> permissions = context.getContentResolver().getPersistedUriPermissions();
        if (permissions == null || permissions.isEmpty()) return false;

        Uri targetUri = pathToSafUri(path);
        String targetPath = targetUri.getPath();
        if (targetPath == null) return false;
        String normTarget = targetPath.replace("\u200B", "");

        for (android.content.UriPermission p : permissions) {
            if (p.isReadPermission()) {
                String pPath = p.getUri().getPath();
                if (pPath != null) {
                    String normP = pPath.replace("\u200B", "");
                    if ((normTarget + "/").contains(normP + "/") || (normP + "/").contains(normTarget + "/")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // 构造系统 DocumentsUI 的 Intent
    public static Intent createSafIntent(Context context, String path) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Uri treeUri = pathToSafUri(path);
            DocumentFile df = DocumentFile.fromTreeUri(context, treeUri);
            if (df != null) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, df.getUri());
            } else {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri);
            }
        }
        return intent;
    }

    // 统一文件与文件夹列表获取（普通目录走 File，Shizuku 走 ADB 桥，旧版走 SAF）
    public static List<FileItem> listFiles(Context context, String path) {
        List<FileItem> list = new ArrayList<>();

        if (isProtectedPath(path)) {
            // 优先 1：如果 Shizuku 已激活授权，通过 Shizuku 穿透读取
            if (ShizukuBridge.hasShizukuPermission()) {
                List<String> dirs = ShizukuBridge.listDirectories(path);
                for (String d : dirs) {
                    String childPath = path.endsWith("/") ? path + d : path + "/" + d;
                    list.add(new FileItem(d, childPath, null, true));
                }
                if (!list.isEmpty()) {
                    list.sort((a, b) -> comparator.compare(a.name, b.name));
                    return list;
                }
            }

            // 优先 2：尝试 SAF DocumentFile 读取
            Uri pathUri = pathToSafUri(path);
            DocumentFile rootDoc = DocumentFile.fromTreeUri(context, pathUri);
            if (rootDoc != null) {
                DocumentFile[] files = rootDoc.listFiles();
                if (files != null && files.length > 0) {
                    for (DocumentFile f : files) {
                        String name = f.getName();
                        if (name == null) continue;
                        String childPath = path.endsWith("/") ? path + name : path + "/" + name;
                        list.add(new FileItem(name, childPath, f.getUri(), f.isDirectory()));
                    }
                }
            }
        } else {
            File dir = new File(path);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    list.add(new FileItem(f.getName(), f.getAbsolutePath(), null, f.isDirectory()));
                }
            }
        }

        list.sort((a, b) -> comparator.compare(a.name, b.name));
        return list;
    }

    public static class FileItem {
        public String name;
        public String path;
        public Uri uri;
        public boolean isDirectory;

        public FileItem(String name, String path, Uri uri, boolean isDirectory) {
            this.name = name;
            this.path = path;
            this.uri = uri;
            this.isDirectory = isDirectory;
        }
    }
}

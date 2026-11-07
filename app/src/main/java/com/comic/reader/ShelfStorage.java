package com.comic.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ShelfStorage {
    private static final String CACHE_FILE = "shelf_cache.json";
    private static final java.util.concurrent.ExecutorService SAVE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    public static void saveShelf(Context context, List<ComicItem> list, String rootPath) {
        if (context == null || list == null) return;
        final Context appCtx = context.getApplicationContext();
        final List<ComicItem> snapshot = new ArrayList<>(list);
        final String safeRoot = rootPath != null ? rootPath : "";
        SAVE_EXECUTOR.execute(() -> saveShelfSync(appCtx, snapshot, safeRoot));
    }

    public static void saveShelfSync(Context context, List<ComicItem> list, String rootPath) {
        if (context == null || list == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (ComicItem item : list) {
                JSONObject obj = new JSONObject();
                obj.put("id", item.getId());
                obj.put("title", item.getTitle());
                obj.put("folderPath", item.getFolderPath());
                obj.put("coverPath", item.getCoverPath());
                obj.put("customCoverPath", item.getCustomCoverPath());
                obj.put("readCount", item.getReadCount());
                obj.put("lastReadTime", item.getLastReadTime());
                obj.put("importTime", item.getImportTime());

                JSONArray pagesArr = new JSONArray();
                for (String p : item.getPagePaths()) {
                    pagesArr.put(p);
                }
                obj.put("pagePaths", pagesArr);
                arr.put(obj);
            }

            JSONObject rootObj = new JSONObject();
            rootObj.put("rootPath", rootPath != null ? rootPath : "");
            rootObj.put("comics", arr);

            File file = new File(context.getFilesDir(), CACHE_FILE);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(rootObj.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static List<ComicItem> loadShelf(Context context) {
        List<ComicItem> list = new ArrayList<>();
        if (context == null) return list;
        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (!file.exists()) return list;

        SharedPreferences sp = context.getSharedPreferences("comic_history", Context.MODE_PRIVATE);

        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            JSONObject rootObj = new JSONObject(sb.toString());
            JSONArray arr = rootObj.optJSONArray("comics");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String id = obj.optString("id");
                    String title = obj.optString("title");
                    String folderPath = obj.optString("folderPath");
                    String coverPath = obj.optString("coverPath");
                    String customCoverPath = obj.optString("customCoverPath", "");
                    if ((customCoverPath == null || customCoverPath.isEmpty()) && sp != null) {
                        customCoverPath = sp.getString("custom_cover_" + id, sp.getString("custom_cover_" + title, ""));
                    }
                    if (customCoverPath != null && !customCoverPath.isEmpty() && isFileOrUriValid(context, customCoverPath)) {
                        coverPath = customCoverPath;
                    } else {
                        customCoverPath = "";
                    }

                    int readCount = obj.optInt("readCount", 0);
                    if (readCount <= 0 && sp != null) {
                        readCount = sp.getInt("read_count_" + id, sp.getInt("read_count_" + title, 0));
                    }

                    long lastReadTime = obj.optLong("lastReadTime", 0);
                    if (lastReadTime <= 0 && sp != null) {
                        lastReadTime = sp.getLong("last_read_time_" + id, sp.getLong("last_read_time_" + title, 0));
                    }

                    long importTime = obj.optLong("importTime", 0);
                    if (importTime <= 0 && sp != null) {
                        importTime = sp.getLong("import_time_" + id, sp.getLong("import_time_" + title, 0));
                    }

                    List<String> pages = new ArrayList<>();
                    JSONArray pagesArr = obj.optJSONArray("pagePaths");
                    if (pagesArr != null) {
                        for (int j = 0; j < pagesArr.length(); j++) {
                            pages.add(pagesArr.getString(j));
                        }
                    }

                    ComicItem loadedItem = new ComicItem(id, title, folderPath, coverPath, pages, readCount, lastReadTime, importTime);
                    loadedItem.setCustomCoverPath(customCoverPath);
                    list.add(loadedItem);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public static String getSavedRootPath(Context context) {
        if (context == null) return "";
        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (!file.exists()) return "";
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONObject rootObj = new JSONObject(sb.toString());
            return rootObj.optString("rootPath", "");
        } catch (Exception e) {
            return "";
        }
    }

    public static void clearComicHistory(Context context, ComicItem item) {
        if (context == null || item == null) return;
        SharedPreferences sp = context.getSharedPreferences("comic_history", Context.MODE_PRIVATE);
        if (sp == null) return;
        String title = item.getTitle() != null ? item.getTitle() : "";
        String id = item.getId() != null ? item.getId() : "";
        cleanupCustomCoverFiles(context, id);
        sp.edit()
                .remove("read_count_" + id)
                .remove("read_count_" + title)
                .remove("last_read_time_" + id)
                .remove("last_read_time_" + title)
                .remove("import_time_" + id)
                .remove("import_time_" + title)
                .remove("progress_" + title)
                .remove("progress_" + id)
                .remove("custom_cover_" + id)
                .remove("custom_cover_" + title)
                .apply();
    }

    public static void clearComicsHistory(Context context, Collection<ComicItem> items) {
        if (context == null || items == null || items.isEmpty()) return;
        SharedPreferences sp = context.getSharedPreferences("comic_history", Context.MODE_PRIVATE);
        if (sp == null) return;
        SharedPreferences.Editor editor = sp.edit();
        for (ComicItem item : items) {
            if (item == null) continue;
            String title = item.getTitle() != null ? item.getTitle() : "";
            String id = item.getId() != null ? item.getId() : "";
            cleanupCustomCoverFiles(context, id);
            editor.remove("read_count_" + id)
                    .remove("read_count_" + title)
                    .remove("last_read_time_" + id)
                    .remove("last_read_time_" + title)
                    .remove("import_time_" + id)
                    .remove("import_time_" + title)
                    .remove("progress_" + title)
                    .remove("progress_" + id)
                    .remove("custom_cover_" + id)
                    .remove("custom_cover_" + title);
        }
        editor.apply();
    }

    public static boolean isFileOrUriValid(Context context, String pathOrUri) {
        if (pathOrUri == null || pathOrUri.trim().isEmpty()) return false;
        try {
            if (pathOrUri.startsWith("content://")) {
                if (context == null) return false;
                DocumentFile df = DocumentFile.fromSingleUri(context, Uri.parse(pathOrUri));
                return df != null && df.exists();
            } else {
                File f = new File(pathOrUri);
                return f.exists() && f.isFile();
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static String getCustomCover(Context context, String id, String title) {
        if (context == null) return "";
        SharedPreferences sp = context.getSharedPreferences("comic_history", Context.MODE_PRIVATE);
        if (sp == null) return "";
        String custom = sp.getString("custom_cover_" + id, sp.getString("custom_cover_" + title, ""));
        if (custom != null && !custom.isEmpty() && isFileOrUriValid(context, custom)) {
            return custom;
        }
        return "";
    }

    public static void saveCustomCover(Context context, ComicItem item, String customPath) {
        if (context == null || item == null) return;
        String safeCustom = customPath != null ? customPath.trim() : "";
        item.setCustomCoverPath(safeCustom);
        if (!safeCustom.isEmpty()) {
            item.setCoverPath(safeCustom);
        } else if (item.getPagePaths() != null && !item.getPagePaths().isEmpty()) {
            item.setCoverPath(item.getPagePaths().get(0));
        }
        SharedPreferences sp = context.getSharedPreferences("comic_history", Context.MODE_PRIVATE);
        if (sp != null) {
            String id = item.getId();
            String title = item.getTitle();
            SharedPreferences.Editor editor = sp.edit();
            if (safeCustom.isEmpty()) {
                editor.remove("custom_cover_" + id).remove("custom_cover_" + title);
            } else {
                editor.putString("custom_cover_" + id, safeCustom)
                      .putString("custom_cover_" + title, safeCustom);
            }
            editor.apply();
        }
    }

    public static void cleanupCustomCoverFiles(Context context, String comicId) {
        if (context == null || comicId == null || comicId.isEmpty()) return;
        try {
            String safePrefix = "cover_" + comicId.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "_") + "_";
            File dir = new File(context.getFilesDir(), "custom_covers");
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(safePrefix)) {
                            f.delete();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public static String copyExternalUriToCustomCover(Context context, String comicId, Uri sourceUri) {
        if (context == null || sourceUri == null) return null;
        try {
            File dir = new File(context.getFilesDir(), "custom_covers");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String safeId = (comicId != null ? comicId : "comic").replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "_");
            cleanupCustomCoverFiles(context, comicId);

            File destFile = new File(dir, "cover_" + safeId + "_" + System.currentTimeMillis() + ".jpg");
            try (InputStream is = context.getContentResolver().openInputStream(sourceUri);
                 FileOutputStream fos = new FileOutputStream(destFile)) {
                if (is == null) return null;
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                fos.flush();
            }
            if (destFile.exists() && destFile.length() > 0) {
                return destFile.getAbsolutePath();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static boolean deleteComicPhysicalFiles(Context context, ComicItem item) {
        if (context == null || item == null) return false;
        boolean anyDeleted = false;
        try {
            cleanupCustomCoverFiles(context, item.getId());

            List<String> pages = item.getPagePaths();
            if (pages != null) {
                for (String p : pages) {
                    if (p == null || p.isEmpty()) continue;
                    try {
                        if (p.startsWith("content://")) {
                            DocumentFile df = DocumentFile.fromSingleUri(context, Uri.parse(p));
                            if (df != null && df.exists() && df.delete()) {
                                anyDeleted = true;
                            }
                        } else {
                            File f = new File(p);
                            if (f.exists() && f.isFile() && f.delete()) {
                                anyDeleted = true;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            String folderPath = item.getFolderPath();
            if (folderPath != null && !folderPath.trim().isEmpty()) {
                if (folderPath.startsWith("content://")) {
                    try {
                        DocumentFile dirDf = DocumentFile.fromTreeUri(context, Uri.parse(folderPath));
                        if (dirDf != null && dirDf.exists() && dirDf.isDirectory()) {
                            boolean hasSubDir = false;
                            DocumentFile[] children = dirDf.listFiles();
                            if (children != null) {
                                for (DocumentFile child : children) {
                                    if (child.isDirectory()) {
                                        hasSubDir = true;
                                        break;
                                    }
                                }
                            }
                            if (!hasSubDir && dirDf.delete()) {
                                anyDeleted = true;
                            }
                        }
                    } catch (Exception ignored) {}
                } else {
                    File dir = new File(folderPath);
                    if (dir.exists() && dir.isDirectory()) {
                        String absDir = dir.getAbsolutePath();
                        String extRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
                        String transDir = getTransferSaveDir(context);
                        String defTransDir = getDefaultTransferSaveDir(context);

                        boolean isProtected = absDir.equalsIgnoreCase(extRoot)
                                || absDir.equalsIgnoreCase(transDir)
                                || absDir.equalsIgnoreCase(defTransDir);

                        if (!isProtected) {
                            File[] subDirs = dir.listFiles(File::isDirectory);
                            boolean hasSubDirs = subDirs != null && subDirs.length > 0;
                            if (!hasSubDirs) {
                                File[] remainingFiles = dir.listFiles(File::isFile);
                                if (remainingFiles != null) {
                                    for (File rf : remainingFiles) {
                                        try { rf.delete(); } catch (Exception ignored) {}
                                    }
                                }
                                if (dir.delete()) {
                                    anyDeleted = true;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return anyDeleted;
    }

    public static String renameComic(Context context, ComicItem item, String rawNewTitle, List<ComicItem> allComics) {
        if (context == null || item == null) return "漫画对象无效";
        String newTitle = rawNewTitle != null ? rawNewTitle.trim() : "";
        if (newTitle.isEmpty()) {
            return "漫画名称不能为空";
        }
        if (newTitle.matches(".*[\\\\/:*?\"<>|].*")) {
            return "名称包含不支持的特殊字符 (\\ / : * ? \" < > |)";
        }

        String oldId = item.getId() != null ? item.getId() : "";
        String oldTitle = item.getTitle() != null ? item.getTitle() : "";
        if (oldTitle.equals(newTitle)) {
            return null;
        }

        if (allComics != null) {
            for (ComicItem c : allComics) {
                if (c != item && c.getTitle() != null && c.getTitle().trim().equalsIgnoreCase(newTitle)) {
                    return "书架上已存在名为《" + newTitle + "》的漫画";
                }
            }
        }

        String oldFolderPath = item.getFolderPath();
        if (oldFolderPath != null && !oldFolderPath.trim().isEmpty() && !oldFolderPath.startsWith("content://")) {
            try {
                File oldDir = new File(oldFolderPath);
                if (oldDir.exists() && oldDir.isDirectory() && oldDir.getParentFile() != null) {
                    String absOld = oldDir.getAbsolutePath();
                    String extRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
                    String transDir = getTransferSaveDir(context);
                    String defTransDir = getDefaultTransferSaveDir(context);
                    boolean isProtected = absOld.equalsIgnoreCase(extRoot)
                            || absOld.equalsIgnoreCase(transDir)
                            || absOld.equalsIgnoreCase(defTransDir);

                    if (!isProtected) {
                        File newDir = new File(oldDir.getParentFile(), newTitle);
                        boolean caseOnly = absOld.equalsIgnoreCase(newDir.getAbsolutePath());
                        if (!caseOnly && newDir.exists()) {
                            return "存储目录下已存在同名文件夹《" + newTitle + "》";
                        }

                        boolean renamed = false;
                        if (caseOnly && !absOld.equals(newDir.getAbsolutePath())) {
                            File tempDir = new File(oldDir.getParentFile(), newTitle + "_tmp_" + (System.currentTimeMillis() % 10000));
                            if (oldDir.renameTo(tempDir)) {
                                renamed = tempDir.renameTo(newDir);
                            }
                        } else if (!absOld.equals(newDir.getAbsolutePath())) {
                            renamed = oldDir.renameTo(newDir);
                        } else {
                            renamed = true;
                        }

                        if (renamed && newDir.exists()) {
                            String oldPrefix = absOld.endsWith(File.separator) ? absOld : (absOld + File.separator);
                            String newPrefix = newDir.getAbsolutePath().endsWith(File.separator)
                                    ? newDir.getAbsolutePath()
                                    : (newDir.getAbsolutePath() + File.separator);

                            item.setFolderPath(newDir.getAbsolutePath());

                            List<String> updatedPages = new ArrayList<>();
                            if (item.getPagePaths() != null) {
                                for (String p : item.getPagePaths()) {
                                    if (p != null && p.startsWith(oldPrefix)) {
                                        updatedPages.add(newPrefix + p.substring(oldPrefix.length()));
                                    } else {
                                        updatedPages.add(p);
                                    }
                                }
                                item.setPagePaths(updatedPages);
                            }

                            String customCover = item.getCustomCoverPath();
                            if (customCover != null && customCover.startsWith(oldPrefix)) {
                                customCover = newPrefix + customCover.substring(oldPrefix.length());
                                item.setCustomCoverPath(customCover);
                            }

                            String cover = item.getCoverPath();
                            if (cover != null && cover.startsWith(oldPrefix)) {
                                item.setCoverPath(newPrefix + cover.substring(oldPrefix.length()));
                            } else if (customCover != null && !customCover.isEmpty() && isFileOrUriValid(context, customCover)) {
                                item.setCoverPath(customCover);
                            } else if (!updatedPages.isEmpty()) {
                                item.setCoverPath(updatedPages.get(0));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 迁移 SharedPreferences 中的阅读进度、阅读次数与自定义封面记录
        SharedPreferences sp = context.getSharedPreferences("comic_history", Context.MODE_PRIVATE);
        if (sp != null) {
            int progress = sp.getInt("progress_" + oldTitle, sp.getInt("progress_" + oldId, 0));
            int readCount = Math.max(item.getReadCount(), sp.getInt("read_count_" + oldId, sp.getInt("read_count_" + oldTitle, 0)));
            long lastRead = Math.max(item.getLastReadTime(), sp.getLong("last_read_time_" + oldId, sp.getLong("last_read_time_" + oldTitle, 0)));
            long importTime = Math.max(item.getImportTime(), sp.getLong("import_time_" + oldId, sp.getLong("import_time_" + oldTitle, 0)));
            String customCover = item.getCustomCoverPath();

            SharedPreferences.Editor editor = sp.edit();
            editor.remove("progress_" + oldTitle).remove("progress_" + oldId)
                  .remove("read_count_" + oldId).remove("read_count_" + oldTitle)
                  .remove("last_read_time_" + oldId).remove("last_read_time_" + oldTitle)
                  .remove("import_time_" + oldId).remove("import_time_" + oldTitle)
                  .remove("custom_cover_" + oldId).remove("custom_cover_" + oldTitle);

            if (progress > 0) {
                editor.putInt("progress_" + newTitle, progress);
            }
            if (readCount > 0) {
                editor.putInt("read_count_" + newTitle, readCount);
            }
            if (lastRead > 0) {
                editor.putLong("last_read_time_" + newTitle, lastRead);
            }
            if (importTime > 0) {
                editor.putLong("import_time_" + newTitle, importTime);
            }
            if (customCover != null && !customCover.isEmpty()) {
                editor.putString("custom_cover_" + newTitle, customCover);
            }
            editor.apply();
        }

        item.setId(newTitle);
        item.setTitle(newTitle);
        return null;
    }

    public static void clearShelf(Context context) {
        if (context == null) return;
        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (file.exists()) {
            file.delete();
        }
        SharedPreferences sp = context.getSharedPreferences("comic_history", Context.MODE_PRIVATE);
        if (sp != null) {
            int sortMode = sp.getInt("comic_sort_mode", 0);
            String transferDir = sp.getString("transfer_save_path", "");
            sp.edit().clear()
                    .putInt("comic_sort_mode", sortMode)
                    .putString("transfer_save_path", transferDir)
                    .apply();
        }
    }

    public static String getDefaultTransferSaveDir(Context context) {
        try {
            File pubDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dir = new File(pubDownload, "YY漫画互传");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            if (dir.exists() && dir.canWrite()) {
                return dir.getAbsolutePath();
            }
        } catch (Exception ignored) {}

        if (context != null) {
            File ext = context.getExternalFilesDir(null);
            if (ext != null) {
                File dir = new File(ext, "YY漫画互传");
                dir.mkdirs();
                return dir.getAbsolutePath();
            }
            File internal = new File(context.getFilesDir(), "YY漫画互传");
            internal.mkdirs();
            return internal.getAbsolutePath();
        }
        return "/storage/emulated/0/Download/YY漫画互传";
    }

    public static String getTransferSaveDir(Context context) {
        if (context == null) return getDefaultTransferSaveDir(null);
        SharedPreferences sp = context.getSharedPreferences("comic_history", Context.MODE_PRIVATE);
        String custom = sp != null ? sp.getString("transfer_save_path", "") : "";
        if (custom != null && !custom.trim().isEmpty()) {
            try {
                File dir = new File(custom.trim());
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                if (dir.exists() && dir.isDirectory()) {
                    return dir.getAbsolutePath();
                }
            } catch (Exception ignored) {}
        }
        return getDefaultTransferSaveDir(context);
    }

    public static void setTransferSaveDir(Context context, String path) {
        if (context == null) return;
        SharedPreferences sp = context.getSharedPreferences("comic_history", Context.MODE_PRIVATE);
        if (sp != null) {
            sp.edit().putString("transfer_save_path", path != null ? path.trim() : "").apply();
        }
    }
}

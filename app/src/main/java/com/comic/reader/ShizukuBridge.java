package com.comic.reader;

import android.content.Context;
import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

public class ShizukuBridge {
    private static final int SHIZUKU_CODE = 2002;
    private static final NaturalOrderComparator comparator = new NaturalOrderComparator();

    public static boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasShizukuPermission() {
        if (!isShizukuAvailable()) return false;
        try {
            if (Shizuku.isPreV11()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestShizukuPermission(Shizuku.OnRequestPermissionResultListener listener) {
        if (!isShizukuAvailable()) return;
        try {
            Shizuku.addRequestPermissionResultListener(listener);
            Shizuku.requestPermission(SHIZUKU_CODE);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static Process createProcess(String[] cmd) {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            return (Process) m.invoke(null, cmd, null, null);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    // 通过 Shizuku 提权执行命令遍历目录下的子文件夹
    public static List<String> listDirectories(String path) {
        List<String> list = new ArrayList<>();
        if (!hasShizukuPermission()) return list;

        try {
            Process process = createProcess(new String[]{"ls", "-1p", path});
            if (process == null) return list;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.endsWith("/")) {
                        list.add(line.substring(0, line.length() - 1));
                    }
                }
            }
            process.waitFor();
            list.sort(comparator);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return list;
    }

    // 遍历图片文件
    public static List<String> listImages(String folderPath) {
        List<String> list = new ArrayList<>();
        if (!hasShizukuPermission()) return list;

        try {
            Process process = createProcess(new String[]{"ls", "-1", folderPath});
            if (process == null) return list;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (isImage(line)) {
                        String fullPath = folderPath.endsWith("/") ? folderPath + line : folderPath + "/" + line;
                        list.add(fullPath);
                    }
                }
            }
            process.waitFor();
            list.sort(comparator);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return list;
    }

    // 通过 Shizuku cat 命令读取图片文件字节流（穿透任何 Android/data 封锁）
    public static InputStream openInputStream(String filePath) {
        if (!hasShizukuPermission()) return null;
        try {
            Process process = createProcess(new String[]{"cat", filePath});
            return process != null ? process.getInputStream() : null;
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    private static boolean isImage(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".bmp") || lower.endsWith(".gif") || lower.endsWith(".avif");
    }
}

package com.comic.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ImageLoader {
    private static volatile ImageLoader instance;
    private final LruCache<String, Bitmap> memoryCache;
    private final ThreadPoolExecutor executorService;
    private final Handler mainHandler;

    private ImageLoader() {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = Math.max(8 * 1024, maxMemory / 5); // 合理控制在 1/5 最大堆内存，避免大图挤爆内存
        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        // 采用双端队列 (LIFO 优先调度最新入队的可见项，超出队列上限自动丢弃已滑出屏幕的旧任务)
        LinkedBlockingDeque<Runnable> workQueue = new LinkedBlockingDeque<Runnable>(48) {
            @Override
            public boolean offer(Runnable r) {
                // 新任务放到队首优先执行，保证当前停留在屏幕上的漫画封面/内页秒出
                if ( offerFirst(r) ) {
                    return true;
                }
                // 队列满时移除队尾最老的已滚出屏幕的任务
                pollLast();
                return offerFirst(r);
            }
        };

        executorService = new ThreadPoolExecutor(
                3,
                3,
                30L,
                TimeUnit.SECONDS,
                workQueue,
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public static ImageLoader getInstance() {
        if (instance == null) {
            synchronized (ImageLoader.class) {
                if (instance == null) {
                    instance = new ImageLoader();
                }
            }
        }
        return instance;
    }

    public void displayImage(Context context, String pathOrUri, ImageView imageView, int reqWidth, int reqHeight) {
        if (pathOrUri == null || pathOrUri.isEmpty()) {
            imageView.setTag(null);
            imageView.setImageDrawable(null);
            return;
        }

        final int bucketWidth = reqWidth > 600 ? 1080 : 360;
        final String cacheKey = pathOrUri + "#" + bucketWidth;
        imageView.setTag(cacheKey);

        Bitmap cached = memoryCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }

        // 默认背景占位
        imageView.setImageDrawable(null);

        final Context appCtx = context.getApplicationContext();
        executorService.execute(() -> {
            // 执行前二次校验：若该 ImageView 已滑出屏幕并复用了其他图片，直接跳过磁盘解码（0 I/O 损耗）
            Object currentTag = imageView.getTag();
            if (!cacheKey.equals(currentTag)) {
                return;
            }

            Bitmap bitmap = decodeSampledBitmap(appCtx, pathOrUri, reqWidth, reqHeight);
            if (bitmap != null) {
                memoryCache.put(cacheKey, bitmap);
                mainHandler.post(() -> {
                    if (cacheKey.equals(imageView.getTag())) {
                        imageView.setImageBitmap(bitmap);
                    }
                });
            }
        });
    }

    private Bitmap decodeSampledBitmap(Context context, String pathOrUri, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;

            if (pathOrUri.startsWith("http://") || pathOrUri.startsWith("https://")) {
                java.net.HttpURLConnection conn = null;
                try {
                    java.net.URL url = new java.net.URL(pathOrUri);
                    conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(15000);
                    conn.setDoInput(true);
                    if (conn.getResponseCode() != 200) {
                        return null;
                    }
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    try (InputStream is = new java.io.BufferedInputStream(conn.getInputStream())) {
                        byte[] buf = new byte[16 * 1024];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            bos.write(buf, 0, n);
                        }
                    }
                    byte[] bytes = bos.toByteArray();
                    if (bytes.length == 0) return null;

                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
                    options.inJustDecodeBounds = false;
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Exception ignored) {}
                    }
                }
            } else if (pathOrUri.startsWith("content://")) {
                Uri uri = Uri.parse(pathOrUri);
                try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(is, null, options);
                }
                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                    return BitmapFactory.decodeStream(is, null, options);
                }
            } else {
                File f = new File(pathOrUri);
                if (f.exists() && f.canRead()) {
                    BitmapFactory.decodeFile(pathOrUri, options);
                    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
                    options.inJustDecodeBounds = false;
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    return BitmapFactory.decodeFile(pathOrUri, options);
                } else if (StorageHelper.isProtectedPath(pathOrUri)) {
                    Uri uri = StorageHelper.pathToSafUri(pathOrUri);
                    try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                        BitmapFactory.decodeStream(is, null, options);
                    }
                    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
                    options.inJustDecodeBounds = false;
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                        return BitmapFactory.decodeStream(is, null, options);
                    }
                }
            }
        } catch (OutOfMemoryError oom) {
            memoryCache.evictAll();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }
}

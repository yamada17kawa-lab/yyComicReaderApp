package com.comic.reader;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class FolderPickerDialog extends Dialog {
    public interface OnDirectorySelectedListener {
        void onDirectorySelected(String selectedPath);
    }

    public interface OnRequestSafListener {
        void onRequestSaf(String path);
    }

    private final MainActivity activity;
    private final OnDirectorySelectedListener listener;
    private final OnRequestSafListener safRequestListener;

    private String currentPath;
    private TextView tvCurrentPath;
    private RecyclerView recyclerFolders;
    private FolderAdapter adapter;
    private final List<StorageHelper.FileItem> items = new ArrayList<>();

    public FolderPickerDialog(@NonNull MainActivity activity,
                              OnDirectorySelectedListener listener,
                              OnRequestSafListener safRequestListener) {
        super(activity);
        this.activity = activity;
        this.listener = listener;
        this.safRequestListener = safRequestListener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_folder_picker);

        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }

        tvCurrentPath = findViewById(R.id.tv_current_path);
        recyclerFolders = findViewById(R.id.recycler_folders);

        recyclerFolders.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new FolderAdapter();
        recyclerFolders.setAdapter(adapter);

        findViewById(R.id.btn_up_level).setOnClickListener(v -> navigateUp());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> dismiss());
        findViewById(R.id.btn_select_this).setOnClickListener(v -> {
            if (listener != null && currentPath != null) {
                listener.onDirectorySelected(currentPath);
                dismiss();
            }
        });

        findViewById(R.id.chip_root).setOnClickListener(v -> navigateTo(StorageHelper.ROOT_PATH));
        findViewById(R.id.chip_download).setOnClickListener(v -> navigateTo(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath()));
        findViewById(R.id.chip_pictures).setOnClickListener(v -> navigateTo(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath()));

        // 默认定位到内部存储根目录
        navigateTo(StorageHelper.ROOT_PATH);
    }

    // 核心统一导航方法
    public void navigateTo(String path) {
        if (path == null || path.isEmpty()) return;

        // 如果进入 Android/data 或 Android/obb 等受保护目录
        if (StorageHelper.isProtectedPath(path)) {
            // 检查是否已有底层授权 (Shizuku 或 SAF)
            if (!StorageHelper.hasAccessPermission(getContext(), path)) {
                promptForAuthorization(path);
                return;
            }
        }

        currentPath = path;
        tvCurrentPath.setText(currentPath);

        // 异步加载目录子项
        Executors.newSingleThreadExecutor().execute(() -> {
            List<StorageHelper.FileItem> all = StorageHelper.listFiles(getContext(), path);
            List<StorageHelper.FileItem> dirs = new ArrayList<>();
            for (StorageHelper.FileItem item : all) {
                if (item.isDirectory) {
                    dirs.add(item);
                }
            }

            activity.runOnUiThread(() -> {
                items.clear();
                items.addAll(dirs);
                adapter.notifyDataSetChanged();
                recyclerFolders.scrollToPosition(0);
            });
        });
    }

    // 针对 Android 14 的明确引导
    private void promptForAuthorization(String path) {
        if (ShizukuBridge.isShizukuAvailable()) {
            new AlertDialog.Builder(getContext())
                    .setTitle("访问系统受保护目录")
                    .setMessage("已检测到您设备上已启动 Shizuku 服务。\n\n点击【Shizuku 授权】即可像 MT 管理器一样直接穿透访问 Android/data 目录！")
                    .setPositiveButton("Shizuku 授权", (dialog, which) -> {
                        ShizukuBridge.requestShizukuPermission((requestCode, grantResult) -> {
                            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                activity.runOnUiThread(() -> navigateTo(path));
                            }
                        });
                    })
                    .setNeutralButton("系统授权", (dialog, which) -> {
                        if (safRequestListener != null) safRequestListener.onRequestSaf(path);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            new AlertDialog.Builder(getContext())
                    .setTitle("Android 14 目录限制提示")
                    .setMessage("您的系统为 Android 14，谷歌已在此版本彻底封锁了系统文件选择器对 Android/data 的直接授权（会提示“无法使用此文件夹”）。\n\n【极速解决方案】：\n强烈建议将漫画文件夹从 Android/data 移动到公共目录（如 Download、Pictures 或直接放在内部存储根目录），App 无需任何特殊授权即可秒开！\n\n【保留在 data 目录】：\n需下载启动 Shizuku（无线调试免Root），App 即可像 MT 管理器一样直接穿透。")
                    .setPositiveButton("尝试系统授权", (dialog, which) -> {
                        if (safRequestListener != null) {
                            safRequestListener.onRequestSaf(path);
                        }
                    })
                    .setNegativeButton("我知道了", null)
                    .show();
        }
    }

    private void navigateUp() {
        if (currentPath == null || currentPath.equals(StorageHelper.ROOT_PATH)) {
            Toast.makeText(getContext(), "已到达最上级目录", Toast.LENGTH_SHORT).show();
            return;
        }

        File current = new File(currentPath);
        String parent = current.getParent();
        if (parent != null && parent.startsWith(StorageHelper.ROOT_PATH)) {
            navigateTo(parent);
        } else {
            navigateTo(StorageHelper.ROOT_PATH);
        }
    }

    public void onSafPermissionGranted(String path) {
        if (path != null && !path.isEmpty()) {
            navigateTo(path);
        } else {
            navigateTo(currentPath);
        }
    }

    private class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(getContext()).inflate(R.layout.item_folder_picker, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            StorageHelper.FileItem item = items.get(position);
            holder.tvName.setText(item.name);
            holder.itemView.setOnClickListener(v -> navigateTo(item.path));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName;
            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_folder_name);
            }
        }
    }
}

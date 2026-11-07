package com.comic.reader;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private ComicShelfAdapter adapter;
    private LinearLayout layoutEmpty;
    private ProgressBar progressBar;
    private EditText etSearch;
    private View ivClearSearch;
    private TextView tvShelfStats;

    // 导入悬浮按钮
    private View btnImport;

    // 批量管理组件
    private View btnBatch;
    private View layoutBatchBar;
    private android.widget.CheckBox cbBatchSelectAll;
    private TextView tvBatchCount;
    private View btnBatchDelete;
    private View btnBatchCancel;

    // 实时导入进度组件
    private View layoutImportProgress;
    private TextView tvImportStatus;
    private TextView tvImportPercent;
    private ProgressBar importProgressBar;
    private TextView tvImportDetail;

    private final List<ComicItem> allComics = new ArrayList<>();
    private final List<ComicItem> onlineShelfComics = new ArrayList<>();
    private final List<ComicItem> displayComics = new ArrayList<>();
    private boolean isOnlineShelfMode = false;
    private final NaturalOrderComparator naturalOrderComparator = new NaturalOrderComparator();
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService lanExecutor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FolderPickerDialog activeFolderDialog;
    private String pendingSafPath;
    private SharedPreferences sp;
    private int currentSortMode = 0; // 0: 名称(A-Z), 1: 导入时间 (最新在前), 2: 阅读次数最多, 3: 最近阅读, 4: 页数最多
    private LanTransferManager lanTransferManager;
    private LanTransferManager.PeerInfo currentConnectedPeer;
    private String lastAnnouncedPeerKey = "";
    private long lastAnnouncedPeerTime = 0;

    // 局域网全屏面板实时UI回调钩子
    private View activeLanProgressCard;
    private TextView activeLanProgressTitle;
    private TextView activeLanProgressPct;
    private ProgressBar activeLanProgressBar;
    private TextView activeLanProgressView;
    private Runnable activeLanRefreshCallback;
    private java.util.function.Consumer<LanTransferManager.PeerInfo> activeLanIncomingPeerCallback;
    private ComicItem pendingCoverComic;

    private final ActivityResultLauncher<Intent> customCoverImageLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && pendingCoverComic != null) {
                    Uri imgUri = result.getData().getData();
                    if (imgUri != null) {
                        String savedCoverPath = ShelfStorage.copyExternalUriToCustomCover(this, pendingCoverComic.getId(), imgUri);
                        if (savedCoverPath != null && !savedCoverPath.isEmpty()) {
                            ShelfStorage.saveCustomCover(this, pendingCoverComic, savedCoverPath);
                            ShelfStorage.saveShelf(this, allComics, ShelfStorage.getSavedRootPath(this));
                            adapter.notifyDataSetChanged();
                            Toast.makeText(this, "🖼️ 已成功为《" + pendingCoverComic.getTitle() + "》更换自定义封面", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "读取所选封面图片失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });

    private final ActivityResultLauncher<Intent> safLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri treeUri = result.getData().getData();
                    if (treeUri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    treeUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            );
                        } catch (Exception ignored) {}

                        if (activeFolderDialog != null && activeFolderDialog.isShowing()) {
                            activeFolderDialog.onSafPermissionGranted(pendingSafPath);
                        } else if (pendingSafPath != null) {
                            scanComicFolder(pendingSafPath);
                        }
                    }
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sp = getSharedPreferences("comic_history", Context.MODE_PRIVATE);
        currentSortMode = sp.getInt("comic_sort_mode", 0);

        recyclerView = findViewById(R.id.recycler_shelf);
        layoutEmpty = findViewById(R.id.layout_empty);
        progressBar = findViewById(R.id.progress_bar);
        etSearch = findViewById(R.id.et_search);
        ivClearSearch = findViewById(R.id.iv_clear_search);
        tvShelfStats = findViewById(R.id.tv_shelf_stats);

        layoutImportProgress = findViewById(R.id.layout_import_progress);
        tvImportStatus = findViewById(R.id.tv_import_status);
        tvImportPercent = findViewById(R.id.tv_import_percent);
        importProgressBar = findViewById(R.id.import_progress_bar);
        tvImportDetail = findViewById(R.id.tv_import_detail);

        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new ComicShelfAdapter(
                this,
                displayComics,
                position -> {
                    if (position < 0 || position >= displayComics.size()) return;
                    ComicItem item = displayComics.get(position);
                    if (isOnlineShelfMode) {
                        ComicDataManager.setOnlineComicList(new ArrayList<>(displayComics));
                        Intent intent = new Intent(MainActivity.this, ReaderActivity.class);
                        intent.putExtra("comic_index", position);
                        intent.putExtra("is_online_comic", true);
                        startActivity(intent);
                    } else {
                        int originalIndex = allComics.indexOf(item);
                        ComicDataManager.setComicList(allComics);
                        Intent intent = new Intent(MainActivity.this, ReaderActivity.class);
                        intent.putExtra("comic_index", originalIndex);
                        startActivity(intent);
                    }
                },
                position -> {
                    if (!isOnlineShelfMode && position >= 0 && position < displayComics.size()) {
                        confirmRemoveComic(displayComics.get(position));
                    }
                }
        );
        adapter.setCoverClickListener(position -> {
            if (!isOnlineShelfMode && position >= 0 && position < displayComics.size()) {
                showCustomCoverDialog(displayComics.get(position));
            }
        });
        adapter.setRenameClickListener(position -> {
            if (!isOnlineShelfMode && position >= 0 && position < displayComics.size()) {
                showRenameComicDialog(displayComics.get(position));
            }
        });
        recyclerView.setAdapter(adapter);

        View btnToggleOnlineShelf = findViewById(R.id.btn_toggle_online_shelf);
        if (btnToggleOnlineShelf != null) {
            btnToggleOnlineShelf.setOnClickListener(v -> {
                if (isOnlineShelfMode) {
                    exitOnlineShelfMode();
                } else if (currentConnectedPeer != null) {
                    enterOnlineShelfMode(currentConnectedPeer, null);
                }
            });
        }

        btnImport = findViewById(R.id.btn_import);
        if (btnImport != null) {
            btnImport.setOnClickListener(v -> openFolderChooser());
        }
        findViewById(R.id.btn_refresh).setOnClickListener(v -> refreshLastFolder());
        findViewById(R.id.btn_sort).setOnClickListener(v -> showSortDialog());
        View btnLanTransfer = findViewById(R.id.btn_lan_transfer);
        if (btnLanTransfer != null) {
            btnLanTransfer.setOnClickListener(v -> showLanTransferDialog());
        }
        View btnSettings = findViewById(R.id.btn_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showSettingsDialog());
        }
        layoutEmpty.setOnClickListener(v -> openFolderChooser());

        // 批量管理控件绑定
        btnBatch = findViewById(R.id.btn_batch);
        layoutBatchBar = findViewById(R.id.layout_batch_bar);
        cbBatchSelectAll = findViewById(R.id.cb_batch_select_all);
        tvBatchCount = findViewById(R.id.tv_batch_count);
        btnBatchDelete = findViewById(R.id.btn_batch_delete);
        btnBatchCancel = findViewById(R.id.btn_batch_cancel);

        if (btnBatch != null) {
            btnBatch.setOnClickListener(v -> toggleBatchMode());
        }
        if (btnBatchCancel != null) {
            btnBatchCancel.setOnClickListener(v -> exitBatchMode());
        }
        if (cbBatchSelectAll != null) {
            cbBatchSelectAll.setOnClickListener(v -> {
                adapter.toggleSelectAll(cbBatchSelectAll.isChecked());
            });
        }
        if (btnBatchDelete != null) {
            btnBatchDelete.setOnClickListener(v -> confirmBatchDelete());
        }

        adapter.setBatchSelectionChangeListener((selectedCount, totalCount) -> {
            updateBatchUI(selectedCount, totalCount);
        });

        findViewById(R.id.tv_title).setOnLongClickListener(v -> {
            confirmClearShelf();
            return true;
        });

        // 搜索框实时模糊匹配
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String kw = s.toString();
                ivClearSearch.setVisibility(kw.isEmpty() ? View.GONE : View.VISIBLE);
                filterComics(kw);
                updateShelfStats();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        ivClearSearch.setOnClickListener(v -> etSearch.setText(""));

        // 检查受限模式横幅
        updatePermissionBanner();

        // 自动恢复本地持久化书架缓存
        loadShelfFromStorage();

        // 启动 Wi-Fi 局域网漫画互传服务
        lanTransferManager = new LanTransferManager(
                this,
                () -> {
                    List<ComicItem> active = new ArrayList<>();
                    for (ComicItem c : allComics) {
                        if (!c.isMissing()) active.add(c);
                    }
                    return active;
                },
                this::onLanComicReceived,
                (status, percent, detail) -> {
                    showImportProgress(status, percent, detail);
                    updateActiveLanDialogProgress(status, percent, detail);
                    if (status != null && status.startsWith("⏩")) {
                        hideImportProgress(2000);
                        hideActiveLanDialogProgress(2400);
                    }
                },
                this::onLanPeerConnected,
                this::onLanBatchTransferComplete
        );
        lanTransferManager.start();
    }

    private void updateActiveLanDialogProgress(String status, int percent, String detail) {
        mainHandler.post(() -> {
            int p = Math.min(100, Math.max(0, percent));
            if (activeLanProgressCard != null) {
                activeLanProgressCard.setVisibility(View.VISIBLE);
            }
            if (activeLanProgressTitle != null && status != null) {
                activeLanProgressTitle.setText(status);
            }
            if (activeLanProgressPct != null) {
                activeLanProgressPct.setText(p + "%");
            }
            if (activeLanProgressBar != null) {
                activeLanProgressBar.setProgress(p);
            }
            if (activeLanProgressView != null && detail != null) {
                activeLanProgressView.setText(detail);
            }
        });
    }

    private void hideActiveLanDialogProgress(long delayMs) {
        mainHandler.postDelayed(() -> {
            if (activeLanProgressCard != null) {
                activeLanProgressCard.setVisibility(View.GONE);
            }
        }, delayMs);
    }

    private void updateMainHeaderLanButtonState(LanTransferManager.PeerInfo peer) {
        TextView tvLanLabel = findViewById(R.id.tv_lan_transfer_label);
        android.widget.ImageView ivLanIcon = findViewById(R.id.iv_lan_transfer_icon);
        boolean connected = peer != null && peer.deviceName != null && !peer.deviceName.isEmpty();
        if (tvLanLabel != null) {
            tvLanLabel.setText(connected ? "🟢 已连" : "互传");
            tvLanLabel.setTextColor(android.graphics.Color.parseColor(connected ? "#6EE7B7" : "#F8FAFC"));
        }
        if (ivLanIcon != null) {
            ivLanIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor(connected ? "#34D399" : "#818CF8")));
        }
        updateOnlineShelfBanner();
    }

    private void updateOnlineShelfBanner() {
        View banner = findViewById(R.id.layout_online_shelf_banner);
        TextView tvBannerTitle = findViewById(R.id.tv_online_shelf_banner_title);
        com.google.android.material.button.MaterialButton btnToggle = findViewById(R.id.btn_toggle_online_shelf);
        if (banner == null) return;

        if (currentConnectedPeer == null && !isOnlineShelfMode) {
            banner.setVisibility(View.GONE);
            return;
        }

        banner.setVisibility(View.VISIBLE);
        String peerName = (currentConnectedPeer != null && currentConnectedPeer.deviceName != null && !currentConnectedPeer.deviceName.isEmpty())
                ? currentConnectedPeer.deviceName
                : "电脑设备";

        if (isOnlineShelfMode) {
            if (tvBannerTitle != null) {
                tvBannerTitle.setText("☁️ 在线书架: " + peerName + " (免下载 · 零磁盘占用)");
            }
            if (btnToggle != null) {
                btnToggle.setText("📱 返回本地书架");
                btnToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#059669")));
            }
        } else {
            if (tvBannerTitle != null) {
                tvBannerTitle.setText("🟢 已连 " + peerName + " · 支持免下载在线阅读");
            }
            if (btnToggle != null) {
                btnToggle.setText("💻 在线看电脑书架");
                btnToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#2563EB")));
            }
        }
    }

    private ComicItem buildOnlineComicItem(LanTransferManager.PeerInfo peer, LanTransferManager.RemoteComic rc) {
        if (peer == null || rc == null) return null;
        String encodedId = rc.id;
        try {
            encodedId = java.net.URLEncoder.encode(rc.id != null ? rc.id : "", "UTF-8");
        } catch (Exception ignored) {}

        String baseUrl = "http://" + peer.ip + ":" + peer.port;
        String coverUrl = baseUrl + "/api/comic-cover?id=" + encodedId;
        int totalPages = Math.max(1, rc.pageCount);
        List<String> pageUrls = new ArrayList<>(totalPages);
        for (int i = 0; i < totalPages; i++) {
            pageUrls.add(baseUrl + "/api/comic-page?id=" + encodedId + "&index=" + i);
        }

        // 在线书架上的漫画，阅读次数与最近阅读时间完全直接采用电脑端的真实数据
        int readCount = rc.readCount;
        long lastReadTime = rc.lastReadTime;
        long importTime = rc.importTime > 0 ? rc.importTime : System.currentTimeMillis();

        return new ComicItem(
                rc.id,
                rc.title,
                baseUrl,
                coverUrl,
                pageUrls,
                readCount,
                lastReadTime,
                importTime
        );
    }

    private void openRemoteComicOnline(LanTransferManager.PeerInfo peer, List<LanTransferManager.RemoteComic> remoteList, String targetId) {
        if (peer == null || remoteList == null || remoteList.isEmpty()) return;
        List<ComicItem> onlineItems = new ArrayList<>(remoteList.size());
        int targetIndex = 0;
        for (int i = 0; i < remoteList.size(); i++) {
            LanTransferManager.RemoteComic rc = remoteList.get(i);
            ComicItem item = buildOnlineComicItem(peer, rc);
            if (item != null) {
                if (rc.id != null && rc.id.equals(targetId)) {
                    targetIndex = onlineItems.size();
                }
                onlineItems.add(item);
            }
        }
        if (onlineItems.isEmpty()) return;

        ComicDataManager.setOnlineComicList(onlineItems);
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra("comic_index", targetIndex);
        intent.putExtra("is_online_comic", true);
        startActivity(intent);
    }

    private void enterOnlineShelfMode(LanTransferManager.PeerInfo peer, List<LanTransferManager.RemoteComic> preloadedList) {
        if (peer == null) return;
        currentConnectedPeer = peer;
        isOnlineShelfMode = true;
        if (adapter != null) {
            adapter.setOnlineShelfMode(true);
        }
        if (btnBatch != null) btnBatch.setVisibility(View.GONE);
        if (btnImport != null) btnImport.setVisibility(View.GONE);
        updateOnlineShelfBanner();

        if (preloadedList != null && !preloadedList.isEmpty()) {
            onlineShelfComics.clear();
            for (LanTransferManager.RemoteComic rc : preloadedList) {
                ComicItem item = buildOnlineComicItem(peer, rc);
                if (item != null) onlineShelfComics.add(item);
            }
            sortComics();
            filterComics(etSearch.getText().toString());
            updateShelfStats();
            Toast.makeText(this, "☁️ 已进入 " + peer.deviceName + " 的在线书架（点击任意漫画即可免下载在线阅读）", Toast.LENGTH_SHORT).show();
            return;
        }

        if (tvShelfStats != null) {
            tvShelfStats.setText("正在加载 " + peer.deviceName + " 的在线书架...");
        }
        lanExecutor.execute(() -> {
            try {
                List<LanTransferManager.RemoteComic> fetched = lanTransferManager.fetchRemoteComics(peer.ip, peer.port, false);
                mainHandler.post(() -> {
                    onlineShelfComics.clear();
                    for (LanTransferManager.RemoteComic rc : fetched) {
                        ComicItem item = buildOnlineComicItem(peer, rc);
                        if (item != null) onlineShelfComics.add(item);
                    }
                    sortComics();
                    filterComics(etSearch.getText().toString());
                    updateShelfStats();
                    Toast.makeText(this, "☁️ 已加载 " + peer.deviceName + " 的 " + onlineShelfComics.size() + " 部漫画（零磁盘占用）", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "读取电脑在线书架失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    exitOnlineShelfMode();
                });
            }
        });
    }

    private void exitOnlineShelfMode() {
        isOnlineShelfMode = false;
        if (adapter != null) {
            adapter.setOnlineShelfMode(false);
        }
        if (btnBatch != null) btnBatch.setVisibility(View.VISIBLE);
        if (btnImport != null) btnImport.setVisibility(View.VISIBLE);
        updateOnlineShelfBanner();
        sortComics();
        filterComics(etSearch.getText().toString());
        updateShelfStats();
    }

    private void onLanPeerConnected(LanTransferManager.PeerInfo peer) {
        if (peer == null || peer.ip == null || peer.ip.isEmpty()) return;
        mainHandler.post(() -> {
            currentConnectedPeer = peer;
            updateMainHeaderLanButtonState(peer);

            String peerKey = peer.ip + ":" + peer.port;
            long now = System.currentTimeMillis();
            if (!peerKey.equals(lastAnnouncedPeerKey) || (now - lastAnnouncedPeerTime) > 15000) {
                lastAnnouncedPeerKey = peerKey;
                lastAnnouncedPeerTime = now;
                Toast.makeText(this, "🔗 已与 " + peer.deviceName + " (" + peer.ip + ") 建立局域网连接", Toast.LENGTH_SHORT).show();
            }

            if (activeLanIncomingPeerCallback != null) {
                activeLanIncomingPeerCallback.accept(peer);
            }
        });
    }

    private void onLanBatchTransferComplete(String mode, String peerName, int transferredCount, int skippedCount) {
        mainHandler.post(() -> {
            String safePeer = (peerName != null && !peerName.isEmpty()) ? peerName : "对方设备";
            String summary;
            if ("pull".equalsIgnoreCase(mode)) {
                summary = skippedCount > 0
                        ? ("已向 " + safePeer + " 传送完成：共发送 " + transferredCount + " 部漫画（跳过 " + skippedCount + " 部已有）")
                        : ("已向 " + safePeer + " 传送完成：共发送 " + transferredCount + " 部漫画");
                updateImportProgress("✅ 发送任务完成", 100, summary);
                updateActiveLanDialogProgress("✅ 发送任务完成", 100, summary);
                Toast.makeText(this, "📤 " + summary, Toast.LENGTH_LONG).show();
            } else {
                summary = skippedCount > 0
                        ? ("从 " + safePeer + " 接收完成：共收到 " + transferredCount + " 部新漫画（跳过 " + skippedCount + " 部本机已有）")
                        : ("从 " + safePeer + " 接收完成：共收到 " + transferredCount + " 部漫画并加入书架");
                updateImportProgress("✅ 接收任务完成", 100, summary);
                updateActiveLanDialogProgress("✅ 接收任务完成", 100, summary);
                Toast.makeText(this, "📥 " + summary, Toast.LENGTH_LONG).show();
            }
            hideImportProgress(2400);
            hideActiveLanDialogProgress(3000);
            if (activeLanRefreshCallback != null) {
                activeLanRefreshCallback.run();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (lanTransferManager != null) {
            lanTransferManager.stop();
        }
    }

    private void onLanComicReceived(String comicTitle, File comicFolder) {
        if (comicFolder == null || !comicFolder.exists()) return;
        ComicItem parsed = parseNormalSingleComic(comicFolder);
        if (parsed == null) return;

        mainHandler.post(() -> {
            ComicItem existing = null;
            for (ComicItem item : allComics) {
                if ((item.getFolderPath() != null && item.getFolderPath().equalsIgnoreCase(comicFolder.getAbsolutePath()))
                        || (item.getTitle() != null && item.getTitle().equalsIgnoreCase(parsed.getTitle()))) {
                    existing = item;
                    break;
                }
            }

            if (existing != null) {
                int idx = allComics.indexOf(existing);
                String customCover = existing.getCustomCoverPath();
                if (customCover == null || customCover.isEmpty()) {
                    customCover = ShelfStorage.getCustomCover(this, existing.getId(), parsed.getTitle());
                }
                String effectiveCover = (customCover != null && !customCover.isEmpty() && ShelfStorage.isFileOrUriValid(this, customCover))
                        ? customCover
                        : parsed.getCoverPath();
                ComicItem updated = new ComicItem(
                        existing.getId(),
                        parsed.getTitle(),
                        parsed.getFolderPath(),
                        effectiveCover,
                        parsed.getPagePaths(),
                        existing.getReadCount(),
                        existing.getLastReadTime(),
                        existing.getImportTime() > 0 ? existing.getImportTime() : parsed.getImportTime()
                );
                updated.setCustomCoverPath(customCover);
                updated.setMissing(false);
                allComics.set(idx, updated);
            } else {
                allComics.add(parsed);
            }

            ShelfStorage.saveShelf(this, allComics, ShelfStorage.getSavedRootPath(this));
            sortComics();
            filterComics(etSearch.getText().toString());
            updateShelfStats();
            if (activeLanRefreshCallback != null) {
                activeLanRefreshCallback.run();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionBanner();

        // 仅在本地书架模式下从 SharedPreferences 同步本地漫画阅读次数
        if (!isOnlineShelfMode && sp != null) {
            for (ComicItem item : allComics) {
                int c = Math.max(item.getReadCount(),
                        Math.max(sp.getInt("read_count_" + item.getId(), 0), sp.getInt("read_count_" + item.getTitle(), 0)));
                long t = Math.max(item.getLastReadTime(),
                        Math.max(sp.getLong("last_read_time_" + item.getId(), 0), sp.getLong("last_read_time_" + item.getTitle(), 0)));
                item.setReadCount(c);
                item.setLastReadTime(t);
            }
        }

        if (!allComics.isEmpty() || !onlineShelfComics.isEmpty() || isOnlineShelfMode) {
            sortComics();
            filterComics(etSearch.getText().toString());
            updateShelfStats();
        }

        if (!allComics.isEmpty()) {
            scanExecutor.execute(() -> {
                boolean changed = false;
                for (ComicItem item : new ArrayList<>(allComics)) {
                    boolean missing = checkComicMissing(item);
                    if (item.isMissing() != missing) {
                        item.setMissing(missing);
                        changed = true;
                    }
                }
                if (changed) {
                    mainHandler.post(() -> {
                        filterComics(etSearch.getText().toString());
                        updateShelfStats();
                    });
                }
            });
        }
    }

    private void updatePermissionBanner() {
        View banner = findViewById(R.id.layout_permission_banner);
        if (banner == null) return;
        boolean hasAllFiles = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ? Environment.isExternalStorageManager()
                : (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);

        if (!hasAllFiles) {
            banner.setVisibility(View.VISIBLE);
            banner.setOnClickListener(v -> requestStoragePermission());
        } else {
            banner.setVisibility(View.GONE);
        }
    }

    private void loadShelfFromStorage() {
        scanExecutor.execute(() -> {
            List<ComicItem> loaded = ShelfStorage.loadShelf(this);
            if (loaded.isEmpty()) {
                mainHandler.post(() -> {
                    allComics.clear();
                    filterComics(etSearch.getText().toString());
                    updateShelfStats();
                });
                return;
            }

            // 采用分批队列方式加载书架数据 (每批 20 部)，首屏秒开，避免一次性全量阻塞主线程
            final int BATCH_SIZE = 20;
            mainHandler.post(() -> allComics.clear());

            for (int i = 0; i < loaded.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, loaded.size());
                List<ComicItem> batch = new ArrayList<>(loaded.subList(i, end));
                for (ComicItem item : batch) {
                    item.setMissing(checkComicMissing(item));
                }
                mainHandler.post(() -> {
                    allComics.addAll(batch);
                    sortComics();
                    filterComics(etSearch.getText().toString());
                    updateShelfStats();
                });
            }
        });
    }

    /**
     * 检测漫画源文件是否丢失/移动。如果文件夹或封面都不存在，标记为丢失，不在书架展示，但保留数据
     */
    private boolean checkComicMissing(ComicItem item) {
        if (item == null) return true;
        String folderPath = item.getFolderPath();
        String coverPath = item.getCoverPath();
        if (folderPath == null || folderPath.trim().isEmpty()) {
            return coverPath == null || coverPath.trim().isEmpty();
        }

        // 处理 SAF content:// 协议
        if (folderPath.startsWith("content://")) {
            try {
                Uri uri = Uri.parse(folderPath);
                DocumentFile df = DocumentFile.fromTreeUri(this, uri);
                if (df != null && df.exists() && df.isDirectory()) {
                    return false;
                }
            } catch (Exception ignored) {}

            if (coverPath != null && coverPath.startsWith("content://")) {
                try {
                    Uri coverUri = Uri.parse(coverPath);
                    DocumentFile coverDf = DocumentFile.fromSingleUri(this, coverUri);
                    if (coverDf != null && coverDf.exists()) {
                        return false;
                    }
                } catch (Exception ignored) {}
            }
            return true;
        }

        // 普通文件路径
        try {
            File folder = new File(folderPath);
            if (folder.exists() && folder.isDirectory()) {
                return false;
            }
        } catch (Exception ignored) {}

        if (coverPath != null && !coverPath.startsWith("content://")) {
            try {
                File cover = new File(coverPath);
                if (cover.exists()) {
                    return false;
                }
            } catch (Exception ignored) {}
        }

        return true;
    }

    private void verifyAllComicsExistence() {
        for (ComicItem item : allComics) {
            item.setMissing(checkComicMissing(item));
        }
    }

    private void confirmRemoveComic(ComicItem item) {
        new AlertDialog.Builder(this)
                .setTitle("彻底删除漫画")
                .setMessage("确定要彻底删除《" + item.getTitle() + "》吗？\n\n⚠️ 警告：此操作将同时：\n1. 从书架移除该漫画\n2. 清除已读进度与阅读次数记录\n3. 永久删除手机存储上的漫画物理文件夹及图片！")
                .setPositiveButton("彻底删除", (dialog, which) -> {
                    ShelfStorage.deleteComicPhysicalFiles(this, item);
                    allComics.remove(item);
                    ShelfStorage.clearComicHistory(this, item);
                    ShelfStorage.saveShelf(this, allComics, ShelfStorage.getSavedRootPath(this));
                    filterComics(etSearch.getText().toString());
                    updateShelfStats();
                    Toast.makeText(this, "🗑️ 已彻底删除《" + item.getTitle() + "》及本地物理文件", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRenameComicDialog(ComicItem item) {
        if (item == null) return;

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_rename_comic, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        View btnClose = dialogView.findViewById(R.id.btn_close_rename_dialog);
        View btnCancel = dialogView.findViewById(R.id.btn_cancel_rename);
        View btnConfirm = dialogView.findViewById(R.id.btn_confirm_rename);
        EditText etInput = dialogView.findViewById(R.id.et_rename_comic_input);
        TextView tvError = dialogView.findViewById(R.id.tv_rename_error);

        if (etInput != null) {
            etInput.setText(item.getTitle());
            etInput.setSelection(etInput.getText().length());
        }

        if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());
        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                String newTitle = etInput != null ? etInput.getText().toString().trim() : "";
                String err = ShelfStorage.renameComic(this, item, newTitle, allComics);
                if (err != null) {
                    if (tvError != null) {
                        tvError.setText("⚠️ " + err);
                        tvError.setVisibility(View.VISIBLE);
                    } else {
                        Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }

                ShelfStorage.saveShelf(this, allComics, ShelfStorage.getSavedRootPath(this));
                sortComics();
                filterComics(etSearch.getText().toString());
                updateShelfStats();
                dialog.dismiss();
                Toast.makeText(this, "✏️ 已重命名为《" + item.getTitle() + "》", Toast.LENGTH_SHORT).show();
            });
        }

        dialog.show();
    }

    private void showCustomCoverDialog(ComicItem item) {
        if (item == null) return;

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_custom_cover, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        View btnClose = dialogView.findViewById(R.id.btn_close_cover_dialog);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        android.widget.ImageView ivCurrentPreview = dialogView.findViewById(R.id.iv_current_cover_preview);
        TextView tvComicTitle = dialogView.findViewById(R.id.tv_cover_comic_title);
        TextView tvStatusBadge = dialogView.findViewById(R.id.tv_cover_status_badge);
        TextView tvTotalPages = dialogView.findViewById(R.id.tv_cover_total_pages);
        View btnPickExternal = dialogView.findViewById(R.id.btn_pick_external_cover);
        View btnResetDefault = dialogView.findViewById(R.id.btn_reset_default_cover);
        RecyclerView recyclerPages = dialogView.findViewById(R.id.recycler_cover_pages);

        List<String> pages = item.getPagePaths() != null ? item.getPagePaths() : new ArrayList<>();
        if (tvComicTitle != null) {
            tvComicTitle.setText(item.getTitle());
        }
        if (tvTotalPages != null) {
            tvTotalPages.setText("共 " + pages.size() + " 页");
        }
        if (ivCurrentPreview != null) {
            ImageLoader.getInstance().displayImage(this, item.getCoverPath(), ivCurrentPreview, 240, 340);
        }

        boolean hasCustom = item.getCustomCoverPath() != null && !item.getCustomCoverPath().isEmpty();
        if (tvStatusBadge != null) {
            if (!hasCustom) {
                tvStatusBadge.setText("当前使用：默认第 1 页封面");
            } else {
                int matchIdx = pages.indexOf(item.getCustomCoverPath());
                if (matchIdx >= 0) {
                    tvStatusBadge.setText("当前使用：漫画内第 " + (matchIdx + 1) + " 页");
                } else {
                    tvStatusBadge.setText("当前使用：外部自定义图片");
                }
            }
        }

        if (btnPickExternal != null) {
            btnPickExternal.setOnClickListener(v -> {
                dialog.dismiss();
                pendingCoverComic = item;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                customCoverImageLauncher.launch(intent);
            });
        }

        if (btnResetDefault != null) {
            btnResetDefault.setOnClickListener(v -> {
                ShelfStorage.cleanupCustomCoverFiles(this, item.getId());
                ShelfStorage.saveCustomCover(this, item, "");
                ShelfStorage.saveShelf(this, allComics, ShelfStorage.getSavedRootPath(this));
                adapter.notifyDataSetChanged();
                dialog.dismiss();
                Toast.makeText(this, "已恢复《" + item.getTitle() + "》为默认第 1 页封面", Toast.LENGTH_SHORT).show();
            });
        }

        if (recyclerPages != null) {
            recyclerPages.setLayoutManager(new GridLayoutManager(this, 3));
            recyclerPages.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @Override
                public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
                    View v = getLayoutInflater().inflate(R.layout.item_cover_page_thumb, parent, false);
                    return new RecyclerView.ViewHolder(v) {};
                }

                @Override
                public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                    String pagePath = pages.get(position);
                    com.google.android.material.card.MaterialCardView card = holder.itemView.findViewById(R.id.card_cover_thumb);
                    android.widget.ImageView ivThumb = holder.itemView.findViewById(R.id.iv_page_thumb);
                    TextView tvPageBadge = holder.itemView.findViewById(R.id.tv_page_num_badge);
                    TextView tvCurrentTag = holder.itemView.findViewById(R.id.tv_current_cover_tag);

                    if (tvPageBadge != null) {
                        tvPageBadge.setText("第 " + (position + 1) + " 页");
                    }
                    if (ivThumb != null) {
                        ImageLoader.getInstance().displayImage(MainActivity.this, pagePath, ivThumb, 260, 360);
                    }

                    boolean isCurrentCover = pagePath != null && pagePath.equals(item.getCoverPath());
                    if (tvCurrentTag != null) {
                        tvCurrentTag.setVisibility(isCurrentCover ? View.VISIBLE : View.GONE);
                    }
                    if (card != null) {
                        card.setStrokeColor(android.graphics.Color.parseColor(isCurrentCover ? "#6366F1" : "#2A2F42"));
                        card.setStrokeWidth(Math.round((isCurrentCover ? 2f : 1f) * getResources().getDisplayMetrics().density));
                    }

                    holder.itemView.setOnClickListener(v -> {
                        ShelfStorage.cleanupCustomCoverFiles(MainActivity.this, item.getId());
                        ShelfStorage.saveCustomCover(MainActivity.this, item, pagePath);
                        ShelfStorage.saveShelf(MainActivity.this, allComics, ShelfStorage.getSavedRootPath(MainActivity.this));
                        adapter.notifyDataSetChanged();
                        dialog.dismiss();
                        Toast.makeText(MainActivity.this, "已将第 " + (position + 1) + " 页画面设为《" + item.getTitle() + "》的封面", Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public int getItemCount() {
                    return pages.size();
                }
            });
        }

        dialog.show();
    }

    private void toggleBatchMode() {
        if (adapter == null) return;
        boolean nextMode = !adapter.isBatchMode();
        adapter.setBatchMode(nextMode);
        updateBatchUI(adapter.getSelectedIds().size(), displayComics.size());
    }

    private void exitBatchMode() {
        if (adapter == null || !adapter.isBatchMode()) return;
        adapter.setBatchMode(false);
        updateBatchUI(0, displayComics.size());
    }

    private void updateBatchUI(int selectedCount, int totalCount) {
        View normalHeader = findViewById(R.id.layout_normal_header);
        if (adapter != null && adapter.isBatchMode()) {
            if (layoutBatchBar != null) layoutBatchBar.setVisibility(View.VISIBLE);
            if (normalHeader != null) normalHeader.setVisibility(View.GONE);
            if (btnImport != null) btnImport.setVisibility(View.GONE);
        } else {
            if (layoutBatchBar != null) layoutBatchBar.setVisibility(View.GONE);
            if (normalHeader != null) normalHeader.setVisibility(View.VISIBLE);
            if (btnImport != null) btnImport.setVisibility(View.VISIBLE);
            if (btnBatch instanceof com.google.android.material.button.MaterialButton) {
                ((com.google.android.material.button.MaterialButton) btnBatch).setText("多选");
            }
        }

        if (tvBatchCount != null) {
            tvBatchCount.setText("已选 " + selectedCount + " 项");
        }
        if (btnBatchDelete != null) {
            btnBatchDelete.setEnabled(selectedCount > 0);
        }
        if (cbBatchSelectAll != null) {
            cbBatchSelectAll.setChecked(totalCount > 0 && selectedCount == totalCount);
        }
    }

    private void confirmBatchDelete() {
        if (adapter == null) return;
        Set<String> selectedIds = adapter.getSelectedIds();
        int count = selectedIds.size();
        if (count == 0) return;

        new AlertDialog.Builder(this)
                .setTitle("批量彻底删除漫画")
                .setMessage("确定要批量彻底删除选中的 " + count + " 部漫画吗？\n\n⚠️ 警告：此操作将同时从书架移除所选漫画、清除阅读记录，并永久删除手机存储上的对应漫画物理文件夹及图片！")
                .setPositiveButton("批量删除", (dialog, which) -> {
                    List<ComicItem> toDelete = new ArrayList<>();
                    for (ComicItem c : allComics) {
                        if (selectedIds.contains(c.getId())) {
                            toDelete.add(c);
                        }
                    }
                    for (ComicItem c : toDelete) {
                        ShelfStorage.deleteComicPhysicalFiles(this, c);
                    }
                    ShelfStorage.clearComicsHistory(this, toDelete);
                    allComics.removeAll(toDelete);
                    ShelfStorage.saveShelf(this, allComics, ShelfStorage.getSavedRootPath(this));
                    exitBatchMode();
                    filterComics(etSearch.getText().toString());
                    updateShelfStats();
                    Toast.makeText(this, "🗑️ 已批量彻底删除 " + count + " 部漫画及本地物理文件", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (isOnlineShelfMode) {
            exitOnlineShelfMode();
            return;
        }
        if (adapter != null && adapter.isBatchMode()) {
            exitBatchMode();
            return;
        }
        super.onBackPressed();
    }

    private void showSortDialog() {
        String[] sortOptions = new String[] {
                "漫画名称 (A-Z)",
                "🕒 导入时间 (最新在前)",
                "🔥 阅读次数最多",
                "⏱️ 最近阅读",
                "📄 切片页数最多"
        };
        new AlertDialog.Builder(this)
                .setTitle("选择排序方式")
                .setSingleChoiceItems(sortOptions, currentSortMode, (dialog, which) -> {
                    currentSortMode = which;
                    sp.edit().putInt("comic_sort_mode", currentSortMode).apply();
                    sortComics();
                    filterComics(etSearch.getText().toString());
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void sortComics() {
        List<ComicItem> targetList = isOnlineShelfMode ? onlineShelfComics : allComics;
        switch (currentSortMode) {
            case 1: // 导入时间 (最新在前) (import_time_desc)
                targetList.sort((a, b) -> {
                    int diff = Long.compare(b.getImportTime(), a.getImportTime());
                    return diff != 0 ? diff : naturalOrderComparator.compare(a.getTitle(), b.getTitle());
                });
                break;
            case 2: // 阅读次数最多 (views_desc)
                targetList.sort((a, b) -> {
                    int diff = b.getReadCount() - a.getReadCount();
                    return diff != 0 ? diff : naturalOrderComparator.compare(a.getTitle(), b.getTitle());
                });
                break;
            case 3: // 最近阅读 (recent_read)
                targetList.sort((a, b) -> {
                    int diff = Long.compare(b.getLastReadTime(), a.getLastReadTime());
                    return diff != 0 ? diff : naturalOrderComparator.compare(a.getTitle(), b.getTitle());
                });
                break;
            case 4: // 切片页数最多 (pages_desc)
                targetList.sort((a, b) -> {
                    int diff = b.getTotalPages() - a.getTotalPages();
                    return diff != 0 ? diff : naturalOrderComparator.compare(a.getTitle(), b.getTitle());
                });
                break;
            case 0: // 漫画名称 (A-Z)
            default:
                targetList.sort((a, b) -> naturalOrderComparator.compare(a.getTitle(), b.getTitle()));
                break;
        }
    }

    private void updateShelfStats() {
        String kw = etSearch.getText().toString().trim();
        if (isOnlineShelfMode) {
            if (kw.isEmpty()) {
                tvShelfStats.setText("☁️ 在线书架 " + onlineShelfComics.size() + " 部漫画");
            } else {
                tvShelfStats.setText("☁️ 在线匹配 " + displayComics.size() + " 部");
            }
            return;
        }

        int missingCount = 0;
        int activeTotal = 0;
        for (ComicItem item : allComics) {
            if (item.isMissing()) {
                missingCount++;
            } else {
                activeTotal++;
            }
        }
        if (kw.isEmpty()) {
            if (missingCount > 0) {
                tvShelfStats.setText(activeTotal + " 部漫画 (" + missingCount + "部未显示)");
            } else {
                tvShelfStats.setText(activeTotal + " 部漫画");
            }
        } else {
            tvShelfStats.setText("匹配 " + displayComics.size() + " 部");
        }
    }

    private void confirmClearShelf() {
        new AlertDialog.Builder(this)
                .setTitle("重置书架")
                .setMessage("确定要清空已保存的书架数据吗？此操作将清空所有漫画记录。")
                .setPositiveButton("清空", (dialog, which) -> {
                    ShelfStorage.clearShelf(this);
                    allComics.clear();
                    displayComics.clear();
                    adapter.notifyDataSetChanged();
                    layoutEmpty.setVisibility(View.VISIBLE);
                    updateShelfStats();
                    Toast.makeText(this, "书架已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSettingsDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        View btnClose = dialogView.findViewById(R.id.btn_close_settings);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        TextView tvBadge = dialogView.findViewById(R.id.tv_settings_missing_count_badge);
        List<ComicItem> missingList = new ArrayList<>();
        for (ComicItem item : allComics) {
            if (item.isMissing()) missingList.add(item);
        }
        if (tvBadge != null) {
            tvBadge.setText(missingList.size() + " 部");
        }

        View cardMissing = dialogView.findViewById(R.id.card_missing_books_setting);
        View btnViewMissing = dialogView.findViewById(R.id.btn_view_missing_comics);
        View.OnClickListener openMissingListener = v -> {
            dialog.dismiss();
            showMissingComicsDetailDialog();
        };
        if (cardMissing != null) cardMissing.setOnClickListener(openMissingListener);
        if (btnViewMissing != null) btnViewMissing.setOnClickListener(openMissingListener);

        // 局域网互传与接收目录设置
        TextView tvTransferDir = dialogView.findViewById(R.id.tv_settings_transfer_dir);
        if (tvTransferDir != null) {
            tvTransferDir.setText(ShelfStorage.getTransferSaveDir(this));
        }

        View btnOpenLan = dialogView.findViewById(R.id.btn_settings_open_lan);
        if (btnOpenLan != null) {
            btnOpenLan.setOnClickListener(v -> {
                dialog.dismiss();
                showLanTransferDialog();
            });
        }

        View btnChangeTransferDir = dialogView.findViewById(R.id.btn_settings_change_transfer_dir);
        if (btnChangeTransferDir != null) {
            btnChangeTransferDir.setOnClickListener(v -> {
                openTransferFolderChooser(newDir -> {
                    if (tvTransferDir != null) {
                        tvTransferDir.setText(newDir);
                    }
                });
            });
        }

        View btnResetTransferDir = dialogView.findViewById(R.id.btn_settings_reset_transfer_dir);
        if (btnResetTransferDir != null) {
            btnResetTransferDir.setOnClickListener(v -> {
                ShelfStorage.setTransferSaveDir(this, "");
                String defDir = ShelfStorage.getTransferSaveDir(this);
                if (tvTransferDir != null) {
                    tvTransferDir.setText(defDir);
                }
                Toast.makeText(this, "已恢复默认接收目录: " + defDir, Toast.LENGTH_SHORT).show();
            });
        }

        TextView tvSortMode = dialogView.findViewById(R.id.tv_settings_sort_mode);
        String[] sortOptions = new String[] {
                "漫画名称 (A-Z)",
                "🕒 导入时间 (最新在前)",
                "🔥 阅读次数最多",
                "⏱️ 最近阅读",
                "📄 切片页数最多"
        };
        if (tvSortMode != null && currentSortMode >= 0 && currentSortMode < sortOptions.length) {
            tvSortMode.setText("当前: " + sortOptions[currentSortMode]);
        }

        View btnChangeSort = dialogView.findViewById(R.id.btn_settings_change_sort);
        if (btnChangeSort != null) {
            btnChangeSort.setOnClickListener(v -> {
                dialog.dismiss();
                showSortDialog();
            });
        }

        View btnClearShelf = dialogView.findViewById(R.id.btn_settings_clear_shelf);
        if (btnClearShelf != null) {
            btnClearShelf.setOnClickListener(v -> {
                dialog.dismiss();
                confirmClearShelf();
            });
        }

        dialog.show();
    }

    private void showMissingComicsDetailDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_missing_comics, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        View btnClose = dialogView.findViewById(R.id.btn_close_missing_dialog);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        TextView tvTitle = dialogView.findViewById(R.id.tv_missing_dialog_title);
        TextView tvEmptyTip = dialogView.findViewById(R.id.tv_no_missing_tip);
        RecyclerView recyclerMissing = dialogView.findViewById(R.id.recycler_missing_comics);
        View btnClearAll = dialogView.findViewById(R.id.btn_clear_all_missing);
        View btnRescan = dialogView.findViewById(R.id.btn_rescan_all);

        List<ComicItem> missingList = new ArrayList<>();
        for (ComicItem item : allComics) {
            if (item.isMissing()) missingList.add(item);
        }

        if (tvTitle != null) {
            tvTitle.setText("丢失/未在书架显示的漫画 (" + missingList.size() + " 部)");
        }

        recyclerMissing.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));

        final MissingComicsAdapter[] adapterHolder = new MissingComicsAdapter[1];
        adapterHolder[0] = new MissingComicsAdapter(missingList, item -> {
            new AlertDialog.Builder(this)
                    .setTitle("彻底清理记录")
                    .setMessage("确定要彻底清理《" + item.getTitle() + "》的历史数据吗？\n（此操作将删除其阅读记录与历史次数）")
                    .setPositiveButton("彻底清理", (d, w) -> {
                        allComics.remove(item);
                        missingList.remove(item);
                        ShelfStorage.clearComicHistory(this, item);
                        ShelfStorage.saveShelf(this, allComics, ShelfStorage.getSavedRootPath(this));
                        filterComics(etSearch.getText().toString());
                        updateShelfStats();
                        adapterHolder[0].notifyDataSetChanged();
                        if (tvTitle != null) {
                            tvTitle.setText("丢失/未在书架显示的漫画 (" + missingList.size() + " 部)");
                        }
                        if (missingList.isEmpty()) {
                            recyclerMissing.setVisibility(View.GONE);
                            if (tvEmptyTip != null) tvEmptyTip.setVisibility(View.VISIBLE);
                            if (btnClearAll != null) btnClearAll.setEnabled(false);
                        }
                        Toast.makeText(this, "已彻底清理《" + item.getTitle() + "》", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        recyclerMissing.setAdapter(adapterHolder[0]);

        if (missingList.isEmpty()) {
            recyclerMissing.setVisibility(View.GONE);
            if (tvEmptyTip != null) tvEmptyTip.setVisibility(View.VISIBLE);
            if (btnClearAll != null) btnClearAll.setEnabled(false);
        } else {
            recyclerMissing.setVisibility(View.VISIBLE);
            if (tvEmptyTip != null) tvEmptyTip.setVisibility(View.GONE);
            if (btnClearAll != null) btnClearAll.setEnabled(true);
        }

        if (btnClearAll != null) {
            btnClearAll.setOnClickListener(v -> {
                if (missingList.isEmpty()) return;
                new AlertDialog.Builder(this)
                        .setTitle("清理全部丢失记录")
                        .setMessage("确定要彻底清理这 " + missingList.size() + " 部丢失漫画的所有历史记录与阅读统计吗？\n（清理后不可撤销）")
                        .setPositiveButton("清理全部", (d, w) -> {
                            for (ComicItem item : missingList) {
                                ShelfStorage.clearComicHistory(this, item);
                            }
                            allComics.removeAll(missingList);
                            missingList.clear();
                            ShelfStorage.saveShelf(this, allComics, ShelfStorage.getSavedRootPath(this));
                            filterComics(etSearch.getText().toString());
                            updateShelfStats();
                            adapterHolder[0].notifyDataSetChanged();
                            recyclerMissing.setVisibility(View.GONE);
                            if (tvEmptyTip != null) tvEmptyTip.setVisibility(View.VISIBLE);
                            if (btnClearAll != null) btnClearAll.setEnabled(false);
                            if (tvTitle != null) tvTitle.setText("丢失/未在书架显示的漫画 (0 部)");
                            Toast.makeText(this, "已彻底清理全部丢失记录", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        if (btnRescan != null) {
            btnRescan.setOnClickListener(v -> {
                verifyAllComicsExistence();
                missingList.clear();
                for (ComicItem item : allComics) {
                    if (item.isMissing()) missingList.add(item);
                }
                adapterHolder[0].notifyDataSetChanged();
                if (tvTitle != null) {
                    tvTitle.setText("丢失/未在书架显示的漫画 (" + missingList.size() + " 部)");
                }
                if (missingList.isEmpty()) {
                    recyclerMissing.setVisibility(View.GONE);
                    if (tvEmptyTip != null) tvEmptyTip.setVisibility(View.VISIBLE);
                    if (btnClearAll != null) btnClearAll.setEnabled(false);
                } else {
                    recyclerMissing.setVisibility(View.VISIBLE);
                    if (tvEmptyTip != null) tvEmptyTip.setVisibility(View.GONE);
                    if (btnClearAll != null) btnClearAll.setEnabled(true);
                }
                filterComics(etSearch.getText().toString());
                updateShelfStats();
                Toast.makeText(this, "重新检测完成，丢失数量: " + missingList.size(), Toast.LENGTH_SHORT).show();
            });
        }

        dialog.show();
    }

    private interface OnTransferDirChanged {
        void onChanged(String newDir);
    }

    private void openTransferFolderChooser(OnTransferDirChanged callback) {
        FolderPickerDialog picker = new FolderPickerDialog(
                this,
                selectedPath -> {
                    File f = new File(selectedPath);
                    if (!f.exists()) f.mkdirs();
                    ShelfStorage.setTransferSaveDir(this, selectedPath);
                    String actual = ShelfStorage.getTransferSaveDir(this);
                    if (callback != null) callback.onChanged(actual);
                    Toast.makeText(this, "已设置接收漫画存储目录: " + actual, Toast.LENGTH_SHORT).show();
                },
                this::requestSafForFolder
        );
        activeFolderDialog = picker;
        picker.show();
    }

    private String buildSkippedPreview(List<String> skippedTitles) {
        if (skippedTitles == null || skippedTitles.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(5, skippedTitles.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append("、");
            sb.append("《").append(skippedTitles.get(i)).append("》");
        }
        if (skippedTitles.size() > limit) {
            sb.append(" 等 ").append(skippedTitles.size()).append(" 部");
        }
        return sb.toString();
    }

    private void showLanTransferDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_lan_transfer, null);
        android.app.Dialog dialog = new android.app.Dialog(this, R.style.Theme_ComicReader);
        dialog.setContentView(dialogView);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
            );
            dialog.getWindow().setStatusBarColor(android.graphics.Color.parseColor("#1A1D26"));
            dialog.getWindow().setNavigationBarColor(android.graphics.Color.parseColor("#121318"));
        }

        View btnClose = dialogView.findViewById(R.id.btn_close_lan_dialog);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        TextView tvConnBadge = dialogView.findViewById(R.id.tv_lan_conn_badge);
        com.google.android.material.button.MaterialButton btnRefreshRemote = dialogView.findViewById(R.id.btn_lan_refresh_remote);
        com.google.android.material.button.MaterialButton btnToggleOptions = dialogView.findViewById(R.id.btn_lan_toggle_options);
        View cardOptionsDrawer = dialogView.findViewById(R.id.card_lan_options_drawer);

        TextView tvSelfIp = dialogView.findViewById(R.id.tv_lan_self_ip);
        TextView tvSaveDir = dialogView.findViewById(R.id.tv_lan_save_dir);
        View btnChangeDir = dialogView.findViewById(R.id.btn_lan_change_dir);
        View btnResetDir = dialogView.findViewById(R.id.btn_lan_reset_dir);

        EditText etManualIp = dialogView.findViewById(R.id.et_lan_manual_ip);
        View btnConnectIp = dialogView.findViewById(R.id.btn_lan_connect_ip);
        com.google.android.material.button.MaterialButton btnScanPeers = dialogView.findViewById(R.id.btn_lan_scan_peers);
        LinearLayout layoutPeers = dialogView.findViewById(R.id.layout_lan_peers);
        TextView tvPeerStatus = dialogView.findViewById(R.id.tv_lan_peer_status);

        if (btnToggleOptions != null && cardOptionsDrawer != null) {
            btnToggleOptions.setOnClickListener(v -> {
                boolean expand = cardOptionsDrawer.getVisibility() != View.VISIBLE;
                cardOptionsDrawer.setVisibility(expand ? View.VISIBLE : View.GONE);
                btnToggleOptions.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(expand ? "#6366F1" : "#222634")));
                btnToggleOptions.setTextColor(android.graphics.Color.parseColor(expand ? "#FFFFFF" : "#CBD5E1"));
            });
        }

        View cardLiveProgress = dialogView.findViewById(R.id.card_lan_live_progress);
        TextView tvProgressTitle = dialogView.findViewById(R.id.tv_lan_progress_title);
        TextView tvProgressPct = dialogView.findViewById(R.id.tv_lan_progress_pct);
        ProgressBar barProgress = dialogView.findViewById(R.id.bar_lan_progress);
        TextView tvTransferProgress = dialogView.findViewById(R.id.tv_lan_transfer_progress);

        com.google.android.material.button.MaterialButton btnBrowseOnlineShelf = dialogView.findViewById(R.id.btn_lan_browse_online_shelf);
        com.google.android.material.button.MaterialButton btnTabPull = dialogView.findViewById(R.id.btn_tab_lan_pull);
        com.google.android.material.button.MaterialButton btnTabPush = dialogView.findViewById(R.id.btn_tab_lan_push);
        View btnSelectAll = dialogView.findViewById(R.id.btn_lan_select_all);
        com.google.android.material.button.MaterialButton btnFilterMissing = dialogView.findViewById(R.id.btn_lan_filter_missing);
        EditText etSearchComic = dialogView.findViewById(R.id.et_lan_search_comic);
        View btnClearSearch = dialogView.findViewById(R.id.btn_lan_clear_search);
        RecyclerView recyclerLanComics = dialogView.findViewById(R.id.recycler_lan_comics);
        TextView tvEmptyList = dialogView.findViewById(R.id.tv_lan_empty_list);
        com.google.android.material.button.MaterialButton btnExecute = dialogView.findViewById(R.id.btn_lan_execute_transfer);

        activeLanProgressCard = cardLiveProgress;
        activeLanProgressTitle = tvProgressTitle;
        activeLanProgressPct = tvProgressPct;
        activeLanProgressBar = barProgress;
        activeLanProgressView = tvTransferProgress;

        List<String> localIps = lanTransferManager != null ? lanTransferManager.getLocalLanIps() : new ArrayList<>();
        String ipText = localIps.isEmpty() ? "请先连接 Wi-Fi 局域网" : String.join(" / ", localIps) + ":" + lanTransferManager.getListeningPort();
        if (tvSelfIp != null) {
            tvSelfIp.setText("📱 本机: " + ipText);
        }
        if (tvSaveDir != null) {
            tvSaveDir.setText("📁 接收目录: " + ShelfStorage.getTransferSaveDir(this));
        }

        if (btnChangeDir != null) {
            btnChangeDir.setOnClickListener(v -> openTransferFolderChooser(newDir -> {
                if (tvSaveDir != null) tvSaveDir.setText("📁 接收目录: " + newDir);
            }));
        }

        if (btnResetDir != null) {
            btnResetDir.setOnClickListener(v -> {
                ShelfStorage.setTransferSaveDir(this, "");
                String defDir = ShelfStorage.getTransferSaveDir(this);
                if (tvSaveDir != null) tvSaveDir.setText("📁 接收目录: " + defDir);
                Toast.makeText(this, "已恢复默认目录: " + defDir, Toast.LENGTH_SHORT).show();
            });
        }

        final List<LanTransferManager.PeerInfo> discoveredPeers = new ArrayList<>();
        final LanTransferManager.PeerInfo[] selectedPeer = new LanTransferManager.PeerInfo[1];
        final List<LanTransferManager.RemoteComic> remoteComics = new ArrayList<>();
        final boolean[] isPullTab = new boolean[]{true}; // true = 从对方下载, false = 发送本机漫画
        final boolean[] onlyMissingFilter = new boolean[]{false}; // true = 仅看本机/对方没有的漫画
        final String[] searchKeyword = new String[]{""};
        final boolean[] isTransferring = new boolean[]{false};

        recyclerLanComics.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        final LanComicSelectAdapter[] selectAdapter = new LanComicSelectAdapter[1];

        Runnable updateConnBadge = () -> {
            if (tvConnBadge == null) return;
            if (selectedPeer[0] != null) {
                tvConnBadge.setText("已连: " + selectedPeer[0].deviceName);
                tvConnBadge.setTextColor(android.graphics.Color.parseColor("#6EE7B7"));
            } else {
                tvConnBadge.setText("等待设备连接");
                tvConnBadge.setTextColor(android.graphics.Color.parseColor("#94A3B8"));
            }
        };

        Runnable updateFooterAction = () -> {
            int count = selectAdapter[0] != null ? selectAdapter[0].getSelectedIds().size() : 0;
            if (selectedPeer[0] == null) {
                if (btnExecute != null) {
                    btnExecute.setEnabled(false);
                    btnExecute.setText(isPullTab[0] ? "请先在上方连接设备" : "请先在上方连接设备");
                }
                return;
            }
            if (btnExecute != null) {
                btnExecute.setEnabled(!isTransferring[0] && count > 0);
                if (isPullTab[0]) {
                    btnExecute.setText("下载所选漫画到本手机 (" + count + ")");
                    btnExecute.setIconResource(R.drawable.ic_download_vector);
                } else {
                    btnExecute.setText("发送到 " + selectedPeer[0].deviceName + " (" + count + ")");
                    btnExecute.setIconResource(R.drawable.ic_upload_vector);
                }
            }
        };

        selectAdapter[0] = new LanComicSelectAdapter(count -> updateFooterAction.run());
        selectAdapter[0].setOnlineReadClickListener(entry -> {
            if (selectedPeer[0] != null && entry != null) {
                openRemoteComicOnline(selectedPeer[0], remoteComics, entry.id);
            }
        });
        recyclerLanComics.setAdapter(selectAdapter[0]);

        if (btnBrowseOnlineShelf != null) {
            btnBrowseOnlineShelf.setOnClickListener(v -> {
                if (selectedPeer[0] != null) {
                    dialog.dismiss();
                    enterOnlineShelfMode(selectedPeer[0], new ArrayList<>(remoteComics));
                }
            });
        }

        Runnable refreshTabList = () -> {
            if (btnBrowseOnlineShelf != null) {
                if (selectedPeer[0] != null && !remoteComics.isEmpty()) {
                    btnBrowseOnlineShelf.setVisibility(View.VISIBLE);
                    btnBrowseOnlineShelf.setText("进入电脑在线书架浏览 (共 " + remoteComics.size() + " 部 · 零磁盘占用)");
                } else {
                    btnBrowseOnlineShelf.setVisibility(View.GONE);
                }
            }
            selectAdapter[0].setShowOnlineReadButton(isPullTab[0] && selectedPeer[0] != null);
            List<ComicItem> localActive = new ArrayList<>();
            Set<String> localTitleKeys = new HashSet<>();
            for (ComicItem c : allComics) {
                if (!c.isMissing()) {
                    localActive.add(c);
                    String k = LanTransferManager.normalizeTitleKey(c.getTitle());
                    if (!k.isEmpty()) localTitleKeys.add(k);
                }
            }

            Set<String> remoteTitleKeys = new HashSet<>();
            for (LanTransferManager.RemoteComic rc : remoteComics) {
                String k = LanTransferManager.normalizeTitleKey(rc.title);
                if (!k.isEmpty()) remoteTitleKeys.add(k);
            }

            if (btnTabPull != null) {
                btnTabPull.setText("从对方下载 (" + remoteComics.size() + ")");
                btnTabPull.setIconResource(R.drawable.ic_download_vector);
                btnTabPull.setIconTint(android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(isPullTab[0] ? "#FFFFFF" : "#CBD5E1")));
                btnTabPull.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(isPullTab[0] ? "#6366F1" : "#222634")));
                btnTabPull.setTextColor(android.graphics.Color.parseColor(
                        isPullTab[0] ? "#FFFFFF" : "#CBD5E1"));
            }
            if (btnTabPush != null) {
                btnTabPush.setText("发送本机漫画 (" + localActive.size() + ")");
                btnTabPush.setIconResource(R.drawable.ic_upload_vector);
                btnTabPush.setIconTint(android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(!isPullTab[0] ? "#FFFFFF" : "#CBD5E1")));
                btnTabPush.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(!isPullTab[0] ? "#6366F1" : "#222634")));
                btnTabPush.setTextColor(android.graphics.Color.parseColor(
                        !isPullTab[0] ? "#FFFFFF" : "#CBD5E1"));
            }

            int missingTotalCount = 0;
            if (isPullTab[0]) {
                for (LanTransferManager.RemoteComic rc : remoteComics) {
                    if (!localTitleKeys.contains(LanTransferManager.normalizeTitleKey(rc.title))) {
                        missingTotalCount++;
                    }
                }
            } else {
                for (ComicItem c : localActive) {
                    boolean peerHas = selectedPeer[0] != null
                            && remoteTitleKeys.contains(LanTransferManager.normalizeTitleKey(c.getTitle()));
                    if (!peerHas) missingTotalCount++;
                }
            }

            if (btnFilterMissing != null) {
                String btnLabel = "未拥有 (" + missingTotalCount + ")";
                btnFilterMissing.setText(btnLabel);
                btnFilterMissing.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(onlyMissingFilter[0] ? "#059669" : "#222634")));
                btnFilterMissing.setTextColor(android.graphics.Color.parseColor(
                        onlyMissingFilter[0] ? "#FFFFFF" : "#CBD5E1"));
            }

            String kw = searchKeyword[0] != null ? searchKeyword[0].trim().toLowerCase() : "";
            Set<String> prevSelected = selectAdapter[0].getSelectedIds();
            Set<String> keptSelection = new HashSet<>();

            List<LanComicSelectAdapter.Entry> entries = new ArrayList<>();
            if (isPullTab[0]) {
                for (LanTransferManager.RemoteComic rc : remoteComics) {
                    boolean localOwned = localTitleKeys.contains(LanTransferManager.normalizeTitleKey(rc.title));
                    if (onlyMissingFilter[0] && localOwned) continue;
                    if (!kw.isEmpty() && (rc.title == null || !rc.title.toLowerCase().contains(kw))) continue;
                    int localSpRead = sp != null
                            ? Math.max(sp.getInt("read_count_" + rc.title, 0), sp.getInt("read_count_" + rc.id, 0))
                            : 0;
                    int effectiveReadCount = Math.max(rc.readCount, localSpRead);
                    entries.add(new LanComicSelectAdapter.Entry(
                            rc.id,
                            rc.title,
                            rc.pageCount,
                            effectiveReadCount,
                            localOwned,
                            localOwned ? "✓ 本机已拥有" : ""
                    ));
                    if (prevSelected.contains(rc.id)) keptSelection.add(rc.id);
                }
            } else {
                for (ComicItem c : localActive) {
                    String id = c.getId() != null ? c.getId() : c.getTitle();
                    boolean peerOwned = selectedPeer[0] != null
                            && remoteTitleKeys.contains(LanTransferManager.normalizeTitleKey(c.getTitle()));
                    if (onlyMissingFilter[0] && peerOwned) continue;
                    if (!kw.isEmpty() && (c.getTitle() == null || !c.getTitle().toLowerCase().contains(kw))) continue;
                    int pageCount = c.getPagePaths() != null ? c.getPagePaths().size() : 0;
                    int localSpRead = sp != null
                            ? Math.max(sp.getInt("read_count_" + c.getTitle(), 0), sp.getInt("read_count_" + id, 0))
                            : 0;
                    int effectiveReadCount = Math.max(c.getReadCount(), localSpRead);
                    entries.add(new LanComicSelectAdapter.Entry(
                            id,
                            c.getTitle(),
                            pageCount,
                            effectiveReadCount,
                            peerOwned,
                            peerOwned ? "✓ 对方已拥有" : ""
                    ));
                    if (prevSelected.contains(id)) keptSelection.add(id);
                }
            }
            selectAdapter[0].setEntries(entries, keptSelection);

            if (tvEmptyList != null) {
                if (selectedPeer[0] == null && isPullTab[0]) {
                    tvEmptyList.setVisibility(View.VISIBLE);
                    tvEmptyList.setText("📡 请先在上方点击连接一台电脑或手机设备\n（或由电脑端直接发起连接）");
                } else if (entries.isEmpty()) {
                    tvEmptyList.setVisibility(View.VISIBLE);
                    if (!kw.isEmpty()) {
                        tvEmptyList.setText("🔍 未找到包含 “" + searchKeyword[0] + "” 的漫画");
                    } else if (onlyMissingFilter[0]) {
                        tvEmptyList.setText(isPullTab[0]
                                ? "🎉 对方设备上的所有漫画本机均已拥有！"
                                : "🎉 本机书架上的所有漫画对方设备均已拥有！");
                    } else {
                        tvEmptyList.setText(isPullTab[0] ? "对方设备书架暂无漫画" : "本机书架暂无漫画");
                    }
                } else {
                    tvEmptyList.setVisibility(View.GONE);
                }
            }
            updateConnBadge.run();
            updateFooterAction.run();
        };

        activeLanRefreshCallback = refreshTabList;

        final Runnable[] renderPeerChipsHolder = new Runnable[1];
        final java.util.function.BiConsumer<LanTransferManager.PeerInfo, Boolean> connectToPeerAction = (p, notifyRemote) -> {
            if (p == null) return;
            boolean isNewPeer = selectedPeer[0] == null || !selectedPeer[0].ip.equals(p.ip);
            selectedPeer[0] = p;
            currentConnectedPeer = p;
            updateMainHeaderLanButtonState(p);
            updateConnBadge.run();
            if (renderPeerChipsHolder[0] != null) renderPeerChipsHolder[0].run();

            if (tvPeerStatus != null) {
                tvPeerStatus.setText("正在同步 " + p.deviceName + " (" + p.ip + ") 的漫画列表...");
            }
            lanExecutor.execute(() -> {
                try {
                    List<LanTransferManager.RemoteComic> fetched = lanTransferManager.fetchRemoteComics(p.ip, p.port, notifyRemote);
                    mainHandler.post(() -> {
                        remoteComics.clear();
                        remoteComics.addAll(fetched);
                        p.comicCount = remoteComics.size();
                        if (renderPeerChipsHolder[0] != null) renderPeerChipsHolder[0].run();
                        if (tvPeerStatus != null) {
                            tvPeerStatus.setText("🟢 已双向连接: " + p.deviceName + " (" + p.ip + ") · 对方共 " + remoteComics.size() + " 部漫画");
                        }
                        if (notifyRemote && isNewPeer) {
                            String peerKey = p.ip + ":" + p.port;
                            lastAnnouncedPeerKey = peerKey;
                            lastAnnouncedPeerTime = System.currentTimeMillis();
                            Toast.makeText(MainActivity.this, "🔗 已与 " + p.deviceName + " 建立局域网连接", Toast.LENGTH_SHORT).show();
                        }
                        refreshTabList.run();
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        if (tvPeerStatus != null) {
                            tvPeerStatus.setText("读取设备书架失败: " + e.getMessage());
                        }
                    });
                }
            });
        };

        renderPeerChipsHolder[0] = () -> {
            if (layoutPeers == null) return;
            layoutPeers.removeAllViews();
            float density = getResources().getDisplayMetrics().density;

            for (LanTransferManager.PeerInfo p : discoveredPeers) {
                boolean active = selectedPeer[0] != null
                        && selectedPeer[0].ip.equals(p.ip)
                        && selectedPeer[0].port == p.port;
                com.google.android.material.button.MaterialButton chip =
                        new com.google.android.material.button.MaterialButton(MainActivity.this);
                boolean isWin = p.platform != null && p.platform.toLowerCase().contains("win");
                chip.setText(p.deviceName + " (" + p.ip + ")" + (active ? " ✓" : ""));
                chip.setIconResource(isWin ? R.drawable.ic_laptop_vector : R.drawable.ic_wifi_vector);
                chip.setIconSize(Math.round(15 * density));
                chip.setIconPadding(Math.round(6 * density));
                chip.setIconTint(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
                chip.setTextSize(12f);
                chip.setAllCaps(false);
                chip.setMinWidth(0);
                chip.setMinHeight(0);
                chip.setPadding(Math.round(12 * density), 0, Math.round(12 * density), 0);
                chip.setTextColor(android.graphics.Color.WHITE);
                chip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(active ? "#059669" : "#222634")));
                chip.setCornerRadius(Math.round(8 * density));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        Math.round(36 * density)
                );
                lp.rightMargin = Math.round(8 * density);
                chip.setLayoutParams(lp);
                chip.setInsetTop(0);
                chip.setInsetBottom(0);

                chip.setOnClickListener(v -> connectToPeerAction.accept(p, true));
                layoutPeers.addView(chip);
            }
        };

        activeLanIncomingPeerCallback = incomingPeer -> {
            boolean found = false;
            for (int i = 0; i < discoveredPeers.size(); i++) {
                if (discoveredPeers.get(i).ip.equals(incomingPeer.ip)) {
                    discoveredPeers.set(i, incomingPeer);
                    found = true;
                    break;
                }
            }
            if (!found) discoveredPeers.add(0, incomingPeer);
            if (!isTransferring[0] && (selectedPeer[0] == null || selectedPeer[0].ip.equals(incomingPeer.ip))) {
                connectToPeerAction.accept(incomingPeer, false);
            } else {
                renderPeerChipsHolder[0].run();
            }
        };

        dialog.setOnDismissListener(d -> {
            if (activeLanProgressView == tvTransferProgress) {
                activeLanProgressCard = null;
                activeLanProgressTitle = null;
                activeLanProgressPct = null;
                activeLanProgressBar = null;
                activeLanProgressView = null;
                activeLanRefreshCallback = null;
                activeLanIncomingPeerCallback = null;
            }
        });

        // 如果之前已经有对端设备连接了本手机，打开面板时直接恢复展示该连接
        if (currentConnectedPeer != null) {
            discoveredPeers.add(currentConnectedPeer);
            connectToPeerAction.accept(currentConnectedPeer, false);
        }

        Runnable triggerScanPeers = () -> {
            if (lanTransferManager == null) return;
            if (btnScanPeers != null) {
                btnScanPeers.setEnabled(false);
                btnScanPeers.setText("扫描中...");
            }
            if (tvPeerStatus != null && selectedPeer[0] == null) {
                tvPeerStatus.setText("正在扫描同一 Wi-Fi 下的 Windows 电脑与手机设备...");
            }
            lanExecutor.execute(() -> {
                List<LanTransferManager.PeerInfo> peers = lanTransferManager.discoverPeers();
                mainHandler.post(() -> {
                    if (btnScanPeers != null) {
                        btnScanPeers.setEnabled(true);
                        btnScanPeers.setText("扫描");
                    }
                    for (LanTransferManager.PeerInfo p : peers) {
                        boolean exists = false;
                        for (int i = 0; i < discoveredPeers.size(); i++) {
                            if (discoveredPeers.get(i).ip.equals(p.ip)) {
                                discoveredPeers.set(i, p);
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) discoveredPeers.add(p);
                    }
                    renderPeerChipsHolder[0].run();

                    if (discoveredPeers.isEmpty()) {
                        if (tvPeerStatus != null) {
                            tvPeerStatus.setText("暂未自动发现设备。请确认电脑端与手机连接同一 Wi-Fi，或直接输入电脑 IP 连接。");
                        }
                    } else if (selectedPeer[0] == null) {
                        connectToPeerAction.accept(discoveredPeers.get(0), true);
                    }
                });
            });
        };

        if (btnScanPeers != null) {
            btnScanPeers.setOnClickListener(v -> triggerScanPeers.run());
        }

        if (btnRefreshRemote != null) {
            btnRefreshRemote.setOnClickListener(v -> {
                if (selectedPeer[0] != null) {
                    LanTransferManager.PeerInfo p = selectedPeer[0];
                    btnRefreshRemote.setEnabled(false);
                    btnRefreshRemote.setText("刷新中...");
                    if (tvPeerStatus != null) {
                        tvPeerStatus.setText("🔄 正在从 " + p.deviceName + " 拉取最新漫画列表...");
                    }
                    lanExecutor.execute(() -> {
                        try {
                            List<LanTransferManager.RemoteComic> fetched = lanTransferManager.fetchRemoteComics(p.ip, p.port, false);
                            mainHandler.post(() -> {
                                btnRefreshRemote.setEnabled(true);
                                btnRefreshRemote.setText("刷新");
                                remoteComics.clear();
                                remoteComics.addAll(fetched);
                                p.comicCount = remoteComics.size();
                                if (renderPeerChipsHolder[0] != null) renderPeerChipsHolder[0].run();
                                if (tvPeerStatus != null) {
                                    tvPeerStatus.setText("已同步最新列表: " + p.deviceName + " (" + p.ip + ") · 共 " + remoteComics.size() + " 部漫画");
                                }
                                refreshTabList.run();
                                if (isOnlineShelfMode) {
                                    onlineShelfComics.clear();
                                    for (LanTransferManager.RemoteComic rc : remoteComics) {
                                        ComicItem item = buildOnlineComicItem(p, rc);
                                        if (item != null) onlineShelfComics.add(item);
                                    }
                                    sortComics();
                                    filterComics(etSearch.getText().toString());
                                    updateShelfStats();
                                }
                                Toast.makeText(MainActivity.this, "已刷新 " + p.deviceName + " 最新书架 (共 " + remoteComics.size() + " 部)", Toast.LENGTH_SHORT).show();
                            });
                        } catch (Exception e) {
                            mainHandler.post(() -> {
                                btnRefreshRemote.setEnabled(true);
                                btnRefreshRemote.setText("刷新");
                                triggerScanPeers.run();
                            });
                        }
                    });
                } else {
                    triggerScanPeers.run();
                    Toast.makeText(MainActivity.this, "正在重新扫描局域网设备...", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnConnectIp != null) {
            btnConnectIp.setOnClickListener(v -> {
                String host = etManualIp != null ? etManualIp.getText().toString().trim() : "";
                if (host.isEmpty()) {
                    Toast.makeText(this, "请输入电脑或手机的局域网 IP 地址", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (tvPeerStatus != null) {
                    tvPeerStatus.setText("正在连接 " + host + " ...");
                }
                lanExecutor.execute(() -> {
                    LanTransferManager.PeerInfo peer = lanTransferManager.pingPeer(host, LanTransferManager.DEFAULT_HTTP_PORT, 3500);
                    mainHandler.post(() -> {
                        if (peer != null) {
                            boolean found = false;
                            for (int i = 0; i < discoveredPeers.size(); i++) {
                                if (discoveredPeers.get(i).ip.equals(peer.ip)) {
                                    discoveredPeers.set(i, peer);
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) discoveredPeers.add(0, peer);
                            connectToPeerAction.accept(peer, true);
                        } else {
                            if (tvPeerStatus != null) {
                                tvPeerStatus.setText("❌ 无法连接到 " + host + "，请确认处于同一 Wi-Fi 且电脑已打开阅读器");
                            }
                        }
                    });
                });
            });
        }

        if (btnTabPull != null) {
            btnTabPull.setOnClickListener(v -> {
                isPullTab[0] = true;
                selectAdapter[0].setEntries(new ArrayList<>(), null);
                refreshTabList.run();
            });
        }
        if (btnTabPush != null) {
            btnTabPush.setOnClickListener(v -> {
                isPullTab[0] = false;
                selectAdapter[0].setEntries(new ArrayList<>(), null);
                refreshTabList.run();
            });
        }
        if (btnSelectAll != null) {
            btnSelectAll.setOnClickListener(v -> selectAdapter[0].toggleSelectAll());
        }
        if (btnFilterMissing != null) {
            btnFilterMissing.setOnClickListener(v -> {
                onlyMissingFilter[0] = !onlyMissingFilter[0];
                refreshTabList.run();
            });
        }
        if (etSearchComic != null) {
            etSearchComic.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String text = s != null ? s.toString() : "";
                    searchKeyword[0] = text;
                    if (btnClearSearch != null) {
                        btnClearSearch.setVisibility(text.trim().isEmpty() ? View.GONE : View.VISIBLE);
                    }
                    refreshTabList.run();
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }
        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(v -> {
                if (etSearchComic != null) {
                    etSearchComic.setText("");
                }
            });
        }

        if (btnExecute != null) {
            btnExecute.setOnClickListener(v -> {
                if (selectedPeer[0] == null || isTransferring[0]) return;
                Set<String> selectedIds = selectAdapter[0].getSelectedIds();
                if (selectedIds.isEmpty()) return;

                isTransferring[0] = true;
                updateFooterAction.run();
                final LanTransferManager.PeerInfo targetPeer = selectedPeer[0];

                if (isPullTab[0]) {
                    List<String> idsToPull = new ArrayList<>(selectedIds);
                    updateActiveLanDialogProgress("📥 正在准备接收...", 0, "正在从 " + targetPeer.deviceName + " 下载 " + idsToPull.size() + " 部漫画...");
                    lanExecutor.execute(() -> {
                        try {
                            LanTransferManager.TransferResult res = lanTransferManager.pullComicsFromPeer(targetPeer.ip, targetPeer.port, idsToPull);
                            mainHandler.post(() -> {
                                isTransferring[0] = false;
                                selectAdapter[0].setEntries(new ArrayList<>(), null);
                                refreshTabList.run();
                                String summaryMsg = res.skippedCount > 0
                                        ? ("新增下载 " + res.transferredCount + " 部，已跳过 " + res.skippedCount + " 部本机已有漫画")
                                        : ("已成功下载 " + res.transferredCount + " 部漫画并加入书架");
                                updateImportProgress("✅ 下载任务完成", 100, summaryMsg);
                                updateActiveLanDialogProgress("✅ 下载任务完成", 100, summaryMsg);
                                hideImportProgress(2400);
                                hideActiveLanDialogProgress(3000);

                                if (res.skippedCount > 0 && !isFinishing() && !isDestroyed()) {
                                    String preview = buildSkippedPreview(res.skippedTitles);
                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("本机已拥有提示")
                                            .setMessage("有 " + res.skippedCount + " 部漫画本机已拥有，已自动跳过不重复接收：\n"
                                                    + preview
                                                    + "\n\n本次成功接收新漫画：" + res.transferredCount + " 部。")
                                            .setPositiveButton("知道了", null)
                                            .show();
                                } else {
                                    Toast.makeText(MainActivity.this, "📥 " + summaryMsg, Toast.LENGTH_LONG).show();
                                }
                            });
                        } catch (Exception e) {
                            mainHandler.post(() -> {
                                isTransferring[0] = false;
                                updateFooterAction.run();
                                updateActiveLanDialogProgress("❌ 下载出错", 0, e.getMessage());
                                hideImportProgress(1500);
                            });
                        }
                    });
                } else {
                    List<ComicItem> toPush = new ArrayList<>();
                    for (ComicItem c : allComics) {
                        if (!c.isMissing()) {
                            String cid = c.getId() != null ? c.getId() : c.getTitle();
                            if (selectedIds.contains(cid)) {
                                toPush.add(c);
                            }
                        }
                    }
                    updateActiveLanDialogProgress("📤 正在准备发送...", 0, "正在向 " + targetPeer.deviceName + " 发送 " + toPush.size() + " 部漫画...");
                    lanExecutor.execute(() -> {
                        try {
                            LanTransferManager.TransferResult res = lanTransferManager.pushComicsToPeer(targetPeer.ip, targetPeer.port, toPush);
                            List<LanTransferManager.RemoteComic> latestRemote = null;
                            try {
                                latestRemote = lanTransferManager.fetchRemoteComics(targetPeer.ip, targetPeer.port, false);
                            } catch (Exception ignored) {}
                            final List<LanTransferManager.RemoteComic> finalLatestRemote = latestRemote;
                            mainHandler.post(() -> {
                                isTransferring[0] = false;
                                if (finalLatestRemote != null) {
                                    remoteComics.clear();
                                    remoteComics.addAll(finalLatestRemote);
                                }
                                selectAdapter[0].setEntries(new ArrayList<>(), null);
                                refreshTabList.run();

                                String summaryMsg = res.skippedCount > 0
                                        ? ("成功发送 " + res.transferredCount + " 部，已跳过 " + res.skippedCount + " 部对方已有漫画")
                                        : ("已成功发送 " + res.transferredCount + " 部漫画到 " + targetPeer.deviceName);
                                updateImportProgress("✅ 发送任务完成", 100, summaryMsg);
                                updateActiveLanDialogProgress("✅ 发送任务完成", 100, summaryMsg);
                                hideImportProgress(2400);
                                hideActiveLanDialogProgress(3000);

                                if (res.skippedCount > 0 && !isFinishing() && !isDestroyed()) {
                                    String preview = buildSkippedPreview(res.skippedTitles);
                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("对方已拥有提示")
                                            .setMessage("对方设备（" + targetPeer.deviceName + "）已拥有以下 " + res.skippedCount + " 部漫画，已自动跳过不传送：\n"
                                                    + preview
                                                    + "\n\n本次实际传送新漫画：" + res.transferredCount + " 部。")
                                            .setPositiveButton("知道了", null)
                                            .show();
                                } else {
                                    Toast.makeText(MainActivity.this, "📤 " + summaryMsg, Toast.LENGTH_LONG).show();
                                }
                            });
                        } catch (Exception e) {
                            mainHandler.post(() -> {
                                isTransferring[0] = false;
                                updateFooterAction.run();
                                updateActiveLanDialogProgress("❌ 发送出错", 0, e.getMessage());
                                hideImportProgress(1500);
                            });
                        }
                    });
                }
            });
        }

        refreshTabList.run();
        dialog.show();
        triggerScanPeers.run();
    }

    private void openFolderChooser() {
        FolderPickerDialog dialog = new FolderPickerDialog(
                this,
                this::scanComicFolder,
                this::requestSafForFolder
        );
        activeFolderDialog = dialog;
        dialog.show();
    }

    private void refreshLastFolder() {
        if (isOnlineShelfMode && currentConnectedPeer != null) {
            enterOnlineShelfMode(currentConnectedPeer, null);
            return;
        }
        String savedRoot = ShelfStorage.getSavedRootPath(this);
        if (savedRoot != null && !savedRoot.isEmpty()) {
            scanComicFolder(savedRoot);
        } else {
            openFolderChooser();
        }
    }

    private void requestSafForFolder(String folderPath) {
        pendingSafPath = folderPath;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        );
        safLauncher.launch(intent);
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    100
            );
        }
    }

    // 后台实时进度条展示
    private void showImportProgress(String status, int percent, String detail) {
        mainHandler.post(() -> {
            if (layoutImportProgress != null) {
                layoutImportProgress.setVisibility(View.VISIBLE);
                updateImportProgress(status, percent, detail);
            }
        });
    }

    private void updateImportProgress(String status, int percent, String detail) {
        mainHandler.post(() -> {
            if (tvImportStatus != null) tvImportStatus.setText(status);
            if (tvImportPercent != null) tvImportPercent.setText(Math.min(100, Math.max(0, percent)) + "%");
            if (importProgressBar != null) importProgressBar.setProgress(Math.min(100, Math.max(0, percent)));
            if (tvImportDetail != null) tvImportDetail.setText(detail);
        });
    }

    private void hideImportProgress(long delayMs) {
        mainHandler.postDelayed(() -> {
            if (layoutImportProgress != null) {
                layoutImportProgress.setVisibility(View.GONE);
            }
        }, delayMs);
    }

    // 核心：后台异步非阻塞导入，边看漫画边后台导入，重复漫画自动静默跳过
    public void scanComicFolder(String folderPath) {
        showImportProgress("正在分析目录结构...", 0, "正在读取: " + folderPath);

        scanExecutor.execute(() -> {
            File rootFile = new File(folderPath);

            // 构建已存在的去重库 (标题与文件夹绝对路径)
            Set<String> existingNames = new HashSet<>();
            Set<String> existingPaths = new HashSet<>();
            for (ComicItem c : allComics) {
                if (c.getTitle() != null) existingNames.add(c.getTitle().trim().toLowerCase());
                if (c.getFolderPath() != null) existingPaths.add(c.getFolderPath().trim().toLowerCase());
            }

            int newlyCount = 0;

            // 1. 优先普通文件系统扫描
            if (rootFile.exists() && rootFile.canRead()) {
                File[] subFolders = rootFile.listFiles(File::isDirectory);

                if (subFolders != null && subFolders.length > 0) {
                    Arrays.sort(subFolders, (a, b) -> naturalOrderComparator.compare(a.getName(), b.getName()));

                    // 静默跳过已存在漫画，筛选出未导入的候选子目录
                    List<File> newSubFolders = new ArrayList<>();
                    for (File sub : subFolders) {
                        String nameKey = sub.getName().trim().toLowerCase();
                        String pathKey = sub.getAbsolutePath().trim().toLowerCase();
                        if (!existingNames.contains(nameKey) && !existingPaths.contains(pathKey)) {
                            newSubFolders.add(sub);
                        }
                    }

                    int total = newSubFolders.size();
                    if (total == 0) {
                        updateImportProgress("扫描完成", 100, "所选文件夹内漫画均已在书架中，已自动跳过重复内容");
                        hideImportProgress(1600);
                        return;
                    }

                    for (int i = 0; i < total; i++) {
                        File sub = newSubFolders.get(i);
                        int pct = Math.round(((float) i / total) * 100);
                        updateImportProgress("正在导入 (" + (i + 1) + "/" + total + ")", pct, "正在解析：《" + sub.getName() + "》...");

                        ComicItem comic = parseNormalSingleComic(sub);
                        if (comic != null) {
                            allComics.add(comic);
                            existingNames.add(comic.getTitle().trim().toLowerCase());
                            existingPaths.add(comic.getFolderPath().trim().toLowerCase());
                            newlyCount++;

                            // 增量持久化
                            ShelfStorage.saveShelf(this, allComics, folderPath);

                            mainHandler.post(() -> {
                                sortComics();
                                filterComics(etSearch.getText().toString());
                                updateShelfStats();
                            });
                        }

                        try {
                            Thread.sleep(16); // 让渡线程，保证主界面操作完全无阻塞
                        } catch (InterruptedException ignored) {}
                    }
                } else {
                    // 没有子目录，检查自身是否就是单部漫画切片文件夹
                    String rootName = rootFile.getName().trim().toLowerCase();
                    String rootPath = rootFile.getAbsolutePath().trim().toLowerCase();
                    if (!existingNames.contains(rootName) && !existingPaths.contains(rootPath)) {
                        updateImportProgress("正在解析单本漫画...", 50, "正在读取切片: " + rootFile.getName());
                        ComicItem comic = parseNormalSingleComic(rootFile);
                        if (comic != null) {
                            allComics.add(comic);
                            newlyCount++;
                            ShelfStorage.saveShelf(this, allComics, folderPath);
                            mainHandler.post(() -> {
                                sortComics();
                                filterComics(etSearch.getText().toString());
                                updateShelfStats();
                            });
                        }
                    } else {
                        updateImportProgress("扫描完成", 100, "《" + rootFile.getName() + "》已在书架中，已自动跳过");
                        hideImportProgress(1600);
                        return;
                    }
                }
            } else {
                // 2. SAF 授权访问分支 (兼容特殊权限受限目录)
                DocumentFile df = null;
                for (Uri uri : getContentResolver().getPersistedUriPermissions().stream().map(p -> p.getUri()).toArray(Uri[]::new)) {
                    DocumentFile temp = DocumentFile.fromTreeUri(this, uri);
                    if (temp != null && temp.canRead()) {
                        df = temp;
                        break;
                    }
                }

                if (df != null) {
                    DocumentFile[] subDocs = df.listFiles();
                    List<DocumentFile> newDocs = new ArrayList<>();
                    if (subDocs != null) {
                        for (DocumentFile d : subDocs) {
                            if (d.isDirectory()) {
                                String dName = d.getName() != null ? d.getName().trim().toLowerCase() : "";
                                if (!existingNames.contains(dName)) {
                                    newDocs.add(d);
                                }
                            }
                        }
                    }

                    int total = newDocs.size();
                    if (total == 0) {
                        updateImportProgress("扫描完成", 100, "所有漫画均已在书架中，已自动跳过重复内容");
                        hideImportProgress(1600);
                        return;
                    }

                    for (int i = 0; i < total; i++) {
                        DocumentFile sub = newDocs.get(i);
                        int pct = Math.round(((float) i / total) * 100);
                        updateImportProgress("正在导入 (" + (i + 1) + "/" + total + ")", pct, "正在解析：《" + sub.getName() + "》...");

                        ComicItem comic = parseSafSingleComic(sub, folderPath);
                        if (comic != null) {
                            allComics.add(comic);
                            existingNames.add(comic.getTitle().trim().toLowerCase());
                            newlyCount++;

                            ShelfStorage.saveShelf(this, allComics, folderPath);

                            mainHandler.post(() -> {
                                sortComics();
                                filterComics(etSearch.getText().toString());
                                updateShelfStats();
                            });
                        }

                        try {
                            Thread.sleep(16);
                        } catch (InterruptedException ignored) {}
                    }
                }
            }

            // 完成处理：平滑隐藏进度条，无弹窗打扰正在看漫画的用户
            int finalNewlyCount = newlyCount;
            updateImportProgress("导入完成", 100, finalNewlyCount > 0 ? ("已成功加入 " + finalNewlyCount + " 部新漫画") : "所有漫画均已存在");
            hideImportProgress(1500);

            mainHandler.post(() -> {
                verifyAllComicsExistence();
                sortComics();
                filterComics(etSearch.getText().toString());
                updateShelfStats();
            });
        });
    }

    private ComicItem parseNormalSingleComic(File folder) {
        File[] files = folder.listFiles(file -> file.isFile() && isImageFile(file.getName()));
        if (files == null || files.length == 0) return null;

        Arrays.sort(files, (a, b) -> naturalOrderComparator.compare(a.getName(), b.getName()));

        List<String> pagePaths = new ArrayList<>();
        for (File img : files) {
            pagePaths.add(img.getAbsolutePath());
        }

        String customCover = ShelfStorage.getCustomCover(this, folder.getName(), folder.getName());
        String coverPath = !customCover.isEmpty() ? customCover : pagePaths.get(0);
        long modTime = folder.lastModified();
        ComicItem item = new ComicItem(
                folder.getName(),
                folder.getName(),
                folder.getAbsolutePath(),
                coverPath,
                pagePaths,
                0,
                0,
                modTime > 0 ? modTime : System.currentTimeMillis()
        );
        item.setCustomCoverPath(customCover);
        return item;
    }

    private ComicItem parseSafSingleComic(DocumentFile folder, String folderPath) {
        DocumentFile[] files = folder.listFiles();
        if (files == null || files.length == 0) return null;

        List<DocumentFile> imageFiles = new ArrayList<>();
        for (DocumentFile f : files) {
            if (f.isFile() && isImageFile(f.getName())) {
                imageFiles.add(f);
            }
        }

        if (imageFiles.isEmpty()) return null;

        imageFiles.sort((a, b) -> naturalOrderComparator.compare(
                a.getName() != null ? a.getName() : "",
                b.getName() != null ? b.getName() : ""
        ));

        List<String> pageUris = new ArrayList<>();
        for (DocumentFile img : imageFiles) {
            pageUris.add(img.getUri().toString());
        }

        String title = folder.getName();
        if (title == null || title.isEmpty()) {
            title = new File(folderPath).getName();
        }
        String customCover = ShelfStorage.getCustomCover(this, title, title);
        String coverUri = !customCover.isEmpty() ? customCover : pageUris.get(0);

        long modTime = folder.lastModified();
        ComicItem item = new ComicItem(
                title,
                title,
                folderPath,
                coverUri,
                pageUris,
                0,
                0,
                modTime > 0 ? modTime : System.currentTimeMillis()
        );
        item.setCustomCoverPath(customCover);
        return item;
    }

    private boolean isImageFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".bmp") || lower.endsWith(".gif") || lower.endsWith(".avif");
    }

    private boolean fuzzyMatch(String text, String pattern) {
        if (pattern == null || pattern.isEmpty()) return true;
        if (text == null || text.isEmpty()) return false;

        String cleanText = text.toLowerCase();
        String cleanPattern = pattern.trim().toLowerCase();

        String[] keywords = cleanPattern.split("\\s+");
        if (keywords.length > 1) {
            for (String kw : keywords) {
                if (!kw.isEmpty() && !fuzzyMatch(text, kw)) return false;
            }
            return true;
        }

        String kw = keywords[0];
        if (kw.isEmpty()) return true;

        if (cleanText.contains(kw)) return true;

        String strippedText = cleanText.replaceAll("[\\s\\-_()\\[\\]【】（）.,!?]", "");
        String strippedKw = kw.replaceAll("[\\s\\-_()\\[\\]【】（）.,!?]", "");
        if (!strippedKw.isEmpty() && strippedText.contains(strippedKw)) return true;

        int tIdx = 0;
        for (int i = 0; i < kw.length(); i++) {
            char c = kw.charAt(i);
            tIdx = cleanText.indexOf(c, tIdx);
            if (tIdx == -1) return false;
            tIdx++;
        }
        return true;
    }

    private void filterComics(String keyword) {
        displayComics.clear();
        String kw = keyword != null ? keyword.trim() : "";
        List<ComicItem> sourceList = isOnlineShelfMode ? onlineShelfComics : allComics;
        for (ComicItem item : sourceList) {
            if (!isOnlineShelfMode && item.isMissing()) {
                continue; // 丢失/移走的书籍在书架中自动隐藏，保留后台数据
            }
            if (kw.isEmpty() || fuzzyMatch(item.getTitle(), kw)) {
                displayComics.add(item);
            }
        }
        adapter.notifyDataSetChanged();
        layoutEmpty.setVisibility(displayComics.isEmpty() ? View.VISIBLE : View.GONE);
    }
}

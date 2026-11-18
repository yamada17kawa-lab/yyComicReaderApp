package com.comic.reader;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
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
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private long connectCooldownUntil = 0;

    public static void applyAppLanguage(Context context, String langCode) {
        if (langCode == null || langCode.isEmpty()) langCode = "zh";
        java.util.Locale locale;
        if ("en".equalsIgnoreCase(langCode)) {
            locale = java.util.Locale.ENGLISH;
        } else if ("ja".equalsIgnoreCase(langCode)) {
            locale = java.util.Locale.JAPANESE;
        } else {
            locale = java.util.Locale.SIMPLIFIED_CHINESE;
        }
        java.util.Locale.setDefault(locale);
        android.content.res.Resources res = context.getResources();
        android.content.res.Configuration config = res.getConfiguration();
        config.setLocale(locale);
        res.updateConfiguration(config, res.getDisplayMetrics());
    }

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
    private final ExecutorService deleteExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean isBatchDeleting = false;
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
    private Runnable activeLanDisconnectCallback;
    private java.util.function.Consumer<LanTransferManager.PeerInfo> activeLanIncomingPeerCallback;
    private ComicItem pendingCoverComic;

    // 异步任务/操作对方设备顺时针雷达扫描进度组件 (置于书架回到顶部按钮正上方)
    private View layoutRadarProgress;
    private RadarProgressView radarProgressView;
    private TextView tvRadarLabel;

    private final ActivityResultLauncher<Intent> customCoverImageLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && pendingCoverComic != null) {
                    Uri imgUri = result.getData().getData();
                    if (imgUri != null) {
                        if (isOnlineShelfMode && currentConnectedPeer != null) {
                            showRadarProgress("正在上传远端封面", 35);
                            String peerIp = currentConnectedPeer.ip;
                            int peerPort = currentConnectedPeer.port;
                            ComicItem targetComic = pendingCoverComic;
                            lanExecutor.execute(() -> {
                                try (java.io.InputStream is = getContentResolver().openInputStream(imgUri)) {
                                    if (is != null) {
                                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                                        byte[] buf = new byte[8192];
                                        int len;
                                        while ((len = is.read(buf)) != -1) {
                                            bos.write(buf, 0, len);
                                        }
                                        byte[] bytes = bos.toByteArray();
                                        boolean ok = lanTransferManager.setRemoteComicCoverFile(peerIp, peerPort, targetComic.getId(), bytes, "custom_cover.jpg");
                                        mainHandler.post(() -> {
                                            if (ok) {
                                                targetComic.setCoverPath("http://" + peerIp + ":" + peerPort + "/api/comic-cover?id=" + targetComic.getId() + "&_t=" + System.currentTimeMillis());
                                                adapter.notifyDataSetChanged();
                                                completeRadarProgress("✅ 远端封面上传成功");
                                                Toast.makeText(MainActivity.this, "🖼️ 已成功为远端《" + targetComic.getTitle() + "》更新自定义封面", Toast.LENGTH_SHORT).show();
                                            } else {
                                                completeRadarProgress("⚠️ 远端封面上传失败");
                                                Toast.makeText(MainActivity.this, "⚠️ 上传远端封面失败，请重试", Toast.LENGTH_SHORT).show();
                                            }
                                        });
                                    }
                                } catch (Exception e) {
                                    mainHandler.post(() -> {
                                        completeRadarProgress("⚠️ 上传失败");
                                        Toast.makeText(MainActivity.this, "读取封面文件失败", Toast.LENGTH_SHORT).show();
                                    });
                                }
                            });
                            return;
                        }

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
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppThemeAndLocaleHelper.attachBaseContext(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppThemeAndLocaleHelper.applyAppTheme(this);
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
                    if (position >= 0 && position < displayComics.size()) {
                        ComicItem item = displayComics.get(position);
                        if (isOnlineShelfMode) {
                            confirmRemoveOnlineComic(item);
                        } else {
                            confirmRemoveComic(item);
                        }
                    }
                }
        );
        adapter.setCoverClickListener(position -> {
            if (position >= 0 && position < displayComics.size()) {
                showCustomCoverDialog(displayComics.get(position));
            }
        });
        adapter.setRenameClickListener(position -> {
            if (position >= 0 && position < displayComics.size()) {
                showRenameComicDialog(displayComics.get(position));
            }
        });
        recyclerView.setAdapter(adapter);

        // 异步任务顺时针雷达扫描进度组件初始化
        layoutRadarProgress = findViewById(R.id.layout_radar_progress);
        radarProgressView = findViewById(R.id.radar_progress_view);
        tvRadarLabel = findViewById(R.id.tv_radar_label);
        if (layoutRadarProgress != null) {
            layoutRadarProgress.setOnClickListener(v -> {
                if (currentConnectedPeer != null) {
                    showLanTransferDialog();
                } else {
                    Toast.makeText(MainActivity.this, "当前后台任务正在进行中...", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 书架一键回到顶部按钮逻辑
        View fabShelfScrollTop = findViewById(R.id.fab_shelf_scroll_top);
        com.google.android.material.appbar.AppBarLayout appBarLayout = findViewById(R.id.app_bar_layout);
        if (fabShelfScrollTop != null) {
            fabShelfScrollTop.setOnClickListener(v -> {
                recyclerView.scrollToPosition(0);
                if (appBarLayout != null) {
                    appBarLayout.setExpanded(true, true);
                }
                fabShelfScrollTop.setVisibility(View.GONE);
            });
        }
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                super.onScrolled(rv, dx, dy);
                if (fabShelfScrollTop != null) {
                    RecyclerView.LayoutManager lm = rv.getLayoutManager();
                    if (lm instanceof GridLayoutManager) {
                        int first = ((GridLayoutManager) lm).findFirstVisibleItemPosition();
                        fabShelfScrollTop.setVisibility(first > 3 ? View.VISIBLE : View.GONE);
                    } else {
                        fabShelfScrollTop.setVisibility(rv.canScrollVertically(-1) ? View.VISIBLE : View.GONE);
                    }
                }
            }
        });

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
                    updateRadarProgress(status, percent);
                    if (status != null && status.startsWith("⏩")) {
                        hideImportProgress(2000);
                        hideActiveLanDialogProgress(2400);
                        hideRadarProgress(2000);
                    }
                },
                this::onLanPeerConnected,
                this::onLanBatchTransferComplete
        );
        lanTransferManager.setOnPairRequestListener(req -> {
            mainHandler.post(() -> showIncomingPairRequestDialog(req));
        });
        lanTransferManager.setOnPeerDisconnectedListener((fromId, fromName, fromIp) -> {
            handlePeerDisconnected(fromId, fromName, fromIp, true);
        });
        lanTransferManager.setOnRemoteOperationListener(new LanTransferManager.OnRemoteOperationListener() {
            @Override
            public boolean onRenameComic(String id, String title, String newTitle) {
                ComicItem target = findLocalComic(id, title);
                if (target == null) return false;
                String err = ShelfStorage.renameComic(MainActivity.this, target, newTitle, allComics);
                if (err == null) {
                    mainHandler.post(() -> {
                        sortComics();
                        filterComics(etSearch != null ? etSearch.getText().toString() : "");
                        updateShelfStats();
                    });
                    return true;
                }
                return false;
            }

            @Override
            public boolean onSetComicCoverPage(String id, int pageIndex) {
                ComicItem target = findLocalComic(id, null);
                if (target == null || target.getPagePaths() == null || pageIndex < 0 || pageIndex >= target.getPagePaths().size()) return false;
                String page = target.getPagePaths().get(pageIndex);
                ShelfStorage.saveCustomCover(MainActivity.this, target, page);
                ShelfStorage.saveShelf(MainActivity.this, allComics, ShelfStorage.getSavedRootPath(MainActivity.this));
                mainHandler.post(() -> adapter.notifyDataSetChanged());
                return true;
            }

            @Override
            public boolean onResetComicCover(String id) {
                ComicItem target = findLocalComic(id, null);
                if (target == null) return false;
                ShelfStorage.cleanupCustomCoverFiles(MainActivity.this, target.getId());
                ShelfStorage.saveCustomCover(MainActivity.this, target, "");
                ShelfStorage.saveShelf(MainActivity.this, allComics, ShelfStorage.getSavedRootPath(MainActivity.this));
                mainHandler.post(() -> adapter.notifyDataSetChanged());
                return true;
            }

            @Override
            public boolean onSetComicCoverFile(String id, byte[] imageBytes, String ext) {
                ComicItem target = findLocalComic(id, null);
                if (target == null || imageBytes == null || imageBytes.length == 0) return false;
                try {
                    File coversDir = new File(getExternalFilesDir(null), "custom_covers");
                    if (!coversDir.exists()) coversDir.mkdirs();
                    File dest = new File(coversDir, "cover_" + id + "_" + System.currentTimeMillis() + ext);
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                        fos.write(imageBytes);
                    }
                    ShelfStorage.saveCustomCover(MainActivity.this, target, dest.getAbsolutePath());
                    ShelfStorage.saveShelf(MainActivity.this, allComics, ShelfStorage.getSavedRootPath(MainActivity.this));
                    mainHandler.post(() -> adapter.notifyDataSetChanged());
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public boolean onDeleteComic(String id, String title) {
                ComicItem target = findLocalComic(id, title);
                if (target == null) return false;
                mainHandler.post(() -> {
                    allComics.remove(target);
                    displayComics.remove(target);
                    filterComics(etSearch != null ? etSearch.getText().toString() : "");
                    updateShelfStats();
                });
                ShelfStorage.deleteComicPhysicalFiles(MainActivity.this, target);
                ShelfStorage.clearComicHistory(MainActivity.this, target);
                ShelfStorage.saveShelf(MainActivity.this, allComics, ShelfStorage.getSavedRootPath(MainActivity.this));
                return true;
            }

            @Override
            public void onRecordRead(String id, String title, int page, boolean inc) {
                ComicItem target = findLocalComic(id, title);
                if (target != null) {
                    if (inc) {
                        target.setReadCount(target.getReadCount() + 1);
                        target.setLastReadTime(System.currentTimeMillis());
                    }
                    if (page >= 1) {
                        target.setLastReadPage(page);
                    }
                    ShelfStorage.saveShelf(MainActivity.this, allComics, ShelfStorage.getSavedRootPath(MainActivity.this));
                    mainHandler.post(() -> adapter.notifyDataSetChanged());
                }
            }
        });
        lanTransferManager.setOnUpdateReceivedListener(info -> {
            mainHandler.post(() -> {
                if (info != null) {
                    Toast.makeText(MainActivity.this, "🚀 收到电脑端推送的更新安装包 (v" + info.versionName + ")", Toast.LENGTH_SHORT).show();
                }
                ApkUpdateManager.showUpdateDialog(MainActivity.this, info);
            });
        });
        lanTransferManager.start();
    }

    private ComicItem findLocalComic(String id, String title) {
        if (id != null && !id.isEmpty()) {
            for (ComicItem c : allComics) {
                if (id.equals(c.getId()) || id.equals(c.getTitle())) return c;
            }
        }
        if (title != null && !title.isEmpty()) {
            for (ComicItem c : allComics) {
                if (title.equals(c.getTitle())) return c;
            }
        }
        return null;
    }

    private void showRadarProgress(String label, int percent) {
        mainHandler.post(() -> {
            if (layoutRadarProgress != null) {
                layoutRadarProgress.setVisibility(View.VISIBLE);
                layoutRadarProgress.setAlpha(1.0f);
            }
            if (tvRadarLabel != null && label != null) {
                tvRadarLabel.setText(label);
            }
            if (radarProgressView != null) {
                radarProgressView.setProgress(percent / 100f);
            }
        });
    }

    private void updateRadarProgress(String label, int percent) {
        mainHandler.post(() -> {
            if (layoutRadarProgress != null && layoutRadarProgress.getVisibility() != View.VISIBLE) {
                layoutRadarProgress.setVisibility(View.VISIBLE);
                layoutRadarProgress.setAlpha(1.0f);
            }
            if (tvRadarLabel != null && label != null) {
                tvRadarLabel.setText(label);
            }
            if (radarProgressView != null) {
                radarProgressView.setProgress(percent / 100f);
            }
        });
    }

    private void completeRadarProgress(String finishLabel) {
        mainHandler.post(() -> {
            if (tvRadarLabel != null && finishLabel != null) {
                tvRadarLabel.setText(finishLabel);
            }
            if (radarProgressView != null) {
                radarProgressView.setProgress(1.0f);
            }
            hideRadarProgress(1800);
        });
    }

    private void hideRadarProgress(long delayMs) {
        mainHandler.postDelayed(() -> {
            if (layoutRadarProgress != null) {
                layoutRadarProgress.animate()
                        .alpha(0f)
                        .setDuration(350)
                        .withEndAction(() -> {
                            if (layoutRadarProgress != null) {
                                layoutRadarProgress.setVisibility(View.GONE);
                                layoutRadarProgress.setAlpha(1f);
                            }
                        })
                        .start();
            }
        }, delayMs);
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

        ComicItem item = new ComicItem(
                rc.id,
                rc.title,
                baseUrl,
                coverUrl,
                pageUrls,
                readCount,
                lastReadTime,
                importTime
        );
        item.setLastReadPage(rc.lastReadPage > 0 ? rc.lastReadPage : 1);
        return item;
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
        if (lanTransferManager != null && lanTransferManager.isSelf(peer)) return;
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

            // 如果对方不是 Android (例如 Windows 端)，且处于连接状态，主动检测对方是否有可更新的安卓客户端
            if (peer.platform != null && !peer.platform.toLowerCase().contains("android")) {
                ApkUpdateManager.checkRemoteUpdateAsync(this, peer.ip, peer.port, (info, hasUpdate) -> {
                    if (hasUpdate) {
                        ApkUpdateManager.showUpdateDialog(MainActivity.this, info);
                    }
                });
            }
        });
    }

    private void handlePeerDisconnected(String fromId, String fromName, String fromIp, boolean showToast) {
        mainHandler.post(() -> {
            boolean wasConnected = currentConnectedPeer != null;
            String name = currentConnectedPeer != null ? currentConnectedPeer.deviceName : (fromName != null && !fromName.isEmpty() ? fromName : fromIp);
            currentConnectedPeer = null;

            // 核心：若正处于在线浏览对方书架状态，立即退出并返回本地离线书架！
            if (isOnlineShelfMode) {
                exitOnlineShelfMode();
            }

            updateMainHeaderLanButtonState(null);
            updateOnlineShelfBanner();

            if (activeLanDisconnectCallback != null) {
                activeLanDisconnectCallback.run();
            }

            if (showToast && wasConnected) {
                Toast.makeText(MainActivity.this, "对方设备【" + name + "】已断开局域网连接，已自动返回本地书架", Toast.LENGTH_SHORT).show();
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ApkUpdateManager.REQUEST_INSTALL_PERM_CODE) {
            // onResume pending install check removed to prevent install loops
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (lanTransferManager != null) {
            lanTransferManager.stop();
        }
        try {
            deleteExecutor.shutdown();
        } catch (Throwable ignored) {}
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
        if (isOnlineShelfMode && currentConnectedPeer == null) {
            exitOnlineShelfMode();
        }
        updatePermissionBanner();
        ApkUpdateManager.checkPendingInstallOnResume(this);

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
        } else if (isOnlineShelfMode && currentConnectedPeer != null) {
            // 在线书架模式：后台静默拉取电脑端最新阅读进度与状态
            lanExecutor.execute(() -> {
                try {
                    List<LanTransferManager.RemoteComic> fetched = lanTransferManager.fetchRemoteComics(currentConnectedPeer.ip, currentConnectedPeer.port, false);
                    if (fetched != null && !fetched.isEmpty()) {
                        mainHandler.post(() -> {
                            if (isOnlineShelfMode && currentConnectedPeer != null) {
                                onlineShelfComics.clear();
                                for (LanTransferManager.RemoteComic rc : fetched) {
                                    ComicItem item = buildOnlineComicItem(currentConnectedPeer, rc);
                                    if (item != null) onlineShelfComics.add(item);
                                }
                                sortComics();
                                filterComics(etSearch != null ? etSearch.getText().toString() : "");
                                updateShelfStats();
                            }
                        });
                    }
                } catch (Exception ignored) {}
            });
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
        if (item == null) return;
        new AlertDialog.Builder(this)
                .setTitle("彻底删除漫画")
                .setMessage("确定要彻底删除《" + item.getTitle() + "》吗？\n\n⚠️ 警告：此操作将同时：\n1. 从书架移除该漫画\n2. 清除已读进度与阅读次数记录\n3. 永久删除手机存储上的漫画物理文件夹及图片！")
                .setPositiveButton("彻底删除", (dialog, which) -> {
                    synchronized (allComics) {
                        allComics.remove(item);
                    }
                    displayComics.remove(item);
                    filterComics(etSearch != null ? etSearch.getText().toString() : "");
                    updateShelfStats();
                    Toast.makeText(this, "🗑️ 正在后台清理《" + item.getTitle() + "》物理文件...", Toast.LENGTH_SHORT).show();

                    deleteExecutor.execute(() -> {
                        try {
                            ShelfStorage.deleteComicPhysicalFiles(MainActivity.this, item);
                            ShelfStorage.clearComicHistory(MainActivity.this, item);
                            ShelfStorage.saveShelf(MainActivity.this, allComics, ShelfStorage.getSavedRootPath(MainActivity.this));
                        } catch (Throwable t) {
                            Log.e("MainActivity", "Error deleting comic files", t);
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemoveOnlineComic(ComicItem item) {
        if (item == null || currentConnectedPeer == null) return;
        new AlertDialog.Builder(this)
                .setTitle("彻底删除电脑端漫画")
                .setMessage("确定要彻底删除电脑上的《" + item.getTitle() + "》吗？\n\n⚠️ 警告：此操作将通过局域网指令，永久删除 Windows 电脑硬盘上的该漫画物理文件/压缩包及所有阅读记录！")
                .setPositiveButton("彻底删除", (dialog, which) -> {
                    String peerIp = currentConnectedPeer.ip;
                    int peerPort = currentConnectedPeer.port;
                    String comicId = item.getId();
                    String comicTitle = item.getTitle();

                    synchronized (onlineShelfComics) {
                        onlineShelfComics.remove(item);
                    }
                    displayComics.remove(item);
                    filterComics(etSearch != null ? etSearch.getText().toString() : "");
                    updateShelfStats();
                    Toast.makeText(this, "🗑️ 正在通知电脑端删除《" + comicTitle + "》物理文件...", Toast.LENGTH_SHORT).show();

                    lanExecutor.execute(() -> {
                        boolean success = false;
                        String errorMsg = "";
                        try {
                            String encodedId = java.net.URLEncoder.encode(comicId != null ? comicId : "", "UTF-8");
                            String encodedTitle = java.net.URLEncoder.encode(comicTitle != null ? comicTitle : "", "UTF-8");
                            String urlStr = "http://" + peerIp + ":" + peerPort + "/api/comic-delete?id=" + encodedId + "&title=" + encodedTitle;
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
                            conn.setRequestMethod("POST");
                            conn.setConnectTimeout(6000);
                            conn.setReadTimeout(6000);
                            int code = conn.getResponseCode();
                            if (code >= 200 && code < 300) {
                                success = true;
                            } else {
                                errorMsg = "HTTP " + code;
                            }
                            conn.disconnect();
                        } catch (Exception e) {
                            errorMsg = e.getMessage() != null ? e.getMessage() : "网络连接异常";
                        }

                        final boolean finalSuccess = success;
                        final String finalErrorMsg = errorMsg;
                        mainHandler.post(() -> {
                            if (finalSuccess) {
                                Toast.makeText(MainActivity.this, "✅ 已同步彻底删除电脑端《" + comicTitle + "》物理文件", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this, "❌ 电脑端删除失败: " + finalErrorMsg, Toast.LENGTH_LONG).show();
                                if (currentConnectedPeer != null) {
                                    refreshLastFolder();
                                }
                            }
                        });
                    });
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
                if (newTitle.isEmpty()) {
                    if (tvError != null) {
                        tvError.setText("⚠️ 漫画名称不能为空");
                        tvError.setVisibility(View.VISIBLE);
                    } else {
                        Toast.makeText(this, "漫画名称不能为空", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }

                if (isOnlineShelfMode && currentConnectedPeer != null) {
                    showRadarProgress("正在修改远端漫画名", 35);
                    String peerIp = currentConnectedPeer.ip;
                    int peerPort = currentConnectedPeer.port;
                    String comicId = item.getId();
                    String oldTitle = item.getTitle();
                    dialog.dismiss();

                    lanExecutor.execute(() -> {
                        boolean ok = lanTransferManager.renameRemoteComic(peerIp, peerPort, comicId, oldTitle, newTitle);
                        mainHandler.post(() -> {
                            if (ok) {
                                item.setTitle(newTitle);
                                sortComics();
                                filterComics(etSearch != null ? etSearch.getText().toString() : "");
                                updateShelfStats();
                                completeRadarProgress("✅ 远端重命名成功");
                                Toast.makeText(MainActivity.this, "✏️ 已成功将远端漫画重命名为《" + newTitle + "》", Toast.LENGTH_SHORT).show();
                            } else {
                                completeRadarProgress("⚠️ 远端重命名失败");
                                Toast.makeText(MainActivity.this, "⚠️ 修改远端漫画名失败，请检查网络或重试", Toast.LENGTH_SHORT).show();
                            }
                        });
                    });
                    return;
                }

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

        final List<String> pages = new ArrayList<>();
        if (item.getPagePaths() != null && !item.getPagePaths().isEmpty()) {
            pages.addAll(item.getPagePaths());
        }
        if (tvComicTitle != null) {
            tvComicTitle.setText(item.getTitle());
        }
        if (tvTotalPages != null) {
            tvTotalPages.setText(pages.isEmpty() && isOnlineShelfMode ? "正在加载远端内页..." : ("共 " + pages.size() + " 页"));
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
                    tvStatusBadge.setText("当前使用：自定义图片封面");
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
                if (isOnlineShelfMode && currentConnectedPeer != null) {
                    showRadarProgress("正在恢复默认封面", 40);
                    String peerIp = currentConnectedPeer.ip;
                    int peerPort = currentConnectedPeer.port;
                    dialog.dismiss();
                    lanExecutor.execute(() -> {
                        boolean ok = lanTransferManager.resetRemoteComicCover(peerIp, peerPort, item.getId());
                        mainHandler.post(() -> {
                            if (ok) {
                                item.setCoverPath("http://" + peerIp + ":" + peerPort + "/api/comic-cover?id=" + item.getId() + "&_t=" + System.currentTimeMillis());
                                item.setCustomCoverPath("");
                                adapter.notifyDataSetChanged();
                                completeRadarProgress("✅ 默认封面已恢复");
                                Toast.makeText(MainActivity.this, "↺ 已恢复《" + item.getTitle() + "》为默认第 1 页封面", Toast.LENGTH_SHORT).show();
                            } else {
                                completeRadarProgress("⚠️ 恢复封面失败");
                                Toast.makeText(MainActivity.this, "恢复远端封面失败", Toast.LENGTH_SHORT).show();
                            }
                        });
                    });
                    return;
                }

                ShelfStorage.cleanupCustomCoverFiles(this, item.getId());
                ShelfStorage.saveCustomCover(this, item, "");
                ShelfStorage.saveShelf(this, allComics, ShelfStorage.getSavedRootPath(this));
                adapter.notifyDataSetChanged();
                dialog.dismiss();
                Toast.makeText(this, "已恢复《" + item.getTitle() + "》为默认第 1 页封面", Toast.LENGTH_SHORT).show();
            });
        }

        RecyclerView.Adapter[] pageAdapterHolder = new RecyclerView.Adapter[1];
        if (recyclerPages != null) {
            recyclerPages.setLayoutManager(new GridLayoutManager(this, 3));
            RecyclerView.Adapter<RecyclerView.ViewHolder> pageAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
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
                        if (isOnlineShelfMode && currentConnectedPeer != null) {
                            int targetPageIdx = position;
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("🖼️ 设置远端封面确认")
                                    .setMessage("确定将第 " + (targetPageIdx + 1) + " 页画面设为远端《" + item.getTitle() + "》的专属封面吗？")
                                    .setPositiveButton("确认设置", (d, w) -> {
                                        dialog.dismiss();
                                        showRadarProgress("正在设置远端封面", 45);
                                        String peerIp = currentConnectedPeer.ip;
                                        int peerPort = currentConnectedPeer.port;
                                        lanExecutor.execute(() -> {
                                            boolean ok = lanTransferManager.setRemoteComicCoverFromPage(peerIp, peerPort, item.getId(), targetPageIdx);
                                            mainHandler.post(() -> {
                                                if (ok) {
                                                    item.setCoverPath("http://" + peerIp + ":" + peerPort + "/api/comic-cover?id=" + item.getId() + "&_t=" + System.currentTimeMillis());
                                                    item.setCustomCoverPath("remote_page_" + targetPageIdx);
                                                    adapter.notifyDataSetChanged();
                                                    completeRadarProgress("✅ 远端封面已生效");
                                                    Toast.makeText(MainActivity.this, "🖼️ 已成功将第 " + (targetPageIdx + 1) + " 页设为封面", Toast.LENGTH_SHORT).show();
                                                } else {
                                                    completeRadarProgress("⚠️ 设置远端封面失败");
                                                    Toast.makeText(MainActivity.this, "⚠️ 设置远端封面失败，请重试", Toast.LENGTH_SHORT).show();
                                                }
                                            });
                                        });
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                            return;
                        }

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
            };
            pageAdapterHolder[0] = pageAdapter;
            recyclerPages.setAdapter(pageAdapter);

            if (isOnlineShelfMode && currentConnectedPeer != null && pages.isEmpty()) {
                String peerIp = currentConnectedPeer.ip;
                int peerPort = currentConnectedPeer.port;
                lanExecutor.execute(() -> {
                    try {
                        String mUrl = "http://" + peerIp + ":" + peerPort + "/api/comic-manifest?id=" + URLEncoder.encode(item.getId(), "UTF-8");
                        HttpURLConnection conn = (HttpURLConnection) new URL(mUrl).openConnection();
                        conn.setConnectTimeout(8000);
                        conn.setReadTimeout(12000);
                        if (conn.getResponseCode() == 200) {
                            String jsonStr = LanTransferManager.readStreamAsString(conn.getInputStream());
                            JSONObject obj = new JSONObject(jsonStr);
                            JSONArray arr = obj.optJSONArray("pages");
                            if (arr != null) {
                                List<String> loadedUrls = new ArrayList<>();
                                for (int i = 0; i < arr.length(); i++) {
                                    loadedUrls.add("http://" + peerIp + ":" + peerPort + "/api/comic-page?id=" + URLEncoder.encode(item.getId(), "UTF-8") + "&index=" + i);
                                }
                                mainHandler.post(() -> {
                                    pages.clear();
                                    pages.addAll(loadedUrls);
                                    item.setPagePaths(loadedUrls);
                                    if (tvTotalPages != null) tvTotalPages.setText("共 " + pages.size() + " 页");
                                    if (pageAdapterHolder[0] != null) pageAdapterHolder[0].notifyDataSetChanged();
                                });
                            }
                        }
                    } catch (Exception ignored) {}
                });
            }
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
        if (isBatchDeleting) {
            Toast.makeText(this, "正在进行批量删除任务，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<String> selectedIds = new HashSet<>(adapter.getSelectedIds());
        int count = selectedIds.size();
        if (count == 0) return;

        new AlertDialog.Builder(this)
                .setTitle("批量彻底删除漫画")
                .setMessage("确定要批量彻底删除选中的 " + count + " 部漫画吗？\n\n⚠️ 警告：此操作将同时从书架移除所选漫画、清除阅读记录，并永久删除手机存储上的对应漫画物理文件夹及图片！")
                .setPositiveButton("批量删除", (dialog, which) -> {
                    if (isBatchDeleting) return;
                    isBatchDeleting = true;

                    List<ComicItem> toDelete = new ArrayList<>();
                    synchronized (allComics) {
                        for (ComicItem c : allComics) {
                            if (c != null && selectedIds.contains(c.getId())) {
                                toDelete.add(c);
                            }
                        }
                        allComics.removeAll(toDelete);
                    }
                    displayComics.removeAll(toDelete);

                    exitBatchMode();
                    filterComics(etSearch != null ? etSearch.getText().toString() : "");
                    updateShelfStats();

                    int total = toDelete.size();
                    if (total == 0) {
                        isBatchDeleting = false;
                        return;
                    }

                    showImportProgress("准备批量删除 (0/" + total + ")...", 0, "正在启动异步删除队列...");

                    deleteExecutor.execute(() -> {
                        int successCount = 0;
                        for (int i = 0; i < total; i++) {
                            ComicItem c = toDelete.get(i);
                            int currentIdx = i + 1;
                            int percent = (int) (((float) currentIdx / total) * 100);
                            String title = (c != null && c.getTitle() != null && !c.getTitle().isEmpty()) ? c.getTitle() : ("第 " + currentIdx + " 部");

                            updateImportProgress("正在批量删除 (" + currentIdx + "/" + total + ")", percent, "正在删除: 《" + title + "》物理文件及记录");

                            try {
                                if (c != null) {
                                    ShelfStorage.deleteComicPhysicalFiles(MainActivity.this, c);
                                    ShelfStorage.clearComicHistory(MainActivity.this, c);
                                    successCount++;
                                }
                            } catch (Throwable t) {
                                Log.e("MainActivity", "Error deleting comic: " + title, t);
                            }
                        }

                        // 保存最新书架状态
                        try {
                            ShelfStorage.saveShelf(MainActivity.this, allComics, ShelfStorage.getSavedRootPath(MainActivity.this));
                        } catch (Throwable t) {
                            Log.e("MainActivity", "Error saving shelf after batch delete", t);
                        }

                        final int finalSuccess = successCount;
                        mainHandler.post(() -> {
                            isBatchDeleting = false;
                            updateImportProgress("批量删除完成", 100, "已彻底清理 " + finalSuccess + " 部漫画的物理文件及记录");
                            hideImportProgress(2500);
                            filterComics(etSearch != null ? etSearch.getText().toString() : "");
                            updateShelfStats();
                            Toast.makeText(MainActivity.this, "🗑️ 已批量彻底删除 " + finalSuccess + " 部漫画及本地物理文件", Toast.LENGTH_SHORT).show();
                        });
                    });
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
                getString(R.string.sort_name),
                getString(R.string.sort_time),
                getString(R.string.sort_reads),
                getString(R.string.sort_recent),
                getString(R.string.sort_pages)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.sort_rules)
                .setSingleChoiceItems(sortOptions, currentSortMode, (dialog, which) -> {
                    currentSortMode = which;
                    sp.edit().putInt("comic_sort_mode", currentSortMode).apply();
                    sortComics();
                    filterComics(etSearch.getText().toString());
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
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
            tvShelfStats.setText(getString(R.string.shelf_stats_format, activeTotal));
        } else {
            tvShelfStats.setText(displayComics.size() + " / " + activeTotal);
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
        SettingsDialogManager.showSettingsDialog(
                this,
                allComics,
                lanTransferManager,
                currentConnectedPeer,
                currentSortMode,
                new SettingsDialogManager.OnSettingsActionListener() {
                    @Override
                    public void onOpenLanTransfer() {
                        showLanTransferDialog();
                    }

                    @Override
                    public void onOpenMissingComics() {
                        showMissingComicsDetailDialog();
                    }

                    @Override
                    public void onChangeTransferDir(java.util.function.Consumer<String> callback) {
                        openTransferFolderChooser(newDir -> {
                            if (callback != null) callback.accept(newDir);
                        });
                    }

                    @Override
                    public void onResetTransferDir(java.util.function.Consumer<String> callback) {
                        ShelfStorage.setTransferSaveDir(MainActivity.this, "");
                        String defDir = ShelfStorage.getTransferSaveDir(MainActivity.this);
                        if (callback != null) callback.accept(defDir);
                    }

                    @Override
                    public void onShowPasswordSetup(boolean isEnableFlow, java.util.function.Consumer<Boolean> callback) {
                        showPasswordSetupDialog(isEnableFlow, success -> {
                            if (callback != null) callback.accept(success);
                        });
                    }

                    @Override
                    public void onShowWhitelistManagement(Runnable onUpdated) {
                        showWhitelistManagementDialog(onUpdated);
                    }

                    @Override
                    public void onShowBlacklistManagement(Runnable onUpdated) {
                        showBlacklistManagementDialog(onUpdated);
                    }

                    @Override
                    public void onOpenSortDialog() {
                        showSortDialog();
                    }

                    @Override
                    public void onClearShelf() {
                        confirmClearShelf();
                    }
                }
        );
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

    private void showIncomingPairRequestDialog(LanTransferManager.PairRequestState req) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle("收到局域网连接请求")
                .setMessage("设备「" + req.fromName + "」(" + req.fromPlatform + ") 正在请求连接您的漫画阅读器，是否允许双方建立互传连接？")
                .setPositiveButton("允许连接", (d, w) -> {
                    lanTransferManager.respondPairRequest(req.reqId, true);
                    Toast.makeText(this, "已允许 " + req.fromName + " 连接", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("拒绝连接", (d, w) -> {
                    lanTransferManager.respondPairRequest(req.reqId, false);
                    Toast.makeText(this, "已拒绝连接请求", Toast.LENGTH_SHORT).show();
                })
                .setCancelable(false)
                .show();
    }

    private void showPasswordSetupDialog(boolean isFirstSetup, java.util.function.Consumer<Boolean> onComplete) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        Context dialogContext = builder.getContext();
        TypedValue tvText = new TypedValue();
        dialogContext.getTheme().resolveAttribute(android.R.attr.textColorPrimary, tvText, true);
        int textColor = tvText.data != 0 ? tvText.data : android.graphics.Color.parseColor("#0F172A");

        TypedValue tvHint = new TypedValue();
        dialogContext.getTheme().resolveAttribute(android.R.attr.textColorHint, tvHint, true);
        int hintColor = tvHint.data != 0 ? tvHint.data : android.graphics.Color.parseColor("#64748B");

        LinearLayout layout = new LinearLayout(dialogContext);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        TextView tvTip = new TextView(dialogContext);
        tvTip.setText("连接密码要求：至少 8 位，且必须同时包含数字、字母和特殊符号。");
        tvTip.setTextColor(hintColor);
        tvTip.setTextSize(12f);
        tvTip.setPadding(0, 0, 0, pad / 2);
        layout.addView(tvTip);

        EditText etPwd = new EditText(dialogContext);
        etPwd.setHint("输入新连接密码 (至少8位)");
        etPwd.setHintTextColor(hintColor);
        etPwd.setTextColor(textColor);
        etPwd.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(etPwd);

        EditText etConfirm = new EditText(dialogContext);
        etConfirm.setHint("再次确认连接密码");
        etConfirm.setHintTextColor(hintColor);
        etConfirm.setTextColor(textColor);
        etConfirm.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(etConfirm);

        TextView tvError = new TextView(dialogContext);
        tvError.setTextColor(android.graphics.Color.parseColor("#EF4444"));
        tvError.setTextSize(12f);
        tvError.setVisibility(View.GONE);
        tvError.setPadding(0, pad / 4, 0, pad / 4);
        layout.addView(tvError);

        AlertDialog dialog = builder
                .setTitle(isFirstSetup ? "设置连接密码" : "修改连接密码")
                .setView(layout)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", (d, w) -> {
                    if (onComplete != null) onComplete.accept(false);
                })
                .create();

        dialog.setOnShowListener(d -> {
            android.widget.Button positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positiveBtn != null) {
                positiveBtn.setOnClickListener(v -> {
                    String p1 = etPwd.getText().toString();
                    String p2 = etConfirm.getText().toString();
                    if (p1.isEmpty() || p2.isEmpty()) {
                        tvError.setText("⚠️ 密码不能为空，请完整输入");
                        tvError.setVisibility(View.VISIBLE);
                        Toast.makeText(MainActivity.this, "⚠️ 密码不能为空，请完整输入", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!p1.equals(p2)) {
                        tvError.setText("⚠️ 两次输入的密码不一致，请重新检查");
                        tvError.setVisibility(View.VISIBLE);
                        Toast.makeText(MainActivity.this, "⚠️ 两次输入的密码不一致，请重新检查", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!LanTransferManager.isValidPassword(p1)) {
                        tvError.setText("⚠️ 密码不符合要求：至少8位，且必须同时包含数字、字母和特殊符号！");
                        tvError.setVisibility(View.VISIBLE);
                        Toast.makeText(MainActivity.this, "⚠️ 密码不符合要求：至少8位，且必须同时包含数字、字母和特殊符号！", Toast.LENGTH_LONG).show();
                        return;
                    }
                    tvError.setVisibility(View.GONE);
                    lanTransferManager.setConnectionPassword(p1);
                    dialog.dismiss();
                    Toast.makeText(MainActivity.this, "连接密码已保存", Toast.LENGTH_SHORT).show();
                    if (onComplete != null) onComplete.accept(true);
                });
            }
        });
        dialog.show();
    }

    private void showInputPasswordDialog(LanTransferManager.PeerInfo peer, String initialError, java.util.function.Consumer<String> onPasswordEntered) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        Context dialogContext = builder.getContext();
        TypedValue tvText = new TypedValue();
        dialogContext.getTheme().resolveAttribute(android.R.attr.textColorPrimary, tvText, true);
        int textColor = tvText.data != 0 ? tvText.data : android.graphics.Color.parseColor("#0F172A");

        TypedValue tvHint = new TypedValue();
        dialogContext.getTheme().resolveAttribute(android.R.attr.textColorHint, tvHint, true);
        int hintColor = tvHint.data != 0 ? tvHint.data : android.graphics.Color.parseColor("#64748B");

        LinearLayout layout = new LinearLayout(dialogContext);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        TextView tvTip = new TextView(dialogContext);
        tvTip.setText("设备「" + peer.deviceName + "」已开启密码保护，请输入该设备的连接密码：");
        tvTip.setTextColor(textColor);
        tvTip.setTextSize(12.5f);
        tvTip.setPadding(0, 0, 0, pad / 2);
        layout.addView(tvTip);

        EditText etPwd = new EditText(dialogContext);
        etPwd.setHint("输入连接密码");
        etPwd.setHintTextColor(hintColor);
        etPwd.setTextColor(textColor);
        etPwd.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(etPwd);

        TextView tvError = new TextView(dialogContext);
        tvError.setTextColor(android.graphics.Color.parseColor("#EF4444"));
        tvError.setTextSize(12f);
        tvError.setPadding(0, pad / 4, 0, pad / 4);
        if (initialError != null && !initialError.isEmpty()) {
            tvError.setText("⚠️ " + initialError);
            tvError.setVisibility(View.VISIBLE);
        } else {
            tvError.setVisibility(View.GONE);
        }
        layout.addView(tvError);

        AlertDialog dialog = builder
                .setTitle("输入连接密码")
                .setView(layout)
                .setPositiveButton("连接", null)
                .setNegativeButton("取消", null)
                .create();

        dialog.setOnShowListener(d -> {
            android.widget.Button btnConnect = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (btnConnect != null) {
                btnConnect.setOnClickListener(v -> {
                    String pwd = etPwd.getText().toString().trim();
                    if (pwd.isEmpty()) {
                        tvError.setText("⚠️ 密码不能为空");
                        tvError.setVisibility(View.VISIBLE);
                        return;
                    }
                    tvError.setVisibility(View.GONE);
                    dialog.dismiss();
                    if (onPasswordEntered != null) onPasswordEntered.accept(pwd);
                });
            }
        });
        dialog.show();
    }

    private void showWhitelistManagementDialog(Runnable onUpdated) {
        List<LanTransferManager.WhitelistEntry> list = lanTransferManager.getWhitelist();
        if (list.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("信任设备白名单")
                    .setMessage("暂无信任设备。\n\n双方连接成功后，可点击「⭐ 加入白名单」，白名单内的设备连接时无需手动确认即可直接连接。")
                    .setPositiveButton("我知道了", null)
                    .show();
            return;
        }

        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            LanTransferManager.WhitelistEntry w = list.get(i);
            items[i] = w.deviceName + " (" + (w.platform.isEmpty() ? "设备" : w.platform) + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle("信任设备白名单 (点击移除)")
                .setItems(items, (d, which) -> {
                    if (which >= 0 && which < list.size()) {
                        LanTransferManager.WhitelistEntry target = list.get(which);
                        new AlertDialog.Builder(this)
                                .setTitle("移出白名单")
                                .setMessage("确定将「" + target.deviceName + "」移出信任白名单吗？移出后对方连接需重新确认。")
                                .setPositiveButton("确定移出", (d2, w2) -> {
                                    lanTransferManager.removeFromWhitelist(target.deviceId);
                                    Toast.makeText(this, "已移出: " + target.deviceName, Toast.LENGTH_SHORT).show();
                                    if (onUpdated != null) onUpdated.run();
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showBlacklistManagementDialog(Runnable onUpdated) {
        List<LanTransferManager.BlacklistEntry> list = lanTransferManager.getBlacklist();
        if (list.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("设备黑名单")
                    .setMessage("当前黑名单为空。\n\n双方连接后，可点击「🚫 黑名单」将对方设备拉黑，黑名单中的设备再次发起连接时将被系统自动拒绝。\n\n您也可以手动输入 IP 地址将其加入黑名单。")
                    .setPositiveButton("➕ 手动添加IP", (d, w) -> showAddBlacklistManualDialog(onUpdated))
                    .setNegativeButton("关闭", null)
                    .show();
            return;
        }

        List<String> displayList = new ArrayList<>();
        displayList.add("➕ 手动添加 IP 到黑名单");
        displayList.add("🗑️ 清空所有黑名单");
        for (LanTransferManager.BlacklistEntry b : list) {
            String name = (b.deviceName != null && !b.deviceName.isEmpty()) ? b.deviceName : "未知设备";
            if (b.ip != null && !b.ip.isEmpty()) name += " (" + b.ip + ")";
            displayList.add("🚫 " + name + " (点击移出)");
        }

        String[] items = displayList.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("黑名单管理 (" + list.size() + ")")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        showAddBlacklistManualDialog(onUpdated);
                    } else if (which == 1) {
                        new AlertDialog.Builder(this)
                                .setTitle("清空黑名单")
                                .setMessage("确定清空所有黑名单设备吗？清空后所有设备均可正常发起连接请求。")
                                .setPositiveButton("清空全部", (d2, w2) -> {
                                    for (LanTransferManager.BlacklistEntry target : list) {
                                        lanTransferManager.removeFromBlacklist(target.deviceId, target.ip);
                                    }
                                    Toast.makeText(this, "已清空所有黑名单", Toast.LENGTH_SHORT).show();
                                    if (onUpdated != null) onUpdated.run();
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    } else {
                        int itemIndex = which - 2;
                        if (itemIndex >= 0 && itemIndex < list.size()) {
                            LanTransferManager.BlacklistEntry target = list.get(itemIndex);
                            new AlertDialog.Builder(this)
                                    .setTitle("移出黑名单")
                                    .setMessage("确定解除对「" + target.deviceName + "」的黑名单限制吗？移除后对方可正常发起连接。")
                                    .setPositiveButton("移出并解封", (d2, w2) -> {
                                        lanTransferManager.removeFromBlacklist(target.deviceId, target.ip);
                                        Toast.makeText(this, "已从黑名单移除: " + target.deviceName, Toast.LENGTH_SHORT).show();
                                        if (onUpdated != null) onUpdated.run();
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showAddBlacklistManualDialog(Runnable onUpdated) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("例如 192.168.1.5 或设备名称");
        input.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.leftMargin = pad;
        params.rightMargin = pad;
        input.setLayoutParams(params);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("手动添加至黑名单")
                .setMessage("输入目标设备的 IP 地址或名称，该设备后续发起的互传连接将被自动拒绝：")
                .setView(container)
                .setPositiveButton("加入黑名单", (d, w) -> {
                    String val = input.getText().toString().trim();
                    if (!val.isEmpty()) {
                        lanTransferManager.addToBlacklist("manual_" + val, val, val, "Manual");
                        Toast.makeText(this, "已将 " + val + " 加入黑名单", Toast.LENGTH_SHORT).show();
                        if (onUpdated != null) onUpdated.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showLanFilterActionMenu(
            View anchor,
            boolean isPull,
            int missingCount,
            int totalEntries,
            boolean isOnlyMissing,
            boolean isAllSelected,
            Runnable onToggleSelectAll,
            Runnable onToggleOnlyMissing,
            Runnable onShowAll,
            Runnable onClearSelection
    ) {
        androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(this, anchor);
        android.view.Menu menu = popup.getMenu();

        // 1. 全选 / 取消全选
        menu.add(0, 1, 0, isAllSelected ? "☐ 取消全选" : "☑ 全选当前漫画 (" + totalEntries + ")");

        // 2. 仅看未拥有 (带状态指示)
        String missingLabel = isOnlyMissing
                ? "✓ 仅看未拥有 (" + missingCount + ") [已开启]"
                : "☐ 仅看未拥有 (" + missingCount + ")";
        menu.add(0, 2, 0, missingLabel);

        // 3. 显示全部
        if (isOnlyMissing) {
            menu.add(0, 3, 0, "↺ 显示全部漫画");
        }

        // 4. 清空所有选择
        menu.add(0, 4, 0, "🗑️ 清空所有选择");

        // 5. 检查电脑端更新
        menu.add(0, 5, 0, "🚀 检查电脑端更新");

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                if (onToggleSelectAll != null) onToggleSelectAll.run();
                return true;
            } else if (id == 2) {
                if (onToggleOnlyMissing != null) onToggleOnlyMissing.run();
                return true;
            } else if (id == 3) {
                if (onShowAll != null) onShowAll.run();
                return true;
            } else if (id == 4) {
                if (onClearSelection != null) onClearSelection.run();
                return true;
            } else if (id == 5) {
                LanTransferManager.PeerInfo p = currentConnectedPeer;
                ApkUpdateManager.checkAndPullUpdate(MainActivity.this, lanTransferManager, p, null);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showLanTransferDialog() {
        try {
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

        androidx.appcompat.widget.SwitchCompat switchRequirePwd = dialogView.findViewById(R.id.switch_lan_require_pwd);
        TextView tvLanPwdStatus = dialogView.findViewById(R.id.tv_lan_pwd_status);
        com.google.android.material.button.MaterialButton btnEditPwd = dialogView.findViewById(R.id.btn_lan_edit_pwd);
        com.google.android.material.button.MaterialButton btnWhitelist = dialogView.findViewById(R.id.btn_lan_whitelist);
        com.google.android.material.button.MaterialButton btnBlacklist = dialogView.findViewById(R.id.btn_lan_blacklist);
        View layoutConnectedBar = dialogView.findViewById(R.id.layout_lan_connected_bar);
        TextView tvConnectedInfo = dialogView.findViewById(R.id.tv_lan_connected_info);
        com.google.android.material.button.MaterialButton btnToggleWhitelist = dialogView.findViewById(R.id.btn_lan_toggle_whitelist);

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
        com.google.android.material.button.MaterialButton btnFilterMenu = dialogView.findViewById(R.id.btn_lan_filter_menu);
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
        if (currentConnectedPeer != null) {
            selectedPeer[0] = currentConnectedPeer;
        }
        final List<LanTransferManager.RemoteComic> remoteComics = new ArrayList<>();
        final boolean[] isPullTab = new boolean[]{true}; // true = 从对方下载, false = 发送本机漫画
        final boolean[] onlyMissingFilter = new boolean[]{false}; // true = 仅看本机/对方没有的漫画
        final String[] searchKeyword = new String[]{""};
        final boolean[] isTransferring = new boolean[]{false};

        recyclerLanComics.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        final LanComicSelectAdapter[] selectAdapter = new LanComicSelectAdapter[1];

        Runnable updateConnBadge = () -> {
            if (tvConnBadge != null) {
                if (selectedPeer[0] != null) {
                    tvConnBadge.setText("已连: " + selectedPeer[0].deviceName);
                    tvConnBadge.setTextColor(android.graphics.Color.parseColor("#6EE7B7"));
                } else {
                    tvConnBadge.setText("等待设备连接");
                    tvConnBadge.setTextColor(android.graphics.Color.parseColor("#94A3B8"));
                }
            }
            if (layoutConnectedBar != null) {
                if (selectedPeer[0] != null) {
                    layoutConnectedBar.setVisibility(View.VISIBLE);
                    if (tvConnectedInfo != null) {
                        tvConnectedInfo.setText("🟢 已连接: " + selectedPeer[0].deviceName + (selectedPeer[0].comicCount > 0 ? " (" + selectedPeer[0].comicCount + "部)" : ""));
                    }
                    if (btnToggleWhitelist != null) {
                        boolean isWhitelisted = lanTransferManager.isDeviceWhitelisted(selectedPeer[0].deviceId);
                        btnToggleWhitelist.setText(isWhitelisted ? "⭐ 移出白名单" : "⭐ 加入白名单");
                        btnToggleWhitelist.setTextColor(android.graphics.Color.parseColor(isWhitelisted ? "#F87171" : "#FBBF24"));
                        btnToggleWhitelist.setStrokeColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(isWhitelisted ? "#F87171" : "#FBBF24")));
                    }
                } else {
                    layoutConnectedBar.setVisibility(View.GONE);
                }
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

        final int[] lastMissingCountHolder = new int[]{0};

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

            int unselectedBg = ContextCompat.getColor(MainActivity.this, R.color.bg_container);
            int unselectedText = ContextCompat.getColor(MainActivity.this, R.color.text_secondary);
            int primaryColor = ContextCompat.getColor(MainActivity.this, R.color.primary);
            int activeEmerald = ContextCompat.getColor(MainActivity.this, R.color.emerald_accent);

            if (btnTabPull != null) {
                btnTabPull.setText("从对方下载 (" + remoteComics.size() + ")");
                btnTabPull.setIconResource(R.drawable.ic_download_vector);
                btnTabPull.setIconTint(android.content.res.ColorStateList.valueOf(
                        isPullTab[0] ? Color.WHITE : unselectedText));
                btnTabPull.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        isPullTab[0] ? primaryColor : unselectedBg));
                btnTabPull.setTextColor(isPullTab[0] ? Color.WHITE : unselectedText);
            }
            if (btnTabPush != null) {
                btnTabPush.setText("发送本机漫画 (" + localActive.size() + ")");
                btnTabPush.setIconResource(R.drawable.ic_upload_vector);
                btnTabPush.setIconTint(android.content.res.ColorStateList.valueOf(
                        !isPullTab[0] ? Color.WHITE : unselectedText));
                btnTabPush.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        !isPullTab[0] ? primaryColor : unselectedBg));
                btnTabPush.setTextColor(!isPullTab[0] ? Color.WHITE : unselectedText);
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
            lastMissingCountHolder[0] = missingTotalCount;

            if (btnFilterMenu != null) {
                if (onlyMissingFilter[0]) {
                    btnFilterMenu.setText("未拥有 (" + missingTotalCount + ") ▾");
                    btnFilterMenu.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activeEmerald));
                    btnFilterMenu.setTextColor(Color.WHITE);
                    btnFilterMenu.setStrokeColor(android.content.res.ColorStateList.valueOf(activeEmerald));
                } else {
                    btnFilterMenu.setText("筛选 / 操作 ▾");
                    btnFilterMenu.setBackgroundTintList(android.content.res.ColorStateList.valueOf(unselectedBg));
                    btnFilterMenu.setTextColor(unselectedText);
                    btnFilterMenu.setStrokeColor(android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(MainActivity.this, R.color.border_color)));
                }
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
                    // 对方电脑上的漫画：严格展示电脑端的真实阅读次数 rc.readCount，绝不与本地记录混合
                    int effectiveReadCount = Math.max(0, rc.readCount);
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

        Runnable updateWhitelistAndBlacklistButtons = () -> {
            if (btnWhitelist != null) {
                int wCount = lanTransferManager.getWhitelist().size();
                btnWhitelist.setText("⭐ 信任白名单 (" + wCount + ")");
            }
            if (btnBlacklist != null) {
                int bCount = lanTransferManager.getBlacklist().size();
                btnBlacklist.setText("🚫 黑名单管理 (" + bCount + ")");
            }
        };
        updateWhitelistAndBlacklistButtons.run();

        Runnable updateLanPasswordUi = () -> {
            boolean req = lanTransferManager.isRequirePassword();
            if (switchRequirePwd != null && switchRequirePwd.isChecked() != req) {
                switchRequirePwd.setChecked(req);
            }
            if (btnEditPwd != null) {
                btnEditPwd.setVisibility(req ? View.VISIBLE : View.GONE);
            }
            if (tvLanPwdStatus != null) {
                if (req) {
                    tvLanPwdStatus.setText("已启用（对方需输入密码直接连接，白名单设备免密免确认）");
                    tvLanPwdStatus.setTextColor(android.graphics.Color.parseColor("#4ADE80"));
                } else {
                    tvLanPwdStatus.setText("未启用（对方连接需本机手动点击确认）");
                    tvLanPwdStatus.setTextColor(android.graphics.Color.parseColor("#94A3B8"));
                }
            }
        };
        updateLanPasswordUi.run();

        if (switchRequirePwd != null) {
            switchRequirePwd.setOnCheckedChangeListener((btnView, isChecked) -> {
                if (!btnView.isPressed()) return;
                if (isChecked) {
                    if (!lanTransferManager.hasConnectionPassword()) {
                        switchRequirePwd.setChecked(false);
                        showPasswordSetupDialog(true, success -> {
                            if (success) {
                                switchRequirePwd.setChecked(true);
                                lanTransferManager.setRequirePassword(true);
                                updateLanPasswordUi.run();
                                Toast.makeText(MainActivity.this, "已开启密码连接保护", Toast.LENGTH_SHORT).show();
                            } else {
                                updateLanPasswordUi.run();
                            }
                        });
                    } else {
                        lanTransferManager.setRequirePassword(true);
                        updateLanPasswordUi.run();
                        Toast.makeText(MainActivity.this, "已开启密码连接限制", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    lanTransferManager.setRequirePassword(false);
                    updateLanPasswordUi.run();
                    Toast.makeText(MainActivity.this, "已关闭密码连接限制", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnEditPwd != null) {
            btnEditPwd.setOnClickListener(v -> showPasswordSetupDialog(false, success -> updateLanPasswordUi.run()));
        }

        if (btnWhitelist != null) {
            btnWhitelist.setOnClickListener(v -> showWhitelistManagementDialog(() -> {
                updateWhitelistAndBlacklistButtons.run();
                updateConnBadge.run();
                if (activeLanRefreshCallback != null) activeLanRefreshCallback.run();
            }));
        }

        if (btnBlacklist != null) {
            btnBlacklist.setOnClickListener(v -> showBlacklistManagementDialog(() -> {
                updateWhitelistAndBlacklistButtons.run();
                updateConnBadge.run();
                if (activeLanRefreshCallback != null) activeLanRefreshCallback.run();
            }));
        }

        if (btnToggleWhitelist != null) {
            btnToggleWhitelist.setOnClickListener(v -> {
                LanTransferManager.PeerInfo p = selectedPeer[0] != null ? selectedPeer[0] : currentConnectedPeer;
                if (p == null) return;
                boolean isWhitelisted = lanTransferManager.isDeviceWhitelisted(p.deviceId);
                if (isWhitelisted) {
                    lanTransferManager.removeFromWhitelist(p.deviceId);
                    Toast.makeText(MainActivity.this, "已将 " + p.deviceName + " 移出白名单", Toast.LENGTH_SHORT).show();
                } else {
                    lanTransferManager.addToWhitelist(p.deviceId, p.deviceName, p.platform);
                    Toast.makeText(MainActivity.this, "已将 " + p.deviceName + " 加入信任白名单", Toast.LENGTH_SHORT).show();
                }
                updateConnBadge.run();
                updateWhitelistAndBlacklistButtons.run();
            });
        }

        final Runnable[] renderPeerChipsHolder = new Runnable[1];

        View btnLanCheckUpdate = dialogView.findViewById(R.id.btn_lan_check_update_peer);
        if (btnLanCheckUpdate != null) {
            btnLanCheckUpdate.setOnClickListener(v -> {
                LanTransferManager.PeerInfo p = selectedPeer[0] != null ? selectedPeer[0] : currentConnectedPeer;
                ApkUpdateManager.checkAndPullUpdate(MainActivity.this, lanTransferManager, p, null);
            });
        }

        View btnLanDisconnect = dialogView.findViewById(R.id.btn_lan_disconnect);
        if (btnLanDisconnect != null) {
            btnLanDisconnect.setOnClickListener(v -> {
                LanTransferManager.PeerInfo peerToDisconnect = selectedPeer[0] != null ? selectedPeer[0] : currentConnectedPeer;
                if (peerToDisconnect != null) {
                    lanTransferManager.disconnect(peerToDisconnect);
                }
                selectedPeer[0] = null;
                currentConnectedPeer = null;
                updateConnBadge.run();
                refreshTabList.run();
                if (renderPeerChipsHolder[0] != null) renderPeerChipsHolder[0].run();
                if (isOnlineShelfMode) {
                    exitOnlineShelfMode();
                }
                updateMainHeaderLanButtonState(null);
                updateOnlineShelfBanner();
                Toast.makeText(MainActivity.this, peerToDisconnect != null ? ("已断开与【" + peerToDisconnect.deviceName + "】的连接，已返回本地书架") : "已断开连接，已返回本地书架", Toast.LENGTH_SHORT).show();
                try {
                    dialog.dismiss();
                } catch (Exception ignored) {}
            });
        }

        View btnLanAddBlacklist = dialogView.findViewById(R.id.btn_lan_add_blacklist);
        if (btnLanAddBlacklist != null) {
            btnLanAddBlacklist.setOnClickListener(v -> {
                LanTransferManager.PeerInfo peerToBlacklist = selectedPeer[0] != null ? selectedPeer[0] : currentConnectedPeer;
                if (peerToBlacklist == null) {
                    Toast.makeText(MainActivity.this, "当前无已连接设备可加入黑名单", Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("确认加入黑名单？")
                        .setMessage("将【" + peerToBlacklist.deviceName + "】加入黑名单后，该设备未来发起的连接请求将被系统直接拒绝，且当前连接将立即断开。")
                        .setPositiveButton("加入黑名单并断开", (d, which) -> {
                            lanTransferManager.addToBlacklist(peerToBlacklist.deviceId, peerToBlacklist.deviceName, peerToBlacklist.ip, peerToBlacklist.platform);
                            lanTransferManager.disconnect(peerToBlacklist);
                            updateWhitelistAndBlacklistButtons.run();
                            selectedPeer[0] = null;
                            currentConnectedPeer = null;
                            updateConnBadge.run();
                            refreshTabList.run();
                            if (renderPeerChipsHolder[0] != null) renderPeerChipsHolder[0].run();
                            if (isOnlineShelfMode) {
                                exitOnlineShelfMode();
                            }
                            updateMainHeaderLanButtonState(null);
                            updateOnlineShelfBanner();
                            Toast.makeText(MainActivity.this, "已将【" + peerToBlacklist.deviceName + "】加入黑名单并断开连接", Toast.LENGTH_SHORT).show();
                            try {
                                dialog.dismiss();
                            } catch (Exception ignored) {}
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        activeLanDisconnectCallback = () -> {
            selectedPeer[0] = null;
            updateConnBadge.run();
            refreshTabList.run();
            if (renderPeerChipsHolder[0] != null) renderPeerChipsHolder[0].run();
            try {
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
            } catch (Exception ignored) {}
        };
        final java.util.function.BiConsumer<LanTransferManager.PeerInfo, Boolean> connectToPeerAction = (p, notifyRemote) -> {
            if (p == null || (lanTransferManager != null && lanTransferManager.isSelf(p))) return;
            boolean isNewPeer = selectedPeer[0] == null || !selectedPeer[0].ip.equals(p.ip);
            selectedPeer[0] = p;
            currentConnectedPeer = p;
            updateMainHeaderLanButtonState(p);
            updateConnBadge.run();
            if (renderPeerChipsHolder[0] != null) renderPeerChipsHolder[0].run();

            if (tvPeerStatus != null) {
                tvPeerStatus.setText("正在同步 " + p.deviceName + " 的漫画列表...");
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
                            tvPeerStatus.setText("🟢 已双向连接: " + p.deviceName + " · 对方共 " + remoteComics.size() + " 部漫画");
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
                        if (e.getMessage() != null && e.getMessage().contains("session_expired")) {
                            selectedPeer[0] = null;
                            currentConnectedPeer = null;
                            updateConnBadge.run();
                            updateMainHeaderLanButtonState(null);
                            if (renderPeerChipsHolder[0] != null) renderPeerChipsHolder[0].run();
                            if (tvPeerStatus != null) {
                                tvPeerStatus.setText("对方连接已失效，请重新点击连接");
                            }
                            Toast.makeText(MainActivity.this, "⚠️ 对方连接已失效或已退出，请重新连接", Toast.LENGTH_SHORT).show();
                        } else if (tvPeerStatus != null) {
                            tvPeerStatus.setText("读取设备书架失败: " + e.getMessage());
                        }
                    });
                }
            });
        };

        final java.util.function.BiConsumer<LanTransferManager.PeerInfo, String>[] doHandshakeConnectHolder = new java.util.function.BiConsumer[1];

        doHandshakeConnectHolder[0] = (peer, password) -> {
            final java.util.concurrent.atomic.AtomicBoolean cancelFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
            AlertDialog waitDialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("正在连接设备")
                    .setMessage("正在向「" + peer.deviceName + "」发送连接申请，请等待对方确认...")
                    .setNegativeButton("取消", (d, w) -> {
                        cancelFlag.set(true);
                    })
                    .setCancelable(false)
                    .create();
            waitDialog.show();

            lanExecutor.execute(() -> {
                LanTransferManager.ConnectResult res = lanTransferManager.connectToPeerWithHandshake(peer, password, cancelFlag);
                mainHandler.post(() -> {
                    try { waitDialog.dismiss(); } catch (Exception ignored) {}
                    if (cancelFlag.get()) return;

                    if (res.ok) {
                        connectToPeerAction.accept(peer, false);
                        Toast.makeText(MainActivity.this, "🟢 已与 " + peer.deviceName + " 成功建立连接", Toast.LENGTH_SHORT).show();
                    } else if ("blacklisted".equals(res.status)) {
                        Toast.makeText(MainActivity.this, "🚫 对方设备已将本机加入黑名单，禁止连接", Toast.LENGTH_SHORT).show();
                        if (tvPeerStatus != null) tvPeerStatus.setText("对方设备已将本机加入黑名单");
                    } else if ("need_password".equals(res.status)) {
                        showInputPasswordDialog(peer, null, enteredPwd -> doHandshakeConnectHolder[0].accept(peer, enteredPwd));
                    } else if ("wrong_password".equals(res.status)) {
                        showInputPasswordDialog(peer, "连接密码错误，请重新输入", enteredPwd -> doHandshakeConnectHolder[0].accept(peer, enteredPwd));
                    } else if ("rejected".equals(res.status)) {
                        Toast.makeText(MainActivity.this, "对方设备拒绝了连接请求", Toast.LENGTH_SHORT).show();
                        if (tvPeerStatus != null) tvPeerStatus.setText("对方设备拒绝了连接请求");
                    } else if ("timeout".equals(res.status)) {
                        Toast.makeText(MainActivity.this, "等待对方确认超时", Toast.LENGTH_SHORT).show();
                        if (tvPeerStatus != null) tvPeerStatus.setText("等待对方确认超时");
                    } else {
                        Toast.makeText(MainActivity.this, res.message != null ? res.message : "连接失败", Toast.LENGTH_SHORT).show();
                        if (tvPeerStatus != null) tvPeerStatus.setText(res.message);
                    }
                });
            });
        };

        final java.util.function.Consumer<LanTransferManager.PeerInfo> startPeerConnectionFlow = peer -> {
            if (peer == null) return;
            if (lanTransferManager != null && lanTransferManager.isSelf(peer)) {
                Toast.makeText(MainActivity.this, "无法连接本机自身设备", Toast.LENGTH_SHORT).show();
                return;
            }
            // 优先级交由服务端统一判定：黑白名单最高 -> 密码次之 -> 基础确认
            // 先尝试发起握手请求：若对方将本机加入了白名单，无论对方是否开启密码，均可免密免确认直连
            // 若对方需要密码且本机不在白名单中，服务端会返回 need_password，随后自动唤起输入密码弹窗
            doHandshakeConnectHolder[0].accept(peer, null);
        };

        renderPeerChipsHolder[0] = () -> {
            if (layoutPeers == null) return;
            layoutPeers.removeAllViews();
            float density = getResources().getDisplayMetrics().density;

            for (LanTransferManager.PeerInfo p : discoveredPeers) {
                if (p == null || !"YYComicReader".equals(p.app) || (lanTransferManager != null && lanTransferManager.isSelf(p))) continue;

                boolean active = selectedPeer[0] != null
                        && ((selectedPeer[0].deviceId != null && !selectedPeer[0].deviceId.isEmpty() && selectedPeer[0].deviceId.equals(p.deviceId))
                        || (selectedPeer[0].ip != null && selectedPeer[0].ip.equals(p.ip) && selectedPeer[0].port == p.port));

                boolean isWhitelisted = lanTransferManager.isDeviceWhitelisted(p.deviceId);
                boolean reqPwd = p.requirePassword;

                StringBuilder sb = new StringBuilder();
                sb.append(p.deviceName);
                if (p.comicCount > 0) {
                    sb.append(" (").append(p.comicCount).append("部)");
                }
                if (isWhitelisted) {
                    sb.append(" ⭐白名单");
                } else if (reqPwd) {
                    sb.append(" 🔒需密码");
                }
                if (active) {
                    sb.append(" ✓已连");
                }

                com.google.android.material.button.MaterialButton chip =
                        new com.google.android.material.button.MaterialButton(MainActivity.this);
                boolean isWin = p.platform != null && p.platform.toLowerCase().contains("win");
                chip.setText(sb.toString());
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
                        android.graphics.Color.parseColor(active ? "#059669" : (isWhitelisted ? "#1E293B" : "#222634"))));
                if (isWhitelisted) {
                    chip.setStrokeColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F59E0B")));
                    chip.setStrokeWidth(Math.round(1 * density));
                }
                chip.setCornerRadius(Math.round(8 * density));
                chip.setElevation(0f);
                chip.setStateListAnimator(null);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        Math.round(36 * density)
                );
                lp.rightMargin = Math.round(8 * density);
                chip.setLayoutParams(lp);
                chip.setInsetTop(0);
                chip.setInsetBottom(0);

                chip.setOnClickListener(v -> {
                    if (active) return;
                    startPeerConnectionFlow.accept(p);
                });
                layoutPeers.addView(chip);
            }
        };

        activeLanIncomingPeerCallback = incomingPeer -> {
            if (incomingPeer == null || (lanTransferManager != null && lanTransferManager.isSelf(incomingPeer))) {
                return;
            }
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
            activeLanDisconnectCallback = null;
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
                        if (p == null || !"YYComicReader".equals(p.app) || (lanTransferManager != null && lanTransferManager.isSelf(p))) continue;
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
                        if (tvPeerStatus != null) {
                            tvPeerStatus.setText("发现 " + discoveredPeers.size() + " 台可用设备，请点击上方设备建立连接。");
                        }
                    }
                });
            });
        };

        // 严格消除幽灵/断线假连接与本机自身连接：只有对方设备且已启动才保持连接
        if (currentConnectedPeer != null && lanTransferManager != null && lanTransferManager.isSelf(currentConnectedPeer)) {
            currentConnectedPeer = null;
            selectedPeer[0] = null;
            updateMainHeaderLanButtonState(null);
            updateConnBadge.run();
        }

        if (currentConnectedPeer != null) {
            final LanTransferManager.PeerInfo prevPeer = currentConnectedPeer;
            if (tvPeerStatus != null) {
                tvPeerStatus.setText("正在检测与 " + prevPeer.deviceName + " 的连接状态...");
            }
            lanExecutor.execute(() -> {
                LanTransferManager.PeerInfo alive = lanTransferManager.pingPeer(prevPeer.ip, prevPeer.port, 1200);
                mainHandler.post(() -> {
                    if (alive != null && !lanTransferManager.isSelf(alive)) {
                        currentConnectedPeer = alive;
                        selectedPeer[0] = alive;
                        discoveredPeers.add(alive);
                        updateConnBadge.run();
                        if (renderPeerChipsHolder[0] != null) renderPeerChipsHolder[0].run();
                        connectToPeerAction.accept(alive, false);
                    } else {
                        currentConnectedPeer = null;
                        selectedPeer[0] = null;
                        updateMainHeaderLanButtonState(null);
                        updateConnBadge.run();
                        if (tvPeerStatus != null) {
                            tvPeerStatus.setText("未检测到已连接的设备，请点击下方设备建立连接。");
                        }
                        triggerScanPeers.run();
                    }
                });
            });
        } else {
            triggerScanPeers.run();
        }

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
                if (lanTransferManager != null && lanTransferManager.isSelfIp(host)) {
                    Toast.makeText(this, "该地址为本机自身设备，无需连接", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (tvPeerStatus != null) {
                    tvPeerStatus.setText("正在连接 " + host + " ...");
                }
                btnConnectIp.setEnabled(false);
                lanExecutor.execute(() -> {
                    LanTransferManager.PeerInfo peer = lanTransferManager.pingPeer(host, LanTransferManager.DEFAULT_HTTP_PORT, 3500);
                    mainHandler.post(() -> {
                        btnConnectIp.setEnabled(true);
                        if (peer != null) {
                            if (lanTransferManager != null && (lanTransferManager.isSelf(peer) || lanTransferManager.isSelfIp(peer.ip))) {
                                if (tvPeerStatus != null) {
                                    tvPeerStatus.setText("该地址为本机自身设备，无需连接");
                                }
                                Toast.makeText(MainActivity.this, "该设备为本机自身，无需连接", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            boolean found = false;
                            for (int i = 0; i < discoveredPeers.size(); i++) {
                                if (discoveredPeers.get(i).ip.equals(peer.ip)) {
                                    discoveredPeers.set(i, peer);
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) discoveredPeers.add(0, peer);
                            if (renderPeerChipsHolder[0] != null) renderPeerChipsHolder[0].run();
                            startPeerConnectionFlow.accept(peer);
                        } else {
                            if (tvPeerStatus != null) {
                                tvPeerStatus.setText("❌ 无法连接到 " + host + "，请确认处于同一 Wi-Fi 且对方已打开阅读器");
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
        if (btnFilterMenu != null) {
            btnFilterMenu.setOnClickListener(v -> {
                int visibleCount = selectAdapter[0] != null ? selectAdapter[0].getItemCount() : 0;
                boolean isAllSelected = selectAdapter[0] != null && selectAdapter[0].isAllSelected();
                showLanFilterActionMenu(
                        btnFilterMenu,
                        isPullTab[0],
                        lastMissingCountHolder[0],
                        visibleCount,
                        onlyMissingFilter[0],
                        isAllSelected,
                        () -> {
                            if (selectAdapter[0] != null) selectAdapter[0].toggleSelectAll();
                            updateFooterAction.run();
                        },
                        () -> {
                            onlyMissingFilter[0] = !onlyMissingFilter[0];
                            refreshTabList.run();
                        },
                        () -> {
                            onlyMissingFilter[0] = false;
                            refreshTabList.run();
                        },
                        () -> {
                            if (selectAdapter[0] != null) selectAdapter[0].clearSelection();
                            updateFooterAction.run();
                        }
                );
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
        } catch (Throwable t) {
            android.util.Log.e("ComicReader", "showLanTransferDialog failed", t);
            Toast.makeText(this, "打开局域网互传界面出错: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
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
            updateRadarProgress(status, percent);
        });
    }

    private void updateImportProgress(String status, int percent, String detail) {
        mainHandler.post(() -> {
            if (tvImportStatus != null) tvImportStatus.setText(status);
            if (tvImportPercent != null) tvImportPercent.setText(Math.min(100, Math.max(0, percent)) + "%");
            if (importProgressBar != null) importProgressBar.setProgress(Math.min(100, Math.max(0, percent)));
            if (tvImportDetail != null) tvImportDetail.setText(detail);
            updateRadarProgress(status, percent);
        });
    }

    private void hideImportProgress(long delayMs) {
        mainHandler.postDelayed(() -> {
            if (layoutImportProgress != null) {
                layoutImportProgress.setVisibility(View.GONE);
            }
            hideRadarProgress(delayMs);
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

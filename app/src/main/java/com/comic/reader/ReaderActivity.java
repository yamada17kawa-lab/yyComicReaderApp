package com.comic.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class ReaderActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private ComicReaderAdapter adapter;
    private View layoutTopBar;
    private View layoutBottomBar;
    private TextView tvTitle;
    private TextView tvPageIndicator;
    private SeekBar seekPage;
    private ImageView btnBack;
    private View btnPrevComic;
    private View btnNextComic;
    private View btnScrollTop;
    private View btnTopScrollTop;
    private View fabScrollTop;

    private int comicIndex = 0;
    private boolean isOnlineMode = false;
    private ComicItem currentComic;
    private LinearLayoutManager layoutManager;
    private boolean isControlsShowing = true;
    private SharedPreferences sp;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        sp = getSharedPreferences("comic_history", Context.MODE_PRIVATE);
        comicIndex = getIntent().getIntExtra("comic_index", 0);
        isOnlineMode = getIntent().getBooleanExtra("is_online_comic", false);

        recyclerView = findViewById(R.id.recycler_reader);
        layoutTopBar = findViewById(R.id.layout_top_bar);
        layoutBottomBar = findViewById(R.id.layout_bottom_bar);
        tvTitle = findViewById(R.id.tv_reader_title);
        tvPageIndicator = findViewById(R.id.tv_page_indicator);
        seekPage = findViewById(R.id.seek_page);
        btnBack = findViewById(R.id.btn_back);
        btnPrevComic = findViewById(R.id.btn_prev_comic);
        btnNextComic = findViewById(R.id.btn_next_comic);
        btnScrollTop = findViewById(R.id.btn_scroll_top);
        btnTopScrollTop = findViewById(R.id.btn_top_scroll_top);
        View btnSetCover = findViewById(R.id.btn_set_cover);
        fabScrollTop = findViewById(R.id.fab_scroll_top);

        btnBack.setOnClickListener(v -> finish());
        btnPrevComic.setOnClickListener(v -> switchComic(comicIndex - 1));
        btnNextComic.setOnClickListener(v -> switchComic(comicIndex + 1));
        if (btnSetCover != null) {
            if (isOnlineMode) {
                btnSetCover.setVisibility(View.GONE);
            } else {
                btnSetCover.setOnClickListener(v -> setCurrentPageAsCover());
            }
        }

        View.OnClickListener scrollToTopListener = v -> scrollToTop();
        if (btnScrollTop != null) btnScrollTop.setOnClickListener(scrollToTopListener);
        if (btnTopScrollTop != null) btnTopScrollTop.setOnClickListener(scrollToTopListener);
        if (fabScrollTop != null) fabScrollTop.setOnClickListener(scrollToTopListener);

        layoutManager = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        layoutManager.setItemPrefetchEnabled(true);
        layoutManager.setInitialPrefetchItemCount(3);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setItemViewCacheSize(4);

        // 1. 核心交互：滑动阅读漫画时立即隐藏悬浮控制UI，并动态切换回到顶部悬浮按钮
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                super.onScrolled(rv, dx, dy);
                if (Math.abs(dy) > 5 && isControlsShowing) {
                    hideControls();
                }

                int firstVisible = layoutManager.findFirstVisibleItemPosition();
                if (firstVisible != RecyclerView.NO_POSITION && currentComic != null) {
                    int currentPage = firstVisible + 1;
                    updatePageIndicator(currentPage);

                    if (fabScrollTop != null) {
                        fabScrollTop.setVisibility(firstVisible > 0 ? View.VISIBLE : View.GONE);
                    }

                    // 记录已读进度
                    sp.edit().putInt("progress_" + currentComic.getTitle(), currentPage).apply();
                    if (isOnlineMode && currentComic != null) {
                        notifyRemoteRecordRead(currentComic.getFolderPath(), currentComic.getId(), currentPage, false);
                    }
                }
            }
        });

        seekPage.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && currentComic != null) {
                    int targetPage = Math.max(1, progress);
                    updatePageIndicator(targetPage);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int targetPage = Math.max(1, seekBar.getProgress());
                layoutManager.scrollToPositionWithOffset(targetPage - 1, 0);
            }
        });

        loadComic(comicIndex);
    }

    private void loadComic(int index) {
        java.util.List<ComicItem> activeList = isOnlineMode
                ? ComicDataManager.getOnlineComicList()
                : ComicDataManager.getComicList();
        currentComic = isOnlineMode
                ? ComicDataManager.getOnlineComic(index)
                : ComicDataManager.getComic(index);
        if (currentComic == null || currentComic.getPagePaths().isEmpty()) {
            Toast.makeText(this, "漫画内容为空", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        comicIndex = index;
        tvTitle.setText(isOnlineMode ? ("☁️ " + currentComic.getTitle()) : currentComic.getTitle());

        if (isOnlineMode) {
            // 在线漫画模式：本地内存先递增预显示，同时异步上报电脑端更新电脑端数据库和界面
            int count = currentComic.getReadCount() + 1;
            currentComic.setReadCount(count);
            currentComic.setLastReadTime(System.currentTimeMillis());

            // 异步通知电脑端递增阅读次数并同步最新统计
            notifyRemoteRecordRead(currentComic.getFolderPath(), currentComic.getId(), 1, true);
        } else {
            // 本地漫画模式：记录并递增阅读次数与最近阅读时间到本地
            int count = currentComic.getReadCount() + 1;
            currentComic.setReadCount(count);
            currentComic.setLastReadTime(System.currentTimeMillis());

            sp.edit()
                    .putInt("read_count_" + currentComic.getId(), count)
                    .putInt("read_count_" + currentComic.getTitle(), count)
                    .putLong("last_read_time_" + currentComic.getId(), currentComic.getLastReadTime())
                    .putLong("last_read_time_" + currentComic.getTitle(), currentComic.getLastReadTime())
                    .apply();

            ShelfStorage.saveShelf(this, ComicDataManager.getComicList(), ShelfStorage.getSavedRootPath(this));
        }

        int total = currentComic.getTotalPages();
        seekPage.setMax(total);

        btnPrevComic.setEnabled(comicIndex > 0);
        btnNextComic.setEnabled(comicIndex < activeList.size() - 1);

        // 2. 核心交互：单击屏幕画面切换控制栏显隐
        adapter = new ComicReaderAdapter(this, currentComic.getPagePaths(), v -> toggleControls());
        recyclerView.setAdapter(adapter);

        // 恢复上次已读进度
        int savedPage = sp.getInt("progress_" + currentComic.getTitle(), 1);
        if (savedPage > 1 && savedPage <= total) {
            recyclerView.post(() -> {
                layoutManager.scrollToPositionWithOffset(savedPage - 1, 0);
                updatePageIndicator(savedPage);
            });
        } else {
            updatePageIndicator(1);
        }
    }

    private void switchComic(int newIndex) {
        java.util.List<ComicItem> activeList = isOnlineMode
                ? ComicDataManager.getOnlineComicList()
                : ComicDataManager.getComicList();
        if (newIndex >= 0 && newIndex < activeList.size()) {
            loadComic(newIndex);
        }
    }

    private void updatePageIndicator(int current) {
        if (currentComic == null) return;
        int total = currentComic.getTotalPages();
        tvPageIndicator.setText(current + " / " + total);
        seekPage.setProgress(current);
    }

    private void showControls() {
        if (isControlsShowing) return;
        layoutTopBar.animate().translationY(0f).alpha(1f).setDuration(220).start();
        layoutBottomBar.animate().translationY(0f).alpha(1f).setDuration(220).start();
        isControlsShowing = true;
    }

    private void hideControls() {
        if (!isControlsShowing) return;
        layoutTopBar.animate().translationY(-layoutTopBar.getHeight()).alpha(0f).setDuration(220).start();
        layoutBottomBar.animate().translationY(layoutBottomBar.getHeight()).alpha(0f).setDuration(220).start();
        isControlsShowing = false;
    }

    private void toggleControls() {
        if (isControlsShowing) {
            hideControls();
        } else {
            showControls();
        }
    }

    private void scrollToTop() {
        if (layoutManager != null) {
            layoutManager.scrollToPositionWithOffset(0, 0);
            updatePageIndicator(1);
            seekPage.setProgress(1);
            if (fabScrollTop != null) {
                fabScrollTop.setVisibility(View.GONE);
            }
            if (currentComic != null) {
                sp.edit().putInt("progress_" + currentComic.getTitle(), 1).apply();
            }
        }
    }

    private void setCurrentPageAsCover() {
        if (currentComic == null || currentComic.getPagePaths() == null || currentComic.getPagePaths().isEmpty()) {
            return;
        }
        int firstVisible = layoutManager != null ? layoutManager.findFirstVisibleItemPosition() : 0;
        if (firstVisible == RecyclerView.NO_POSITION || firstVisible < 0 || firstVisible >= currentComic.getPagePaths().size()) {
            firstVisible = Math.max(0, seekPage.getProgress() - 1);
        }
        String pagePath = currentComic.getPagePaths().get(firstVisible);
        ShelfStorage.cleanupCustomCoverFiles(this, currentComic.getId());
        ShelfStorage.saveCustomCover(this, currentComic, pagePath);
        ShelfStorage.saveShelf(this, ComicDataManager.getComicList(), ShelfStorage.getSavedRootPath(this));
        Toast.makeText(this, "🖼️ 已将当前第 " + (firstVisible + 1) + " 页设为《" + currentComic.getTitle() + "》的封面", Toast.LENGTH_SHORT).show();
    }

    private void notifyRemoteRecordRead(String baseUrl, String comicId, int page, boolean incrementViews) {
        if (baseUrl == null || !baseUrl.startsWith("http") || comicId == null || comicId.trim().isEmpty()) return;
        new Thread(() -> {
            try {
                String encodedId = java.net.URLEncoder.encode(comicId, "UTF-8");
                String urlStr = baseUrl + "/api/record-read?id=" + encodedId + "&page=" + page;
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoOutput(true);
                conn.getOutputStream().write(new byte[0]);
                if (conn.getResponseCode() == 200) {
                    java.io.InputStream in = conn.getInputStream();
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        bos.write(buf, 0, len);
                    }
                    org.json.JSONObject obj = new org.json.JSONObject(bos.toString("UTF-8"));
                    int remoteCount = obj.optInt("readCount", -1);
                    if (remoteCount > 0 && currentComic != null) {
                        runOnUiThread(() -> currentComic.setReadCount(remoteCount));
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }
}

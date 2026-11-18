package com.comic.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ComicShelfAdapter extends RecyclerView.Adapter<ComicShelfAdapter.ViewHolder> {
    private final Context context;
    private final List<ComicItem> list;
    private final OnItemClickListener listener;
    private final OnRemoveClickListener removeListener;
    private OnCoverClickListener coverListener;
    private OnRenameClickListener renameListener;
    private final SharedPreferences sp;

    private boolean isBatchMode = false;
    private boolean isOnlineShelfMode = false;
    private final Set<String> selectedIds = new HashSet<>();
    private OnBatchSelectionChangeListener batchChangeListener;

    public void setOnlineShelfMode(boolean onlineShelfMode) {
        this.isOnlineShelfMode = onlineShelfMode;
        if (onlineShelfMode) {
            this.isBatchMode = false;
            this.selectedIds.clear();
        }
        notifyDataSetChanged();
    }

    public boolean isOnlineShelfMode() {
        return isOnlineShelfMode;
    }

    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    public interface OnRemoveClickListener {
        void onRemoveClick(int position);
    }

    public interface OnCoverClickListener {
        void onCoverClick(int position);
    }

    public interface OnRenameClickListener {
        void onRenameClick(int position);
    }

    public interface OnBatchSelectionChangeListener {
        void onBatchSelectionChanged(int selectedCount, int totalCount);
    }

    public ComicShelfAdapter(Context context, List<ComicItem> list, OnItemClickListener listener, OnRemoveClickListener removeListener) {
        this.context = context;
        this.list = list;
        this.listener = listener;
        this.removeListener = removeListener;
        this.sp = context.getSharedPreferences("comic_history", Context.MODE_PRIVATE);
    }

    public void setCoverClickListener(OnCoverClickListener coverListener) {
        this.coverListener = coverListener;
    }

    public void setRenameClickListener(OnRenameClickListener renameListener) {
        this.renameListener = renameListener;
    }

    public void setBatchSelectionChangeListener(OnBatchSelectionChangeListener listener) {
        this.batchChangeListener = listener;
    }

    public void setBatchMode(boolean batchMode) {
        this.isBatchMode = batchMode;
        if (!batchMode) {
            selectedIds.clear();
        }
        notifyDataSetChanged();
        if (batchChangeListener != null) {
            batchChangeListener.onBatchSelectionChanged(selectedIds.size(), list.size());
        }
    }

    public boolean isBatchMode() {
        return isBatchMode;
    }

    public Set<String> getSelectedIds() {
        return selectedIds;
    }

    public void toggleSelectAll(boolean selectAll) {
        selectedIds.clear();
        if (selectAll) {
            for (ComicItem item : list) {
                selectedIds.add(item.getId());
            }
        }
        notifyDataSetChanged();
        if (batchChangeListener != null) {
            batchChangeListener.onBatchSelectionChanged(selectedIds.size(), list.size());
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_comic_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ComicItem item = list.get(position);
        holder.tvTitle.setText(item.getTitle());
        holder.tvPageCount.setText(item.getTotalPages() + " P");

        // 默认第一张图作为封面
        ImageLoader.getInstance().displayImage(context, item.getCoverPath(), holder.ivCover, 400, 600);

        // 显示历史已读进度（在线书架模式优先显示电脑端同步进度）
        int savedPage = isOnlineShelfMode ? item.getLastReadPage() : (sp != null ? sp.getInt("progress_" + item.getTitle(), 0) : 0);
        if (savedPage <= 1 && sp != null) {
            savedPage = Math.max(savedPage, sp.getInt("progress_" + item.getTitle(), 0));
        }
        if (savedPage > 1) {
            holder.tvProgress.setVisibility(View.VISIBLE);
            holder.tvProgress.setText("读至 P." + savedPage);
        } else {
            holder.tvProgress.setVisibility(View.GONE);
        }

        // 显示累计阅读次数：本地书架只显示本地阅读次数，在线书架只显示电脑端的阅读次数
        int readCount;
        if (isOnlineShelfMode) {
            // 在线书架模式：纯粹展示来自电脑端的阅读次数，绝不读取或混入手机本地记录
            readCount = Math.max(0, item.getReadCount());
        } else {
            // 本地书架模式：纯粹展示手机本地的阅读次数
            readCount = item.getReadCount();
            if (sp != null) {
                int spById = sp.getInt("read_count_" + item.getId(), 0);
                int spByTitle = sp.getInt("read_count_" + item.getTitle(), 0);
                readCount = Math.max(readCount, Math.max(spById, spByTitle));
                if (readCount != item.getReadCount()) {
                    item.setReadCount(readCount);
                }
            }
        }
        holder.tvReadCount.setVisibility(View.VISIBLE);
        holder.tvReadCount.setText("已读 " + readCount + " 次");

        int defaultCardBg = ContextCompat.getColor(context, R.color.bg_card);
        int selectedBg = ContextCompat.getColor(context, R.color.bg_card_selected);

        if (isOnlineShelfMode) {
            holder.cbSelect.setVisibility(View.GONE);
            holder.btnRemove.setVisibility(View.VISIBLE);
            if (holder.btnCover != null) holder.btnCover.setVisibility(View.VISIBLE);
            if (holder.btnRename != null) holder.btnRename.setVisibility(View.VISIBLE);
            holder.cardView.setCardBackgroundColor(defaultCardBg);
        } else if (isBatchMode) {
            holder.cbSelect.setVisibility(View.VISIBLE);
            boolean isSelected = selectedIds.contains(item.getId());
            holder.cbSelect.setChecked(isSelected);
            holder.btnRemove.setVisibility(View.GONE);
            if (holder.btnCover != null) holder.btnCover.setVisibility(View.GONE);
            if (holder.btnRename != null) holder.btnRename.setVisibility(View.GONE);
            holder.cardView.setCardBackgroundColor(isSelected ? selectedBg : defaultCardBg);
        } else {
            holder.cbSelect.setVisibility(View.GONE);
            holder.btnRemove.setVisibility(View.VISIBLE);
            if (holder.btnCover != null) holder.btnCover.setVisibility(View.VISIBLE);
            if (holder.btnRename != null) holder.btnRename.setVisibility(View.VISIBLE);
            holder.cardView.setCardBackgroundColor(defaultCardBg);
        }

        // 单击卡片
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos < 0 || pos >= list.size()) return;

            if (isBatchMode && !isOnlineShelfMode) {
                ComicItem clicked = list.get(pos);
                if (selectedIds.contains(clicked.getId())) {
                    selectedIds.remove(clicked.getId());
                } else {
                    selectedIds.add(clicked.getId());
                }
                notifyItemChanged(pos);
                if (batchChangeListener != null) {
                    batchChangeListener.onBatchSelectionChanged(selectedIds.size(), list.size());
                }
            } else {
                if (listener != null) {
                    listener.onItemClick(pos);
                }
            }
        });

        // 重置并绑定三点更多按钮及其状态
        holder.isMenuExpanded = false;
        if (holder.btnCardMore != null) {
            holder.btnCardMore.setRotation(0f);
            holder.btnCardMore.setVisibility(isBatchMode ? View.GONE : View.VISIBLE);
            holder.btnCardMore.setOnClickListener(v -> toggleCardMenu(holder));
        }
        if (holder.layoutCardActions != null) {
            holder.layoutCardActions.setVisibility(View.GONE);
            holder.layoutCardActions.setAlpha(0f);
        }

        // 移除按钮点击
        if (holder.btnRemove != null) {
            holder.btnRemove.setOnClickListener(v -> {
                collapseCardMenu(holder);
                int pos = holder.getBindingAdapterPosition();
                if (pos >= 0 && pos < list.size() && removeListener != null) {
                    removeListener.onRemoveClick(pos);
                }
            });
        }

        // 更换自定义封面按钮点击
        if (holder.btnCover != null) {
            holder.btnCover.setOnClickListener(v -> {
                collapseCardMenu(holder);
                int pos = holder.getBindingAdapterPosition();
                if (pos >= 0 && pos < list.size() && coverListener != null) {
                    coverListener.onCoverClick(pos);
                }
            });
        }

        // 修改漫画文件名按钮点击
        if (holder.btnRename != null) {
            holder.btnRename.setOnClickListener(v -> {
                collapseCardMenu(holder);
                int pos = holder.getBindingAdapterPosition();
                if (pos >= 0 && pos < list.size() && renameListener != null) {
                    renameListener.onRenameClick(pos);
                }
            });
        }

        // 长按卡片快捷进入批量选择模式
        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos < 0 || pos >= list.size() || isOnlineShelfMode) return false;
            if (!isBatchMode) {
                setBatchMode(true);
                selectedIds.add(list.get(pos).getId());
                notifyDataSetChanged();
                if (batchChangeListener != null) {
                    batchChangeListener.onBatchSelectionChanged(selectedIds.size(), list.size());
                }
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        CardView cardView;
        ImageView ivCover;
        TextView tvTitle;
        TextView tvPageCount;
        TextView tvProgress;
        TextView tvReadCount;
        ImageView btnCardMore;
        View layoutCardActions;
        View btnRemove;
        View btnCover;
        View btnRename;
        CheckBox cbSelect;
        boolean isMenuExpanded = false;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.card_view_comic);
            ivCover = itemView.findViewById(R.id.iv_cover);
            tvTitle = itemView.findViewById(R.id.tv_comic_title);
            tvPageCount = itemView.findViewById(R.id.tv_page_count);
            tvProgress = itemView.findViewById(R.id.tv_progress);
            tvReadCount = itemView.findViewById(R.id.tv_read_count);
            btnCardMore = itemView.findViewById(R.id.btn_card_more);
            layoutCardActions = itemView.findViewById(R.id.layout_card_actions);
            btnRemove = itemView.findViewById(R.id.btn_remove);
            btnCover = itemView.findViewById(R.id.btn_cover);
            btnRename = itemView.findViewById(R.id.btn_rename);
            cbSelect = itemView.findViewById(R.id.cb_card_select);
        }
    }

    // 三点更多按钮的顺时针急刹回弹旋转与向下甩出操作菜单动画
    private void toggleCardMenu(ViewHolder holder) {
        if (holder == null || holder.btnCardMore == null || holder.layoutCardActions == null) return;
        if (!holder.isMenuExpanded) {
            expandCardMenu(holder);
        } else {
            collapseCardMenu(holder);
        }
    }

    private void expandCardMenu(ViewHolder holder) {
        if (holder == null || holder.btnCardMore == null || holder.layoutCardActions == null) return;
        holder.isMenuExpanded = true;

        // 1. 三点图标顺时针快速旋转半圈 (180度)，超过半圈后急刹惯性回弹 (OvershootInterpolator 2.6f)
        holder.btnCardMore.animate()
                .rotation(180f)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.6f))
                .setDuration(360)
                .start();

        // 2. 操作菜单向下甩出，带惯性回弹动效 (OvershootInterpolator 2.2f)
        holder.layoutCardActions.setVisibility(View.VISIBLE);
        holder.layoutCardActions.setAlpha(0f);
        holder.layoutCardActions.setTranslationY(-28f);
        holder.layoutCardActions.setScaleX(0.5f);
        holder.layoutCardActions.setScaleY(0.5f);
        holder.layoutCardActions.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                .setDuration(360)
                .start();
    }

    private void collapseCardMenu(ViewHolder holder) {
        if (holder == null || holder.btnCardMore == null || holder.layoutCardActions == null) return;
        if (!holder.isMenuExpanded && holder.layoutCardActions.getVisibility() == View.GONE) return;
        holder.isMenuExpanded = false;

        // 逆时针收起旋转
        holder.btnCardMore.animate()
                .rotation(0f)
                .setInterpolator(new android.view.animation.AnticipateInterpolator(1.8f))
                .setDuration(280)
                .start();

        // 操作菜单向上收起淡出
        holder.layoutCardActions.animate()
                .alpha(0f)
                .translationY(-20f)
                .scaleX(0.5f)
                .scaleY(0.5f)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .setDuration(220)
                .withEndAction(() -> {
                    if (!holder.isMenuExpanded) {
                        holder.layoutCardActions.setVisibility(View.GONE);
                    }
                })
                .start();
    }

}

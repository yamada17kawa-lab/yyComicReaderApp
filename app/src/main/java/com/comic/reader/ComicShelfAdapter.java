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

        // 显示历史已读进度
        int savedPage = sp.getInt("progress_" + item.getTitle(), 0);
        if (savedPage > 1) {
            holder.tvProgress.setVisibility(View.VISIBLE);
            holder.tvProgress.setText("读至 P." + savedPage);
        } else {
            holder.tvProgress.setVisibility(View.GONE);
        }

        // 显示累计阅读次数 (本地书架与在线书架均实时同步并展示阅读次数)
        int readCount = item.getReadCount();
        if (sp != null) {
            int spById = sp.getInt("read_count_" + item.getId(), 0);
            int spByTitle = sp.getInt("read_count_" + item.getTitle(), 0);
            readCount = Math.max(readCount, Math.max(spById, spByTitle));
            if (readCount != item.getReadCount()) {
                item.setReadCount(readCount);
            }
        }
        if (isOnlineShelfMode || readCount > 0) {
            holder.tvReadCount.setVisibility(View.VISIBLE);
            holder.tvReadCount.setText("已读 " + readCount + " 次");
        } else {
            holder.tvReadCount.setVisibility(View.VISIBLE);
            holder.tvReadCount.setText("已读 0 次");
        }

        int defaultCardBg = ContextCompat.getColor(context, R.color.bg_card);
        int selectedBg = ContextCompat.getColor(context, R.color.bg_card_selected);

        if (isOnlineShelfMode) {
            holder.cbSelect.setVisibility(View.GONE);
            holder.btnRemove.setVisibility(View.GONE);
            if (holder.btnCover != null) holder.btnCover.setVisibility(View.GONE);
            if (holder.btnRename != null) holder.btnRename.setVisibility(View.GONE);
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

        // 移除按钮点击与长按
        holder.btnRemove.setOnClickListener(v -> {
            if (removeListener != null) {
                removeListener.onRemoveClick(holder.getBindingAdapterPosition());
            }
        });

        // 更换自定义封面按钮点击
        if (holder.btnCover != null) {
            holder.btnCover.setOnClickListener(v -> {
                if (coverListener != null) {
                    coverListener.onCoverClick(holder.getBindingAdapterPosition());
                }
            });
        }

        // 修改漫画文件名按钮点击
        if (holder.btnRename != null) {
            holder.btnRename.setOnClickListener(v -> {
                if (renameListener != null) {
                    renameListener.onRenameClick(holder.getBindingAdapterPosition());
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
        View btnRemove;
        View btnCover;
        View btnRename;
        CheckBox cbSelect;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.card_view_comic);
            ivCover = itemView.findViewById(R.id.iv_cover);
            tvTitle = itemView.findViewById(R.id.tv_comic_title);
            tvPageCount = itemView.findViewById(R.id.tv_page_count);
            tvProgress = itemView.findViewById(R.id.tv_progress);
            tvReadCount = itemView.findViewById(R.id.tv_read_count);
            btnRemove = itemView.findViewById(R.id.btn_remove);
            btnCover = itemView.findViewById(R.id.btn_cover);
            btnRename = itemView.findViewById(R.id.btn_rename);
            cbSelect = itemView.findViewById(R.id.cb_card_select);
        }
    }
}

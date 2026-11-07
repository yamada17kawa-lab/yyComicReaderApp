package com.comic.reader;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LanComicSelectAdapter extends RecyclerView.Adapter<LanComicSelectAdapter.ViewHolder> {

    public static class Entry {
        public final String id;
        public final String title;
        public final int pageCount;
        public final int readCount;
        public final boolean alreadyOwned;
        public final String ownedLabel;

        public Entry(String id, String title, int pageCount) {
            this(id, title, pageCount, 0, false, "");
        }

        public Entry(String id, String title, int pageCount, boolean alreadyOwned, String ownedLabel) {
            this(id, title, pageCount, 0, alreadyOwned, ownedLabel);
        }

        public Entry(String id, String title, int pageCount, int readCount, boolean alreadyOwned, String ownedLabel) {
            this.id = id;
            this.title = title;
            this.pageCount = pageCount;
            this.readCount = Math.max(0, readCount);
            this.alreadyOwned = alreadyOwned;
            this.ownedLabel = ownedLabel != null ? ownedLabel : "";
        }
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedCount);
    }

    public interface OnOnlineReadClickListener {
        void onOnlineReadClick(Entry entry);
    }

    private final List<Entry> items = new ArrayList<>();
    private final Set<String> selectedIds = new HashSet<>();
    private final OnSelectionChangedListener listener;
    private OnOnlineReadClickListener onlineReadListener;
    private boolean showOnlineReadButton = false;

    public LanComicSelectAdapter(OnSelectionChangedListener listener) {
        this.listener = listener;
    }

    public void setOnlineReadClickListener(OnOnlineReadClickListener onlineReadListener) {
        this.onlineReadListener = onlineReadListener;
    }

    public void setShowOnlineReadButton(boolean show) {
        this.showOnlineReadButton = show;
    }

    public void setEntries(List<Entry> list, Set<String> activeSelection) {
        items.clear();
        if (list != null) items.addAll(list);
        selectedIds.clear();
        if (activeSelection != null) selectedIds.addAll(activeSelection);
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged(selectedIds.size());
    }

    public Set<String> getSelectedIds() {
        return new HashSet<>(selectedIds);
    }

    public void toggleSelectAll() {
        boolean allSelected = !items.isEmpty();
        for (Entry e : items) {
            if (!selectedIds.contains(e.id)) {
                allSelected = false;
                break;
            }
        }
        if (allSelected) {
            for (Entry e : items) {
                selectedIds.remove(e.id);
            }
        } else {
            for (Entry e : items) {
                selectedIds.add(e.id);
            }
        }
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged(selectedIds.size());
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_lan_comic_select, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Entry entry = items.get(position);
        boolean checked = selectedIds.contains(entry.id);

        holder.cb.setChecked(checked);
        holder.tvTitle.setText(entry.title);
        holder.tvTitle.setAlpha(entry.alreadyOwned ? 0.75f : 1.0f);
        holder.tvPages.setText("共 " + entry.pageCount + " 页");
        holder.tvReadCount.setText("已读 " + entry.readCount + " 次");

        if (entry.alreadyOwned && !entry.ownedLabel.isEmpty()) {
            holder.tvBadge.setText(entry.ownedLabel);
            holder.tvBadge.setVisibility(View.VISIBLE);
        } else {
            holder.tvBadge.setVisibility(View.GONE);
        }

        if (showOnlineReadButton && onlineReadListener != null) {
            holder.btnOnlineRead.setVisibility(View.VISIBLE);
            holder.btnOnlineRead.setOnClickListener(v -> onlineReadListener.onOnlineReadClick(entry));
        } else {
            holder.btnOnlineRead.setVisibility(View.GONE);
            holder.btnOnlineRead.setOnClickListener(null);
        }

        float density = holder.itemView.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        if (checked) {
            bg.setColor(Color.parseColor("#2E6366F1"));
            bg.setStroke(Math.max(1, Math.round(1.2f * density)), Color.parseColor("#818CF8"));
        } else {
            bg.setColor(Color.parseColor("#1D202C"));
            bg.setStroke(Math.max(1, Math.round(density)), Color.parseColor("#2A2F42"));
        }
        holder.itemView.setBackground(bg);

        holder.itemView.setOnClickListener(v -> {
            if (selectedIds.contains(entry.id)) {
                selectedIds.remove(entry.id);
            } else {
                selectedIds.add(entry.id);
            }
            notifyItemChanged(holder.getBindingAdapterPosition());
            if (listener != null) listener.onSelectionChanged(selectedIds.size());
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final View root;
        final CheckBox cb;
        final TextView tvTitle;
        final TextView tvBadge;
        final TextView tvPages;
        final TextView tvReadCount;
        final TextView btnOnlineRead;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            root = itemView.findViewById(R.id.item_lan_root);
            cb = itemView.findViewById(R.id.cb_lan_select);
            tvTitle = itemView.findViewById(R.id.tv_lan_item_title);
            tvBadge = itemView.findViewById(R.id.tv_lan_item_badge);
            tvPages = itemView.findViewById(R.id.tv_lan_item_pages);
            tvReadCount = itemView.findViewById(R.id.tv_lan_item_read_count);
            btnOnlineRead = itemView.findViewById(R.id.btn_lan_online_read);
        }
    }
}

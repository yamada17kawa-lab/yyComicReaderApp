package com.comic.reader;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class MissingComicsAdapter extends RecyclerView.Adapter<MissingComicsAdapter.ViewHolder> {

    public interface OnDeleteClickListener {
        void onDeleteClick(ComicItem item);
    }

    private final List<ComicItem> items;
    private final OnDeleteClickListener onDeleteListener;

    public MissingComicsAdapter(List<ComicItem> items, OnDeleteClickListener onDeleteListener) {
        this.items = items;
        this.onDeleteListener = onDeleteListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_missing_comic, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ComicItem item = items.get(position);
        holder.tvTitle.setText(item.getTitle());
        holder.tvReadInfo.setText("读过 " + item.getReadCount() + " 次");
        String path = item.getFolderPath();
        if (path == null || path.isEmpty()) {
            path = item.getCoverPath();
        }
        holder.tvPath.setText("原路径: " + (path != null ? path : "未知"));

        holder.btnDelete.setOnClickListener(v -> {
            if (onDeleteListener != null) {
                onDeleteListener.onDeleteClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvTitle;
        final TextView tvReadInfo;
        final TextView tvPath;
        final View btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_missing_title);
            tvReadInfo = itemView.findViewById(R.id.tv_missing_read_info);
            tvPath = itemView.findViewById(R.id.tv_missing_path);
            btnDelete = itemView.findViewById(R.id.btn_delete_missing_single);
        }
    }
}

package com.comic.reader;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ComicReaderAdapter extends RecyclerView.Adapter<ComicReaderAdapter.PageViewHolder> {
    private final Context context;
    private final List<String> pagePaths;
    private final View.OnClickListener onPageClickListener;

    public ComicReaderAdapter(Context context, List<String> pagePaths, View.OnClickListener onPageClickListener) {
        this.context = context;
        this.pagePaths = pagePaths;
        this.onPageClickListener = onPageClickListener;
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_comic_page, parent, false);
        return new PageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        String path = pagePaths.get(position);
        // 按屏幕宽度（约 1080 - 1440）解码，保持清晰同时极大降低显存消耗
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        ImageLoader.getInstance().displayImage(context, path, holder.ivPage, screenWidth, screenWidth * 2);

        holder.itemView.setOnClickListener(onPageClickListener);
    }

    @Override
    public int getItemCount() {
        return pagePaths.size();
    }

    public static class PageViewHolder extends RecyclerView.ViewHolder {
        ImageView ivPage;

        public PageViewHolder(@NonNull View itemView) {
            super(itemView);
            ivPage = itemView.findViewById(R.id.iv_page);
        }
    }
}

package com.comic.reader;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ComicItem implements Serializable {
    private String id;
    private String title;
    private String folderPath;
    private String coverPath;
    private String customCoverPath = "";
    private List<String> pagePaths;
    private int readCount;
    private long lastReadTime;
    private long importTime;
    private boolean isMissing = false;

    public ComicItem() {
        this.pagePaths = new ArrayList<>();
        this.readCount = 0;
        this.lastReadTime = 0;
        this.importTime = System.currentTimeMillis();
    }

    public ComicItem(String id, String title, String folderPath, String coverPath, List<String> pagePaths) {
        this.id = id;
        this.title = title;
        this.folderPath = folderPath;
        this.coverPath = coverPath;
        this.pagePaths = pagePaths != null ? pagePaths : new ArrayList<>();
        this.readCount = 0;
        this.lastReadTime = 0;
        this.importTime = System.currentTimeMillis();
    }

    public ComicItem(String id, String title, String folderPath, String coverPath, List<String> pagePaths, int readCount, long lastReadTime) {
        this.id = id;
        this.title = title;
        this.folderPath = folderPath;
        this.coverPath = coverPath;
        this.pagePaths = pagePaths != null ? pagePaths : new ArrayList<>();
        this.readCount = readCount;
        this.lastReadTime = lastReadTime;
        this.importTime = System.currentTimeMillis();
    }

    public ComicItem(String id, String title, String folderPath, String coverPath, List<String> pagePaths, int readCount, long lastReadTime, long importTime) {
        this.id = id;
        this.title = title;
        this.folderPath = folderPath;
        this.coverPath = coverPath;
        this.pagePaths = pagePaths != null ? pagePaths : new ArrayList<>();
        this.readCount = readCount;
        this.lastReadTime = lastReadTime;
        this.importTime = importTime > 0 ? importTime : System.currentTimeMillis();
    }

    public String getId() {
        return id != null ? id : (title != null ? title : "");
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title != null ? title : "";
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getFolderPath() {
        return folderPath;
    }

    public void setFolderPath(String folderPath) {
        this.folderPath = folderPath;
    }

    public String getCoverPath() {
        return coverPath;
    }

    public void setCoverPath(String coverPath) {
        this.coverPath = coverPath;
    }

    public String getCustomCoverPath() {
        return customCoverPath != null ? customCoverPath : "";
    }

    public void setCustomCoverPath(String customCoverPath) {
        this.customCoverPath = customCoverPath != null ? customCoverPath : "";
    }

    public List<String> getPagePaths() {
        return pagePaths;
    }

    public void setPagePaths(List<String> pagePaths) {
        this.pagePaths = pagePaths;
    }

    public int getTotalPages() {
        return pagePaths != null ? pagePaths.size() : 0;
    }

    public int getReadCount() {
        return readCount;
    }

    public void setReadCount(int readCount) {
        this.readCount = readCount;
    }

    public long getLastReadTime() {
        return lastReadTime;
    }

    public void setLastReadTime(long lastReadTime) {
        this.lastReadTime = lastReadTime;
    }

    public long getImportTime() {
        return importTime;
    }

    public void setImportTime(long importTime) {
        this.importTime = importTime;
    }

    public boolean isMissing() {
        return isMissing;
    }

    public void setMissing(boolean missing) {
        this.isMissing = missing;
    }
}

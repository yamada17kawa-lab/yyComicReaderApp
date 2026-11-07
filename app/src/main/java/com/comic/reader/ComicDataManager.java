package com.comic.reader;

import java.util.ArrayList;
import java.util.List;

public class ComicDataManager {
    private static List<ComicItem> currentComicList = new ArrayList<>();
    private static List<ComicItem> onlineComicList = new ArrayList<>();

    public static void setComicList(List<ComicItem> list) {
        currentComicList = list != null ? list : new ArrayList<>();
    }

    public static List<ComicItem> getComicList() {
        return currentComicList;
    }

    public static ComicItem getComic(int index) {
        if (index >= 0 && index < currentComicList.size()) {
            return currentComicList.get(index);
        }
        return null;
    }

    public static void setOnlineComicList(List<ComicItem> list) {
        onlineComicList = list != null ? list : new ArrayList<>();
    }

    public static List<ComicItem> getOnlineComicList() {
        return onlineComicList;
    }

    public static ComicItem getOnlineComic(int index) {
        if (index >= 0 && index < onlineComicList.size()) {
            return onlineComicList.get(index);
        }
        return null;
    }
}

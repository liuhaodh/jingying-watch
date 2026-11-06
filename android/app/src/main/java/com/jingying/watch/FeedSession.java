package com.jingying.watch;

import java.util.ArrayList;
import java.util.List;

/** 从首页进入播放器时传递的作品列表（进程内传递，不需要序列化）。 */
public final class FeedSession {

    private static List<Work> items = new ArrayList<>();
    private static int index;
    private static String label = "";
    private static boolean preview;

    private FeedSession() {
    }

    public static void open(List<Work> works, int start, String title) {
        items = new ArrayList<>(works == null ? new ArrayList<>() : works);
        index = Math.max(0, Math.min(start, Math.max(0, items.size() - 1)));
        label = title == null ? "" : title;
        preview = false;
    }

    /** 回收站预览：只放一条，下载/收藏/删除都不可用。 */
    public static void openPreview(Work work) {
        List<Work> one = new ArrayList<>();
        if (work != null) {
            one.add(work);
        }
        items = one;
        index = 0;
        label = "回收站预览";
        preview = true;
    }

    public static boolean isPreview() {
        return preview;
    }

    public static List<Work> items() {
        return new ArrayList<>(items);
    }

    public static int index() {
        return index;
    }

    public static String label() {
        return label;
    }

    public static void update(List<Work> works, int start) {
        open(works, start, label);
    }
}

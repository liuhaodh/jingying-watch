package com.jingying.watch;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 收藏：只存作品路径（rel），播放时按 rel 去片库索引里找作品。
 *
 * 存 rel 而不是 key，是为了换手机 / 清数据后还能按路径对上；
 * 顺序按「最近收藏在前」，落盘用换行分隔的字符串
 * （SharedPreferences 的 StringSet 不保证顺序）。
 */
public final class FavoriteStore {

    private static final String FILE = "favorites";
    private static final String KEY = "list";
    /** 收藏夹上限，防止无限增长。 */
    private static final int LIMIT = 5000;

    private final SharedPreferences sp;
    private final List<String> rels = new ArrayList<>();
    private final Set<String> lookup = new HashSet<>();

    public FavoriteStore(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        load();
    }

    public synchronized boolean isFavorite(String rel) {
        return rel != null && !rel.isEmpty() && lookup.contains(rel);
    }

    /** 切换收藏，返回切换后的状态：true = 已收藏。 */
    public synchronized boolean toggle(String rel) {
        if (rel == null || rel.isEmpty()) {
            return false;
        }
        if (lookup.remove(rel)) {
            rels.remove(rel);
            persist();
            return false;
        }
        rels.add(0, rel);
        lookup.add(rel);
        while (rels.size() > LIMIT) {
            String dropped = rels.remove(rels.size() - 1);
            lookup.remove(dropped);
        }
        persist();
        return true;
    }

    public synchronized void remove(String rel) {
        if (rel == null) {
            return;
        }
        if (lookup.remove(rel)) {
            rels.remove(rel);
            persist();
        }
    }

    /** 最近收藏的在前。 */
    public synchronized List<String> rels() {
        return new ArrayList<>(rels);
    }

    public synchronized int count() {
        return rels.size();
    }

    private void load() {
        String text = sp.getString(KEY, "");
        if (text == null || text.isEmpty()) {
            return;
        }
        for (String line : text.split("\n")) {
            String rel = line.trim();
            if (!rel.isEmpty() && lookup.add(rel)) {
                rels.add(rel);
            }
        }
    }

    private void persist() {
        StringBuilder builder = new StringBuilder();
        for (String rel : rels) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(rel);
        }
        sp.edit().putString(KEY, builder.toString()).apply();
    }
}

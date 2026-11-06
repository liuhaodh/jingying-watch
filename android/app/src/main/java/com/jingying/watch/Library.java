package com.jingying.watch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 片库统计与筛选（纯函数，界面和测试都用它）。 */
public final class Library {

    private Library() {
    }

    public static final class AuthorInfo {
        public final String name;
        public final String uid;
        public int count;
        public long bytes;
        public long latest;

        AuthorInfo(String name, String uid) {
            this.name = name;
            this.uid = uid == null ? "" : uid;
        }
    }

    public static final class Stats {
        public int count;
        public int videos;
        public int albums;
        public int authors;
        public int details;
        public long bytes;
    }

    public static List<AuthorInfo> authors(List<Work> works) {
        Map<String, AuthorInfo> grouped = new LinkedHashMap<>();
        for (Work work : works) {
            String name = work.displayAuthor();
            AuthorInfo info = grouped.get(name);
            if (info == null) {
                info = new AuthorInfo(name, work.uniqueId);
                grouped.put(name, info);
            }
            info.count++;
            info.bytes += work.totalBytes();
            info.latest = Math.max(info.latest, work.time);
        }
        List<AuthorInfo> result = new ArrayList<>(grouped.values());
        Collections.sort(result, new Comparator<AuthorInfo>() {
            @Override
            public int compare(AuthorInfo left, AuthorInfo right) {
                if (left.count != right.count) {
                    return Integer.compare(right.count, left.count);
                }
                return left.name.compareTo(right.name);
            }
        });
        return result;
    }

    public static Stats stats(List<Work> works) {
        Stats stats = new Stats();
        stats.count = works.size();
        // 作者数直接去重统计，不用为了一个数字再排一次作者列表
        Set<String> authors = new HashSet<>();
        for (Work work : works) {
            if (work.isVideo()) {
                stats.videos++;
            } else if (work.isAlbum()) {
                stats.albums++;
            }
            if (work.detailsLoaded) {
                stats.details++;
            }
            stats.bytes += work.totalBytes();
            authors.add(work.displayAuthor());
        }
        stats.authors = authors.size();
        return stats;
    }

    public static List<Work> filter(List<Work> works, String author, String query, String kind) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.CHINA);
        List<Work> result = new ArrayList<>();
        for (Work work : works) {
            if (author != null && !author.isEmpty()
                    && !author.equals(work.displayAuthor())) {
                continue;
            }
            if ("video".equals(kind) && !work.isVideo()) {
                continue;
            }
            if ("album".equals(kind) && !work.isAlbum()) {
                continue;
            }
            if (!needle.isEmpty() && !work.searchText().contains(needle)) {
                continue;
            }
            result.add(work);
        }
        sortByTime(result);
        return result;
    }

    public static void sortByTime(List<Work> works) {
        Collections.sort(works, new Comparator<Work>() {
            @Override
            public int compare(Work left, Work right) {
                if (left.time != right.time) {
                    return Long.compare(right.time, left.time);
                }
                return Long.compare(right.mtime, left.mtime);
            }
        });
    }
}

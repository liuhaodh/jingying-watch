package com.jingying.watch;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 离线作品目录的展示辅助：判断「是否真的下载完了」、列出目录里的作品文件。
 *
 * <p>只依赖 java.*，方便用桌面 JVM 跑单元测试。离线目录的结构见 OfflineStore：
 * {@code <externalFiles>/offline/w<hash>/} 下直接放媒体原文件、文案.txt，
 * 下载成功后才原子写入 meta.json；下载过程中是 {@code 原名.part} / {@code 原名.tmp}。</p>
 */
public final class LocalFiles {

    private static final String META = "meta.json";

    private LocalFiles() {
    }

    /**
     * 是否可当作「已下载到本地」展示。
     * meta.json 是下载成功后的完成标记，同时要求目录里至少有一个作品文件 ——
     * 这样下载中、下载失败被清空的目录都不会被误判。
     */
    public static boolean isDownloaded(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        if (!new File(dir, META).isFile()) {
            return false;
        }
        return !files(dir).isEmpty();
    }

    /**
     * 目录里的作品文件：排除完成标记、隐藏文件、下载中的临时文件和子目录，按文件名排序。
     * 顺序固定，避免同一作品每次打开详情看到的文件顺序乱跳。
     */
    public static List<File> files(File dir) {
        List<File> result = new ArrayList<>();
        if (dir == null) {
            return result;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return result;
        }
        for (File child : children) {
            if (!child.isFile() || ignored(child.getName())) {
                continue;
            }
            result.add(child);
        }
        Collections.sort(result, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareTo(right.getName());
            }
        });
        return result;
    }

    private static boolean ignored(String name) {
        return name == null || name.isEmpty()
                || name.startsWith(".")
                || META.equals(name)
                || name.endsWith(".part")
                || name.endsWith(".tmp");
    }

    /**
     * 生成「文件名 · 大小」清单，最多 maxLines 行；多出来的折成一行提示，
     * 免得几十张图的图集把详情弹层顶到满屏。目录里没有作品文件时返回空串。
     */
    public static String listing(File dir, int maxLines) {
        List<File> list = files(dir);
        if (list.isEmpty()) {
            return "";
        }
        int limit = Math.max(1, maxLines);
        int shown = Math.min(limit, list.size());
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < shown; index++) {
            if (index > 0) {
                text.append('\n');
            }
            File file = list.get(index);
            text.append(file.getName()).append("  ·  ")
                    .append(Util.humanSize(file.length()));
        }
        if (list.size() > shown) {
            text.append('\n').append("… 还有 ")
                    .append(list.size() - shown).append(" 个文件");
        }
        return text.toString();
    }
}

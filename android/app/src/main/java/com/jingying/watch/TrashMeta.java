package com.jingying.watch;

import java.util.ArrayList;
import java.util.List;

/**
 * 回收站 `meta.txt` 的读写。
 *
 * 用纯文本而不是 JSON，是为了在网盘网页端也能直接看懂这条记录是什么、原来在哪：
 *
 * <pre>
 * type=work
 * name=阿星_街拍合集_20261025_160000
 * deleted=1762320000
 * 1.rel=净影/Videos/阿星_94704804628/阿星_街拍合集_20261025_160000
 * 1.music=净影/Music/阿星_94704804628/阿星_街拍合集_20261025_160000
 * </pre>
 */
final class TrashMeta {

    static final class Parsed {
        String type = "unknown";
        String name = "";
        long deletedAt;
        final List<String> rels = new ArrayList<>();
        final List<String> musicRels = new ArrayList<>();
    }

    private TrashMeta() {
    }

    static String encode(String type, String name, long deletedAt,
                         List<String> rels, List<String> musicRels) {
        StringBuilder builder = new StringBuilder();
        builder.append("type=").append(type == null || type.isEmpty() ? "unknown" : type).append('\n');
        builder.append("name=").append(name == null ? "" : name).append('\n');
        builder.append("deleted=").append(deletedAt).append('\n');
        if (rels != null) {
            for (int index = 0; index < rels.size(); index++) {
                int number = index + 1;
                String rel = rels.get(index);
                if (rel != null && !rel.isEmpty()) {
                    builder.append(number).append(".rel=").append(rel).append('\n');
                }
                String music = musicRels != null && index < musicRels.size()
                        ? musicRels.get(index) : "";
                if (music != null && !music.isEmpty()) {
                    builder.append(number).append(".music=").append(music).append('\n');
                }
            }
        }
        return builder.toString();
    }

    static Parsed decode(String text) {
        Parsed parsed = new Parsed();
        if (text == null || text.isEmpty()) {
            return parsed;
        }
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if ("type".equals(key)) {
                if (!value.isEmpty()) {
                    parsed.type = value;
                }
            } else if ("name".equals(key)) {
                if (!value.isEmpty()) {
                    parsed.name = value;
                }
            } else if ("deleted".equals(key)) {
                parsed.deletedAt = parseLong(value, 0L);
            } else {
                int dot = key.indexOf('.');
                if (dot <= 0) {
                    continue;
                }
                int number = (int) parseLong(key.substring(0, dot), 0);
                if (number <= 0 || number > 20) {
                    continue;
                }
                if (number > parsed.rels.size() + 1) {
                    // 只接受连续编号：跳号说明文件被手改坏了，丢掉这一行
                    // 而不是撑出一堆空槽位
                    continue;
                }
                String field = key.substring(dot + 1);
                while (parsed.rels.size() < number) {
                    parsed.rels.add("");
                    parsed.musicRels.add("");
                }
                if ("rel".equals(field)) {
                    parsed.rels.set(number - 1, value);
                } else if ("music".equals(field)) {
                    parsed.musicRels.set(number - 1, value);
                }
            }
        }
        // 去掉末尾没有原始路径的空槽位
        while (!parsed.rels.isEmpty()
                && parsed.rels.get(parsed.rels.size() - 1).isEmpty()) {
            parsed.rels.remove(parsed.rels.size() - 1);
            parsed.musicRels.remove(parsed.musicRels.size() - 1);
        }
        return parsed;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception exc) {
            return fallback;
        }
    }
}

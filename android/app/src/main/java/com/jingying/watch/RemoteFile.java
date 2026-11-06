package com.jingying.watch;

import org.json.JSONObject;

import java.util.Locale;

/** WebDAV 里的一个文件（视频 / 图片 / 音乐 / 文案）。 */
public final class RemoteFile {

    public final String rel;
    public final String name;
    public final long size;

    public RemoteFile(String rel, String name, long size) {
        this.rel = rel == null ? "" : rel;
        this.name = name == null || name.isEmpty() ? baseName(this.rel) : name;
        this.size = Math.max(0L, size);
    }

    public static String baseName(String rel) {
        int index = rel == null ? -1 : rel.lastIndexOf('/');
        return index < 0 ? (rel == null ? "" : rel) : rel.substring(index + 1);
    }

    public String ext() {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot).toLowerCase(Locale.US) : "";
    }

    public String mime() {
        return Mime.of(name);
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("rel", rel);
            json.put("name", name);
            json.put("size", size);
        } catch (Exception ignored) {
        }
        return json;
    }

    public static RemoteFile fromJson(JSONObject json, String parentRel) {
        if (json == null) {
            return null;
        }
        String rel = json.optString("rel", "");
        String name = json.optString("name", "");
        if (rel.isEmpty() && !name.isEmpty()) {
            rel = parentRel == null || parentRel.isEmpty() ? name : parentRel + "/" + name;
        }
        return new RemoteFile(rel, name, json.optLong("size", 0L));
    }

    @Override
    public String toString() {
        return rel + " (" + size + ")";
    }
}

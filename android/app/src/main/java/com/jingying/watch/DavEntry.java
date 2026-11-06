package com.jingying.watch;

import java.util.Locale;

/** 一条 PROPFIND 结果。 */
public final class DavEntry {

    public final String rel;
    public final String name;
    public final boolean dir;
    public final long size;
    public final long mtime;

    public DavEntry(String rel, String name, boolean dir, long size, long mtime) {
        this.rel = rel == null ? "" : rel;
        this.name = name == null ? "" : name;
        this.dir = dir;
        this.size = Math.max(0L, size);
        this.mtime = mtime;
    }

    public String ext() {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot).toLowerCase(Locale.US) : "";
    }

    @Override
    public String toString() {
        return (dir ? "[D] " : "[F] ") + rel + " " + size;
    }
}

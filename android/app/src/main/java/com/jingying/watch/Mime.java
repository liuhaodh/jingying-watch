package com.jingying.watch;

import java.util.Locale;

/** 扩展名 ↔ MIME，播放器/图片加载都用得到。 */
public final class Mime {

    private Mime() {
    }

    public static String of(String name) {
        String ext = ext(name);
        switch (ext) {
            case ".mp4":
            case ".m4v":
                return "video/mp4";
            case ".mov":
                return "video/quicktime";
            case ".webm":
                return "video/webm";
            case ".mkv":
                return "video/x-matroska";
            case ".ts":
                return "video/mp2t";
            case ".jpg":
            case ".jpeg":
                return "image/jpeg";
            case ".png":
                return "image/png";
            case ".webp":
                return "image/webp";
            case ".gif":
                return "image/gif";
            case ".bmp":
                return "image/bmp";
            case ".avif":
                return "image/avif";
            case ".mp3":
                return "audio/mpeg";
            case ".m4a":
                return "audio/mp4";
            case ".aac":
                return "audio/aac";
            case ".wav":
                return "audio/wav";
            case ".flac":
                return "audio/flac";
            case ".ogg":
            case ".opus":
                return "audio/ogg";
            case ".txt":
            case ".md":
                return "text/plain; charset=utf-8";
            default:
                return "application/octet-stream";
        }
    }

    public static String ext(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot).toLowerCase(Locale.US) : "";
    }
}

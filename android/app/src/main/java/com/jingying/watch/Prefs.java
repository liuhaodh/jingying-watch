package com.jingying.watch;

import android.content.Context;
import android.content.SharedPreferences;

/** 本机设置：WebDAV 账号、缓存上限、静音等。 */
public final class Prefs {

    private static final String FILE = "watch";
    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String davUrl() {
        return sp.getString("dav_url", "");
    }

    public String davUser() {
        return sp.getString("dav_user", "");
    }

    public String davPass() {
        return sp.getString("dav_pass", "");
    }

    public String remoteRoot() {
        String value = sp.getString("remote_root", "净影");
        return value == null || value.trim().isEmpty() ? "净影" : value.trim();
    }

    public boolean insecure() {
        return sp.getBoolean("insecure", false);
    }

    public void saveDav(String url, String user, String pass, String root, boolean insecure) {
        sp.edit()
                .putString("dav_url", url == null ? "" : url.trim())
                .putString("dav_user", user == null ? "" : user)
                .putString("dav_pass", pass == null ? "" : pass)
                .putString("remote_root", root == null ? "" : root.trim())
                .putBoolean("insecure", insecure)
                .apply();
    }

    public boolean hasDav() {
        return !davUrl().trim().isEmpty();
    }

    public int cacheMb() {
        return sp.getInt("cache_mb", 4096);
    }

    public void setCacheMb(int mb) {
        sp.edit().putInt("cache_mb", Math.max(128, mb)).apply();
    }

    public boolean muted() {
        return sp.getBoolean("muted", false);
    }

    public void setMuted(boolean muted) {
        sp.edit().putBoolean("muted", muted).apply();
    }

    public boolean autoNext() {
        return sp.getBoolean("auto_next", true);
    }

    public void setAutoNext(boolean value) {
        sp.edit().putBoolean("auto_next", value).apply();
    }

    public boolean autoDownload() {
        return sp.getBoolean("auto_download", false);
    }

    public void setAutoDownload(boolean value) {
        sp.edit().putBoolean("auto_download", value).apply();
    }

    public String lastKey() {
        return sp.getString("last_key", "");
    }

    public int lastIndex() {
        return sp.getInt("last_index", 0);
    }

    public void setLast(String key, int index) {
        sp.edit().putString("last_key", key == null ? "" : key)
                .putInt("last_index", Math.max(0, index)).apply();
    }

    public long scannedAt() {
        return sp.getLong("scanned_at", 0L);
    }

    public void setScannedAt(long value) {
        sp.edit().putLong("scanned_at", value).apply();
    }
}

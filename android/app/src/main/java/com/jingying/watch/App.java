package com.jingying.watch;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** 全局单例：设置、片库索引、媒体代理、扫描器。 */
public final class App extends Application {

    private static App instance;

    public Prefs prefs;
    public LibraryStore store;
    public MediaProxy proxy;
    public LibraryScanner scanner;
    public ImageLoader images;
    public AvatarStore avatars;
    public OfflineStore offline;
    public FavoriteStore favorites;
    public TrashStore trash;
    public Handler main;
    public ExecutorService io;
    public ExecutorService details;

    private volatile DavClient dav;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        main = new Handler(Looper.getMainLooper());
        prefs = new Prefs(this);
        io = Executors.newFixedThreadPool(4, daemon("watch-io"));
        details = Executors.newFixedThreadPool(3, daemon("watch-details"));
        store = new LibraryStore(this);
        offline = new OfflineStore(this);
        io.execute(() -> {
            store.load();
            offline.load();
        });
        proxy = new MediaProxy(this);
        proxy.setCacheLimit(prefs.cacheMb() * 1024L * 1024L);
        proxy.start();
        proxy.setClient(dav());
        scanner = new LibraryScanner(this);
        images = new ImageLoader(this);
        avatars = new AvatarStore(this);
        favorites = new FavoriteStore(this);
        trash = new TrashStore(this);
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 当前 WebDAV 客户端；没配置或地址非法时返回 null。 */
    public DavClient dav() {
        if (!prefs.hasDav()) {
            return null;
        }
        DavClient current = dav;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (dav == null) {
                try {
                    dav = new DavClient(prefs.davUrl(), prefs.davUser(), prefs.davPass(),
                            prefs.remoteRoot(), prefs.insecure());
                } catch (IllegalArgumentException exc) {
                    dav = null;
                }
            }
            return dav;
        }
    }

    public synchronized void reloadDav() {
        dav = null;
        DavClient current = dav();
        proxy.setClient(current);
        proxy.setCacheLimit(prefs.cacheMb() * 1024L * 1024L);
    }

    public void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            main.post(runnable);
        }
    }

    public String hostLabel() {
        String url = prefs.davUrl();
        if (url == null || url.isEmpty()) {
            return "未连接";
        }
        try {
            String value = url.replaceFirst("^[a-zA-Z]+://", "");
            int slash = value.indexOf('/');
            return slash > 0 ? value.substring(0, slash) : value;
        } catch (Exception exc) {
            return url;
        }
    }
}

package com.jingying.watch;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** WebDAV 连接设置 + 缓存管理。 */
public final class SetupActivity extends Activity {

    private App app;
    private Prefs prefs;
    private EditText urlField;
    private EditText userField;
    private EditText passField;
    private EditText rootField;
    private CheckBox insecureBox;
    private TextView statusText;
    private TextView cacheText;
    private TextView offlineText;
    private LinearLayout limitRow;
    private TextView testButton;
    private TextView saveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = App.get();
        prefs = app.prefs;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildUi();
        loadCacheInfo();
        loadOfflineInfo();
    }

    private void loadOfflineInfo() {
        app.io.execute(() -> {
            final int count = app.offline.count();
            final long bytes = app.offline.totalBytes();
            final String path = app.offline.rootPath();
            runOnUiThread(() -> {
                if (offlineText != null) {
                    offlineText.setText("已下载 " + count + " 个 · " + Util.humanSize(bytes)
                            + "\n" + path);
                }
            });
        });
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 20), Ui.statusBarHeight(this) + Ui.dp(this, 14),
                Ui.dp(this, 20), Ui.dp(this, 34));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(titleRow);

        TextView back = Ui.text(this, "‹", 30, Ui.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, Ui.dp(this, 14), Ui.dp(this, 6));
        back.setOnClickListener(view -> finish());
        titleRow.addView(back);

        titleRow.addView(Ui.text(this, "连接 WebDAV 片库", 22, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView hint = Ui.label(this,
                "填 WebDAV 根地址。远程目录是存放 Videos / Music 的那一层，"
                        + "「净影」上传后默认就是 净影。", 12, Ui.DIM);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = Ui.dp(this, 8);
        content.addView(hint, hintParams);

        urlField = addField(content, "WebDAV 地址",
                "https://webdav.123pan.cn/webdav", InputType.TYPE_TEXT_VARIATION_URI,
                prefs.davUrl());
        userField = addField(content, "账号",
                "手机号 / 用户名", InputType.TYPE_CLASS_TEXT, prefs.davUser());
        passField = addField(content, "密码",
                "WebDAV 密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                prefs.davPass());
        rootField = addField(content, "远程目录",
                "净影", InputType.TYPE_CLASS_TEXT, prefs.remoteRoot());

        insecureBox = new CheckBox(this);
        insecureBox.setText("允许自签名证书（家用 NAS 常见）");
        insecureBox.setTextColor(Ui.DIM);
        insecureBox.setTextSize(12);
        insecureBox.setButtonTintList(ColorStateList.valueOf(Ui.DIM));
        insecureBox.setChecked(prefs.insecure());
        LinearLayout.LayoutParams insecureParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        insecureParams.topMargin = Ui.dp(this, 14);
        content.addView(insecureBox, insecureParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = Ui.dp(this, 20);
        content.addView(actions, actionsParams);

        testButton = actionButton("测试连接", false);
        testButton.setOnClickListener(view -> testConnection());
        actions.addView(testButton, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f));

        saveButton = actionButton("保存并扫描", true);
        saveButton.setOnClickListener(view -> save());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f);
        saveParams.leftMargin = Ui.dp(this, 10);
        actions.addView(saveButton, saveParams);

        statusText = Ui.label(this, "", 12, Ui.DIM);
        statusText.setPadding(0, Ui.dp(this, 14), 0, 0);
        content.addView(statusText);

        // 存储
        View divider = new View(this);
        divider.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        dividerParams.topMargin = Ui.dp(this, 24);
        content.addView(divider, dividerParams);

        content.addView(Ui.text(this, "缓存", 16, Ui.TEXT, true));

        cacheText = Ui.label(this, "正在统计…", 12, Ui.DIM);
        LinearLayout.LayoutParams cacheParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cacheParams.topMargin = Ui.dp(this, 8);
        content.addView(cacheText, cacheParams);

        limitRow = new LinearLayout(this);
        limitRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams limitParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        limitParams.topMargin = Ui.dp(this, 12);
        content.addView(limitRow, limitParams);
        buildLimitChips();

        LinearLayout cacheActions = new LinearLayout(this);
        cacheActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cacheActionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cacheActionParams.topMargin = Ui.dp(this, 12);
        content.addView(cacheActions, cacheActionParams);

        TextView clearMedia = actionButton("清空视频缓存", false);
        clearMedia.setOnClickListener(view -> {
            app.proxy.clearCache();
            loadCacheInfo();
            Ui.toast(this, "视频缓存已清空");
        });
        cacheActions.addView(clearMedia, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f));

        TextView clearCovers = actionButton("清空封面缓存", false);
        clearCovers.setOnClickListener(view -> {
            app.images.clearDisk();
            loadCacheInfo();
            Ui.toast(this, "封面缓存已清空");
        });
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 42), 1f);
        coverParams.leftMargin = Ui.dp(this, 10);
        cacheActions.addView(clearCovers, coverParams);

        LinearLayout archiveRow = new LinearLayout(this);
        archiveRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams archiveRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        archiveRowParams.topMargin = Ui.dp(this, 10);
        content.addView(archiveRow, archiveRowParams);

        TextView clearArchive = actionButton("清空解包缓存（作品包）", false);
        clearArchive.setOnClickListener(view -> {
            app.archives.clearAll();
            loadCacheInfo();
            Ui.toast(this, "解包缓存已清空");
        });
        archiveRow.addView(clearArchive, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f));

        // 本地下载
        View divider2 = new View(this);
        divider2.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams divider2Params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        divider2Params.topMargin = Ui.dp(this, 24);
        content.addView(divider2, divider2Params);

        content.addView(Ui.text(this, "本地下载", 16, Ui.TEXT, true));

        offlineText = Ui.label(this, "正在统计…", 12, Ui.DIM);
        LinearLayout.LayoutParams offlineParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        offlineParams.topMargin = Ui.dp(this, 8);
        content.addView(offlineText, offlineParams);

        TextView offlineNote = Ui.label(this,
                "下载的作品保存在 App 私有目录，并放了 .nomedia 标记，"
                        + "系统相册/媒体库不会显示；App 里可以离线观看。",
                11, Ui.FAINT);
        LinearLayout.LayoutParams offlineNoteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        offlineNoteParams.topMargin = Ui.dp(this, 6);
        content.addView(offlineNote, offlineNoteParams);

        TextView clearOffline = actionButton("清空本地下载", false);
        clearOffline.setOnClickListener(view -> new android.app.AlertDialog.Builder(this)
                .setTitle("清空本地下载")
                .setMessage("删除所有已下载到本地的作品？WebDAV 上的文件不受影响。")
                .setPositiveButton("清空", (dialog, which) -> {
                    app.offline.clearAll();
                    loadOfflineInfo();
                    Ui.toast(this, "本地下载已清空");
                })
                .setNegativeButton("取消", null)
                .show());
        LinearLayout.LayoutParams clearOfflineParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 42));
        clearOfflineParams.topMargin = Ui.dp(this, 12);
        content.addView(clearOffline, clearOfflineParams);

        TextView footer = Ui.label(this,
                "账号只保存在本机 SharedPreferences。观看时视频边拉边播并缓存在手机里，"
                        + "上面可以调整缓存上限；清空缓存不会影响 WebDAV 上的作品。", 11, Ui.FAINT);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = Ui.dp(this, 26);
        content.addView(footer, footerParams);

        TextView version = Ui.label(this, versionText(), 11, Ui.FAINT);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionParams.topMargin = Ui.dp(this, 10);
        content.addView(version, versionParams);

        setContentView(root);
    }

    /** 显示版本号和构建号，方便确认手机装的是哪一次构建。 */
    private String versionText() {
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            return "净影 · 观看 v" + info.versionName + "（build " + code + "）";
        } catch (Exception exc) {
            return "净影 · 观看";
        }
    }

    private EditText addField(LinearLayout parent, String label, String hint,
                              int inputType, String value) {
        TextView labelView = Ui.text(this, label, 12, Ui.DIM, true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = Ui.dp(this, 18);
        parent.addView(labelView, labelParams);

        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setHintTextColor(Ui.FAINT);
        field.setTextColor(Ui.TEXT);
        field.setTextSize(14);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setBackground(Ui.round(this, 0xFF1A1A20, 12));
        field.setPadding(Ui.dp(this, 14), Ui.dp(this, 11),
                Ui.dp(this, 14), Ui.dp(this, 11));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, 7);
        parent.addView(field, params);
        return field;
    }

    private TextView actionButton(String label, boolean primary) {
        TextView view = Ui.text(this, label, 14, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(Ui.ripple(this, Ui.round(this,
                primary ? Ui.ACCENT : 0xFF25252C, 23), 23));
        return view;
    }

    private void buildLimitChips() {
        if (limitRow == null) {
            return;
        }
        limitRow.removeAllViews();
        final int[] values = {512, 1024, 2048, 4096, 8192};
        final String[] labels = {"512M", "1G", "2G", "4G", "8G"};
        int current = prefs.cacheMb();
        for (int index = 0; index < values.length; index++) {
            boolean active = current == values[index];
            TextView chip = Ui.text(this, labels[index], 12,
                    active ? Color.WHITE : Ui.DIM, active);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 7),
                    Ui.dp(this, 12), Ui.dp(this, 7));
            chip.setBackground(Ui.round(this, active ? 0xFF2C2C34 : 0xFF17171C, 14));
            final int value = values[index];
            final String label = labels[index];
            chip.setOnClickListener(view -> {
                prefs.setCacheMb(value);
                app.proxy.setCacheLimit(value * 1024L * 1024L);
                buildLimitChips();
                loadCacheInfo();
                Ui.toast(this, "缓存上限已设为 " + label);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (index > 0) {
                params.leftMargin = Ui.dp(this, 8);
            }
            limitRow.addView(chip, params);
        }
    }

    private void loadCacheInfo() {
        app.io.execute(() -> {
            long media = app.proxy.cacheBytes();
            long archive = app.archives == null ? 0L : app.archives.cacheBytes();
            long covers = 0;
            java.io.File dir = new java.io.File(getFilesDir(), "covers");
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    covers += file.length();
                }
            }
            final long mediaBytes = media;
            final long archiveBytes = archive;
            final long coverBytes = covers;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                cacheText.setText("视频缓存 " + Util.humanSize(mediaBytes)
                    + "   ·   封面 " + Util.humanSize(coverBytes)
                    + "   ·   解包 " + Util.humanSize(archiveBytes)
                    + "   ·   上限 " + prefs.cacheMb() + " MB");
            });
        });
    }

    private String urlValue() {
        return urlField.getText().toString().trim();
    }

    private String rootValue() {
        String value = rootField.getText().toString().trim();
        return value.isEmpty() ? "净影" : value;
    }

    private void testConnection() {
        String url = urlValue();
        if (url.isEmpty()) {
            statusText.setText("先填 WebDAV 地址");
            statusText.setTextColor(Ui.ACCENT);
            return;
        }
        statusText.setTextColor(Ui.DIM);
        statusText.setText("正在连接…");
        testButton.setEnabled(false);
        final String user = userField.getText().toString();
        final String pass = passField.getText().toString();
        final String root = rootValue();
        final boolean insecure = insecureBox.isChecked();
        app.io.execute(() -> {
            String message;
            boolean ok = false;
            try {
                DavClient client = new DavClient(url, user, pass, root, insecure);
                List<DavEntry> entries = client.propfind(client.seed(), 1);
                int dirs = 0;
                for (DavEntry entry : entries) {
                    if (entry.dir) {
                        dirs++;
                    }
                }
                StringBuilder names = new StringBuilder();
                for (int index = 0; index < Math.min(6, entries.size()); index++) {
                    if (index > 0) {
                        names.append("、");
                    }
                    names.append(entries.get(index).name);
                }
                message = "连接成功：读到 " + entries.size() + " 项（" + dirs + " 个目录）\n"
                        + names;
                ok = true;
            } catch (Exception exc) {
                message = "连接失败：" + (exc.getMessage() == null ? "未知错误" : exc.getMessage());
            }
            final boolean success = ok;
            final String result = message;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                statusText.setText(result);
                statusText.setTextColor(success ? 0xFF7FD8FF : Ui.ACCENT);
                testButton.setEnabled(true);
            });
        });
    }

    private void save() {
        String url = urlValue();
        if (url.isEmpty()) {
            statusText.setText("WebDAV 地址不能为空");
            statusText.setTextColor(Ui.ACCENT);
            return;
        }
        String oldUrl = prefs.davUrl();
        String oldRoot = prefs.remoteRoot();
        String user = userField.getText().toString().trim();
        String pass = passField.getText().toString();
        String root = rootValue();
        prefs.saveDav(url, user, pass, root, insecureBox.isChecked());
        app.reloadDav();
        if (!url.equals(oldUrl) || !root.equals(oldRoot)) {
            // 换了片库：清掉旧索引，避免显示上个网盘的作品
            app.store.clear();
        } else {
            app.store.setRoots("", "");
        }
        Ui.toast(this, "已保存，开始扫描");
        setResult(RESULT_OK);
        finish();
    }
}

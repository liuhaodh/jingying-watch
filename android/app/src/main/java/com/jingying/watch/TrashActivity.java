package com.jingying.watch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 回收站：列出云端 `回收站/` 里的条目，可以单条恢复、单条彻底删除，或者一键清空。
 */
public final class TrashActivity extends Activity {

    private App app;
    private final List<TrashStore.Item> items = new ArrayList<>();
    private ListView list;
    private Adapter adapter;
    private TextView countText;
    private TextView emptyText;
    private TextView clearButton;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = App.get();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 12), Ui.statusBarHeight(this),
                Ui.dp(this, 16), Ui.dp(this, 4));
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView back = Ui.text(this, "‹", 30, Ui.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, Ui.dp(this, 10), Ui.dp(this, 4));
        back.setOnClickListener(view -> finish());
        topBar.addView(back);

        TextView title = Ui.text(this, "回收站", 18, Ui.TEXT, true);
        topBar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        clearButton = Ui.text(this, "全部删除", 13, 0xFFFF6B85, true);
        clearButton.setPadding(Ui.dp(this, 10), Ui.dp(this, 8),
                Ui.dp(this, 10), Ui.dp(this, 8));
        clearButton.setBackground(Ui.ripple(this, Ui.round(this, 0x00000000, 16), 16));
        clearButton.setOnClickListener(view -> confirmClearAll());
        clearButton.setVisibility(View.GONE);
        topBar.addView(clearButton);

        countText = Ui.label(this, "", 11, Ui.FAINT);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countParams.setMargins(Ui.dp(this, 18), Ui.dp(this, 2), Ui.dp(this, 18), Ui.dp(this, 8));
        root.addView(countText, countParams);

        emptyText = Ui.label(this, "", 13, Ui.DIM);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(Ui.dp(this, 24), Ui.dp(this, 60), Ui.dp(this, 24), 0);
        emptyText.setVisibility(View.GONE);
        root.addView(emptyText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(this);
        // ListView 的子项不能用 LinearLayout.LayoutParams，行间距靠透明分隔线撑
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(Ui.dp(this, 8));
        list.setClipToPadding(false);
        list.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 24));
        adapter = new Adapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    // ---------- 数据 ----------
    private void reload() {
        if (loading) {
            return;
        }
        loading = true;
        if (items.isEmpty()) {
            countText.setText("正在读取回收站…");
        }
        app.trash.listAsync((loaded, result) -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            loading = false;
            items.clear();
            items.addAll(loaded);
            adapter.notifyDataSetChanged();
            if (!result.ok) {
                countText.setText(result.error == null ? "读取失败" : result.error);
                renderEmpty("回收站读取失败\n" + (result.error == null ? "" : result.error));
                return;
            }
            countText.setText(items.isEmpty() ? "回收站是空的"
                    : "共 " + items.size() + " 项 · 点条目预览，长按彻底删除");
            if (items.isEmpty()) {
                renderEmpty("回收站是空的\n删除的作品和作者会先放到这里，可以随时恢复");
            } else {
                renderEmpty(null);
            }
            clearButton.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void renderEmpty(String message) {
        if (message == null) {
            emptyText.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        } else {
            emptyText.setText(message);
            emptyText.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
        }
    }

    // ---------- 操作 ----------
    /** 点一下直接预览：把回收站目录拼成临时作品，用播放页打开。 */
    private void previewItem(TrashStore.Item item) {
        if (item == null) {
            return;
        }
        if (item.rels.isEmpty()) {
            Ui.toast(this, "这条记录缺少原始路径，无法预览");
            return;
        }
        final android.app.AlertDialog busy = Ui.busy(this, "正在打开…");
        app.trash.loadWorkAsync(item, (work, result) -> {
            Ui.dismiss(busy);
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (!result.ok || work == null) {
                android.util.Log.w("JYWatch", "trash preview failed: " + result.error);
                Ui.toast(this, result.error == null ? "打不开这个作品" : result.error);
                return;
            }
            FeedSession.openPreview(work);
            startActivity(new Intent(this, FeedActivity.class));
        });
    }

    private void confirmRestore(TrashStore.Item item) {
        if (item == null) {
            return;
        }
        if (item.rels.isEmpty()) {
            Ui.toast(this, "这条记录缺少原始路径，只能彻底删除");
            return;
        }
        Ui.confirm(this, "恢复",
                "把「" + item.displayName() + "」恢复到原位置？\n" + item.originalRel(),
                "恢复", Ui.TEXT, () -> {
                    final android.app.AlertDialog busy = Ui.busy(this, "正在恢复…");
                    app.trash.restoreAsync(item, result -> {
                        Ui.dismiss(busy);
                        if (!result.ok) {
                            Ui.toast(this, result.error == null ? "恢复失败" : result.error);
                            return;
                        }
                        Ui.toast(this, "已恢复");
                        reload();
                        afterRestore(item);
                    });
                });
    }

    /**
     * 恢复后让片库立刻能看到：作品直接按 rel 补一条骨架（不用联网），
     * 作者则只重扫这一位作者。
     */
    private void afterRestore(TrashStore.Item item) {
        if (item == null) {
            return;
        }
        if (item.isAuthor()) {
            for (String rel : item.rels) {
                app.scanner.refreshAuthor(rel);
            }
            return;
        }
        // 一次批量合并：以前每条都 store.update()，恢复一个 500 条作品的作者
        // 就是 500 次线性查找 + 500 次全量排序 + 反复触发整库 JSON 落盘
        java.util.List<Work> restored = new ArrayList<>();
        for (String rel : item.rels) {
            String authorRel = parentOf(rel);
            String[] author = Work.splitAuthor(RemoteFile.baseName(authorRel));
            String name = RemoteFile.baseName(rel);
            if (TarReader.looksLikeTar(name)) {
                // 恢复的是作品包：必须按 archive 骨架加回去，否则会被当成散目录，
                // 打开时 PROPFIND 一个文件、什么也读不出来
                restored.add(Work.archiveSkeleton(rel, authorRel, author[0], author[1],
                        name, 0L, 0L));
            } else {
                restored.add(Work.skeleton(rel, authorRel, author[0], author[1],
                        name, 0L));
            }
        }
        if (!restored.isEmpty()) {
            app.store.addBatch(restored);
        }
    }

    private static String parentOf(String rel) {
        int index = rel == null ? -1 : rel.lastIndexOf('/');
        return index <= 0 ? "" : rel.substring(0, index);
    }

    private void confirmDelete(TrashStore.Item item) {
        if (item == null) {
            return;
        }
        Ui.confirm(this, "彻底删除",
                "「" + item.displayName() + "」会被永久删除，无法恢复。",
                "彻底删除", () -> {
                    final android.app.AlertDialog busy = Ui.busy(this, "正在删除…");
                    app.trash.deleteItemAsync(item, result -> {
                        Ui.dismiss(busy);
                        if (!result.ok) {
                            Ui.toast(this, result.error == null ? "删除失败" : result.error);
                            return;
                        }
                        Ui.toast(this, "已彻底删除");
                        reload();
                    });
                });
    }

    private void confirmClearAll() {
        if (items.isEmpty()) {
            return;
        }
        Ui.confirm(this, "清空回收站",
                "回收站里的 " + items.size() + " 项会被永久删除，无法恢复。",
                "全部删除", () -> {
                    final android.app.AlertDialog busy = Ui.busy(this, "正在清空回收站…");
                    app.trash.clearAllAsync(result -> {
                        Ui.dismiss(busy);
                        if (!result.ok) {
                            Ui.toast(this, result.error == null ? "清空失败" : result.error);
                            return;
                        }
                        Ui.toast(this, "回收站已清空");
                        reload();
                    });
                });
    }

    // ---------- 列表 ----------
    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public TrashStore.Item getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                holder = new Holder();
                convertView = holder.build();
                convertView.setTag(holder);
            } else {
                holder = (Holder) convertView.getTag();
            }
            holder.bind(getItem(position));
            return convertView;
        }
    }

    private final class Holder {
        LinearLayout root;
        TextView title;
        TextView subtitle;
        TextView badge;
        TextView restore;
        TrashStore.Item item;

        View build() {
            root = new LinearLayout(TrashActivity.this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(Ui.dp(TrashActivity.this, 12), Ui.dp(TrashActivity.this, 11),
                    Ui.dp(TrashActivity.this, 12), Ui.dp(TrashActivity.this, 11));
            root.setBackground(Ui.ripple(TrashActivity.this,
                    Ui.round(TrashActivity.this, 0xFF121216, 12), 12));

            LinearLayout column = new LinearLayout(TrashActivity.this);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            root.addView(column, columnParams);

            LinearLayout titleRow = new LinearLayout(TrashActivity.this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            column.addView(titleRow);

            badge = Ui.text(TrashActivity.this, "", 9, 0xFFB6F5F3, true);
            badge.setBackground(Ui.round(TrashActivity.this, 0x3325F4EE, 6));
            badge.setPadding(Ui.dp(TrashActivity.this, 6), Ui.dp(TrashActivity.this, 2),
                    Ui.dp(TrashActivity.this, 6), Ui.dp(TrashActivity.this, 2));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeParams.rightMargin = Ui.dp(TrashActivity.this, 8);
            titleRow.addView(badge, badgeParams);

            title = Ui.text(TrashActivity.this, "", 14, Ui.TEXT, true);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            titleRow.addView(title, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            subtitle = Ui.label(TrashActivity.this, "", 11, Ui.FAINT);
            subtitle.setMaxLines(2);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subtitleParams.topMargin = Ui.dp(TrashActivity.this, 5);
            column.addView(subtitle, subtitleParams);

            restore = Ui.text(TrashActivity.this, "恢复", 12, Ui.TEXT, true);
            restore.setGravity(Gravity.CENTER);
            restore.setBackground(Ui.ripple(TrashActivity.this,
                    Ui.roundStroke(TrashActivity.this, 0x00000000, Ui.LINE, 16, 1), 16));
            restore.setPadding(Ui.dp(TrashActivity.this, 14), Ui.dp(TrashActivity.this, 7),
                    Ui.dp(TrashActivity.this, 14), Ui.dp(TrashActivity.this, 7));
            LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            restoreParams.leftMargin = Ui.dp(TrashActivity.this, 10);
            root.addView(restore, restoreParams);

            return root;
        }

        void bind(TrashStore.Item value) {
            item = value;
            badge.setText(value.typeLabel());
            badge.setBackground(Ui.round(TrashActivity.this,
                    value.isAuthor() ? 0x33FF9F43 : 0x3325F4EE, 6));
            badge.setTextColor(value.isAuthor() ? 0xFFFFC078 : 0xFFB6F5F3);
            title.setText(value.displayName());
            String when = value.deletedAt > 0
                    ? Util.relativeTime(value.deletedAt) + "删除" : "已删除";
            String path = value.originalRel();
            subtitle.setText(when + (path.isEmpty() ? "" : "\n" + path));

            boolean canRestore = !value.rels.isEmpty();
            restore.setVisibility(canRestore ? View.VISIBLE : View.GONE);
            restore.setOnClickListener(view -> confirmRestore(item));
            // 点整行 = 直接预览，确认是不是要恢复的那条
            root.setOnClickListener(view -> previewItem(item));
            root.setOnLongClickListener(view -> {
                confirmDelete(item);
                return true;
            });
            root.setLongClickable(true);
        }
    }
}

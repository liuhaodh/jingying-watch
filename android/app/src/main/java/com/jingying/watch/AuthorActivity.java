package com.jingying.watch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 作者主页：头像、资料和这个作者的全部作品。 */
public final class AuthorActivity extends Activity {

    public static final String EXTRA_AUTHOR = "author";

    private App app;
    private String author;
    private String authorUid = "";
    private final List<Work> works = new ArrayList<>();
    private int cellWidth;
    private int cellHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = App.get();
        author = getIntent().getStringExtra(EXTRA_AUTHOR);
        if (author == null) {
            author = "";
        }
        works.addAll(Library.filter(app.store.works(), author, "", ""));
        if (works.isEmpty()) {
            Ui.toast(this, "这个作者还没有作品");
            finish();
            return;
        }
        authorUid = uidForAuthor();
        // 提前把 sec_uid 读出来，用户点「打开抖音主页」时就能直接进主页
        app.avatars.loadSecUid(author, authorUid, null);
        int gap = Ui.dp(this, 6);
        int padding = Ui.dp(this, 6);
        cellWidth = (Ui.screenWidth(this) - padding * 2 - gap * 2) / 3;
        cellHeight = Math.round(cellWidth * 1.34f);

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

        TextView title = Ui.text(this, "作者主页", 18, Ui.TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        topBar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView delete = Ui.text(this, "删除作者", 13, 0xFFFF6B85, true);
        delete.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        delete.setBackground(Ui.ripple(this, Ui.round(this, 0x00000000, 16), 16));
        delete.setOnClickListener(view -> confirmDeleteAuthor());
        topBar.addView(delete);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        list.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), Ui.dp(this, 20));
        list.addHeaderView(buildHeader(), null, false);
        GridAdapter adapter = new GridAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - 1;
            if (index >= 0 && index < works.size()) {
                openWork(index);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(Ui.dp(this, 10), Ui.dp(this, 8),
                Ui.dp(this, 10), Ui.dp(this, 14));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(row);

        FrameLayout avatarBox = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF3A3A44, 0xFF202028});
        background.setShape(GradientDrawable.OVAL);
        avatarBox.setBackground(background);
        avatarBox.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        avatarBox.setClipToOutline(true);
        TextView letter = Ui.text(this, AvatarStore.initialOf(author), 26, Color.WHITE, true);
        letter.setGravity(Gravity.CENTER);
        avatarBox.addView(letter, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView avatarImage = new ImageView(this);
        avatarImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarBox.addView(avatarImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(avatarBox, new LinearLayout.LayoutParams(
                Ui.dp(this, 74), Ui.dp(this, 74)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoParams.leftMargin = Ui.dp(this, 14);
        row.addView(info, infoParams);

        TextView name = Ui.text(this, author, 20, Ui.TEXT, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);

        String uid = authorUid;
        long latest = 0L;
        for (Work work : works) {
            latest = Math.max(latest, work.time);
        }
        if (!uid.isEmpty()) {
            TextView uidView = Ui.label(this, "抖音号 " + uid, 12, Ui.DIM);
            LinearLayout.LayoutParams uidParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            uidParams.topMargin = Ui.dp(this, 5);
            info.addView(uidView, uidParams);
        }
        String meta = works.size() + " 个作品"
                + (latest > 0 ? " · 最近 " + Util.relativeTime(latest) : "");
        TextView metaView = Ui.label(this, meta, 12, Ui.DIM);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = Ui.dp(this, 5);
        info.addView(metaView, metaParams);

        Work sample = works.get(0);
        app.avatars.load(sample, (nameValue, uidValue, bitmap) -> {
            if (bitmap != null && author.equals(nameValue)) {
                avatarImage.setImageBitmap(bitmap);
            }
        });

        // 拉起官方抖音打开这位作者的主页
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = Ui.dp(this, 12);
        header.addView(actionRow, actionParams);

        TextView openDouyin = Ui.text(this, "打开抖音主页", 12, 0xFFB6F5F3, true);
        openDouyin.setGravity(Gravity.CENTER);
        openDouyin.setBackground(Ui.ripple(this,
                Ui.roundStroke(this, 0x00000000, 0x5525F4EE, 15, 1), 15));
        openDouyin.setPadding(Ui.dp(this, 14), Ui.dp(this, 7),
                Ui.dp(this, 14), Ui.dp(this, 7));
        openDouyin.setOnClickListener(view -> openInDouyin(authorUid));
        openDouyin.setLongClickable(true);
        openDouyin.setOnLongClickListener(view -> {
            showDouyinOptions();
            return true;
        });
        actionRow.addView(openDouyin);

        TextView hint = Ui.label(this, "主页空白？长按换方式", 11, Ui.FAINT);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.leftMargin = Ui.dp(this, 10);
        actionRow.addView(hint, hintParams);

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lineParams.topMargin = Ui.dp(this, 16);
        header.addView(line, lineParams);
        return header;
    }

    private void openWork(int index) {
        FeedSession.open(works, index, "@" + author);
        startActivity(new Intent(this, FeedActivity.class));
    }

    /** 拉起抖音打开作者主页：有 sec_uid 直接进主页，没有就按抖音号搜。 */
    private void openInDouyin(String uid) {
        String sec = app.avatars.cachedSecUid(author, uid);
        DouyinLauncher.openAuthor(this, author, uid, sec);
        if (sec.isEmpty()) {
            // 还没读到 authors.json：后台补一次，下次点就能直接进主页
            app.avatars.loadSecUid(author, uid, null);
        }
    }

    /** 长按「打开抖音主页」：主页空白 / sec_uid 无效时给用户可控的兜底。 */
    private void showDouyinOptions() {
        final String sec = app.avatars.cachedSecUid(author, authorUid);
        new android.app.AlertDialog.Builder(this)
                .setTitle("打开抖音 · " + author)
                .setMessage(sec.isEmpty()
                        ? "没读到这位作者的 sec_uid，建议按抖音号搜索"
                        : "如果主页打开是空白，多半是 sec_uid 无效；可以改用抖音号搜索，或复制链接自己打开")
                .setItems(new String[]{"打开作者主页", "按抖音号搜索", "复制主页链接"},
                        (dialog, which) -> {
                            if (which == 0) {
                                DouyinLauncher.openAuthor(this, author, authorUid, sec);
                            } else if (which == 1) {
                                DouyinLauncher.openSearch(this, author, authorUid);
                            } else if (which == 2) {
                                DouyinLauncher.copyLink(this, author, authorUid, sec);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private String uidForAuthor() {
        for (Work work : works) {
            if (work.uniqueId != null && !work.uniqueId.isEmpty()) {
                return work.uniqueId;
            }
        }
        return "";
    }

    /** 删除整个作者：云端作者目录 + Music 下同名目录 + 本地索引/下载。 */
    private void confirmDeleteAuthor() {
        final List<String> rels = new ArrayList<>();
        for (Work work : works) {
            if (work.authorRel != null && !work.authorRel.isEmpty()
                    && !rels.contains(work.authorRel)) {
                rels.add(work.authorRel);
            }
        }
        if (rels.isEmpty()) {
            Ui.toast(this, "找不到作者目录，无法删除");
            return;
        }
        final int count = works.size();
        String pathText = rels.size() == 1
                ? rels.get(0) : rels.get(0) + " 等 " + rels.size() + " 个目录";
        Ui.confirm(this, "移入回收站",
                "将从片库移除 @" + author + " 的整个目录（" + count + " 个作品）：\n"
                        + pathText + "\n\n"
                        + "• 云端文件会移到「回收站」，可以随时恢复\n"
                        + "• 本地下载和播放缓存会清除\n"
                        + "• 回收站里可以彻底删除",
                "移入回收站", () -> {
                    final android.app.AlertDialog busy =
                            Ui.busy(this, "正在移入回收站…");
                    app.trash.moveAuthorAsync(rels, result -> {
                        Ui.dismiss(busy);
                        if (result.ok) {
                            Ui.toast(this, "已移入回收站");
                            finish();
                            return;
                        }
                        if (result.moveUnsupported) {
                            confirmDeleteForever(rels, count);
                            return;
                        }
                        Ui.toast(this, result.error == null ? "操作失败" : result.error);
                    });
                });
    }

    /** 服务器不支持 MOVE 时的兜底：明确告知后彻底删除。 */
    private void confirmDeleteForever(List<String> rels, int count) {
        Ui.confirm(this, "网盘不支持回收站",
                "这个 WebDAV 服务器不支持移动文件（MOVE），没法把作者放进回收站。\n\n"
                        + "要直接彻底删除这 " + count + " 个作品吗？删除后无法恢复。",
                "彻底删除", () -> {
                    final android.app.AlertDialog busy =
                            Ui.busy(this, "正在删除 " + count + " 个作品…");
                    app.trash.deleteForeverAuthorAsync(rels, result -> {
                        Ui.dismiss(busy);
                        if (!result.ok) {
                            Ui.toast(this, result.error == null ? "删除失败" : result.error);
                            return;
                        }
                        Ui.toast(this, "已删除 @" + author);
                        finish();
                    });
                });
    }

    private final class GridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return (works.size() + 2) / 3;
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row;
            if (convertView == null) {
                row = new Row();
                convertView = row.build();
                convertView.setTag(row);
            } else {
                row = (Row) convertView.getTag();
            }
            for (int column = 0; column < 3; column++) {
                int index = position * 3 + column;
                if (index < works.size()) {
                    row.cells[column].bind(works.get(index), index);
                } else {
                    row.cells[column].root.setVisibility(View.INVISIBLE);
                }
            }
            return convertView;
        }
    }

    private final class Row {
        final LinearLayout root;
        final Cell[] cells = new Cell[3];

        Row() {
            root = new LinearLayout(AuthorActivity.this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setPadding(0, Ui.dp(AuthorActivity.this, 3), 0, Ui.dp(AuthorActivity.this, 3));
            for (int index = 0; index < 3; index++) {
                cells[index] = new Cell();
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        cellWidth, cellHeight);
                if (index > 0) {
                    params.leftMargin = Ui.dp(AuthorActivity.this, 6);
                }
                root.addView(cells[index].build(), params);
            }
        }

        View build() {
            return root;
        }
    }

    private final class Cell {
        FrameLayout root;
        ImageView cover;
        TextView badge;
        TextView title;
        String rel = "";

        View build() {
            root = new FrameLayout(AuthorActivity.this);
            root.setBackground(Ui.round(AuthorActivity.this, 0xFF16161B, 8));

            cover = new ImageView(AuthorActivity.this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            Ui.dpf(AuthorActivity.this, 8));
                }
            });
            cover.setClipToOutline(true);
            root.addView(cover, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            View scrim = new View(AuthorActivity.this);
            scrim.setBackground(Ui.verticalGradient(AuthorActivity.this,
                    0x00000000, 0xCC000000));
            FrameLayout.LayoutParams scrimParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(AuthorActivity.this, 64));
            scrimParams.gravity = Gravity.BOTTOM;
            root.addView(scrim, scrimParams);

            title = Ui.text(AuthorActivity.this, "", 11, 0xEEFFFFFF, false);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleParams.gravity = Gravity.BOTTOM;
            titleParams.setMargins(Ui.dp(AuthorActivity.this, 6), 0,
                    Ui.dp(AuthorActivity.this, 6), Ui.dp(AuthorActivity.this, 6));
            root.addView(title, titleParams);

            badge = Ui.text(AuthorActivity.this, "", 9, Color.WHITE, true);
            badge.setBackground(Ui.round(AuthorActivity.this, 0x66000000, 7));
            badge.setPadding(Ui.dp(AuthorActivity.this, 5), Ui.dp(AuthorActivity.this, 2),
                    Ui.dp(AuthorActivity.this, 5), Ui.dp(AuthorActivity.this, 2));
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeParams.gravity = Gravity.TOP | Gravity.END;
            badgeParams.setMargins(0, Ui.dp(AuthorActivity.this, 5),
                    Ui.dp(AuthorActivity.this, 5), 0);
            root.addView(badge, badgeParams);
            return root;
        }

        void bind(Work work, int index) {
            root.setVisibility(View.VISIBLE);
            rel = work.rel;
            title.setText(work.displayTitle());
            if (work.isAlbum()) {
                badge.setText("图集 " + work.images.size());
                badge.setVisibility(View.VISIBLE);
            } else if (work.isVideo()) {
                badge.setText("视频");
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }
            android.graphics.Bitmap peek = app.images.peek(work.key());
            if (peek != null) {
                cover.setImageBitmap(peek);
            } else {
                cover.setImageBitmap(null);
                cover.setBackgroundColor(0xFF16161B);
                final String key = work.rel;
                app.images.loadCover(work, (loadedKey, bitmap) -> {
                    if (bitmap != null && key.equals(rel)) {
                        cover.setImageBitmap(bitmap);
                    }
                });
            }
            root.setOnClickListener(view -> {
                int target = works.indexOf(work);
                if (target >= 0) {
                    openWork(target);
                }
            });
        }
    }
}

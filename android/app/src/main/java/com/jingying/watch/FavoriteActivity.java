package com.jingying.watch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

/**
 * 收藏夹：播放页点右侧「收藏」的作品都会出现在这里，
 * 点任意一个就从这份收藏列表开始连着看。
 */
public final class FavoriteActivity extends Activity {

    private App app;
    private final List<Work> works = new ArrayList<>();
    private ListView list;
    private GridAdapter adapter;
    private TextView countText;
    private TextView emptyText;
    private int cellWidth;
    private int cellHeight;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 冷启动时片库索引还在后台读，等它读完再对收藏，别先报「还没有收藏」。 */
    private final Runnable loadWatcher = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (app.store.isLoaded()) {
                reload();
            } else {
                handler.postDelayed(this, 150L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = App.get();
        int gap = Ui.dp(this, 6);
        int padding = Ui.dp(this, 6);
        cellWidth = (Ui.screenWidth(this) - padding * 2 - gap * 2) / 3;
        cellHeight = Math.round(cellWidth * 1.34f);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 播放页里可能刚收藏/取消收藏，每次回来都重新对一遍
        reload();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(loadWatcher);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
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

        TextView title = Ui.text(this, "收藏", 18, Ui.TEXT, true);
        topBar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        countText = Ui.label(this, "", 12, Ui.DIM);
        topBar.addView(countText);

        emptyText = Ui.label(this, "", 13, Ui.DIM);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(Ui.dp(this, 24), Ui.dp(this, 80), Ui.dp(this, 24), 0);
        emptyText.setVisibility(View.GONE);
        root.addView(emptyText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        list.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 20));
        adapter = new GridAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void reload() {
        if (!app.store.isLoaded()) {
            // 索引没读完时 find() 全是 null，直接显示「还没有收藏」会误导人
            works.clear();
            countText.setText("");
            emptyText.setText("正在读取本地片库…");
            emptyText.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
            handler.removeCallbacks(loadWatcher);
            handler.postDelayed(loadWatcher, 150L);
            return;
        }
        handler.removeCallbacks(loadWatcher);
        works.clear();
        for (String rel : app.favorites.rels()) {
            Work work = app.store.find(rel);
            if (work != null) {
                works.add(work);
            }
        }
        countText.setText(works.isEmpty() ? "" : works.size() + " 个");
        if (works.isEmpty()) {
            emptyText.setText("还没有收藏\n播放作品时点右侧 ☆ 收藏，就会出现在这里");
            emptyText.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
        } else {
            emptyText.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        }
        adapter.notifyDataSetChanged();
    }

    private void openWork(int index) {
        if (index < 0 || index >= works.size()) {
            return;
        }
        FeedSession.open(works, index, "收藏");
        startActivity(new Intent(this, FeedActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void confirmUnfavorite(Work work) {
        if (work == null) {
            return;
        }
        Ui.confirm(this, "取消收藏",
                "把「" + work.displayTitle() + "」从收藏里去掉？",
                "取消收藏", () -> {
                    app.favorites.remove(work.rel);
                    Ui.toast(this, "已取消收藏");
                    reload();
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
            root = new LinearLayout(FavoriteActivity.this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setPadding(0, Ui.dp(FavoriteActivity.this, 3), 0, Ui.dp(FavoriteActivity.this, 3));
            for (int index = 0; index < 3; index++) {
                cells[index] = new Cell();
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        cellWidth, cellHeight);
                if (index > 0) {
                    params.leftMargin = Ui.dp(FavoriteActivity.this, 6);
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
            root = new FrameLayout(FavoriteActivity.this);
            root.setBackground(Ui.round(FavoriteActivity.this, 0xFF16161B, 8));

            cover = new ImageView(FavoriteActivity.this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            Ui.dpf(FavoriteActivity.this, 8));
                }
            });
            cover.setClipToOutline(true);
            root.addView(cover, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            View scrim = new View(FavoriteActivity.this);
            scrim.setBackground(Ui.verticalGradient(FavoriteActivity.this,
                    0x00000000, 0xCC000000));
            FrameLayout.LayoutParams scrimParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(FavoriteActivity.this, 64));
            scrimParams.gravity = Gravity.BOTTOM;
            root.addView(scrim, scrimParams);

            title = Ui.text(FavoriteActivity.this, "", 11, 0xEEFFFFFF, false);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleParams.gravity = Gravity.BOTTOM;
            titleParams.setMargins(Ui.dp(FavoriteActivity.this, 6), 0,
                    Ui.dp(FavoriteActivity.this, 6), Ui.dp(FavoriteActivity.this, 6));
            root.addView(title, titleParams);

            badge = Ui.text(FavoriteActivity.this, "", 9, Color.WHITE, true);
            badge.setBackground(Ui.round(FavoriteActivity.this, 0x66000000, 7));
            badge.setPadding(Ui.dp(FavoriteActivity.this, 5), Ui.dp(FavoriteActivity.this, 2),
                    Ui.dp(FavoriteActivity.this, 5), Ui.dp(FavoriteActivity.this, 2));
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeParams.gravity = Gravity.TOP | Gravity.END;
            badgeParams.setMargins(0, Ui.dp(FavoriteActivity.this, 5),
                    Ui.dp(FavoriteActivity.this, 5), 0);
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
            root.setOnClickListener(view -> openWork(index));
            root.setLongClickable(true);
            root.setOnLongClickListener(view -> {
                confirmUnfavorite(work);
                return true;
            });
        }
    }
}

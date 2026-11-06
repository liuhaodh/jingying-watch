package com.jingying.watch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 作者列表：列出片库里的所有作者，点进去是作者主页。 */
public final class AuthorListActivity extends Activity {

    private App app;
    private final List<Library.AuthorInfo> all = new ArrayList<>();
    private final List<Library.AuthorInfo> shown = new ArrayList<>();
    private final Map<String, Work> samples = new HashMap<>();
    private ListView list;
    private Adapter adapter;
    private EditText search;
    private TextView countText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = App.get();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到列表都重新读一遍：作者主页里删掉作者后要能立刻消失
        reload();
    }

    private void reload() {
        all.clear();
        samples.clear();
        List<Work> works = app.store.works();
        for (Work work : works) {
            String author = work.displayAuthor();
            Work old = samples.get(author);
            if (old == null || (old.uniqueId.isEmpty() && !work.uniqueId.isEmpty())) {
                samples.put(author, work);
            }
        }
        all.addAll(Library.authors(works));
        applyFilter(search == null ? "" : search.getText().toString());
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
                Ui.dp(this, 16), Ui.dp(this, 6));
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView back = Ui.text(this, "‹", 30, Ui.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, Ui.dp(this, 10), Ui.dp(this, 4));
        back.setOnClickListener(view -> finish());
        topBar.addView(back);

        TextView title = Ui.text(this, "作者", 20, Ui.TEXT, true);
        topBar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        countText = Ui.label(this, all.size() + " 位", 12, Ui.DIM);
        topBar.addView(countText);

        search = new EditText(this);
        search.setHint("搜索作者名 / 抖音号");
        search.setHintTextColor(Ui.FAINT);
        search.setTextColor(Ui.TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setBackground(Ui.round(this, 0xFF17171C, 12));
        search.setPadding(Ui.dp(this, 14), Ui.dp(this, 9),
                Ui.dp(this, 14), Ui.dp(this, 9));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.setMargins(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        root.addView(search, searchParams);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 16));
        list.setClipToPadding(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < shown.size()) {
                openAuthor(shown.get(position));
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        setContentView(root);
    }

    private void applyFilter(String query) {
        String needle = query.trim().toLowerCase(Locale.CHINA);
        shown.clear();
        for (Library.AuthorInfo info : all) {
            if (needle.isEmpty()
                    || info.name.toLowerCase(Locale.CHINA).contains(needle)
                    || info.uid.toLowerCase(Locale.CHINA).contains(needle)) {
                shown.add(info);
            }
        }
        countText.setText(shown.size() + " 位");
        adapter.notifyDataSetChanged();
    }

    private void openAuthor(Library.AuthorInfo info) {
        Intent intent = new Intent(this, AuthorActivity.class);
        intent.putExtra(AuthorActivity.EXTRA_AUTHOR, info.name);
        startActivity(intent);
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Library.AuthorInfo getItem(int position) {
            return shown.get(position);
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
            Library.AuthorInfo info = getItem(position);
            holder.author = info.name;
            holder.name.setText(info.name);
            String sub = (info.uid.isEmpty() ? "" : info.uid + " · ")
                    + info.count + " 个作品";
            holder.sub.setText(sub);
            holder.letter.setText(AvatarStore.initialOf(info.name));
            holder.avatar.setImageDrawable(null);
            Work sample = samples.get(info.name);
            if (sample != null) {
                final String author = info.name;
                app.avatars.load(sample, (name, uid, bitmap) -> {
                    if (bitmap != null && author.equals(holder.author)) {
                        holder.avatar.setImageBitmap(bitmap);
                    }
                });
            }
            return convertView;
        }
    }

    private final class Holder {
        LinearLayout root;
        FrameLayout avatarBox;
        ImageView avatar;
        TextView letter;
        TextView name;
        TextView sub;
        String author = "";

        View build() {
            root = new LinearLayout(AuthorListActivity.this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(Ui.dp(AuthorListActivity.this, 8), Ui.dp(AuthorListActivity.this, 9),
                    Ui.dp(AuthorListActivity.this, 8), Ui.dp(AuthorListActivity.this, 9));
            root.setBackground(Ui.ripple(AuthorListActivity.this,
                    Ui.round(AuthorListActivity.this, 0xFF121216, 12), 12));

            avatarBox = new FrameLayout(AuthorListActivity.this);
            GradientDrawable avatarBackground = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF3A3A44, 0xFF202028});
            avatarBackground.setShape(GradientDrawable.OVAL);
            avatarBox.setBackground(avatarBackground);
            avatarBox.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            avatarBox.setClipToOutline(true);
            letter = Ui.text(AuthorListActivity.this, "", 17, Color.WHITE, true);
            letter.setGravity(Gravity.CENTER);
            avatarBox.addView(letter, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            avatar = new ImageView(AuthorListActivity.this);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatarBox.addView(avatar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(
                    Ui.dp(AuthorListActivity.this, 46), Ui.dp(AuthorListActivity.this, 46));
            root.addView(avatarBox, avatarParams);

            LinearLayout column = new LinearLayout(AuthorListActivity.this);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            columnParams.leftMargin = Ui.dp(AuthorListActivity.this, 12);
            root.addView(column, columnParams);

            name = Ui.text(AuthorListActivity.this, "", 15, Ui.TEXT, true);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            column.addView(name);

            sub = Ui.label(AuthorListActivity.this, "", 11, Ui.DIM);
            LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subParams.topMargin = Ui.dp(AuthorListActivity.this, 3);
            column.addView(sub, subParams);

            TextView arrow = Ui.text(AuthorListActivity.this, "›", 22, Ui.FAINT, false);
            arrow.setPadding(Ui.dp(AuthorListActivity.this, 6), 0, 0, 0);
            root.addView(arrow);

            return root;
        }
    }
}

package com.jingying.watch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 片库首页：抖音个人页的骨架 —— 头像/统计/搜索/作者筛选 + 三列封面墙。
 * 打开先显示上次缓存的片库，后台再扫描 WebDAV。
 */
public final class HomeActivity extends Activity implements LibraryScanner.Listener {

    private static final int REQ_SETUP = 101;

    private App app;
    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ListView list;
    private HomeAdapter adapter;
    private final List<Work> allWorks = new ArrayList<>();
    private final Set<String> allRels = new HashSet<>();
    private List<Work> displayed = new ArrayList<>();
    /** allWorks 是否已按时间排序：扫描 incremental 追加时会短暂变乱。 */
    private boolean worksSorted = true;
    private boolean offlineDirty = true;
    private String lastChipFilter = "";
    private boolean lastChipOffline;
    /** 作者列表按索引 revision 缓存，搜索时只改高亮，不重建整排 chip。 */
    private List<Library.AuthorInfo> cachedAuthors;
    private int cachedAuthorsRevision = -1;
    private int cachedAuthorsSize = -1;
    private long builtAuthorsHash;
    private int builtOfflineCount = -1;
    private int cachedOfflineCount = -1;
    private int cachedOfflineRevision = -1;
    private String lastRandomKey = "";
    private final Runnable rebuildRunnable = this::rebuildDisplayed;
    /** 索引每次变化 revision 都会 +1，比「数量变没变」可靠（删一条又补一条时数量是相等的）。 */
    private int lastRevision = -1;
    private String filterAuthor = "";
    private String query = "";
    private boolean offlineOnly;
    private boolean scanning;
    private String lastError;

    private TextView hostText;
    private TextView statsText;
    private TextView statusText;
    private TextView emptyText;
    private TextView emptyAction;
    private TextView continueText;
    private ProgressBar scanProgress;
    private EditText search;
    /** 搜索防抖：停手 220ms 再筛，别每敲一个字就过一遍全库。 */
    private final Runnable searchRunnable = () -> {
        query = search == null ? "" : search.getText().toString().trim();
        rebuildDisplayed();
    };
    private LinearLayout chipsBox;
    private HorizontalScrollView chipsScroll;
    private final List<TextView> chipViews = new ArrayList<>();
    /** 作者 chip 分批挂上去：ListView 的表头每次布局都会重新 measure 所有子 view。 */
    private List<Library.AuthorInfo> chipAuthors;
    private int chipBuilt;
    private TextView offlineChip;
    private static final int CHIP_PAGE = 40;

    private int cellWidth;
    private int cellHeight;
    private int gridPadding;
    private int gridGap;
    private volatile long cacheBytes;
    private Library.Stats cachedStats;
    private long cachedStatsAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = App.get();
        prefs = app.prefs;
        computeCells();
        buildUi();
        if (app.store.isLoaded()) {
            onLibraryReady();
        } else {
            statusText.setText("正在读取本地片库…");
            handler.postDelayed(loadWatcher, 150L);
        }
    }

    private final Runnable loadWatcher = new Runnable() {
        @Override
        public void run() {
            if (app.store.isLoaded()) {
                onLibraryReady();
            } else {
                handler.postDelayed(this, 150L);
            }
        }
    };

    private void onLibraryReady() {
        handler.removeCallbacks(loadWatcher);
        loadFromStore();
        if (!prefs.hasDav()) {
            emptyText.setText("还没有连接 WebDAV 片库");
            emptyText.setVisibility(View.VISIBLE);
            emptyAction.setText("去连接");
            emptyAction.setVisibility(View.VISIBLE);
        } else {
            startScan(true);
        }
    }

    private void computeCells() {
        gridPadding = Ui.dp(this, 12);
        gridGap = Ui.dp(this, 4);
        cellWidth = (Ui.screenWidth(this) - gridPadding * 2 - gridGap * 2) / 3;
        cellHeight = Math.round(cellWidth * 1.26f);
    }

    // ---------- 界面 ----------
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.statusBarHeight(this) + Ui.dp(this, 52), 0, Ui.dp(this, 30));
        list.setBackgroundColor(Ui.BG);
        root.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(gridPadding, Ui.dp(this, 8), gridPadding, Ui.dp(this, 6));
        list.addHeaderView(header, null, false);
        buildHeader(header);

        // 顶部固定栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 16), Ui.statusBarHeight(this), Ui.dp(this, 10), 0);
        topBar.setBackground(Ui.verticalGradient(this, Ui.BG, 0xE60A0A0C));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.statusBarHeight(this) + Ui.dp(this, 52));
        root.addView(topBar, topParams);

        TextView logo = Ui.text(this, "净影 · 观看", 18, Ui.TEXT, true);
        topBar.addView(logo, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView favoriteIcon = Ui.text(this, "收藏", 13, Ui.DIM, true);
        favoriteIcon.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        favoriteIcon.setBackground(Ui.ripple(this, Ui.round(this, 0x00000000, 16), 16));
        favoriteIcon.setOnClickListener(view -> startActivity(
                new android.content.Intent(this, FavoriteActivity.class)));
        topBar.addView(favoriteIcon);

        TextView trashIcon = Ui.text(this, "回收站", 13, Ui.DIM, true);
        trashIcon.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        trashIcon.setBackground(Ui.ripple(this, Ui.round(this, 0x00000000, 16), 16));
        trashIcon.setOnClickListener(view -> startActivity(
                new android.content.Intent(this, TrashActivity.class)));
        topBar.addView(trashIcon);

        TextView authorsIcon = Ui.text(this, "作者", 13, Ui.DIM, true);
        authorsIcon.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        authorsIcon.setBackground(Ui.ripple(this, Ui.round(this, 0x00000000, 16), 16));
        authorsIcon.setOnClickListener(view -> startActivity(
                new android.content.Intent(this, AuthorListActivity.class)));
        topBar.addView(authorsIcon);

        TextView settingsIcon = Ui.text(this, "设置", 13, Ui.DIM, true);
        settingsIcon.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        settingsIcon.setBackground(Ui.ripple(this, Ui.round(this, 0x00000000, 16), 16));
        settingsIcon.setOnClickListener(view -> openSetup());
        topBar.addView(settingsIcon);

        adapter = new HomeAdapter();
        list.setAdapter(adapter);
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            int index = position - 1;  // 0 是 header
            if (index >= 0 && index < displayed.size()) {
                showWorkMenu(displayed.get(index));
                return true;
            }
            return false;
        });
        setContentView(root);
    }

    private void buildHeader(LinearLayout header) {
        // 头像 + 名字
        LinearLayout profile = new LinearLayout(this);
        profile.setOrientation(LinearLayout.HORIZONTAL);
        profile.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(profile);

        TextView avatar = new TextView(this);
        avatar.setText("净");
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(26);
        avatar.setTypeface(Ui.BLACK);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable avatarBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF292933, 0xFF14141A});
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setStroke(Ui.dp(this, 1.5f), 0x33FFFFFF);
        avatar.setBackground(avatarBg);
        profile.addView(avatar, new LinearLayout.LayoutParams(
                Ui.dp(this, 74), Ui.dp(this, 74)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams namesParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        namesParams.leftMargin = Ui.dp(this, 14);
        profile.addView(names, namesParams);

        names.addView(Ui.text(this, "净影 · 观看已下载", 21, Ui.TEXT, true));
        hostText = Ui.text(this, "WebDAV 片库", 12, Ui.DIM, false);
        LinearLayout.LayoutParams hostParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hostParams.topMargin = Ui.dp(this, 5);
        names.addView(hostText, hostParams);

        TextView badge = Ui.text(this, "本地播放", 10, 0xFFB6F5F3, true);
        badge.setBackground(Ui.round(this, 0x3325F4EE, 8));
        badge.setPadding(Ui.dp(this, 7), Ui.dp(this, 3), Ui.dp(this, 7), Ui.dp(this, 3));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.topMargin = Ui.dp(this, 7);
        names.addView(badge, badgeParams);

        // 统计
        statsText = Ui.text(this, "作品 0   ·   作者 0   ·   缓存 0 B", 13, Ui.DIM, false);
        LinearLayout.LayoutParams statsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statsParams.topMargin = Ui.dp(this, 16);
        header.addView(statsText, statsParams);

        // 按钮
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = Ui.dp(this, 14);
        header.addView(actions, actionsParams);

        TextView watch = Ui.text(this, "开始观看", 14, Color.WHITE, true);
        watch.setGravity(Gravity.CENTER);
        watch.setBackground(Ui.ripple(this, Ui.round(this, Ui.ACCENT, 22), 22));
        watch.setOnClickListener(view -> openFeed(displayed, 0));
        LinearLayout.LayoutParams watchParams = new LinearLayout.LayoutParams(0,
                Ui.dp(this, 44), 1f);
        actions.addView(watch, watchParams);

        TextView random = Ui.text(this, "随机观看", 14, Ui.TEXT, true);
        random.setGravity(Gravity.CENTER);
        random.setBackground(Ui.ripple(this, Ui.roundStroke(this, 0x00000000, Ui.LINE, 22, 1), 22));
        random.setOnClickListener(view -> randomWatch());
        LinearLayout.LayoutParams randomParams = new LinearLayout.LayoutParams(0,
                Ui.dp(this, 44), 1f);
        randomParams.leftMargin = Ui.dp(this, 10);
        actions.addView(random, randomParams);

        TextView rescan = Ui.text(this, "重新扫描", 14, Ui.TEXT, true);
        rescan.setGravity(Gravity.CENTER);
        rescan.setBackground(Ui.ripple(this, Ui.roundStroke(this, 0x00000000, Ui.LINE, 22, 1), 22));
        rescan.setOnClickListener(view -> startScan(false));
        LinearLayout.LayoutParams rescanParams = new LinearLayout.LayoutParams(0,
                Ui.dp(this, 44), 1f);
        rescanParams.leftMargin = Ui.dp(this, 10);
        actions.addView(rescan, rescanParams);

        continueText = Ui.text(this, "", 12, 0xFF7FD8FF, true);
        continueText.setVisibility(View.GONE);
        continueText.setPadding(0, Ui.dp(this, 10), 0, 0);
        continueText.setOnClickListener(view -> resumeLast());
        header.addView(continueText);

        // 空状态
        emptyText = Ui.label(this, "", 13, Ui.DIM);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setVisibility(View.GONE);
        LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        emptyParams.topMargin = Ui.dp(this, 18);
        header.addView(emptyText, emptyParams);

        emptyAction = Ui.text(this, "去连接", 13, Color.WHITE, true);
        emptyAction.setGravity(Gravity.CENTER);
        emptyAction.setBackground(Ui.ripple(this, Ui.round(this, 0xFF25252C, 18), 18));
        emptyAction.setPadding(Ui.dp(this, 18), Ui.dp(this, 9), Ui.dp(this, 18), Ui.dp(this, 9));
        emptyAction.setVisibility(View.GONE);
        emptyAction.setOnClickListener(view -> {
            if (!prefs.hasDav()) {
                openSetup();
            } else {
                startScan(false);
            }
        });
        LinearLayout.LayoutParams emptyActionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        emptyActionParams.topMargin = Ui.dp(this, 10);
        emptyActionParams.gravity = Gravity.CENTER_HORIZONTAL;
        header.addView(emptyAction, emptyActionParams);

        // 搜索框
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(Ui.round(this, 0xFF1A1A20, 12));
        searchBox.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 42));
        searchParams.topMargin = Ui.dp(this, 16);
        header.addView(searchBox, searchParams);

        ImageView searchIcon = Ui.icon(this, R.drawable.ic_search, 18, Ui.FAINT);
        searchBox.addView(searchIcon);
        search = new EditText(this);
        search.setBackground(null);
        search.setHint("搜索作者或文案");
        search.setHintTextColor(Ui.FAINT);
        search.setTextColor(Ui.TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setPadding(Ui.dp(this, 8), 0, 0, 0);
        searchBox.addView(search, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // 每敲一个字就过一遍 1.6 万条太贵：停手 220ms 再筛
                handler.removeCallbacks(searchRunnable);
                handler.postDelayed(searchRunnable, 220L);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // 作者筛选
        chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = Ui.dp(this, 12);
        header.addView(chipsScroll, scrollParams);
        chipsBox = new LinearLayout(this);
        chipsBox.setOrientation(LinearLayout.HORIZONTAL);
        chipsScroll.addView(chipsBox);
        chipsScroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (chipAuthors == null || chipBuilt >= chipAuthors.size()) {
                return;
            }
            View content = chipsBox;
            int near = Ui.dp(this, 240);
            if (content != null && x + view.getWidth() >= content.getWidth() - near) {
                appendChips();
            }
        });

        // 扫描状态
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = Ui.dp(this, 14);
        header.addView(statusRow, statusParams);

        statusText = Ui.text(this, "准备就绪", 11, Ui.FAINT, false);
        statusRow.addView(statusText);
        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanProgress.setMax(1000);
        scanProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        scanProgress.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        scanProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3));
        progressParams.topMargin = Ui.dp(this, 8);
        statusRow.addView(scanProgress, progressParams);

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(1, Ui.dp(this, 10)));
    }

    // ---------- 数据 ----------
    private void loadFromStore() {
        allWorks.clear();
        allRels.clear();
        for (Work work : app.store.works()) {
            if (!allRels.add(work.rel)) {
                continue;
            }
            allWorks.add(work);
        }
        worksSorted = true;
        lastRevision = app.store.revision();
        cachedStats = null;
        rebuildDisplayed();
        refreshCacheBytes();
    }

    private void refreshCacheBytes() {
        app.io.execute(() -> {
            long bytes = 0;
            try {
                bytes = app.proxy.cacheBytes();
            } catch (Exception ignored) {
            }
            cacheBytes = bytes;
            runOnUiThread(() -> {
                if (statsText != null) {
                    renderStats();
                }
            });
        });
    }

    private void rebuildDisplayed() {
        List<Work> base;
        if (filterAuthor.isEmpty() && query.isEmpty()) {
            // 没筛选时直接引用全量列表，避免每次刷新都复制/排序 1.6 万条
            base = allWorks;
        } else {
            // 列表已经按时间排好时不用再排一遍（1.6 万条每次搜索都重排很贵）
            base = Library.filter(allWorks, filterAuthor, query, "all", !worksSorted);
        }
        if (offlineOnly) {
            List<Work> onlyOffline = new ArrayList<>();
            for (Work work : base) {
                if (app.offline.has(work)) {
                    onlyOffline.add(work);
                }
            }
            displayed = onlyOffline;
        } else {
            displayed = base;
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        renderStats();
        renderChips();
        renderStatusIdle();
        updateContinue();
        if (displayed.isEmpty()) {
            if (offlineOnly) {
                emptyText.setText("还没有下载到本地的作品，长按封面可以下载");
                emptyAction.setText("重新扫描");
                emptyAction.setVisibility(View.VISIBLE);
            } else if (!query.isEmpty() || !filterAuthor.isEmpty()) {
                // 筛选/搜索命中 0 条，不是「片库空」，别引导用户去重新扫描
                emptyText.setText("没有匹配的作品，换个关键词或点「全部」");
                emptyAction.setVisibility(View.GONE);
            } else {
                emptyText.setText(prefs.hasDav()
                        ? (scanning ? "正在从 WebDAV 拉取片库…"
                        : "片库里还没有作品，先确认「净影」已上传到 WebDAV")
                        : "还没有连接 WebDAV 片库");
                emptyAction.setText(prefs.hasDav() ? "重新扫描" : "去连接");
                emptyAction.setVisibility(View.VISIBLE);
            }
            emptyText.setVisibility(View.VISIBLE);
        } else {
            emptyText.setVisibility(View.GONE);
            emptyAction.setVisibility(View.GONE);
        }
    }

    private void renderStats() {
        if (statsText == null) {
            return;
        }
        // 扫描时每 350ms 就会重建一次列表，全量统计 1.6 万条没必要跟着跑那么勤
        long now = System.currentTimeMillis();
        if (cachedStats == null || (!scanning && now - cachedStatsAt > 800L)) {
            cachedStats = Library.stats(allWorks);
            cachedStatsAt = now;
        }
        Library.Stats stats = cachedStats;
        // 扫描中列表在长，直接数当前条数；作者数用上一次的统计，别每个 batch 都去重一遍
        int count = scanning ? allWorks.size() : stats.count;
        statsText.setText("作品 " + count + "   ·   作者 " + stats.authors
                + "   ·   缓存 " + Util.humanSize(cacheBytes));
        hostText.setText(prefs.hasDav() ? app.hostLabel() : "未连接 WebDAV");
    }

    private void renderChips() {
        if (chipsBox == null) {
            return;
        }
        if (scanning) {
            // 扫描期间作者列表每 350ms 变一次，重建几千个 chip 纯属浪费；
            // onDone 里会把 scanning 置回 false 再重建一次。
            return;
        }
        List<Library.AuthorInfo> authors = cachedAuthors();
        int offline = cachedOfflineCount();
        long hash = authorHash(authors);
        boolean authorsChanged = chipAuthors == null || hash != builtAuthorsHash;
        if (!authorsChanged) {
            // 下载数量变了只改「已下载 N」那一颗，别把整排 chip 重建（滚动位置会跳回最左）
            if (offline != builtOfflineCount) {
                builtOfflineCount = offline;
                if (offlineChip != null) {
                    offlineChip.setText("已下载 " + offline);
                }
            }
            ensureChipBuilt(filterAuthor);
            if (!filterAuthor.equals(lastChipFilter) || offlineOnly != lastChipOffline) {
                updateChipStyles();
            }
            lastChipFilter = filterAuthor;
            lastChipOffline = offlineOnly;
            return;
        }
        lastChipFilter = filterAuthor;
        lastChipOffline = offlineOnly;
        builtAuthorsHash = hash;
        builtOfflineCount = offline;
        chipAuthors = authors;
        chipBuilt = 0;
        chipsBox.removeAllViews();
        chipViews.clear();
        addChip("全部", "");
        addOfflineChip(offline);
        appendChips();
        ensureChipBuilt(filterAuthor);
    }

    /** 一次只挂一页 chip；横向滚到接近末尾再追加下一页。 */
    private void appendChips() {
        if (chipAuthors == null) {
            return;
        }
        int target = Math.min(chipAuthors.size(), chipBuilt + CHIP_PAGE);
        for (int index = chipBuilt; index < target; index++) {
            Library.AuthorInfo info = chipAuthors.get(index);
            addChip(info.name + " " + info.count, info.name);
        }
        chipBuilt = target;
    }

    /** 当前筛选的作者还没挂出来时，把他前面的几页补上。 */
    private void ensureChipBuilt(String author) {
        if (author == null || author.isEmpty() || chipAuthors == null) {
            return;
        }
        for (int index = chipBuilt; index < chipAuthors.size(); index++) {
            if (chipAuthors.get(index).name.equals(author)) {
                while (chipBuilt <= index && chipBuilt < chipAuthors.size()) {
                    appendChips();
                }
                return;
            }
        }
    }

    /** 作者列表只在索引真的变了以后重算；Library.authors 要遍历+排序全库。 */
    private List<Library.AuthorInfo> cachedAuthors() {
        int revision = app.store.revision();
        if (cachedAuthors == null || cachedAuthorsRevision != revision
                || cachedAuthorsSize != allWorks.size()) {
            cachedAuthors = Library.authors(allWorks);
            cachedAuthorsRevision = revision;
            cachedAuthorsSize = allWorks.size();
        }
        return cachedAuthors;
    }

    private static long authorHash(List<Library.AuthorInfo> authors) {
        long hash = 17L;
        for (Library.AuthorInfo info : authors) {
            hash = hash * 31L + info.name.hashCode();
            hash = hash * 31L + info.count;
        }
        return hash;
    }

    /** 只统计当前片库里确实还能本地播放的作品，避免旧路径下载数虚高。 */
    private int offlineCount() {
        int count = 0;
        for (Work work : allWorks) {
            if (app.offline.has(work)) {
                count++;
            }
        }
        return count;
    }

    private int cachedOfflineCacheSize = -1;

    private int cachedOfflineCount() {
        int revision = app.store.revision();
        if (offlineDirty || cachedOfflineRevision != revision
                || cachedOfflineCacheSize != allWorks.size() || cachedOfflineCount < 0) {
            cachedOfflineCount = offlineCount();
            cachedOfflineRevision = revision;
            cachedOfflineCacheSize = allWorks.size();
            offlineDirty = false;
        }
        return cachedOfflineCount;
    }

    private void addOfflineChip(int count) {
        boolean active = offlineOnly;
        TextView chip = Ui.text(this, "已下载 " + count, 12,
                active ? Color.WHITE : Ui.DIM, active);
        chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 7),
                Ui.dp(this, 12), Ui.dp(this, 7));
        chip.setBackground(Ui.round(this, active ? 0xFF1D4A4A : 0xFF17171C, 14));
        chip.setTag(new ChipTag("", true, active));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Ui.dp(this, 8);
        chip.setOnClickListener(view -> {
            offlineOnly = true;
            filterAuthor = "";
            query = "";
            if (search != null) {
                search.setText("");
            }
            offlineDirty = true;
            rebuildDisplayed();
        });
        chipsBox.addView(chip, params);
        chipViews.add(chip);
        offlineChip = chip;
    }

    private void addChip(String label, String author) {
        boolean active = !offlineOnly && author.equals(filterAuthor);
        TextView chip = Ui.text(this, label, 12, active ? Color.WHITE : Ui.DIM, active);
        chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 7),
                Ui.dp(this, 12), Ui.dp(this, 7));
        chip.setBackground(Ui.round(this, active ? 0xFF2C2C34 : 0xFF17171C, 14));
        chip.setTag(new ChipTag(author, false, active));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Ui.dp(this, 8);
        chip.setOnClickListener(view -> {
            offlineOnly = false;
            filterAuthor = author;
            rebuildDisplayed();
        });
        chipsBox.addView(chip, params);
        chipViews.add(chip);
    }

    /** 筛选变化时只改颜色；只有状态真的变了才新建 drawable。 */
    private void updateChipStyles() {
        for (TextView chip : chipViews) {
            Object tag = chip.getTag();
            if (!(tag instanceof ChipTag)) {
                continue;
            }
            ChipTag info = (ChipTag) tag;
            boolean active = info.offline
                    ? offlineOnly
                    : (!offlineOnly && info.author.equals(filterAuthor));
            if (active == info.active) {
                continue;
            }
            info.active = active;
            chip.setTextColor(active ? Color.WHITE : Ui.DIM);
            int background = active
                    ? (info.offline ? 0xFF1D4A4A : 0xFF2C2C34) : 0xFF17171C;
            chip.setBackground(Ui.round(this, background, 14));
        }
    }

    private static final class ChipTag {
        final String author;
        final boolean offline;
        boolean active;

        ChipTag(String author, boolean offline, boolean active) {
            this.author = author;
            this.offline = offline;
            this.active = active;
        }
    }

    private void renderStatusIdle() {
        if (scanning || statusText == null) {
            return;
        }
        if (lastError != null) {
            statusText.setText(lastError);
            statusText.setTextColor(Ui.ACCENT);
            return;
        }
        statusText.setTextColor(Ui.FAINT);
        long scanned = app.store.scannedAt();
        String when = scanned > 0 ? " · 上次扫描 " + Util.relativeTime(scanned) : "";
        statusText.setText(displayed.size() + " 个作品" + when
                + (offlineOnly ? " · 只看已下载" : "")
                + (filterAuthor.isEmpty() ? "" : " · 只看 " + filterAuthor));
        scanProgress.setVisibility(View.GONE);
    }

    private void updateContinue() {
        if (continueText == null) {
            return;
        }
        int index = indexOfKey(prefs.lastKey());
        if (index >= 0) {
            Work work = displayed.get(index);
            continueText.setText("继续上次观看：@" + work.displayAuthor() + " · " + work.displayTitle());
            continueText.setVisibility(View.VISIBLE);
        } else {
            continueText.setVisibility(View.GONE);
        }
    }

    private int indexOfKey(String key) {
        if (key == null || key.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < displayed.size(); index++) {
            if (displayed.get(index).key().equals(key)) {
                return index;
            }
        }
        return -1;
    }

    private void resumeLast() {
        int index = indexOfKey(prefs.lastKey());
        if (index >= 0) {
            openFeed(displayed, index);
        }
    }

    /** 随机挑一条视频开始看；片库里没有视频时退回全部作品。 */
    private void randomWatch() {
        // 筛选中/只看已下载时，随机范围就是眼前这份列表；否则用全库
        boolean filtered = offlineOnly || !filterAuthor.isEmpty() || !query.isEmpty();
        List<Work> source = filtered ? displayed : allWorks;
        List<Work> pool = new ArrayList<>();
        List<Work> unknown = new ArrayList<>();
        for (Work work : source) {
            if (work.empty) {
                // 空目录（连图片/视频都没有）随机到只会报错
                continue;
            }
            if (work.isVideo()) {
                pool.add(work);
            } else if (!work.isAlbum()) {
                // 还没读过详情的作品大多是视频，先放进候选；否则随机池只剩已经播放过的几条
                unknown.add(work);
            }
        }
        // 需要把还没读详情的作品也算进来，否则随机池只有已经播放过/下载过的那几十条
        pool.addAll(unknown);
        if (pool.isEmpty()) {
            for (Work work : source) {
                if (!work.empty) {
                    pool.add(work);
                }
            }
        }
        if (pool.isEmpty()) {
            Ui.toast(this, filtered ? "当前筛选里没有可播放的作品"
                    : (prefs.hasDav() ? "片库里还没有可播放的作品" : "先连接 WebDAV 片库"));
            return;
        }
        // 整段打乱：随机观看要的是整段随机顺序，而不是只随机一个起点、
        // 之后顺着作者目录连续看下去（那样同一个作者的一堆视频总扎堆在一起）。
        Shuffles.fisherYates(pool, new Random());
        // 连续点击时尽量别两次第一条都一模一样（约万分之一概率，做个轻保护）
        if (pool.size() > 1 && pool.get(0).rel.equals(lastRandomKey)) {
            int swap = new Random().nextInt(pool.size() - 1) + 1;
            Work tmp = pool.get(0);
            pool.set(0, pool.get(swap));
            pool.set(swap, tmp);
        }
        lastRandomKey = pool.get(0).rel;
        openFeed(pool, 0);
    }

    private void openFeed(List<Work> works, int index) {
        if (works.isEmpty()) {
            Ui.toast(this, prefs.hasDav() ? "片库里还没有作品" : "先连接 WebDAV 片库");
            return;
        }
        FeedSession.open(works, index, "观看已下载");
        startActivity(new Intent(this, FeedActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void openSetup() {
        startActivityForResult(new Intent(this, SetupActivity.class), REQ_SETUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETUP && resultCode == RESULT_OK) {
            allWorks.clear();
            loadFromStore();
            startScan(true);
        }
    }

    // ---------- 扫描 ----------
    private void startScan(boolean auto) {
        if (!prefs.hasDav()) {
            openSetup();
            return;
        }
        if (scanning) {
            return;
        }
        if (app.scanner.isRunning()) {
            // 扫描是全局的：上一次扫描还没结束（比如退出后重进、或别的页面起的），
            // 这里接上去等它结束，否则这次扫描的增量永远不会回到首页。
            attachToRunningScan();
            return;
        }
        scanning = true;
        lastError = null;
        handler.removeCallbacks(scanWatcher);
        emptyText.setVisibility(displayed.isEmpty() ? View.VISIBLE : View.GONE);
        emptyText.setText("正在从 WebDAV 拉取片库…");
        emptyAction.setVisibility(View.GONE);
        statusText.setText("正在连接 WebDAV…");
        scanProgress.setVisibility(View.VISIBLE);
        scanProgress.setIndeterminate(true);
        app.scanner.scan(this);
    }

    /** 扫描进行中时挂上去：只轮询是否结束，结束后重读一次落盘结果。 */
    private void attachToRunningScan() {
        scanning = true;
        lastError = null;
        if (statusText != null) {
            statusText.setText("已有扫描在进行，等它结束后刷新片库…");
        }
        if (scanProgress != null) {
            scanProgress.setVisibility(View.VISIBLE);
            scanProgress.setIndeterminate(true);
        }
        handler.removeCallbacks(scanWatcher);
        handler.postDelayed(scanWatcher, 400L);
    }

    private final Runnable scanWatcher = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (app.scanner.isRunning()) {
                handler.postDelayed(this, 400L);
                return;
            }
            scanning = false;
            if (scanProgress != null) {
                scanProgress.setVisibility(View.GONE);
            }
            allWorks.clear();
            allRels.clear();
            loadFromStore();
            refreshCacheBytes();
        }
    };

    @Override
    public void onPhase(String message) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (statusText != null) {
                statusText.setText(message);
            }
        });
    }

    @Override
    public void onProgress(int authorsDone, int authorsTotal, int worksFound) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (scanProgress != null) {
                scanProgress.setIndeterminate(false);
                scanProgress.setMax(Math.max(1, authorsTotal));
                scanProgress.setProgress(authorsDone);
            }
            if (statusText != null) {
                statusText.setText("已读取 " + authorsDone + "/" + authorsTotal
                        + " 位作者，共 " + worksFound + " 个作品");
            }
        });
    }

    @Override
    public void onBatch(List<Work> works) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            boolean added = false;
            for (Work work : works) {
                if (allRels.add(work.rel)) {
                    allWorks.add(work);
                    added = true;
                }
            }
            if (!added) {
                return;
            }
            worksSorted = false;
            handler.removeCallbacks(rebuildRunnable);
            handler.postDelayed(rebuildRunnable, 350L);
        });
    }

    @Override
    public void onDone(int works, String error) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        handler.removeCallbacks(scanWatcher);
        scanning = false;
        handler.removeCallbacks(rebuildRunnable);
        if (error != null && !error.isEmpty() && !"已停止扫描".equals(error)) {
            lastError = error;
            Ui.toast(this, error);
        } else {
            lastError = null;
        }
        if (error == null || "已停止扫描".equals(error)) {
            // 正常结束、或用户中途停止：以落盘结果为准。
            // 停止时扫描线程可能已经 addBatch 了一部分，界面上那份增量不能再留着（点开会 404）。
            allWorks.clear();
            allRels.clear();
            for (Work work : app.store.works()) {
                if (allRels.add(work.rel)) {
                    allWorks.add(work);
                }
            }
            worksSorted = true;
        }
        cachedStats = null;   // 扫描结束，统计必须是最新的一份
        offlineDirty = true;   // 扫描结束，已下载数量按新片库重算
        rebuildDisplayed();
        refreshCacheBytes();
        if (scanProgress != null) {
            scanProgress.setVisibility(View.GONE);
        }
        if (error == null) {
            Ui.toast(this, works + " 个作品已就绪");
        }
    }

    private final OfflineStore.Listener offlineListener = new OfflineStore.Listener() {
        @Override
        public void onOfflineChanged(Work work) {
            runOnUiThread(() -> {
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
                offlineDirty = true;
                renderChips();
                renderStats();
                if (offlineOnly) {
                    rebuildDisplayed();
                }
            });
        }

        @Override
        public void onDownloadProgress(Work work, int percent) {
            // 首页不跟着每 400ms 刷一遍，下载完成会发 onOfflineChanged
        }
    };

    private void showWorkMenu(Work work) {
        if (work == null) {
            return;
        }
        if (app.offline.isDownloading(work)) {
            Ui.toast(this, "正在下载这个作品…");
            return;
        }
        boolean saved = app.offline.has(work);
        final String[] items = saved
                ? new String[]{"删除本地下载", "移入回收站（含本地）"}
                : new String[]{"下载到本地", "移入回收站"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(work.displayTitle())
                .setItems(items, (dialog, which) -> {
                    if (which == 1) {
                        confirmDeleteWork(work);
                        return;
                    }
                    if (saved) {
                        app.offline.delete(work);
                        Ui.toast(this, "已删除本地文件");
                        adapter.notifyDataSetChanged();
                        offlineDirty = true;
                        renderChips();
                        renderStats();
                        if (offlineOnly) {
                            rebuildDisplayed();
                        }
                    } else {
                        downloadWork(work);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 删除云端作品：二次确认后移入回收站，再清掉本地索引/下载/缓存。 */
    private void confirmDeleteWork(Work work) {
        Ui.confirm(this, "移入回收站",
                "将从片库移除：\n" + work.displayTitle() + "\n\n"
                        + "• 云端文件会移到「回收站」，可以随时恢复\n"
                        + "• 本地下载和播放缓存会清除\n"
                        + "• 回收站里可以彻底删除",
                "移入回收站", () -> {
                    final android.app.AlertDialog busy = Ui.busy(this, "正在移入回收站…");
                    app.trash.moveWorkAsync(work, result -> {
                        Ui.dismiss(busy);
                        if (result.ok) {
                            Ui.toast(this, "已移入回收站");
                            removeFromHome(work);
                            return;
                        }
                        if (result.moveUnsupported) {
                            confirmDeleteForever(work);
                            return;
                        }
                        Ui.toast(this, result.error == null ? "操作失败" : result.error);
                    });
                });
    }

    /** 服务器不支持 MOVE 时的兜底：明确告知后彻底删除。 */
    private void confirmDeleteForever(Work work) {
        Ui.confirm(this, "网盘不支持回收站",
                "这个 WebDAV 服务器不支持移动文件（MOVE），没法把作品放进回收站。\n\n"
                        + "要直接彻底删除吗？删除后无法恢复。",
                "彻底删除", () -> {
                    final android.app.AlertDialog busy = Ui.busy(this, "正在删除…");
                    app.trash.deleteForeverAsync(work, result -> {
                        Ui.dismiss(busy);
                        if (!result.ok) {
                            Ui.toast(this, result.error == null ? "删除失败" : result.error);
                            return;
                        }
                        Ui.toast(this, "已删除");
                        removeFromHome(work);
                    });
                });
    }

    /** 删除成功后把这条从首页列表里摘掉；不整份重读，扫描中也不会打断增量结果。 */
    private void removeFromHome(Work work) {
        allRels.remove(work.rel);
        allWorks.remove(work);
        cachedStats = null;
        offlineDirty = true;
        rebuildDisplayed();
        refreshCacheBytes();
    }

    private void downloadWork(Work work) {
        if (!work.detailsLoaded) {
            Ui.toast(this, "正在读取作品信息…");
            app.scanner.loadDetails(work, (target, ok, error) -> {
                if (!ok) {
                    Ui.toast(this, error == null ? "作品信息读取失败" : error);
                    return;
                }
                app.offline.download(work);
                adapter.notifyDataSetChanged();
                Ui.toast(this, "已加入本地下载");
            });
            return;
        }
        app.offline.download(work);
        adapter.notifyDataSetChanged();
        Ui.toast(this, "已加入本地下载，完成后可离线观看");
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.offline.addListener(offlineListener);
        // 在作者主页/播放页删掉作品后返回，本地索引已经变了，重新读一次。
        // 用 revision 而不是数量：删一条又补一条时数量相同，但内容已经不同了。
        if (!scanning && app.store.isLoaded() && app.store.revision() != lastRevision) {
            loadFromStore();
        }
        if (list != null) {
            adapter.notifyDataSetChanged();
        }
        updateContinue();
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.offline.removeListener(offlineListener);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(loadWatcher);
        handler.removeCallbacks(rebuildRunnable);
        handler.removeCallbacks(searchRunnable);
        handler.removeCallbacks(scanWatcher);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------- 封面墙 ----------
    private final class HomeAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return (displayed.size() + 2) / 3;
        }

        @Override
        public Object getItem(int position) {
            if (displayed.isEmpty()) {
                return null;
            }
            return displayed.get(Math.min(position * 3, displayed.size() - 1));
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            GridRow row = convertView instanceof GridRow ? (GridRow) convertView : new GridRow();
            row.bind(position);
            return row;
        }
    }

    private final class GridRow extends LinearLayout {
        final Cell[] cells = new Cell[3];

        GridRow() {
            super(HomeActivity.this);
            setOrientation(HORIZONTAL);
            setPadding(gridPadding, gridGap, gridPadding, gridGap);
            setLayoutParams(new ListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, cellHeight + gridGap * 2));
            for (int index = 0; index < 3; index++) {
                Cell cell = new Cell();
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, cellHeight, 1f);
                if (index > 0) {
                    params.leftMargin = gridGap;
                }
                addView(cell, params);
                cells[index] = cell;
            }
        }

        void bind(int rowIndex) {
            for (int index = 0; index < 3; index++) {
                int workIndex = rowIndex * 3 + index;
                if (workIndex < displayed.size()) {
                    cells[index].bind(displayed.get(workIndex), workIndex);
                } else {
                    cells[index].clear();
                }
            }
        }
    }

    private final class Cell extends FrameLayout {
        final ImageView cover;
        final TextView title;
        final TextView badge;
        final TextView offlineBadge;
        /** 两种状态底色只建一次：每次 bind 都 Ui.round 会在滚动时堆垃圾。 */
        final GradientDrawable savedBadgeBg;
        final GradientDrawable downloadingBadgeBg;
        int index = -1;

        Cell() {
            super(HomeActivity.this);
            setBackground(Ui.round(HomeActivity.this, 0xFF16161B, 8));
            cover = new ImageView(HomeActivity.this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            Ui.dpf(HomeActivity.this, 8));
                }
            });
            cover.setClipToOutline(true);
            addView(cover, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            View scrim = new View(HomeActivity.this);
            scrim.setBackground(Ui.verticalGradient(HomeActivity.this,
                    0x00000000, 0xCC000000));
            FrameLayout.LayoutParams scrimParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(HomeActivity.this, 90));
            scrimParams.gravity = Gravity.BOTTOM;
            addView(scrim, scrimParams);

            title = Ui.text(HomeActivity.this, "", 11, 0xEEFFFFFF, false);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleParams.gravity = Gravity.BOTTOM;
            titleParams.setMargins(Ui.dp(HomeActivity.this, 7), 0,
                    Ui.dp(HomeActivity.this, 7), Ui.dp(HomeActivity.this, 7));
            addView(title, titleParams);

            badge = Ui.text(HomeActivity.this, "", 9, Color.WHITE, true);
            badge.setBackground(Ui.round(HomeActivity.this, 0x66000000, 7));
            badge.setPadding(Ui.dp(HomeActivity.this, 5), Ui.dp(HomeActivity.this, 2),
                    Ui.dp(HomeActivity.this, 5), Ui.dp(HomeActivity.this, 2));
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeParams.gravity = Gravity.TOP | Gravity.END;
            badgeParams.setMargins(0, Ui.dp(HomeActivity.this, 5),
                    Ui.dp(HomeActivity.this, 5), 0);
            addView(badge, badgeParams);

            offlineBadge = Ui.text(HomeActivity.this, "已下载", 9, Color.WHITE, true);
            savedBadgeBg = Ui.round(HomeActivity.this, 0xCC0E7C86, 7);
            downloadingBadgeBg = Ui.round(HomeActivity.this, 0xCC9A5B00, 7);
            offlineBadge.setBackground(savedBadgeBg);
            offlineBadge.setPadding(Ui.dp(HomeActivity.this, 5), Ui.dp(HomeActivity.this, 2),
                    Ui.dp(HomeActivity.this, 5), Ui.dp(HomeActivity.this, 2));
            offlineBadge.setVisibility(View.GONE);
            FrameLayout.LayoutParams offlineParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            offlineParams.gravity = Gravity.TOP | Gravity.START;
            offlineParams.setMargins(Ui.dp(HomeActivity.this, 5),
                    Ui.dp(HomeActivity.this, 5), 0, 0);
            addView(offlineBadge, offlineParams);

            setOnClickListener(view -> {
                if (index >= 0 && index < displayed.size()) {
                    openFeed(displayed, index);
                }
            });
            setLongClickable(true);
            setOnLongClickListener(view -> {
                if (index >= 0 && index < displayed.size()) {
                    showWorkMenu(displayed.get(index));
                    return true;
                }
                return false;
            });
        }

        void bind(Work work, int index) {
            this.index = index;
            title.setText(work.displayTitle());
            if (work.isAlbum()) {
                badge.setText("图集 " + work.images.size());
                badge.setVisibility(View.VISIBLE);
            } else if (work.isVideo()) {
                badge.setText("视频");
                badge.setVisibility(View.VISIBLE);
            } else if (work.isArchive()) {
                // 作品包：类型要等打开时读 tar 头才知道，先给个中性角标
                badge.setText("作品");
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }
            if (app.offline.has(work)) {
                offlineBadge.setText("已下载");
                offlineBadge.setBackground(savedBadgeBg);
                offlineBadge.setVisibility(View.VISIBLE);
            } else if (app.offline.isDownloading(work)) {
                int percent = Math.max(0, app.offline.progressOf(work));
                offlineBadge.setText("下载中 " + percent + "%");
                offlineBadge.setBackground(downloadingBadgeBg);
                offlineBadge.setVisibility(View.VISIBLE);
            } else {
                offlineBadge.setVisibility(View.GONE);
            }
            android.graphics.Bitmap peek = app.images.peek(work.key());
            if (peek != null) {
                cover.setImageBitmap(peek);
            } else {
                cover.setImageBitmap(null);
                cover.setBackgroundColor(0xFF16161B);
                app.images.loadCover(work, (key, bitmap) -> {
                    if (bitmap != null && work.rel.equals(displayedWorkRel(index))) {
                        cover.setImageBitmap(bitmap);
                    }
                });
            }
        }

        private String displayedWorkRel(int position) {
            if (position >= 0 && position < displayed.size()) {
                return displayed.get(position).rel;
            }
            return "";
        }

        void clear() {
            index = -1;
            cover.setImageBitmap(null);
            title.setText("");
            badge.setVisibility(View.GONE);
            offlineBadge.setVisibility(View.GONE);
        }
    }
}

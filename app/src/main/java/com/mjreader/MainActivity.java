package com.mjreader;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;

public class MainActivity extends Activity {
    private final ArrayList<Uri> pendingFiles = new ArrayList<>();
    private boolean libraryLoaded;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver downloadedBooks = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            worker.execute(() -> {
                try {
                    List<Books.Book> loaded = store.list();
                    runOnUiThread(() -> { if (!isDestroyed()) { books = loaded; if (current == null && !settingsOpen) library(); } });
                } catch (Exception error) { android.util.Log.e("MJReader", "Refresh downloaded books", error); }
            });
        }
    };
    private SharedPreferences prefs;
    private Books store;
    private List<Books.Book> books = new ArrayList<>();
    private Books.Book current;
    private ReadingText reading;
    private ReaderOptions options;
    private LinearLayout root, header, footer;
    private android.transition.Transition controlsTransition;
    private boolean controlsFramePending;
    private ListView libraryList;
    private BookAdapter adapter;
    private EditText librarySearch;
    private String query = "", filter = "all", collectionFilter = "";
    private String reimportId;
    private int sort, libraryRow, position, anchor, returnAnchor = -1, speed, countdown;
    private int libraryBookIndex = -1, libraryOffset;
    private boolean grid, focus, playing, restoring, fullscreen, importing;
    private static volatile boolean batchRunning;
    private static java.lang.ref.WeakReference<MainActivity> active = new java.lang.ref.WeakReference<>(null);
    private ScrollView textScroll;
    private ReaderPages pageReader;
    private AlertDialog notePopup;
    private int pictureRequest, restoreRequest;
    private TextView body, counter, contextLabel, chapterLabel, importStatus;
    private FocusView focusView;
    private Button play;
    private SeekBar progress;
    private int renderVersion, textStart, textEnd;
    private boolean settingsOpen, settingsRsvp, settingsLibrary, restoreSettings;
    private ScrollView settingsScroll;
    private int settingsY;
    private final LinkedHashMap<String, Bitmap> covers = new LinkedHashMap<>(16, .75f, true);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!playing || reading == null) return;
            if (countdown > 0) {
                countdown--;
                updateFocus();
                handler.postDelayed(this, countdown > 0 ? 700 : wordDelay());
                return;
            }
            if (position >= reading.words.size() - 1) {
                pause();
                prefs.edit().putString(key(current, "status"), "done").apply();
                updateFocus();
                return;
            }
            position++;
            anchor = reading.offsets.get(position);
            updateFocus();
            handler.postDelayed(this, wordDelay());
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        active = new java.lang.ref.WeakReference<>(this);
        importing = batchRunning;
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        store = new Books(this);
        IntentFilter downloadsFilter = new IntentFilter(getPackageName() + ".BOOKS_IMPORTED");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadedBooks, downloadsFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(downloadedBooks, downloadsFilter);
        options = ReaderOptions.load(prefs, null);
        grid = prefs.getBoolean("grid", true);
        sort = prefs.getInt("sort", 0);
        if (state != null) {
            ArrayList<Uri> pending = state.getParcelableArrayList("pendingFiles");
            if (pending != null) pendingFiles.addAll(pending);
            reimportId = state.getString("reimportId");
            query = state.getString("query", "");
            filter = state.getString("filter", "all");
            collectionFilter = state.getString("collection", "");
            libraryRow = state.getInt("libraryRow");
            libraryBookIndex = state.getInt("libraryBookIndex", -1);
            libraryOffset = state.getInt("libraryOffset");
            fullscreen = state.getBoolean("fullscreen");
            restoreSettings = state.getBoolean("settingsOpen");
            settingsRsvp = state.getBoolean("settingsRsvp");
            settingsLibrary = state.getBoolean("settingsLibrary");
            settingsY = state.getInt("settingsY");
        }
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::back);
        if (state == null) receiveFile(getIntent());
        shell();
        loadingScreen(tr("Ваша библиотека", "Your library"), tr("Загружаем книги…", "Loading books…"));
        worker.execute(() -> {
            try {
                List<Books.Book> loaded = store.list();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    books = loaded; libraryLoaded = true;
                    if (!pendingFiles.isEmpty() && !importing) { openPendingFile(); return; }
                    String id = state == null ? null : state.getString("book");
                    for (Books.Book book : books) if (book.id().equals(id)) {
                        open(book, state.getBoolean("focus"));
                        return;
                    }
                    library();
                    if (restoreSettings) { if (settingsLibrary) sortDialog(); else settings(settingsRsvp); }
                });
            } catch (Exception e) { runOnUiThread(() -> message(tr("Ошибка загрузки", "Loading failed"), e.toString())); }
        });
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receiveFile(intent);
    }
    private void receiveFile(Intent intent) {
        if (intent == null) return;
        Uri uri = Intent.ACTION_VIEW.equals(intent.getAction()) ? intent.getData()
                : Intent.ACTION_SEND.equals(intent.getAction()) ? intent.getParcelableExtra(Intent.EXTRA_STREAM) : null;
        if (uri == null || !"content".equals(uri.getScheme())) return;
        if (!pendingFiles.contains(uri)) pendingFiles.add(uri);
        // Consume the launch action; recreation restores the pending queue instead.
        setIntent(new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN));
        openPendingFile();
    }
    private void openPendingFile() {
        if (!libraryLoaded || importing || pendingFiles.isEmpty()) return;
        pause(); save();
        importUris(Collections.singletonList(pendingFiles.remove(0)), null, true);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        save();
        if (current != null) {
            state.putString("book", current.id());
            state.putBoolean("focus", focus);
        }
        state.putParcelableArrayList("pendingFiles", pendingFiles);
        state.putString("reimportId", reimportId);
        state.putString("query", query);
        state.putString("filter", filter);
        state.putString("collection", collectionFilter);
        state.putInt("libraryRow", libraryRow);
        state.putInt("libraryBookIndex", libraryBookIndex);
        state.putInt("libraryOffset", libraryOffset);
        state.putBoolean("fullscreen", fullscreen);
        state.putBoolean("settingsOpen", settingsOpen);
        state.putBoolean("settingsRsvp", settingsRsvp);
        state.putBoolean("settingsLibrary", settingsLibrary);
        state.putInt("settingsY", settingsScroll == null ? 0 : settingsScroll.getScrollY());
        super.onSaveInstanceState(state);
    }
    @Override protected void onPause() { if (notePopup != null) notePopup.dismiss(); pause(); super.onPause(); }
    @Override protected void onDestroy() {
        unregisterReceiver(downloadedBooks);
        handler.removeCallbacksAndMessages(null);
        worker.shutdown();
        if (active.get() == this) active.clear();
        super.onDestroy();
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { back(); }
    private void back() {
        if (settingsOpen) {
            settingsOpen = false;
            settingsY = 0;
            if (current != null) reader(); else library();
            return;
        }
        if (fullscreen) { setFullscreen(false); return; }
        if (current != null) library(); else finish();
    }
    private String tr(String ru, String en) { return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru") ? ru : en; }
    private String key(Books.Book book, String field) { return field + "." + book.id(); }
    private String title(Books.Book book) { return prefs.getString(key(book, "title"), book.title()); }
    private String author(Books.Book book) { return prefs.getString(key(book, "author"), book.author()); }
    private void save() {
        if (current == null || reading == null) return;
        prefs.edit().putInt(current.id(), position).putInt(key(current, "anchor"), anchor)
                .putString("last", current.id()).apply();
    }
    private void pause() {
        playing = false;
        countdown = 0;
        handler.removeCallbacks(tick);
        if (play != null) playbackLabel(R.string.start);
        applyWindow();
        save();
    }
    private void applyWindow() {
        if (options == null) return;
        if (current != null && (playing || options.flag("awake", false)))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        int brightness = current == null ? 0 : options.number("brightness", 0);
        attributes.screenBrightness = brightness == 0 ? -1 : Math.max(.05f, brightness / 100f);
        getWindow().setAttributes(attributes);
    }
    private void shell() {
        if (notePopup != null) notePopup.dismiss();
        renderVersion++;
        if (root != null) {
            android.transition.TransitionManager.endTransitions(root);
            controlsTransition = null;
            controlsFramePending = false;
            root.animate().cancel();
        }
        getWindow().setBackgroundDrawable(new ColorDrawable(options.background()));
        root = column();
        ReaderOptions.observeWindow(root, () -> {
            if (isDestroyed()) return;
            if (settingsOpen) {
                settingsY = settingsScroll == null ? 0 : settingsScroll.getScrollY();
                if (settingsLibrary) sortDialog(); else settings(settingsRsvp);
            } else if (current != null && reading != null) reader();
            else if (current == null && libraryList != null && libraryList.isAttachedToWindow()) library();
            else root.requestApplyInsets();
        });
        root.setBackgroundColor(options.background());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int width = getResources().getConfiguration().screenWidthDp;
            int limit = settingsOpen || (current != null && (focus || !options.flag("pages", false))) ? 700 : 1200;
            int gutter = Math.max(width >= 600 ? 24 : 16, (width - limit) / 2);
            view.setPadding(dp(gutter) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                    dp(gutter) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        int flags = options.light() ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
        if (Build.VERSION.SDK_INT >= 27 && options.light()) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        root.requestApplyInsets();
        applyWindow();
    }
    private void loadingScreen(String title, String detail) {
        LinearLayout content = column();
        content.setGravity(Gravity.CENTER);
        content.addView(new LoadingRing(), new LinearLayout.LayoutParams(dp(56), dp(56)));
        gap(content, 24);
        TextView heading = label(title, 24, options.ink());
        heading.setGravity(Gravity.CENTER);
        content.addView(heading);
        gap(content, 12);
        TextView status = label(detail, 14, options.muted());
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private class LoadingRing extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final long started = android.os.SystemClock.uptimeMillis();
        LoadingRing() { super(MainActivity.this); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float inset = dp(4);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(3)); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(options.muted()); paint.setAlpha(45);
            canvas.drawOval(inset, inset, getWidth() - inset, getHeight() - inset, paint);
            paint.setColor(options.accent()); paint.setAlpha(255);
            boolean animate = android.animation.ValueAnimator.areAnimatorsEnabled();
            float angle = animate ? (android.os.SystemClock.uptimeMillis() - started) % 1100 * 360f / 1100 : -90;
            canvas.drawArc(inset, inset, getWidth() - inset, getHeight() - inset, angle, 230, false, paint);
            if (animate && isShown()) postInvalidateOnAnimation();
        }
    }
    private void automaticUpdates() {
        if (!AppUpdates.automaticDue(this, System.currentTimeMillis())) return;
        Context context = getApplicationContext();
        AppUpdates.WORKER.execute(() -> {
            try {
                AppUpdates.Release update = AppUpdates.check(context);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || current != null || settingsOpen || !hasWindowFocus() || update == null
                            || !AppUpdates.prefs(this).getBoolean("automatic", true)
                            || update.version().equals(AppUpdates.prefs(this).getString("announced", ""))) return;
                    AppUpdates.prefs(this).edit().putString("announced", update.version()).apply();
                    dialogs().setTitle(tr("Доступно обновление ", "Update available ") + update.version())
                            .setMessage(tr("Посмотреть изменения и скачать новую версию MJReader?", "View changes and download the new MJReader version?"))
                            .setNegativeButton(tr("Позже", "Later"), null)
                            .setPositiveButton(tr("Подробнее", "View update"), (d, w) -> startActivity(new Intent(this, UpdateActivity.class))).show();
                });
            } catch (Exception ignored) { /* Offline reading never shows an update error. Manual checks can retry. */ }
        });
    }
    private void library() {
        automaticUpdates();
        settingsOpen = false;
        pause();
        current = null;
        reading = null;
        fullscreen = false;
        options = ReaderOptions.load(prefs, null);
        textScroll = null;
        pageReader = null;
        body = null;
        shell();
        LinearLayout mast = row();
        TextView brand = label("MJ / READER", 14, options.accent());
        brand.setLetterSpacing(.16f);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        mast.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        mast.addView(readerAction("settings", tr("Настройки", "Settings"), () -> settings(false)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(mast);
        View mastDivider = new View(this);
        mastDivider.setBackgroundColor(options.surface());
        root.addView(mastDivider, new LinearLayout.LayoutParams(-1, dp(1)));
        boolean compact = getResources().getConfiguration().screenHeightDp < 600;
        gap(root, compact ? 4 : 12);
        LinearLayout heading = row();
        heading.addView(label(getString(R.string.library), compact ? 26 : 34, options.ink()), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(heading);
        if (!compact) {
            gap(root, 4);
            root.addView(label(tr("Ваши книги. В вашем ритме.", "Your books. Your pace."), 14, options.muted()));
        }
        librarySearch = field(query);
        librarySearch.setHint(tr("Название или автор", "Title or author"));
        librarySearch.setContentDescription(tr("Поиск по библиотеке", "Search library"));
        librarySearch.setSingleLine(true);
        librarySearch.setOnClickListener(v -> {
            librarySearch.requestFocus();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(librarySearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
        librarySearch.setCompoundDrawablesRelativeWithIntrinsicBounds(new ReaderIcon(this, "search", options.muted()), null, null, null);
        librarySearch.setCompoundDrawablePadding(dp(12));
        gap(root, 16);
        root.addView(librarySearch, matchWrap());
        gap(root, 12);
        boolean narrow = getResources().getConfiguration().screenWidthDp < 400;
        LinearLayout filters = row();
        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row();
        String[] values = {"all", "reading", "favorite"};
        String[] names = {tr("Все", "All"), tr("Читаю", "Reading"), tr("Избранное", "Favorites")};
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            if (i > 0) space(chips);
            Button chip = button(names[i], filter.equals(value), () -> {
                filter = value; libraryBookIndex = -1; libraryOffset = 0; library();
            });
            chip.setTextSize(14); chip.setSelected(filter.equals(value));
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
            chips.addView(chip);
            if (chip.isSelected()) filterScroll.post(() -> filterScroll.scrollTo(chip.getLeft(), 0));
        }
        filterScroll.addView(chips);
        filters.addView(filterScroll, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout filterActions = narrow ? row() : filters;
        if (!narrow) space(filters);
        filterActions.addView(readerAction("filter", tr("Все фильтры", "All filters"), this::filterDialog), new LinearLayout.LayoutParams(dp(48), dp(48)));
        space(filterActions);
        filterActions.addView(readerAction("sort", tr("Вид и порядок", "View & sort"), this::sortDialog), new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(filters);
        if (filter.equals("all")) {
            Books.Book recent = books.stream().filter(book -> !prefs.getBoolean(key(book, "deleted"), false)
                    && prefs.getString(key(book, "status"), "want").equals("reading") && prefs.getLong(key(book, "opened"), 0) > 0)
                    .max(Comparator.comparingLong(book -> prefs.getLong(key(book, "opened"), 0))).orElse(null);
            if (recent != null) {
                LinearLayout resume = column(); resume.setTag("resume-section");
                resume.setVisibility(query.isBlank() ? View.VISIBLE : View.GONE);
                gap(resume, 16); resume.addView(continueCard(recent), matchWrap()); root.addView(resume, matchWrap());
            }
        }
        gap(root, 16);
        TextView shelf = label(filterLabel(), 18, options.ink());
        shelf.setTypeface(Typeface.DEFAULT_BOLD);
        if (narrow) {
            LinearLayout shelfRow = row();
            shelfRow.addView(shelf, weight()); space(shelfRow);
            shelfRow.addView(filterActions); root.addView(shelfRow);
        } else root.addView(shelf);
        gap(root, 4);
        libraryList = new ListView(this);
        libraryList.setDivider(null);
        libraryList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        libraryList.setCacheColorHint(Color.TRANSPARENT);
        libraryList.setItemsCanFocus(true);
        libraryList.setClipToPadding(false);
        LinearLayout libraryHeader = column();
        while (root.getChildCount() > 2) {
            View child = root.getChildAt(2);
            root.removeViewAt(2);
            libraryHeader.addView(child);
        }
        libraryList.addHeaderView(libraryHeader, null, false);
        adapter = new BookAdapter();
        libraryList.setAdapter(adapter);
        root.addView(libraryList, new LinearLayout.LayoutParams(-1, 0, 1));
        librarySearch.addTextChangedListener(watcher(() -> {
            query = librarySearch.getText().toString();
            View resume = libraryHeader.findViewWithTag("resume-section");
            if (resume != null) resume.setVisibility(query.isBlank() ? View.VISIBLE : View.GONE);
            libraryRow = 0;
            libraryBookIndex = -1; libraryOffset = 0;
            adapter.refresh();
        }));
        adapter.refresh();
        ListView list = libraryList;
        int targetRow = libraryBookIndex < 0 ? 0 : libraryBookIndex / adapter.columns() + 1;
        list.setSelectionFromTop(targetRow, libraryOffset);
        list.post(() -> list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int state) { }
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (list != libraryList || !list.isAttachedToWindow() || list.getChildCount() == 0) return;
                libraryRow = first;
                libraryBookIndex = first == 0 ? -1 : (first - 1) * adapter.columns();
                libraryOffset = list.getChildAt(0).getTop();
            }
        }));
        importStatus = null;
        if (importing) {
            LinearLayout status = row();
            status.addView(new LoadingRing(), new LinearLayout.LayoutParams(dp(32), dp(32)));
            space(status);
            importStatus = label(tr("Обрабатываем файлы…", "Processing files…"), 14, options.muted());
            status.addView(importStatus, weight());
            root.addView(status, matchWrap()); gap(root, 8);
        }
        Button add = button(importing ? tr("Импортируем…", "Importing…") : getString(R.string.add_book), true, this::pickBooks);
        add.setEnabled(!importing);
        LinearLayout dock = row();
        dock.setPadding(dp(8), dp(8), dp(8), dp(8));
        dock.setBackground(shape(options.surface(), 20));
        Button discover = button(tr("Найти книги", "Discover"), false, () -> startActivityForResult(new Intent(this, CatalogActivity.class), 8));
        boolean stackedDock = narrow && getResources().getConfiguration().fontScale >= 1.6f;
        if (narrow) for (Button action : new Button[]{add, discover}) {
            action.setTextSize(14);
            action.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
        }
        if (stackedDock) dock.setOrientation(LinearLayout.VERTICAL);
        dock.addView(add, stackedDock ? matchWrap() : weight());
        if (stackedDock) gap(dock, 8); else space(dock);
        dock.addView(discover, stackedDock ? matchWrap() : weight());
        LinearLayout.LayoutParams dockParams = matchWrap();
        dockParams.topMargin = dp(12);
        root.addView(dock, dockParams);
    }
    private View continueCard(Books.Book book) {
        LinearLayout card = row();
        card.setPadding(dp(16), dp(16), dp(12), dp(16));
        GradientDrawable background = shape(options.surface(), 20);
        card.setBackground(feedback(background));
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(shape(options.background(), 8)); image.setClipToOutline(true);
        Bitmap bitmap = cover(book);
        if (bitmap != null) image.setImageBitmap(bitmap); else image.setImageDrawable(new CoverArt(title(book), book.id().hashCode()));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(image, new LinearLayout.LayoutParams(dp(56), dp(84))); space(card);
        LinearLayout detail = column(); detail.setPadding(dp(4), 0, dp(8), 0);
        TextView caption = label(tr("ПРОДОЛЖИТЬ ЧТЕНИЕ", "CONTINUE READING"), 11, options.accent());
        caption.setLetterSpacing(.08f); detail.addView(caption); gap(detail, 6);
        TextView title = label(title(book), 18, options.ink()); title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); detail.addView(title); gap(detail, 6);
        int percent = Math.min(100, (int) (100L * prefs.getInt(key(book, "anchor"), 0) / Math.max(1, book.text().length() - 1)));
        detail.addView(label(percent + tr("% прочитано", "% read"), 13, options.muted()));
        card.addView(detail, weight());
        ImageView arrow = new ImageView(this); arrow.setImageDrawable(new ControlIcon("→", options.accent()));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(24)));
        card.setContentDescription(tr("Продолжить чтение: ", "Continue reading: ") + title(book) + ", " + percent + "%");
        card.setTag("continue-reading"); card.setFocusable(true); card.setOnClickListener(v -> open(book, false));
        return card;
    }
    private String filterLabel() {
        return switch (filter) {
            case "reading" -> tr("Читаю", "Reading");
            case "want" -> tr("Хочу прочитать", "Want to read");
            case "done" -> tr("Прочитано", "Finished");
            case "favorite" -> tr("Избранное", "Favorites");
            case "collection" -> collectionFilter;
            case "trash" -> tr("Удалённые", "Deleted");
            default -> tr("Все книги", "All books");
        };
    }
    private void filterDialog() {
        String[] names = {tr("Все книги", "All books"), tr("Читаю", "Reading"), tr("Хочу прочитать", "Want to read"),
                tr("Прочитано", "Finished"), tr("Избранное", "Favorites"), tr("Коллекции", "Collections"), tr("Удалённые", "Deleted")};
        dialogs().setTitle(tr("Показать", "Show")).setItems(names, (dialog, index) -> {
            filter = new String[]{"all", "reading", "want", "done", "favorite", "collection", "trash"}[index];
            libraryRow = 0;
            libraryBookIndex = -1; libraryOffset = 0;
            if (index == 5) collectionsDialog(); else library();
        }).show();
    }
    private void collectionsDialog() {
        TreeSet<String> names = new TreeSet<>(prefs.getStringSet("collections", Collections.emptySet()));
        for (Books.Book book : books) names.addAll(prefs.getStringSet(key(book, "collections"), Collections.emptySet()));
        List<String> items = new ArrayList<>(names);
        items.add(tr("+ Новая коллекция", "+ New collection"));
        dialogs().setTitle(tr("Коллекции", "Collections")).setItems(items.toArray(new String[0]), (d, index) -> {
            if (index < items.size() - 1) { collectionFilter = items.get(index); library(); return; }
            EditText input = field("");
            dialogs().setTitle(tr("Название коллекции", "Collection name")).setView(input)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        String value = input.getText().toString().trim();
                        if (value.isEmpty()) return;
                        names.add(value);
                        prefs.edit().putStringSet("collections", names).apply();
                        collectionFilter = value;
                        library();
                    }).show();
        }).show();
    }
    private void sortDialog() {
        settingsOpen = true;
        settingsLibrary = true;
        restoreSettings = false;
        shell();
        LinearLayout top = row();
        Button back = button("‹", false, this::back);
        back.setContentDescription(tr("Назад", "Back"));
        top.addView(back, new LinearLayout.LayoutParams(dp(48), -2));
        space(top);
        top.addView(label(tr("Вид библиотеки", "Library layout"), 24, options.ink()), weight());
        root.addView(top);
        settingsScroll = new ScrollView(this);
        LinearLayout page = column();
        settingsScroll.addView(page);
        root.addView(settingsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = settingsGroup(page, tr("Отображение", "Display"));
        toggle(content, tr("Сетка обложек", "Cover grid"), grid, checked -> {
            grid = checked; prefs.edit().putBoolean("grid", grid).apply();
        });
        choose(content, tr("Сортировка", "Sort order"), new String[]{tr("Последнее открытие", "Last opened"),
                tr("Название", "Title"), tr("Дата добавления", "Date added")}, sort, value -> {
            sort = value; prefs.edit().putInt("sort", sort).apply();
        });
    }
    private class BookAdapter extends BaseAdapter {
        final List<Books.Book> visible = new ArrayList<>();
        final int columnCount = grid ? Math.max(1, Math.min(6, (libraryWidth() + 8) /
                (getResources().getConfiguration().fontScale >= 1.6f ? 320 : 166))) : 1;
        int columns() {
            return columnCount;
        }
        void refresh() {
            visible.clear();
            String needle = query.toLowerCase(Locale.ROOT);
            for (Books.Book book : books) {
                boolean deleted = prefs.getBoolean(key(book, "deleted"), false);
                if (deleted != filter.equals("trash")) continue;
                if (!(title(book) + " " + author(book)).toLowerCase(Locale.ROOT).contains(needle)) continue;
                if (filter.equals("favorite") && !prefs.getBoolean(key(book, "favorite"), false)) continue;
                if (filter.equals("collection") && !prefs.getStringSet(key(book, "collections"), Collections.emptySet()).contains(collectionFilter)) continue;
                if (Arrays.asList("reading", "want", "done").contains(filter) && !prefs.getString(key(book, "status"), "want").equals(filter)) continue;
                visible.add(book);
            }
            if (sort == 0) visible.sort(Comparator.comparingLong((Books.Book b) -> prefs.getLong(key(b, "opened"), 0)).reversed());
            if (sort == 1) visible.sort(Comparator.comparing(MainActivity.this::title, String.CASE_INSENSITIVE_ORDER));
            notifyDataSetChanged();
        }
        @Override public int getCount() { return Math.max(1, (visible.size() + columns() - 1) / columns()); }
        @Override public Object getItem(int index) { return index; }
        @Override public long getItemId(int index) { return index; }
        @Override public View getView(int index, View recycled, android.view.ViewGroup parent) {
            if (visible.isEmpty()) {
                LinearLayout empty = column(); empty.setPadding(dp(16), dp(28), dp(16), dp(28));
                TextView heading = label(query.isBlank() ? tr("Полка пока пуста", "This shelf is empty") : tr("Ничего не найдено", "No matching books"), 22, options.ink());
                heading.setTypeface(Typeface.DEFAULT_BOLD); empty.addView(heading); gap(empty, 8);
                empty.addView(label(query.isBlank() ? tr("Добавьте книгу или выберите другую полку.", "Add a book or choose another shelf.")
                        : tr("Попробуйте другое название или имя автора.", "Try another title or author."), 15, options.muted()));
                gap(empty, 20);
                empty.addView(button(query.isBlank() ? tr("Все книги", "All books") : tr("Сбросить поиск", "Clear search"), false, () -> {
                    if (!query.isBlank()) librarySearch.setText("");
                    else { filter = "all"; libraryBookIndex = -1; libraryOffset = 0; library(); }
                }), matchWrap());
                return empty;
            }
            LinearLayout line = row();
            line.setGravity(Gravity.TOP);
            line.setPadding(0, dp(8), 0, dp(8));
            for (int i = 0; i < columns(); i++) {
                int item = index * columns() + i;
                if (i > 0) space(line);
                if (item < visible.size()) line.addView(bookCard(visible.get(item), columns() > 1), weight());
                else line.addView(new View(MainActivity.this), weight());
            }
            return line;
        }
    }
    private int libraryWidth() {
        int width = getResources().getConfiguration().screenWidthDp;
        return Math.min(1200, width - (width >= 600 ? 48 : 32));
    }
    private View bookCard(Books.Book book, boolean tile) {
        boolean deleted = prefs.getBoolean(key(book, "deleted"), false);
        LinearLayout card = column();
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(feedback(shape(options.surface(), 20)));
        card.setFocusable(true);
        card.setOnLongClickListener(view -> { bookMenu(book); return true; });
        card.setOnClickListener(v -> {
            if (deleted) return;
            libraryRow = libraryList.getFirstVisiblePosition();
            open(book, false);
        });
        LinearLayout info = tile ? column() : row();
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setBackground(shape(options.background(), 10));
        cover.setClipToOutline(true);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Bitmap bitmap = cover(book);
        if (bitmap != null) cover.setImageBitmap(bitmap); else cover.setImageDrawable(new CoverArt(title(book), book.id().hashCode()));
        int available = libraryList.getWidth() > 0 ? libraryList.getWidth() : dp(libraryWidth());
        int columns = adapter.columns();
        int coverHeight = tile ? Math.round(((available - dp(8) * (columns - 1)) / (float) columns - dp(24)) * 1.5f) : dp(104);
        info.addView(cover, new LinearLayout.LayoutParams(tile ? -1 : dp(70), coverHeight));
        LinearLayout detail = column();
        detail.setPadding(tile ? 0 : dp(12), tile ? dp(12) : 0, 0, 0);
        TextView titleView = label(title(book), tile ? 16 : 18, options.ink());
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (tile) { titleView.setLines(2); titleView.setEllipsize(TextUtils.TruncateAt.END); }
        detail.addView(titleView);
        gap(detail, 4);
        TextView authorView = label(author(book), 13, options.muted());
        if (tile) { authorView.setSingleLine(true); authorView.setEllipsize(TextUtils.TruncateAt.END); }
        detail.addView(authorView);
        gap(detail, 4);
        int percent = (int) (100L * prefs.getInt(key(book, "anchor"), 0) / Math.max(1, book.text().length() - 1));
        String state = prefs.getString(key(book, "status"), "want");
        String status = state.equals("reading") ? tr("Читаю", "Reading") : state.equals("done") ? tr("Прочитано", "Finished") : tr("В планах", "To read");
        if (state.equals("done")) percent = 100;
        String progressText = state.equals("want") ? status : status + " · " + percent + "%";
        if (prefs.getBoolean(key(book, "favorite"), false)) progressText += tr(" · Избранное", " · Favorite");
        LinearLayout meta = row();
        TextView progressLabel = label(progressText, 12, options.muted());
        meta.addView(progressLabel, weight());
        Button more = button("⋮", false, () -> bookMenu(book));
        more.setContentDescription(tr("Действия с книгой ", "Book actions for ") + title(book));
        meta.addView(more, new LinearLayout.LayoutParams(dp(48), -2));
        detail.addView(meta);
        info.addView(detail, tile ? matchWrap() : new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(info);
        card.setContentDescription(title(book) + (author(book).isEmpty() ? "" : ", " + author(book)) + ", " + progressText);
        card.setTooltipText(title(book) + (author(book).isEmpty() ? "" : "\n" + author(book)));
        for (TextView label : new TextView[]{titleView, authorView, progressLabel})
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (state.equals("reading")) {
            ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setProgress(percent);
            progress.setProgressTintList(ColorStateList.valueOf(options.accent()));
            progress.setProgressBackgroundTintList(ColorStateList.valueOf(options.background()));
            progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        }
        if (deleted) {
            card.addView(button(tr("Вернуть", "Restore"), false, () -> {
                prefs.edit().remove(key(book, "deleted")).apply(); adapter.refresh();
            }), matchWrap());
        }
        return card;
    }
    private Bitmap cover(Books.Book book) {
        if (book.cover() == null) return null;
        if (covers.containsKey(book.id())) return covers.get(book.id());
        BitmapFactory.Options sizing = new BitmapFactory.Options();
        sizing.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(book.cover(), 0, book.cover().length, sizing);
        sizing.inSampleSize = Math.max(1, Math.max(sizing.outWidth, sizing.outHeight) / 320);
        sizing.inJustDecodeBounds = false;
        Bitmap result = BitmapFactory.decodeByteArray(book.cover(), 0, book.cover().length, sizing);
        covers.put(book.id(), result);
        if (covers.size() > 24) covers.remove(covers.keySet().iterator().next());
        return result;
    }
    private void bookMenu(Books.Book book) {
        List<String> actions = new ArrayList<>(Arrays.asList(tr("Название и автор", "Title & author"), tr("Статус чтения", "Reading status"),
                tr("Избранное", "Favorite"), tr("Коллекции", "Collections"), tr("Сбросить прогресс", "Reset progress"), tr("Удалить", "Delete")));
        if (!book.id().startsWith("demo-")) actions.add(tr("Повторный импорт", "Reimport"));
        dialogs().setTitle(title(book)).setItems(actions.toArray(new String[0]), (dialog, index) -> {
            switch (index) {
                case 6 -> pickBooks(book.id());
                case 0 -> editBook(book);
                case 1 -> dialogs().setTitle(actions.get(1)).setItems(
                        new String[]{tr("Хочу прочитать", "Want to read"), tr("Читаю", "Reading"), tr("Прочитано", "Finished")},
                        (d, value) -> { prefs.edit().putString(key(book, "status"), new String[]{"want", "reading", "done"}[value]).apply(); adapter.refresh(); }).show();
                case 2 -> { prefs.edit().putBoolean(key(book, "favorite"), !prefs.getBoolean(key(book, "favorite"), false)).apply(); adapter.refresh(); }
                case 3 -> assignCollections(book);
                case 4 -> dialogs().setTitle(actions.get(4)).setMessage(title(book)).setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (d, value) -> {
                            prefs.edit().putInt(book.id(), 0).putInt(key(book, "anchor"), 0).putString(key(book, "status"), "want").apply(); adapter.refresh();
                        }).show();
                case 5 -> dialogs().setTitle(tr("Удалить книгу?", "Delete book?")).setMessage(title(book))
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(tr("Удалить", "Delete"), (d, value) -> {
                            prefs.edit().putBoolean(key(book, "deleted"), true).apply(); adapter.refresh();
                            dialogs().setMessage(tr("Книга удалена. Её можно вернуть из фильтра «Удалённые».", "Book deleted. You can restore it from the Deleted filter."))
                                    .setNegativeButton(tr("Отменить удаление", "Undo delete"), (undo, which) -> {
                                        prefs.edit().remove(key(book, "deleted")).apply(); adapter.refresh();
                                    }).setPositiveButton(android.R.string.ok, null).show();
                        }).show();
            }
        }).show();
    }
    private void editBook(Books.Book book) {
        LinearLayout content = column();
        EditText title = labeledField(content, tr("Название", "Title"), title(book));
        EditText author = labeledField(content, tr("Автор", "Author"), author(book));
        AlertDialog dialog = dialogs().setTitle(tr("О книге", "Book details")).setView(content)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(tr("Сохранить", "Save"), null).create();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(view -> {
            if (title.getText().toString().trim().isEmpty()) { title.setError(tr("Введите название", "Enter a title")); return; }
            prefs.edit().putString(key(book, "title"), title.getText().toString().trim())
                    .putString(key(book, "author"), author.getText().toString().trim()).apply();
            adapter.refresh(); dialog.dismiss();
        }));
        dialog.show();
    }
    private void assignCollections(Books.Book book) {
        Set<String> selected = new TreeSet<>(prefs.getStringSet(key(book, "collections"), Collections.emptySet()));
        TreeSet<String> all = new TreeSet<>(prefs.getStringSet("collections", Collections.emptySet()));
        all.addAll(selected);
        String[] names = all.toArray(new String[0]);
        boolean[] checked = new boolean[names.length];
        for (int i = 0; i < names.length; i++) checked[i] = selected.contains(names[i]);
        dialogs().setTitle(tr("Коллекции", "Collections")).setMultiChoiceItems(names, checked, (d, i, enabled) -> {
            if (enabled) selected.add(names[i]); else selected.remove(names[i]);
        }).setNeutralButton(tr("Новая", "New"), (d, which) -> {
            LinearLayout fields = column();
            EditText name = labeledField(fields, tr("Название", "Name"), "");
            dialogs().setTitle(tr("Новая коллекция", "New collection")).setView(fields)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(tr("Создать", "Create"), (dialog, value) -> {
                        String input = name.getText().toString().trim();
                        if (input.isEmpty()) return;
                        all.add(input); selected.add(input);
                        prefs.edit().putStringSet("collections", all).putStringSet(key(book, "collections"), selected).apply();
                        adapter.refresh();
                    }).show();
        }).setNegativeButton(android.R.string.cancel, null).setPositiveButton(tr("Сохранить", "Save"), (d, value) -> {
            prefs.edit().putStringSet(key(book, "collections"), selected).apply(); adapter.refresh();
        }).show();
    }
    private void pickBooks() { pickBooks(null); }
    private void pickBooks(String bookId) {
        if (importing) return;
        reimportId = bookId;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/epub+zip", "text/plain", "application/octet-stream",
                "application/x-fictionbook+xml", "application/x-fictionbook", "application/fb2+xml",
                "application/fb2+zip", "application/zip", "application/x-zip-compressed", "application/xml", "text/xml"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, bookId == null);
        startActivityForResult(intent, bookId == null ? 1 : 2);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 8) {
            worker.execute(() -> {
                try {
                    List<Books.Book> loaded = store.list();
                    runOnUiThread(() -> { if (!isDestroyed()) { books = loaded; library(); } });
                } catch (Exception error) { runOnUiThread(() -> message(tr("Ошибка загрузки", "Loading failed"), error.getMessage())); }
            });
            return;
        }
        if (request != 1 && request != 2) return;
        String repairId = request == 2 ? reimportId : null;
        if (request == 2) reimportId = null;
        if (result != RESULT_OK || data == null) return;
        if (request == 2 && repairId == null) {
            message(tr("Повторный импорт", "Reimport"), tr("Выберите повторный импорт в меню книги ещё раз.", "Choose Reimport from the book menu again."));
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        if (repairId != null && uris.size() != 1) {
            message(tr("Повторный импорт", "Reimport"), tr("Выберите один исходный файл этой книги.", "Choose one original file for this book."));
            return;
        }
        importUris(uris, repairId, false);
    }
    private void importUris(List<Uri> uris, String repairId, boolean openExisting) {
        importing = true;
        batchRunning = true;
        library();
        Set<String> knownIds = new HashSet<>();
        for (Books.Book book : books) knownIds.add(book.id());
        worker.execute(() -> {
            StringBuilder report = new StringBuilder();
            List<Books.Book> imported = new ArrayList<>();
            int index = 0;
            for (Uri uri : uris) {
                int number = ++index;
                runOnUiThread(() -> {
                    MainActivity target = active.get();
                    if (target != null && target.importStatus != null)
                        target.importStatus.setText(target.tr("Импорт ", "Import ") + number + target.tr(" из ", " of ") + uris.size());
                });
                try {
                    Books.Book book = store.importBook(uri, repairId);
                    imported.add(book);
                    boolean added = knownIds.add(book.id());
                    report.append(title(book)).append(" — ").append(added ? tr("добавлена", "added")
                            : tr("обложка, оглавление и иллюстрации обновлены", "cover, contents and illustrations updated")).append("\n");
                } catch (Exception e) {
                    if (openExisting && e instanceof Books.DuplicateBook duplicate) {
                        try {
                            Books.Book existing = store.list().stream().filter(book -> book.id().equals(duplicate.id)).findFirst().orElse(null);
                            if (existing != null) { imported.add(existing); continue; }
                        } catch (Exception ignored) { }
                    }
                    String name = uri.getLastPathSegment();
                    try (android.database.Cursor cursor = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                    } catch (Exception ignored) {}
                    report.append(name).append(" — ").append(e instanceof Books.DifferentBook
                            ? tr("это другой файл. Выберите исходный файл этой книги; библиотека не изменена.", "this is a different book. Choose its original file; the library was not changed.")
                            : e instanceof Books.DuplicateBook
                            ? (repairId != null ? tr("книга уже обновлена, изменений нет", "book is already up to date")
                                    : tr("уже в библиотеке (проверьте также «Удалённые»)", "already in library (also check Deleted)"))
                            : tr("не удалось прочитать. Выберите TXT, FB2 или EPUB без DRM. ", "could not read. Choose TXT, FB2 or DRM-free EPUB. ") + e.getMessage()).append("\n");
                }
            }
            List<Books.Book> refreshed;
            try { refreshed = store.list(); }
            catch (Exception e) { refreshed = null; }
            List<Books.Book> loaded = refreshed;
            runOnUiThread(() -> {
                batchRunning = false;
                MainActivity target = active.get();
                if (target == null || target.isDestroyed()) return;
                target.importing = false;
                if (loaded != null) target.books = loaded;
                target.covers.clear();
                target.filter = "all"; target.query = "";
                target.library();
                if (uris.size() == 1 && imported.size() == 1) target.open(imported.get(0), false);
                else target.message(target.tr("Результат импорта", "Import results"), report.toString());
                target.openPendingFile();
            });
        });
    }
    private void open(Books.Book book, boolean mode) {
        pause();
        current = book;
        reading = null;
        options = ReaderOptions.load(prefs, book.id());
        // Build token offsets away from the UI thread, including older imported books.
        shell();
        loadingScreen(title(book), getString(R.string.importing));
        worker.execute(() -> {
            ReadingText loaded = new ReadingText(book.text());
            runOnUiThread(() -> {
                if (isDestroyed() || current != book) return;
                reading = loaded;
                if (reading.words.isEmpty()) { library(); return; }
                int legacy = Math.min(reading.words.size() - 1, Math.max(0, prefs.getInt(book.id(), 0)));
                anchor = Math.min(book.text().length() - 1, Math.max(0, prefs.getInt(key(book, "anchor"), reading.offsets.get(legacy))));
                position = reading.wordAt(anchor);
                focus = mode;
                returnAnchor = prefs.getInt(key(book, "return"), -1);
                speed = options.number("speed", prefs.getInt("speed", 300));
                prefs.edit().putLong(key(book, "opened"), System.currentTimeMillis())
                        .putString(key(book, "status"), prefs.getString(key(book, "status"), "want").equals("done") ? "done" : "reading").apply();
                reader();
                if (restoreSettings) { if (settingsLibrary) sortDialog(); else settings(settingsRsvp); }
            });
        });
    }
    private void reader() {
        pause();
        focusView = null;
        play = null;
        textScroll = null;
        pageReader = null;
        body = null;
        shell();
        header = column();
        LinearLayout top = row();
        Button back = button("‹", false, this::library);
        back.setContentDescription(getString(R.string.back));
        top.addView(back, new LinearLayout.LayoutParams(dp(48), -2));
        space(top);
        LinearLayout identity = column();
        TextView title = label(title(current), 14, options.muted());
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(title);
        chapterLabel = label("", 14, options.ink());
        chapterLabel.setMaxLines(1); chapterLabel.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(chapterLabel);
        identity.setMinimumHeight(dp(48)); identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.setBackground(feedback(shape(options.background(), 12)));
        identity.setFocusable(true); identity.setOnClickListener(v -> contents());
        identity.setContentDescription(tr("Оглавление: ", "Contents: ") + title(current));
        top.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        space(top);
        top.addView(readerAction("type", tr("Быстрое оформление", "Quick appearance"), this::quickAppearance), new LinearLayout.LayoutParams(dp(48), -2));
        space(top);
        Button menu = button("⋮", false, this::readerMenu);
        menu.setContentDescription(tr("Меню чтения", "Reading menu"));
        top.addView(menu, new LinearLayout.LayoutParams(dp(48), -2));
        header.addView(top);
        gap(header, 8);
        LinearLayout modes = row();
        Button text = button(getString(R.string.reader), !focus, () -> { focus = false; reader(); });
        text.setSelected(!focus);
        modes.addView(text, weight());
        space(modes);
        Button rsvp = button("RSVP", focus, () -> { focus = true; reader(); });
        rsvp.setSelected(focus);
        modes.addView(rsvp, weight());
        if (focus && getResources().getConfiguration().screenHeightDp >= 400) header.addView(modes);
        gap(header, 8);
        chapterLabel.setText(current.chapters().get(chapterIndex()).title());
        root.addView(header);
        if (focus) focusScreen(); else textScreen();
        setFullscreen(fullscreen);
    }
    // LineBreaker constants are inlined; the TextView setters exist since API 26.
    @android.annotation.SuppressLint({"NewApi", "ClickableViewAccessibility"})
    private void textScreen() {
        if (options.flag("pages", false)) {
            int version = renderVersion;
            // Snapshot notes before the layout worker uses them.
            JSONArray notes = bookmarkData();
            Books.Book book = current;
            ReaderOptions appearance = new ReaderOptions(options.values.toString());
            pageReader = new ReaderPages(this, book, appearance, anchor, worker,
                    (from, to) -> styledText(book, appearance, from, to, notes), offset -> {
                        if (version != renderVersion) return;
                        anchor = offset; position = reading.wordAt(anchor); updateTextProgress(); save();
                    }, this::paragraphActions, this::showNote, () -> setFullscreen(!fullscreen));
            root.addView(pageReader, new LinearLayout.LayoutParams(-1, 0, 1));
        } else scrollScreen();
        readingFooter();
    }
    @android.annotation.SuppressLint({"NewApi", "ClickableViewAccessibility"})
    private void scrollScreen() {
        textScroll = new ScrollView(this);
        body = label("", options.number("size", prefs.getInt("typeSize", 21)), options.ink());
        String[] fonts = {"serif", "sans-serif", "monospace"};
        body.setTypeface(Typeface.create(fonts[options.number("font", 0)], Typeface.NORMAL));
        body.setLineSpacing(0, options.number("line", 150) / 100f);
        body.setGravity(options.number("alignment", 0) == 2 ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        body.setJustificationMode(options.number("alignment", 0) == 1 ? android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD : android.graphics.text.LineBreaker.JUSTIFICATION_MODE_NONE);
        body.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY);
        body.setHyphenationFrequency(options.flag("hyphenation", true) ? android.text.Layout.HYPHENATION_FREQUENCY_FULL : android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        body.setTextLocales(new android.os.LocaleList(ReaderText.language(current.text())));
        body.setIncludeFontPadding(false);
        int margin = dp(options.number("margin", 8));
        body.setPadding(margin, dp(12), margin, dp(24));
        int[] window = ReaderText.window(current.text(), anchor);
        textStart = window[0]; textEnd = window[1];
        applyReadingText();
        textScroll.addView(body);
        root.addView(textScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body.setOnClickListener(v -> setFullscreen(!fullscreen));
        body.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                ReaderText.noteActions(current, info, textStart, textEnd);
            }
            @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                int index = action - ReaderText.NOTE_ACTION;
                if (index >= 0 && index < current.notes().size()) return showNote(current.notes().get(index).start());
                return super.performAccessibilityAction(host, action, args);
            }
        });
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(android.view.MotionEvent event) {
                int offset = ReaderText.noteHit(current, body.getLayout(), textStart, textStart, textEnd,
                        event.getX() - body.getPaddingLeft(), event.getY() - body.getPaddingTop(), getResources().getDisplayMetrics().density);
                if (offset >= 0 && showNote(offset)) return true;
                body.performClick();
                return true;
            }
            @Override public void onLongPress(android.view.MotionEvent event) {
                paragraphActions(textStart + body.getOffsetForPosition(event.getX(), event.getY()));
            }
        });
        // Taps toggle controls; vertical gestures remain native scrolling.
        body.setOnTouchListener((view, event) -> gestures.onTouchEvent(event));
        body.setOnLongClickListener(view -> { paragraphActions(anchor); return true; });
        int version = renderVersion;
        restoring = true;
        textScroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (restoring || version != renderVersion || body.getLayout() == null || y == oldY) return;
            int line = body.getLayout().getLineForVertical(Math.max(0, y - body.getPaddingTop()));
            anchor = textStart + body.getLayout().getLineStart(line);
            position = reading.wordAt(anchor);
            updateTextProgress();
            save();
            loadVisiblePictures();
            int edge = Math.max(dp(200), textScroll.getHeight() * 2);
            if ((textStart > 0 && y < edge) || (textEnd < current.text().length() && body.getHeight() - y - textScroll.getHeight() < edge)) {
                int[] next = ReaderText.window(current.text(), anchor);
                if (next[0] != textStart || next[1] != textEnd) {
                    restoring = true;
                    textScroll.fling(0);
                    textScroll.post(() -> {
                        if (version != renderVersion) return;
                        textStart = next[0]; textEnd = next[1];
                        applyReadingText();
                    });
                }
            }
        });
    }
    private void readingFooter() {
        footer = column();
        footer.setPadding(dp(8), dp(8), dp(8), dp(8));
        footer.setBackground(shape(options.surface(), 20));
        LinearLayout navigation = row();
        if (options.flag("pages", false)) {
            Button previous = button("←", false, () -> turnPage(-1));
            previous.setContentDescription(getString(R.string.previous_page));
            navigation.addView(previous, new LinearLayout.LayoutParams(dp(48), -2));
            space(navigation);
        }
        counter = label("", 13, options.muted());
        counter.setGravity(Gravity.CENTER);
        counter.setMinHeight(dp(48));
        counter.setContentDescription(tr("Прогресс и переход по книге", "Reading progress and navigation"));
        counter.setOnClickListener(v -> readingNavigation());
        counter.setFocusable(true);
        counter.setBackground(feedback(shape(options.surface(), 14)));
        navigation.addView(counter, new LinearLayout.LayoutParams(0, -2, 1));
        if (options.flag("pages", false)) {
            space(navigation);
            Button next = button("→", false, () -> turnPage(1));
            next.setContentDescription(getString(R.string.next_page));
            navigation.addView(next, new LinearLayout.LayoutParams(dp(48), -2));
        }
        footer.addView(navigation);
        if (getResources().getConfiguration().screenHeightDp >= 400) {
            LinearLayout tools = row();
            tools.addView(readerAction("list", tr("Оглавление", "Contents"), this::contents), weight());
            space(tools);
            tools.addView(readerAction("bookmark", tr("Добавить закладку", "Add bookmark"), this::addBookmark), weight());
            space(tools);
            tools.addView(readerAction("sun", tr("День / ночь", "Day / night"), () -> {
                options.put("theme", options.light() ? 0 : 2); changedOptions();
            }), weight());
            Button rsvp = readerAction("speed", "RSVP", () -> { focus = true; reader(); });
            space(tools);
            tools.addView(rsvp, weight());
            gap(footer, 8);
            footer.addView(tools);
        }
        LinearLayout.LayoutParams dock = new LinearLayout.LayoutParams(-1, -2);
        dock.topMargin = dp(8);
        root.addView(footer, dock);
        updateTextProgress();
    }
    private Button readerAction(String icon, String description, Runnable action) {
        Button button = button("", false, action);
        button.setContentDescription(description);
        button.setTooltipText(description);
        LayerDrawable background = new LayerDrawable(new Drawable[]{feedback(shape(icon.equals("settings") ? options.background() : options.surface(), 14)), new ReaderIcon(this, icon, options.ink())});
        background.setLayerSize(1, dp(24), dp(24));
        background.setLayerGravity(1, Gravity.CENTER);
        button.setBackground(background);
        return button;
    }
    private void applyReadingText() {
        if (body == null || current == null || focus) return;
        int version = renderVersion, from = textStart, to = textEnd;
        Books.Book book = current;
        ReaderOptions appearance = options;
        SpannableString styled = styledText(book, appearance, from, to, bookmarkData());
        int width = Math.max(dp(48), getResources().getDisplayMetrics().widthPixels - dp(64));
        int height = Math.max(dp(48), getResources().getDisplayMetrics().heightPixels / 2);
        worker.execute(() -> {
            ReaderText.illustrate(book, styled, from, width, height);
            runOnUiThread(() -> {
                if (version != renderVersion || from != textStart || to != textEnd || body == null) return;
                restoring = true;
                body.setText(styled);
                restoreAfterLayout();
            });
        });
    }
    private SpannableString styledText(Books.Book book, ReaderOptions appearance, int from, int to, JSONArray notes) {
        SpannableString styled = ReaderText.style(this, book, appearance, from, to);
        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note == null) continue;
            int originalStart = note.optInt("offset");
            int originalEnd = note.optInt("end", originalStart);
            int start = Math.max(originalStart, from) - from;
            int end = Math.min(originalEnd, to) - from;
            if (start >= 0 && end > start && end <= styled.length()) {
                int accent = appearance.accent();
                styled.setSpan(new android.text.style.BackgroundColorSpan(Color.argb(45, Color.red(accent), Color.green(accent), Color.blue(accent))),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return styled;
    }
    private void loadVisiblePictures() {
        if (body == null || textScroll == null || body.getLayout() == null || !(body.getText() instanceof Spanned text)) return;
        int version = renderVersion, request = ++pictureRequest;
        android.text.Layout layout = body.getLayout();
        int y = Math.max(0, textScroll.getScrollY() - body.getPaddingTop());
        int from = layout.getLineStart(layout.getLineForVertical(y));
        int to = layout.getLineEnd(layout.getLineForVertical(y + textScroll.getHeight()));
        worker.execute(() -> {
            ReaderText.loadPictures(text, from, to);
            runOnUiThread(() -> { if (version == renderVersion && request == pictureRequest) body.invalidate(); });
        });
    }
    private boolean showNote(int offset) {
        if (current == null || offset < 0) return false;
        for (Books.Note note : current.notes()) if (offset >= note.start() && offset < note.end()) {
            if (notePopup != null) notePopup.dismiss();
            int[] sentence = ReadingText.sentence(current.text(), note.start(), note.end());
            Path selection = new Path(); View source = pageReader != null ? pageReader : body;
            if (pageReader != null) selection = pageReader.selection(sentence[0], sentence[1]);
            else if (body != null && body.getLayout() != null) {
                int from = Math.max(sentence[0], textStart) - textStart, to = Math.min(sentence[1], textEnd) - textStart;
                if (to > from) body.getLayout().getSelectionPath(from, to, selection);
                selection.offset(body.getPaddingLeft(), body.getPaddingTop());
            }
            int[] rootPosition = new int[2], sourcePosition = new int[2]; root.getLocationOnScreen(rootPosition);
            if (source != null) source.getLocationOnScreen(sourcePosition);
            selection.offset(sourcePosition[0] - rootPosition[0], sourcePosition[1] - rootPosition[1]);
            Rect visible = new Rect();
            if (source != null && source.getGlobalVisibleRect(visible)) {
                Path clip = new Path(); clip.addRect(visible.left - rootPosition[0], visible.top - rootPosition[1],
                        visible.right - rootPosition[0], visible.bottom - rootPosition[1], Path.Direction.CW);
                selection.op(clip, Path.Op.INTERSECT);
            } else selection.reset();
            ReaderSpotlight backdrop = new ReaderSpotlight(this, root, selection, options);
            backdrop.setContentDescription(current.text().substring(sentence[0], sentence[1]));
            RectF location = new RectF(); selection.computeBounds(location, true); location.offset(rootPosition[0], rootPosition[1]);
            notePopup = dialogs().setTitle(tr("Сноска ", "Note ") + current.text().substring(note.start(), note.end()))
                    .setMessage(note.text()).setPositiveButton(android.R.string.ok, null).setBackdrop(backdrop, location).create();
            notePopup.setOnDismissListener(d -> notePopup = null);
            notePopup.show();
            return true;
        }
        return false;
    }
    private void paragraphActions(int offset) {
        String text = current.text();
        offset = Math.max(0, Math.min(text.length() - 1, offset));
        while (offset < text.length() - 1 && Character.isWhitespace(text.charAt(offset))) offset++;
        int start = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int next = text.indexOf('\n', offset);
        int end = next < 0 ? text.length() : next;
        if (end <= start) return;
        String quote = text.substring(start, end);
        dialogs().setTitle(tr("Цитата из абзаца", "Paragraph quote"))
                .setMessage(quote.length() > 600 ? quote.substring(0, 600) + "…" : quote)
                .setPositiveButton(tr("Сохранить цитату", "Save quote"), (d, which) -> addBookmark(start, end))
                .setNeutralButton(tr("Копировать", "Copy"), (d, which) -> {
                    ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(title(current), quote));
                    Toast.makeText(this, tr("Абзац скопирован", "Paragraph copied"), Toast.LENGTH_SHORT).show();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void textSizeControl(LinearLayout parent) {
        boolean shortWindow = getResources().getConfiguration().screenHeightDp < 500;
        boolean sideBySide = shortWindow && getResources().getConfiguration().screenWidthDp >= 600;
        LinearLayout panel = sideBySide ? row() : column();
        String excerpt = tr("Время для новой истории. Откройте книгу и продолжайте читать в своём ритме.",
                "Time for a new story. Open a book and keep reading at your own pace.");
        if (current != null) {
            int start = Math.max(0, Math.min(anchor, current.text().length() - 160));
            String sample = current.text().substring(start, Math.min(current.text().length(), start + 320))
                    .replace("\uFFFC", "").replaceAll("\\s+", " ").trim();
            if (!sample.isBlank()) excerpt = sample;
        }
        int size = options.number("size", prefs.getInt("typeSize", 21));
        TextView preview = label(excerpt, size, options.ink());
        preview.setTag("text-size-preview");
        preview.setTypeface(Typeface.create(new String[]{"serif", "sans-serif", "monospace"}[options.number("font", 0)], Typeface.NORMAL));
        preview.setLineSpacing(0, options.number("line", 150) / 100f);
        preview.setMaxLines(shortWindow || getResources().getConfiguration().fontScale >= 1.6f ? 1 : 2);
        preview.setEllipsize(TextUtils.TruncateAt.END); preview.setGravity(Gravity.CENTER_VERTICAL);
        preview.setPadding(dp(12), dp(8), dp(12), dp(8));
        preview.setBackground(shape(options.background(), 14));
        panel.addView(preview, sideBySide ? new LinearLayout.LayoutParams(0, dp(96), 1) : new LinearLayout.LayoutParams(-1, dp(shortWindow ? 96 : 120)));
        if (sideBySide) space(panel); else gap(panel, 12);
        LinearLayout controls = column();
        range(controls, tr("Размер текста", "Text size"), 10, 36, size, value -> {
            options.put("size", value);
            if (settingsOpen) persistOptions(); else changedOptions();
        }, value -> preview.setTextSize(value));
        panel.addView(controls, sideBySide ? weight() : matchWrap());
        parent.addView(panel, matchWrap());
    }
    private AlertDialog quickAppearance() {
        LinearLayout content = column();
        textSizeControl(content);
        ReaderDialogs sheet = dialogs().setView(content).setNegativeButton(tr("Готово", "Done"), null);
        if (getResources().getConfiguration().screenHeightDp >= 500)
            sheet.setPositiveButton(tr("Все настройки", "All settings"), (d, which) -> settings(false));
        AlertDialog panel = sheet.show();
        if (panel.getWindow() != null) {
            panel.getWindow().setGravity(Gravity.BOTTOM);
            panel.getWindow().setDimAmount(0);
            panel.getWindow().getDecorView().setSystemUiVisibility(options.light()
                    ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        }
        return panel;
    }
    private void readingNavigation() {
        int chapter = chapterIndex();
        LinearLayout content = column();
        content.addView(label(current.chapters().get(chapter).title(), 18, options.ink()));
        gap(content, 12);
        int[] percent = {(int) (100L * anchor / Math.max(1, current.text().length() - 1))};
        range(content, tr("Позиция, %", "Position, %"), 0, 100, percent[0], value -> percent[0] = value);
        AlertDialog[] panel = {null};
        LinearLayout chapters = row();
        Button previous = button(tr("Предыдущая глава", "Previous chapter"), false, () -> {
            panel[0].dismiss(); jump(current.chapters().get(Math.max(0, chapter - 1)).offset());
        });
        previous.setEnabled(chapter > 0);
        chapters.addView(previous, weight()); space(chapters);
        Button next = button(tr("Следующая глава", "Next chapter"), false, () -> {
            panel[0].dismiss(); jump(current.chapters().get(Math.min(current.chapters().size() - 1, chapter + 1)).offset());
        });
        next.setEnabled(chapter < current.chapters().size() - 1);
        chapters.addView(next, weight()); content.addView(chapters);
        if (returnAnchor >= 0) {
            gap(content, 8);
            content.addView(button(tr("Вернуться к месту", "Return to previous place"), false, () -> {
                panel[0].dismiss(); jump(returnAnchor);
            }), matchWrap());
        }
        content.addView(label(tr("Оценка времени — при темпе 250 слов/мин.", "Time estimate assumes 250 words/min."), 12, options.muted()));
        panel[0] = dialogs().setTitle(tr("Навигация", "Navigation")).setView(content)
                .setPositiveButton(tr("Перейти", "Go to"), (d, which) -> jump((int) ((current.text().length() - 1L) * percent[0] / 100)))
                .setNeutralButton(tr("Оглавление", "Contents"), (d, which) -> contents())
                .setNegativeButton(android.R.string.cancel, null).show();
    }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (current != null && !settingsOpen && !(getCurrentFocus() instanceof EditText)
                && event.hasNoModifiers() && (event.getKeyCode() == KeyEvent.KEYCODE_PAGE_DOWN || event.getKeyCode() == KeyEvent.KEYCODE_PAGE_UP)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int direction = event.getKeyCode() == KeyEvent.KEYCODE_PAGE_DOWN ? 1 : -1;
                if (focus) seekSentence(direction); else if (textScroll != null || pageReader != null) turnPage(direction);
            }
            return true;
        }
        if (current != null && !focus && !settingsOpen && (textScroll != null || pageReader != null) && options.flag("volumePages", false)
                && (event.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || event.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                turnPage(event.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ? 1 : -1);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private void restoreAfterLayout() {
        TextView text = body;
        int version = renderVersion, request = ++restoreRequest;
        restoring = true;
        // Async image sizing must finish layout before ScrollView restores/clamps its position.
        text.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (version != renderVersion || request != restoreRequest) {
                    text.getViewTreeObserver().removeOnPreDrawListener(this); return true;
                }
                if (text.getLayout() == null || text.length() == 0) return true;
                text.getViewTreeObserver().removeOnPreDrawListener(this);
                restoreAnchor();
                loadVisiblePictures();
                text.postOnAnimation(() -> {
                    if (version == renderVersion && request == restoreRequest) restoring = false;
                });
                return true;
            }
        });
        text.requestLayout();
    }
    private void restoreAnchor() {
        if (body == null || body.getLayout() == null || textScroll == null) return;
        int line = body.getLayout().getLineForOffset(Math.max(0, Math.min(anchor - textStart, body.length())));
        textScroll.scrollTo(0, body.getLayout().getLineTop(line) + body.getPaddingTop());
    }
    private void turnPage(int direction) {
        if (pageReader != null) { pageReader.turn(direction); return; }
        if (body == null || body.getLayout() == null) return;
        int height = Math.max(dp(48), textScroll.getHeight() - dp(24));
        int y = Math.max(0, textScroll.getScrollY() + direction * height - body.getPaddingTop());
        int line = body.getLayout().getLineForVertical(y);
        textScroll.scrollTo(0, body.getLayout().getLineTop(line) + body.getPaddingTop());
    }
    private int chapterIndex() {
        int index = 0;
        for (int i = 0; i < current.chapters().size(); i++) {
            if (current.chapters().get(i).offset() <= anchor) index = i;
        }
        return index;
    }
    private void updateTextProgress() {
        if (counter == null || current == null) return;
        int percent = (int) (100L * anchor / Math.max(1, current.text().length() - 1));
        int chapter = chapterIndex();
        int end = chapter + 1 < current.chapters().size() ? current.chapters().get(chapter + 1).offset() : current.text().length() - 1;
        int minutes = Math.max(1, (int) Math.ceil((reading.wordAt(end) - position) / 250.0));
        counter.setText(percent + "%  ·  " + tr("≈ " + minutes + " мин до конца главы", "≈ " + minutes + " min left in chapter"));
        counter.setContentDescription(counter.getText() + ". " + tr("Навигация", "Navigation"));
        if (chapterLabel != null) chapterLabel.setText(current.chapters().get(chapter).title());
    }
    private void setFullscreen(boolean enabled) {
        boolean changed = fullscreen != enabled;
        fullscreen = enabled;
        if (header == null || current == null) return;
        android.transition.TransitionManager.endTransitions(root);
        controlsTransition = null;
        if (changed && !controlsFramePending && root.isLaidOut() && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            // Only the controls move. Text reflows once and retains its full-text anchor.
            android.transition.TransitionSet movement = new android.transition.TransitionSet();
            movement.addTransition(controlPanelTransition(header, -1));
            if (footer != null) movement.addTransition(controlPanelTransition(footer, 1));
            movement.setDuration(180);
            movement.setInterpolator(new android.view.animation.DecelerateInterpolator());
            movement.addListener(new android.transition.TransitionListenerAdapter() {
                @Override public void onTransitionEnd(android.transition.Transition transition) {
                    if (controlsTransition == movement) controlsTransition = null;
                }
            });
            controlsTransition = movement;
            android.transition.TransitionManager.beginDelayedTransition(root, movement);
            // Native delayed transitions cannot be restarted safely before their first draw.
            // Further toggles in this frame settle immediately to the latest visibility.
            controlsFramePending = true;
            ViewGroup scene = root;
            int version = renderVersion;
            scene.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    scene.getViewTreeObserver().removeOnPreDrawListener(this);
                    if (version == renderVersion) controlsFramePending = false;
                    return true;
                }
            });
        }
        header.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (footer != null) footer.setVisibility(enabled ? View.GONE : View.VISIBLE);
        int flags = options.light() ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
        if (Build.VERSION.SDK_INT >= 27 && options.light()) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (enabled) flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if (!focus && textScroll != null) restoreAfterLayout();
    }
    private android.transition.Transition controlPanelTransition(View target, int direction) {
        // A full-root Slide moves a small panel offscreen almost instantly; use 16 dp.
        android.transition.Visibility transition = new android.transition.Visibility() {
            private android.animation.Animator motion(View view, boolean entering) {
                float shift = dp(16) * direction;
                android.animation.AnimatorSet animation = new android.animation.AnimatorSet();
                animation.playTogether(
                        android.animation.ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, entering ? shift : 0, entering ? 0 : shift),
                        android.animation.ObjectAnimator.ofFloat(view, View.ALPHA, entering ? 0 : 1, entering ? 1 : 0));
                animation.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator animator) {
                        view.setTranslationY(0); view.setAlpha(1);
                    }
                });
                return animation;
            }
            @Override public android.animation.Animator onAppear(ViewGroup scene, View view,
                    android.transition.TransitionValues start, android.transition.TransitionValues end) {
                return motion(view, true);
            }
            @Override public android.animation.Animator onDisappear(ViewGroup scene, View view,
                    android.transition.TransitionValues start, android.transition.TransitionValues end) {
                return motion(view, false);
            }
        };
        return transition.addTarget(target);
    }
    private void readerMenu() {
        pause();
        String[] items = {tr("Поиск в книге", "Search book"), tr("Закладки и цитаты", "Bookmarks & quotes"),
                tr("Навигация", "Navigation"), tr("Настройки", "Settings"), focus ? getString(R.string.reader) : "RSVP"};
        dialogs().setTitle(tr("Чтение", "Reading")).setItems(items, (d, index) -> {
            switch (index) {
                case 0 -> searchBook();
                case 1 -> bookmarks();
                case 2 -> readingNavigation();
                case 3 -> settings(focus);
                case 4 -> { focus = !focus; reader(); }
            }
        }).show();
    }
    private void jump(int offset) {
        pause();
        returnAnchor = anchor;
        anchor = Math.min(current.text().length() - 1, Math.max(0, offset));
        position = reading.wordAt(anchor);
        prefs.edit().putInt(key(current, "return"), returnAnchor).apply();
        reader();
    }
    private void contents() {
        String[] titles = current.chapters().stream().map(Books.Chapter::title).toArray(String[]::new);
        dialogs().setTitle(tr("Оглавление", "Contents")).setItems(titles,
                (d, index) -> jump(current.chapters().get(index).offset())).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void searchBook() {
        LinearLayout content = column();
        EditText search = labeledField(content, tr("Найти текст", "Find text"), "");
        TextView resultCount = label(tr("Введите слово или фразу", "Enter a word or phrase"), 14, options.muted());
        content.addView(resultCount);
        ListView results = new ListView(this);
        results.setSelector(new ColorDrawable(Color.TRANSPARENT));
        results.setDivider(new ColorDrawable(options.surface()));
        results.setDividerHeight(dp(8));
        results.setVisibility(View.GONE);
        content.addView(results, new LinearLayout.LayoutParams(-1, dp(220)));
        AlertDialog dialog = dialogs().setTitle(tr("Поиск в книге", "Search book")).setView(content)
                .setNegativeButton(android.R.string.cancel, null).create();
        Runnable[] pending = {null};
        int[] generation = {0};
        search.addTextChangedListener(watcher(() -> {
            if (pending[0] != null) handler.removeCallbacks(pending[0]);
            int request = ++generation[0];
            String needle = search.getText().toString();
            String haystack = current.text();
            pending[0] = () -> worker.execute(() -> {
                List<Integer> offsets = new ArrayList<>();
                List<String> snippets = new ArrayList<>();
                if (!needle.isEmpty()) {
                    java.util.regex.Matcher matches = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(needle),
                            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE).matcher(haystack);
                    while (matches.find() && offsets.size() < 200) {
                        offsets.add(matches.start());
                        snippets.add(haystack.substring(Math.max(0, matches.start() - 24), Math.min(haystack.length(), matches.end() + 55)).replace('\n', ' '));
                    }
                }
                runOnUiThread(() -> {
                    if (!dialog.isShowing() || request != generation[0]) return;
                    results.setVisibility(needle.isEmpty() || offsets.isEmpty() ? View.GONE : View.VISIBLE);
                    resultCount.setText(needle.isEmpty() ? tr("Введите слово или фразу", "Enter a word or phrase")
                            : offsets.isEmpty() ? tr("Совпадений нет", "No matches")
                            : offsets.size() == 200 ? tr("Первые 200 совпадений", "First 200 matches")
                            : tr("Найдено: ", "Matches: ") + offsets.size());
                    results.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, snippets) {
                        @Override public View getView(int position, View convert, android.view.ViewGroup parent) {
                            TextView view = convert instanceof TextView label ? label : label("", 16, options.ink());
                            view.setText(getItem(position));
                            view.setPadding(dp(12), dp(12), dp(12), dp(12));
                            view.setMinHeight(dp(48));
                            view.setBackground(feedback(shape(options.background(), 12)));
                            return view;
                        }
                    });
                    results.setOnItemClickListener((parent, view, index, id) -> {
                        if (index < offsets.size()) { dialog.dismiss(); jump(offsets.get(index)); }
                    });
                });
            });
            handler.postDelayed(pending[0], 180);
        }));
        dialog.setOnDismissListener(d -> { generation[0]++; if (pending[0] != null) handler.removeCallbacks(pending[0]); });
        dialog.show();
    }
    private JSONArray bookmarkData() {
        try { return new JSONArray(prefs.getString(key(current, "bookmarks"), "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    private void addBookmark() { addBookmark(anchor, anchor); }
    private void addBookmark(int start, int end) {
        LinearLayout content = column();
        EditText note = labeledField(content, tr("Заметка (необязательно)", "Note (optional)"), "");
        dialogs().setTitle(tr("Добавить закладку", "Add bookmark")).setView(content)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(tr("Сохранить", "Save"), (d, which) -> {
                    JSONArray data = bookmarkData();
                    try {
                        data.put(new JSONObject().put("offset", start).put("end", end).put("note", note.getText().toString())
                                .put("excerpt", current.text().substring(start, Math.min(end > start ? end : start + 90, Math.min(start + 300, current.text().length()))).replace('\n', ' ')));
                        prefs.edit().putString(key(current, "bookmarks"), data.toString()).apply();
                        if (end > start) applyReadingText();
                    } catch (Exception e) { message(tr("Ошибка сохранения", "Save failed"), e.toString()); }
                }).show();
    }
    private void bookmarks() {
        JSONArray data = bookmarkData();
        if (data.length() == 0) { message(tr("Закладки", "Bookmarks"), tr("Добавьте закладку через меню чтения.", "Add a bookmark from the reading menu.")); return; }
        String[] names = new String[data.length()];
        for (int i = 0; i < names.length; i++) {
            JSONObject item = data.optJSONObject(i);
            names[i] = item.optString("note") + "\n" + item.optString("excerpt");
        }
        dialogs().setTitle(tr("Закладки и цитаты", "Bookmarks & quotes")).setItems(names, (d, index) -> {
            JSONObject item = data.optJSONObject(index);
            dialogs().setMessage(names[index]).setPositiveButton(tr("Перейти", "Go to"), (dialog, which) -> jump(item.optInt("offset")))
                    .setNeutralButton(tr("Редактировать", "Edit"), (dialog, which) -> {
                        LinearLayout fields = column();
                        EditText note = labeledField(fields, tr("Заметка", "Note"), item.optString("note"));
                        dialogs().setView(fields).setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(tr("Сохранить", "Save"), (edit, value) -> {
                                    try { item.put("note", note.getText().toString()); prefs.edit().putString(key(current, "bookmarks"), data.toString()).apply(); }
                                    catch (Exception e) { message(tr("Ошибка", "Error"), e.toString()); }
                                }).show();
                    }).setNegativeButton(tr("Удалить", "Delete"), (dialog, which) -> {
                        data.remove(index); prefs.edit().putString(key(current, "bookmarks"), data.toString()).apply();
                        applyReadingText();
                    }).show();
        }).show();
    }
    private long wordDelay() {
        String word = reading.words.get(position);
        double base = 60000.0 / speed;
        double delay = base;
        if (options.flag("punctuation", true)) delay += (ReadingText.delay(word, speed) - base) * options.number("pauseStrength", 100) / 100.0;
        if (options.flag("longWords", true) && word.codePointCount(0, word.length()) > 9) delay *= options.number("longPause", 135) / 100.0;
        return (long) delay;
    }
    private void focusScreen() {
        footer = null;
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView tag = label(getString(R.string.focus_label), 12, options.accent());
        tag.setGravity(Gravity.CENTER);
        content.addView(tag);
        focusView = new FocusView();
        content.addView(focusView, new LinearLayout.LayoutParams(-1, dp(180)));
        focusView.setOnClickListener(v -> setFullscreen(!fullscreen));
        contextLabel = label("", 15, options.muted());
        contextLabel.setGravity(Gravity.CENTER);
        content.addView(contextLabel);
        gap(content, 16);
        counter = label("", 13, options.muted());
        counter.setGravity(Gravity.CENTER);
        content.addView(counter);
        progress = new SeekBar(this);
        tintSeekBar(progress);
        progress.setMax(reading.words.size() - 1);
        progress.setContentDescription(getString(R.string.book_progress));
        progress.setOnSeekBarChangeListener(seekListener(value -> {
            pause(); position = value; anchor = reading.offsets.get(position); updateFocus(); save();
        }));
        content.addView(progress, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout controls = row();
        controls.addView(button("−10", false, () -> seekWords(-10)), weight());
        space(controls);
        play = button(getString(R.string.start), true, this::togglePlayback);
        controls.addView(play, new LinearLayout.LayoutParams(0, -2, 2));
        space(controls);
        controls.addView(button("+10", false, () -> seekWords(10)), weight());
        content.addView(controls);
        gap(content, 8);
        LinearLayout sentences = row();
        sentences.addView(button(tr("← Фраза", "← Sentence"), false, () -> seekSentence(-1)), weight());
        space(sentences);
        sentences.addView(button(tr("Фраза →", "Sentence →"), false, () -> seekSentence(1)), weight());
        content.addView(sentences);
        gap(content, 16);
        range(content, getString(R.string.speed), 100, 900, speed, value -> {
            speed = value; options.put("speed", speed); persistOptions(); updateFocus();
        });
        content.addView(button(tr("Профили и настройки", "Profiles & settings"), false, () -> settings(true)), matchWrap());
        updateFocus();
    }
    private void togglePlayback() {
        if (playing) { pause(); updateFocus(); return; }
        if (position >= reading.words.size() - 1) { position = 0; anchor = 0; }
        playing = true;
        countdown = options.flag("countdown", true) ? 3 : 0;
        playbackLabel(R.string.pause);
        applyWindow(); updateFocus();
        handler.postDelayed(tick, countdown > 0 ? 700 : wordDelay());
    }
    private void seekWords(int delta) {
        pause();
        position = Math.max(0, Math.min(reading.words.size() - 1, position + delta));
        anchor = reading.offsets.get(position);
        updateFocus(); save();
    }
    private void seekSentence(int direction) {
        pause();
        int next = position;
        if (direction > 0) {
            while (next < reading.words.size() - 1 && !sentenceEnd(reading.words.get(next))) next++;
            next = Math.min(reading.words.size() - 1, next + 1);
        } else {
            next = Math.max(0, next - 2);
            while (next > 0 && !sentenceEnd(reading.words.get(next))) next--;
            if (next > 0) next++;
        }
        position = next; anchor = reading.offsets.get(position); updateFocus(); save();
    }
    private boolean sentenceEnd(String word) { return word.matches(".*[.!?…][”’\"»)]*$"); }
    private void updateFocus() {
        if (focusView == null || reading == null) return;
        focusView.invalidate();
        focusView.setContentDescription(reading.words.get(position));
        counter.setText(String.format(Locale.getDefault(), "%d / %d · %s", position + 1, reading.words.size(), getString(R.string.wpm, speed)));
        progress.setProgress(position);
        contextLabel.setText(playing ? "" : String.join(" ", reading.words.subList(Math.max(0, position - 5), Math.min(reading.words.size(), position + 7))));
        if (!playing && position == reading.words.size() - 1) playbackLabel(R.string.restart);
    }
    private void persistOptions() {
        String destination = current != null && prefs.contains("options." + current.id()) ? "options." + current.id() : "options";
        prefs.edit().putString(destination, options.values.toString()).apply();
    }
    private void changedOptions() {
        persistOptions();
        if (settingsOpen) {
            settingsY = settingsScroll.getScrollY();
            settings(settingsRsvp);
        } else if (current != null && reading != null) reader(); else library();
    }
    private LinearLayout settingsGroup(LinearLayout parent, String title) {
        gap(parent, 24);
        TextView heading = label(title, 18, options.ink());
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setCompoundDrawablesRelativeWithIntrinsicBounds(ReaderIcon.forLabel(this, title, options.accent()), null, null, null);
        heading.setCompoundDrawablePadding(dp(10));
        parent.addView(heading);
        gap(parent, 12);
        LinearLayout group = column();
        group.setPadding(dp(16), dp(16), dp(16), dp(16));
        group.setBackground(shape(options.surface(), 20));
        parent.addView(group, matchWrap());
        return group;
    }
    private void themeChoices(LinearLayout parent) {
        String[] names = {tr("Тёмная", "Dark"), tr("Сумерки", "Dusk"), tr("Светлая", "Light"), tr("Сепия", "Sepia")};
        for (int line = 0; line < 2; line++) {
            LinearLayout row = row();
            for (int col = 0; col < 2; col++) {
                int theme = line * 2 + col;
                if (col > 0) space(row);
                boolean selected = options.number("theme", 0) == theme;
                Button button = button((selected ? "✓  " : "") + names[theme], selected, () -> {
                    options.put("theme", theme); changedOptions();
                });
                button.setSelected(selected);
                button.setContentDescription(names[theme]);
                ReaderOptions swatch = new ReaderOptions("{}");
                swatch.put("theme", theme);
                GradientDrawable background = shape(swatch.background(), 16);
                background.setStroke(dp(selected ? 2 : 1), selected ? options.accent() : options.muted());
                button.setTextColor(swatch.ink());
                button.setBackground(feedback(background));
                button.setMinHeight(dp(60));
                row.addView(button, weight());
            }
            parent.addView(row);
            gap(parent, 8);
        }
    }
    private void settings(boolean rsvp) {
        pause();
        if (!settingsOpen && !restoreSettings) settingsY = 0;
        settingsOpen = true;
        settingsLibrary = false;
        settingsRsvp = rsvp;
        restoreSettings = false;
        fullscreen = false;
        if (current == null) speed = options.number("speed", prefs.getInt("speed", 300));
        shell();
        LinearLayout top = row();
        Button back = button("‹", false, this::back);
        back.setContentDescription(tr("Назад", "Back"));
        top.addView(back, new LinearLayout.LayoutParams(dp(48), -2));
        space(top);
        TextView title = label(tr("Настройки", "Settings"), 24, options.ink());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, weight());
        root.addView(top);
        gap(root, 16);
        boolean narrowTabs = getResources().getConfiguration().screenWidthDp < 400;
        boolean stackedTabs = narrowTabs && getResources().getConfiguration().fontScale >= 1.6f;
        LinearLayout tabs = stackedTabs ? column() : row();
        tabs.setPadding(dp(6), dp(6), dp(6), dp(6));
        tabs.setBackground(shape(options.surface(), 20));
        Button appearance = button(tr("Оформление", "Appearance"), !rsvp, () -> { settingsY = 0; settings(false); });
        appearance.setSelected(!rsvp);
        tabs.addView(appearance, stackedTabs ? matchWrap() : new LinearLayout.LayoutParams(0, -2, narrowTabs ? 2 : 1));
        if (stackedTabs) gap(tabs, 8); else space(tabs);
        Button focusTab = button("RSVP", rsvp, () -> { settingsY = 0; settings(true); });
        focusTab.setSelected(rsvp);
        if (narrowTabs || getResources().getConfiguration().fontScale >= 1.6f) {
            appearance.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
            focusTab.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
        }
        tabs.addView(focusTab, stackedTabs ? matchWrap() : weight());
        root.addView(tabs);
        gap(root, 8);
        settingsScroll = new ScrollView(this);
        settingsScroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = column();
        page.setPadding(0, dp(8), 0, dp(24));
        settingsScroll.addView(page);
        root.addView(settingsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView description = label(current == null ? tr("Ваше пространство для чтения", "Your reading space")
                : title(current), 14, options.muted());
        page.addView(description);
        LinearLayout sample = column();
        sample.setPadding(dp(20), dp(20), dp(20), dp(20));
        sample.setBackground(shape(options.surface(), 20));
        boolean compactSettings = getResources().getConfiguration().screenHeightDp < 500;
        if (rsvp && !compactSettings) gap(page, 16);
        page.addView(sample);
        sample.setVisibility(!rsvp || compactSettings ? View.GONE : View.VISIBLE);
        sample.addView(label(tr("ПРЕДПРОСМОТР", "PREVIEW"), 12, options.accent()));
        gap(sample, 12);
        if (rsvp && reading != null) {
            sample.addView(new FocusView(), new LinearLayout.LayoutParams(-1, dp(130)));
        } else {
            TextView preview = label(tr("Время для новой истории", "Time for a new story"), options.number("size", 21), options.ink());
            preview.setTypeface(Typeface.create(new String[]{"serif", "sans-serif", "monospace"}[options.number("font", 0)], Typeface.NORMAL));
            preview.setLineSpacing(0, options.number("line", 150) / 100f);
            sample.addView(preview);
            gap(sample, 8);
            sample.addView(label(tr("Настройки сохраняются автоматически.", "Changes are saved automatically."), 14, options.muted()));
        }
        LinearLayout content = column();
        gap(page, 12);
        page.addView(content);
        if (current != null) toggle(content, tr("Только для этой книги", "Only for this book"), prefs.contains("options." + current.id()), enabled -> {
            if (enabled) prefs.edit().putString("options." + current.id(), options.values.toString()).apply();
            else {
                prefs.edit().remove("options." + current.id()).apply();
                options = ReaderOptions.load(prefs, null);
                speed = options.number("speed", 300);
                settingsY = settingsScroll.getScrollY();
                settings(rsvp);
            }
        });
        if (!rsvp) {
            content = settingsGroup(page, tr("Чтение", "Reading"));
            choose(content, tr("Режим чтения", "Reading mode"), new String[]{tr("Прокрутка", "Scroll"), tr("Листание", "Pages")},
                    options.flag("pages", false) ? 1 : 0,
                    value -> { options.put("pages", value == 1); changedOptions(); });
            content.addView(label(tr("Прокрутка — непрерывный текст. Листание — страницы и развороты на широком экране.",
                    "Scroll for continuous text. Pages for single pages or a spread on wide screens."), 14, options.muted()));
            gap(content, 8);
            content = settingsGroup(page, tr("Типографика", "Typography"));
            choose(content, tr("Шрифт", "Typeface"), new String[]{tr("С засечками", "Serif"), tr("Без засечек", "Sans serif"), tr("Моноширинный", "Monospace")},
                    options.number("font", 0), value -> { options.put("font", value); changedOptions(); });
            textSizeControl(content);
            range(content, tr("Межстрочный интервал, %", "Line height, %"), 110, 200, options.number("line", 150),
                    value -> { options.put("line", value); changedOptions(); });
            toggle(content, tr("Переносы слов", "Word hyphenation"), options.flag("hyphenation", true),
                    value -> { options.put("hyphenation", value); changedOptions(); });
            content = settingsGroup(page, tr("Цвет и атмосфера", "Color & atmosphere"));
            themeChoices(content);
            choose(content, tr("Акцент", "Accent"), new String[]{tr("Лайм", "Lime"), tr("Лёд", "Ice"), tr("Лаванда", "Lavender"), tr("Янтарь", "Amber")},
                    options.number("accent", 0), value -> { options.put("accent", value); changedOptions(); });
            content = settingsGroup(page, tr("Страница", "Page layout"));
            range(content, tr("Поля", "Margins"), 0, 40, options.number("margin", 8),
                    value -> { options.put("margin", value); changedOptions(); });
            range(content, tr("Красная строка", "Paragraph indent"), 0, 48, options.number("indent", 20),
                    value -> { options.put("indent", value); changedOptions(); });
            range(content, tr("Интервал между абзацами", "Paragraph spacing"), 0, 32, options.number("paragraphGap", 8),
                    value -> { options.put("paragraphGap", value); changedOptions(); });
            choose(content, tr("Выравнивание", "Alignment"), new String[]{tr("По левому краю", "Start"), tr("По ширине", "Justified"), tr("По центру", "Centered")},
                    options.number("alignment", 0), value -> { options.put("alignment", value); changedOptions(); });
            toggle(content, tr("Листать кнопками громкости", "Turn pages with volume keys"), options.flag("volumePages", false),
                    value -> { options.put("volumePages", value); persistOptions(); });
            content.addView(label(tr("В режиме листания: стрелки и края — страницы, центр — панели. Удержание текста — цитата.",
                    "Page mode: arrows or edge taps turn pages, center toggles controls. Hold text to save a quote."), 14, options.muted()));
            content = settingsGroup(page, tr("Экран", "Display"));
            range(content, tr("Яркость, 0 = системная", "Brightness, 0 = system"), 0, 100, options.number("brightness", 0),
                    value -> { options.put("brightness", value); persistOptions(); applyWindow(); });
            toggle(content, tr("Не выключать экран", "Keep screen awake"), options.flag("awake", false),
                    value -> { options.put("awake", value); persistOptions(); applyWindow(); });
        } else {
            content = settingsGroup(page, tr("Отображение слова", "Word display"));
            range(content, tr("Размер слова", "Word size"), 24, 64, options.number("rsvpSize", 40),
                    value -> { options.put("rsvpSize", value); changedOptions(); });
            choose(content, tr("Цвет опорной буквы", "Anchor letter color"), new String[]{tr("Акцент темы", "Theme accent"), tr("Коралловый", "Coral"), tr("Голубой", "Blue")},
                    options.number("pivotColor", 0), value -> { options.put("pivotColor", value); changedOptions(); });
            toggle(content, tr("Направляющие", "Guides"), options.flag("guides", true),
                    value -> { options.put("guides", value); changedOptions(); });
            content = settingsGroup(page, tr("Темп и паузы", "Pace & pauses"));
            toggle(content, tr("Обратный отсчёт", "Start countdown"), options.flag("countdown", true),
                    value -> { options.put("countdown", value); persistOptions(); });
            toggle(content, tr("Паузы на пунктуации", "Punctuation pauses"), options.flag("punctuation", true),
                    value -> { options.put("punctuation", value); persistOptions(); });
            toggle(content, tr("Паузы на длинных словах", "Long-word pauses"), options.flag("longWords", true),
                    value -> { options.put("longWords", value); persistOptions(); });
            range(content, tr("Сила пауз на пунктуации, %", "Punctuation pause strength, %"), 0, 200, options.number("pauseStrength", 100),
                    value -> { options.put("pauseStrength", value); persistOptions(); });
            range(content, tr("Длительность длинных слов, %", "Long-word duration, %"), 100, 250, options.number("longPause", 135),
                    value -> { options.put("longPause", value); persistOptions(); });
            range(content, getString(R.string.speed), 100, 900, speed,
                    value -> { speed = value; options.put("speed", value); changedOptions(); });
            content = settingsGroup(page, tr("Профили скорости", "Speed profiles"));
            for (String language : new String[]{"RU", "EN"}) {
                for (int slot = 1; slot <= 2; slot++) {
                    String profile = language + " " + slot;
                    LinearLayout actions = row();
                    Button load = button(profile + " · " + prefs.getInt("profile." + profile, language.equals("RU") ? 250 : 300),
                            false, () -> {
                                speed = prefs.getInt("profile." + profile, language.equals("RU") ? 250 : 300);
                                options.put("speed", speed); changedOptions();
                            });
                    actions.addView(load, weight());
                    space(actions);
                    actions.addView(button(tr("Записать", "Save"), false, () -> {
                        prefs.edit().putInt("profile." + profile, speed).apply();
                        load.setText(String.format(Locale.getDefault(), "%s · %d", profile, speed));
                        Toast.makeText(this, tr("Профиль сохранён", "Profile saved"), Toast.LENGTH_SHORT).show();
                    }), weight());
                    content.addView(actions);
                    gap(content, 8);
                }
            }
        }
        LinearLayout updates = settingsGroup(page, tr("Приложение", "Application"));
        LinearLayout updateRow = row(); updateRow.setTag("updates-entry");
        updateRow.setPadding(0, dp(4), 0, dp(4)); updateRow.setMinimumHeight(dp(56));
        updateRow.setBackground(feedback(shape(options.surface(), 14)));
        ImageView updateIcon = new ImageView(this); updateIcon.setImageDrawable(new ReaderIcon(this, "download", options.accent()));
        updateIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        updateRow.addView(updateIcon, new LinearLayout.LayoutParams(dp(24), dp(24))); space(updateRow);
        LinearLayout updateText = column(); updateText.addView(label(tr("Обновления", "Updates"), 16, options.ink())); gap(updateText, 4);
        updateText.addView(label(tr("Версия ", "Version ") + AppUpdates.version(this), 13, options.muted()));
        updateRow.addView(updateText, weight()); space(updateRow);
        ImageView updateArrow = new ImageView(this); updateArrow.setImageDrawable(new ControlIcon("→", options.muted()));
        updateArrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        updateRow.addView(updateArrow, new LinearLayout.LayoutParams(dp(24), dp(24)));
        updateRow.setContentDescription(tr("Обновления. Версия ", "Updates. Version ") + AppUpdates.version(this));
        updateRow.setFocusable(true); updateRow.setOnClickListener(v -> startActivity(new Intent(this, UpdateActivity.class)));
        updates.addView(updateRow, matchWrap());
        int scrollY = settingsY;
        ScrollView scroll = settingsScroll;
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }
    private class FocusView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        FocusView() { super(MainActivity.this); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); }
        @Override protected void onDraw(Canvas canvas) {
            float center = getWidth() / 2f, baseline = getHeight() / 2f + dp(12);
            if (options.flag("guides", true)) {
                paint.setColor(options.muted()); paint.setAlpha(100); paint.setStrokeWidth(dp(1));
                canvas.drawLine(0, baseline - dp(55), getWidth(), baseline - dp(55), paint);
                canvas.drawLine(0, baseline + dp(30), getWidth(), baseline + dp(30), paint);
                paint.setAlpha(255); paint.setColor(options.accent());
                canvas.drawLine(center, baseline - dp(55), center, baseline - dp(44), paint);
                canvas.drawLine(center, baseline + dp(19), center, baseline + dp(30), paint);
            }
            String word = countdown > 0 ? String.valueOf(countdown) : reading.words.get(position);
            int pivot = ReadingText.pivot(word), end = word.offsetByCodePoints(pivot, 1);
            String left = word.substring(0, pivot), middle = word.substring(pivot, end), right = word.substring(end);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            float textSize = options.number("rsvpSize", 40) * getResources().getDisplayMetrics().scaledDensity;
            paint.setTextSize(textSize);
            float extent = Math.max(paint.measureText(left), paint.measureText(right)) + paint.measureText(middle) / 2;
            paint.setTextSize(textSize * Math.min(1, (center - dp(8)) / Math.max(1, extent)));
            float start = center - paint.measureText(middle) / 2;
            paint.setColor(options.ink());
            canvas.drawText(left, start - paint.measureText(left), baseline, paint);
            canvas.drawText(right, start + paint.measureText(middle), baseline, paint);
            int color = options.number("pivotColor", 0);
            paint.setColor(color == 1 ? (options.light() ? 0xFFAC2B39 : 0xFFFF96A0)
                    : color == 2 ? (options.light() ? 0xFF07577A : 0xFF8BD9FF) : options.accent());
            canvas.drawText(middle, start, baseline, paint);
        }
    }
    private class CoverArt extends Drawable {
        final String title;
        final int seed;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint lettering = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        StaticLayout titleLayout;
        CoverArt(String title, int seed) { this.title = title; this.seed = seed; }
        @Override protected void onBoundsChange(Rect bounds) {
            lettering.setColor(0xFFF0EDDB); lettering.setTypeface(Typeface.create("serif", Typeface.BOLD));
            lettering.setTextSize(bounds.width() * .115f);
            titleLayout = StaticLayout.Builder.obtain(title, 0, title.length(), lettering, Math.max(1, (int) (bounds.width() * .76f)))
                    .setIncludePad(false).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
        }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds(); float width = b.width(), height = b.height();
            int[] colors = {0xFF173F3C, 0xFF3B304D, 0xFF243C56, 0xFF563B2D};
            int[] accents = {0xFFB8D5B6, 0xFFD5B8CB, 0xFFB6CED5, 0xFFE0BE8D};
            int variant = Math.floorMod(seed, colors.length);
            canvas.save(); canvas.clipRect(b);
            paint.setStyle(Paint.Style.FILL); paint.setColor(colors[variant]); canvas.drawRect(b, paint);
            paint.setColor(0x30000000); canvas.drawRect(b.left, b.top, b.left + width * .045f, b.bottom, paint);
            paint.setColor(accents[variant]); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1, width * .006f));
            for (int i = 0; i < 4; i++) {
                float radius = width * (.10f + .06f * i);
                canvas.drawCircle(b.left + width * (.5f + (variant % 2 == 0 ? .12f : -.12f)), b.top + height * .33f, radius, paint);
            }
            canvas.drawLine(b.left + width * .12f, b.top + height * .55f, b.right - width * .12f, b.top + height * .55f, paint);
            paint.setStyle(Paint.Style.FILL); paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextSize(width * .045f);
            canvas.drawText("M J  /  R E A D E R", b.left + width * .12f, b.top + height * .10f, paint);
            if (titleLayout != null) {
                canvas.translate(b.left + width * .12f, b.top + height * .62f);
                titleLayout.draw(canvas);
            }
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); lettering.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); lettering.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    private class ControlIcon extends Drawable {
        private final String symbol;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ControlIcon(String symbol, int color) {
            this.symbol = symbol;
            paint.setColor(color);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
        }
        @Override public int getIntrinsicWidth() { return dp(24); }
        @Override public int getIntrinsicHeight() { return dp(24); }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.save();
            canvas.translate(b.left, b.top);
            canvas.scale(b.width() / 24f, b.height() / 24f);
            paint.setStrokeWidth(2);
            if (symbol.equals("⋮")) {
                paint.setStyle(Paint.Style.FILL);
                for (int y = 6; y <= 18; y += 6) canvas.drawCircle(12, y, 1.5f, paint);
            } else if (symbol.equals("+") || symbol.equals("−")) {
                canvas.drawLine(5, 12, 19, 12, paint);
                if (symbol.equals("+")) canvas.drawLine(12, 5, 12, 19, paint);
            } else if (symbol.equals("⌄")) {
                canvas.drawLine(6, 9, 12, 15, paint);
                canvas.drawLine(12, 15, 18, 9, paint);
            } else {
                if (symbol.equals("→")) { canvas.translate(24, 0); canvas.scale(-1, 1); }
                canvas.drawLine(14, 5, 7, 12, paint);
                canvas.drawLine(7, 12, 14, 19, paint);
                if (!symbol.equals("‹")) canvas.drawLine(7, 12, 21, 12, paint);
            }
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout;
    }
    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this); layout.setGravity(Gravity.CENTER_VERTICAL); return layout;
    }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private void space(LinearLayout parent) { parent.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1)); }
    private void gap(LinearLayout parent, int size) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size))); }
    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(radius)); return background;
    }
    private Drawable feedback(GradientDrawable background) {
        return options.feedback(background, getResources().getDisplayMetrics().density);
    }
    private Button button(String title, boolean accent, Runnable action) {
        Button button = new Button(this);
        button.setText(title); button.setTextSize(16); button.setAllCaps(false);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(ReaderIcon.forLabel(this, title, accent ? options.onAccent() : options.ink()), null, null, null);
        button.setCompoundDrawablePadding(dp(8));
        if (Arrays.asList("+", "−", "←", "→", "‹", "⋮").contains(title)) {
            button.setText("");
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(new ControlIcon(title, accent ? options.onAccent() : options.ink()), null, null, null);
        }
        button.setTextColor(accent ? options.onAccent() : options.ink());
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setMinHeight(dp(48)); button.setMinWidth(dp(48)); button.setMinimumWidth(dp(48));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setBackground(feedback(shape(accent ? options.accent() : options.surface(), 14)));
        button.setOnClickListener(v -> action.run());
        return button;
    }
    private EditText field(String value) {
        EditText input = new EditText(this);
        input.setText(value); input.setTextColor(options.ink()); input.setHintTextColor(options.muted());
        input.setTextSize(16); input.setMinHeight(dp(48)); input.setMinimumHeight(dp(48));
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable fieldBackground = shape(options.surface(), 16);
        fieldBackground.setStroke(dp(1), options.muted());
        input.setBackground(feedback(fieldBackground));
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setBackgroundTintList(null);
        return input;
    }
    private EditText labeledField(LinearLayout parent, String label, String value) {
        TextView caption = label(label, 14, options.muted());
        EditText input = field(value);
        input.setId(View.generateViewId()); caption.setLabelFor(input.getId());
        parent.addView(caption); parent.addView(input, matchWrap()); gap(parent, 8); return input;
    }
    private TextWatcher watcher(Runnable change) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { change.run(); }
            public void afterTextChanged(Editable editable) {}
        };
    }
    private SeekBar.OnSeekBarChangeListener seekListener(IntConsumer change) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) change.accept(value); }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        };
    }
    private void playbackLabel(int resource) {
        play.setText(resource);
        play.setCompoundDrawablesRelativeWithIntrinsicBounds(ReaderIcon.forLabel(this, getString(resource), options.onAccent()), null, null, null);
    }
    private void tintSeekBar(SeekBar bar) {
        bar.setProgressTintList(null);
        bar.setProgressBackgroundTintList(null);
        bar.setThumbTintList(null);
        bar.setSplitTrack(false);
        bar.setBackground(feedback(shape(options.background(), 12)));
        bar.setPadding(dp(12), 0, dp(12), 0);
        bar.setProgressDrawable(new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas canvas) {
                Rect b = getBounds();
                float y = b.exactCenterY(), radius = dp(3);
                paint.setColor(options.muted()); paint.setAlpha(100);
                canvas.drawRoundRect(b.left, y - radius, b.right, y + radius, radius, radius, paint);
                paint.setColor(options.accent()); paint.setAlpha(255);
                canvas.drawRoundRect(b.left, y - radius, b.left + b.width() * getLevel() / 10000f,
                        y + radius, radius, radius, paint);
            }
            @Override protected boolean onLevelChange(int level) { invalidateSelf(); return true; }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        });
        GradientDrawable thumb = shape(options.accent(), 10);
        thumb.setSize(dp(20), dp(20));
        thumb.setStroke(dp(3), options.surface());
        bar.setThumb(thumb);
        bar.setThumbOffset(dp(10));
    }
    private void range(LinearLayout parent, String title, int min, int max, int value, IntConsumer change) {
        range(parent, title, min, max, value, change, number -> {});
    }
    private void range(LinearLayout parent, String title, int min, int max, int value, IntConsumer change, IntConsumer preview) {
        LinearLayout heading = row();
        TextView caption = label(title, 16, options.ink());
        heading.addView(caption, weight());
        space(heading);
        TextView amount = label(String.valueOf(value), 14, options.accent());
        amount.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        amount.setPadding(dp(10), dp(6), dp(10), dp(6));
        amount.setBackground(shape(options.background(), 10));
        heading.addView(amount);
        parent.addView(heading);
        gap(parent, 4);
        LinearLayout controls = row();
        SeekBar bar = new SeekBar(this);
        tintSeekBar(bar);
        bar.setMax(max - min); bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        bar.setContentDescription(title);
        IntConsumer apply = number -> { amount.setText(String.valueOf(number)); change.accept(number); };
        controls.addView(button("−", false, () -> {
            int next = Math.max(0, bar.getProgress() - 1); bar.setProgress(next); apply.accept(min + next);
        }), new LinearLayout.LayoutParams(dp(48), -2));
        controls.addView(bar, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(button("+", false, () -> {
            int next = Math.min(bar.getMax(), bar.getProgress() + 1); bar.setProgress(next); apply.accept(min + next);
        }), new LinearLayout.LayoutParams(dp(48), -2));
        controls.getChildAt(0).setContentDescription(tr("Уменьшить ", "Decrease ") + title);
        controls.getChildAt(2).setContentDescription(tr("Увеличить ", "Increase ") + title);
        // Apply on release to keep dragging responsive while the reading text reflows.
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seek, int number, boolean user) {
                amount.setText(String.valueOf(number + min));
                preview.accept(number + min);
                if (user && !seek.isPressed()) apply.accept(number + min);
            }
            public void onStartTrackingTouch(SeekBar seek) {}
            public void onStopTrackingTouch(SeekBar seek) { apply.accept(seek.getProgress() + min); }
        });
        parent.addView(controls); gap(parent, 16);
    }
    private void choose(LinearLayout parent, String caption, String[] items, int selected, IntConsumer change) {
        TextView heading = label(caption, 14, options.muted());
        parent.addView(heading);
        gap(parent, 8);
        LinearLayout choices = column();
        Button selectedValue = button(items[selected], false, () -> {
            boolean expanded = choices.getVisibility() != View.VISIBLE;
            choices.setVisibility(expanded ? View.VISIBLE : View.GONE);
        });
        selectedValue.setTextSize(16);
        selectedValue.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, new ControlIcon("⌄", options.muted()), null);
        selectedValue.setCompoundDrawablePadding(dp(12));
        selectedValue.setBackground(feedback(shape(options.background(), 14)));
        selectedValue.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        selectedValue.setContentDescription(caption + " · " + items[selected]);
        parent.addView(selectedValue, matchWrap());
        gap(parent, 8);
        choices.setVisibility(View.GONE);
        for (int index = 0; index < items.length; index++) {
            final int value = index;
            Button choice = button(items[index], selected == index, () -> {
                for (int child = 0; child < choices.getChildCount(); child += 2) {
                    Button other = (Button) choices.getChildAt(child);
                    boolean active = child / 2 == value;
                    other.setSelected(active);
                    other.setTextColor(active ? options.onAccent() : options.ink());
                    other.setBackground(feedback(shape(active ? options.accent() : options.background(), 12)));
                    other.setText((active ? "✓  " : "") + items[child / 2]);
                }
                choices.setVisibility(View.GONE);
                selectedValue.setText(items[value]);
                selectedValue.setContentDescription(caption + " · " + items[value]);
                change.accept(value);
            });
            choice.setText((index == selected ? "✓  " : "") + items[index]);
            choice.setSelected(index == selected);
            choice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            choice.setContentDescription(caption + " · " + items[index]);
            choices.addView(choice, matchWrap());
            gap(choices, 8);
        }
        parent.addView(choices);
        gap(parent, 12);
    }
    private void toggle(LinearLayout parent, String title, boolean checked, java.util.function.Consumer<Boolean> change) {
        Button control = button(title, false, () -> {});
        control.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        control.setContentDescription(title);
        control.setSelected(checked);
        control.setCompoundDrawablePadding(dp(16));
        control.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, options.toggleIcon(this, checked), null);
        control.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Switch");
                info.setCheckable(true);
                info.setChecked(host.isSelected());
            }
        });
        control.setOnClickListener(view -> {
            boolean enabled = !control.isSelected();
            control.setSelected(enabled);
            control.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, options.toggleIcon(this, enabled), null);
            change.accept(enabled);
        });
        parent.addView(control, matchWrap());
        gap(parent, 12);
    }
    private ReaderDialogs dialogs() {
        return new ReaderDialogs(this, options);
    }
    private void message(String title, String message) {
        if (isDestroyed()) return;
        dialogs().setTitle(title).setMessage(message).setPositiveButton(android.R.string.ok, null).show();
    }
}

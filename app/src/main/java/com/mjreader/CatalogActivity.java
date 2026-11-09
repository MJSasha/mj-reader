package com.mjreader;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Flibusta's OPDS catalog and direct book downloads, using the reader's native controls. */
public class CatalogActivity extends Activity {
    private ReaderOptions theme;
    private SharedPreferences prefs;
    private LinearLayout root, content;
    private EditText input;
    private TextView loadingLabel, resultStatus, pagingLabel;
    private LinearLayout pagingFooter;
    private ProgressBar pagingSpinner;
    private ListView resultsList, downloadsList;
    private boolean loadingMore, pagingFailed, refreshing;
    private final Set<String> loadedPages = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> task;
    private boolean authors, downloads, busy;
    private int generation;
    private int catalogItem = -1, catalogTop;
    private int resultColumns = 1;
    private int downloadRow, downloadTop;
    private String pendingUrl = "";
    private record Retained(List<Flibusta.Entry> entries, Set<String> pages, List<JSONObject> jobs,
            String url, String next, String title, String message, String pending) {}
    private String query = "", message = "", currentUrl = "", next = "", feedTitle = "";
    private final List<Flibusta.Entry> entries = new ArrayList<>();
    private final Deque<String> history = new ArrayDeque<>();
    private List<JSONObject> jobs = new ArrayList<>();
    private BaseAdapter adapter;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (downloads && !busy && !refreshing) { refreshing = true; worker.execute(() -> {
                List<JSONObject> updated = BookDownloads.list(CatalogActivity.this);
                runOnUiThread(() -> { refreshing = false; if (isDestroyed() || !downloads || busy) return;
                    boolean structure = jobs.isEmpty() != updated.isEmpty();
                    boolean changed = !jobs.toString().equals(updated.toString()); jobs = updated;
                    if (structure) render(); else if (adapter != null && changed) adapter.notifyDataSetChanged();
                });
            }); }
            handler.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); prefs = getSharedPreferences("reader", MODE_PRIVATE); theme = ReaderOptions.load(prefs, null);
        downloads = getIntent().getBooleanExtra("downloads", false);
        String restoreUrl = "";
        if (state != null) {
            query = state.getString("query", ""); authors = state.getBoolean("authors"); downloads = state.getBoolean("downloads"); restoreUrl = state.getString("url", "");
            catalogItem = state.getInt("catalogItem"); catalogTop = state.getInt("catalogTop");
            downloadRow = state.getInt("downloadRow"); downloadTop = state.getInt("downloadTop");
            ArrayList<String> saved = state.getStringArrayList("history"); if (saved != null) history.addAll(saved);
        }
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::back);
        Retained retained = (Retained) getLastNonConfigurationInstance();
        if (retained != null) {
            entries.addAll(retained.entries()); loadedPages.addAll(retained.pages()); jobs = retained.jobs();
            currentUrl = retained.url(); next = retained.next(); feedTitle = retained.title(); message = retained.message();
            restoreUrl = retained.pending();
        }
        setResult(RESULT_OK); render();
        if (!restoreUrl.isEmpty() && !downloads) load(restoreUrl, false);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (input != null && !downloads) query = input.getText().toString();
        state.putString("query", query); state.putBoolean("authors", authors); state.putBoolean("downloads", downloads);
        rememberScroll(); state.putInt("catalogItem", catalogItem); state.putInt("catalogTop", catalogTop);
        state.putInt("downloadRow", downloadRow); state.putInt("downloadTop", downloadTop);
        state.putString("url", pendingUrl.isEmpty() ? currentUrl : pendingUrl); state.putStringArrayList("history", new ArrayList<>(history)); super.onSaveInstanceState(state);
    }
    @Override public Object onRetainNonConfigurationInstance() {
        return new Retained(new ArrayList<>(entries), new HashSet<>(loadedPages), new ArrayList<>(jobs),
                currentUrl, next, feedTitle, message, pendingUrl);
    }
    private void rememberScroll() {
        if (downloads && downloadsList != null && downloadsList.isAttachedToWindow() && downloadsList.getChildCount() > 0) {
            downloadRow = downloadsList.getFirstVisiblePosition(); downloadTop = downloadsList.getChildAt(0).getTop();
        }
        if (!downloads && resultsList != null && resultsList.isAttachedToWindow() && resultsList.getChildCount() > 0) {
            int row = resultsList.getFirstVisiblePosition();
            catalogItem = row == 0 ? -1 : (row - 1) * catalogColumns();
            catalogTop = resultsList.getChildAt(0).getTop();
        }
    }
    private int catalogColumns() {
        return resultColumns;
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { generation++; if (task != null) task.cancel(true); worker.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { back(); }
    private void back() {
        if (busy) { cancel(); return; }
        if (loadingMore) { generation++; if (task != null) task.cancel(true); loadingMore = false; }
        if (!downloads && !history.isEmpty()) { load(history.pop(), false); return; }
        if (!downloads && !currentUrl.isEmpty()) { currentUrl = ""; next = ""; entries.clear(); message = ""; render(); return; }
        finish();
    }
    private void cancel() { generation++; if (task != null) task.cancel(true); loadingMore = false; busy = false; pendingUrl = ""; message = feedTitle; render(); }
    private String base() { String value = prefs.getString("catalog", "auto"); return value.equals("auto") ? Flibusta.mirrors(prefs).get(0) : value; }
    private void render() {
        resultColumns = getResources().getConfiguration().screenWidthDp >= 720 && getResources().getConfiguration().fontScale < 1.6f ? 2 : 1;
        input = null; resultsList = null; downloadsList = null; adapter = null; loadingLabel = null; resultStatus = null; pagingFooter = null;
        root = column(); root.setBackgroundColor(theme.background());
        ReaderOptions.observeWindow(root, () -> { if (!isDestroyed()) { rememberQuery(); rememberScroll(); render(); } });
        root.setOnApplyWindowInsetsListener((v, insets) -> { int width = getResources().getConfiguration().screenWidthDp;
            int gutter = Math.max(width >= 600 ? 24 : 16, (width - 1040) / 2);
            v.setPadding(dp(gutter) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(), dp(gutter) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom()); return insets; });
        getWindow().setBackgroundDrawable(new ColorDrawable(theme.background()));
        getWindow().getDecorView().setSystemUiVisibility(theme.light() ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        setContentView(root); root.requestApplyInsets();
        LinearLayout mast = row(); mast.addView(control(tr("Назад", "Back"), "back", false, this::back));
        TextView brand = text("MJ / READER", 12, theme.accent()); brand.setLetterSpacing(.16f); brand.setGravity(Gravity.CENTER);
        mast.addView(brand, new LinearLayout.LayoutParams(0, -1, 1));
        mast.addView(iconButton(tr("Настройки каталога", "Catalog settings"), "settings", this::catalogSettings)); root.addView(mast);
        View mastDivider = new View(this); mastDivider.setBackgroundColor(theme.surface());
        root.addView(mastDivider, new LinearLayout.LayoutParams(-1, dp(1))); gap(root, 16);
        if (getResources().getConfiguration().screenHeightDp >= 600) {
            root.addView(text(downloads ? tr("Загрузки", "Downloads") : tr("Флибуста", "Flibusta"), 32, theme.ink())); gap(root, 18);
        }
        content = column(); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout dock = row(); dock.setPadding(dp(8), dp(8), dp(8), dp(8));
        dock.setBackground(shape(theme.surface(), 20));
        dock.addView(navItem(tr("Каталог", "Catalog"), "book", !downloads, () -> switchTab(false)), weight());
        space(dock);
        dock.addView(navItem(tr("Загрузки", "Downloads"), "download", downloads, () -> switchTab(true)), weight());
        LinearLayout.LayoutParams dockParams = match(); dockParams.topMargin = dp(12);
        root.addView(dock, dockParams);
        if (busy) loading(); else if (downloads) downloadsPage(); else searchPage();
    }
    private void switchTab(boolean value) {
        if (input != null && !downloads) query = input.getText().toString();
        if (downloads == value) return;
        rememberScroll();
        generation++; if (task != null) task.cancel(true); pendingUrl = ""; busy = false; loadingMore = false; downloads = value; render(); handler.removeCallbacks(refresh); handler.post(refresh);
    }
    private void searchPage() {
        LinearLayout searchBox = row(); searchBox.setBackground(shape(theme.surface(), 18)); searchBox.setPadding(dp(4), dp(2), dp(4), dp(2));
        input = field(query, authors ? tr("Фамилия автора", "Author's name") : tr("Найти книгу", "Find a book"));
        input.setBackground(theme.feedback(shape(theme.surface(), 14), getResources().getDisplayMetrics().density));
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { search(); return true; } return false; });
        searchBox.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        Button submit = iconButton(tr("Найти", "Find"), "search", this::search);
        submit.setBackground(theme.feedback(shape(theme.accent(), 14), getResources().getDisplayMetrics().density));
        submit.setCompoundDrawablesRelativeWithIntrinsicBounds(new ReaderIcon(this, "search", theme.onAccent()), null, null, null);
        searchBox.addView(submit); content.addView(searchBox); gap(content, 10);
        LinearLayout tools = row();
        Button scope = textAction(authors ? tr("По автору", "By author") : tr("По названию", "By title"), "sort", this::searchOptions);
        tools.addView(scope, weight()); space(tools);
        tools.addView(textAction(tr("Разделы", "Browse"), "library", () -> { rememberQuery(); history.clear(); load(base(), false); }), weight());
        content.addView(tools); gap(content, 14);
        if (!message.isEmpty()) { resultStatus = text(message, 14, theme.muted()); content.addView(resultStatus); gap(content, 10); }
        if (entries.isEmpty() && next.isEmpty()) empty(currentUrl.isEmpty() ? tr("Выбирайте, что читать дальше", "Choose your next read") : tr("Ничего не найдено", "No results"),
                currentUrl.isEmpty() ? tr("Ищите по названию или автору, открывайте разделы каталога. EPUB скачивается прямо в MJReader.", "Search by title or author, or browse the catalog. EPUB books download directly into MJReader.") : tr("Попробуйте другое название, фамилию автора или адрес каталога.", "Try another title, author, or catalog address."));
        else {
            ListView list = list(); adapter = new BaseAdapter() {
                public int getCount() { return (entries.size() + catalogColumns() - 1) / catalogColumns(); }
                public Object getItem(int i) { return entries.get(i * catalogColumns()); }
                public long getItemId(int i) { return i; }
                public View getView(int i, View convert, ViewGroup parent) {
                    LinearLayout line = row(); line.setGravity(Gravity.TOP);
                    for (int col = 0; col < catalogColumns(); col++) {
                        if (col > 0) space(line);
                        int index = i * catalogColumns() + col;
                        line.addView(index < entries.size() ? resultCard(entries.get(index)) : new View(CatalogActivity.this), weight());
                    }
                    return wrap(line);
                }
            }; attachPaging(list); list.setAdapter(adapter); content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            list.setSelectionFromTop(catalogItem < 0 ? 0 : catalogItem / catalogColumns() + 1, catalogTop);
        }
    }
    private View resultCard(Flibusta.Entry entry) {
        LinearLayout card = card();
        LinearLayout badge = row(); ImageView icon = new ImageView(CatalogActivity.this); icon.setImageDrawable(new ReaderIcon(CatalogActivity.this, entry.book() ? "book" : "folder", theme.accent())); badge.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22))); space(badge);
        badge.addView(text(entry.book() ? entry.format().isEmpty() ? tr("ДРУГОЙ ФОРМАТ", "OTHER FORMAT") : entry.format().toUpperCase(Locale.ROOT) : tr("РАЗДЕЛ КАТАЛОГА", "CATALOG SECTION"), 12, theme.accent())); card.addView(badge); gap(card, 10);
        TextView title = text(entry.title(), 19, theme.ink()); title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(3); title.setEllipsize(android.text.TextUtils.TruncateAt.END); card.addView(title);
        if (!entry.author().isEmpty()) { gap(card, 6); card.addView(text(entry.author(), 14, theme.muted())); }
        if (!entry.summary().isEmpty()) { gap(card, 8); TextView summary = text(entry.summary(), 14, theme.muted()); summary.setMaxLines(3); summary.setEllipsize(android.text.TextUtils.TruncateAt.END); card.addView(summary); }
        card.setBackground(theme.feedback(shape(theme.surface(), 20), getResources().getDisplayMetrics().density));
        card.setOnClickListener(v -> {
            if (entry.book()) bookDetails(entry);
            else { history.push(currentUrl.isEmpty() ? base() : currentUrl); load(entry.url(), false); }
        });
        card.setFocusable(true); return card;
    }
    private void rememberQuery() { if (input != null && !downloads) query = input.getText().toString().trim(); }
    private void searchOptions() {
        rememberQuery(); rememberScroll();
        new ReaderDialogs(this, theme).setTitle(tr("Искать по", "Search by"))
                .setItems(new String[]{tr("Название книги", "Book title"), tr("Автор", "Author")}, (d, which) -> { authors = which == 1; render(); }).show();
    }
    private void search() {
        rememberQuery(); if (query.isEmpty()) { input.requestFocus(); ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT); return; }
        try { history.clear(); load(Flibusta.searchUrl(base(), query, authors), false); }
        catch (Exception error) { error(error); }
    }
    private void load(String address, boolean append) {
        if (!append) rememberQuery();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0);
        if (append) {
            if (loadingMore || next.isEmpty()) return;
            loadingMore = true; pagingFailed = false; updatePaging();
        } else {
            if (task != null) task.cancel(true);
            loadingMore = false; pagingFailed = false;
            pendingUrl = address; busy = true; message = tr("Открываем каталог…", "Opening catalog…"); render();
        }
        int token = ++generation;
        boolean automatic = prefs.getString("catalog", "auto").equals("auto");
        task = worker.submit(() -> {
            try {
                Flibusta.Loaded loaded = Flibusta.loadMirrors(address, automatic ? Flibusta.mirrors(prefs) : Collections.singletonList(base()), (host, attempt) ->
                    runOnUiThread(() -> { if (valid(token) && !append && loadingLabel != null) loadingLabel.setText(tr("Подключаемся к ", "Connecting to ") + host + (attempt > 1 ? tr("\nПовторная попытка ", "\nRetry ") + (attempt - 1) : "")); }));
                if (automatic) prefs.edit().putString("catalog.last", loaded.base()).apply();
                runOnUiThread(() -> {
                    if (!valid(token)) return;
                    if (!append) { entries.clear(); loadedPages.clear(); currentUrl = loaded.url(); pendingUrl = ""; catalogItem = -1; catalogTop = 0; }
                    loadedPages.add(address); loadedPages.add(loaded.url());
                    Set<String> existing = new HashSet<>(); for (Flibusta.Entry entry : entries) existing.add(entryKey(entry));
                    for (Flibusta.Entry entry : loaded.feed().entries()) if (existing.add(entryKey(entry))) entries.add(entry);
                    next = loaded.feed().next(); if (loadedPages.contains(next)) next = "";
                    feedTitle = loaded.feed().title(); busy = false; loadingMore = false; pagingFailed = false;
                    message = feedTitle + (entries.isEmpty() ? "" : " · " + entries.size());
                    if (append) { if (resultStatus != null) resultStatus.setText(message); if (adapter != null) adapter.notifyDataSetChanged(); updatePaging(); fillViewport(); }
                    else render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!valid(token)) return; busy = false; loadingMore = false;
                    if (append) { pagingFailed = true; updatePaging(); }
                    else { pendingUrl = ""; message = tr("Не удалось открыть каталог. Проверьте сеть или смените адрес.", "Catalog unavailable. Check your connection or change the address."); render(); error(error); }
                });
            }
        });
    }
    private static String entryKey(Flibusta.Entry entry) { return entry.url().isEmpty() ? entry.title() + "\n" + entry.author() : entry.url(); }
    private void attachPaging(ListView list) {
        resultsList = list;
        pagingFooter = row(); pagingFooter.setGravity(Gravity.CENTER); pagingFooter.setPadding(dp(12), dp(12), dp(12), dp(16));
        pagingSpinner = new ProgressBar(this); pagingSpinner.setIndeterminateTintList(ColorStateList.valueOf(theme.accent()));
        pagingFooter.addView(pagingSpinner, new LinearLayout.LayoutParams(dp(24), dp(24))); space(pagingFooter);
        pagingLabel = text("", 13, theme.muted()); pagingLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); pagingFooter.addView(pagingLabel);
        list.addFooterView(pagingFooter, null, false); updatePaging();
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            boolean scrolling;
            public void onScrollStateChanged(AbsListView view, int state) {
                scrolling = state != SCROLL_STATE_IDLE;
                if (state == SCROLL_STATE_TOUCH_SCROLL && !loadingMore) pagingFailed = false;
                if (scrolling) maybeLoad(view);
            }
            public void onScroll(AbsListView view, int first, int visible, int total) { if (scrolling) maybeLoad(view); }
            private void maybeLoad(AbsListView view) {
                if (!busy && !loadingMore && !pagingFailed && !next.isEmpty() && view.getLastVisiblePosition() >= view.getCount() - 4) load(next, true);
            }
        });
        fillViewport();
    }
    private void fillViewport() {
        ListView list = resultsList;
        if (list != null) list.postOnAnimation(() -> {
            if (!downloads && list == resultsList && list.isAttachedToWindow() && list.getHeight() > 0 && !busy && !loadingMore && !pagingFailed && !next.isEmpty()
                    && !list.canScrollVertically(1) && !list.canScrollVertically(-1)) load(next, true);
        });
    }
    private void updatePaging() {
        if (pagingFooter == null) return;
        pagingFooter.setVisibility(loadingMore || pagingFailed ? View.VISIBLE : View.GONE);
        pagingSpinner.setVisibility(loadingMore ? View.VISIBLE : View.GONE);
        pagingLabel.setText(pagingFailed ? tr("Не удалось загрузить. Прокрутите, чтобы повторить.", "Could not load. Scroll again to retry.") : tr("Загружаем следующие книги…", "Loading more books…"));
    }
    private boolean valid(int token) { return !isDestroyed() && token == generation; }
    private void bookDetails(Flibusta.Entry entry) {
        ReaderDialogs dialog = new ReaderDialogs(this, theme).setTitle(entry.title()).setMessage(entry.author() + (entry.summary().isEmpty() ? "" : "\n\n" + entry.summary())
                + (entry.format().isEmpty() ? tr("\n\nУ этой записи нет прямой ссылки на EPUB или TXT.", "\n\nThis entry has no direct EPUB or TXT link.") : "\n\n" + entry.format().toUpperCase(Locale.ROOT)))
                .setNegativeButton(tr("Закрыть", "Close"), null);
        if (!entry.format().isEmpty()) dialog.setPositiveButton(tr("Скачать", "Download"), (d, w) -> download(entry));
        dialog.show();
    }
    private void download(Flibusta.Entry entry) {
        rememberQuery(); rememberScroll();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
        busy = true; message = tr("Создаём загрузку…", "Starting download…"); render(); int token = ++generation;
        task = worker.submit(() -> {
            try {
                String id = BookDownloads.create(this, entry);
                runOnUiThread(() -> { if (!valid(token)) return;
                    try { BookDownloadService.command(this, "start", id); busy = false; switchTab(true); }
                    catch (RuntimeException error) { busy = false; render(); error(error); }
                });
            } catch (Exception error) { runOnUiThread(() -> { if (!valid(token)) return; busy = false; render(); error(error); }); }
        });
    }
    private void catalogSettings() {
        rememberQuery(); rememberScroll();
        List<String> mirrors = Flibusta.mirrors(prefs);
        List<String> labels = new ArrayList<>(); labels.add(tr("Автоматически", "Automatic"));
        String selected = prefs.getString("catalog", "auto");
        for (String mirror : mirrors) labels.add(mirror.replace("https://", "").replace("/opds", ""));
        labels.add(tr("Добавить зеркало", "Add mirror"));
        if (mirrors.size() > Flibusta.MIRRORS.size()) labels.add(tr("Убрать зеркало", "Remove mirror"));
        int selectedIndex = selected.equals("auto") ? 0 : mirrors.indexOf(selected) + 1;
        if (selected.equals("auto") || mirrors.contains(selected)) labels.set(selectedIndex, "✓  " + labels.get(selectedIndex));
        new ReaderDialogs(this, theme).setTitle(tr("Зеркала каталога", "Catalog mirrors"))
                .setMessage(tr("Авто переключается между адресами и запоминает рабочий. Новое зеркало проверяется перед добавлением.", "Auto switches between addresses and remembers the working one. New mirrors are checked before adding."))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which <= mirrors.size()) { selectSource(which == 0 ? "auto" : mirrors.get(which - 1)); return; }
                    if (which == mirrors.size() + 1) { addMirror(); return; }
                    List<String> custom = new ArrayList<>(mirrors); custom.removeAll(Flibusta.MIRRORS);
                    new ReaderDialogs(this, theme).setTitle(tr("Убрать зеркало", "Remove mirror")).setItems(custom.toArray(new String[0]), (dialog, index) -> {
                        String removed = custom.remove(index); prefs.edit().putString("catalog.mirrors", String.join("\n", custom)).apply();
                        if (prefs.getString("catalog", "auto").equals(removed)) selectSource("auto");
                    }).show();
                }).show();
    }
    private void selectSource(String source) {
        generation++; if (task != null) task.cancel(true); loadingMore = false; busy = false; pendingUrl = "";
        prefs.edit().putString("catalog", source).apply(); currentUrl = ""; history.clear(); entries.clear(); loadedPages.clear();
        next = ""; message = ""; catalogItem = -1; catalogTop = 0; render();
    }
    private void addMirror() {
        EditText address = field("", "https://…/opds"); address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        AlertDialog form = new ReaderDialogs(this, theme).setTitle(tr("Новое зеркало", "New mirror"))
                .setMessage(tr("HTTPS-адрес Флибусты. Можно указать только домен: /opds добавится автоматически.", "Flibusta HTTPS address. The /opds path is added automatically to a bare domain."))
                .setView(address).setNegativeButton(tr("Отмена", "Cancel"), null).setPositiveButton(tr("Проверить и добавить", "Check & add"), null).show();
        Future<?>[] check = {null};
        form.setOnDismissListener(d -> { if (check[0] != null) check[0].cancel(true); });
        form.getButton(-1).setOnClickListener(v -> {
            final String url;
            try { url = Flibusta.catalog(address.getText().toString()); }
            catch (Exception error) { address.setError(error.getMessage()); return; }
            Button button = form.getButton(-1); button.setEnabled(false); button.setText(tr("Проверяем…", "Checking…")); address.setError(null);
            check[0] = worker.submit(() -> {
                try {
                    Flibusta.load(url);
                    runOnUiThread(() -> {
                        if (isDestroyed() || !form.isShowing()) return;
                        List<String> mirrors = Flibusta.mirrors(prefs); if (!mirrors.contains(url)) mirrors.add(url); mirrors.removeAll(Flibusta.MIRRORS);
                        prefs.edit().putString("catalog.mirrors", String.join("\n", mirrors)).apply();
                        form.dismiss(); selectSource("auto");
                        Toast.makeText(this, tr("Зеркало проверено и добавлено", "Mirror verified and added"), Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception error) { runOnUiThread(() -> {
                    if (isDestroyed() || !form.isShowing()) return;
                    button.setEnabled(true); button.setText(tr("Проверить и добавить", "Check & add"));
                    address.setError(tr("Каталог недоступен: ", "Catalog unavailable: ") + Objects.toString(error.getMessage(), ""));
                }); }
            });
        });
    }
    private void downloadsPage() {
        if (jobs.isEmpty()) { empty(tr("Книги скоро будут здесь", "Your books will appear here"), tr("Выберите книгу в каталоге и нажмите «Скачать». Готовая книга появится в библиотеке автоматически.", "Choose a book in the catalog and tap Download. Finished books are added to your library automatically.")); return; }
        ListView list = list(); downloadsList = list; adapter = new BaseAdapter() {
            public boolean hasStableIds() { return true; }
            public int getCount() { return jobs.size(); } public Object getItem(int i) { return jobs.get(i); } public long getItemId(int i) { return jobs.get(i).optString("id").hashCode(); }
            public View getView(int i, View convert, ViewGroup parent) {
                JSONObject job = jobs.get(i); String id = job.optString("id"), state = job.optString("state"); boolean active = BookDownloadService.active(id);
                if (!active && (state.equals("queued") || state.equals("downloading") || state.equals("importing"))) state = "cancelled";
                String identity = id + ":" + state + ":" + active;
                if (convert != null && identity.equals(convert.getTag())) { updateDownload(convert, job, active); return convert; }
                boolean complete = state.equals("complete"); LinearLayout card = card(); card.addView(text(job.optString("title"), 18, theme.ink()));
                if (!job.optString("author").isEmpty()) { gap(card, 6); card.addView(text(job.optString("author"), 14, theme.muted())); } gap(card, 12);
                String status = switch (state) { case "complete" -> tr("В библиотеке", "In your library"); case "cancelled" -> tr("Загрузка прервана", "Download interrupted"); case "error" -> tr("Не удалось скачать", "Download failed"); case "importing" -> tr("Добавляем в библиотеку…", "Adding to library…"); case "queued" -> tr("В очереди", "Queued"); default -> tr("Скачиваем", "Downloading"); };
                card.addView(text(status, 13, theme.accent())); gap(card, 8);
                ProgressBar progress = new ProgressBar(CatalogActivity.this, null, android.R.attr.progressBarStyleHorizontal);
                progress.setProgressTintList(ColorStateList.valueOf(theme.accent())); progress.setIndeterminateTintList(ColorStateList.valueOf(theme.accent()));
                progress.setTag("download-progress"); progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                card.addView(progress, new LinearLayout.LayoutParams(-1, dp(5))); gap(card, 8);
                TextView amount = text("", 12, theme.muted()); amount.setTag("download-amount"); card.addView(amount);
                if (state.equals("error")) { gap(card, 8); card.addView(text(job.optString("error"), 13, theme.muted())); }
                gap(card, 12); LinearLayout buttons = row();
                if (complete) buttons.addView(control(tr("Библиотека", "Library"), "library", true, () -> { if (isTaskRoot()) startActivity(new Intent(CatalogActivity.this, MainActivity.class)); setResult(RESULT_OK); finish(); }), weight());
                else buttons.addView(control(active ? tr("Отменить", "Cancel") : tr("Заново", "Retry"), active ? "close" : "download", false, () -> command(active ? "cancel" : "start", id)), weight());
                space(buttons); buttons.addView(control(tr("Убрать", "Remove"), "trash", false, () -> new ReaderDialogs(CatalogActivity.this, theme).setTitle(tr("Убрать загрузку?", "Remove download?"))
                        .setMessage(tr("Добавленная в библиотеку книга останется.", "The imported book will stay in your library.")).setNegativeButton(tr("Отмена", "Cancel"), null).setPositiveButton(tr("Убрать", "Remove"), (d, w) -> command("delete", id)).show()), weight());
                card.addView(buttons); View wrapper = wrap(card); wrapper.setTag(identity); updateDownload(wrapper, job, active); return wrapper;
            }
        }; list.setAdapter(adapter); content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setSelectionFromTop(downloadRow, downloadTop);
    }
    private void updateDownload(View card, JSONObject job, boolean active) {
        ProgressBar progress = card.findViewWithTag("download-progress");
        progress.setIndeterminate(active && job.optLong("total", -1) <= 0); progress.setMax(1000);
        progress.setProgress((int) Math.min(1000, job.optLong("done") * 1000 / Math.max(1, job.optLong("total"))));
        TextView amount = card.findViewWithTag("download-amount");
        amount.setText(job.optString("format").toUpperCase(Locale.ROOT) + " · " + BookDownloads.size(job.optLong("done")) + " / " + BookDownloads.size(job.optLong("total", -1))
                + (active && job.optLong("rate") > 0 ? " · " + BookDownloads.size(job.optLong("rate")) + "/s" : ""));
    }
    private void command(String action, String id) { try { BookDownloadService.command(this, action, id); } catch (RuntimeException error) { error(error); } }
    private void error(Throwable error) { new ReaderDialogs(this, theme).setTitle(tr("Не получилось", "Something went wrong")).setMessage(Objects.toString(error.getMessage(), error.toString())).setPositiveButton("OK", null).show(); }
    private void loading() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); LinearLayout page = takeHeader(); LinearLayout center = column(); center.setGravity(Gravity.CENTER); center.setPadding(dp(12), dp(32), dp(12), dp(32));
        ProgressBar spinner = new ProgressBar(this); spinner.setIndeterminateTintList(ColorStateList.valueOf(theme.accent())); center.addView(spinner, new LinearLayout.LayoutParams(dp(48), dp(48))); gap(center, 22);
        TextView status = text(message, 16, theme.ink()); loadingLabel = status; status.setGravity(Gravity.CENTER); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); center.addView(status); gap(center, 20); center.addView(control(tr("Отмена", "Cancel"), "close", false, this::cancel));
        page.addView(center, new LinearLayout.LayoutParams(-1, -2, 1)); scroll.addView(page); content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private void empty(String title, String detail) {
        ScrollView scroll = new ScrollView(this); LinearLayout page = takeHeader(); LinearLayout panel = column(); panel.setPadding(dp(12), dp(28), dp(12), dp(20));
        ImageView icon = new ImageView(this); icon.setImageDrawable(new ReaderIcon(this, downloads ? "download" : "book", theme.accent())); panel.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44))); gap(panel, 18);
        panel.addView(text(title, 22, theme.ink())); gap(panel, 10); panel.addView(text(detail, 15, theme.muted())); page.addView(panel); scroll.addView(page); content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private Button iconButton(String description, String icon, Runnable action) {
        Button button = control("", icon, false, action); button.setContentDescription(description); button.setTooltipText(description);
        button.setCompoundDrawablePadding(0); button.setPadding(dp(13), dp(12), dp(13), dp(12));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        button.setBackground(theme.feedback(shape(theme.background(), 14), getResources().getDisplayMetrics().density)); return button;
    }
    private Button textAction(String label, String icon, Runnable action) {
        Button button = control(label, icon, false, action); button.setTextColor(theme.muted());
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(new ReaderIcon(this, icon, theme.muted()), null, null, null);
        button.setBackground(theme.feedback(shape(theme.background(), 12), getResources().getDisplayMetrics().density));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); return button;
    }
    private View navItem(String label, String icon, boolean selected, Runnable action) {
        Button button = control(label, icon, selected, action);
        int color = selected ? theme.onAccent() : theme.muted();
        button.setTextColor(color); button.setSelected(selected); button.setGravity(Gravity.CENTER);
        button.setTextSize(13); button.setMinHeight(dp(64)); button.setMinimumHeight(dp(64));
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, new ReaderIcon(this, icon, color), null, null);
        button.setCompoundDrawablePadding(dp(4));
        return button;
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout card() { LinearLayout view = column(); view.setPadding(dp(16), dp(16), dp(16), dp(16)); view.setBackground(shape(theme.surface(), 20)); return view; }
    private View wrap(View child) { LinearLayout wrapper = column(); wrapper.setPadding(0, 0, 0, dp(12)); wrapper.addView(child, match()); return wrapper; }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(3), 1); return view; }
    private Button control(String label, String icon, boolean accent, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setTextSize(14); button.setAllCaps(false); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(12), dp(10), dp(12), dp(10));
        int ink = accent ? theme.onAccent() : theme.ink(); button.setTextColor(ink); button.setStateListAnimator(null);
        button.setBackground(theme.feedback(shape(accent ? theme.accent() : theme.surface(), 16), getResources().getDisplayMetrics().density));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(new ReaderIcon(this, icon, ink), null, null, null); button.setCompoundDrawablePadding(dp(8)); button.setOnClickListener(v -> action.run()); return button;
    }
    private EditText field(String value, String hint) { EditText view = new EditText(this); view.setSingleLine(); view.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI); view.setText(value); view.setHint(hint); view.setContentDescription(hint); view.setTextSize(16); view.setTextColor(theme.ink()); view.setHintTextColor(theme.muted()); view.setPadding(dp(14), dp(12), dp(14), dp(12)); view.setMinHeight(dp(52)); view.setBackground(theme.feedback(shape(theme.surface(), 16), getResources().getDisplayMetrics().density)); return view; }
    private LinearLayout takeHeader() {
        LinearLayout header = column();
        while (root.getChildAt(2) != content) { View view = root.getChildAt(2); root.removeViewAt(2); header.addView(view); }
        while (content.getChildCount() > 0) { View view = content.getChildAt(0); content.removeViewAt(0); header.addView(view); }
        return header;
    }
    private ListView list() {
        ListView view = new ListView(this); view.setDivider(null); view.setSelector(new ColorDrawable(Color.TRANSPARENT)); view.setCacheColorHint(Color.TRANSPARENT);
        view.setItemsCanFocus(true); view.addHeaderView(takeHeader(), null, false); return view;
    }
    private GradientDrawable shape(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(-1, -2); }
    private void gap(LinearLayout parent, int size) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size))); }
    private void space(LinearLayout parent) { parent.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String tr(String ru, String en) { return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru") ? ru : en; }
}

package com.mjreader;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Downloads persist in Android's DownloadManager across navigation and process death. */
public class UpdateActivity extends Activity {
    private ReaderOptions theme;
    private TextView status, available, details;
    private ScrollView page;
    private LinearLayout transfer;
    private ProgressBar progress;
    private Button action, cancel, check;
    private AppUpdates.Release release;
    private String message = "";
    private boolean busy, checking, polling, resumed, ready, failed;
    private int percent = -1;
    private int generation;
    private long verifiedId = -1;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (prefs, key) -> {
        if (("release".equals(key) || "checked".equals(key)) && !busy && downloadId() < 0 && !isDestroyed()) {
            release = AppUpdates.cached(this);
            if (release != null && AppUpdates.compare(release.version(), AppUpdates.version(this)) <= 0) release = null;
            message = release == null ? tr("Установлена актуальная версия", "You are up to date") : tr("Доступно обновление", "Update available"); update();
        }
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { poll(); if (resumed) handler.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        theme = ReaderOptions.load(getSharedPreferences("reader", 0), null);
        release = AppUpdates.cached(this);
        try { if (release != null && AppUpdates.compare(release.version(), AppUpdates.version(this)) <= 0) release = null; } catch (Exception ignored) { release = null; }
        message = release == null ? tr("Проверьте, доступна ли новая версия.", "Check for a new version.") : tr("Доступно обновление", "Update available");
        render();
        AppUpdates.prefs(this).registerOnSharedPreferenceChangeListener(changed);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; handler.post(refresh); }
    @Override protected void onPause() { resumed = false; handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); AppUpdates.prefs(this).unregisterOnSharedPreferenceChangeListener(changed); super.onDestroy(); }
    private String tr(String ru, String en) { return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru") ? ru : en; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void render() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(theme.background());
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(theme.background()));
        getWindow().getDecorView().setSystemUiVisibility(theme.light() ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int gutter = Math.max(16, (getResources().getConfiguration().screenWidthDp - 650) / 2);
            view.setPadding(dp(gutter) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                    dp(gutter) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom()); return insets;
        });
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button(tr("Назад", "Back"), false, this::finish);
        back.setText(""); back.setContentDescription(tr("Назад", "Back")); back.setTooltipText(tr("Назад", "Back"));
        back.setPadding(dp(12), 0, dp(12), 0);
        back.setBackground(theme.feedback(shape(theme.background(), 16), getResources().getDisplayMetrics().density));
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = heading(tr("Обновления", "Updates"), 28); title.setPadding(dp(8), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(header);
        ScrollView scroll = new ScrollView(this); scroll.setId(android.R.id.list); page = scroll; root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = column(); content.setPadding(0, dp(8), 0, dp(8)); scroll.addView(content);
        LinearLayout installed = card(content);
        installed.setPadding(dp(16), dp(16), dp(16), dp(16));
        installed.setBackground(theme.feature(getResources().getDisplayMetrics().density, 20));
        LinearLayout identity = new LinearLayout(this); identity.setGravity(Gravity.CENTER_VERTICAL);
        ImageView appIcon = new ImageView(this); appIcon.setImageDrawable(new ReaderIcon(this, "update", theme.onSelection()));
        appIcon.setPadding(dp(12), dp(12), dp(12), dp(12)); appIcon.setBackground(shape(theme.selection(), 12));
        appIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        identity.addView(appIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout version = column(); version.setPadding(dp(12), 0, 0, 0);
        version.addView(heading("MJReader", 22));
        version.addView(text(tr("Установлена версия ", "Installed version ") + AppUpdates.version(this), 14, theme.muted()));
        identity.addView(version, new LinearLayout.LayoutParams(0, -2, 1)); installed.addView(identity);
        status = text("", 16, theme.muted()); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(0, dp(12), 0, dp(12)); content.addView(status);
        transfer = card(content);
        available = heading("", 18); available.setPadding(0, 0, 0, dp(8)); transfer.addView(available);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(theme.accent()));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(theme.background()));
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams track = new LinearLayout.LayoutParams(-1, dp(4)); track.topMargin = dp(8); track.bottomMargin = dp(8);
        transfer.addView(progress, track);
        action = button("", true, this::act); transfer.addView(action);
        cancel = button(tr("Отменить загрузку", "Cancel download"), false, () -> {
            busy = true; generation++; update();
            AppUpdates.WORKER.execute(() -> {
                try {
                    AppUpdates.cancel(getApplicationContext());
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        busy = false; ready = false; failed = false; verifiedId = -1; percent = -1;
                        message = tr("Загрузка отменена", "Download cancelled"); update();
                    });
                } catch (RuntimeException error) {
                    error(tr("Не удалось отменить загрузку", "Could not cancel download"));
                }
            });
        }); transfer.addView(cancel);
        details = text("", 15, theme.muted()); details.setPadding(0, dp(8), 0, dp(12)); transfer.addView(details);
        LinearLayout checks = card(content);
        TextView checkHeading = heading(tr("Проверка обновлений", "Update checks"), 18);
        checkHeading.setPadding(0, 0, 0, dp(8));
        checks.addView(checkHeading);
        Button automatic = button(tr("Проверять автоматически", "Check automatically"), false, () -> {});
        automatic.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        automatic.setSelected(AppUpdates.prefs(this).getBoolean("automatic", true));
        automatic.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, theme.toggleIcon(this, automatic.isSelected()), null);
        automatic.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName("android.widget.Switch"); info.setCheckable(true); info.setChecked(host.isSelected());
            }
        });
        automatic.setOnClickListener(v -> {
            automatic.setSelected(!automatic.isSelected());
            automatic.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, theme.toggleIcon(this, automatic.isSelected()), null);
            AppUpdates.prefs(this).edit().putBoolean("automatic", automatic.isSelected()).apply();
        }); checks.addView(automatic);
        TextView hint = text(tr("Проверка раз в сутки при открытии библиотеки. Установку подтверждает Android.",
                "Checks once a day when you open the library. Android asks you to confirm installation."), 14, theme.muted());
        hint.setPadding(dp(12), dp(8), dp(12), dp(8)); hint.setBackground(shape(theme.surface(), 12));
        checks.addView(hint);
        check = button(tr("Проверить обновления", "Check for updates"), false, this::checkNow); checks.addView(check);
        Button releases = button("GitHub Releases", false, () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdates.RELEASES))); }
            catch (ActivityNotFoundException e) { message = tr("Браузер недоступен", "Browser unavailable"); update(); }
        }); checks.addView(releases);
        setContentView(root); root.requestApplyInsets();
        ReaderOptions.observeWindow(root, () -> { int y = scroll.getScrollY(); render(); page.post(() -> page.scrollTo(0, y)); });
        update();
    }
    private LinearLayout column() {
        LinearLayout group = new LinearLayout(this); group.setOrientation(LinearLayout.VERTICAL); return group;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout group = column(); group.setPadding(dp(16), dp(16), dp(16), dp(8));
        group.setBackground(shape(theme.surface(), 20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(12);
        parent.addView(group, params); return group;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(radius)); return background;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private TextView heading(String value, int size) {
        TextView heading = text(value, size, theme.ink()); heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        return heading;
    }
    private Button button(String label, boolean primary, Runnable click) {
        Button button = new Button(this); button.setText(label); button.setTextSize(16); button.setAllCaps(false); button.setMinHeight(dp(primary ? 52 : 48));
        button.setTextColor(primary ? theme.onAccent() : theme.ink()); button.setPadding(dp(16), dp(12), dp(16), dp(12));
        String icon = label.equals(tr("Назад", "Back")) ? "back"
                : label.equals(tr("Проверить обновления", "Check for updates")) ? "refresh"
                : label.equals(tr("Отменить загрузку", "Cancel download")) ? "close"
                : label.equals("GitHub Releases") ? "external" : null;
        if (icon != null) button.setCompoundDrawablesRelativeWithIntrinsicBounds(new ReaderIcon(this, icon, primary ? theme.onAccent() : theme.ink()), null, null, null);
        button.setCompoundDrawablePadding(dp(12));
        button.setStateListAnimator(null); button.setElevation(0);
        button.setBackground(theme.feedback(shape(primary ? theme.accent() : theme.surfaceHigh(), 16), getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(8); button.setLayoutParams(params);
        button.setOnClickListener(v -> click.run()); return button;
    }
    private long downloadId() { return AppUpdates.prefs(this).getLong("download", -1); }
    private void update() {
        if (status == null) return;
        if (!status.getText().toString().equals(message)) status.setText(message);
        status.setTextColor(failed ? theme.error() : theme.muted());
        boolean downloading = downloadId() >= 0;
        transfer.setVisibility(release != null || downloading ? View.VISIBLE : View.GONE);
        available.setVisibility(release == null ? View.GONE : View.VISIBLE);
        available.setText(release == null ? "" : "MJReader " + release.version() + " · " + android.text.format.Formatter.formatShortFileSize(this, release.size()));
        String description = release == null ? "" : release.notes();
        if (!details.getText().toString().equals(description)) details.setText(description);
        details.setVisibility(description.isBlank() ? View.GONE : View.VISIBLE);
        progress.setVisibility(percent < 0 || ready || failed ? View.GONE : View.VISIBLE);
        if (percent >= 0) progress.setProgress(percent);
        action.setText(ready ? tr("Установить обновление", "Install update") : failed ? tr("Повторить загрузку", "Retry download") : tr("Скачать обновление", "Download update"));
        action.setCompoundDrawablesRelativeWithIntrinsicBounds(new ReaderIcon(this, ready ? "check" : "download", theme.onAccent()), null, null, null);
        action.setVisibility(release != null && (!downloading || ready || failed) ? View.VISIBLE : View.GONE);
        action.setEnabled(!busy);
        cancel.setText(ready ? tr("Убрать скачанный APK", "Remove downloaded APK") : tr("Отменить загрузку", "Cancel download"));
        cancel.setVisibility(downloading ? View.VISIBLE : View.GONE); cancel.setEnabled(!busy);
        check.setText(checking ? tr("Проверяем…", "Checking…") : tr("Проверить обновления", "Check for updates"));
        check.setEnabled(!busy && !downloading);
    }
    private void checkNow() {
        checking = true; busy = true; generation++; message = tr("Проверяем GitHub Releases…", "Checking GitHub Releases…"); update();
        Context context = getApplicationContext();
        AppUpdates.WORKER.execute(() -> {
            try {
                AppUpdates.Release found = AppUpdates.check(context);
                runOnUiThread(() -> { if (isDestroyed()) return; checking = false; busy = false; failed = false; release = found;
                    message = found == null ? tr("Установлена актуальная версия", "You are up to date") : tr("Доступно обновление", "Update available"); update(); });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    checking = false; busy = false;
                    message = tr("Не удалось проверить обновления. Проверьте сеть или откройте GitHub Releases.",
                            "Could not check for updates. Check your connection or open GitHub Releases.");
                    update();
                });
            }
        });
    }
    private void act() {
        if (release == null || busy) return;
        AppUpdates.Release selected = release; busy = true; generation++; update();
        Context context = getApplicationContext(); boolean installing = ready;
        AppUpdates.WORKER.execute(() -> {
            try {
                if (installing) {
                    AppUpdates.verify(context, selected);
                    Uri uri = context.getSystemService(DownloadManager.class).getUriForDownloadedFile(downloadId());
                    if (uri == null) throw new java.io.IOException("Missing download");
                    runOnUiThread(() -> { if (isDestroyed()) return; busy = false; update(); install(uri); });
                } else {
                    AppUpdates.download(context, selected);
                    runOnUiThread(() -> { if (isDestroyed()) return; busy = false; failed = false; verifiedId = -1; percent = -1;
                        message = tr("Загрузка… Можно вернуться к чтению.", "Downloading… You can return to reading."); update(); poll(); });
                }
            } catch (Exception e) { error(tr("Не удалось подготовить обновление. Повторите загрузку.", "Could not prepare the update. Download it again.")); }
        });
    }
    private void install(Uri uri) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            new ReaderDialogs(this, theme).setTitle(tr("Разрешить обновление", "Allow updates"))
                    .setMessage(tr("Разрешите установку из MJReader в настройках Android, затем вернитесь и нажмите «Установить обновление».",
                            "Allow installation from MJReader in Android settings, then return and tap Install update."))
                    .setNegativeButton(tr("Позже", "Later"), null).setPositiveButton(tr("Настройки Android", "Android settings"), (d, w) -> {
                        try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
                        catch (ActivityNotFoundException e) { message = tr("Настройки установки недоступны", "Installation settings unavailable"); update(); }
                    }).show(); return;
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
        catch (ActivityNotFoundException | SecurityException e) { message = tr("Установщик Android недоступен", "Android installer unavailable"); update(); }
    }
    private void poll() {
        long id = downloadId(); if (id < 0 || polling || busy || id == verifiedId || failed) return;
        polling = true; int request = generation; Context context = getApplicationContext();
        AppUpdates.WORKER.execute(() -> {
            String result; boolean complete = false, failure = false; AppUpdates.Release pending = null;
            int downloaded = -1;
            try {
                pending = AppUpdates.decode(AppUpdates.prefs(context).getString("pending", ""));
                if (AppUpdates.compare(pending.version(), AppUpdates.version(context)) <= 0) {
                    AppUpdates.cancel(context); result = tr("Обновление установлено", "Update installed"); pending = null;
                } else try (Cursor cursor = context.getSystemService(DownloadManager.class).query(new DownloadManager.Query().setFilterById(id))) {
                    if (cursor == null || !cursor.moveToFirst()) throw new java.io.IOException("Download missing");
                    int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    if (done > pending.size()) { AppUpdates.cancel(context); throw new java.io.IOException("APK too large"); }
                    if (done >= 0 && state != DownloadManager.STATUS_PENDING) downloaded = (int) (done * 100 / pending.size());
                    if (state == DownloadManager.STATUS_SUCCESSFUL) {
                        runOnUiThread(() -> {
                            if (isDestroyed() || request != generation) return;
                            message = tr("Проверяем APK…", "Verifying APK…"); update();
                        });
                        AppUpdates.verify(context, pending); complete = true; result = tr("Обновление проверено и готово к установке", "Update verified and ready to install");
                    } else if (state == DownloadManager.STATUS_FAILED) { failure = true; result = tr("Загрузка не удалась. Попробуйте снова.", "Download failed. Please retry."); }
                    else if (state == DownloadManager.STATUS_PAUSED) result = tr("Загрузка ожидает сеть или повторную попытку…", "Waiting for a connection or retry…");
                    else result = downloaded < 0 ? tr("Ожидаем загрузку…", "Waiting to download…")
                            : tr("Загрузка: ", "Downloading: ") + downloaded + "%";
                }
            } catch (Exception e) { failure = true; result = tr("Проверка обновления не пройдена. Повторите загрузку.", "Update verification failed. Download it again."); }
            String label = result; boolean verified = complete, rejected = failure; AppUpdates.Release selected = pending; int done = downloaded;
            runOnUiThread(() -> { polling = false; if (isDestroyed() || request != generation) return;
                if (selected != null || !rejected) release = selected;
                ready = verified; failed = rejected; percent = done;
                if (verified) verifiedId = id; message = label; update(); });
        });
    }
    private void error(String label) { runOnUiThread(() -> { if (isDestroyed()) return; busy = false; ready = false; failed = true; message = label; update(); }); }
}

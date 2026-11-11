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
    private TextView status, details;
    private ScrollView page;
    private Button action, cancel, check;
    private AppUpdates.Release release;
    private String message = "";
    private boolean busy, polling, resumed, ready, failed;
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
        root.addView(button(tr("Назад", "Back"), false, this::finish));
        ScrollView scroll = new ScrollView(this); scroll.setId(android.R.id.list); page = scroll; root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content);
        content.addView(text(tr("Обновления", "Updates"), 30));
        content.addView(text(tr("Установлена версия ", "Installed version ") + AppUpdates.version(this), 15));
        status = text("", 18); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); content.addView(status);
        details = text("", 15); content.addView(details);
        action = button("", true, this::act); content.addView(action);
        cancel = button(tr("Отменить загрузку", "Cancel download"), false, () -> {
            busy = true; generation++; update();
            AppUpdates.WORKER.execute(() -> { AppUpdates.cancel(getApplicationContext()); runOnUiThread(() -> {
                if (isDestroyed()) return; busy = false; ready = false; failed = false; verifiedId = -1;
                message = tr("Загрузка отменена", "Download cancelled"); update();
            }); });
        }); content.addView(cancel);
        check = button(tr("Проверить обновления", "Check for updates"), false, this::checkNow); content.addView(check);
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
        }); content.addView(automatic);
        content.addView(text(tr("Проверка раз в сутки при открытии библиотеки. Загрузка — только по вашему нажатию; установку подтверждает Android.",
                "Checks once a day when opening the library. Downloads start only when you tap; Android asks you to confirm installation."), 14));
        setContentView(root); root.requestApplyInsets();
        ReaderOptions.observeWindow(root, () -> { int y = scroll.getScrollY(); render(); page.post(() -> page.scrollTo(0, y)); });
        update();
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(theme.ink()); view.setPadding(0, dp(12), 0, dp(12)); return view;
    }
    private Button button(String label, boolean primary, Runnable click) {
        Button button = new Button(this); button.setText(label); button.setTextSize(16); button.setAllCaps(false); button.setMinHeight(dp(48));
        button.setTextColor(primary ? theme.onAccent() : theme.ink()); button.setPadding(dp(16), dp(12), dp(16), dp(12));
        String icon = label.equals(tr("Назад", "Back")) ? "back" : label.equals(tr("Проверить обновления", "Check for updates")) ? "history" : label.equals(tr("Отменить загрузку", "Cancel download")) ? "close" : null;
        if (icon != null) button.setCompoundDrawablesRelativeWithIntrinsicBounds(new ReaderIcon(this, icon, primary ? theme.onAccent() : theme.ink()), null, null, null);
        button.setCompoundDrawablePadding(dp(12));
        button.setStateListAnimator(null); button.setElevation(0);
        GradientDrawable background = new GradientDrawable(); background.setColor(primary ? theme.accent() : theme.surface()); background.setCornerRadius(dp(16));
        button.setBackground(theme.feedback(background, getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(8); button.setLayoutParams(params);
        button.setOnClickListener(v -> click.run()); return button;
    }
    private long downloadId() { return AppUpdates.prefs(this).getLong("download", -1); }
    private void update() {
        if (status == null) return;
        if (!status.getText().toString().equals(message)) status.setText(message);
        String description = release == null ? "" : "MJReader " + release.version() + "\n\n" + release.notes();
        if (!details.getText().toString().equals(description)) details.setText(description);
        details.setVisibility(release == null ? View.GONE : View.VISIBLE);
        action.setText(ready ? tr("Установить обновление", "Install update") : failed ? tr("Повторить загрузку", "Retry download") : tr("Скачать обновление", "Download update"));
        action.setCompoundDrawablesRelativeWithIntrinsicBounds(new ReaderIcon(this, ready ? "check" : "download", theme.onAccent()), null, null, null);
        action.setVisibility(release == null ? View.GONE : View.VISIBLE);
        action.setEnabled(!busy && (downloadId() < 0 || ready || failed));
        cancel.setVisibility(downloadId() >= 0 ? View.VISIBLE : View.GONE); cancel.setEnabled(!busy);
        check.setEnabled(!busy && downloadId() < 0);
    }
    private void checkNow() {
        busy = true; generation++; message = tr("Проверяем GitHub Releases…", "Checking GitHub Releases…"); update();
        Context context = getApplicationContext();
        AppUpdates.WORKER.execute(() -> {
            try {
                AppUpdates.Release found = AppUpdates.check(context);
                runOnUiThread(() -> { if (isDestroyed()) return; busy = false; release = found;
                    message = found == null ? tr("Установлена актуальная версия", "You are up to date") : tr("Доступно обновление", "Update available"); update(); });
            } catch (Exception e) { error(tr("Не удалось проверить обновления. Проверьте сеть и попробуйте позже.", "Could not check for updates. Check your connection and try again later.")); }
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
                    runOnUiThread(() -> { if (isDestroyed()) return; busy = false; failed = false; verifiedId = -1;
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
            try {
                pending = AppUpdates.decode(AppUpdates.prefs(context).getString("pending", ""));
                if (AppUpdates.compare(pending.version(), AppUpdates.version(context)) <= 0) {
                    AppUpdates.cancel(context); result = tr("Обновление установлено", "Update installed"); pending = null;
                } else try (Cursor cursor = context.getSystemService(DownloadManager.class).query(new DownloadManager.Query().setFilterById(id))) {
                    if (cursor == null || !cursor.moveToFirst()) throw new java.io.IOException("Download missing");
                    int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    if (done > AppUpdates.MAX_APK) { AppUpdates.cancel(context); throw new java.io.IOException("APK too large"); }
                    if (state == DownloadManager.STATUS_SUCCESSFUL) {
                        AppUpdates.verify(context, pending); complete = true; result = tr("Обновление проверено и готово к установке", "Update verified and ready to install");
                    } else if (state == DownloadManager.STATUS_FAILED) { failure = true; result = tr("Загрузка не удалась. Попробуйте снова.", "Download failed. Please retry."); }
                    else if (state == DownloadManager.STATUS_PAUSED) result = tr("Загрузка ожидает сеть или повторную попытку…", "Waiting for a connection or retry…");
                    else result = tr("Загрузка: ", "Downloading: ") + Math.min(100, done * 100 / pending.size()) + "%";
                }
            } catch (Exception e) { failure = true; result = tr("Проверка обновления не пройдена. Повторите загрузку.", "Update verification failed. Download it again."); }
            String label = result; boolean verified = complete, rejected = failure; AppUpdates.Release selected = pending;
            runOnUiThread(() -> { polling = false; if (isDestroyed() || request != generation) return;
                release = selected; ready = verified; failed = rejected; if (verified) verifiedId = id; message = label; update(); });
        });
    }
    private void error(String label) { runOnUiThread(() -> { if (isDestroyed()) return; busy = false; ready = false; failed = true; message = label; update(); }); }
}

package com.mjreader;

import android.content.SharedPreferences;
import org.json.JSONObject;

final class ReaderOptions {
    final JSONObject values;
    ReaderOptions(String json) {
        JSONObject parsed;
        try { parsed = new JSONObject(json); } catch (Exception e) { parsed = new JSONObject(); }
        values = parsed;
    }
    int number(String key, int fallback) { return values.optInt(key, fallback); }
    boolean flag(String key, boolean fallback) { return values.optBoolean(key, fallback); }
    void put(String key, Object value) {
        try { values.put(key, value); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    static ReaderOptions load(SharedPreferences prefs, String book) {
        return new ReaderOptions(prefs.getString(book != null && prefs.contains("options." + book) ? "options." + book : "options", "{}"));
    }
    int background() {
        return switch (number("theme", 0)) { case 1 -> 0xFF242033; case 2 -> 0xFFFAFAF7; case 3 -> 0xFFF3E7CF; default -> 0xFF0C0E15; };
    }
    boolean light() { return number("theme", 0) >= 2; }
    int surface() { return switch (number("theme", 0)) { case 1 -> 0xFF342E46; case 2 -> 0xFFEAEDE6; case 3 -> 0xFFE6D8BC; default -> 0xFF191E29; }; }
    int ink() { return light() ? 0xFF20251F : number("theme", 0) == 1 ? 0xFFF3EEF7 : 0xFFF0F2EC; }
    int muted() { return light() ? 0xFF4D584E : number("theme", 0) == 1 ? 0xFFC3B8D2 : 0xFFABB5C5; }
    int accent() {
        int[] dark = {0xFFC7FF68, 0xFF8BD9FF, 0xFFD6B5FF, 0xFFFFCF82};
        int[] light = {0xFF345A05, 0xFF07577A, 0xFF64348A, 0xFF785000};
        return (light() ? light : dark)[Math.max(0, Math.min(3, number("accent", 0)))];
    }
    android.graphics.drawable.Drawable feedback(android.graphics.drawable.GradientDrawable normal, float density) {
        android.graphics.drawable.GradientDrawable pressed = (android.graphics.drawable.GradientDrawable) normal.getConstantState().newDrawable().mutate();
        int base = normal.getColor().getDefaultColor(), overlay = light() ? 0 : 255;
        pressed.setColor(android.graphics.Color.rgb(
                (int) (android.graphics.Color.red(base) * .88f + overlay * .12f),
                (int) (android.graphics.Color.green(base) * .88f + overlay * .12f),
                (int) (android.graphics.Color.blue(base) * .88f + overlay * .12f)));
        android.graphics.drawable.GradientDrawable focused = (android.graphics.drawable.GradientDrawable) normal.getConstantState().newDrawable().mutate();
        // Primary buttons need a contrasting ring too: accent-on-accent is invisible.
        focused.setStroke(Math.round(2 * density), base == accent() ? onAccent() : accent());
        android.graphics.drawable.GradientDrawable hovered = (android.graphics.drawable.GradientDrawable) pressed.getConstantState().newDrawable().mutate();
        android.graphics.drawable.GradientDrawable disabled = (android.graphics.drawable.GradientDrawable) normal.getConstantState().newDrawable().mutate();
        disabled.setAlpha(100);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, disabled);
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{android.R.attr.state_hovered}, hovered);
        states.addState(new int[]{}, normal);
        return states;
    }
    android.graphics.drawable.Drawable toggleIcon(android.content.Context context, boolean checked) {
        float density = context.getResources().getDisplayMetrics().density;
        return new android.graphics.drawable.Drawable() {
            final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override public int getIntrinsicWidth() { return Math.round(44 * density); }
            @Override public int getIntrinsicHeight() { return Math.round(28 * density); }
            @Override public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect b = getBounds();
                paint.setColor(checked ? accent() : muted());
                canvas.drawRoundRect(new android.graphics.RectF(b), 14 * density, 14 * density, paint);
                paint.setColor(checked ? onAccent() : background());
                canvas.drawCircle(checked ? b.right - 14 * density : b.left + 14 * density, b.centerY(), 10 * density, paint);
            }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }
    int onAccent() { return light() ? 0xFFFFFFFF : 0xFF0C0E15; }
    // Small window changes do not always recreate an Activity. Ignore IME-only resizes.
    static void observeWindow(android.view.View root, Runnable resize) {
        android.content.res.Configuration initial = root.getResources().getConfiguration();
        int[] size = {initial.screenWidthDp, initial.screenHeightDp};
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            android.content.res.Configuration current = view.getResources().getConfiguration();
            if (size[0] == current.screenWidthDp && size[1] == current.screenHeightDp) return;
            size[0] = current.screenWidthDp; size[1] = current.screenHeightDp;
            view.post(() -> { if (view.isAttachedToWindow()) resize.run(); });
        });
    }
}

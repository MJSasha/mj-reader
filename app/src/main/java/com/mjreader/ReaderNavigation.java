package com.mjreader;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.function.Consumer;

/** Shared bottom navigation for the library, OPDS catalog, and settings. */
final class ReaderNavigation {
    static final String TAG = "main-navigation";

    enum Tab {
        LIBRARY(R.string.library, "library"), SEARCH(R.string.search, "search"),
        DOWNLOADS(R.string.downloads, "download"), SETTINGS(R.string.settings, "settings");

        final int title;
        final String icon;
        Tab(int title, String icon) { this.title = title; this.icon = icon; }
    }

    static void add(LinearLayout parent, ReaderOptions theme, Tab selected, Consumer<Tab> navigate) {
        Context context = parent.getContext();
        var tabs = Tab.values();
        LinearLayout dock = new LinearLayout(context);
        dock.setTag(TAG);
        dock.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setBackground(shape(context, theme.navigationSurface(), 20));
        for (Tab tab : tabs) {
            Button button = item(context, theme, context.getString(tab.title), tab.icon, tab == selected, () -> navigate.accept(tab));
            button.setTag(tab);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(0, dp(context, 48), 1);
            if (tab != tabs[0]) itemParams.setMarginStart(dp(context, 8));
            dock.addView(button, itemParams);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(context, 8);
        parent.addView(dock, params);
        WindowInsets insets = parent.getRootWindowInsets();
        if (insets != null) updateInsets(parent, insets);
        parent.requestApplyInsets();
    }

    static GradientDrawable panel(Context context, ReaderOptions theme) {
        var shape = theme.feature(context.getResources().getDisplayMetrics().density, 20);
        shape.setStroke(dp(context, 1), theme.surfaceHigh());
        return shape;
    }

    static Button item(Context context, ReaderOptions theme, String title, String icon, boolean selected, Runnable click) {
        Button button = new Button(context);
        button.setContentDescription(title);
        button.setTooltipText(title);
        button.setMinWidth(dp(context, 48)); button.setMinimumWidth(dp(context, 48));
        button.setMinHeight(dp(context, 48)); button.setMinimumHeight(dp(context, 48));
        button.setStateListAnimator(null);
        button.setSelected(selected);
        float density = context.getResources().getDisplayMetrics().density;
        boolean vivid = selected && theme.vividSelection();
        var fill = vivid ? theme.vividSelection(density, 16)
                : shape(context, selected ? theme.selection() : Color.TRANSPARENT, 16);
        var indicator = new LayerDrawable(new Drawable[]{
                theme.feedback(fill, density),
                new ReaderIcon(context, icon, vivid ? 0xFFFFC2F7 : selected ? theme.onSelection() : theme.muted())});
        indicator.setLayerInset(0, dp(context, 2), dp(context, 3), dp(context, 2), dp(context, 3));
        indicator.setLayerSize(1, dp(context, 24), dp(context, 24));
        indicator.setLayerGravity(1, Gravity.CENTER);
        button.setBackground(indicator);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(v -> click.run());
        return button;
    }

    static void updateInsets(View root, WindowInsets insets) {
        View navigation = root.findViewWithTag(TAG);
        if (navigation == null) return;
        boolean keyboard = Build.VERSION.SDK_INT >= 30 ? insets.isVisible(WindowInsets.Type.ime())
                : insets.getSystemWindowInsetBottom() > insets.getStableInsetBottom();
        navigation.setVisibility(keyboard ? View.GONE : View.VISIBLE);
    }

    static int bottomPadding(Context context, int systemInset) {
        return Math.max(dp(context, 8), systemInset);
    }

    private static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    private static int dp(Context context, int size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }
}

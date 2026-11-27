package com.mjreader;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.function.Consumer;

/** Shared bottom navigation for the library, OPDS catalog, and settings. */
final class ReaderNavigation {
    static final String TAG = "main-navigation";
    private static final int ITEM_HEIGHT = 48;
    private static final int PANEL_PADDING = 4;
    private static final int GAP = 8;

    enum Tab {
        LIBRARY(R.string.library, "library"), SEARCH(R.string.search, "search"),
        DOWNLOADS(R.string.downloads, "download"), SETTINGS(R.string.settings, "settings");

        final int title;
        final String icon;
        Tab(int title, String icon) { this.title = title; this.icon = icon; }
    }

    static void add(LinearLayout parent, View body, View scrolling, ReaderOptions theme, Tab selected, Consumer<Tab> navigate) {
        Context context = parent.getContext();
        int index = parent.indexOfChild(body);
        ViewGroup.LayoutParams bodyParams = body.getLayoutParams();
        parent.removeViewAt(index);
        FrameLayout stage = new FrameLayout(context);
        stage.addView(body, new FrameLayout.LayoutParams(-1, -1));
        parent.addView(stage, index, bodyParams);
        var tabs = Tab.values();
        Dock dock = new Dock(context, scrolling);
        dock.setTag(TAG);
        int padding = dp(context, PANEL_PADDING);
        dock.setPadding(padding, padding, padding, padding);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setBackground(panel(context, theme));
        for (Tab tab : tabs) {
            Button button = item(context, theme, context.getString(tab.title), tab.icon, tab == selected, false, () -> navigate.accept(tab));
            button.setTag(tab);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(0, dp(context, ITEM_HEIGHT), 1);
            if (tab != tabs[0]) itemParams.setMarginStart(dp(context, 8));
            dock.addView(button, itemParams);
        }
        stage.addView(dock, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        dock.applyInsets(parent.getRootWindowInsets());
        parent.requestApplyInsets();
    }

    static GradientDrawable panel(Context context, ReaderOptions theme) {
        var shape = theme.feature(context.getResources().getDisplayMetrics().density, 20);
        shape.setStroke(dp(context, 1), theme.surfaceHigh());
        return shape;
    }

    static Button readingItem(Context context, ReaderOptions theme, String title, String icon, Runnable click) {
        return item(context, theme, title, icon, false, true, click);
    }

    private static Button item(Context context, ReaderOptions theme, String title, String icon, boolean selected, boolean reading, Runnable click) {
        Button button = new Button(context);
        button.setContentDescription(title);
        button.setTooltipText(title);
        button.setMinWidth(dp(context, 48)); button.setMinimumWidth(dp(context, 48));
        button.setMinHeight(dp(context, 48)); button.setMinimumHeight(dp(context, 48));
        button.setStateListAnimator(null);
        boolean localFeedback = reading || theme.light();
        if (localFeedback) button.setDefaultFocusHighlightEnabled(false);
        button.setSelected(selected);
        float density = context.getResources().getDisplayMetrics().density;
        boolean vivid = selected && theme.vividSelection();
        var fill = vivid ? theme.vividSelection(density, 16)
                : shape(context, selected ? theme.selection() : Color.TRANSPARENT, 16);
        var indicator = new LayerDrawable(new Drawable[]{
                localFeedback ? theme.tonalFeedback(fill, density) : theme.feedback(fill, density),
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
        Dock dock = root.findViewWithTag(TAG);
        if (dock != null) dock.applyInsets(insets);
    }

    private static final class Dock extends LinearLayout {
        private final View scrolling;
        private final int initialPadding;

        Dock(Context context, View scrolling) {
            super(context);
            this.scrolling = scrolling;
            initialPadding = scrolling.getPaddingBottom();
            if (scrolling instanceof ViewGroup group) group.setClipToPadding(false);
        }

        void applyInsets(WindowInsets insets) {
            boolean keyboard = insets != null && (Build.VERSION.SDK_INT >= 30 ? insets.isVisible(WindowInsets.Type.ime())
                    : insets.getSystemWindowInsetBottom() > insets.getStableInsetBottom());
            int bottom = bottomPadding(getContext(), insets == null ? 0 : insets.getSystemWindowInsetBottom());
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) getLayoutParams();
            if (params.bottomMargin != bottom) {
                params.bottomMargin = bottom;
                setLayoutParams(params);
            }
            setVisibility(keyboard ? GONE : VISIBLE);
            int reserve = keyboard ? 0 : bottom + dp(getContext(), ITEM_HEIGHT)
                    + 2 * dp(getContext(), PANEL_PADDING) + dp(getContext(), GAP);
            if (scrolling.getPaddingBottom() != initialPadding + reserve)
                scrolling.setPadding(scrolling.getPaddingLeft(), scrolling.getPaddingTop(), scrolling.getPaddingRight(), initialPadding + reserve);
        }
    }

    static int bottomPadding(Context context, int systemInset) {
        return Math.max(dp(context, GAP), systemInset);
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

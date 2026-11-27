package com.mjreader;

import android.app.AlertDialog;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Branded dialogs built from native views with the same color tokens as the reader. */
final class ReaderDialogs {
    private final Context context;
    private final ReaderOptions theme;
    private String title, message;
    private View custom, backdrop;
    private RectF spotlight;
    private CharSequence[] items;
    private boolean[] checked;
    private DialogInterface.OnClickListener itemClick;
    private int selectedItem = -1;
    private DialogInterface.OnMultiChoiceClickListener multiClick;
    private final Map<Integer, String> labels = new LinkedHashMap<>();
    private final Map<Integer, DialogInterface.OnClickListener> actions = new LinkedHashMap<>();

    ReaderDialogs(Context context, ReaderOptions theme) { this.context = context; this.theme = theme; }
    ReaderDialogs setTitle(String value) { title = value; return this; }
    ReaderDialogs setMessage(String value) { message = value; return this; }
    ReaderDialogs setBackdrop(View view, RectF anchor) { backdrop = view; spotlight = new RectF(anchor); return this; }
    ReaderDialogs setView(View value) { custom = value; return this; }
    ReaderDialogs setItems(CharSequence[] values, DialogInterface.OnClickListener click) {
        items = values; itemClick = click; return this;
    }
    ReaderDialogs setItems(CharSequence[] values, int selected, DialogInterface.OnClickListener click) {
        selectedItem = selected;
        return setItems(values, click);
    }
    ReaderDialogs setMultiChoiceItems(CharSequence[] values, boolean[] states, DialogInterface.OnMultiChoiceClickListener click) {
        items = values; checked = states.clone(); multiClick = click; return this;
    }
    ReaderDialogs setPositiveButton(String text, DialogInterface.OnClickListener action) { return action(-1, text, action); }
    ReaderDialogs setNegativeButton(String text, DialogInterface.OnClickListener action) { return action(-2, text, action); }
    ReaderDialogs setNeutralButton(String text, DialogInterface.OnClickListener action) { return action(-3, text, action); }
    ReaderDialogs setPositiveButton(int resource, DialogInterface.OnClickListener action) { return action(-1, context.getString(resource), action); }
    ReaderDialogs setNegativeButton(int resource, DialogInterface.OnClickListener action) { return action(-2, context.getString(resource), action); }
    private ReaderDialogs action(int id, String text, DialogInterface.OnClickListener action) {
        labels.put(id, text); actions.put(id, action); return this;
    }
    AlertDialog show() { AlertDialog dialog = create(); dialog.show(); return dialog; }
    AlertDialog create() {
        return new AlertDialog(context) {
            private final Map<Integer, Button> buttons = new LinkedHashMap<>();
            @Override public Button getButton(int which) { return buttons.get(which); }
            @Override protected void onCreate(Bundle state) {
                super.onCreate(state);
                LinearLayout page = new LinearLayout(context) {
                    @Override protected void onMeasure(int width, int height) {
                        int available = Math.min(MeasureSpec.getSize(height),
                                context.getResources().getDisplayMetrics().heightPixels - dp(64));
                        if (items != null) available = Math.min(available, dp(620));
                        super.onMeasure(width, MeasureSpec.makeMeasureSpec(Math.max(1, available), MeasureSpec.AT_MOST));
                    }
                };
                page.setOrientation(LinearLayout.VERTICAL);
                page.setPadding(dp(16), dp(12), dp(16), dp(16));
                GradientDrawable panel = shape(theme.surface(), 24);
                panel.setStroke(dp(1), theme.surfaceHigh());
                page.setBackground(panel);
                LinearLayout header = new LinearLayout(context);
                header.setGravity(Gravity.CENTER_VERTICAL); header.setBaselineAligned(false);
                header.setPaddingRelative(dp(4), 0, 0, 0);
                TextView heading = text(title == null ? "" : title, 22);
                heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                heading.setMaxLines(2); heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
                if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
                header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
                header.addView(new View(context), new LinearLayout.LayoutParams(dp(8), 1));
                // Only dismiss-only actions move to the header; saving/deleting stays explicit.
                int closeAction = labels.containsKey(-2) && actions.get(-2) == null && dismissLabel(labels.get(-2)) ? -2
                        : labels.size() == 1 && actions.get(-1) == null && "OK".equalsIgnoreCase(labels.get(-1)) ? -1 : 0;
                String closeLabel = closeAction == 0
                        ? (context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru") ? "Закрыть" : "Close")
                        : labels.get(closeAction);
                Button close = control("", false);
                close.setTag("dialog-close"); close.setContentDescription(closeLabel); close.setTooltipText(closeLabel);
                close.setMinWidth(dp(48)); close.setMinimumWidth(dp(48)); close.setPadding(0, 0, 0, 0);
                LayerDrawable background = new LayerDrawable(new Drawable[]{
                        theme.tonalFeedback(shape(theme.surfaceHigh(), 24), context.getResources().getDisplayMetrics().density),
                        new ReaderIcon(context, "close", theme.ink())});
                background.setLayerSize(1, dp(24), dp(24)); background.setLayerGravity(1, Gravity.CENTER);
                close.setBackground(background); close.setOnClickListener(v -> cancel());
                if (closeAction != 0) buttons.put(closeAction, close);
                header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
                page.addView(header, new LinearLayout.LayoutParams(-1, -2));
                gap(page, 8);
                LinearLayout body = column();
                ScrollView viewport = new ScrollView(context);
                viewport.setTag("dialog-scroll"); viewport.setClipToPadding(false);
                viewport.addView(body);
                page.addView(viewport, new LinearLayout.LayoutParams(-1, -2, 1));
                if (message != null) {
                    body.addView(text(message, 16));
                    if (custom != null || (items != null && items.length > 0)) gap(body, 12);
                }
                if (custom != null) body.addView(custom);
                if (items != null && items.length > 0) {
                    LinearLayout choices = column();
                    choices.setBackground(shape(theme.surfaceHigh(), 16));
                    choices.setClipToOutline(true);
                    body.addView(choices, new LinearLayout.LayoutParams(-1, -2));
                    for (int i = 0; i < items.length; i++) {
                        int index = i;
                        Button item = control(items[i].toString(), false);
                        item.setTag("dialog-item-" + i);
                        item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                        item.setMinHeight(dp(52));
                        styleItem(item, i == selectedItem || checked != null && checked[i]);
                        if (selectedItem >= 0 || checked != null) item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                            @Override public void onInitializeAccessibilityNodeInfo(View view, android.view.accessibility.AccessibilityNodeInfo info) {
                                super.onInitializeAccessibilityNodeInfo(view, info);
                                info.setClassName(checked == null ? RadioButton.class.getName() : CheckBox.class.getName());
                                info.setCheckable(true);
                                info.setChecked(view.isSelected());
                                info.setSelected(false);
                            }
                        });
                        item.setOnClickListener(view -> {
                            if (checked != null) {
                                checked[index] = !checked[index];
                                styleItem(item, checked[index]);
                                multiClick.onClick(this, index, checked[index]);
                            } else {
                                dismiss();
                                itemClick.onClick(this, index);
                            }
                        });
                        choices.addView(item, new LinearLayout.LayoutParams(-1, -2));
                        if (i == selectedItem) item.post(() -> viewport.scrollTo(0,
                                choices.getTop() + item.getTop() - (viewport.getHeight() - item.getHeight()) / 2));
                        if (i + 1 < items.length) {
                            View divider = new View(context);
                            divider.setBackgroundColor(theme.surface());
                            choices.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
                        }
                    }
                }
                int actionCount = labels.size() - (closeAction == 0 ? 0 : 1);
                if (actionCount > 0) gap(body, 16);
                var configuration = context.getResources().getConfiguration();
                boolean horizontal = actionCount == 2 && configuration.screenWidthDp >= 400 && configuration.fontScale < 1.6f;
                LinearLayout footer = column();
                if (horizontal) footer.setOrientation(LinearLayout.HORIZONTAL);
                body.addView(footer, new LinearLayout.LayoutParams(-1, -2));
                for (int id : new int[]{-2, -3, -1}) if (labels.containsKey(id) && id != closeAction) {
                    Button button = control(labels.get(id), id == -1);
                    buttons.put(id, button);
                    button.setOnClickListener(v -> {
                        DialogInterface.OnClickListener listener = actions.get(id);
                        if (listener != null) listener.onClick(this, id);
                        dismiss();
                    });
                    if (footer.getChildCount() > 0) footer.addView(new View(context), new LinearLayout.LayoutParams(horizontal ? dp(8) : 1, horizontal ? 1 : dp(8)));
                    footer.addView(button, horizontal ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2));
                }
                if (backdrop == null) setContentView(page);
                else {
                    page.setTag("note-card"); page.setClickable(true); page.setElevation(dp(12));
                    FrameLayout overlay = new FrameLayout(context) {
                        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                            backdrop.layout(0, 0, getWidth(), getHeight());
                            int[] origin = new int[2]; getLocationOnScreen(origin);
                            RectF anchor = new RectF(spotlight); anchor.offset(-origin[0], -origin[1]);
                            int top = getPaddingTop() + dp(16), bottom = getHeight() - getPaddingBottom() - dp(16);
                            int below = Math.max(0, bottom - (int) anchor.bottom - dp(16));
                            int above = Math.max(0, (int) anchor.top - dp(16) - top);
                            boolean after = below >= above;
                            int available = Math.max(above, below);
                            boolean compact = available < dp(144);
                            if (compact) available = Math.max(dp(96), (bottom - top) / 2);
                            int width = Math.min(getWidth() - dp(32) - getPaddingLeft() - getPaddingRight(), dp(480));
                            page.measure(MeasureSpec.makeMeasureSpec(Math.max(1, width), MeasureSpec.EXACTLY),
                                    MeasureSpec.makeMeasureSpec(Math.max(1, available), MeasureSpec.AT_MOST));
                            int x = Math.max(getPaddingLeft() + dp(16), Math.min(getWidth() - getPaddingRight() - dp(16) - width,
                                    (int) anchor.centerX() - width / 2));
                            int y = compact ? bottom - page.getMeasuredHeight()
                                    : after ? (int) anchor.bottom + dp(16) : (int) anchor.top - dp(16) - page.getMeasuredHeight();
                            y = Math.max(top, Math.min(bottom - page.getMeasuredHeight(), y));
                            page.layout(x, y, x + page.getMeasuredWidth(), y + page.getMeasuredHeight());
                        }
                    };
                    overlay.setOnApplyWindowInsetsListener((v, insets) -> {
                        v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                        return insets;
                    });
                    backdrop.setOnClickListener(v -> cancel());
                    backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    overlay.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
                    overlay.addView(page, new FrameLayout.LayoutParams(-1, -2));
                    setContentView(overlay);
                    if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                        page.setAlpha(0); page.setTranslationY(dp(8));
                        page.animate().alpha(1).translationY(0).setDuration(160).start();
                    }
                }
                Window window = getWindow();
                if (window != null) {
                    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    window.setLayout(Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(32), dp(560)), -2);
                    window.setDimAmount(.52f);
                    if (backdrop != null) {
                        window.setLayout(-1, -1); window.setDimAmount(0); window.setWindowAnimations(0);
                        window.getDecorView().setSystemUiVisibility(theme.light()
                                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
                    }
                    EditText input = firstInput(custom);
                    if (input != null) {
                        // AlertDialog's empty stock content marks the window as unsuitable for the IME.
                        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                        input.requestFocus();
                        input.post(() -> ((android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE))
                                .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
                    } else window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                }
            }
        };
    }
    private EditText firstInput(View view) {
        if (view instanceof EditText input) return input;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            EditText input = firstInput(group.getChildAt(i));
            if (input != null) return input;
        }
        return null;
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private void styleItem(Button item, boolean selected) {
        item.setSelected(selected);
        item.setTextColor(selected ? theme.onSelection() : theme.ink());
        item.setBackground(theme.tonalFeedback(shape(selected ? theme.selection() : Color.TRANSPARENT, 0),
                context.getResources().getDisplayMetrics().density));
        Drawable marker = null;
        if (selectedItem >= 0 || checked != null) {
            marker = selected ? new ReaderIcon(context, "check", theme.accent()) : new ColorDrawable(Color.TRANSPARENT);
            marker.setBounds(0, 0, dp(24), dp(24));
        }
        item.setCompoundDrawablesRelative(item.getCompoundDrawablesRelative()[0], null, marker, null);
    }
    private boolean dismissLabel(String label) {
        return label.equals(context.getString(android.R.string.cancel)) || switch (label) {
            case "Отмена", "Закрыть", "Готово", "Cancel", "Close", "Done" -> true;
            default -> false;
        };
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView text(String value, int size) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(theme.ink()); view.setLineSpacing(dp(3), 1); return view;
    }
    private void gap(LinearLayout parent, int size) { parent.addView(new View(context), new LinearLayout.LayoutParams(1, dp(size))); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private Button control(String label, boolean primary) {
        Button button = new Button(context);
        button.setText(label); button.setTextSize(16); button.setAllCaps(false);
        boolean destructive = switch (label) {
            case "Delete", "Удалить", "Move to Deleted", "Переместить в удалённые", "Remove download", "Убрать загрузку" -> true;
            default -> false;
        };
        int foreground = destructive ? theme.error() : primary ? theme.onAccent() : theme.ink();
        ReaderIcon icon = ReaderIcon.forLabel(context, label, foreground);
        if (icon != null) {
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        }
        button.setCompoundDrawablePadding(dp(12));
        button.setTextColor(foreground);
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setMinHeight(dp(48)); button.setStateListAnimator(null); button.setElevation(0);
        button.setBackground(theme.feedback(shape(primary && !destructive ? theme.accent() : theme.surfaceHigh(), 16), context.getResources().getDisplayMetrics().density));
        return button;
    }
}

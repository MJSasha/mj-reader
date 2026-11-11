package com.mjreader;

import android.app.AlertDialog;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
    private View custom;
    private CharSequence[] items;
    private boolean[] checked;
    private DialogInterface.OnClickListener itemClick;
    private DialogInterface.OnMultiChoiceClickListener multiClick;
    private final Map<Integer, String> labels = new LinkedHashMap<>();
    private final Map<Integer, DialogInterface.OnClickListener> actions = new LinkedHashMap<>();

    ReaderDialogs(Context context, ReaderOptions theme) { this.context = context; this.theme = theme; }
    ReaderDialogs setTitle(String value) { title = value; return this; }
    ReaderDialogs setMessage(String value) { message = value; return this; }
    ReaderDialogs setView(View value) { custom = value; return this; }
    ReaderDialogs setItems(CharSequence[] values, DialogInterface.OnClickListener click) {
        items = values; itemClick = click; return this;
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
                LinearLayout page = column();
                page.setPadding(dp(20), dp(20), dp(20), dp(16));
                page.setBackground(shape(theme.surface(), 24));
                if (title != null) {
                    TextView heading = text(title, 22);
                    heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    page.addView(heading);
                    gap(page, 16);
                }
                LinearLayout body = column();
                page.addView(body, new LinearLayout.LayoutParams(-1, -2));
                if (message != null) { body.addView(text(message, 16)); gap(body, 16); }
                if (custom != null) body.addView(custom);
                if (items != null) for (int i = 0; i < items.length; i++) {
                    final int index = i;
                    Button item = control((checked != null && checked[i] ? "✓  " : "") + items[i], false);
                    item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    item.setSelected(checked != null && checked[i]);
                    item.setOnClickListener(view -> {
                        if (checked != null) {
                            checked[index] = !checked[index];
                            item.setText((checked[index] ? "✓  " : "") + items[index]);
                            item.setSelected(checked[index]);
                            multiClick.onClick(this, index, checked[index]);
                        } else {
                            dismiss();
                            itemClick.onClick(this, index);
                        }
                    });
                    body.addView(item, new LinearLayout.LayoutParams(-1, -2));
                    gap(body, 8);
                }
                if (!labels.isEmpty()) gap(page, 16);
                boolean horizontal = labels.size() == 2 && context.getResources().getConfiguration().fontScale < 1.6f;
                LinearLayout footer = column();
                if (horizontal) footer.setOrientation(LinearLayout.HORIZONTAL);
                page.addView(footer, new LinearLayout.LayoutParams(-1, -2));
                int[] order = horizontal ? new int[]{-2, -3, -1} : new int[]{-1, -3, -2};
                for (int id : order) if (labels.containsKey(id)) {
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
                // Scroll the entire sheet so actions remain reachable with large fonts or a keyboard.
                ScrollView viewport = new ScrollView(context) {
                    @Override protected void onMeasure(int width, int height) {
                        int available = Math.min(MeasureSpec.getSize(height),
                                context.getResources().getDisplayMetrics().heightPixels - dp(64));
                        super.onMeasure(width, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
                    }
                };
                viewport.setBackground(shape(theme.surface(), 24));
                viewport.setClipToOutline(true);
                viewport.addView(page);
                setContentView(viewport);
                Window window = getWindow();
                if (window != null) {
                    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    window.setLayout(Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(32), dp(560)), -2);
                    window.setDimAmount(.65f);
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
    private LinearLayout column() { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView text(String value, int size) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(theme.ink()); view.setLineSpacing(dp(3), 1); return view;
    }
    private void gap(LinearLayout parent, int size) { parent.addView(new View(context), new LinearLayout.LayoutParams(1, dp(size))); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private Button control(String label, boolean primary) {
        Button button = new Button(context);
        button.setText(label); button.setTextSize(16); button.setAllCaps(false);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(ReaderIcon.forLabel(context, label, primary ? theme.onAccent() : theme.ink()), null, null, null);
        button.setCompoundDrawablePadding(dp(12));
        button.setTextColor(primary ? theme.onAccent() : theme.ink());
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setMinHeight(dp(48)); button.setStateListAnimator(null); button.setElevation(0);
        button.setBackground(theme.feedback(shape(primary ? theme.accent() : theme.background(), 14), context.getResources().getDisplayMetrics().density));
        return button;
    }
}

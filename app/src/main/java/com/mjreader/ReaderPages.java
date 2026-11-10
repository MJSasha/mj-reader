package com.mjreader;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.*;

/** Bounded native layouts. Page ends are actual line boundaries in the original text. */
@android.annotation.SuppressLint("ViewConstructor") // Constructed with a book in code, never inflated from XML.
final class ReaderPages extends View {
    private final Books.Book book;
    private final ReaderOptions options;
    private final Executor worker;
    private final BiFunction<Integer, Integer, SpannableString> style;
    private final IntConsumer moved;
    private final GestureDetector gestures;
    private final ArrayDeque<Integer> history = new ArrayDeque<>();
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private List<Page> pages = List.of();
    private int anchor, end, generation;
    private boolean loading;
    private PageTurn pageTurn;
    private final Paint rule = new Paint();
    record Page(int start, int end, StaticLayout layout, int sourceStart, int top) {}

    ReaderPages(Context context, Books.Book book, ReaderOptions options, int anchor, Executor worker,
                BiFunction<Integer, Integer, SpannableString> style, IntConsumer moved,
                IntConsumer paragraph, Runnable center) {
        super(context);
        this.book = book; this.options = options; this.anchor = anchor; this.end = anchor;
        this.worker = worker; this.style = style; this.moved = moved;
        paint.setColor(options.ink());
        paint.setTextSize(options.number("size", 21) * getResources().getDisplayMetrics().scaledDensity);
        paint.setTypeface(Typeface.create(new String[]{"serif", "sans-serif", "monospace"}[options.number("font", 0)], Typeface.NORMAL));
        paint.setTextLocale(ReaderText.language(book.text()));
        setFocusable(true); setClickable(true); setLongClickable(true);
        setOnClickListener(v -> center.run());
        setOnLongClickListener(v -> { paragraph.accept(this.anchor); return true; });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) {
                performClick();
                return true;
            }
            @Override public void onLongPress(MotionEvent event) {
                paragraph.accept(offsetAt(event.getX(), event.getY()));
            }
        });
    }
    int columns() {
        float scale = getResources().getConfiguration().fontScale;
        return getWidth() / getResources().getDisplayMetrics().density >= Math.max(640, 560 * scale) ? 2 : 1;
    }
    int start() { return anchor; }
    int end() { return end; }
    boolean ready() { return !loading && pageTurn == null && !pages.isEmpty(); }
    List<Page> visiblePages() { return pages; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int margin() { return dp(options.number("margin", 8)); }
    private int pageWidth() { return Math.max(1, (getWidth() - (columns() - 1) * dp(32)) / columns() - margin() * 2); }
    private int pageHeight() { return Math.max(1, getHeight() - dp(24)); }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        finishTurn();
        history.clear();
        render(anchor, false, 0);
    }
    @Override protected void onDetachedFromWindow() { generation++; finishTurn(); super.onDetachedFromWindow(); }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean onTouchEvent(MotionEvent event) { return gestures.onTouchEvent(event) || super.onTouchEvent(event); }
    void turn(int direction) {
        if (!ready()) return;
        if (direction > 0 && end < book.text().length()) {
            history.addLast(anchor);
            if (history.size() > 100) history.removeFirst();
            render(end, false, 1);
        } else if (direction < 0 && anchor > 0) {
            if (!history.isEmpty()) render(history.removeLast(), false, -1);
            else render(anchor, true, -1);
        }
    }
    private void render(int requested, boolean backwards, int direction) {
        if (getWidth() == 0 || getHeight() == 0) return;
        int version = ++generation, width = pageWidth(), height = pageHeight(), count = columns();
        loading = true;
        worker.execute(() -> {
            List<Page> built = new ArrayList<>();
            int start = requested, next = requested;
            if (backwards) {
                // Slice one bounded layout backwards: reflowing its suffix could change line breaks.
                int from = Math.max(0, requested - 18000);
                if (from > 0 && Character.isLowSurrogate(book.text().charAt(from))) from--;
                StaticLayout previous = buildLayout(from, requested, width, height);
                int line = previous.getLineCount();
                for (int page = 0; page < count && line > 0; page++) {
                    int bottom = previous.getLineTop(line), first = line;
                    while (first > 0 && bottom - previous.getLineTop(first - 1) <= height) first--;
                    first = first == line ? Math.max(0, line - 1) : first;
                    int begin = from + previous.getLineStart(first);
                    int stop = from + previous.getLineStart(line);
                    if (stop > begin) built.add(0, new Page(begin, stop, previous, from, previous.getLineTop(first)));
                    line = first;
                }
                start = from + previous.getLineStart(line);
                ReaderText.loadPictures((Spanned) previous.getText(), start - from, requested - from);
            } else {
                for (int i = 0; i < count && next < book.text().length(); i++) {
                    int limit = Math.min(book.text().length(), next + 18000);
                    if (limit < book.text().length() && Character.isLowSurrogate(book.text().charAt(limit))) limit--;
                    StaticLayout layout = buildLayout(next, limit, width, height);
                    int lines = 0;
                    while (lines < layout.getLineCount() && layout.getLineBottom(lines) <= height) lines++;
                    lines = Math.max(1, lines);
                    int stop = next + layout.getLineEnd(lines - 1);
                    ReaderText.loadPictures((Spanned) layout.getText(), 0, stop - next);
                    built.add(new Page(next, stop, layout, next, 0));
                    next = stop;
                }
            }
            int newStart = start, newEnd = next;
            post(() -> {
                if (version != generation || !isAttachedToWindow()) return;
                List<Page> previous = pages;
                pages = built; anchor = newStart; end = newEnd; loading = false;
                if (direction != 0 && !previous.isEmpty() && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    pageTurn = new PageTurn(previous, direction);
                    pageTurn.animator.start();
                }
                SpannableStringBuilder description = new SpannableStringBuilder(book.text().substring(anchor, end));
                for (int i = book.illustrations().size() - 1; i >= 0; i--) {
                    Books.Illustration picture = book.illustrations().get(i);
                    if (picture.offset() >= anchor && picture.offset() < end && !picture.alt().isEmpty())
                        description.replace(picture.offset() - anchor, picture.offset() - anchor + 1, picture.alt());
                }
                setContentDescription(description.toString().replace("\uFFFC", ""));
                moved.accept(anchor); invalidate();
            });
        });
    }
    @android.annotation.SuppressLint("NewApi") // LineBreaker constants are inlined; APIs exist on 26.
    private StaticLayout buildLayout(int from, int to, int width, int height) {
        SpannableString text = style.apply(from, to);
        ReaderText.illustrate(book, text, from, width, height);
        return StaticLayout.Builder.obtain(text, 0, text.length(), new TextPaint(paint), width)
                .setAlignment(options.number("alignment", 0) == 2 ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, options.number("line", 150) / 100f).setIncludePad(false)
                .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(options.flag("hyphenation", true) ? Layout.HYPHENATION_FREQUENCY_FULL : Layout.HYPHENATION_FREQUENCY_NONE)
                .setJustificationMode(options.number("alignment", 0) == 1 ? android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD : android.graphics.text.LineBreaker.JUSTIFICATION_MODE_NONE).build();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pageTurn != null) pageTurn.draw(canvas);
        else drawPages(canvas, pages);
    }
    private void drawPages(Canvas canvas, List<Page> shown) {
        int width = pageWidth(), stride = width + margin() * 2 + dp(32);
        if (columns() == 2) {
            rule.setColor(options.muted()); rule.setAlpha(55);
            canvas.drawRect(getWidth() / 2f, dp(12), getWidth() / 2f + dp(1), getHeight() - dp(12), rule);
        }
        for (int i = 0; i < shown.size(); i++) {
            Page page = shown.get(i);
            int save = canvas.save();
            canvas.translate(margin() + i * stride, dp(12));
            // Clip after the last complete line, never expose text repeated on the next page.
            int last = page.layout().getLineForOffset(Math.max(0, page.end() - page.sourceStart() - 1));
            canvas.clipRect(0, 0, width, Math.min(pageHeight(), page.layout().getLineBottom(last) - page.top()));
            canvas.translate(0, -page.top());
            page.layout().draw(canvas);
            canvas.restoreToCount(save);
        }
    }
    private void finishTurn() {
        PageTurn finished = pageTurn;
        pageTurn = null;
        if (finished != null) finished.animator.cancel();
        invalidate();
    }
    /** Slide the existing bounded layouts; no page textures or simulated paper. */
    private final class PageTurn {
        final android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0, 1);
        final List<Page> previous;
        final int direction;
        float progress;
        PageTurn(List<Page> previous, int direction) {
            this.previous = previous; this.direction = direction;
            animator.setDuration(180);
            animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            animator.addUpdateListener(value -> { progress = (float) value.getAnimatedValue(); invalidate(); });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (pageTurn == PageTurn.this) { pageTurn = null; invalidate(); }
                }
            });
        }
        void draw(Canvas canvas) {
            int saved = canvas.save();
            canvas.clipRect(0, 0, getWidth(), getHeight());
            canvas.translate(-direction * progress * getWidth(), 0);
            drawPages(canvas, previous);
            canvas.translate(direction * getWidth(), 0);
            drawPages(canvas, pages);
            canvas.restoreToCount(saved);
        }
    }
    private int offsetAt(float x, float y) {
        if (pages.isEmpty()) return anchor;
        int stride = pageWidth() + margin() * 2 + dp(32);
        int index = Math.max(0, Math.min(pages.size() - 1, (int) x / stride));
        Page page = pages.get(index);
        int line = page.layout().getLineForVertical(Math.max(0, (int) y - dp(12)) + page.top());
        return Math.min(page.end() - 1, page.sourceStart() + page.layout().getOffsetForHorizontal(line, x - index * stride - margin()));
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.TextView"); info.setScrollable(true);
        if (anchor > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (end < book.text().length()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    }
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { turn(1); return true; }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { turn(-1); return true; }
        return super.performAccessibilityAction(action, arguments);
    }
}

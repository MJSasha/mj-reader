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
    private float downX;
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
            @Override public boolean onDown(MotionEvent event) { downX = event.getX(); return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) {
                if (event.getX() < getWidth() * .25f) turn(-1);
                else if (event.getX() > getWidth() * .75f) turn(1);
                else performClick();
                return true;
            }
            @Override public void onLongPress(MotionEvent event) {
                paragraph.accept(offsetAt(event.getX(), event.getY()));
            }
            @Override public boolean onFling(MotionEvent first, MotionEvent last, float vx, float vy) {
                if (Math.abs(last.getX() - downX) > dp(48) && Math.abs(vx) > Math.abs(vy)) {
                    turn(last.getX() < downX ? 1 : -1); return true;
                }
                return false;
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
    /** Two bounded page textures, bent in strips around the book spine; no full-book bitmap. */
    private final class PageTurn {
        private static final int COLUMNS = 32, ROWS = 6;
        final android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0, 1);
        final List<Page> previous;
        final int direction;
        final boolean spread = columns() == 2;
        final float sheetWidth = spread ? getWidth() / 2f : getWidth();
        final Bitmap front, back;
        final float[] mesh = new float[(COLUMNS + 1) * (ROWS + 1) * 2];
        final int[] shades = new int[(COLUMNS + 1) * (ROWS + 1)];
        final Paint texture = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path outline = new Path();
        final Matrix shadowMatrix = new Matrix();
        final LinearGradient shadowGradient = new LinearGradient(0, 0, 1, 0,
                new int[]{0x00000000, 0x48000000, 0x00000000}, null, Shader.TileMode.CLAMP);
        float progress;
        PageTurn(List<Page> previous, int direction) {
            this.previous = previous; this.direction = direction;
            front = snapshot(!spread && direction < 0 ? pages : previous, spread && direction > 0 ? sheetWidth : 0);
            back = spread ? snapshot(pages, direction > 0 ? 0 : sheetWidth) : null;
            shadow.setShader(shadowGradient);
            edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(dp(1));
            animator.setDuration(spread ? 520 : 420);
            animator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            animator.addUpdateListener(value -> { progress = (float) value.getAnimatedValue(); invalidate(); });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (pageTurn == PageTurn.this) { pageTurn = null; invalidate(); }
                }
            });
        }
        private Bitmap snapshot(List<Page> content, float left) {
            // Keep each texture below 0.75 megapixels even on high-density tablets.
            float scale = Math.min(1f, (float) Math.sqrt(750000f / (sheetWidth * getHeight())));
            Bitmap image = Bitmap.createBitmap(Math.max(1, Math.round(sheetWidth * scale)),
                    Math.max(1, Math.round(getHeight() * scale)), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.drawColor(options.background()); canvas.scale(scale, scale); canvas.translate(-left, 0);
            drawPages(canvas, content);
            return image;
        }
        void draw(Canvas canvas) {
            if (spread) {
                drawPages(canvas, previous);
                int saved = canvas.save();
                if (direction > 0) canvas.clipRect(sheetWidth, 0, getWidth(), getHeight());
                else canvas.clipRect(0, 0, sheetWidth, getHeight());
                canvas.drawColor(options.background()); drawPages(canvas, pages); canvas.restoreToCount(saved);
            } else drawPages(canvas, direction > 0 ? pages : previous);
            double angle = Math.PI * (spread ? progress : (direction > 0 ? progress : 1 - progress) / 2);
            float sine = (float) Math.sin(angle), cosine = (float) Math.cos(angle);
            boolean reverse = spread && progress > .5f;
            float hinge = spread ? sheetWidth : 0, side = spread ? direction : 1;
            float camera = Math.max(sheetWidth, getHeight()) * 3;
            for (int row = 0; row <= ROWS; row++) for (int column = 0; column <= COLUMNS; column++) {
                float source = column / (float) COLUMNS;
                float u = spread && (direction < 0 ^ reverse) ? 1 - source : source;
                float bend = (float) Math.sin(u * Math.PI) * sheetWidth * .16f * sine * Math.abs(cosine);
                float depth = u * sheetWidth * sine - bend * cosine;
                float perspective = camera / (camera + depth);
                int vertex = row * (COLUMNS + 1) + column;
                mesh[vertex * 2] = hinge + side * (u * sheetWidth * cosine + bend * sine) * perspective;
                mesh[vertex * 2 + 1] = getHeight() / 2f + (row / (float) ROWS - .5f) * getHeight() * perspective;
                int tone = Math.round(255 * (1 - .2f * sine * (float) Math.sin(u * Math.PI)));
                shades[vertex] = Color.rgb(tone, tone, tone);
            }
            float farEdge = hinge + side * sheetWidth * cosine * camera / (camera + sheetWidth * sine);
            float shadeWidth = dp(36) * sine;
            shadowMatrix.setScale(Math.max(1, shadeWidth * 2), getHeight());
            shadowMatrix.postTranslate(farEdge - shadeWidth, 0); shadowGradient.setLocalMatrix(shadowMatrix);
            shadow.setAlpha(Math.round(150 * sine));
            canvas.drawRect(farEdge - shadeWidth, 0, farEdge + shadeWidth, getHeight(), shadow);
            canvas.drawBitmapMesh(reverse ? back : front, COLUMNS, ROWS, mesh, 0, shades, 0, texture);
            outline.rewind(); outline.moveTo(mesh[0], mesh[1]);
            for (int column = 1; column <= COLUMNS; column++) outline.lineTo(mesh[column * 2], mesh[column * 2 + 1]);
            for (int row = 1; row <= ROWS; row++) {
                int vertex = row * (COLUMNS + 1) + COLUMNS; outline.lineTo(mesh[vertex * 2], mesh[vertex * 2 + 1]);
            }
            for (int column = COLUMNS - 1; column >= 0; column--) {
                int vertex = ROWS * (COLUMNS + 1) + column; outline.lineTo(mesh[vertex * 2], mesh[vertex * 2 + 1]);
            }
            outline.close(); edge.setColor(options.muted()); edge.setAlpha(Math.round(90 * sine));
            canvas.drawPath(outline, edge);
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

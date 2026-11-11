package com.mjreader;

import android.content.Context;
import android.graphics.Typeface;
import android.text.*;
import android.text.style.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/** Presentation spans never modify stored text or its bookmark offsets. */
final class ReaderText {
    static SpannableString style(Context context, Books.Book book, ReaderOptions options, int from, int to) {
        String fragment = book.text().substring(from, to);
        SpannableString text = new SpannableString(fragment);
        float density = context.getResources().getDisplayMetrics().density;
        List<int[]> headings = new ArrayList<>();
        for (Books.Chapter chapter : book.chapters()) {
            int start = chapter.offset() - from;
            if (start < 0 || start >= text.length() || chapter.title().trim().isEmpty()) continue;
            String pattern = Arrays.stream(chapter.title().trim().split("[\\s\\p{Z}]+"))
                    .map(Pattern::quote).collect(Collectors.joining("[\\s\\p{Z}]+"));
            Matcher match = Pattern.compile(pattern).matcher(text);
            match.region(start, Math.min(text.length(), start + 600));
            if (!match.lookingAt()) continue;
            int end = match.end();
            headings.add(new int[]{start, end});
            text.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new RelativeSizeSpan(1.18f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int indent = Math.round(options.number("indent", 20) * density);
        for (int start = 0; start < text.length();) {
            int end = fragment.indexOf('\n', start);
            if (end < 0) end = text.length();
            boolean heading = false;
            for (int[] range : headings) if (start >= range[0] && start < range[1]) { heading = true; break; }
            boolean illustration = end > start && fragment.substring(start, end).trim().equals("\uFFFC");
            if (illustration) text.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (end > start && indent > 0 && !heading && !illustration
                    && (start > 0 || from == 0 || book.text().charAt(from - 1) == '\n'))
                text.setSpan(new LeadingMarginSpan.Standard(indent, 0), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = end + 1;
        }
        // EPUB paragraphs commonly contain a blank line. Give it a deliberate gap rather than a full text line.
        int paragraphGap = Math.round(options.number("paragraphGap", 8) * density);
        text.setSpan((LineHeightSpan) (value, start, end, spanstartv, v, fm) -> {
            boolean blank = end > start;
            for (int i = start; i < end; i++) if (!Character.isWhitespace(value.charAt(i))) { blank = false; break; }
            if (blank) { fm.ascent = -paragraphGap; fm.top = -paragraphGap; fm.descent = 0; fm.bottom = 0; }
        }, 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (Books.Note note : book.notes()) {
            int start = Math.max(from, note.start()) - from, end = Math.min(to, note.end()) - from;
            if (end > start) {
                text.setSpan(new ForegroundColorSpan(options.accent()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return text;
    }
    static int noteHit(Books.Book book, Layout layout, int source, int from, int to, float x, float y, float density) {
        if (layout == null) return -1;
        int best = -1; float distance = Float.MAX_VALUE;
        android.graphics.Path path = new android.graphics.Path();
        android.graphics.RectF bounds = new android.graphics.RectF();
        for (Books.Note note : book.notes()) {
            int start = Math.max(from, note.start()) - source, end = Math.min(to, note.end()) - source;
            if (start < 0 || end <= start || end > layout.getText().length()) continue;
            int first = layout.getLineForOffset(start), last = layout.getLineForOffset(end - 1);
            for (int line = first; line <= last; line++) {
                path.reset(); layout.getSelectionPath(Math.max(start, layout.getLineStart(line)), Math.min(end, layout.getLineEnd(line)), path);
                path.computeBounds(bounds, true);
                float dx = Math.max(0, Math.max(bounds.left - x, x - bounds.right));
                float dy = Math.max(0, Math.max(bounds.top - y, y - bounds.bottom));
                float padX = Math.max(8 * density, (48 * density - bounds.width()) / 2);
                float padY = Math.max(6 * density, (48 * density - bounds.height()) / 2);
                float score = dx * dx + dy * dy;
                if (dx <= padX && dy <= padY && score < distance) { best = note.start(); distance = score; }
            }
        }
        return best;
    }
    static final int NOTE_ACTION = 0x01000000;
    static void noteActions(Books.Book book, android.view.accessibility.AccessibilityNodeInfo info, int from, int to) {
        for (int i = 0; i < book.notes().size(); i++) {
            Books.Note note = book.notes().get(i);
            if (note.start() < to && note.end() > from)
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(NOTE_ACTION + i,
                        book.text().substring(note.start(), note.end())));
        }
    }

    /** Sizing uses image headers only; pixels are decoded for visible pages on the worker. */
    static void illustrate(Books.Book book, SpannableString text, int from, int width, int height) {
        Map<Integer, Books.Illustration> images = new HashMap<>();
        for (Books.Illustration image : book.illustrations())
            if (image.offset() >= from && image.offset() < from + text.length()) images.put(image.offset() - from, image);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\uFFFC') continue;
            text.setSpan(new Picture(images.get(i), width, height), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
    static void loadPictures(Spanned text, int from, int to) {
        for (Picture picture : text.getSpans(0, text.length(), Picture.class)) {
            int start = text.getSpanStart(picture);
            if (start >= from && start < to) picture.load();
            else picture.bitmap = null;
        }
    }
    static final class Picture extends ReplacementSpan {
        final Books.Illustration image;
        final int width, height;
        volatile android.graphics.Bitmap bitmap;
        private final android.graphics.Paint imagePaint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.RectF destination = new android.graphics.RectF();
        Picture(Books.Illustration image, int maxWidth, int maxHeight) {
            this.image = image;
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            if (image != null) android.graphics.BitmapFactory.decodeByteArray(image.bytes(), 0, image.bytes().length, bounds);
            float ratio = bounds.outWidth > 0 && bounds.outHeight > 0 ? Math.min(1f,
                    Math.min(maxWidth / (float) bounds.outWidth, Math.max(1, maxHeight - 64) / (float) bounds.outHeight)) : 0;
            width = Math.round(Math.max(0, bounds.outWidth) * ratio);
            height = Math.round(Math.max(0, bounds.outHeight) * ratio);
        }
        void load() {
            if (bitmap != null || image == null || width == 0 || height == 0) return;
            android.graphics.BitmapFactory.Options decode = new android.graphics.BitmapFactory.Options();
            decode.inJustDecodeBounds = true;
            byte[] bytes = image.bytes();
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decode);
            decode.inSampleSize = 1;
            while (decode.outWidth / decode.inSampleSize > Math.min(width * 2, 1536)
                    || decode.outHeight / decode.inSampleSize > Math.min(height * 2, 1536)) decode.inSampleSize *= 2;
            decode.inJustDecodeBounds = false;
            bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decode);
        }
        @Override public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end, android.graphics.Paint.FontMetricsInt fm) {
            if (fm != null && height > 0) { fm.ascent = -height; fm.top = -height; fm.descent = 0; fm.bottom = 0; }
            return width;
        }
        @Override public void draw(android.graphics.Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, android.graphics.Paint paint) {
            android.graphics.Bitmap ready = bitmap;
            if (ready != null) {
                destination.set(x, y - height, x + width, y);
                canvas.drawBitmap(ready, null, destination, imagePaint);
            }
        }
    }

    static int[] window(String text, int anchor) {
        int start = Math.max(0, anchor - 6000);
        int paragraph = text.lastIndexOf('\n', start);
        if (paragraph >= 0 && start - paragraph < 2000) start = paragraph + 1;
        else if (start > 0) {
            int word = text.lastIndexOf(' ', start);
            if (word >= 0 && start - word < 100) start = word + 1;
        }
        int end = Math.min(text.length(), start + 18000);
        if (end < text.length()) {
            int next = text.indexOf('\n', end);
            if (next >= 0 && next - end < 1000) end = next + 1;
            else {
                int word = text.lastIndexOf(' ', end);
                if (word > start && end - word < 100) end = word + 1;
            }
        }
        return new int[]{start, end};
    }

    static Locale language(String text) {
        int cyrillic = 0, latin = 0;
        for (int i = 0; i < Math.min(4096, text.length()); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC) cyrillic++;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
        }
        return Locale.forLanguageTag(cyrillic > latin ? "ru" : "en");
    }
}

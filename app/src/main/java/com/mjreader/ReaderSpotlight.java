package com.mjreader;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.widget.FrameLayout;

/** One bounded, in-memory viewport snapshot; the original sentence stays at its exact position. */
final class ReaderSpotlight extends FrameLayout {
    ReaderSpotlight(Context context, View source, Path selection, ReaderOptions theme) {
        super(context);
        setTag("note-spotlight");
        int width = Math.max(1, source.getWidth()), height = Math.max(1, source.getHeight());
        float scale = Math.min(1f, (float) Math.sqrt(4_000_000d / ((double) width * height)));
        Bitmap snapshot = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
        Canvas capture = new Canvas(snapshot); capture.scale(scale, scale); source.draw(capture);
        int[] origin = new int[2]; source.getLocationOnScreen(origin);
        float density = getResources().getDisplayMetrics().density;
        Path focus = new Path(selection);
        // Older Android uses a small filtered snapshot instead of an unsupported GPU effect.
        Bitmap soft = android.os.Build.VERSION.SDK_INT >= 31 ? snapshot
                : Bitmap.createScaledBitmap(snapshot, Math.max(1, width / 24), Math.max(1, height / 24), true);
        View blurred = new View(context) {
            final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                int[] position = new int[2]; getLocationOnScreen(position);
                canvas.drawBitmap(soft, null, new RectF(origin[0] - position[0], origin[1] - position[1],
                        origin[0] - position[0] + width, origin[1] - position[1] + height), paint);
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= 31)
            blurred.setRenderEffect(RenderEffect.createBlurEffect(10 * density, 10 * density, Shader.TileMode.CLAMP));
        blurred.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(blurred, new LayoutParams(-1, -1));
        View sharp = new View(context) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(theme.light() ? 0x70FFFFFF : 0x70000000);
                int[] position = new int[2]; getLocationOnScreen(position);
                int save = canvas.save(); canvas.translate(origin[0] - position[0], origin[1] - position[1]);
                canvas.clipPath(focus);
                canvas.drawBitmap(snapshot, null, new RectF(0, 0, width, height), paint);
                canvas.restoreToCount(save);
            }
        };
        sharp.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(sharp, new LayoutParams(-1, -1));
    }
}

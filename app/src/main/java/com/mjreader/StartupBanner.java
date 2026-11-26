package com.mjreader;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Graphite family launch artwork shown while the library loads. */
final class StartupBanner extends LinearLayout {
    private final Emblem emblem;
    private final TextView title;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF lavender = new RectF();
    private final RectF pink = new RectF();
    private LinearGradient lavenderFill, pinkFill;
    private ValueAnimator entrance;
    private ValueAnimator reveal;
    private Runnable libraryReady;
    private boolean firstFrame = true;
    private final Runnable startEntrance = this::startEntrance;

    StartupBanner(Context context) {
        super(context);
        setBackgroundResource(R.color.mj_startup_background);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(24) + insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    dp(24) + insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        int availableHeight = getResources().getConfiguration().screenHeightDp;
        boolean compact = availableHeight < 400;
        boolean largeText = getResources().getConfiguration().fontScale >= 1.6f;
        int size = dp(Math.min(compact ? 104 : 148, Math.max(64, availableHeight / (largeText ? 3 : 2))));
        emblem = new Emblem(context);
        addView(emblem, new LayoutParams(size, size));

        title = new TextView(context);
        title.setText(R.string.launch_wordmark);
        title.setTextColor(0xFFF5F1F8);
        title.setTextSize(compact || largeText ? 20 : 24);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        setIdentityAlpha(0f);
        emblem.progress = 0f;
        entrance = ValueAnimator.ofFloat(0f, 1f);
        entrance.setDuration(280);
        entrance.addUpdateListener(animation -> {
            emblem.progress = (float) animation.getAnimatedValue();
            setIdentityAlpha(Math.min(1f, emblem.progress / .16f));
            emblem.invalidate();
            invalidate();
        });
        entrance.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                entrance = null;
                Runnable complete = libraryReady;
                libraryReady = null;
                if (complete != null) reveal(complete);
            }
        });
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (width <= 0 || height <= 0) return;
        lavender.set(-width * .84f, -height * .44f, width * .65f, height * .24f);
        pink.set(width * .32f, height * .81f, width * 1.8f, height * 1.38f);
        lavenderFill = new LinearGradient(0, 0, width * .62f, height * .24f,
                0xFF3D285E, 0xFF7945AC, Shader.TileMode.CLAMP);
        pinkFill = new LinearGradient(width * .38f, height * .83f, width, height,
                0xFF492766, 0xFF9A327F, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (firstFrame) {
            firstFrame = false;
            // A launch without a transferred system splash gets no exit callback.
            postDelayed(startEntrance, Build.VERSION.SDK_INT >= 31 ? 250 : 0);
        }
        float settled = Math.max(0f, 2f * emblem.progress - 1f);
        paint.setShader(lavenderFill);
        paint.setAlpha(Math.round(255 * (1f - settled)));
        canvas.save();
        canvas.translate(-dp(18) * settled, dp(14) * settled);
        canvas.drawOval(lavender, paint);
        canvas.restore();
        canvas.save();
        canvas.translate(-dp(24) * (1f - settled), getHeight() + dp(14) * (1f - settled));
        canvas.scale(1f, -1f);
        paint.setAlpha(Math.round(255 * settled));
        canvas.drawOval(lavender, paint);
        canvas.restore();
        paint.setShader(pinkFill);
        paint.setAlpha(255);
        canvas.save();
        canvas.translate(dp(18) * (1f - emblem.progress), dp(12) * (1f - emblem.progress));
        canvas.drawOval(pink, paint);
        canvas.restore();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) { return true; }

    void reveal(Runnable complete) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            complete.run();
            return;
        }
        if (entrance != null) {
            libraryReady = complete;
            return;
        }
        reveal = ValueAnimator.ofFloat(0f, 1f);
        reveal.setDuration(100);
        reveal.setInterpolator(new PathInterpolator(.4f, 0f, .2f, 1f));
        reveal.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            // Clear the launch mark before revealing the library's separate masthead.
            setIdentityAlpha(Math.max(0f, 1f - progress / .35f));
            setAlpha(Math.min(1f, (1f - progress) / .65f));
        });
        reveal.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                reveal = null;
                complete.run();
            }
        });
        reveal.start();
    }

    void startEntrance() {
        removeCallbacks(startEntrance);
        if (entrance != null && !entrance.isStarted() && isAttachedToWindow()) entrance.start();
    }

    void handoffSystemSplash(View splash, Runnable remove) {
        removeCallbacks(startEntrance);
        firstFrame = false;
        setIdentityAlpha(0f);
        splash.animate().alpha(0f).setDuration(64).withEndAction(() -> {
            remove.run();
            startEntrance();
        }).start();
    }

    private void setIdentityAlpha(float alpha) {
        emblem.setAlpha(alpha);
        title.setAlpha(alpha);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(startEntrance);
        libraryReady = null;
        if (entrance != null) {
            entrance.removeAllListeners();
            entrance.cancel();
            entrance = null;
        }
        if (reveal != null) {
            reveal.removeAllListeners();
            reveal.cancel();
            reveal = null;
        }
        super.onDetachedFromWindow();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class Emblem extends View {
        private final Drawable signature, leftPage, rightPage;
        private float progress = 1f;

        Emblem(Context context) {
            super(context);
            signature = context.getDrawable(R.drawable.ic_launcher_foreground).mutate();
            leftPage = context.getDrawable(R.drawable.ic_launcher_foreground).mutate();
            rightPage = context.getDrawable(R.drawable.ic_launcher_foreground).mutate();
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            int half = Math.round(Math.min(width, height) * .78f);
            int top = height - dp(3) - Math.round(2 * half * 80f / 108f);
            signature.setBounds(width / 2 - half, top, width / 2 + half, top + 2 * half);
            leftPage.setBounds(signature.getBounds());
            rightPage.setBounds(signature.getBounds());
        }

        @Override protected void onDraw(Canvas canvas) {
            // The launcher vector uses a 108-unit canvas with an empty seam at y=71
            // between the book and the stationary MJ signature.
            var bounds = signature.getBounds();
            int seam = bounds.top + Math.round(bounds.height() * 71f / 108f);
            canvas.save();
            canvas.clipRect(0, seam, getWidth(), getHeight());
            signature.draw(canvas);
            canvas.restore();
            if (progress <= 0f) return;
            drawPage(canvas, leftPage, true, progress, seam);
            drawPage(canvas, rightPage, false, Math.max(0f, (progress - .12f) / .88f), seam);
        }

        private void drawPage(Canvas canvas, Drawable page, boolean left, float progress, int seam) {
            if (progress <= 0f) return;
            float distance = 1f - progress;
            float spine = signature.getBounds().exactCenterX();
            canvas.save();
            canvas.translate(dp(left ? -26 : 24) * distance, dp(left ? 8 : -10) * distance);
            canvas.rotate((left ? -13f : 15f) * distance, spine, seam - dp(25));
            canvas.clipRect(left ? 0f : spine, 0, left ? spine : getWidth(), seam);
            page.setAlpha(Math.round(255 * Math.min(1f, 1.35f * progress)));
            page.draw(canvas);
            canvas.restore();
        }
    }
}

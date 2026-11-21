package com.mjreader;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The MJ family launch treatment, shared in spirit with MJWatch's orbital emblem. */
final class StartupBanner extends LinearLayout {
    private static final int LIME = 0xFFC7FF68;
    private static final int LIGHT = 0xFFF0F2EC;
    private final Emblem emblem;
    private ValueAnimator entrance;
    private ValueAnimator reveal;
    private Runnable ready;

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
        int size = dp(Math.min(compact ? 144 : 184, Math.max(64, availableHeight / (largeText ? 3 : 2))));
        emblem = new Emblem(context);
        addView(emblem, new LayoutParams(size, size));

        TextView title = new TextView(context);
        SpannableString wordmark = new SpannableString(context.getString(R.string.startup_title));
        wordmark.setSpan(new ForegroundColorSpan(LIME), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(wordmark);
        title.setTextColor(LIGHT);
        title.setTextSize(compact || largeText ? 32 : 44);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-.035f);
        addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView status = new TextView(context);
        status.setText(R.string.loading_books);
        status.setTextColor(0xFFB6B9B2);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        LayoutParams statusSize = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        statusSize.topMargin = dp(8);
        addView(status, statusSize);
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        emblem.progress = 0f;
        title.setAlpha(0f);
        title.setTranslationY(dp(12));
        status.setAlpha(0f);
        entrance = ValueAnimator.ofFloat(0f, 1f);
        entrance.setDuration(520);
        entrance.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            emblem.progress = progress;
            emblem.invalidate();
            float wordmarkIn = Math.max(0f, Math.min(1f, (progress - .24f) / .55f));
            title.setAlpha(wordmarkIn);
            title.setTranslationY(dp(12) * (1f - wordmarkIn));
            status.setAlpha(Math.max(0f, (progress - .55f) / .45f));
        });
        entrance.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                entrance = null;
                Runnable action = ready;
                ready = null;
                if (action != null) action.run();
            }
        });
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) { return true; }

    void reveal(Runnable complete) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            complete.run();
            return;
        }
        if (entrance != null) {
            ready = () -> reveal(complete);
            return;
        }
        reveal = ValueAnimator.ofFloat(0f, 1f);
        reveal.setDuration(420);
        reveal.setInterpolator(new PathInterpolator(.4f, 0f, .2f, 1f));
        reveal.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            float scale = 1f + .5f * progress;
            emblem.setScaleX(scale);
            emblem.setScaleY(scale);
            setAlpha(1f - progress);
        });
        reveal.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                reveal = null;
                complete.run();
            }
        });
        reveal.start();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (entrance != null) entrance.start();
    }

    @Override protected void onDetachedFromWindow() {
        ready = null;
        if (entrance != null) entrance.cancel();
        if (reveal != null) {
            reveal.removeAllListeners();
            reveal.cancel();
            reveal = null;
        }
        super.onDetachedFromWindow();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class Emblem extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable mark;
        private final RectF orbit = new RectF();
        private RadialGradient glow;
        private float progress = 1f;

        Emblem(Context context) {
            super(context);
            mark = context.getDrawable(R.drawable.ic_launcher_foreground);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            float centerX = width / 2f, centerY = height / 2f, radius = Math.min(centerX, centerY);
            if (radius <= 0) return;
            glow = new RadialGradient(centerX, centerY, radius, 0x25C7FF68, 0x00C7FF68, Shader.TileMode.CLAMP);
            orbit.set(centerX - radius * .83f, centerY - radius * .83f,
                    centerX + radius * .83f, centerY + radius * .83f);
            int half = Math.round(radius * .86f);
            mark.setBounds(width / 2 - half, height / 2 - half, width / 2 + half, height / 2 + half);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (glow == null) return;
            paint.setShader(glow);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0x35C7FF68);
            paint.setStrokeWidth(dp(1));
            canvas.drawOval(orbit, paint);
            paint.setColor(0xBAC7FF68);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            float angle = -148 + 52 * progress;
            canvas.drawArc(orbit, angle, 40 + 43 * progress, false, paint);
            canvas.drawArc(orbit, angle + 180, 40 + 43 * progress, false, paint);
            canvas.save();
            float scale = .88f + .12f * progress;
            canvas.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);
            mark.draw(canvas);
            canvas.restore();
        }
    }
}

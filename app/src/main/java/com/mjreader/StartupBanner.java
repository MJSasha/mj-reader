package com.mjreader;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One light orbit, then a reveal over the drawn library. */
final class StartupBanner extends LinearLayout {
    private static final int ENTRANCE_MS = 700;
    private static final int REVEAL_MS = 400;
    private static final int LAVENDER = 0xFFCDA5FF;
    private static final int PINK = 0xFFFF8BE0;
    private final Emblem emblem;
    private final TextView title;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF orbit = new RectF();
    private final Shader halo = new RadialGradient(0, 0, 1f,
            new int[]{0x704E347D, 0x304E316B, 0}, new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP);
    private final Shader lavenderArc = arcShader(LAVENDER);
    private final Shader pinkArc = arcShader(PINK);
    private final Shader lightPass = new LinearGradient(-1f, 0, 1f, 0,
            new int[]{0x00CDA5FF, 0x18CDA5FF, 0x3AFFD1F4, 0x00FFC2EF},
            new float[]{0f, .38f, .56f, 1f}, Shader.TileMode.CLAMP);
    private ValueAnimator entrance;
    private ValueAnimator reveal;
    private Runnable libraryReady;
    private Runnable waitingForLibrary;
    private View systemSplash;
    private Runnable removeSystemSplash;
    private float entranceTime = ENTRANCE_MS;
    private float revealProgress;
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
        int size = dp(Math.min(compact ? 128 : 184, Math.max(64, availableHeight / (largeText ? 3 : 2))));
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
        title.setAlpha(0f);
        entranceTime = 0f;
        entrance = ValueAnimator.ofFloat(0f, ENTRANCE_MS);
        entrance.setDuration(ENTRANCE_MS);
        entrance.setInterpolator(new LinearInterpolator());
        entrance.addUpdateListener(animation -> {
            entranceTime = (float) animation.getAnimatedValue();
            title.setAlpha(ease(entranceTime, 160f, 320f));
            emblem.invalidate();
            invalidate();
        });
        entrance.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                entrance = null;
                Runnable complete = libraryReady;
                libraryReady = null;
                if (complete != null) reveal(complete);
                else if (waitingForLibrary != null) waitingForLibrary.run();
            }
        });
    }

    void setWaitingForLibrary(Runnable action) {
        waitingForLibrary = action;
    }

    private static Shader arcShader(int color) {
        int transparent = color & 0x00FFFFFF;
        return new SweepGradient(0, 0, new int[]{transparent, color, transparent, transparent},
                new float[]{0f, .17f, .34f, 1f});
    }

    private static float ease(float value, float start, float end) {
        float t = Math.max(0f, Math.min(1f, (value - start) / (end - start)));
        return t * t * (3f - 2f * t);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (firstFrame) {
            firstFrame = false;
            // A launch without a transferred system splash gets no exit callback.
            postDelayed(startEntrance, Build.VERSION.SDK_INT >= 31 ? 250 : 0);
        }
        if (emblem.getWidth() == 0) return;
        float time = entranceTime;
        float arrival = ease(time, 0f, 180f);
        float rotation = ease(time, 80f, 540f);
        float settled = ease(time, 540f, ENTRANCE_MS);
        float radius = emblem.getWidth() * (.62f - .11f * rotation);
        canvas.save();
        canvas.translate(emblem.getLeft() + emblem.getWidth() / 2f, emblem.getTop() + emblem.markCenterY());
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(halo);
        paint.setAlpha(Math.round(95 + 105 * arrival - 38 * settled));
        canvas.save();
        canvas.scale(radius * 2.16f, radius * 2.16f);
        canvas.drawCircle(0, 0, 1f, paint);
        canvas.restore();

        float strength = arrival * (1f - .51f * settled);
        drawArc(canvas, lavenderArc, radius, -dp(47) * (1f - rotation), -dp(28) * (1f - rotation),
                158f + 117f * rotation, strength);
        drawArc(canvas, pinkArc, radius * .94f, dp(48) * (1f - rotation), dp(29) * (1f - rotation),
                -45f + 123f * rotation, strength * .9f);
        drawFragments(canvas, radius, time, rotation);
        canvas.restore();
        if (revealProgress > 0f) drawLightPass(canvas);
    }

    private void drawArc(Canvas canvas, Shader shader, float radius, float x, float y, float turn, float strength) {
        if (strength <= 0f) return;
        orbit.set(-radius, -radius, radius, radius);
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(turn);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setShader(shader);
        paint.setStrokeWidth(dp(5));
        paint.setAlpha(Math.round(strength * 38));
        canvas.drawArc(orbit, 0, 124f, false, paint);
        paint.setStrokeWidth(dp(1.5f));
        paint.setAlpha(Math.round(strength * 230));
        canvas.drawArc(orbit, 0, 124f, false, paint);
        canvas.restore();
    }

    private void drawFragments(Canvas canvas, float radius, float time, float rotation) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 9; i++) {
            float age = time - i % 3 * 24f;
            float opacity = ease(age, 20f, 160f) * (1f - ease(age, 325f, 540f));
            float angle = (float) Math.toRadians(29f + i * 40f + 54f * rotation);
            float distance = radius * (1.47f + .13f * (i % 3) - .86f * rotation);
            float x = (float) Math.cos(angle) * distance;
            float y = (float) Math.sin(angle) * distance;
            float dot = dp(i % 3 == 0 ? 1.8f : 1f);
            paint.setColor(i % 2 == 0 ? LAVENDER : PINK);
            paint.setAlpha(Math.round(opacity * 55));
            canvas.drawCircle(x, y, dot * 2.1f, paint);
            paint.setAlpha(Math.round(opacity * 218));
            canvas.drawCircle(x, y, dot, paint);
        }
    }

    private void drawLightPass(Canvas canvas) {
        float sweep = ease(revealProgress, 0f, .78f);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(lightPass);
        paint.setAlpha(Math.round(230 * (float) Math.sin(Math.PI * sweep)));
        canvas.save();
        canvas.translate(getWidth() * (sweep * 1.45f - .23f), getHeight() / 2f);
        canvas.rotate(-23f);
        canvas.scale(getWidth() * .24f, 1f);
        canvas.drawRect(-1f, -getHeight(), 1f, getHeight(), paint);
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
        reveal.setDuration(REVEAL_MS);
        reveal.setInterpolator(new PathInterpolator(.4f, 0f, .2f, 1f));
        reveal.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            revealProgress = progress;
            float scale = 1f + .08f * ease(progress, 0f, .28f);
            emblem.setScaleX(scale);
            emblem.setScaleY(scale);
            // Clear the launch mark before revealing the library's separate masthead.
            setIdentityAlpha(1f - ease(progress, .035f, .35f));
            setAlpha(Math.min(1f, (1f - progress) / .65f));
            invalidate();
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
        systemSplash = splash;
        removeSystemSplash = remove;
        startEntrance();
        splash.animate().alpha(0f).setDuration(150).withEndAction(this::finishSystemHandoff).start();
    }

    private void finishSystemHandoff() {
        Runnable remove = removeSystemSplash;
        removeSystemSplash = null;
        systemSplash = null;
        if (remove != null) remove.run();
    }

    private void setIdentityAlpha(float alpha) {
        emblem.setAlpha(alpha);
        title.setAlpha(alpha);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(startEntrance);
        if (systemSplash != null) systemSplash.animate().cancel();
        finishSystemHandoff();
        libraryReady = null;
        waitingForLibrary = null;
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
            setPivotY(markCenterY());
        }

        private float markCenterY() {
            var bounds = signature.getBounds();
            return bounds.top + bounds.height() * 49f / 108f;
        }

        @Override protected void onDraw(Canvas canvas) {
            // The launcher vector uses a 108-unit canvas with an empty seam at y=71
            // between the book and the stationary MJ signature.
            var bounds = signature.getBounds();
            int seam = bounds.top + Math.round(bounds.height() * 71f / 108f);
            float settle = 1f + .04f * ease(entranceTime, 410f, 540f) * (1f - ease(entranceTime, 540f, ENTRANCE_MS));
            canvas.save();
            canvas.scale(settle, settle, bounds.exactCenterX(), markCenterY());
            canvas.save();
            canvas.clipRect(0, seam, getWidth(), getHeight());
            signature.setAlpha(Math.round(255 * ease(entranceTime, 155f, 310f)));
            signature.draw(canvas);
            canvas.restore();
            drawPage(canvas, leftPage, true, ease(entranceTime, 180f, 508f), seam);
            drawPage(canvas, rightPage, false, ease(entranceTime, 206f, 540f), seam);
            canvas.restore();
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

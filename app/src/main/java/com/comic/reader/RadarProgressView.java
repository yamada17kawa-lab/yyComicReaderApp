package com.comic.reader;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * 环形雷达扫描进度控件 (Material Design 3 风格)
 * 顺时针绘制：
 * - 红色部分：未完成进度
 * - 绿色部分：已完成进度
 * - 雷达扫描指针：顺时针持续旋转，具有生动的科技感扫掠光效
 * - 中心显示当前百分比数字
 */
public class RadarProgressView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint redTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint greenArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF arcRect = new RectF();
    private float progress = 0f; // 0.0 ~ 1.0
    private float radarAngle = 0f; // 0 ~ 360
    private ValueAnimator sweepAnimator;

    private static final int COLOR_BG = Color.parseColor("#1A1D26");
    private static final int COLOR_RED = Color.parseColor("#EF4444");
    private static final int COLOR_GREEN = Color.parseColor("#10B981");
    private static final int COLOR_NEEDLE = Color.parseColor("#34D399");

    public RadarProgressView(Context context) {
        this(context, null);
    }

    public RadarProgressView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RadarProgressView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        bgPaint.setColor(COLOR_BG);
        bgPaint.setStyle(Paint.Style.FILL);

        redTrackPaint.setColor(COLOR_RED);
        redTrackPaint.setStyle(Paint.Style.STROKE);
        redTrackPaint.setStrokeWidth(dp2px(3.8f));
        redTrackPaint.setStrokeCap(Paint.Cap.ROUND);

        greenArcPaint.setColor(COLOR_GREEN);
        greenArcPaint.setStyle(Paint.Style.STROKE);
        greenArcPaint.setStrokeWidth(dp2px(3.8f));
        greenArcPaint.setStrokeCap(Paint.Cap.ROUND);

        needlePaint.setColor(COLOR_NEEDLE);
        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeWidth(dp2px(2.0f));
        needlePaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setColor(Color.WHITE);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(dp2px(11f));

        sweepPaint.setStyle(Paint.Style.FILL);

        sweepAnimator = ValueAnimator.ofFloat(0f, 360f);
        sweepAnimator.setDuration(1600);
        sweepAnimator.setRepeatCount(ValueAnimator.INFINITE);
        sweepAnimator.setInterpolator(new LinearInterpolator());
        sweepAnimator.addUpdateListener(anim -> {
            radarAngle = (float) anim.getAnimatedValue();
            invalidate();
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE && sweepAnimator != null && !sweepAnimator.isStarted()) {
            sweepAnimator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (sweepAnimator != null) {
            sweepAnimator.cancel();
        }
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) {
            if (sweepAnimator != null && !sweepAnimator.isStarted()) {
                sweepAnimator.start();
            }
        } else {
            if (sweepAnimator != null) {
                sweepAnimator.cancel();
            }
        }
    }

    /**
     * 设置进度 (0.0 ~ 1.0 或 0 ~ 100)
     */
    public void setProgress(float p) {
        if (p > 1.0f) p = p / 100.0f;
        this.progress = Math.max(0f, Math.min(1.0f, p));
        invalidate();
    }

    public float getProgress() {
        return progress;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float cy = h / 2f;
        float stroke = dp2px(3.8f);
        float r = Math.min(cx, cy) - stroke;

        // 1. 绘制暗色圆盘背景
        canvas.drawCircle(cx, cy, r + stroke / 2f, bgPaint);

        // 2. 绘制顺时针环形进度 (从 12 点钟位置 -90 度开始)
        arcRect.set(cx - r, cy - r, cx + r, cy + r);

        // 红色部分：未完成部分
        canvas.drawCircle(cx, cy, r, redTrackPaint);

        // 绿色部分：已完成部分 (顺时针覆盖)
        if (progress > 0f) {
            float sweepAngle = 360f * progress;
            canvas.drawArc(arcRect, -90f, sweepAngle, false, greenArcPaint);
        }

        // 3. 绘制顺时针雷达扫描动态波束与指针
        canvas.save();
        canvas.rotate(radarAngle, cx, cy);

        // 雷达扫掠扇形浅光晕 (顺时针后方 60 度渐变)
        int[] colors = new int[]{
                Color.argb(0, 16, 185, 129),
                Color.argb(100, 16, 185, 129),
                Color.argb(210, 52, 211, 153)
        };
        float[] positions = new float[]{0.75f, 0.95f, 1.0f};
        SweepGradient sweepGradient = new SweepGradient(cx, cy, colors, positions);
        sweepPaint.setShader(sweepGradient);
        canvas.drawCircle(cx, cy, r - stroke / 2f, sweepPaint);

        // 雷达扫描针 (从中心到边缘)
        canvas.drawLine(cx, cy, cx + r - stroke / 2f, cy, needlePaint);
        canvas.restore();

        // 4. 中心百分比文字
        String text;
        if (progress >= 1.0f) {
            text = "✓";
        } else {
            text = Math.round(progress * 100) + "%";
        }
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, cx, textY, textPaint);
    }

    private float dp2px(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}

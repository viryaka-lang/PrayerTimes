package com.ptimes.rabat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

/** A ring that fills clockwise from the previous prayer to the next one. */
public class CountdownRingView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float stroke;
    private final float dotRadius;
    private final int gold;
    private final int goldDark;

    private float progress;

    public CountdownRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float dp = getResources().getDisplayMetrics().density;
        stroke = 8 * dp;
        dotRadius = 7 * dp;
        gold = context.getColor(R.color.gold);
        goldDark = context.getColor(R.color.gold_dark);

        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(context.getColor(R.color.divider));

        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);

        dot.setColor(gold);
        dot.setShadowLayer(6 * dp, 0, 0, gold);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // for the dot's glow
    }

    /** 0 just after the previous prayer, 1 when the next one starts. */
    public void setProgress(float value) {
        float p = Math.max(0f, Math.min(1f, value));
        if (p != progress) {
            progress = p;
            updateShader();
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float inset = dotRadius + stroke / 2;
        oval.set(inset, inset, w - inset, h - inset);
        updateShader();
    }

    /** The arc brightens from start to tip, whatever its length. */
    private void updateShader() {
        if (oval.isEmpty()) return;
        arc.setShader(new SweepGradient(oval.centerX(), oval.centerY(),
                new int[]{goldDark, gold}, new float[]{0f, Math.max(progress, 0.01f)}));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = oval.centerX(), cy = oval.centerY(), r = oval.width() / 2;

        canvas.drawOval(oval, track);

        float sweep = 360f * progress;
        canvas.save();
        canvas.rotate(-90, cx, cy); // start at 12 o'clock
        if (sweep > 0.5f) canvas.drawArc(oval, 0, sweep, false, arc);
        double a = Math.toRadians(sweep);
        canvas.drawCircle(cx + (float) (r * Math.cos(a)), cy + (float) (r * Math.sin(a)), dotRadius, dot);
        canvas.restore();
    }
}

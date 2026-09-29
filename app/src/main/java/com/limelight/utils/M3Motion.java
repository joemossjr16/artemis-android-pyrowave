package com.limelight.utils;

import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.limelight.R;

/** Shared Material motion primitives for the non-streaming client UI. */
public final class M3Motion {
    private static final DecelerateInterpolator DECELERATE = new DecelerateInterpolator();

    private M3Motion() {
    }

    public static void enter(View view, int position) {
        if (view.getTag(R.id.m3_motion_animated) != null) return;
        view.setTag(R.id.m3_motion_animated, Boolean.TRUE);
        view.setAlpha(0f);
        view.setTranslationY(18f);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(Math.min(position, 6) * 32L)
                .setDuration(260L)
                .setInterpolator(DECELERATE)
                .start();
    }

    public static void attachPressFeedback(View view) {
        if (view.getTag(R.id.m3_motion_animated) == null) {
            enter(view, 0);
        }
        view.setOnTouchListener((touched, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touched.animate().scaleX(0.985f).scaleY(0.985f)
                            .setDuration(90L).setInterpolator(DECELERATE).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touched.animate().scaleX(1f).scaleY(1f)
                            .setDuration(180L).setInterpolator(DECELERATE).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }
}

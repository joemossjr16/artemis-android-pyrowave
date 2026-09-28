package com.limelight.ui;

import android.app.Activity;
import android.graphics.Rect;

import androidx.core.util.Consumer;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowInfoTracker;
import androidx.window.layout.WindowLayoutInfo;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * A hinge across the screen, tabletop-posture only: a foldable held like a laptop, video on the
 * upper half and the on-screen controller on the lower half. Ported from punktfunk's
 * FoldSplit.kt (design/touch-client-overlay.md's approach) since Moonlight has no equivalent.
 *
 * A hinge that doesn't span the full width (book posture, held like a paperback) or that leaves
 * either half too small to be useful is not a split - {@link #split} returns null and the caller
 * should behave exactly as it does today.
 */
public final class FoldPosture {
    private FoldPosture() {}

    /** The two halves, in window px. The hinge itself is excluded from both. */
    public static final class Split {
        public final int videoPx;
        public final int hingePx;

        public Split(int videoPx, int hingePx) {
            this.videoPx = videoPx;
            this.hingePx = hingePx;
        }
    }

    /**
     * The split for a hinge at {@code hinge} across a {@code width}x{@code height} window, or
     * null when this fold can't carry one. Both rects are window px.
     */
    public static Split split(Rect hinge, int width, int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        // A hinge that doesn't span the full width folds the screen left/right (book posture) -
        // no flat upper/lower half to split video from controller.
        if (hinge.left > 0 || hinge.right < width) {
            return null;
        }
        int video = clamp(hinge.top, 0, height);
        int gap = clamp(hinge.bottom - hinge.top, 0, height - video);
        int min = height / 5;
        if (video < min || height - video - gap < min) {
            return null;
        }
        return new Split(video, gap);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Notified with the current hinge bounds, or null when the device is flat/shut/single-panel. */
    public interface Listener {
        void onHingeChanged(Rect hingeOrNull);
    }

    /**
     * Starts observing this activity's fold posture. Call {@link Handle#close()} (e.g. from
     * onDestroy) to stop. A no-op on devices/OS versions the window library doesn't support -
     * the listener simply never fires, same as punktfunk's null-flow fallback.
     */
    public static Handle observe(Activity activity, Executor executor, Listener listener) {
        WindowInfoTrackerCallbackAdapter adapter =
                new WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(activity));
        Consumer<WindowLayoutInfo> consumer = info -> {
            Rect hinge = null;
            List<FoldingFeature> folds = foldingFeatures(info);
            for (FoldingFeature f : folds) {
                if (f.getState() == FoldingFeature.State.HALF_OPENED || f.isSeparating()) {
                    hinge = f.getBounds();
                    break;
                }
            }
            listener.onHingeChanged(hinge);
        };
        adapter.addWindowLayoutInfoListener(activity, executor, consumer);
        return () -> adapter.removeWindowLayoutInfoListener(consumer);
    }

    private static List<FoldingFeature> foldingFeatures(WindowLayoutInfo info) {
        List<FoldingFeature> result = new java.util.ArrayList<>();
        for (androidx.window.layout.DisplayFeature f : info.getDisplayFeatures()) {
            if (f instanceof FoldingFeature) {
                result.add((FoldingFeature) f);
            }
        }
        return result;
    }

    /** Closes (stops observing) the fold posture listener. */
    public interface Handle {
        void close();
    }
}

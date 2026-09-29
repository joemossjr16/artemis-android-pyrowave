package com.limelight.utils;

import java.util.ArrayList;
import java.util.Iterator;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;

public class SpinnerDialog implements Runnable,OnCancelListener {
    private final String title;
    private String message;
    private final Activity activity;
    private AlertDialog progress;
    private TextView messageView;
    private final boolean finish;

    private static final ArrayList<SpinnerDialog> rundownDialogs = new ArrayList<>();

    private SpinnerDialog(Activity activity, String title, String message, boolean finish)
    {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.progress = null;
        this.finish = finish;
    }

    public static SpinnerDialog displayDialog(Activity activity, String title, String message, boolean finish)
    {
        SpinnerDialog spinner = new SpinnerDialog(activity, title, message, finish);
        activity.runOnUiThread(spinner);
        return spinner;
    }

    public static void closeDialogs(Activity activity)
    {
        synchronized (rundownDialogs) {
            Iterator<SpinnerDialog> i = rundownDialogs.iterator();
            while (i.hasNext()) {
                SpinnerDialog dialog = i.next();
                if (dialog.activity == activity) {
                    i.remove();
                    if (dialog.progress != null && dialog.progress.isShowing()) {
                        dialog.progress.dismiss();
                    }
                    dialog.progress = null;
                    dialog.messageView = null;
                }
            }
        }
    }

    public void dismiss()
    {
        activity.runOnUiThread(() -> {
            synchronized (rundownDialogs) {
                rundownDialogs.remove(this);
            }
            if (progress != null && progress.isShowing()) {
                progress.dismiss();
            }
            progress = null;
            messageView = null;
        });
    }

    public void setMessage(final String message)
    {
        activity.runOnUiThread(() -> {
            this.message = message;
            if (messageView != null) {
                messageView.setText(message);
            }
        });
    }

    @Override
    public void run() {

        // If we're dying, don't bother doing anything
        if (activity.isFinishing()) {
            return;
        }

        if (progress == null)
        {
            int horizontalPadding = dp(24);
            int verticalPadding = dp(8);

            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

            CircularProgressIndicator indicator = new CircularProgressIndicator(activity);
            indicator.setIndeterminate(true);
            TypedValue primary = new TypedValue();
            if (activity.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, primary, true)) {
                indicator.setIndicatorColor(primary.data);
            }
            content.addView(indicator, new LinearLayout.LayoutParams(dp(40), dp(40)));

            messageView = new TextView(activity);
            messageView.setText(message);
            messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            messageView.setGravity(Gravity.CENTER_VERTICAL);
            TypedValue onSurface = new TypedValue();
            if (activity.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, onSurface, true)) {
                messageView.setTextColor(onSurface.data);
            } else {
                messageView.setTextColor(Color.WHITE);
            }
            LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            messageParams.leftMargin = dp(20);
            content.addView(messageView, messageParams);

            progress = new MaterialAlertDialogBuilder(activity)
                    .setTitle(title)
                    .setView(content)
                    .setOnCancelListener(this)
                    .create();
            progress.setCancelable(finish);
            progress.setCanceledOnTouchOutside(false);

            synchronized (rundownDialogs) {
                rundownDialogs.add(this);
                progress.show();
            }
        }
        else
        {
            synchronized (rundownDialogs) {
                if (rundownDialogs.remove(this) && progress.isShowing()) {
                    progress.dismiss();
                }
            }
        }
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        synchronized (rundownDialogs) {
            rundownDialogs.remove(this);
        }

        // This will only be called if finish was true, so we don't need to check again
        if (finish) {
            activity.finish();
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

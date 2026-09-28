/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;

import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * This is a digital button on screen element. It is used to get click and double click user input.
 */
public class DigitalButton extends VirtualControllerElement {

    /**
     * Listener interface to update registered observers.
     */
    public interface DigitalButtonListener {

        /**
         * onClick event will be fired on button click.
         */
        void onClick();

        /**
         * onLongClick event will be fired on button long click.
         */
        void onLongClick();

        /**
         * onRelease event will be fired on button unpress.
         */
        void onRelease();
    }

    private List<DigitalButtonListener> listeners = new ArrayList<>();
    private String text = "";
    private int icon = -1;

    private int iconPress=-1;
    private String faceButtonStyle = "xbox";
    private long timerLongClickTimeout = 3000;
    private final Runnable longClickRunnable = new Runnable() {
        @Override
        public void run() {
            onLongClickCallback();
        }
    };

    private final Paint paint = new Paint();
    private final RectF rect = new RectF();

    private int layer;
    private DigitalButton movingButton = null;

    boolean inRange(float x, float y) {
        return (this.getX() < x && this.getX() + this.getWidth() > x) &&
                (this.getY() < y && this.getY() + this.getHeight() > y);
    }

    public boolean checkMovement(float x, float y, DigitalButton movingButton) {
        // check if the movement happened in the same layer
        if (movingButton.layer != this.layer) {
            return false;
        }

        // save current pressed state
        boolean wasPressed = isPressed();

        // check if the movement directly happened on the button
        if ((this.movingButton == null || movingButton == this.movingButton)
                && this.inRange(x, y)) {
            // set button pressed state depending on moving button pressed state
            if (this.isPressed() != movingButton.isPressed()) {
                this.setPressed(movingButton.isPressed());
            }
        }
        // check if the movement is outside of the range and the movement button
        // is the saved moving button
        else if (movingButton == this.movingButton) {
            this.setPressed(false);
        }

        // check if a change occurred
        if (wasPressed != isPressed()) {
            if (isPressed()) {
                // is pressed set moving button and emit click event
                this.movingButton = movingButton;
                onClickCallback();
            } else {
                // no longer pressed reset moving button and emit release event
                this.movingButton = null;
                onReleaseCallback();
            }

            invalidate();

            return true;
        }

        return false;
    }

    private void checkMovementForAllButtons(float x, float y) {
        for (VirtualControllerElement element : virtualController.getElements()) {
            if (element != this && element instanceof DigitalButton) {
                ((DigitalButton) element).checkMovement(x, y, this);
            }
        }
    }

    public DigitalButton(VirtualController controller, int elementId, int layer, Context context) {
        super(controller, context, elementId);
        this.layer = layer;
    }

    public void addDigitalButtonListener(DigitalButtonListener listener) {
        listeners.add(listener);
    }

    public void setText(String text) {
        this.text = text;
        invalidate();
    }

    public void setIcon(int id) {
        this.icon = id;
        invalidate();
    }

    public void setIconPress(int iconPress) {
        this.iconPress = iconPress;
    }

    public void setFaceButtonStyle(String style) {
        faceButtonStyle = style;
        invalidate();
    }

    @Override
    protected void onElementDraw(Canvas canvas) {
        // set transparent background
        canvas.drawColor(Color.TRANSPARENT);

        paint.setTextSize(getPercent(getWidth(), 25));

        paint.setTextAlign(Paint.Align.CENTER);

        paint.setStrokeWidth(getDefaultStrokeWidth());

        paint.setColor(isPressed() ? pressedColor:getDefaultColor());

        rect.left = rect.top = paint.getStrokeWidth();
        rect.right = getWidth() - rect.left;
        rect.bottom = getHeight() - rect.top;

        if (elementId == EID_A || elementId == EID_B || elementId == EID_X || elementId == EID_Y) {
            drawStyledFaceButton(canvas, PreferenceConfiguration.readPreferences(getContext()).oscOpacity);
            return;
        }

        //皮肤选择 官方皮肤
        if(PreferenceConfiguration.readPreferences(getContext()).enableOnScreenStyleOfficial){
            paint.setStyle(Paint.Style.STROKE);
            //方形
            if(PreferenceConfiguration.readPreferences(getContext()).enableKeyboardSquare){
                canvas.drawRect(rect,paint);
            }else{
                canvas.drawOval(rect, paint);
            }
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeWidth(getDefaultStrokeWidth()/2);
            canvas.drawText(text, getPercent(getWidth(), 50), getPercent(getHeight(), 63), paint);
            return;
        }
        int oscOpacity=PreferenceConfiguration.readPreferences(getContext()).oscOpacity;
        //虚拟手柄皮肤
        if (icon != -1) {
            Drawable d = getResources().getDrawable(isPressed()?iconPress:icon);
            d.setBounds(5, 5, getWidth() - 5, getHeight() - 5);
            d.setAlpha((int) (oscOpacity*2.55));
            d.draw(canvas);
        }else{
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(getDefaultStrokeWidth()/2);
            canvas.drawText(text, getPercent(getWidth(), 50), getPercent(getHeight(), 63), paint);
        }

        boolean bIsMoving = virtualController.getControllerMode() == VirtualController.ControllerMode.MoveButtons;
        boolean bIsResizing = virtualController.getControllerMode() == VirtualController.ControllerMode.ResizeButtons;
        boolean bIsEnable = virtualController.getControllerMode() == VirtualController.ControllerMode.DisableEnableButtons;

        if (bIsMoving || bIsResizing || bIsEnable ||icon==-1) {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(rect,paint);
        }

    }

    private void drawStyledFaceButton(Canvas canvas, int opacity) {
        int color;
        String label;
        if ("playstation".equals(faceButtonStyle)) {
            label = elementId == EID_A ? "×" : elementId == EID_B ? "○" : elementId == EID_X ? "□" : "△";
            color = elementId == EID_A ? Color.rgb(92, 170, 255) :
                    elementId == EID_B ? Color.rgb(255, 115, 154) :
                    elementId == EID_X ? Color.rgb(90, 190, 255) : Color.rgb(95, 210, 180);
        } else if ("nintendo".equals(faceButtonStyle)) {
            label = elementId == EID_A ? "A" : elementId == EID_B ? "B" : elementId == EID_X ? "X" : "Y";
            color = elementId == EID_A ? Color.rgb(230, 63, 67) :
                    elementId == EID_B ? Color.rgb(65, 145, 225) :
                    elementId == EID_X ? Color.rgb(75, 185, 115) : Color.rgb(235, 190, 55);
        } else {
            label = elementId == EID_A ? "A" : elementId == EID_B ? "B" : elementId == EID_X ? "X" : "Y";
            color = elementId == EID_A ? Color.rgb(90, 190, 90) :
                    elementId == EID_B ? Color.rgb(220, 70, 65) :
                    elementId == EID_X ? Color.rgb(65, 135, 220) : Color.rgb(225, 190, 55);
        }

        int alpha = Math.max(0, Math.min(255, Math.round(opacity * 2.55f)));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(isPressed() ? pressedColor : Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)));
        float inset = Math.max(3, getWidth() * 0.07f);
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawOval(rect, paint);

        paint.setColor(Color.argb(alpha, 255, 255, 255));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(getPercent(getWidth(), "playstation".equals(faceButtonStyle) ? 39 : 32));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawText(label, getWidth() / 2f, getHeight() * 0.63f, paint);
    }

    private void onClickCallback() {
        _DBG("clicked");
        // notify listeners
        for (DigitalButtonListener listener : listeners) {
            listener.onClick();
        }

        virtualController.getHandler().removeCallbacks(longClickRunnable);
        virtualController.getHandler().postDelayed(longClickRunnable, timerLongClickTimeout);
    }

    private void onLongClickCallback() {
        _DBG("long click");
        // notify listeners
        for (DigitalButtonListener listener : listeners) {
            listener.onLongClick();
        }
    }

    private void onReleaseCallback() {
        _DBG("released");
        // notify listeners
        for (DigitalButtonListener listener : listeners) {
            listener.onRelease();
        }

        // We may be called for a release without a prior click
        virtualController.getHandler().removeCallbacks(longClickRunnable);
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        // get masked (not specific to a pointer) action
        float x = getX() + event.getX();
        float y = getY() + event.getY();
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                movingButton = null;
                setPressed(true);
                onClickCallback();

                invalidate();

                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                checkMovementForAllButtons(x, y);

                return true;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                setPressed(false);
                onReleaseCallback();

                checkMovementForAllButtons(x, y);

                invalidate();

                return true;
            }
            default: {
            }
        }
        return true;
    }
}

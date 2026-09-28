/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.Game;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class VirtualController {
    public static class ControllerInputContext {
//        public short inputMap = 0x0000;
        public int inputMap = 0;
        public byte leftTrigger = 0x00;
        public byte rightTrigger = 0x00;
        public short rightStickX = 0x0000;
        public short rightStickY = 0x0000;
        public short leftStickX = 0x0000;
        public short leftStickY = 0x0000;
    }

    public enum ControllerMode {
        Active,
        MoveButtons,
        ResizeButtons,
        DisableEnableButtons
    }

    private static final boolean _PRINT_DEBUG_INFORMATION = false;

    private final ControllerHandler controllerHandler;
    private final Context context;
    private final Handler handler;

    private final Runnable delayedRetransmitRunnable = new Runnable() {
        @Override
        public void run() {
            sendControllerInputContextInternal();
        }
    };

    private FrameLayout frame_layout = null;

    ControllerMode currentMode = ControllerMode.Active;
    ControllerInputContext inputContext = new ControllerInputContext();

    private Button buttonConfigure = null;

    private List<VirtualControllerElement> elements = new ArrayList<>();
    private static class LayoutBounds {
        final int left;
        final int top;
        final int width;
        final int height;

        LayoutBounds(FrameLayout.LayoutParams params) {
            left = params.leftMargin;
            top = params.topMargin;
            width = params.width;
            height = params.height;
        }

        LayoutBounds(int left, int top, int width, int height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }
    private final IdentityHashMap<View, LayoutBounds> fullScreenBounds = new IdentityHashMap<>();
    private final Map<Integer, LayoutBounds> faceButtonBaseBounds = new java.util.HashMap<>();
    private int foldSplitTop = -1;
    private int foldSplitRootHeight;
    private String layoutProfile = "advanced";
    private String buttonStyle = "xbox";
    private String displayMode = "pc_browsing";
    private TextView pcTouchpad;
    private TextView pcLeftClick;
    private TextView pcRightClick;
    private ImageButton pcKeyboard;

    private Vibrator vibrator;

    private final VibrationEffect defaultVibrationEffect;

    public VirtualController(final ControllerHandler controllerHandler, FrameLayout layout, final Context context) {
        this.controllerHandler = controllerHandler;
        this.frame_layout = layout;
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());

        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultVibrationEffect = VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE);
        } else {
            defaultVibrationEffect = null;
        }

        buttonConfigure = new Button(context);
        buttonConfigure.setAlpha(0.25f);
        buttonConfigure.setFocusable(false);
        buttonConfigure.setBackgroundResource(R.drawable.ic_settings);
        buttonConfigure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentMode == ControllerMode.Active) {
                    showControllerSettings();
                } else {
                    beginConfigurationMode();
                }
            }
        });

    }

    Handler getHandler() {
        return handler;
    }

    public void hide() {
        for (VirtualControllerElement element : elements) {
            element.setVisibility(View.GONE);
        }
        setPcControlsVisibility(View.GONE);
        buttonConfigure.setVisibility(View.GONE);
    }

    public void show() {
        updateDisplayModeVisibility();
        buttonConfigure.setVisibility(View.VISIBLE);
    }

    public void setDisplayMode(String mode) {
        displayMode = "gaming".equals(mode) ? "gaming" :
                ("vanilla".equals(mode) ? "vanilla" : "pc_browsing");
        if (!elements.isEmpty()) {
            updateDisplayModeVisibility();
            if (buttonConfigure.getVisibility() == View.VISIBLE) {
                positionPcBrowsingControls();
            }
        }
    }

    private void updateDisplayModeVisibility() {
        if ("vanilla".equals(displayMode)) {
            for (VirtualControllerElement element : elements) element.setVisibility(View.GONE);
            setPcControlsVisibility(View.GONE);
            buttonConfigure.setVisibility(View.VISIBLE);
        } else if ("pc_browsing".equals(displayMode)) {
            for (VirtualControllerElement element : elements) element.setVisibility(View.GONE);
            ensurePcBrowsingControls();
            setPcControlsVisibility(View.VISIBLE);
            positionPcBrowsingControls();
        } else {
            setPcControlsVisibility(View.GONE);
            showEnabledElements();
        }
    }

    private void ensurePcBrowsingControls() {
        if (pcTouchpad != null) return;
        pcTouchpad = createPcControl(context.getString(R.string.pc_mode_touchpad), true);
        pcTouchpad.setOnTouchListener((view, event) -> context instanceof Game &&
                ((Game) context).handlePcBrowsingTrackpadTouch(event));
        pcLeftClick = createPcControl(context.getString(R.string.pc_mode_left_click), false);
        pcLeftClick.setOnTouchListener((view, event) -> {
            if (!(context instanceof Game)) return false;
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    ((Game) context).setPcBrowsingMouseButton(
                            com.limelight.nvstream.input.MouseButtonPacket.BUTTON_LEFT, true);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    ((Game) context).setPcBrowsingMouseButton(
                            com.limelight.nvstream.input.MouseButtonPacket.BUTTON_LEFT, false);
                    return true;
                default:
                    return true;
            }
        });
        pcRightClick = createPcControl(context.getString(R.string.pc_mode_right_click), false);
        pcRightClick.setOnClickListener(view -> {
            if (context instanceof Game) ((Game) context).sendPcBrowsingMouseClick(
                    com.limelight.nvstream.input.MouseButtonPacket.BUTTON_RIGHT);
        });
        pcKeyboard = new ImageButton(context);
        pcKeyboard.setImageResource(R.drawable.ic_android_keyboard);
        pcKeyboard.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int iconPadding = Math.round(8 * context.getResources().getDisplayMetrics().density);
        pcKeyboard.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
        pcKeyboard.setContentDescription(context.getString(R.string.pc_mode_keyboard));
        GradientDrawable keyboardBackground = new GradientDrawable();
        keyboardBackground.setColor(0xCC30343B);
        keyboardBackground.setCornerRadius(14f);
        keyboardBackground.setStroke(2, 0x99FFFFFF);
        pcKeyboard.setBackground(keyboardBackground);
        pcKeyboard.setFocusable(false);
        pcKeyboard.setOnClickListener(view -> {
            if (context instanceof Game) ((Game) context).toggleKeyboard();
        });
        frame_layout.addView(pcTouchpad);
        frame_layout.addView(pcLeftClick);
        frame_layout.addView(pcRightClick);
        frame_layout.addView(pcKeyboard);
        pcTouchpad.setElevation(8f);
        pcLeftClick.setElevation(9f);
        pcRightClick.setElevation(9f);
        pcKeyboard.setElevation(10f);
    }

    private TextView createPcControl(String label, boolean touchSurface) {
        TextView control = new TextView(context);
        control.setText(label);
        control.setTextColor(Color.WHITE);
        control.setTextSize(15);
        control.setGravity(Gravity.CENTER);
        control.setFocusable(false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(touchSurface ? 0x66202020 : 0xCC30343B);
        background.setCornerRadius(18f);
        background.setStroke(2, 0x99FFFFFF);
        control.setBackground(background);
        return control;
    }

    private void setPcControlsVisibility(int visibility) {
        if (pcTouchpad != null) pcTouchpad.setVisibility(visibility);
        if (pcLeftClick != null) pcLeftClick.setVisibility(visibility);
        if (pcRightClick != null) pcRightClick.setVisibility(visibility);
        if (pcKeyboard != null) pcKeyboard.setVisibility(visibility);
    }

    private void positionPcBrowsingControls() {
        if (pcTouchpad == null || frame_layout == null) return;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = frame_layout.getWidth() > 0 ? frame_layout.getWidth() : metrics.widthPixels;
        int rootHeight = frame_layout.getHeight() > 0 ? frame_layout.getHeight() : metrics.heightPixels;
        int top = foldSplitTop >= 0 ? foldSplitTop : 0;
        int height = foldSplitTop >= 0 ? foldSplitRootHeight - foldSplitTop : rootHeight;
        if (height <= 0) return;

        int padWidth = Math.round(width * 0.70f);
        int padHeight = Math.round(height * 0.66f);
        int marginX = Math.round(width * 0.035f);
        int padTop = top + Math.round(height * 0.29f);
        setOverlayBounds(pcTouchpad, marginX, padTop, padWidth, padHeight);

        int buttonX = Math.round(width * 0.77f);
        int buttonWidth = Math.round(width * 0.195f);
        int buttonHeight = Math.round(height * 0.22f);
        int firstButtonTop = top + Math.round(height * 0.31f);
        int buttonGap = Math.round(height * 0.055f);
        setOverlayBounds(pcLeftClick, buttonX, firstButtonTop, buttonWidth, buttonHeight);
        setOverlayBounds(pcRightClick, buttonX, firstButtonTop + buttonHeight + buttonGap, buttonWidth, buttonHeight);
        int keyboardSize = Math.round(46 * metrics.density);
        int keyboardMargin = Math.round(12 * metrics.density);
        setOverlayBounds(pcKeyboard, width - keyboardSize - keyboardMargin,
                top + height - keyboardSize - keyboardMargin, keyboardSize, keyboardSize);
    }

    private void setOverlayBounds(View view, int x, int y, int width, int height) {
        FrameLayout.LayoutParams params = view.getLayoutParams() instanceof FrameLayout.LayoutParams ?
                (FrameLayout.LayoutParams) view.getLayoutParams() : new FrameLayout.LayoutParams(width, height);
        params.width = width;
        params.height = height;
        params.leftMargin = x;
        params.topMargin = y;
        view.setLayoutParams(params);
    }

    public int switchShowHide() {
        if ("vanilla".equals(displayMode)) {
            showControllerSettings();
            return 0;
        }
        if (buttonConfigure.getVisibility() == View.VISIBLE) {
            hide();
            return 0;
        } else {
            show();
            return 1;
        }
    }

    public void showElements(){
        for(VirtualControllerElement element : elements){
            element.setVisibility(View.VISIBLE);
        }
    }

    public void showEnabledElements(){
        for(VirtualControllerElement element: elements){
            element.setVisibility(element.enabled && profileAllows(element.elementId) ? View.VISIBLE : View.GONE);
        }
    }

    private void showControllerSettings() {
        String activeMode = context instanceof Game ? ((Game) context).getControllerDisplayMode() : displayMode;
        int modeLabel = "gaming".equals(activeMode) ? R.string.stream_input_mode_gaming :
                ("vanilla".equals(activeMode) ? R.string.stream_input_mode_vanilla : R.string.stream_input_mode_browsing);
        String activeProfile = context instanceof Game ?
                ((Game) context).getControllerLayoutProfile() : layoutProfile;
        int selected = "basic".equals(activeProfile) ? 0 :
                ("standard".equals(activeProfile) ? 1 : 2);
        String activeLayout = context instanceof Game ? ((Game) context).getControllerLayoutVariant() : "full_screen_lower_half";
        String layoutLabel = "fold_split".equals(activeLayout) ?
                context.getString(R.string.stream_layout_fold_split) :
                context.getString(R.string.stream_layout_fullscreen_lower_half);
        String[] options = new String[] {
                context.getString(R.string.stream_input_mode_title) + ": " + context.getString(modeLabel),
                context.getString(R.string.stream_layout_title) + ": " + layoutLabel,
                context.getString(R.string.game_menu_controller_layout_basic) + (selected == 0 ? "  ✓" : ""),
                context.getString(R.string.game_menu_controller_layout_standard) + (selected == 1 ? "  ✓" : ""),
                context.getString(R.string.game_menu_controller_layout_advanced) + (selected == 2 ? "  ✓" : ""),
                context.getString(R.string.controller_settings_button_style),
                context.getString(R.string.controller_settings_customize)
        };
        new AlertDialog.Builder(context)
                .setTitle(R.string.controller_settings_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0 && context instanceof Game) {
                        showInputModeSettings((Game) context);
                    } else if (which == 1 && context instanceof Game) {
                        showControlLayoutSettings((Game) context);
                    } else if (which >= 2 && which <= 4) {
                        String profile = which == 2 ? "basic" : (which == 3 ? "standard" : "advanced");
                        layoutProfile = profile;
                        if (context instanceof Game) {
                            ((Game) context).setControllerLayoutProfile(profile);
                        } else {
                            showEnabledElements();
                        }
                    } else if (which == 5 && context instanceof Game) {
                        showButtonStyleSettings((Game) context);
                    } else if (which == 6) {
                        if ("gaming".equals(displayMode)) {
                            beginConfigurationMode();
                        } else {
                            Toast.makeText(context, R.string.controller_customize_gaming_only, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showControlLayoutSettings(Game game) {
        String[] layouts = new String[] {"full_screen_lower_half", "fold_split"};
        int[] labels = new int[] {R.string.stream_layout_fullscreen_lower_half, R.string.stream_layout_fold_split};
        CharSequence[] choices = new CharSequence[layouts.length];
        String currentLayout = game.getControllerLayoutVariant();
        int selected = "fold_split".equals(currentLayout) ? 1 : 0;
        for (int i = 0; i < layouts.length; i++) {
            choices[i] = context.getString(labels[i]);
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.stream_layout_title)
                .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                    if (which >= 0 && which < layouts.length) {
                        game.setControllerLayoutVariant(layouts[which]);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showInputModeSettings(Game game) {
        String[] modes = new String[] {"gaming", "pc_browsing", "vanilla"};
        int[] labels = new int[] {R.string.stream_input_mode_gaming, R.string.stream_input_mode_browsing,
                R.string.stream_input_mode_vanilla};
        CharSequence[] choices = new CharSequence[modes.length];
        String currentMode = game.getControllerDisplayMode();
        int selected = 0;
        for (int i = 0; i < modes.length; i++) {
            choices[i] = context.getString(labels[i]);
            if (modes[i].equals(currentMode)) selected = i;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.stream_input_mode_title)
                .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                    if (which >= 0 && which < modes.length) {
                        game.setControllerDisplayMode(modes[which]);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showButtonStyleSettings(Game game) {
        String activeStyle = game.getControllerButtonStyle();
        String[] styles = new String[] {"xbox", "nintendo", "playstation"};
        int[] labels = new int[] {R.string.controller_style_xbox, R.string.controller_style_nintendo,
                R.string.controller_style_playstation};
        CharSequence[] choices = new CharSequence[styles.length];
        for (int i = 0; i < styles.length; i++) {
            String label = context.getString(labels[i]);
            choices[i] = label + (styles[i].equals(activeStyle) ? "  ✓" : "");
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.controller_settings_button_style)
                .setItems(choices, (dialog, which) -> {
                    if (which >= 0 && which < styles.length) {
                        game.setControllerButtonStyle(styles[which]);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void beginConfigurationMode() {
        String message;
        if (currentMode == ControllerMode.Active) {
            currentMode = ControllerMode.DisableEnableButtons;
            showElements();
            message = context.getString(R.string.configuration_mode_disable_enable_buttons);
        } else if (currentMode == ControllerMode.DisableEnableButtons) {
            currentMode = ControllerMode.MoveButtons;
            showEnabledElements();
            message = context.getString(R.string.configuration_mode_move_buttons);
        } else if (currentMode == ControllerMode.MoveButtons) {
            currentMode = ControllerMode.ResizeButtons;
            message = context.getString(R.string.configuration_mode_resize_buttons);
        } else {
            currentMode = ControllerMode.Active;
            VirtualControllerConfigurationLoader.saveProfile(this, context);
            message = context.getString(R.string.configuration_mode_exiting);
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        buttonConfigure.invalidate();
        for (VirtualControllerElement element : elements) {
            element.invalidate();
        }
    }

    public void setLayoutProfile(String profile) {
        if (!"basic".equals(profile) && !"standard".equals(profile)) {
            profile = "advanced";
        }
        layoutProfile = profile;
        showEnabledElements();
    }

    public void setButtonStyle(String style) {
        if (!"nintendo".equals(style) && !"playstation".equals(style)) {
            style = "xbox";
        }
        boolean styleChanged = !buttonStyle.equals(style);
        buttonStyle = style;
        applyButtonStyleArtOnly();
        if (foldSplitTop >= 0 && styleChanged) {
            applyFoldFaceButtonArrangement();
        } else if (foldSplitTop < 0) {
            applyBaseFaceButtonArrangement();
        }
    }

    private void applyButtonStyleArtOnly() {
        for (VirtualControllerElement element : elements) {
            if (element instanceof DigitalButton) {
                ((DigitalButton) element).setFaceButtonStyle(buttonStyle);
            }
        }
    }

    private boolean isFaceButton(int id) {
        return id == VirtualControllerElement.EID_A || id == VirtualControllerElement.EID_B ||
                id == VirtualControllerElement.EID_X || id == VirtualControllerElement.EID_Y;
    }

    private void captureFaceButtonBaseBounds() {
        faceButtonBaseBounds.clear();
        for (VirtualControllerElement element : elements) {
            if (isFaceButton(element.elementId) && element.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                faceButtonBaseBounds.put(element.elementId,
                        new LayoutBounds((FrameLayout.LayoutParams) element.getLayoutParams()));
            }
        }
    }

    private void applyBaseFaceButtonArrangement() {
        for (VirtualControllerElement element : elements) {
            if (!isFaceButton(element.elementId)) continue;
            int sourceId = element.elementId;
            if ("nintendo".equals(buttonStyle)) {
                if (sourceId == VirtualControllerElement.EID_A) sourceId = VirtualControllerElement.EID_B;
                else if (sourceId == VirtualControllerElement.EID_B) sourceId = VirtualControllerElement.EID_A;
                else if (sourceId == VirtualControllerElement.EID_X) sourceId = VirtualControllerElement.EID_Y;
                else if (sourceId == VirtualControllerElement.EID_Y) sourceId = VirtualControllerElement.EID_X;
            }
            LayoutBounds target = faceButtonBaseBounds.get(sourceId);
            if (target == null) continue;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) element.getLayoutParams();
            params.leftMargin = target.left + (target.width - params.width) / 2;
            params.topMargin = target.top + (target.height - params.height) / 2;
            element.setLayoutParams(params);
        }
    }

    private void applyFoldFaceButtonArrangement() {
        int width = frame_layout.getWidth();
        int panelHeight = foldSplitRootHeight - foldSplitTop;
        if (width <= 0 || panelHeight <= 0) return;
        float centerX = width * 0.88f;
        float centerY = panelHeight * 0.39f;
        int face = Math.round(panelHeight * 0.095f);
        float offset = face * 1.05f;
        if ("nintendo".equals(buttonStyle)) {
            setCenteredBounds(VirtualControllerElement.EID_X, centerX, centerY - offset, face, face);
            setCenteredBounds(VirtualControllerElement.EID_Y, centerX - offset, centerY, face, face);
            setCenteredBounds(VirtualControllerElement.EID_A, centerX + offset, centerY, face, face);
            setCenteredBounds(VirtualControllerElement.EID_B, centerX, centerY + offset, face, face);
        } else {
            setCenteredBounds(VirtualControllerElement.EID_Y, centerX, centerY - offset, face, face);
            setCenteredBounds(VirtualControllerElement.EID_X, centerX - offset, centerY, face, face);
            setCenteredBounds(VirtualControllerElement.EID_B, centerX + offset, centerY, face, face);
            setCenteredBounds(VirtualControllerElement.EID_A, centerX, centerY + offset, face, face);
        }
    }

    private boolean profileAllows(int elementId) {
        if ("basic".equals(layoutProfile)) {
            return elementId == VirtualControllerElement.EID_DPAD ||
                    elementId == VirtualControllerElement.EID_A ||
                    elementId == VirtualControllerElement.EID_B ||
                    elementId == VirtualControllerElement.EID_BACK ||
                    elementId == VirtualControllerElement.EID_START;
        }
        if ("standard".equals(layoutProfile)) {
            return elementId != VirtualControllerElement.EID_RS &&
                    elementId != VirtualControllerElement.EID_RSB &&
                    elementId != VirtualControllerElement.EID_GDB &&
                    elementId != VirtualControllerElement.EID_TOUCHPAD;
        }
        return true;
    }

    public void removeElements() {
        for (VirtualControllerElement element : elements) {
            frame_layout.removeView(element);
        }
        elements.clear();

        frame_layout.removeView(buttonConfigure);
        removePcBrowsingControls();
        fullScreenBounds.clear();
    }

    private void removePcBrowsingControls() {
        if (pcTouchpad != null) frame_layout.removeView(pcTouchpad);
        if (pcLeftClick != null) frame_layout.removeView(pcLeftClick);
        if (pcRightClick != null) frame_layout.removeView(pcRightClick);
        if (pcKeyboard != null) frame_layout.removeView(pcKeyboard);
        pcTouchpad = null;
        pcLeftClick = null;
        pcRightClick = null;
        pcKeyboard = null;
    }

    public void setOpacity(int opacity) {
        for (VirtualControllerElement element : elements) {
            element.setOpacity(opacity);
        }
    }


    public void addElement(VirtualControllerElement element, int x, int y, int width, int height) {
        elements.add(element);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(width, height);
        layoutParams.setMargins(x, y, 0, 0);

        frame_layout.addView(element, layoutParams);
    }

    public List<VirtualControllerElement> getElements() {
        return elements;
    }

    private static final void _DBG(String text) {
        if (_PRINT_DEBUG_INFORMATION) {
            LimeLog.info("VirtualController: " + text);
        }
    }

    public void refreshLayout() {
        removeElements();

        DisplayMetrics screen = context.getResources().getDisplayMetrics();

        int buttonSize = (int)(screen.heightPixels*0.06f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonSize, buttonSize);
        params.leftMargin = 15;
        params.topMargin = 15;
        frame_layout.addView(buttonConfigure, params);

        // Start with the default layout
        VirtualControllerConfigurationLoader.createDefaultLayout(this, context);

        // Apply user preferences onto the default layout
        VirtualControllerConfigurationLoader.loadFromPreferences(this, context);
        captureFaceButtonBaseBounds();
        applyButtonStyleArtOnly();
        if (foldSplitTop >= 0) {
            applyFoldSplitTransform();
        } else {
            applyBaseFaceButtonArrangement();
        }
    }

    /** Constrains the touch controls to the lower region while tabletop fold split is active. */
    public void setFoldSplitRegion(int regionTop, int rootHeight) {
        if (regionTop < 0 || rootHeight <= regionTop) {
            restoreFullScreenBounds();
            foldSplitTop = -1;
            foldSplitRootHeight = 0;
            applyBaseFaceButtonArrangement();
            if (pcTouchpad != null) positionPcBrowsingControls();
            return;
        }

        if (foldSplitTop >= 0) {
            restoreBoundsFromSnapshot();
        }
        foldSplitTop = regionTop;
        foldSplitRootHeight = rootHeight;
        applyFoldSplitTransform();
        if (pcTouchpad != null) positionPcBrowsingControls();
    }

    private void applyFoldSplitTransform() {
        if (frame_layout == null || foldSplitTop < 0 || foldSplitRootHeight <= foldSplitTop) {
            return;
        }

        float scale = (float) (foldSplitRootHeight - foldSplitTop) / (float) foldSplitRootHeight;
        for (View view : elements) {
            applyFoldSplitTransform(view, scale);
        }
        applyFoldSplitTransform(buttonConfigure, scale);
        applyComfortableFoldLayout();
        VirtualControllerConfigurationLoader.loadFoldSplitProfile(this, context);
        if ("nintendo".equals(buttonStyle)) {
            applyFoldFaceButtonArrangement();
        }
    }

    private void applyComfortableFoldLayout() {
        int width = frame_layout.getWidth();
        int panelHeight = foldSplitRootHeight - foldSplitTop;
        if (width <= 0 || panelHeight <= 0) {
            return;
        }

        float w = width;
        float h = panelHeight;
        int stick = Math.round(h * 0.22f);
        int dpad = Math.round(h * 0.25f);
        int face = Math.round(h * 0.095f);
        int shoulderWidth = Math.round(w * 0.145f);
        int shoulderHeight = Math.round(h * 0.095f);
        int menuWidth = Math.round(w * 0.07f);
        int menuHeight = Math.round(h * 0.065f);

        setCenteredBounds(VirtualControllerElement.EID_LS, w * 0.29f, h * 0.39f, stick, stick);
        setCenteredBounds(VirtualControllerElement.EID_DPAD, w * 0.105f, h * 0.64f, dpad, dpad);
        setCenteredBounds(VirtualControllerElement.EID_RS, w * 0.70f, h * 0.64f, stick, stick);

        float faceCenterX = w * 0.88f;
        float faceCenterY = h * 0.39f;
        float faceOffset = face * 1.05f;
        setCenteredBounds(VirtualControllerElement.EID_Y, faceCenterX, faceCenterY - faceOffset, face, face);
        setCenteredBounds(VirtualControllerElement.EID_X, faceCenterX - faceOffset, faceCenterY, face, face);
        setCenteredBounds(VirtualControllerElement.EID_B, faceCenterX + faceOffset, faceCenterY, face, face);
        setCenteredBounds(VirtualControllerElement.EID_A, faceCenterX, faceCenterY + faceOffset, face, face);

        setCenteredBounds(VirtualControllerElement.EID_LT, w * 0.09f, h * 0.08f, shoulderWidth, shoulderHeight);
        setCenteredBounds(VirtualControllerElement.EID_LB, w * 0.25f, h * 0.08f, shoulderWidth, shoulderHeight);
        setCenteredBounds(VirtualControllerElement.EID_RB, w * 0.75f, h * 0.08f, shoulderWidth, shoulderHeight);
        setCenteredBounds(VirtualControllerElement.EID_RT, w * 0.91f, h * 0.08f, shoulderWidth, shoulderHeight);
        setCenteredBounds(VirtualControllerElement.EID_BACK, w * 0.44f, h * 0.18f, menuWidth, menuHeight);
        setCenteredBounds(VirtualControllerElement.EID_START, w * 0.56f, h * 0.18f, menuWidth, menuHeight);

        int stickClick = Math.round(h * 0.07f);
        setCenteredBounds(VirtualControllerElement.EID_LSB, w * 0.20f, h * 0.82f, stickClick, stickClick);
        setCenteredBounds(VirtualControllerElement.EID_RSB, w * 0.80f, h * 0.82f, stickClick, stickClick);
        setCenteredBounds(VirtualControllerElement.EID_GDB, w * 0.50f, h * 0.84f, menuWidth, menuHeight);
        setCenteredBounds(VirtualControllerElement.EID_TOUCHPAD, w * 0.50f, h * 0.55f,
                Math.round(w * 0.17f), Math.round(h * 0.10f));
    }

    private void setCenteredBounds(int elementId, float centerX, float centerY, int width, int height) {
        for (VirtualControllerElement element : elements) {
            if (element.elementId != elementId) {
                continue;
            }
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) element.getLayoutParams();
            params.leftMargin = Math.max(0, Math.round(centerX - width / 2f));
            params.topMargin = foldSplitTop + Math.max(0, Math.round(centerY - height / 2f));
            params.width = width;
            params.height = height;
            element.setLayoutParams(params);
            return;
        }
    }

    private void applyFoldSplitTransform(View view, float scale) {
        if (view == null || view.getLayoutParams() == null) {
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        fullScreenBounds.putIfAbsent(view, new LayoutBounds(params));
        LayoutBounds original = fullScreenBounds.get(view);
        params.leftMargin = original.left;
        params.topMargin = foldSplitTop + Math.round(original.top * scale);
        params.width = Math.max(1, Math.round(original.width * scale));
        params.height = Math.max(1, Math.round(original.height * scale));
        view.setLayoutParams(params);
    }

    private void restoreFullScreenBounds() {
        restoreBoundsFromSnapshot();
        foldSplitTop = -1;
        foldSplitRootHeight = 0;
    }

    private void restoreBoundsFromSnapshot() {
        for (Map.Entry<View, LayoutBounds> entry : fullScreenBounds.entrySet()) {
            View view = entry.getKey();
            if (view.getParent() != frame_layout || !(view.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
                continue;
            }
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            LayoutBounds bounds = entry.getValue();
            params.leftMargin = bounds.left;
            params.topMargin = bounds.top;
            params.width = bounds.width;
            params.height = bounds.height;
            view.setLayoutParams(params);
        }
        fullScreenBounds.clear();
    }

    public boolean isFoldSplitActive() {
        return foldSplitTop >= 0;
    }

    public ControllerMode getControllerMode() {
        return currentMode;
    }

    public ControllerInputContext getControllerInputContext() {
        return inputContext;
    }

    private void sendControllerInputContextInternal() {
        _DBG("INPUT_MAP + " + inputContext.inputMap);
        _DBG("LEFT_TRIGGER " + inputContext.leftTrigger);
        _DBG("RIGHT_TRIGGER " + inputContext.rightTrigger);
        _DBG("LEFT STICK X: " + inputContext.leftStickX + " Y: " + inputContext.leftStickY);
        _DBG("RIGHT STICK X: " + inputContext.rightStickX + " Y: " + inputContext.rightStickY);

        if (controllerHandler != null) {
            controllerHandler.reportOscState(
                    inputContext.inputMap,
                    inputContext.leftStickX,
                    inputContext.leftStickY,
                    inputContext.rightStickX,
                    inputContext.rightStickY,
                    inputContext.leftTrigger,
                    inputContext.rightTrigger
            );
        }
    }

    public void sendControllerInputContext(long vibrationDuration, int vibrationAmplitude) {
        // Cancel retransmissions of prior gamepad inputs
        handler.removeCallbacks(delayedRetransmitRunnable);

        sendControllerInputContextInternal();
        if (frame_layout != null && PreferenceConfiguration.readPreferences(context).enableKeyboardVibrate) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                VibrationEffect effect;
                if (vibrationDuration == 0) {
                    effect = defaultVibrationEffect;
                } else {
                    effect = VibrationEffect.createOneShot(vibrationDuration, vibrationAmplitude);
                }
                vibrator.vibrate(effect);
            } else {
                if (vibrationDuration == 0) {
                    vibrationDuration = 10;
                }
                vibrator.vibrate(vibrationDuration);
            }
        }
        // HACK: GFE sometimes discards gamepad packets when they are received
        // very shortly after another. This can be critical if an axis zeroing packet
        // is lost and causes an analog stick to get stuck. To avoid this, we retransmit
        // the gamepad state a few times unless another input event happens before then.
        handler.postDelayed(delayedRetransmitRunnable, 25);
        handler.postDelayed(delayedRetransmitRunnable, 50);
        handler.postDelayed(delayedRetransmitRunnable, 75);
    }

    public void sendControllerInputContext() {
        sendControllerInputContext(0, 0);
    }
}

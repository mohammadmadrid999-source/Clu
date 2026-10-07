package com.xim.simulator.mapper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * Invisible, focusable, non-touchable full-screen view. It takes the keyboard,
 * gamepad and (captured) mouse while touches still reach the game below.
 */
@SuppressLint("ViewConstructor")
public class CaptureView extends View {
    private final Mapper mapper;
    private final Runnable onInput;
    private final Runnable onExit;
    private int lastButtons;
    private boolean l2, r2;

    public CaptureView(Context c, Mapper mapper, Runnable onInput, Runnable onExit) {
        super(c);
        this.mapper = mapper;
        this.onInput = onInput;
        this.onExit = onExit;
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            requestFocus();
            requestPointerCapture();
        } else {
            mapper.resetInput();
            lastButtons = 0;
            onInput.run();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            onExit.run();
            return true;
        }
        mapper.pressed.add(keyCode);
        onInput.run();
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        mapper.pressed.remove(keyCode);
        onInput.run();
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        int src = e.getSource();
        boolean pad = (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (!pad || e.getActionMasked() != MotionEvent.ACTION_MOVE) return super.onGenericMotionEvent(e);

        mapper.lx = e.getAxisValue(MotionEvent.AXIS_X);
        mapper.ly = e.getAxisValue(MotionEvent.AXIS_Y);
        mapper.rx = e.getAxisValue(MotionEvent.AXIS_Z);
        mapper.ry = e.getAxisValue(MotionEvent.AXIS_RZ);

        // Analog triggers -> L2/R2 buttons.
        boolean nl2 = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f;
        boolean nr2 = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f;
        if (nl2 != l2) setPressed(KeyEvent.KEYCODE_BUTTON_L2, l2 = nl2);
        if (nr2 != r2) setPressed(KeyEvent.KEYCODE_BUTTON_R2, r2 = nr2);

        // D-pad reported as a hat.
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        setPressed(KeyEvent.KEYCODE_DPAD_LEFT, hx < -0.5f);
        setPressed(KeyEvent.KEYCODE_DPAD_RIGHT, hx > 0.5f);
        setPressed(KeyEvent.KEYCODE_DPAD_UP, hy < -0.5f);
        setPressed(KeyEvent.KEYCODE_DPAD_DOWN, hy > 0.5f);

        onInput.run();
        return true;
    }

    @Override
    public boolean onCapturedPointerEvent(MotionEvent e) {
        // Relative mouse: X/Y are deltas, including the batched history.
        float dx = 0, dy = 0;
        for (int i = 0; i < e.getHistorySize(); i++) {
            dx += e.getHistoricalX(i);
            dy += e.getHistoricalY(i);
        }
        mapper.mouseDx += dx + e.getX();
        mapper.mouseDy += dy + e.getY();

        int bs = e.getButtonState();
        if (bs != lastButtons) {
            mapButton(bs, MotionEvent.BUTTON_PRIMARY, Mapper.MOUSE_LEFT);
            mapButton(bs, MotionEvent.BUTTON_SECONDARY, Mapper.MOUSE_RIGHT);
            mapButton(bs, MotionEvent.BUTTON_TERTIARY, Mapper.MOUSE_MIDDLE);
            mapButton(bs, MotionEvent.BUTTON_BACK, Mapper.MOUSE_BACK);
            mapButton(bs, MotionEvent.BUTTON_FORWARD, Mapper.MOUSE_FORWARD);
            lastButtons = bs;
        }
        onInput.run();
        return true;
    }

    private void mapButton(int state, int mask, int code) {
        setPressed(code, (state & mask) != 0);
    }

    private void setPressed(int code, boolean down) {
        if (down) mapper.pressed.add(code);
        else mapper.pressed.remove(code);
    }
}

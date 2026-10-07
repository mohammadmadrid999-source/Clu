package com.xim.simulator.mapper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen editor drawn over the game: drag the joystick, camera area and
 * button markers onto the game's own controls.
 */
@SuppressLint("ViewConstructor")
public class EditorView extends FrameLayout {
    public interface Listener {
        void onSave();

        void onClose();
    }

    private static final int SEL_NONE = -3, SEL_JOY = -2, SEL_CAM = -1;

    private final Mapper m;
    private final Listener listener;
    private final Board board;
    private final TextView hint;
    private boolean waitingForKey;
    private int selected = SEL_NONE;

    public EditorView(Context c, Mapper mapper, Listener listener) {
        super(c);
        this.m = mapper;
        this.listener = listener;
        setFocusable(true);
        setFocusableInTouchMode(true);

        board = new Board(c);
        addView(board, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE0161B24);
        bg.setCornerRadius(dp(10));
        bar.setBackground(bg);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));
        bar.addView(button(c, "+ زر", v -> {
            waitingForKey = true;
            updateHint();
        }));
        bar.addView(button(c, "حجم −", v -> resize(-0.01f)));
        bar.addView(button(c, "حجم +", v -> resize(0.01f)));
        bar.addView(button(c, "حفظ", v -> listener.onSave()));
        bar.addView(button(c, "إغلاق", v -> listener.onClose()));
        LayoutParams barLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        barLp.topMargin = dp(8);
        addView(bar, barLp);

        hint = new TextView(c);
        hint.setTextColor(Color.WHITE);
        hint.setBackgroundColor(0xB0000000);
        hint.setPadding(dp(8), dp(4), dp(8), dp(4));
        LayoutParams hintLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintLp.bottomMargin = dp(8);
        addView(hint, hintLp);
        updateHint();
    }

    private Button button(Context c, String label, OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void updateHint() {
        hint.setText(waitingForKey
                ? "اضغط مفتاحاً أو زر ماوس أو زر يد التحكم لربطه…"
                : "اسحب الدوائر فوق أزرار اللعبة · ضغطة مطوّلة على زر لحذفه · العصا = WASD/العصا اليسرى · الكاميرا = الماوس/العصا اليمنى");
    }

    private void resize(float d) {
        if (selected == SEL_JOY) m.joyR = Math.max(0.03f, Math.min(0.4f, m.joyR + d));
        else if (selected == SEL_CAM) m.camR = Math.max(0.05f, Math.min(0.6f, m.camR + d));
        board.invalidate();
    }

    private void bind(int code) {
        waitingForKey = false;
        m.buttons.add(new Mapper.ButtonMap(code, 0.5f, 0.5f));
        selected = m.buttons.size() - 1;
        updateHint();
        board.invalidate();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (waitingForKey && e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                waitingForKey = false;
                updateHint();
            } else {
                bind(e.getKeyCode());
            }
            return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            listener.onClose();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (waitingForKey && e.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS) {
            int code = mouseCode(e.getActionButton());
            if (code != 0) {
                bind(code);
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private static int mouseCode(int button) {
        switch (button) {
            case MotionEvent.BUTTON_PRIMARY: return Mapper.MOUSE_LEFT;
            case MotionEvent.BUTTON_SECONDARY: return Mapper.MOUSE_RIGHT;
            case MotionEvent.BUTTON_TERTIARY: return Mapper.MOUSE_MIDDLE;
            case MotionEvent.BUTTON_BACK: return Mapper.MOUSE_BACK;
            case MotionEvent.BUTTON_FORWARD: return Mapper.MOUSE_FORWARD;
            default: return 0;
        }
    }

    /** Draws and drags the markers. */
    private class Board extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int drag = SEL_NONE;
        private long downAt;
        private float downX, downY;
        private boolean moved;

        Board(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(2));
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(dp(13));
            text.setFakeBoldText(true);
        }

        private float btnR() {
            return dp(26);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(0x66000000);

            drawArea(c, m.joyX * w, m.joyY * h, m.joyR * h, 0xFF3FD0FF, "العصا", selected == SEL_JOY);
            drawArea(c, m.camX * w, m.camY * h, m.camR * h, 0xFF4ADE80, "الكاميرا", selected == SEL_CAM);

            for (int i = 0; i < m.buttons.size(); i++) {
                Mapper.ButtonMap b = m.buttons.get(i);
                float x = b.x * w, y = b.y * h;
                fill.setColor(i == selected ? 0xEEFF4D4D : 0xAAFF4D4D);
                c.drawCircle(x, y, btnR(), fill);
                stroke.setColor(Color.WHITE);
                c.drawCircle(x, y, btnR(), stroke);
                c.drawText(Mapper.codeName(b.code), x, y + dp(5), text);
            }
        }

        private void drawArea(Canvas c, float x, float y, float r, int color, String label, boolean sel) {
            fill.setColor((color & 0x00FFFFFF) | (sel ? 0x55000000 : 0x33000000));
            c.drawCircle(x, y, r, fill);
            stroke.setColor(color);
            c.drawCircle(x, y, r, stroke);
            c.drawCircle(x, y, dp(6), stroke);
            c.drawText(label, x, y - dp(12), text);
        }

        private int hitTest(float x, float y) {
            int w = getWidth(), h = getHeight();
            for (int i = m.buttons.size() - 1; i >= 0; i--) {
                Mapper.ButtonMap b = m.buttons.get(i);
                if (Math.hypot(x - b.x * w, y - b.y * h) < btnR() * 1.3) return i;
            }
            if (Math.hypot(x - m.joyX * w, y - m.joyY * h) < Math.max(dp(40), m.joyR * h)) return SEL_JOY;
            if (Math.hypot(x - m.camX * w, y - m.camY * h) < Math.max(dp(40), m.camR * h)) return SEL_CAM;
            return SEL_NONE;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int w = getWidth(), h = getHeight();
            if (waitingForKey && e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
                    && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                bind(Mapper.MOUSE_LEFT);
                return true;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    drag = hitTest(e.getX(), e.getY());
                    selected = drag;
                    downAt = SystemClock.uptimeMillis();
                    downX = e.getX();
                    downY = e.getY();
                    moved = false;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (drag == SEL_NONE) return true;
                    if (Math.hypot(e.getX() - downX, e.getY() - downY) > dp(8)) moved = true;
                    float fx = clamp01(e.getX() / w), fy = clamp01(e.getY() / h);
                    if (drag == SEL_JOY) { m.joyX = fx; m.joyY = fy; }
                    else if (drag == SEL_CAM) { m.camX = fx; m.camY = fy; }
                    else { m.buttons.get(drag).x = fx; m.buttons.get(drag).y = fy; }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (drag >= 0 && !moved && SystemClock.uptimeMillis() - downAt > 600) {
                        m.buttons.remove(drag);
                        selected = SEL_NONE;
                        invalidate();
                    }
                    drag = SEL_NONE;
                    return true;
                default:
                    return true;
            }
        }

        private float clamp01(float v) {
            return Math.max(0f, Math.min(1f, v));
        }
    }
}

package com.xim.simulator.mapper;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

/**
 * Accessibility service that turns mouse / keyboard / gamepad input into
 * touches inside any app. A floating bubble controls it:
 * tap = start/stop control, long-press = edit the on-screen layout.
 */
public class MapperService extends AccessibilityService implements GestureEngine.Source {
    private static volatile MapperService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Mapper mapper = new Mapper();
    private WindowManager wm;
    private GestureEngine engine;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private CaptureView capture;
    private EditorView editor;

    public static boolean isRunning() {
        return instance != null;
    }

    /** Re-reads translator settings after the web UI changed them. */
    public static void reloadSettings() {
        MapperService s = instance;
        if (s != null) s.handler.post(() -> MapperStore.load(s, s.mapper));
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        MapperStore.load(this, mapper);
        engine = new GestureEngine(this, handler, this);
        showBubble();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        stopCapture();
        closeEditor();
        if (bubble != null) {
            wm.removeView(bubble);
            bubble = null;
        }
        instance = null;
        super.onDestroy();
    }

    // ---- GestureEngine.Source ----
    @Override
    public void compute(long now, float dt, Map<String, float[]> out) {
        if (capture == null) return; // control off: every finger lifts
        int[] s = screenSize();
        mapper.computeTargets(now, dt, s[0], s[1], out);
    }

    @Override
    public boolean wantsTicks() {
        return capture != null && mapper.wantsTicks();
    }

    @SuppressWarnings("deprecation")
    @Override
    public int[] screenSize() {
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        return new int[]{p.x, p.y};
    }

    // ---- overlays ----
    private WindowManager.LayoutParams overlayParams(int w, int h, int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showBubble() {
        float d = getResources().getDisplayMetrics().density;
        bubble = new TextView(this);
        bubble.setText("X");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(18);
        bubble.setGravity(Gravity.CENTER);
        updateBubble();
        int size = Math.round(48 * d);
        bubbleLp = overlayParams(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        bubbleLp.x = Math.round(8 * d);
        bubbleLp.y = Math.round(120 * d);

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            long downAt;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY();
                        ox = bubbleLp.x; oy = bubbleLp.y;
                        downAt = SystemClock.uptimeMillis();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (Math.hypot(dx, dy) > 12 * d) moved = true;
                        if (moved) {
                            bubbleLp.x = ox + Math.round(dx);
                            bubbleLp.y = oy + Math.round(dy);
                            wm.updateViewLayout(bubble, bubbleLp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            if (SystemClock.uptimeMillis() - downAt > 600) openEditor();
                            else if (capture == null) startCapture();
                            else stopCapture();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        wm.addView(bubble, bubbleLp);
    }

    private void updateBubble() {
        if (bubble == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(capture != null ? 0xEEFF4D4D : 0xAA2A3242);
        bg.setStroke(3, Color.WHITE);
        bubble.setBackground(bg);
    }

    /** Keeps the bubble above any overlay added after it. */
    private void raiseBubble() {
        if (bubble == null) return;
        wm.removeView(bubble);
        wm.addView(bubble, bubbleLp);
    }

    private void startCapture() {
        if (capture != null) return;
        closeEditor();
        mapper.resetInput();
        capture = new CaptureView(this, mapper, engine::kick, this::stopCapture);
        wm.addView(capture, overlayParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE));
        raiseBubble();
        updateBubble();
        toast("التحكم يعمل — Esc أو الفقاعة للإيقاف");
    }

    private void stopCapture() {
        if (capture == null) return;
        capture.releasePointerCapture();
        wm.removeView(capture);
        capture = null;
        mapper.resetInput();
        engine.kick(); // lifts every finger
        updateBubble();
    }

    private void openEditor() {
        if (editor != null) return;
        stopCapture();
        String snapshot;
        try {
            snapshot = mapper.layoutJson().toString();
        } catch (org.json.JSONException e) {
            snapshot = null;
        }
        final String saved = snapshot;
        editor = new EditorView(this, mapper, new EditorView.Listener() {
            @Override
            public void onSave() {
                MapperStore.saveLayout(MapperService.this, mapper);
                toast("تم حفظ التخطيط");
                closeEditor();
            }

            @Override
            public void onClose() {
                try { // discard unsaved edits
                    if (saved != null) mapper.applyLayout(new org.json.JSONObject(saved));
                } catch (org.json.JSONException ignored) {
                }
                closeEditor();
            }
        });
        wm.addView(editor, overlayParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, 0));
        editor.requestFocus();
        raiseBubble();
    }

    private void closeEditor() {
        if (editor == null) return;
        wm.removeView(editor);
        editor = null;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

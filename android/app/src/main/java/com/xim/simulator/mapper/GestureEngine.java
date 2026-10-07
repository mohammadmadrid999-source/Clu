package com.xim.simulator.mapper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Injects multi-finger touches into the foreground app through the
 * accessibility gesture API. Every tick it sends one gesture holding all
 * active fingers; strokes are continued between ticks so a finger stays
 * down (and can move) for as long as its input is held.
 */
public class GestureEngine {
    public interface Source {
        void compute(long now, float dt, Map<String, float[]> out);

        boolean wantsTicks();

        int[] screenSize();
    }

    private static final long STROKE_MS = 16;

    private static class Finger {
        float x, y;
        GestureDescription.StrokeDescription stroke;
    }

    private final AccessibilityService service;
    private final Handler handler;
    private final Source source;
    private final Map<String, Finger> fingers = new HashMap<>();
    private boolean running;
    private long lastTick;

    private final AccessibilityService.GestureResultCallback callback =
            new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    handler.post(GestureEngine.this::tick);
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    // Interrupted (e.g. a real touch): start fresh fingers next tick.
                    fingers.clear();
                    handler.postDelayed(GestureEngine.this::tick, STROKE_MS);
                }
            };

    public GestureEngine(AccessibilityService service, Handler handler, Source source) {
        this.service = service;
        this.handler = handler;
        this.source = source;
    }

    /** Starts the loop if it is idle; call after every input event. */
    public void kick() {
        if (running) return;
        running = true;
        lastTick = SystemClock.uptimeMillis();
        handler.post(this::tick);
    }

    private void tick() {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (now - lastTick) / 1000f);
        lastTick = now;

        Map<String, float[]> want = new LinkedHashMap<>();
        source.compute(now, dt, want);
        int[] size = source.screenSize();

        GestureDescription.Builder b = new GestureDescription.Builder();
        int n = 0;
        int max = GestureDescription.getMaxStrokeCount();

        Iterator<Map.Entry<String, Finger>> it = fingers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Finger> e = it.next();
            Finger f = e.getValue();
            float[] t = want.remove(e.getKey());
            Path p = new Path();
            p.moveTo(f.x, f.y);
            if (t != null) {
                float x = clamp(t[0], size[0]), y = clamp(t[1], size[1]);
                if (x != f.x || y != f.y) p.lineTo(x, y);
                f.stroke = f.stroke.continueStroke(p, 0, STROKE_MS, true);
                f.x = x;
                f.y = y;
                b.addStroke(f.stroke);
            } else {
                b.addStroke(f.stroke.continueStroke(p, 0, STROKE_MS, false)); // lift
                it.remove();
            }
            n++;
        }
        for (Map.Entry<String, float[]> e : want.entrySet()) {
            if (n >= max) break;
            Finger f = new Finger();
            f.x = clamp(e.getValue()[0], size[0]);
            f.y = clamp(e.getValue()[1], size[1]);
            Path p = new Path();
            p.moveTo(f.x, f.y);
            f.stroke = new GestureDescription.StrokeDescription(p, 0, STROKE_MS, true);
            fingers.put(e.getKey(), f);
            b.addStroke(f.stroke);
            n++;
        }

        if (n == 0) {
            if (source.wantsTicks()) {
                handler.postDelayed(this::tick, STROKE_MS);
            } else {
                running = false;
            }
            return;
        }
        if (!service.dispatchGesture(b.build(), callback, handler)) {
            fingers.clear();
            running = false;
        }
    }

    private static float clamp(float v, int max) {
        return Math.max(0f, Math.min(max - 1, v));
    }
}

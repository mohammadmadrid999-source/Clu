package com.xim.simulator.mapper;

import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates mouse / keyboard / gamepad input into virtual finger positions
 * on the screen of another app (the XIM-style translator for touch games).
 *
 * Positions are stored as fractions of the screen so a layout survives
 * resolution changes; radii are fractions of the screen height.
 */
public class Mapper {
    public static final int MOUSE_LEFT = -1, MOUSE_RIGHT = -2, MOUSE_MIDDLE = -3,
            MOUSE_BACK = -4, MOUSE_FORWARD = -5;

    // ---- translator settings (edited from the web UI) ----
    public float sensitivity = 1.0f;        // touch px per mouse count
    public float adsMultiplier = 0.6f;      // sensitivity scale while ADS key is held
    public int adsCode = MOUSE_RIGHT;       // 0 = none
    public float yxRatio = 1.0f;
    public float accel = 0.3f;              // 0 = linear, 1 = strong speed-based boost
    public float stickCameraSpeed = 1400f;  // px/s at full right-stick deflection
    public float stickDeadzone = 0.12f;
    public boolean invertY = false;

    // ---- layout (edited on top of the game) ----
    public float joyX = 0.16f, joyY = 0.72f, joyR = 0.12f;
    public float camX = 0.70f, camY = 0.45f, camR = 0.28f;
    public int keyUp = KeyEvent.KEYCODE_W, keyDown = KeyEvent.KEYCODE_S,
            keyLeft = KeyEvent.KEYCODE_A, keyRight = KeyEvent.KEYCODE_D;
    public final List<ButtonMap> buttons = new ArrayList<>();

    public static class ButtonMap {
        public int code;
        public float x, y;

        public ButtonMap(int code, float x, float y) {
            this.code = code;
            this.x = x;
            this.y = y;
        }
    }

    // ---- live input state (touched from the UI thread only) ----
    final Set<Integer> pressed = new HashSet<>();
    float mouseDx, mouseDy;
    float lx, ly, rx, ry;

    // ---- camera finger state ----
    private boolean camActive, camLifting;
    private float camPx, camPy, camPendX, camPendY;
    private long camLastMove;

    public void resetInput() {
        pressed.clear();
        mouseDx = mouseDy = 0;
        lx = ly = rx = ry = 0;
        camActive = camLifting = false;
        camPendX = camPendY = 0;
    }

    /** Fills {@code out} with the screen position of every finger that should be down now. */
    public void computeTargets(long now, float dt, int w, int h, Map<String, float[]> out) {
        // Left stick: movement keys first, gamepad left stick otherwise.
        float vx = (pressed.contains(keyRight) ? 1 : 0) - (pressed.contains(keyLeft) ? 1 : 0);
        float vy = (pressed.contains(keyDown) ? 1 : 0) - (pressed.contains(keyUp) ? 1 : 0);
        if (vx == 0 && vy == 0 && Math.hypot(lx, ly) > stickDeadzone) {
            vx = lx;
            vy = ly;
        }
        float vm = (float) Math.hypot(vx, vy);
        if (vm > 1) {
            vx /= vm;
            vy /= vm;
        }
        if (vx != 0 || vy != 0) {
            float r = joyR * h;
            out.put("joy", new float[]{joyX * w + vx * r, joyY * h + vy * r});
        }

        // Camera: mouse counts through the translator, plus the right stick.
        boolean ads = adsCode != 0 && pressed.contains(adsCode);
        float adsK = ads ? adsMultiplier : 1f;
        float mx = mouseDx, my = mouseDy;
        mouseDx = mouseDy = 0;
        float speed = (float) Math.hypot(mx, my) / Math.max(dt * 1000f, 1f); // counts per ms
        float mult = (1 - accel) + accel * Math.min(2f, speed / 5f);
        float tx = mx * sensitivity * mult * adsK;
        float ty = my * sensitivity * mult * adsK * yxRatio;

        float sm = (float) Math.hypot(rx, ry);
        if (sm > stickDeadzone) {
            float eff = (float) Math.pow(Math.min(1f, (sm - stickDeadzone) / (1 - stickDeadzone)), 1.5) / sm;
            tx += rx * eff * stickCameraSpeed * dt * adsK;
            ty += ry * eff * stickCameraSpeed * dt * adsK * yxRatio;
        }
        if (invertY) ty = -ty;
        if (tx != 0 || ty != 0) camLastMove = now;

        float ax = camX * w, ay = camY * h, ar = camR * h;
        if (camLifting) {
            // The finger is being lifted this frame; keep the motion for the re-touch.
            camLifting = false;
            camPendX += tx;
            camPendY += ty;
        } else {
            if (!camActive && (tx != 0 || ty != 0 || camPendX != 0 || camPendY != 0)) {
                camActive = true;
                camPx = ax;
                camPy = ay;
                tx += camPendX;
                ty += camPendY;
                camPendX = camPendY = 0;
            }
            if (camActive) {
                camPx += tx;
                camPy += ty;
                float d = (float) Math.hypot(camPx - ax, camPy - ay);
                if (d > ar) {
                    // Reached the edge of the drag area: stop here, lift, re-centre.
                    camPx = ax + (camPx - ax) / d * ar;
                    camPy = ay + (camPy - ay) / d * ar;
                    out.put("cam", new float[]{camPx, camPy});
                    camActive = false;
                    camLifting = true;
                } else if (now - camLastMove > 400) {
                    camActive = false; // idle: lift the finger
                } else {
                    out.put("cam", new float[]{camPx, camPy});
                }
            }
        }

        for (int i = 0; i < buttons.size(); i++) {
            ButtonMap b = buttons.get(i);
            if (pressed.contains(b.code)) out.put("b" + i, new float[]{b.x * w, b.y * h});
        }
    }

    /** True while something still needs the gesture loop to run. */
    public boolean wantsTicks() {
        return camActive || camLifting || !pressed.isEmpty()
                || Math.hypot(lx, ly) > stickDeadzone || Math.hypot(rx, ry) > stickDeadzone;
    }

    public static String codeName(int code) {
        switch (code) {
            case MOUSE_LEFT: return "LMB";
            case MOUSE_RIGHT: return "RMB";
            case MOUSE_MIDDLE: return "MMB";
            case MOUSE_BACK: return "M4";
            case MOUSE_FORWARD: return "M5";
            case 0: return "—";
            default:
                return KeyEvent.keyCodeToString(code).replace("KEYCODE_", "").replace("BUTTON_", "");
        }
    }

    // ---- JSON ----
    public JSONObject settingsJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("sensitivity", sensitivity);
        o.put("adsMultiplier", adsMultiplier);
        o.put("adsCode", adsCode);
        o.put("yxRatio", yxRatio);
        o.put("accel", accel);
        o.put("stickCameraSpeed", stickCameraSpeed);
        o.put("stickDeadzone", stickDeadzone);
        o.put("invertY", invertY);
        return o;
    }

    public void applySettings(JSONObject o) {
        sensitivity = (float) o.optDouble("sensitivity", sensitivity);
        adsMultiplier = (float) o.optDouble("adsMultiplier", adsMultiplier);
        adsCode = o.optInt("adsCode", adsCode);
        yxRatio = (float) o.optDouble("yxRatio", yxRatio);
        accel = (float) o.optDouble("accel", accel);
        stickCameraSpeed = (float) o.optDouble("stickCameraSpeed", stickCameraSpeed);
        stickDeadzone = (float) o.optDouble("stickDeadzone", stickDeadzone);
        invertY = o.optBoolean("invertY", invertY);
    }

    public JSONObject layoutJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("joy", new JSONArray().put(joyX).put(joyY).put(joyR));
        o.put("cam", new JSONArray().put(camX).put(camY).put(camR));
        o.put("keys", new JSONArray().put(keyUp).put(keyDown).put(keyLeft).put(keyRight));
        JSONArray bs = new JSONArray();
        for (ButtonMap b : buttons) bs.put(new JSONArray().put(b.code).put(b.x).put(b.y));
        o.put("buttons", bs);
        return o;
    }

    public void applyLayout(JSONObject o) {
        JSONArray j = o.optJSONArray("joy");
        if (j != null && j.length() == 3) {
            joyX = (float) j.optDouble(0); joyY = (float) j.optDouble(1); joyR = (float) j.optDouble(2);
        }
        JSONArray c = o.optJSONArray("cam");
        if (c != null && c.length() == 3) {
            camX = (float) c.optDouble(0); camY = (float) c.optDouble(1); camR = (float) c.optDouble(2);
        }
        JSONArray k = o.optJSONArray("keys");
        if (k != null && k.length() == 4) {
            keyUp = k.optInt(0); keyDown = k.optInt(1); keyLeft = k.optInt(2); keyRight = k.optInt(3);
        }
        JSONArray bs = o.optJSONArray("buttons");
        if (bs != null) {
            buttons.clear();
            for (int i = 0; i < bs.length(); i++) {
                JSONArray b = bs.optJSONArray(i);
                if (b != null && b.length() == 3) {
                    buttons.add(new ButtonMap(b.optInt(0), (float) b.optDouble(1), (float) b.optDouble(2)));
                }
            }
        }
    }
}

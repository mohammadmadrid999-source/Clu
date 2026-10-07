package com.xim.simulator.mapper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Persists translator settings and the on-screen layout in SharedPreferences. */
public final class MapperStore {
    private static final String PREFS = "mapper";

    private MapperStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void load(Context c, Mapper m) {
        try {
            String s = prefs(c).getString("settings", null);
            if (s != null) m.applySettings(new JSONObject(s));
            String l = prefs(c).getString("layout", null);
            if (l != null) m.applyLayout(new JSONObject(l));
        } catch (Exception ignored) {
            // Corrupt data: keep defaults.
        }
    }

    public static String getSettings(Context c) {
        String s = prefs(c).getString("settings", null);
        if (s != null) return s;
        try {
            return new Mapper().settingsJson().toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static void saveSettings(Context c, String json) {
        prefs(c).edit().putString("settings", json).apply();
    }

    public static void saveLayout(Context c, Mapper m) {
        try {
            prefs(c).edit().putString("layout", m.layoutJson().toString()).apply();
        } catch (Exception ignored) {
        }
    }
}

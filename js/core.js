/*
 * XIM Matrix Simulator — core math.
 * Pure functions (no DOM) so they can be unit-tested with Node.
 *
 * Pipeline (per frame):
 *   mouse counts -> velocity -> sensitivity -> ballistics curve -> Y/X ratio
 *   -> smoothing -> deadzone compensation -> clamp -> right-stick output
 */
(function (root) {
  'use strict';

  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  // Default per-mode settings (hip-fire and ADS each get their own copy).
  function defaultMode(overrides) {
    return Object.assign({
      sensitivity: 8,          // stick units per (count/ms) * 100
      yxRatio: 1.0,            // vertical multiplier relative to horizontal
      smoothing: 0.25,         // 0 = none, 0.95 = very smooth
      deadzone: 0.15,          // game deadzone to compensate (0..0.5)
      deadzoneShape: 'circular', // 'circular' | 'square'
      // Ballistics: [inputSpeed (0..1), multiplier]; sorted by x.
      curve: [[0, 0.7], [0.25, 0.9], [0.5, 1.0], [1, 1.2]],
    }, overrides || {});
  }

  function defaultConfig() {
    return {
      name: 'افتراضي',
      hip: defaultMode(),
      ads: defaultMode({ sensitivity: 5, smoothing: 0.35 }),
      adsMode: 'hold',          // 'hold' | 'toggle'
      invertY: false,
      bindings: defaultBindings(),
    };
  }

  // Controller buttons -> input codes (KeyboardEvent.code or Mouse0..4).
  function defaultBindings() {
    return {
      LS_UP: 'KeyW', LS_DOWN: 'KeyS', LS_LEFT: 'KeyA', LS_RIGHT: 'KeyD',
      A: 'Space', B: 'KeyC', X: 'KeyR', Y: 'Digit1',
      LB: 'KeyQ', RB: 'KeyE', LT: 'Mouse2', RT: 'Mouse0',
      L3: 'ShiftLeft', R3: 'KeyV',
      DPAD_UP: 'Digit2', DPAD_DOWN: 'KeyX', DPAD_LEFT: 'Digit3', DPAD_RIGHT: 'Digit4',
      BACK: 'Tab', START: 'Enter',
    };
  }

  // Piecewise-linear interpolation over the curve points.
  function evalCurve(curve, x) {
    if (!curve || curve.length === 0) return 1;
    const pts = curve.slice().sort((a, b) => a[0] - b[0]);
    if (x <= pts[0][0]) return pts[0][1];
    for (let i = 1; i < pts.length; i++) {
      const [x1, y1] = pts[i];
      if (x <= x1) {
        const [x0, y0] = pts[i - 1];
        const t = x1 === x0 ? 1 : (x - x0) / (x1 - x0);
        return y0 + (y1 - y0) * t;
      }
    }
    return pts[pts.length - 1][1];
  }

  // Map a vector so that any non-zero input lands outside the game deadzone.
  function applyDeadzone(x, y, dz, shape) {
    const eps = 1e-4;
    if (shape === 'square') {
      const f = (a) => (Math.abs(a) < eps ? 0 : Math.sign(a) * (dz + (1 - dz) * Math.min(1, Math.abs(a))));
      return [f(x), f(y)];
    }
    const mag = Math.hypot(x, y);
    if (mag < eps) return [0, 0];
    const out = dz + (1 - dz) * Math.min(1, mag);
    return [(x / mag) * out, (y / mag) * out];
  }

  // Stateful translator: keeps the smoothing state between frames.
  function createTranslator() {
    const state = { sx: 0, sy: 0 };
    return {
      state,
      reset() { state.sx = 0; state.sy = 0; },
      /**
       * @param {number} dx mouse counts since last frame (x)
       * @param {number} dy mouse counts since last frame (y)
       * @param {number} dt frame time in seconds
       * @param {object} mode hip or ads settings
       * @param {boolean} invertY
       * @returns {{x:number,y:number,speed:number,mult:number}}
       */
      step(dx, dy, dt, mode, invertY) {
        dt = Math.max(dt, 1 / 1000);
        const k = mode.sensitivity / 100;
        let tx = (dx / (dt * 1000)) * k;
        let ty = (dy / (dt * 1000)) * k;

        const speed = Math.hypot(tx, ty);
        const mult = evalCurve(mode.curve, Math.min(speed, 1));
        tx *= mult;
        ty *= mult * mode.yxRatio;
        if (invertY) ty = -ty;

        // Frame-rate independent exponential smoothing.
        const s = clamp(mode.smoothing, 0, 0.98);
        const alpha = 1 - Math.pow(s, dt * 60);
        state.sx += (tx - state.sx) * alpha;
        state.sy += (ty - state.sy) * alpha;
        if (Math.hypot(state.sx, state.sy) < 0.004) { state.sx = 0; state.sy = 0; }

        let [x, y] = applyDeadzone(state.sx, state.sy, mode.deadzone, mode.deadzoneShape);
        const m = Math.hypot(x, y);
        if (mode.deadzoneShape !== 'square' && m > 1) { x /= m; y /= m; }
        return { x: clamp(x, -1, 1), y: clamp(y, -1, 1), speed, mult };
      },
    };
  }

  // Keyboard digital directions -> analog left stick (normalised diagonal).
  function digitalToStick(up, down, left, right) {
    let x = (right ? 1 : 0) - (left ? 1 : 0);
    let y = (down ? 1 : 0) - (up ? 1 : 0);
    const m = Math.hypot(x, y);
    if (m > 1) { x /= m; y /= m; }
    return [x, y];
  }

  // How a typical console game turns stick deflection into camera rotation.
  function gameStickResponse(x, y, game) {
    const mag = Math.hypot(x, y);
    if (mag <= game.deadzone) return [0, 0];
    const eff = Math.min(1, (mag - game.deadzone) / (1 - game.deadzone));
    const curved = Math.pow(eff, game.exponent);
    return [(x / mag) * curved, (y / mag) * curved];
  }

  function defaultGame() {
    return {
      deadzone: 0.15,
      exponent: 1.6,
      yawRate: 360,   // deg/s at full deflection
      pitchRate: 220,
      fov: 90,
      aimAssist: true,
      assistRadius: 4, // degrees
      assistSlowdown: 0.55,
      adsZoom: 1.6,
      adsTurnScale: 0.6,
    };
  }

  // Ensure a loaded/imported config has every field (forward-compatible).
  function normalizeConfig(cfg) {
    const base = defaultConfig();
    if (!cfg || typeof cfg !== 'object') return base;
    const out = Object.assign({}, base, cfg);
    out.hip = defaultMode(cfg.hip);
    out.ads = defaultMode(Object.assign({ sensitivity: 5, smoothing: 0.35 }, cfg.ads));
    out.bindings = Object.assign(defaultBindings(), cfg.bindings);
    for (const m of [out.hip, out.ads]) {
      if (!Array.isArray(m.curve) || m.curve.length < 2) m.curve = defaultMode().curve;
      m.curve = m.curve.map(([x, y]) => [clamp(+x, 0, 1), clamp(+y, 0, 3)]).sort((a, b) => a[0] - b[0]);
    }
    return out;
  }

  const PRESETS = {
    'متوازن': () => defaultConfig(),
    'سريع (Arena)': () => normalizeConfig({
      name: 'سريع (Arena)',
      hip: { sensitivity: 12, smoothing: 0.15, curve: [[0, 0.8], [0.3, 1], [0.6, 1.25], [1, 1.5]] },
      ads: { sensitivity: 7, smoothing: 0.2 },
    }),
    'تكتيكي (دقة)': () => normalizeConfig({
      name: 'تكتيكي (دقة)',
      hip: { sensitivity: 6, smoothing: 0.35, yxRatio: 0.8, curve: [[0, 0.55], [0.3, 0.8], [0.7, 1], [1, 1.05]] },
      ads: { sensitivity: 3.5, smoothing: 0.45, yxRatio: 0.8 },
    }),
    'قناص': () => normalizeConfig({
      name: 'قناص',
      hip: { sensitivity: 7, smoothing: 0.3 },
      ads: { sensitivity: 2.5, smoothing: 0.5, curve: [[0, 0.5], [0.5, 0.9], [1, 1]] },
    }),
  };

  const api = {
    clamp, evalCurve, applyDeadzone, createTranslator, digitalToStick,
    gameStickResponse, defaultMode, defaultConfig, defaultBindings, defaultGame,
    normalizeConfig, PRESETS,
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.XimCore = api;
})(typeof window !== 'undefined' ? window : globalThis);

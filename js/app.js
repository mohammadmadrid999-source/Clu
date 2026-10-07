/* XIM Matrix Simulator — UI, input capture, game simulation and rendering. */
(function () {
  'use strict';

  const X = window.XimCore;
  const $ = (id) => document.getElementById(id);

  // ---------- persistence ----------
  const store = {
    get(key, fallback) {
      try { const v = localStorage.getItem(key); return v ? JSON.parse(v) : fallback; } catch (e) { return fallback; }
    },
    set(key, value) {
      try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* storage unavailable */ }
    },
  };

  // ---------- state ----------
  let config = X.normalizeConfig(store.get('xim.current', null));
  const game = Object.assign(X.defaultGame(), store.get('xim.game', {}));
  const translator = X.createTranslator();
  const ui = { editMode: 'hip', curveMode: 'hip', listening: null };
  const input = { keys: new Set(), dx: 0, dy: 0, locked: false, adsToggled: false };
  const sim = { yaw: 0, pitch: 0, targets: [], hits: 0, shots: 0, prevRT: false, prevLT: false };
  const out = { ls: [0, 0], rs: [0, 0], buttons: {}, ads: false, speed: 0, mult: 1 };
  const history = [];
  const trail = [];

  const saveConfig = () => store.set('xim.current', config);
  const saveGame = () => store.set('xim.game', game);

  // ---------- labels ----------
  const BUTTON_LABELS = {
    LS_UP: 'العصا اليسرى ↑', LS_DOWN: 'العصا اليسرى ↓', LS_LEFT: 'العصا اليسرى ←', LS_RIGHT: 'العصا اليسرى →',
    A: 'A / ✕', B: 'B / ○', X: 'X / □', Y: 'Y / △', LB: 'LB / L1', RB: 'RB / R1', LT: 'LT / L2 (ADS)', RT: 'RT / R2 (إطلاق)',
    L3: 'L3 (ركض)', R3: 'R3', DPAD_UP: 'أسهم ↑', DPAD_DOWN: 'أسهم ↓', DPAD_LEFT: 'أسهم ←', DPAD_RIGHT: 'أسهم →',
    BACK: 'Back / Share', START: 'Start / Options',
  };
  const MOUSE_NAMES = { Mouse0: 'LMB', Mouse1: 'MMB', Mouse2: 'RMB', Mouse3: 'M4', Mouse4: 'M5' };
  const codeName = (c) => MOUSE_NAMES[c] || (c || '—').replace(/^Key/, '').replace(/^Digit/, '');

  // ---------- generic slider builder ----------
  function slider(parent, label, min, max, step, get, set, fmt) {
    const wrap = document.createElement('div');
    wrap.className = 'field';
    wrap.innerHTML = `<div class="lbl"><span>${label}</span><b></b></div><input type="range" min="${min}" max="${max}" step="${step}">`;
    const r = wrap.querySelector('input');
    const b = wrap.querySelector('b');
    const show = () => { b.textContent = fmt ? fmt(get()) : (+get()).toFixed(2); };
    r.value = get(); show();
    r.addEventListener('input', () => { set(+r.value); show(); });
    parent.appendChild(wrap);
  }

  function checkbox(parent, label, get, set) {
    const l = document.createElement('label');
    l.className = 'row check';
    l.innerHTML = `<input type="checkbox"> ${label}`;
    const c = l.querySelector('input');
    c.checked = get();
    c.addEventListener('change', () => set(c.checked));
    parent.appendChild(l);
  }

  // ---------- tabs ----------
  $('tabs').addEventListener('click', (e) => {
    const t = e.target.dataset.tab;
    if (!t) return;
    for (const b of $('tabs').children) b.classList.toggle('active', b.dataset.tab === t);
    for (const p of document.querySelectorAll('.tab')) p.classList.toggle('active', p.id === 'tab-' + t);
    if (t === 'curve') drawCurve();
  });

  function segmented(id, key, after) {
    $(id).addEventListener('click', (e) => {
      const m = e.target.dataset.mode;
      if (!m) return;
      ui[key] = m;
      for (const b of $(id).children) b.classList.toggle('active', b.dataset.mode === m);
      after();
    });
  }

  // ---------- config tab ----------
  function renderModeFields() {
    const p = $('modeFields');
    p.innerHTML = '';
    const m = () => config[ui.editMode];
    const upd = (k) => (v) => { m()[k] = v; saveConfig(); };
    slider(p, 'الحساسية', 0.5, 30, 0.1, () => m().sensitivity, upd('sensitivity'), (v) => (+v).toFixed(1));
    slider(p, 'نسبة Y/X', 0.3, 2, 0.05, () => m().yxRatio, upd('yxRatio'));
    slider(p, 'التنعيم (Smoothing)', 0, 0.95, 0.01, () => m().smoothing, upd('smoothing'));
    slider(p, 'تعويض المنطقة الميتة', 0, 0.5, 0.01, () => m().deadzone, upd('deadzone'));
    const row = document.createElement('label');
    row.className = 'row';
    row.innerHTML = 'شكل المنطقة الميتة <select><option value="circular">دائري</option><option value="square">مربع</option></select>';
    const sel = row.querySelector('select');
    sel.value = m().deadzoneShape;
    sel.addEventListener('change', () => { m().deadzoneShape = sel.value; saveConfig(); });
    p.appendChild(row);
    const auto = document.createElement('button');
    auto.textContent = 'مطابقة المنطقة الميتة مع اللعبة';
    auto.addEventListener('click', () => { m().deadzone = game.deadzone; saveConfig(); renderModeFields(); });
    p.appendChild(auto);
  }
  segmented('modeSeg', 'editMode', renderModeFields);

  function renderGlobalFields() {
    $('adsMode').value = config.adsMode;
    $('invertY').checked = config.invertY;
  }
  $('adsMode').addEventListener('change', (e) => { config.adsMode = e.target.value; input.adsToggled = false; saveConfig(); });
  $('invertY').addEventListener('change', (e) => { config.invertY = e.target.checked; saveConfig(); });

  // ---------- game tab ----------
  function renderGameFields() {
    const p = $('gameFields');
    p.innerHTML = '';
    const upd = (k) => (v) => { game[k] = v; saveGame(); };
    slider(p, 'المنطقة الميتة للعبة', 0, 0.4, 0.01, () => game.deadzone, upd('deadzone'));
    slider(p, 'منحنى الاستجابة (أُس)', 1, 3, 0.05, () => game.exponent, upd('exponent'));
    slider(p, 'سرعة الدوران الأفقي °/ث', 90, 720, 5, () => game.yawRate, upd('yawRate'), (v) => Math.round(v));
    slider(p, 'سرعة الدوران العمودي °/ث', 60, 540, 5, () => game.pitchRate, upd('pitchRate'), (v) => Math.round(v));
    slider(p, 'مجال الرؤية FOV', 60, 120, 1, () => game.fov, upd('fov'), (v) => Math.round(v));
    slider(p, 'تكبير ADS', 1, 4, 0.1, () => game.adsZoom, upd('adsZoom'), (v) => (+v).toFixed(1) + '×');
    slider(p, 'معامل الدوران أثناء ADS', 0.2, 1, 0.05, () => game.adsTurnScale, upd('adsTurnScale'));
    slider(p, 'حساسية اللمس (وضع الهاتف)', 1, 12, 0.5, () => game.touchGain, upd('touchGain'), (v) => (+v).toFixed(1));
    checkbox(p, 'تفعيل مساعدة التصويب (Aim Assist)', () => game.aimAssist, upd('aimAssist'));
    slider(p, 'نطاق مساعدة التصويب °', 1, 12, 0.5, () => game.assistRadius, upd('assistRadius'), (v) => (+v).toFixed(1));
    slider(p, 'إبطاء المساعدة', 0.2, 1, 0.05, () => game.assistSlowdown, upd('assistSlowdown'));
  }

  // ---------- bindings tab ----------
  function renderBindings() {
    const list = $('bindList');
    list.innerHTML = '';
    for (const id of Object.keys(BUTTON_LABELS)) {
      const row = document.createElement('div');
      row.className = 'bind';
      row.innerHTML = `<span>${BUTTON_LABELS[id]}</span><button></button>`;
      const b = row.querySelector('button');
      b.textContent = ui.listening === id ? '...اضغط' : codeName(config.bindings[id]);
      b.classList.toggle('listening', ui.listening === id);
      b.addEventListener('click', (e) => {
        e.stopPropagation();
        ui.listening = ui.listening === id ? null : id;
        renderBindings();
      });
      list.appendChild(row);
    }
  }
  function finishBinding(code) {
    if (code === 'Mouse2') ui.suppressMenu = true;
    // A code may drive only one button; unbind any previous owner.
    for (const k in config.bindings) if (config.bindings[k] === code) config.bindings[k] = '';
    config.bindings[ui.listening] = code;
    ui.listening = null;
    saveConfig();
    renderBindings();
  }
  $('bindReset').addEventListener('click', () => { config.bindings = X.defaultBindings(); saveConfig(); renderBindings(); });

  // ---------- profiles tab ----------
  function profiles() { return store.get('xim.profiles', {}); }
  function renderProfiles() {
    $('cfgName').value = config.name;
    const list = $('cfgList');
    list.innerHTML = '';
    const all = profiles();
    const names = Object.keys(all);
    if (!names.length) list.innerHTML = '<li class="muted">لا توجد ملفات محفوظة بعد.</li>';
    for (const name of names) {
      const li = document.createElement('li');
      li.innerHTML = '<b></b><span><button class="small" data-a="load">تحميل</button><button class="small" data-a="del">حذف</button></span>';
      li.querySelector('b').textContent = name;
      li.addEventListener('click', (e) => {
        const a = e.target.dataset.a;
        if (a === 'load') applyConfig(all[name]);
        if (a === 'del') { delete all[name]; store.set('xim.profiles', all); renderProfiles(); }
      });
      list.appendChild(li);
    }
    const pl = $('presetList');
    pl.innerHTML = '';
    for (const name of Object.keys(X.PRESETS)) {
      const b = document.createElement('button');
      b.textContent = name;
      b.addEventListener('click', () => applyConfig(Object.assign(X.PRESETS[name](), { name })));
      pl.appendChild(b);
    }
  }
  function applyConfig(cfg) {
    config = X.normalizeConfig(JSON.parse(JSON.stringify(cfg)));
    translator.reset();
    input.adsToggled = false;
    saveConfig();
    renderAll();
  }
  $('cfgName').addEventListener('input', (e) => { config.name = e.target.value; saveConfig(); });
  $('cfgSave').addEventListener('click', () => {
    const all = profiles();
    all[config.name || 'بدون اسم'] = config;
    store.set('xim.profiles', all);
    renderProfiles();
  });
  $('cfgExport').addEventListener('click', () => {
    const blob = new Blob([JSON.stringify({ config, game }, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = (config.name || 'xim-config') + '.json';
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  });
  $('cfgImport').addEventListener('change', async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    try {
      const data = JSON.parse(await f.text());
      if (data.game) { Object.assign(game, data.game); saveGame(); }
      applyConfig(data.config || data);
    } catch (err) {
      alert('ملف غير صالح: ' + err.message);
    }
    e.target.value = '';
  });

  // ---------- curve editor ----------
  const cc = $('curveCanvas');
  const cctx = cc.getContext('2d');
  const CURVE_YMAX = 2.5, PAD = 24;
  const curveMode = () => config[ui.curveMode];
  const toPx = ([x, y]) => [PAD + x * (cc.width - 2 * PAD), cc.height - PAD - (y / CURVE_YMAX) * (cc.height - 2 * PAD)];
  const fromPx = (px, py) => [
    X.clamp((px - PAD) / (cc.width - 2 * PAD), 0, 1),
    X.clamp(((cc.height - PAD - py) / (cc.height - 2 * PAD)) * CURVE_YMAX, 0, CURVE_YMAX),
  ];
  let dragIdx = -1;

  function drawCurve() {
    const w = cc.width, h = cc.height, c = cctx;
    c.clearRect(0, 0, w, h);
    c.strokeStyle = '#1f2633';
    c.fillStyle = '#5c6679';
    c.font = '10px sans-serif';
    for (let i = 0; i <= 5; i++) {
      const y = (i / 5) * CURVE_YMAX;
      const [, py] = toPx([0, y]);
      c.beginPath(); c.moveTo(PAD, py); c.lineTo(w - PAD, py); c.stroke();
      c.fillText(y.toFixed(1), 2, py + 3);
    }
    for (let i = 0; i <= 4; i++) {
      const [px] = toPx([i / 4, 0]);
      c.beginPath(); c.moveTo(px, PAD); c.lineTo(px, h - PAD); c.stroke();
    }
    // multiplier = 1 reference
    c.strokeStyle = '#3c475c'; c.setLineDash([4, 4]);
    const [, p1] = toPx([0, 1]);
    c.beginPath(); c.moveTo(PAD, p1); c.lineTo(w - PAD, p1); c.stroke();
    c.setLineDash([]);

    const pts = curveMode().curve;
    c.strokeStyle = '#3fd0ff'; c.lineWidth = 2;
    c.beginPath();
    for (let i = 0; i <= 100; i++) {
      const x = i / 100;
      const [px, py] = toPx([x, X.evalCurve(pts, x)]);
      i ? c.lineTo(px, py) : c.moveTo(px, py);
    }
    c.stroke(); c.lineWidth = 1;
    for (const p of pts) {
      const [px, py] = toPx(p);
      c.fillStyle = '#ff4d4d';
      c.beginPath(); c.arc(px, py, 5, 0, Math.PI * 2); c.fill();
    }
    // live speed marker
    if ((input.locked || input.touch) && config[out.ads ? 'ads' : 'hip'] === curveMode()) {
      const s = Math.min(out.speed, 1);
      const [px, py] = toPx([s, X.evalCurve(pts, s)]);
      c.strokeStyle = '#4ade80';
      c.beginPath(); c.arc(px, py, 7, 0, Math.PI * 2); c.stroke();
    }
    c.fillStyle = '#5c6679';
    c.fillText('سرعة الماوس →', w - 90, h - 6);
  }

  function canvasPoint(e) {
    const r = cc.getBoundingClientRect();
    return [(e.clientX - r.left) * (cc.width / r.width), (e.clientY - r.top) * (cc.height / r.height)];
  }
  function nearestPoint(px, py) {
    const pts = curveMode().curve;
    let best = -1, bd = 12;
    pts.forEach((p, i) => {
      const [x, y] = toPx(p);
      const d = Math.hypot(x - px, y - py);
      if (d < bd) { bd = d; best = i; }
    });
    return best;
  }
  cc.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return;
    dragIdx = nearestPoint(...canvasPoint(e));
    if (dragIdx >= 0) cc.setPointerCapture(e.pointerId);
  });
  cc.addEventListener('pointermove', (e) => {
    if (dragIdx < 0) return;
    const pts = curveMode().curve;
    const [x, y] = fromPx(...canvasPoint(e));
    // Keep points ordered: x stays between neighbours; endpoints fixed on x.
    const lo = dragIdx === 0 ? 0 : pts[dragIdx - 1][0] + 0.01;
    const hi = dragIdx === pts.length - 1 ? 1 : pts[dragIdx + 1][0] - 0.01;
    const fixedX = dragIdx === 0 ? 0 : dragIdx === pts.length - 1 ? 1 : null;
    pts[dragIdx] = [fixedX !== null ? fixedX : X.clamp(x, lo, hi), y];
    drawCurve();
  });
  cc.addEventListener('pointerup', () => { if (dragIdx >= 0) saveConfig(); dragIdx = -1; });
  cc.addEventListener('dblclick', (e) => {
    const [x, y] = fromPx(...canvasPoint(e));
    if (x <= 0.01 || x >= 0.99) return;
    const pts = curveMode().curve;
    pts.push([x, y]);
    pts.sort((a, b) => a[0] - b[0]);
    saveConfig(); drawCurve();
  });
  cc.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    const pts = curveMode().curve;
    const i = nearestPoint(...canvasPoint(e));
    if (i > 0 && i < pts.length - 1) { pts.splice(i, 1); saveConfig(); drawCurve(); }
  });
  const setCurve = (pts) => { curveMode().curve = pts; saveConfig(); drawCurve(); };
  $('curveLinear').addEventListener('click', () => setCurve([[0, 1], [1, 1]]));
  $('curveAccel').addEventListener('click', () => setCurve([[0, 0.6], [0.3, 0.85], [0.6, 1.15], [1, 1.6]]));
  $('curveDecel').addEventListener('click', () => setCurve([[0, 1.3], [0.4, 1.05], [1, 0.85]]));
  segmented('curveSeg', 'curveMode', drawCurve);

  // ---------- input capture ----------
  const arena = $('arena');
  const overlay = $('arenaOverlay');
  const coarsePointer = window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
  overlay.addEventListener('click', () => {
    if (coarsePointer || !arena.requestPointerLock) { enterTouch(); return; }
    const p = arena.requestPointerLock();
    if (p && p.catch) p.catch(enterTouch);
  });
  document.addEventListener('pointerlockchange', () => {
    input.locked = document.pointerLockElement === arena;
    overlay.classList.toggle('hidden', input.locked);
    input.keys.clear();
    input.dx = input.dy = 0;
    translator.reset();
    setActiveBadge();
  });
  document.addEventListener('mousemove', (e) => {
    if (!input.locked) return;
    input.dx += e.movementX;
    input.dy += e.movementY;
  });

  function press(code) {
    if (code === config.bindings.LT && config.adsMode === 'toggle' && !input.keys.has(code)) {
      input.adsToggled = !input.adsToggled;
    }
    input.keys.add(code);
  }
  document.addEventListener('keydown', (e) => {
    if (ui.listening) {
      e.preventDefault();
      if (e.code === 'Escape') { ui.listening = null; renderBindings(); return; }
      finishBinding(e.code);
      return;
    }
    if (!input.locked && !input.touch) return;
    e.preventDefault();
    press(e.code);
  });
  document.addEventListener('keyup', (e) => input.keys.delete(e.code));
  document.addEventListener('mousedown', (e) => {
    const code = 'Mouse' + e.button;
    if (ui.listening && e.target.closest && e.target.closest('#bindList') && e.button === 0) return; // button's own click
    if (ui.listening) { e.preventDefault(); finishBinding(code); return; }
    if (input.locked) press(code);
  });
  document.addEventListener('mouseup', (e) => input.keys.delete('Mouse' + e.button));
  document.addEventListener('contextmenu', (e) => {
    if (input.locked || ui.listening || ui.suppressMenu) e.preventDefault();
    ui.suppressMenu = false;
  });
  window.addEventListener('blur', () => input.keys.clear());

  // ---------- touch mode (phones / Android) ----------
  // Dragging on the arena acts as the mouse, a virtual joystick is the left
  // stick, and on-screen buttons drive RT / LT / A.
  input.touch = false;
  input.touchLS = [0, 0];
  input.touchButtons = new Set();
  input.touchTaps = new Set(); // presses since the last frame, so quick taps are never missed

  function setActiveBadge() {
    const on = input.locked || input.touch;
    $('lockBadge').textContent = input.touch ? 'وضع اللمس' : input.locked ? 'الماوس ملتقط' : 'الماوس غير ملتقط';
    $('lockBadge').classList.toggle('off', !on);
  }
  function enterTouch() {
    input.touch = true;
    document.body.classList.add('touch-mode');
    overlay.classList.add('hidden');
    $('touchLayer').classList.remove('hidden');
    translator.reset();
    setActiveBadge();
    resizeArena();
  }
  function exitTouch() {
    input.touch = false;
    input.touchLS = [0, 0];
    input.touchButtons.clear();
    document.body.classList.remove('touch-mode');
    overlay.classList.remove('hidden');
    $('touchLayer').classList.add('hidden');
    setActiveBadge();
    resizeArena();
  }
  $('tExit').addEventListener('click', exitTouch);
  // Called by the Android wrapper's back button.
  window.XimApp = { back() { if (!input.touch) return false; exitTouch(); return true; } };

  const look = { id: null, x: 0, y: 0 };
  const lookPad = $('lookPad');
  lookPad.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    look.id = e.pointerId; look.x = e.clientX; look.y = e.clientY;
    lookPad.setPointerCapture(e.pointerId);
  });
  lookPad.addEventListener('pointermove', (e) => {
    if (e.pointerId !== look.id) return;
    input.dx += (e.clientX - look.x) * game.touchGain;
    input.dy += (e.clientY - look.y) * game.touchGain;
    look.x = e.clientX; look.y = e.clientY;
  });
  const endLook = (e) => { if (e.pointerId === look.id) look.id = null; };
  lookPad.addEventListener('pointerup', endLook);
  lookPad.addEventListener('pointercancel', endLook);

  const joy = $('joy'), knob = joy.querySelector('.knob');
  let joyId = null;
  function moveJoy(e) {
    const r = joy.getBoundingClientRect();
    const rad = r.width / 2;
    let x = (e.clientX - (r.left + rad)) / rad;
    let y = (e.clientY - (r.top + rad)) / rad;
    const m = Math.hypot(x, y);
    if (m > 1) { x /= m; y /= m; }
    input.touchLS = [x, y];
    knob.style.transform = `translate(${x * rad * 0.6}px, ${y * rad * 0.6}px)`;
  }
  joy.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    joyId = e.pointerId;
    joy.setPointerCapture(e.pointerId);
    moveJoy(e);
  });
  joy.addEventListener('pointermove', (e) => { if (e.pointerId === joyId) moveJoy(e); });
  const endJoy = (e) => {
    if (e.pointerId !== joyId) return;
    joyId = null;
    input.touchLS = [0, 0];
    knob.style.transform = '';
  };
  joy.addEventListener('pointerup', endJoy);
  joy.addEventListener('pointercancel', endJoy);

  for (const b of document.querySelectorAll('#touchLayer [data-btn]')) {
    const id = b.dataset.btn;
    b.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      b.setPointerCapture(e.pointerId);
      if (id === 'LT' && config.adsMode === 'toggle') input.adsToggled = !input.adsToggled;
      input.touchButtons.add(id);
      input.touchTaps.add(id);
      b.classList.add('on');
    });
    const up = () => { input.touchButtons.delete(id); b.classList.remove('on'); };
    b.addEventListener('pointerup', up);
    b.addEventListener('pointercancel', up);
  }

  // ---------- gamepad passthrough ----------
  const PAD_MAP = ['A', 'B', 'X', 'Y', 'LB', 'RB', 'LT', 'RT', 'BACK', 'START', 'L3', 'R3', 'DPAD_UP', 'DPAD_DOWN', 'DPAD_LEFT', 'DPAD_RIGHT'];
  function readGamepad() {
    const pads = navigator.getGamepads ? navigator.getGamepads() : [];
    for (const gp of pads) if (gp && gp.connected) return gp;
    return null;
  }

  // ---------- targets / game sim ----------
  const wrap180 = (a) => ((a + 540) % 360) - 180;
  function spawnTarget() {
    return {
      yaw: sim.yaw + (Math.random() * 2 - 1) * 60,
      pitch: X.clamp(sim.pitch + (Math.random() * 2 - 1) * 20, -30, 30),
      vy: (Math.random() * 2 - 1) * 25,
      vp: (Math.random() * 2 - 1) * 6,
      r: 1.2 + Math.random() * 1.3,
      flash: 0,
    };
  }
  for (let i = 0; i < 5; i++) sim.targets.push(spawnTarget());

  function angDist(t) { return Math.hypot(wrap180(t.yaw - sim.yaw), t.pitch - sim.pitch); }

  function stepGame(dt) {
    const fov = game.fov / (out.ads ? game.adsZoom : 1);
    let [gx, gy] = X.gameStickResponse(out.rs[0], out.rs[1], game);
    let scale = out.ads ? game.adsTurnScale : 1;

    let nearest = null, nd = Infinity;
    for (const t of sim.targets) { const d = angDist(t); if (d < nd) { nd = d; nearest = t; } }
    sim.assistActive = game.aimAssist && nearest && nd < game.assistRadius + nearest.r;
    if (sim.assistActive) scale *= game.assistSlowdown;

    sim.yaw = wrap180(sim.yaw + gx * game.yawRate * scale * dt);
    sim.pitch = X.clamp(sim.pitch - gy * game.pitchRate * scale * dt, -80, 80);

    // Rotational aim assist: follow the target a little while it moves.
    for (const t of sim.targets) {
      t.yaw = wrap180(t.yaw + t.vy * dt);
      t.pitch += t.vp * dt;
      if (Math.abs(t.pitch) > 35) t.vp = -t.vp;
      if (Math.random() < dt * 0.4) t.vy = (Math.random() * 2 - 1) * 25;
      if (sim.assistActive && t === nearest) {
        sim.yaw = wrap180(sim.yaw + t.vy * dt * 0.5);
      }
      t.flash = Math.max(0, t.flash - dt);
    }
    // Strafing with the left stick shifts targets sideways (parallax).
    for (const t of sim.targets) t.yaw = wrap180(t.yaw - out.ls[0] * 12 * dt);

    const rt = !!out.buttons.RT;
    if (rt && !sim.prevRT) {
      sim.shots++;
      const hit = sim.targets.findIndex((t) => angDist(t) <= t.r);
      if (hit >= 0) { sim.hits++; sim.targets[hit] = spawnTarget(); }
      sim.muzzle = 0.06;
    }
    sim.prevRT = rt;
    sim.muzzle = Math.max(0, (sim.muzzle || 0) - dt);
    return fov;
  }

  // ---------- rendering ----------
  const actx = arena.getContext('2d');
  function resizeArena() {
    const r = arena.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    arena.width = Math.max(1, r.width * dpr);
    arena.height = Math.max(1, r.height * dpr);
  }
  window.addEventListener('resize', resizeArena);

  function drawArena(fov) {
    const c = actx, w = arena.width, h = arena.height;
    const ppd = w / fov; // pixels per degree
    const cx = w / 2, cy = h / 2;
    c.fillStyle = '#0a0d12'; c.fillRect(0, 0, w, h);

    // Sky / ground split at horizon.
    const horizon = cy + sim.pitch * ppd;
    c.fillStyle = '#10161f'; c.fillRect(0, horizon, w, h - horizon);

    // World grid every 15°.
    c.strokeStyle = '#1b2230'; c.lineWidth = 1;
    const startYaw = Math.floor((sim.yaw - fov / 2) / 15) * 15;
    for (let a = startYaw; a <= sim.yaw + fov / 2 + 15; a += 15) {
      const x = cx + (a - sim.yaw) * ppd;
      c.beginPath(); c.moveTo(x, 0); c.lineTo(x, h); c.stroke();
    }
    for (let p = -90; p <= 90; p += 15) {
      const y = cy + (sim.pitch - p) * ppd;
      c.beginPath(); c.moveTo(0, y); c.lineTo(w, y); c.stroke();
    }
    c.strokeStyle = '#2a3242';
    c.beginPath(); c.moveTo(0, horizon); c.lineTo(w, horizon); c.stroke();

    for (const t of sim.targets) {
      const x = cx + wrap180(t.yaw - sim.yaw) * ppd;
      const y = cy - (t.pitch - sim.pitch) * ppd;
      const r = t.r * ppd;
      c.fillStyle = '#ff4d4d';
      c.beginPath(); c.arc(x, y, r, 0, Math.PI * 2); c.fill();
      c.fillStyle = '#fff';
      c.beginPath(); c.arc(x, y, r * 0.35, 0, Math.PI * 2); c.fill();
    }

    // Aim assist ring.
    if (game.aimAssist) {
      c.strokeStyle = sim.assistActive ? 'rgba(74,222,128,.7)' : 'rgba(138,149,168,.25)';
      c.setLineDash([6, 6]);
      c.beginPath(); c.arc(cx, cy, game.assistRadius * ppd, 0, Math.PI * 2); c.stroke();
      c.setLineDash([]);
    }

    // Crosshair.
    const dpr = window.devicePixelRatio || 1;
    const g = (out.ads ? 4 : 10) * dpr, len = 10 * dpr;
    c.strokeStyle = sim.muzzle > 0 ? '#ffd166' : '#e6ebf3'; c.lineWidth = 2 * dpr;
    c.beginPath();
    c.moveTo(cx - g - len, cy); c.lineTo(cx - g, cy);
    c.moveTo(cx + g, cy); c.lineTo(cx + g + len, cy);
    c.moveTo(cx, cy - g - len); c.lineTo(cx, cy - g);
    c.moveTo(cx, cy + g); c.lineTo(cx, cy + g + len);
    c.stroke(); c.lineWidth = 1;

    if (out.ads) {
      const grd = c.createRadialGradient(cx, cy, Math.min(w, h) * 0.3, cx, cy, Math.max(w, h) * 0.7);
      grd.addColorStop(0, 'rgba(0,0,0,0)'); grd.addColorStop(1, 'rgba(0,0,0,.65)');
      c.fillStyle = grd; c.fillRect(0, 0, w, h);
    }

    c.fillStyle = '#8a95a8'; c.font = `${12 * (window.devicePixelRatio || 1)}px sans-serif`;
    c.textAlign = 'left';
    c.fillText(`yaw ${sim.yaw.toFixed(1)}°  pitch ${sim.pitch.toFixed(1)}°  FOV ${fov.toFixed(0)}°`, 10, h - 10);
  }

  const tc = $('trace').getContext('2d');
  function drawTrace() {
    const W = 220, c = tc, R = 100, cx = W / 2, cy = W / 2;
    c.clearRect(0, 0, W, W);
    c.strokeStyle = '#2a3242';
    c.beginPath(); c.arc(cx, cy, R, 0, Math.PI * 2); c.stroke();
    c.beginPath(); c.moveTo(cx - R, cy); c.lineTo(cx + R, cy); c.moveTo(cx, cy - R); c.lineTo(cx, cy + R); c.stroke();
    // Game deadzone.
    c.fillStyle = 'rgba(255,77,77,.15)';
    c.beginPath(); c.arc(cx, cy, game.deadzone * R, 0, Math.PI * 2); c.fill();
    // Translator deadzone compensation.
    const m = config[out.ads ? 'ads' : 'hip'];
    c.strokeStyle = '#3fd0ff'; c.setLineDash([3, 3]);
    if (m.deadzoneShape === 'square') c.strokeRect(cx - m.deadzone * R, cy - m.deadzone * R, m.deadzone * 2 * R, m.deadzone * 2 * R);
    else { c.beginPath(); c.arc(cx, cy, m.deadzone * R, 0, Math.PI * 2); c.stroke(); }
    c.setLineDash([]);
    trail.forEach(([x, y], i) => {
      c.fillStyle = `rgba(255,77,77,${(i + 1) / trail.length})`;
      c.fillRect(cx + x * R - 1.5, cy + y * R - 1.5, 3, 3);
    });
    c.fillStyle = '#fff';
    c.beginPath(); c.arc(cx + out.rs[0] * R, cy + out.rs[1] * R, 5, 0, Math.PI * 2); c.fill();
  }

  const gc = $('graph').getContext('2d');
  function drawGraph() {
    const c = gc, w = c.canvas.width, h = c.canvas.height, mid = h / 2;
    c.clearRect(0, 0, w, h);
    c.strokeStyle = '#2a3242';
    c.beginPath(); c.moveTo(0, mid); c.lineTo(w, mid); c.stroke();
    const series = [[0, '#ff4d4d', 'X'], [1, '#3fd0ff', 'Y'], [2, '#4ade80', 'speed']];
    for (const [idx, color] of series) {
      c.strokeStyle = color;
      c.beginPath();
      history.forEach((s, i) => {
        const x = (i / 299) * w;
        const y = mid - X.clamp(s[idx], -1, 1) * (mid - 6);
        i ? c.lineTo(x, y) : c.moveTo(x, y);
      });
      c.stroke();
    }
    c.font = '11px sans-serif';
    series.forEach(([, color, name], i) => { c.fillStyle = color; c.fillText(name, 8 + i * 50, 14); });
  }

  // ---------- main loop ----------
  const pad = window.XimPad.createPad($('pad'));
  let last = performance.now(), frames = 0, fpsT = 0, curveT = 0;

  function frame(now) {
    const dt = Math.min(0.1, (now - last) / 1000);
    last = now;

    const b = config.bindings;
    const held = (id) => !!b[id] && input.keys.has(b[id]);
    const gp = readGamepad();

    out.buttons = {};
    for (const id of Object.keys(b)) if (!id.startsWith('LS_')) out.buttons[id] = held(id);
    for (const id of input.touchButtons) out.buttons[id] = true;
    for (const id of input.touchTaps) out.buttons[id] = true;
    input.touchTaps.clear();
    if (gp) PAD_MAP.forEach((id, i) => { if (gp.buttons[i] && gp.buttons[i].pressed) out.buttons[id] = true; });

    out.ads = config.adsMode === 'toggle' ? input.adsToggled : !!out.buttons.LT;
    if (config.adsMode === 'toggle') out.buttons.LT = input.adsToggled;
    const mode = out.ads ? config.ads : config.hip;

    const r = translator.step(input.dx, input.dy, dt, mode, config.invertY);
    input.dx = input.dy = 0;
    out.rs = [r.x, r.y];
    out.speed = r.speed;
    out.mult = r.mult;
    out.ls = X.digitalToStick(held('LS_UP'), held('LS_DOWN'), held('LS_LEFT'), held('LS_RIGHT'));
    if (input.touchLS[0] || input.touchLS[1]) out.ls = input.touchLS.slice();

    // Physical controller passthrough when it is being used.
    if (gp) {
      const ax = gp.axes;
      if (Math.hypot(ax[0] || 0, ax[1] || 0) > 0.12 && !out.ls[0] && !out.ls[1]) out.ls = [ax[0], ax[1]];
      if (Math.hypot(ax[2] || 0, ax[3] || 0) > 0.12 && !r.x && !r.y) out.rs = [ax[2], ax[3]];
    }

    trail.push(out.rs.slice());
    if (trail.length > 60) trail.shift();
    history.push([out.rs[0], out.rs[1], Math.min(1, out.speed)]);
    if (history.length > 300) history.shift();

    const fov = stepGame(dt);
    drawArena(fov);
    drawTrace();
    drawGraph();
    pad.update(out);

    curveT += dt;
    if (curveT > 0.05 && $('tab-curve').classList.contains('active') && dragIdx < 0) { curveT = 0; drawCurve(); }

    $('rx').textContent = out.rs[0].toFixed(2);
    $('ry').textContent = out.rs[1].toFixed(2);
    $('mult').textContent = out.mult.toFixed(2);
    $('hits').textContent = sim.hits;
    $('shots').textContent = sim.shots;
    $('acc').textContent = (sim.shots ? Math.round((sim.hits / sim.shots) * 100) : 0) + '%';
    const mb = $('modeBadge');
    mb.textContent = out.ads ? 'ADS' : 'HIP';
    mb.classList.toggle('ads', out.ads);
    $('padBadge').textContent = gp ? 'يد تحكم متصلة' : 'لا توجد يد تحكم';
    $('padBadge').classList.toggle('off', !gp);

    frames++; fpsT += dt;
    if (fpsT >= 0.5) { $('fpsBadge').textContent = Math.round(frames / fpsT) + ' Hz'; frames = 0; fpsT = 0; }
    requestAnimationFrame(frame);
  }

  $('resetScore').addEventListener('click', () => { sim.hits = sim.shots = 0; });

  function renderAll() {
    renderModeFields();
    renderGlobalFields();
    renderBindings();
    renderGameFields();
    renderProfiles();
    drawCurve();
  }

  renderAll();
  resizeArena();
  requestAnimationFrame(frame);
})();

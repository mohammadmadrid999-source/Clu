/* Virtual controller (SVG) that mirrors the simulated output. */
(function () {
  'use strict';

  const NS = 'http://www.w3.org/2000/svg';
  const STICK_R = 22, KNOB_R = 11;

  function el(tag, attrs, parent) {
    const e = document.createElementNS(NS, tag);
    for (const k in attrs) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  }

  function createPad(container) {
    const svg = el('svg', { viewBox: '0 0 320 210' });
    el('path', {
      d: 'M60 40 Q160 20 260 40 Q310 50 312 120 Q314 200 270 200 Q245 200 225 160 L95 160 Q75 200 50 200 Q6 200 8 120 Q10 50 60 40 Z',
      fill: '#161b24', stroke: '#2a3242', 'stroke-width': 2,
    }, svg);

    const parts = {};
    const label = (x, y, t) => { const tx = el('text', { x, y }, svg); tx.textContent = t; return tx; };

    // Triggers with fill level and bumpers.
    for (const [id, x] of [['LT', 50], ['RT', 230]]) {
      const g = el('g', {}, svg);
      el('rect', { x, y: 2, width: 40, height: 16, rx: 4, class: 'btn' }, g);
      parts[id] = el('rect', { x, y: 2, width: 0, height: 16, rx: 4, class: 'trig-fill' }, g);
      label(x + 20, 10, id);
    }
    for (const [id, x] of [['LB', 45], ['RB', 225]]) {
      parts[id] = el('rect', { x, y: 24, width: 50, height: 12, rx: 5, class: 'btn' }, svg);
      label(x + 25, 30, id);
    }

    // Face buttons.
    const face = { Y: [250, 65], B: [272, 87], A: [250, 109], X: [228, 87] };
    for (const id in face) {
      const [x, y] = face[id];
      parts[id] = el('circle', { cx: x, cy: y, r: 10, class: 'btn' }, svg);
      label(x, y, id);
    }

    // D-pad.
    const dp = { DPAD_UP: [110, 108], DPAD_DOWN: [110, 140], DPAD_LEFT: [94, 124], DPAD_RIGHT: [126, 124] };
    for (const id in dp) {
      const [x, y] = dp[id];
      parts[id] = el('rect', { x: x - 8, y: y - 8, width: 16, height: 16, rx: 3, class: 'btn' }, svg);
    }

    parts.BACK = el('rect', { x: 132, y: 70, width: 18, height: 10, rx: 5, class: 'btn' }, svg);
    parts.START = el('rect', { x: 170, y: 70, width: 18, height: 10, rx: 5, class: 'btn' }, svg);

    // Sticks.
    const sticks = {};
    for (const [id, cx, cy] of [['L', 70, 87], ['R', 200, 124]]) {
      el('circle', { cx, cy, r: STICK_R, class: 'stick-base' }, svg);
      sticks[id] = { knob: el('circle', { cx, cy, r: KNOB_R, class: 'stick' }, svg), cx, cy };
    }
    parts.L3 = sticks.L.knob;
    parts.R3 = sticks.R.knob;

    container.innerHTML = '';
    container.appendChild(svg);

    return {
      update(out) {
        for (const id in parts) {
          if (id === 'LT' || id === 'RT') {
            parts[id].setAttribute('width', 40 * (out.buttons[id] ? 1 : 0));
          } else {
            parts[id].classList.toggle('on', !!out.buttons[id]);
          }
        }
        for (const [id, [x, y]] of [['L', out.ls], ['R', out.rs]]) {
          const s = sticks[id];
          s.knob.setAttribute('cx', s.cx + x * (STICK_R - 4));
          s.knob.setAttribute('cy', s.cy + y * (STICK_R - 4));
        }
      },
    };
  }

  window.XimPad = { createPad };
})();

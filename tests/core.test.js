const test = require('node:test');
const assert = require('node:assert/strict');
const X = require('../js/core.js');

const near = (a, b, e = 1e-6) => assert.ok(Math.abs(a - b) < e, `${a} != ${b}`);

test('evalCurve interpolates and clamps to ends', () => {
  const c = [[0, 0.5], [0.5, 1], [1, 2]];
  near(X.evalCurve(c, 0), 0.5);
  near(X.evalCurve(c, 0.25), 0.75);
  near(X.evalCurve(c, 0.75), 1.5);
  near(X.evalCurve(c, 5), 2);
  near(X.evalCurve(c, -1), 0.5);
});

test('circular deadzone compensation pushes small input past deadzone', () => {
  const [x, y] = X.applyDeadzone(0.01, 0, 0.2, 'circular');
  near(x, 0.2 + 0.8 * 0.01);
  near(y, 0);
  assert.deepEqual(X.applyDeadzone(0, 0, 0.2, 'circular'), [0, 0]);
});

test('square deadzone compensates each axis independently', () => {
  const [x, y] = X.applyDeadzone(0.5, -0.1, 0.1, 'square');
  near(x, 0.1 + 0.9 * 0.5);
  near(y, -(0.1 + 0.9 * 0.1));
});

test('translator output stays within [-1,1] and returns to zero', () => {
  const t = X.createTranslator();
  const mode = X.defaultMode({ smoothing: 0 });
  const big = t.step(5000, -5000, 1 / 60, mode, false);
  assert.ok(Math.hypot(big.x, big.y) <= 1 + 1e-9);
  assert.ok(big.x > 0 && big.y < 0);
  const still = t.step(0, 0, 1 / 60, mode, false);
  assert.deepEqual([still.x, still.y], [0, 0]);
});

test('invertY and yxRatio affect vertical axis', () => {
  const mode = X.defaultMode({ smoothing: 0, deadzone: 0, yxRatio: 0.5, curve: [[0, 1], [1, 1]] });
  const a = X.createTranslator().step(0, 10, 1 / 60, mode, false);
  const b = X.createTranslator().step(0, 10, 1 / 60, mode, true);
  near(a.y, -b.y);
  const k = (10 / (1000 / 60)) * mode.sensitivity / 100 * 0.5;
  near(a.y, k);
});

test('game response respects deadzone', () => {
  const g = X.defaultGame();
  assert.deepEqual(X.gameStickResponse(0.1, 0, g), [0, 0]);
  const [x] = X.gameStickResponse(1, 0, g);
  near(x, 1);
});

test('normalizeConfig fills missing fields and presets are valid', () => {
  const c = X.normalizeConfig({ hip: { sensitivity: 3 } });
  assert.equal(c.hip.sensitivity, 3);
  assert.ok(c.bindings.RT);
  for (const make of Object.values(X.PRESETS)) {
    const p = make();
    assert.ok(p.hip.curve.length >= 2 && p.ads.curve.length >= 2);
  }
});

test('digitalToStick normalises diagonals', () => {
  const [x, y] = X.digitalToStick(true, false, false, true);
  near(Math.hypot(x, y), 1);
});

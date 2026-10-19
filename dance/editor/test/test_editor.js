// Logic test of dance/editor/index.html's script under Node with a stubbed DOM (no rendering checks).
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..', '..');   // dance/
const html = fs.readFileSync(ROOT + '/editor/index.html', 'utf8');
let js = html.match(/<script>([\s\S]*)<\/script>/)[1];
js = js.replace('(function () {', 'globalThis.__editor = (function () {');
js = js.replace(/\}\)\(\);\s*$/, '  return { get sel() { return sel; }, get data() { return data; }, get ei() { return ei; }, get view() { return view; }, get mode() { return mode; }, get compView() { return compView; }, get comps() { return comps; }, px, world, elem, buildTimeline, placementAt, weightsAt };\n})();');

class El {
  constructor(tag) { this.tagName = tag.toUpperCase(); this.listeners = {}; this.children = []; this._cls = new Set(); this.classList = { add: c => this._cls.add(c), remove: c => this._cls.delete(c), has: c => this._cls.has(c), toggle: (c, on) => on ? this._cls.add(c) : this._cls.delete(c) }; this.style = {}; this.value = ''; this.textContent = ''; this.title = ''; this.width = 1000; this.height = 700; this.disabled = false; this.src = ''; this.max = '0'; }
  addEventListener(t, fn) { (this.listeners[t] = this.listeners[t] || []).push(fn); }
  dispatch(t, ev) { (this.listeners[t] || []).forEach(fn => fn(Object.assign({ preventDefault() {}, target: { tagName: 'BODY' } }, ev))); }
  appendChild(c) { this.children.push(c); return c; }
  set innerHTML(v) { this._html = v; if (v === '') this.children = []; }
  get innerHTML() { return this._html; }
  querySelector() { return null; }
  getBoundingClientRect() { return { left: 0, top: 0, width: 1000, height: 700 }; }
  getContext() { return new Proxy({}, { get: (o, k) => (k in o ? o[k] : () => {}), set: (o, k, v) => { o[k] = v; return true; } }); }
  scrollIntoView() {}
  play() { return Promise.resolve(); }
  pause() {}
}
const els = {};
const SELECTS = new Set(['element', 'track', 'compState', 'addElement']);
const document = {
  getElementById: id => els[id] || (els[id] = new El(id === 'floor' ? 'canvas' : (SELECTS.has(id) || id === 'variant') ? 'select' : (id === 'compName' || id === 'compRepeat') ? 'input' : 'div')),
  createElement: tag => new El(tag),
  addEventListener: (t, fn) => document._l.push([t, fn]), _l: [],
  dispatchKey: ev => document._l.filter(x => x[0] === 'keydown').forEach(x => x[1](Object.assign({ preventDefault() {}, target: { tagName: 'BODY' } }, ev))),
};
const window = { addEventListener: (t, fn) => window._l.push([t, fn]), _l: [], dispatch: (t, ev) => window._l.filter(x => x[0] === t).forEach(x => x[1](Object.assign({ preventDefault() {} }, ev))) };
let saved = null, rendered = null, savedComps = null, composeCalls = [];
const fetch = async (url, opts) => {
  if (url === '/api/skeletons') return { ok: true, json: async () => JSON.parse(fs.readFileSync(ROOT + '/skeletons.json', 'utf8')) };
  if (url === '/api/compositions' && !opts) return { ok: true, json: async () => JSON.parse(fs.readFileSync(ROOT + '/compositions.json', 'utf8')) };
  if (url === '/api/compositions' && opts) { savedComps = JSON.parse(opts.body); return { ok: true, text: async () => 'saved' }; }
  if (url === '/api/compose') { const req = JSON.parse(opts.body); composeCalls.push(req);
    if (req.elements.join(',') === 'rock step,triple step,triple step' && req.repeat === 4 && req.start_state === 'side') return { ok: true, json: async () => JSON.parse(fs.readFileSync(path.join(__dirname, 'comp_rdts.json'), 'utf8')) };
    // a tiny fake for drafts: one element per entry, no geometry
    const st = req.start_state; const errs = req.elements.includes('circle') && st === 'side' ? ["no skeleton for 'circle' starting from state 'side'; stopping"] : [];
    return { ok: true, json: async () => ({ steps: [], elements: [], start: {}, start_hands: [], end_state: st, end_free: 'L', length: 0, errors: errs }) }; }
  if (url === '/api/save') { saved = JSON.parse(opts.body); return { ok: true, text: async () => 'saved' }; }
  if (url === '/api/render') { rendered = JSON.parse(opts.body); return { ok: true, json: async () => ({ ok: true, url: '/out/test.mp4', log: '' }) }; }
  return { ok: true, text: async () => 'stub' };
};
const vm = require('vm');
const ctx = vm.createContext({ document, window, fetch, console, Math, JSON, Date, Set, Promise, setTimeout, clearTimeout, encodeURIComponent, confirm: () => true, requestAnimationFrame: () => 0, cancelAnimationFrame: () => {} });
vm.runInContext(js, ctx);
const E = ctx.__editor;
const tick = (ms = 60) => new Promise(r => setTimeout(r, ms));
function assert(c, msg) { if (!c) { console.error('FAIL:', msg); process.exitCode = 1; } else console.log('ok  :', msg); }

(async () => {
  await tick(120);
  const NSK = 14, NEL = 7, NCO = 5, NSUB = 3;
  assert(E.data && E.data.elements.length === NSK, 'loaded ' + NSK + ' skeletons');
  assert(els['element'].children.length === NEL, 'the element dropdown lists ' + NEL + ' merged elements');
  assert(E.comps.length === NCO, 'loaded ' + NCO + ' compositions');
  // variants: triple step has 4 starting conditions
  els['element'].dispatch('change', { target: { value: 'triple step' } });
  assert(E.elem().name === 'triple step' && E.elem().start_state === 'side' && (E.elem().leader_free_start || 'L') === 'L', 'triple step selected: variant from side, left foot free');
  assert(document.getElementById('variant').children.length === 4, 'the starting-condition dropdown lists 4 options for the triple step');
  document.getElementById('variant').dispatch('change', { target: { value: '1' } });
  assert(E.elem().start_state === 'side' && E.elem().leader_free_start === 'R', 'option 2 = side, right foot free');
  document.getElementById('variant').dispatch('change', { target: { value: '3' } });
  assert(E.elem().start_state === 'facing' && E.elem().leader_free_start === 'R', 'option 4 = facing, right foot free');
  els['element'].dispatch('change', { target: { value: 'rock step' } });
  assert(E.elem().name === 'rock step' && E.elem().start_state === 'facing' && E.elem().leader_free_start === 'R', 'changing the element keeps the starting condition when it exists');
  els['element'].dispatch('change', { target: { value: 'circle' } });
  assert(E.elem().name === 'circle' && E.elem().start_state === 'facing' && document.getElementById('variant').disabled, 'an element with one starting condition disables the dropdown');
  // the cutoff: selection and editing
  els['element'].dispatch('change', { target: { value: 'rock back cutoff' } });
  const CUT = E.ei; const n = E.elem().steps.length;
  assert(E.elem().name === 'rock back cutoff' && n === 16, 'cutoff selected, 16 steps');
  document.dispatchKey({ key: 'ArrowDown' }); document.dispatchKey({ key: 'ArrowDown' });
  assert(E.sel.start === 2 && E.sel.len === 1, 'ArrowDown moves the selection');
  document.dispatchKey({ key: 'ArrowDown', shiftKey: true }); document.dispatchKey({ key: 'ArrowDown', shiftKey: true }); document.dispatchKey({ key: 'ArrowDown', shiftKey: true });
  assert(E.sel.start === 2 && E.sel.len === 4, 'shift+ArrowDown grows at the end');
  document.dispatchKey({ key: 'ArrowDown', shiftKey: true, ctrlKey: true });
  assert(E.sel.start === 2 && E.sel.len === 3, 'ctrl+shift+ArrowDown shrinks at the end');
  document.dispatchKey({ key: 'ArrowUp', shiftKey: true });
  assert(E.sel.start === 1 && E.sel.len === 4, 'shift+ArrowUp grows at the start');
  document.dispatchKey({ key: 'ArrowUp', shiftKey: true, ctrlKey: true });
  assert(E.sel.start === 2 && E.sel.len === 3, 'ctrl+shift+ArrowUp shrinks at the start');
  for (let i = 0; i < n - 2; i++) document.dispatchKey({ key: 'ArrowDown' });
  assert(E.sel.start === 0, 'selection wraps around past the last step');
  document.dispatchKey({ key: 'ArrowUp' });
  assert(E.sel.start === n - 1 && E.sel.len === 3, 'ArrowUp wraps to the last step; a range can wrap round');
  els['rows'].children[4].dispatch('click', {});
  assert(E.sel.start === 4 && E.sel.len === 1, 'click on a row selects it');
  els['rows'].children[7].dispatch('click', { shiftKey: true });
  assert(E.sel.start === 4 && E.sel.len === 4, 'shift-click extends to the clicked row');
  els['rows'].children[8].dispatch('click', {});
  const s = E.elem().steps[8]; const sx = s.x, sy = s.y, sh = s.heading;
  assert(s.count === '4' && s.dancer === 'L', 'step 8 is count 4 L.L');
  let p = E.px([s.x, s.y]); const sc = E.view.scale;
  els['floor'].dispatch('mousedown', { button: 0, clientX: p[0], clientY: p[1] });
  window.dispatch('mousemove', { clientX: p[0] + 10 * sc, clientY: p[1] + 10 * sc });
  window.dispatch('mouseup', {});
  assert(s.x === sx + 10 && s.y === sy - 10, `left-drag moved the foot to (${s.x},${s.y})`);
  p = E.px([s.x, s.y]);
  els['floor'].dispatch('mousedown', { button: 2, clientX: p[0], clientY: p[1] });
  window.dispatch('mousemove', { clientX: p[0] + 50, clientY: p[1] - 50 });
  window.dispatch('mouseup', {});
  assert(s.heading === 45, `right-drag rotated the foot to ${s.heading}`);
  document.dispatchKey({ key: 'z', ctrlKey: true }); document.dispatchKey({ key: 'z', ctrlKey: true });
  assert(s.x === sx && s.y === sy && s.heading === sh, 'ctrl-z twice restores the step');
  p = E.px([s.x, s.y]);
  els['floor'].dispatch('mousedown', { button: 0, clientX: p[0], clientY: p[1] });
  window.dispatch('mousemove', { clientX: p[0] + 5 * sc, clientY: p[1] });
  window.dispatch('mouseup', {});
  els['save'].dispatch('click', {});
  await tick();
  assert(saved && saved.elements.length === NSK && saved.states && saved.elements[CUT].steps[8].x === sx + 5, 'save posts the whole document with the edit');
  document.dispatchKey({ key: 'z', ctrlKey: true });
  // ---- the timeline port: compare with render.py's numbers for the cutoff (elements mode, swing 0.68)
  const ref = JSON.parse(fs.readFileSync(path.join(__dirname, 'timeline_ref.json'), 'utf8')).cutoff;
  const tl = E.buildTimeline(0.68);
  let worst = 0;
  for (const row of ref) for (const d of ['L', 'F']) { const w = E.weightsAt(tl.tracks[d], row.b); for (const f of ['L', 'R']) { const q = E.placementAt(tl.tracks[d].tracks[f], row.b); const r = row[d + '.' + f];
    worst = Math.max(worst, Math.abs(q[0] - r[0]), Math.abs(q[1] - r[1]), Math.abs(q[2] - r[2]), Math.abs(w[f] - r[3])); } }
  assert(worst < 1e-3, `timeline port matches render.py at ${ref.length} sample beats (worst difference ${worst.toExponential(2)})`);
  assert(tl.length === 6 && tl.steps.length === 16 && tl.steps[0].t === 0 && Math.abs(tl.steps[6].t - 2.68) < 1e-9, 'element timeline: 6 beats, 16 steps, the & at 2.68');
  // ---- compositions mode
  els['modeCompositions'].dispatch('click', {});
  await tick(120);
  const RD = E.comps.findIndex(c => c.name === 'rock double triple step');
  els['element'].dispatch('change', { target: { value: 'c' + RD } }); await tick(120);
  assert(E.mode === 'compositions' && E.compView && E.compView.steps.length === 64, 'compositions mode composes the selected composition (64 steps)');
  assert(composeCalls[composeCalls.length - 1].elements.join(',') === 'rock step,triple step,triple step', 'the draft is composed from its element list');
  assert(els['element'].children.length === NCO + NSUB && els['rows'].children.length === 64, 'dropdown lists compositions and sub-compositions, the flow lists every composed step');
  assert(els['mapStrip'].children.length === 12, 'the map has one box per element (12)');
  assert(els['compList'].children.length === 3 && els['compList'].children[1]._html.includes('side (L) → side (R)'), 'composition rows show the derived start and end condition');
  assert(els['addElement'].children.length >= 1 && els['addElement'].children.every(o => ['rock step', 'triple step', 'rock back cutoff', 'follower underarm pass', 'RTT', 'follower pass & back', 'pass+circle'].includes(o.value)) && els['addElement'].children.some(o => o.value === 'RTT'), 'the add list only offers elements and sub-compositions that can follow (from side, left foot free)');
  assert(els['save'].disabled && els['undo'].disabled && document.getElementById('variantRow').style.display === 'none', 'save, undo and the starting-condition dropdown are off in compositions mode');
  els['mapStrip'].children[5].dispatch('click', {});
  assert(E.sel.start === 26 && E.sel.len === 6, 'clicking a map box selects that element\'s steps (element 5 = steps 26-31)');
  const cs = E.elem().steps[26]; const before = [cs.x, cs.y];
  p = E.px([cs.x, cs.y]);
  els['floor'].dispatch('mousedown', { button: 0, clientX: p[0], clientY: p[1] });
  window.dispatch('mousemove', { clientX: p[0] + 10 * sc, clientY: p[1] });
  window.dispatch('mouseup', {});
  assert(cs.x === before[0] && cs.y === before[1], 'dragging does nothing on a composition floor');
  const ctl = E.buildTimeline(0.68);
  assert(ctl.length === 24 && ctl.steps.length === 64 && Math.abs(ctl.steps[4].t - 2) < 1e-9 && Math.abs(ctl.steps[6].t - 2.68) < 1e-9, 'composition timeline: 24 beats, triple on 3 at beat 2 and its & at 2.68');
  // new composition, add elements, reorder, remove, save
  els['newComp'].dispatch('click', {});
  await tick(80);
  const NC = E.comps.length - 1;
  assert(E.comps.length === NCO + 1 && E.comps[NC].name === 'composition ' + (NCO + 1) && E.comps[NC].elements.length === 0, 'new composition appended and selected');
  document.getElementById('addElement').value = 'rock step'; els['addElementBtn'].dispatch('click', {}); await tick(80);
  document.getElementById('addElement').value = 'triple step'; els['addElementBtn'].dispatch('click', {}); await tick(80);
  assert(E.comps[NC].elements.join(',') === 'rock step,triple step' && composeCalls[composeCalls.length - 1].elements.length === 2, 'adding elements recomposes the draft');
  document.getElementById('addElement').value = 'RTT'; document.getElementById('addTimes').value = '3'; els['addElementBtn'].dispatch('click', {}); await tick(80);
  assert(E.comps[NC].elements.join(',') === 'rock step,triple step,RTT x3' && els['compList'].children[2]._html.includes('x 3') && els['compList'].children[2]._html.includes('sub'), 'a sub-composition entry with a repeat is added and shown');
  els['compList'].children[2].children[2].dispatch('click', {}); await tick(80);
  document.getElementById('addTimes').value = '1';
  els['compList'].children[1].children[0].dispatch('click', {}); await tick(80);
  assert(E.comps[NC].elements.join(',') === 'triple step,rock step', 'the up button reorders');
  els['compList'].children[0].children[2].dispatch('click', {}); await tick(80);
  assert(E.comps[NC].elements.join(',') === 'rock step', 'the remove button removes');
  document.getElementById('compName').dispatch('input', { target: { value: 'my basic' } });
  document.getElementById('compRepeat').dispatch('change', { target: { value: '3' } }); await tick(80);
  assert(E.comps[NC].name === 'my basic' && E.comps[NC].repeat === 3 && composeCalls[composeCalls.length - 1].repeat === 3, 'name and repeat edits are kept and recomposed');
  els['saveComps'].dispatch('click', {}); await tick(80);
  assert(savedComps && savedComps.compositions.length === NCO + 1 && savedComps.compositions[NC].name === 'my basic', 'save compositions posts the whole list');
  els['deleteComp'].dispatch('click', {}); await tick(80);
  assert(E.comps.length === NCO, 'delete removes the composition (after confirm)');
  els['element'].dispatch('change', { target: { value: 'c' + RD } }); await tick(120);
  document.getElementById('track').value = 'sunday';
  els['render'].dispatch('click', {});
  await tick();
  assert(rendered === null || rendered.composition === 'rock double triple step', 'render refuses while compositions are unsaved, or posts the saved one');
  els['play'].dispatch('click', {}); await tick(80);
  assert(els['status'].textContent.includes('no Web Audio'), 'play reports the missing Web Audio in this stub instead of failing');
  els['modeElements'].dispatch('click', {});
  assert(E.mode === 'elements' && !els['save'].disabled && E.elem().name === 'rock back cutoff', 'back to elements mode with the previous element');
})();

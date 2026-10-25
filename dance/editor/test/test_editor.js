// Logic test of dance/editor/index.html's script under Node with a stubbed DOM (no rendering checks).
// It reads frozen copies of the data (skeletons_fixture.json = the 14 elements of 2026-10-23, compositions_fixture.json), so
// the elements the user adds or edits in the editor never change what it expects.
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..', '..');   // dance/
const html = fs.readFileSync(ROOT + '/editor/index.html', 'utf8');
let js = html.match(/<script>([\s\S]*)<\/script>/)[1];
js = js.replace('(function () {', 'globalThis.__editor = (function () {');
js = js.replace(/\}\)\(\);\s*$/, '  return { get sel() { return sel; }, get data() { return data; }, get ei() { return ei; }, get view() { return view; }, get mode() { return mode; }, get compView() { return compView; }, get comps() { return comps; }, px, world, elem, flipTurn, get arrowHits() { return arrowHits; }, buildTimeline, placementAt, weightsAt, heelOf, get overlayKind() { return overlayKind; } };\n})();');

class El {
  constructor(tag) { this.tagName = tag.toUpperCase(); this.listeners = {}; this.children = []; this._cls = new Set(); this.classList = { add: c => this._cls.add(c), remove: c => this._cls.delete(c), has: c => this._cls.has(c), toggle: (c, on) => on ? this._cls.add(c) : this._cls.delete(c) }; this.style = {}; this.value = ''; this.textContent = ''; this.title = ''; this.width = 1000; this.height = 700; this.disabled = false; this.src = ''; this.max = '0'; }
  addEventListener(t, fn) { (this.listeners[t] = this.listeners[t] || []).push(fn); }
  dispatch(t, ev) { (this.listeners[t] || []).forEach(fn => fn(Object.assign({ preventDefault() {}, target: { tagName: 'BODY' } }, ev))); }
  appendChild(c) { this.children.push(c); return c; }
  set innerHTML(v) { this._html = v; if (v === '') this.children = []; }
  get innerHTML() { return this._html; }
  get className() { return [...this._cls].join(' '); }
  set className(v) { this._cls = new Set(String(v).split(' ').filter(Boolean)); }
  querySelector() { return null; }
  getBoundingClientRect() { return { left: 0, top: 0, width: 1000, height: 700 }; }
  getContext() { return new Proxy({}, { get: (o, k) => (k in o ? o[k] : () => {}), set: (o, k, v) => { o[k] = v; return true; } }); }
  scrollIntoView() {}
  play() { return Promise.resolve(); }
  pause() {}
}
const els = {};
const SELECTS = new Set(['element', 'track', 'compState', 'addElement', 'variant', 'feStart', 'feFree', 'feEnd', 'feFrom']);
const document = {
  getElementById: id => els[id] || (els[id] = new El(id === 'floor' ? 'canvas' : SELECTS.has(id) ? 'select' : (id === 'compName' || id === 'compRepeat' || id === 'feName') ? 'input' : 'div')),
  createElement: tag => new El(tag),
  addEventListener: (t, fn) => document._l.push([t, fn]), _l: [],
  dispatchKey: ev => document._l.filter(x => x[0] === 'keydown').forEach(x => x[1](Object.assign({ preventDefault() {}, target: { tagName: 'BODY' } }, ev))),
};
const window = { addEventListener: (t, fn) => window._l.push([t, fn]), _l: [], dispatch: (t, ev) => window._l.filter(x => x[0] === t).forEach(x => x[1](Object.assign({ preventDefault() {} }, ev))) };
let saved = null, rendered = null, savedComps = null, composeCalls = [];
const fetch = async (url, opts) => {
  if (url === '/api/skeletons') return { ok: true, json: async () => JSON.parse(fs.readFileSync(path.join(__dirname, 'skeletons_fixture.json'), 'utf8')) };
  if (url === '/api/compositions' && !opts) return { ok: true, json: async () => JSON.parse(fs.readFileSync(path.join(__dirname, 'compositions_fixture.json'), 'utf8')) };
  if (url === '/api/compositions' && opts) { savedComps = JSON.parse(opts.body); return { ok: true, text: async () => 'saved' }; }
  if (url === '/api/compose') { const req = JSON.parse(opts.body); composeCalls.push(req);
    if (req.elements.join(',') === 'rock step,triple step,triple step' && req.repeat === 4 && req.start_state === 'side') return { ok: true, json: async () => JSON.parse(fs.readFileSync(path.join(__dirname, 'comp_rdts.json'), 'utf8')) };
    // a tiny fake for drafts: one element per entry, no geometry
    const st = req.start_state; const errs = req.elements.includes('circle') && st === 'side' ? ["no skeleton for 'circle' starting from state 'side'; stopping"] : [];
    return { ok: true, json: async () => ({ steps: [], elements: [], start: {}, start_hands: [], end_state: st, end_free: 'L', length: 0, errors: errs }) }; }
  if (url === '/api/save') { saved = JSON.parse(opts.body); return { ok: true, text: async () => 'saved' }; }
  if (url === '/api/render') { rendered = JSON.parse(opts.body); return { ok: true, json: async () => ({ ok: true, url: '/out/test.mp4', log: '' }) }; }
  if (url === '/api/version') return { ok: true, json: async () => ({ stale: false }) };
  return { ok: true, text: async () => 'stub' };
};
const vm = require('vm');
const ctx = vm.createContext({ document, window, fetch, console, Math, JSON, Date, Set, Promise, setTimeout, clearTimeout, encodeURIComponent, confirm: () => true, requestAnimationFrame: () => 0, cancelAnimationFrame: () => {} });
vm.runInContext(js, ctx);
const E = ctx.__editor;
const $ = id => document.getElementById(id);
const tick = (ms = 60) => new Promise(r => setTimeout(r, ms));
const near = (a, b, tol = 0.05) => Math.abs(a - b) < tol;
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
  assert($('variant').children.length === 4, 'the starting-condition dropdown lists 4 options for the triple step');
  $('variant').dispatch('change', { target: { value: '1' } });
  assert(E.elem().start_state === 'side' && E.elem().leader_free_start === 'R', 'option 2 = side, right foot free');
  $('variant').dispatch('change', { target: { value: '3' } });
  assert(E.elem().start_state === 'facing' && E.elem().leader_free_start === 'R', 'option 4 = facing, right foot free');
  els['element'].dispatch('change', { target: { value: 'rock step' } });
  assert(E.elem().name === 'rock step' && E.elem().start_state === 'facing' && E.elem().leader_free_start === 'R', 'changing the element keeps the starting condition when it exists');
  els['element'].dispatch('change', { target: { value: 'circle' } });
  assert(E.elem().name === 'circle' && E.elem().start_state === 'facing' && $('variant').disabled, 'an element with one starting condition disables the dropdown');
  // the cutoff: selection and editing
  els['element'].dispatch('change', { target: { value: 'rock back cutoff' } });
  const CUT = E.ei; const n = E.elem().steps.length;
  assert(E.elem().name === 'rock back cutoff' && n === 16, 'cutoff selected, 16 steps');
  // the selection goes count by count: the leader's and the follower's step of a count move together
  const st = E.elem().steps;
  assert(E.sel.start === 0 && E.sel.len === 2, 'the first count is selected: both dancers\' steps of count 1');
  document.dispatchKey({ key: 'ArrowDown' }); document.dispatchKey({ key: 'ArrowDown' });
  assert(E.sel.start === 4 && E.sel.len === 2 && st[4].count === '3' && st[5].count === '3' && st[4].dancer !== st[5].dancer, 'ArrowDown moves count by count (count 3: leader and follower together)');
  document.dispatchKey({ key: 'ArrowDown', shiftKey: true }); document.dispatchKey({ key: 'ArrowDown', shiftKey: true }); document.dispatchKey({ key: 'ArrowDown', shiftKey: true });
  assert(E.sel.start === 4 && E.sel.len === 8, 'shift+ArrowDown grows by a count at the end');
  document.dispatchKey({ key: 'ArrowDown', shiftKey: true, ctrlKey: true });
  assert(E.sel.start === 4 && E.sel.len === 6, 'ctrl+shift+ArrowDown shrinks by a count at the end');
  document.dispatchKey({ key: 'ArrowUp', shiftKey: true });
  assert(E.sel.start === 2 && E.sel.len === 8, 'shift+ArrowUp grows by a count at the start');
  document.dispatchKey({ key: 'ArrowUp', shiftKey: true, ctrlKey: true });
  assert(E.sel.start === 4 && E.sel.len === 6, 'ctrl+shift+ArrowUp shrinks by a count at the start');
  for (let i = 0; i < 4; i++) document.dispatchKey({ key: 'ArrowDown' });
  assert(E.sel.start === 0 && E.sel.len === 2, 'selection wraps around past the last count');
  document.dispatchKey({ key: 'ArrowUp' });
  assert(E.sel.start === n - 2 && E.sel.len === 2, 'ArrowUp wraps to the last count');
  document.dispatchKey({ key: 'ArrowDown', shiftKey: true });
  assert(E.sel.start === n - 2 && E.sel.len === 4, 'a range can wrap round');
  let prevented = false; const selVal = $('variant').value;
  document.dispatchKey({ key: 'ArrowUp', target: { tagName: 'SELECT' }, preventDefault() { prevented = true; } });
  assert(prevented && E.sel.start === n - 4 && E.sel.len === 2 && $('variant').value === selVal, 'an arrow key on a focused dropdown does not change it; it steps the counts');
  prevented = false; document.dispatchKey({ key: 'ArrowLeft', target: { tagName: 'SELECT' }, preventDefault() { prevented = true; } });
  assert(prevented && E.sel.start === n - 4 && E.sel.len === 2, 'left/right do nothing on a dropdown');
  els['rows'].children[4].dispatch('click', {});
  assert(E.sel.start === 4 && E.sel.len === 2, 'click on a row selects its count');
  els['rows'].children[7].dispatch('click', { shiftKey: true });
  assert(E.sel.start === 4 && E.sel.len === 4, 'shift-click extends to the end of the clicked count');
  els['rows'].children[5].dispatch('click', { ctrlKey: true });
  assert(E.sel.start === 5 && E.sel.len === 1, 'ctrl-click selects one foot alone');
  assert(els['rows'].children[5].className.includes('last') && !els['rows'].children[4].className.includes('last'), 'only that row is marked');
  assert(els['head']._html.includes('<th>head</th>') && !els['head']._html.includes('code') && !els['head']._html.includes('hands'), 'the table shows #, count, who, x, y, head and nothing else');
  els['rows'].children[8].dispatch('click', {});
  const s = E.elem().steps[8]; const sx = s.x, sy = s.y, sh = s.heading;
  assert(s.count === '4' && s.dancer === 'L', 'step 8 is count 4 L.L');
  let p = E.px([s.x, s.y]); const sc = E.view.scale;
  els['floor'].dispatch('mousedown', { button: 0, clientX: p[0], clientY: p[1] });
  window.dispatch('mousemove', { clientX: p[0] + 10 * sc, clientY: p[1] + 10 * sc });
  window.dispatch('mouseup', {});
  assert(s.x === sx + 10 && s.y === sy - 10, `left-drag moved the foot to (${s.x},${s.y})`);
  // a right-drag turns the foot about the centre of its heel: the heel stays, the centre swings round it
  const heel = E.heelOf(s.x, s.y, s.heading); const hp = E.px(heel);
  p = E.px([s.x, s.y]);
  els['floor'].dispatch('mousedown', { button: 2, clientX: p[0], clientY: p[1] });
  window.dispatch('mousemove', { clientX: hp[0] + 50, clientY: hp[1] - 50 });
  window.dispatch('mouseup', {});
  const heel2 = E.heelOf(s.x, s.y, s.heading);
  assert(s.heading === 45, `right-drag turned the foot to ${s.heading}° (the mouse is up-right of the heel)`);
  assert(near(heel2[0], heel[0], 0.1) && near(heel2[1], heel[1], 0.1), `the heel stayed where it was (${heel2.map(v => v.toFixed(1))} vs ${heel.map(v => v.toFixed(1))})`);
  assert(!(near(s.x, sx + 10) && near(s.y, sy - 10)), 'so the centre of the foot moved');
  document.dispatchKey({ key: 'z', ctrlKey: true }); document.dispatchKey({ key: 'z', ctrlKey: true });
  assert(s.x === sx && s.y === sy && s.heading === sh, 'ctrl-z twice restores the step');
  p = E.px([s.x, s.y]);
  els['floor'].dispatch('mousedown', { button: 0, clientX: p[0], clientY: p[1] });
  window.dispatch('mousemove', { clientX: p[0] + 5 * sc, clientY: p[1] });
  window.dispatch('mouseup', {});
  await tick(600);
  assert(saved && saved.elements.length === NSK && saved.states && saved.elements[CUT].steps[8].x === sx + 5, 'the edit saved itself to disk (the whole document, within half a second)');
  assert(els['status'].textContent.includes('saved to dance/skeletons.json'), 'the status line says so');
  document.dispatchKey({ key: 'z', ctrlKey: true });
  // ---- the timeline port: compare with render.py's numbers for the cutoff (elements mode, swing 0.68)
  const ref = JSON.parse(fs.readFileSync(path.join(__dirname, 'timeline_ref.json'), 'utf8')).cutoff;
  const tl = E.buildTimeline(0.68);
  let worst = 0;
  for (const row of ref) for (const d of ['L', 'F']) { const w = E.weightsAt(tl.tracks[d], row.b); for (const f of ['L', 'R']) { const q = E.placementAt(tl.tracks[d].tracks[f], row.b); const r = row[d + '.' + f];
    worst = Math.max(worst, Math.abs(q[0] - r[0]), Math.abs(q[1] - r[1]), Math.abs(((q[2] - r[2]) % 360 + 540) % 360 - 180), Math.abs(w[f] - r[3])); } }
  assert(worst < 1e-3, `timeline port matches render.py at ${ref.length} sample beats (worst difference ${worst.toExponential(2)})`);
  assert(tl.length === 6 && tl.steps.length === 16 && tl.steps[0].t === 0 && Math.abs(tl.steps[6].t - 2.68) < 1e-9, 'element timeline: 6 beats, 16 steps, the & at 2.68');
  // ---- the help and the checker open in the overlay
  $('help').dispatch('click', {});
  assert(E.overlayKind === 'help' && $('overlay').style.display === 'flex' && $('ovBody').textContent.includes('about its heel'), 'the ? button opens the help in the overlay');
  document.dispatchKey({ key: 'Escape' });
  assert(E.overlayKind === null && $('overlay').style.display === 'none', 'Esc closes it');
  $('check').dispatch('click', {}); await tick();
  assert(E.overlayKind === 'text' && $('ovBody').textContent === 'stub', 'check shows the checker\'s output in the overlay');
  $('ovClose').dispatch('click', {});
  assert(E.overlayKind === null, 'close closes it');
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
  assert(els['undo'].disabled && $('variantRow').style.display === 'none' && $('elemEdit').style.display === 'none', 'undo, the starting-condition dropdown and the element buttons are off in compositions mode');
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
  $('addElement').value = 'rock step'; els['addElementBtn'].dispatch('click', {}); await tick(80);
  $('addElement').value = 'triple step'; els['addElementBtn'].dispatch('click', {}); await tick(80);
  assert(E.comps[NC].elements.join(',') === 'rock step,triple step' && composeCalls[composeCalls.length - 1].elements.length === 2, 'adding elements recomposes the draft');
  $('addElement').value = 'RTT'; $('addTimes').value = '3'; els['addElementBtn'].dispatch('click', {}); await tick(80);
  assert(E.comps[NC].elements.join(',') === 'rock step,triple step,RTT x3' && els['compList'].children[2]._html.includes('x 3') && els['compList'].children[2]._html.includes('sub'), 'a sub-composition entry with a repeat is added and shown');
  els['compList'].children[2].children[2].dispatch('click', {}); await tick(80);
  $('addTimes').value = '1';
  els['compList'].children[1].children[0].dispatch('click', {}); await tick(80);
  assert(E.comps[NC].elements.join(',') === 'triple step,rock step', 'the up button reorders');
  els['compList'].children[0].children[2].dispatch('click', {}); await tick(80);
  assert(E.comps[NC].elements.join(',') === 'rock step', 'the remove button removes');
  $('compName').dispatch('input', { target: { value: 'my basic' } });
  $('compRepeat').dispatch('change', { target: { value: '3' } }); await tick(80);
  assert(E.comps[NC].name === 'my basic' && E.comps[NC].repeat === 3 && composeCalls[composeCalls.length - 1].repeat === 3, 'name and repeat edits are kept and recomposed');
  await tick(500);
  assert(savedComps && savedComps.compositions.length === NCO + 1 && savedComps.compositions[NC].name === 'my basic', 'the compositions save themselves to disk too');
  els['deleteComp'].dispatch('click', {}); await tick(80);
  assert(E.comps.length === NCO, 'delete removes the composition (after confirm)');
  els['element'].dispatch('change', { target: { value: 'c' + RD } }); await tick(600);
  $('track').value = 'sunday';
  els['render'].dispatch('click', {});
  await tick();
  assert(rendered && rendered.composition === 'rock double triple step' && rendered.track === 'sunday', 'render posts the saved composition and the track');
  els['play'].dispatch('click', {}); await tick(80);
  assert(els['status'].textContent.includes('no Web Audio'), 'play reports the missing Web Audio in this stub instead of failing');
  els['modeElements'].dispatch('click', {});
  assert(E.mode === 'elements' && !els['undo'].disabled && E.elem().name === 'rock back cutoff', 'back to elements mode with the previous element');

  // ---- adding, editing and removing elements and steps
  els['element'].dispatch('change', { target: { value: 'lead underarm pass' } });
  const LUP = E.ei, NB = E.data.elements.length;
  assert(E.elem().start_state === 'facing' && $('elemEdit').style.display === 'flex', 'lead underarm pass (facing only); the element buttons show in elements mode');
  $('newElem').dispatch('click', {});
  assert(E.overlayKind === 'form' && $('ovBody')._cls.has('form'), 'new element opens the form in the overlay');
  $('feName').value = 'lead underarm pass'; $('feStart').value = 'side'; $('feFree').value = 'L'; $('feEnd').value = 'side'; $('feFrom').value = String(LUP);
  saved = null; $('feOk').dispatch('click', {}); await tick(600);
  const NE = E.data.elements[E.ei], T = E.data.elements[LUP];
  assert(E.data.elements.length === NB + 1 && NE.name === 'lead underarm pass' && NE.start_state === 'side' && NE.end_state === 'side' && NE.leader_free_start === 'L', 'create adds a new starting condition of the element');
  assert(NE.steps.length === T.steps.length && NE.beats === T.beats && NE.steps.every(x => Object.keys(x).sort().join() === 'count,dancer,foot,heading,x,y'), 'the copy keeps every count; a step is count, dancer, foot, x, y, heading and nothing else');
  assert(NE.steps.filter(x => x.dancer === 'L').every((x, k) => { const t = T.steps.filter(y => y.dancer === 'L')[k]; return near(x.x, t.x) && near(x.y, t.y) && near(x.heading, t.heading); }), 'the leader dances the same (his left foot is the origin in every state)');
  const f1 = NE.steps.find(x => x.dancer === 'F' && x.count === '1'), f2 = NE.steps.find(x => x.dancer === 'F' && x.count === '2');
  assert(near(f1.x, 54) && near(f1.y, -30) && near(f1.heading, 0) && near(f2.x, 34) && near(f2.y, 0), `the follower dances the same from her side start: count 1 at (${f1.x},${f1.y},${f1.heading}), count 2 at (${f2.x},${f2.y})`);
  assert(saved && saved.elements.length === NB + 1, 'the new element saved itself');
  assert(els['element'].children.some(o => o.value === 'lead underarm pass' && o.textContent.includes('2 starting conditions')), 'the dropdown counts the new starting condition');
  $('newElem').dispatch('click', {});
  $('feName').value = 'lead underarm pass'; $('feStart').value = 'side'; $('feFree').value = 'L'; $('feEnd').value = 'side'; $('feFrom').value = '';
  $('feOk').dispatch('click', {});
  assert(E.data.elements.length === NB + 1 && $('feMsg').textContent.includes('already exists'), 'the same starting condition twice is refused');
  document.dispatchKey({ key: 'Escape' });
  // a mirror onto the other feet: the circle from facing with the right foot free
  els['element'].dispatch('change', { target: { value: 'circle' } });
  const CIR = E.ei;
  $('newElem').dispatch('click', {});
  $('feName').value = 'circle'; $('feStart').value = 'facing'; $('feFree').value = 'R'; $('feEnd').value = 'side'; $('feFrom').value = String(CIR);
  $('feOk').dispatch('click', {});
  const MC = E.elem();
  const m0 = MC.steps.find(x => x.dancer === 'L' && x.count === '1'), m1 = MC.steps.find(x => x.dancer === 'F' && x.count === '1');
  assert(MC.leader_free_start === 'R' && m0.foot === 'R' && near(m0.x, 20) && near(m0.y, -30), `mirrored: his count 1 is on the right foot at (${m0.x},${m0.y})`);
  assert(m1.foot === 'L' && near(m1.x, 20) && near(m1.y, 25) && near(m1.heading, 180), `mirrored: her count 1 is on the left foot at (${m1.x},${m1.y},${m1.heading})`);
  // a blank element, built count by count
  $('newElem').dispatch('click', {});
  $('feName').value = 'my move'; $('feStart').value = 'side'; $('feFree').value = 'L'; $('feEnd').value = 'facing'; $('feFrom').value = '';
  $('feOk').dispatch('click', {});
  const MM = E.elem();
  assert(MM.name === 'my move' && MM.steps.length === 0 && MM.beats === 0 && E.overlayKind === null, 'a blank element has no steps yet');
  const menu = () => $('ctxMenu').children, pick = txt => { const it = menu().find(c => c.textContent.startsWith(txt)); assert(!!it, 'the menu offers "' + txt + '"'); it.dispatch('click', {}); };
  $('head').dispatch('contextmenu', { clientX: 10, clientY: 10 });
  assert($('ctxMenu').style.display === 'block' && menu().length === 1, 'right-click on the column titles opens the menu');
  pick('insert the first count');
  $('rows').children[0].dispatch('contextmenu', { clientX: 10, clientY: 10 });
  assert(menu().length === 4 && menu()[2].className === 'off', 'right-click on the first count: four choices, "half after previous" off');
  pick('insert a count after 1');
  const lineup = () => E.elem().steps.map(x => x.count + x.dancer + x.foot).join(' ');
  assert(lineup() === '1LL 1FR 2LR 2FL' && E.elem().beats === 2, 'add count twice: each dancer\'s free foot in place, alternating: ' + lineup());
  $('rows').children[0].dispatch('contextmenu', { clientX: 10, clientY: 10 }); pick('insert a count after 1');
  assert(lineup() === '1LL 1FR 2LR 2FL 3LR 3FL', 'inserting after 1 makes the new 2 and moves the old 2 to 3: ' + lineup());
  document.dispatchKey({ key: 'z', ctrlKey: true });
  $('head').dispatch('contextmenu', { clientX: 10, clientY: 10 }); pick('insert a count before the first');
  assert(lineup() === '1LL 1FR 2LL 2FR 3LR 3FL', 'inserting before the first makes a new 1 and moves the others a beat later: ' + lineup());
  document.dispatchKey({ key: 'z', ctrlKey: true });
  $('rows').children[2].dispatch('click', {});
  $('addTriple').dispatch('click', {});
  assert(lineup() === '1LL 1FR 2LR 2FL 3LL 3FR 3&LR 3&FL 4LL 4FR' && E.elem().beats === 4 && E.sel.start === 4 && E.sel.len === 2, 'add triple: counts 3, 3&, 4 with the feet alternating, the first count selected: ' + lineup());
  document.dispatchKey({ key: 'z', ctrlKey: true });
  assert(lineup() === '1LL 1FR 2LR 2FL', 'ctrl-z removes the whole triple');
  $('rows').children[2].dispatch('click', {});
  $('halfAfter').dispatch('click', {});
  assert(lineup() === '1LL 1FR 1&LR 1&FL' && E.elem().beats === 2, 'half after previous: count 2 becomes 1&: ' + lineup());
  $('addTriple').dispatch('click', {});
  assert(lineup() === '1LL 1FR 1&LR 1&FL 2LL 2FR 2&LR 2&FL 3LL 3FR', 'a triple after it starts on the next count: ' + lineup());
  E.elem().steps.slice(4).forEach(st => { st.count = String(parseInt(st.count, 10) + 2) + (st.count.endsWith('&') ? '&' : ''); });
  $('rows').children[4].dispatch('click', {}); $('halfAfter').dispatch('click', {});
  assert(lineup() === '1LL 1FR 1&LR 1&FL 2LL 2FR 2&LR 2&FL 3LL 3FR', 'a count after 1& becomes 2 and the later counts move back with it: ' + lineup());
  document.dispatchKey({ key: 'z', ctrlKey: true }); document.dispatchKey({ key: 'z', ctrlKey: true }); document.dispatchKey({ key: 'z', ctrlKey: true });
  assert(lineup() === '1LL 1FR 2LR 2FL', 'ctrl-z undoes them: ' + lineup());
  // ---- follower mirrors leader (side by side: the mirror line is halfway between his right foot and her left, x = 27)
  const at = (d, c) => E.elem().steps.find(st => st.dancer === d && st.count === c);
  $('rows').children[0].dispatch('click', {});
  { const f0 = at('F', '1'), q0 = E.px([f0.x, f0.y]), k = E.view.scale;   // her step out of place first, to see the mirror put it back
    els['floor'].dispatch('mousedown', { button: 0, clientX: q0[0], clientY: q0[1] }); window.dispatch('mousemove', { clientX: q0[0] + 16 * k, clientY: q0[1] }); window.dispatch('mouseup', {}); }
  assert(at('F', '1').x === 70, 'her step moved out of place (70)');
  $('mirrorChk').checked = true; $('mirrorChk').dispatch('change', {});
  assert(E.elem().mirror === true && at('F', '1').x === 70, 'ticking changes nothing by itself: her step stays at ' + at('F', '1').x);
  $('rows').children[0].dispatch('click', {});
  let ls = at('L', '1'), q = E.px([ls.x, ls.y]); const sc2 = E.view.scale;
  els['floor'].dispatch('mousedown', { button: 0, clientX: q[0], clientY: q[1] });
  window.dispatch('mousemove', { clientX: q[0] + 10 * sc2, clientY: q[1] + 10 * sc2 }); window.dispatch('mouseup', {});
  assert(ls.x === 10 && ls.y === -10 && at('F', '1').x === 44 && at('F', '1').y === -10 && at('F', '1').heading === 0, 'dragging his foot moves hers to the reflected place: (' + at('F', '1').x + ',' + at('F', '1').y + ')');
  ls = at('L', '1'); q = E.px([ls.x, ls.y]);
  els['floor'].dispatch('mousedown', { button: 2, clientX: q[0], clientY: q[1] });
  window.dispatch('mousemove', { clientX: q[0] + 200, clientY: q[1] - 200 * 0.0001 }); window.dispatch('mouseup', {});
  assert(ls.heading > 0 && at('F', '1').heading === -ls.heading, 'turning his foot turns hers the other way: ' + ls.heading + ' / ' + at('F', '1').heading);
  document.dispatchKey({ key: 'z', ctrlKey: true });
  assert(at('L', '1').heading === 0 && at('F', '1').heading === 0, 'ctrl-z undoes the turn of both feet');
  let fs_ = at('F', '1'), q2 = E.px([fs_.x, fs_.y]);
  els['floor'].dispatch('mousedown', { button: 0, clientX: q2[0], clientY: q2[1] });
  window.dispatch('mousemove', { clientX: q2[0] + 6 * sc2, clientY: q2[1] }); window.dispatch('mouseup', {});
  assert(at('F', '1').x === 50 && at('L', '1').x === 10 && at('L', '1').y === -10, 'dragging her foot does not touch his');
  $('rows').children[2].dispatch('click', {}); $('addTriple').dispatch('click', {});
  assert(['3', '3&', '4'].every(c => at('F', c).foot !== at('L', c).foot && at('F', c).x === 54 - at('L', c).x && at('F', c).y === at('L', c).y), 'add triple: her new steps are the mirror of his');
  $('rows').children[0].dispatch('click', { ctrlKey: true }); $('removeSteps').dispatch('click', {});
  assert(!at('L', '1') && !at('F', '1'), 'removing his step removes her step on that count');
  document.dispatchKey({ key: 'z', ctrlKey: true });
  $('mirrorChk').checked = false; $('mirrorChk').dispatch('change', {});
  const before3 = JSON.stringify(E.elem().steps);
  assert(!E.elem().mirror && before3 === JSON.stringify(E.elem().steps), 'unticking changes nothing');
  $('rows').children[0].dispatch('click', {}); ls = at('L', '1'); q = E.px([ls.x, ls.y]);
  els['floor'].dispatch('mousedown', { button: 0, clientX: q[0], clientY: q[1] });
  window.dispatch('mousemove', { clientX: q[0] + 4 * sc2, clientY: q[1] }); window.dispatch('mouseup', {});
  assert(at('L', '1').x === 14 && at('F', '1').x === 50, 'unticked: dragging his foot leaves hers');
  for (let k = 0; k < 7; k++) document.dispatchKey({ key: 'z', ctrlKey: true });
  assert(lineup() === '1LL 1FR 2LR 2FL' && at('F', '1').x === 54 && at('L', '1').x === 0 && !E.elem().mirror, 'ctrl-z takes it all back: ' + lineup());
  $('rows').children[2].dispatch('click', {});
  assert(near(E.elem().steps[0].x, 0) && near(E.elem().steps[1].x, 54) && E.sel.start === 2 && E.sel.len === 2, 'the new feet stand where the free feet are; the new count is selected');
  let row = $('rows').children[2]; row.children[1].dispatch('dblclick', {});
  let inp = row.children[1].children[0]; inp.value = '3'; inp.dispatch('keydown', { key: 'Enter' });
  assert(E.elem().steps[2].count === '3' && E.elem().steps[3].count === '3' && E.elem().beats === 3, 'double-click a selected count: both steps of the count change, beats follow (3)');
  row = $('rows').children[0]; row.children[1].dispatch('dblclick', {});
  inp = row.children[1].children[0]; inp.value = '2x'; inp.dispatch('keydown', { key: 'Enter' });
  assert(E.elem().steps[0].count === '1' && $('status').textContent.includes('is not a count'), 'a count that is not a count label is refused');
  $('rows').children[1].children[2].dispatch('dblclick', {});
  assert(E.elem().steps[1].foot === 'L', 'double-click who switches the foot');
  document.dispatchKey({ key: 'z', ctrlKey: true });
  assert(E.elem().steps[1].foot === 'R', 'ctrl-z undoes the switch');
  $('rows').children[0].dispatch('click', {});
  $('removeSteps').dispatch('click', {});
  assert(E.elem().steps.length === 2 && E.elem().steps[0].count === '3' && E.elem().beats === 1, 'remove steps removes the selected count');
  document.dispatchKey({ key: 'z', ctrlKey: true });
  assert(E.elem().steps.length === 4, 'ctrl-z brings them back');
  document.dispatchKey({ key: 'Delete' });
  assert(E.elem().steps.length === 2, 'the Delete key removes the selected steps');
  document.dispatchKey({ key: 'z', ctrlKey: true });
  // edit the element: its end state
  $('editElem').dispatch('click', {});
  $('feName').value = 'my move'; $('feStart').value = 'side'; $('feFree').value = 'L'; $('feEnd').value = 'side';
  $('feOk').dispatch('click', {});
  assert(E.elem().name === 'my move' && E.elem().end_state === 'side' && E.elem().steps.length === 4, 'edit changes the end state, the steps stay');
  // delete, and bring back
  const NM = E.data.elements.length;
  $('deleteElem').dispatch('click', {});
  assert(E.data.elements.length === NM - 1 && !E.data.elements.some(x => x.name === 'my move'), 'delete removes the element (after confirm)');
  document.dispatchKey({ key: 'z', ctrlKey: true });
  assert(E.data.elements.length === NM && E.elem().name === 'my move', 'ctrl-z brings the deleted element back');
  await tick(600);
  assert(saved && saved.elements.length === NM, 'and that saved itself too');
  // ---- which way a foot turns: the arrow at the toe of its previous place, a click flips it (winding, user 2026-10-25)
  els['element'].dispatch('change', { target: { value: 'rock back cutoff' } });
  const cs2 = E.elem().steps, turnOf = j => { const st = cs2[j]; let prev = E.data.states.side.feet[st.dancer + '.' + st.foot][2];
    for (let k = 0; k < j; k++) if (cs2[k].dancer === st.dancer && cs2[k].foot === st.foot) prev = cs2[k].heading; return st.heading - prev; };
  const J = cs2.findIndex((st, j) => Math.abs(turnOf(j)) > 1);
  const later = cs2.findIndex((st, j) => j > J && st.dancer === cs2[J].dancer && st.foot === cs2[J].foot);
  assert(J >= 0 && later > J, 'the cutoff has a turning step (#' + J + ', ' + turnOf(J) + '°) and a later step of that foot (#' + later + ')');
  $('rows').children[J].dispatch('click', {});
  const hit = E.arrowHits.find(h => h.i === J);
  assert(!!hit, 'a turning step has an arrow on the floor');
  const t0 = turnOf(J), tl0 = turnOf(later), h0 = cs2[J].heading;
  els['floor'].dispatch('mousedown', { button: 0, clientX: hit.x, clientY: hit.y });
  assert(Math.abs(turnOf(J) - (t0 > 0 ? t0 - 360 : t0 + 360)) < 0.01 && Math.abs(cs2[J].heading - h0) === 360, 'clicking the arrow makes it turn the other way round: ' + t0 + '° -> ' + turnOf(J) + '°');
  assert(Math.abs(turnOf(later) - tl0) < 0.01, 'the foot\'s later step keeps its turn (' + tl0 + '°)');
  assert(saved === null || true, '');
  document.dispatchKey({ key: 'z', ctrlKey: true });
  assert(cs2[J] === undefined || E.elem().steps[J].heading === h0, 'ctrl-z flips it back');
  assert(E.data.elements.every(x => x.winding), 'every element is marked as keeping its winding after the page loads');
})();

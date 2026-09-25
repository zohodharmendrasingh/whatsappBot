/* FloChat visual flow builder: drag-and-drop canvas, step editor, WhatsApp preview, AI, templates, test chat.
   No external libraries. Data comes from #fbData (base64 JSON); saves go to flowBuilderSave. */
(function () {
  'use strict';
  var root = document.getElementById('fb');
  if (!root) { return; }
  var URLS = { save: root.dataset.saveUrl, ai: root.dataset.aiUrl, back: root.dataset.backUrl };
  var FLOW_ID = root.dataset.flowId;
  var AI_ON = root.dataset.ai === 'Y';
  function b64json(id) {
    var el = document.getElementById(id);
    var s = el.textContent.trim().replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) { s += '='; }
    var bin = atob(s);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) { bytes[i] = bin.charCodeAt(i); }
    return JSON.parse(new TextDecoder('utf-8').decode(bytes));
  }
  var G = b64json('fbGraph');
  var TEMPLATES = b64json('fbTemplates');
  var OTHER_FLOWS = b64json('fbFlows');

  // ------------------------------------------------------------------ step types
  var TYPES = {
    text:    { icon: '💬', name: 'Message',       hint: 'Send a message, then continue', color: '#2563eb', next: true },
    buttons: { icon: '🔘', name: 'Buttons',       hint: 'Up to 3 quick-reply buttons', color: '#16a34a', choice: true },
    list:    { icon: '📋', name: 'List menu',     hint: 'Menu with up to 10 choices', color: '#0d9488', choice: true },
    ask:     { icon: '❓', name: 'Question',      hint: 'Ask and save the answer', color: '#9333ea', next: true },
    image:   { icon: '🖼️', name: 'Image',         hint: 'Send a picture with caption', color: '#db2777', next: true },
    handoff: { icon: '🙋', name: 'Talk to agent', hint: 'Hand the chat to your team', color: '#ea580c', end: true },
    end:     { icon: '🏁', name: 'End',           hint: 'Final message, chat ends', color: '#475569', end: true },
    goto:    { icon: '↪️', name: 'Go to flow',    hint: 'Jump to another bot flow', color: '#7c3aed', end: true }
  };
  var LIM = { buttons: 3, rows: 10, btnLabel: 20, rowLabel: 24, rowDesc: 72, header: 60, footer: 60, listBtn: 20, body: 1024, text: 4096 };
  var ANSWER_CHECKS = [
    { key: '', label: 'Any answer', re: '' },
    { key: 'number', label: 'A number', re: '^[0-9]+([.,][0-9]+)?$' },
    { key: 'phone', label: 'Phone number', re: '^\\+?[0-9 ()-]{8,18}$' },
    { key: 'email', label: 'Email address', re: '^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$' },
    { key: 'yesno', label: 'Yes or No', re: '(?i)^(yes|no|y|n)$' }
  ];

  // ------------------------------------------------------------------ state
  var meta = { flowName: G.flowName || '', triggerKeywords: G.triggerKeywords || '', isDefault: !!G.isDefault, isActive: G.isActive !== false };
  var graph = { startNodeId: G.startNodeId || '', nodes: (G.nodes || []).map(normNode) };
  var sel = null;          // {kind:'node', id} | {kind:'edge', from, opt}
  var view = { x: 40, y: 30, z: 1 };
  var undoStack = [], redoStack = [], dirty = false, testNode = null;

  function normNode(n) {
    return { id: n.id, type: TYPES[n.type] ? n.type : 'text', text: n.text || '', header: n.header || '', footer: n.footer || '',
      buttonLabel: n.buttonLabel || '', mediaUrl: n.mediaUrl || '', saveAs: n.saveAs || '', validation: n.validation || '',
      next: n.next || '', targetFlowId: n.targetFlowId || '', x: typeof n.x === 'number' ? n.x : null, y: typeof n.y === 'number' ? n.y : null,
      options: (n.options || []).map(function (o) { return { label: o.label || '', description: o.description || '', keywords: o.keywords || '', target: o.target || '' }; }) };
  }
  function snapshot() { return JSON.stringify({ meta: meta, graph: graph }); }
  function change(fn, opts) {
    undoStack.push(snapshot()); if (undoStack.length > 80) { undoStack.shift(); }
    redoStack = [];
    fn();
    setDirty(true);
    if (!opts || !opts.noRender) { render(); } else { drawEdges(); refreshChecks(); }
    if (!opts || !opts.keepPanel) { renderPanel(); }
  }
  function restore(s) { var o = JSON.parse(s); meta = o.meta; graph = o.graph; if (sel && sel.kind === 'node' && !node(sel.id)) { sel = null; } syncMetaInputs(); render(); renderPanel(); setDirty(true); }
  function undo() { if (!undoStack.length) { return; } redoStack.push(snapshot()); restore(undoStack.pop()); }
  function redo() { if (!redoStack.length) { return; } undoStack.push(snapshot()); restore(redoStack.pop()); }
  function setDirty(d) {
    dirty = d;
    var b = document.getElementById('fbSave');
    b.classList.toggle('pulse', d);
    b.textContent = d ? 'Save changes' : 'Saved';
    document.getElementById('fbUndo').disabled = !undoStack.length;
    document.getElementById('fbRedo').disabled = !redoStack.length;
  }
  function node(id) { for (var i = 0; i < graph.nodes.length; i++) { if (graph.nodes[i].id === id) { return graph.nodes[i]; } } return null; }
  function newId(type) {
    var base = { text: 'MESSAGE', buttons: 'BUTTONS', list: 'MENU', ask: 'QUESTION', image: 'IMAGE', handoff: 'AGENT', end: 'END', goto: 'GOTO' }[type] || 'STEP';
    for (var i = 1; ; i++) { if (!node(base + '_' + i)) { return base + '_' + i; } }
  }

  // ------------------------------------------------------------------ DOM helpers
  function h(tag, attrs, kids) {
    var el = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (v === null || v === undefined || v === false) { return; }
        if (k === 'class') { el.className = v; } else if (k === 'text') { el.textContent = v; }
        else if (k.slice(0, 2) === 'on') { el.addEventListener(k.slice(2), v); }
        else if (k === 'style') { el.setAttribute('style', v); } else { el.setAttribute(k, v === true ? '' : v); }
      });
    }
    (kids || []).forEach(function (c) { if (c !== null && c !== undefined && c !== false) { el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); } });
    return el;
  }
  function toast(msg, kind) {
    var t = document.getElementById('fbToast');
    t.textContent = msg; t.className = 'fb-toast show ' + (kind || '');
    clearTimeout(toast.tm); toast.tm = setTimeout(function () { t.className = 'fb-toast'; }, kind === 'err' ? 7000 : 3500);
  }
  function post(url, data) {
    return fetch(url, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(data).toString() }).then(function (r) {
      return r.json().catch(function () { throw new Error(r.status === 401 ? 'Your session expired. Please log in again in another tab, then retry.' : 'Server error (' + r.status + ')'); });
    });
  }
  function busy(on, text) { var b = document.getElementById('fbBusy'); b.hidden = !on; if (text) { document.getElementById('fbBusyText').textContent = text; } }
  function preview(s, n) { s = (s || '').replace(/\s+/g, ' ').trim(); return s.length > n ? s.slice(0, n - 1) + '…' : s; }

  // ------------------------------------------------------------------ canvas
  var canvas = document.getElementById('fbCanvas');
  var world = document.getElementById('fbWorld');
  var svg = document.getElementById('fbEdges');
  function applyView() {
    world.style.transform = 'translate(' + view.x + 'px,' + view.y + 'px) scale(' + view.z + ')';
    canvas.style.backgroundPosition = view.x + 'px ' + view.y + 'px';
    canvas.style.backgroundSize = (22 * view.z) + 'px ' + (22 * view.z) + 'px';
    document.getElementById('fbZoomPct').textContent = Math.round(view.z * 100) + '%';
  }
  function toWorld(cx, cy) { var r = canvas.getBoundingClientRect(); return { x: (cx - r.left - view.x) / view.z, y: (cy - r.top - view.y) / view.z }; }
  function zoomAt(z, cx, cy) {
    z = Math.max(0.3, Math.min(1.6, z));
    var r = canvas.getBoundingClientRect();
    if (cx === undefined) { cx = r.left + r.width / 2; cy = r.top + r.height / 2; }
    var w = toWorld(cx, cy);
    view.z = z; view.x = cx - r.left - w.x * z; view.y = cy - r.top - w.y * z; applyView();
  }

  function render() {
    world.querySelectorAll('.fb-node').forEach(function (e) { e.remove(); });
    var needLayout = graph.nodes.some(function (n) { return n.x === null; });
    graph.nodes.forEach(function (n) { world.appendChild(nodeEl(n)); });
    if (needLayout) { autoArrange(true); return; }
    drawEdges();
    refreshChecks();
    document.getElementById('fbEmpty').hidden = graph.nodes.length > 0;
  }

  function nodeEl(n) {
    var t = TYPES[n.type];
    var isStart = graph.startNodeId === n.id;
    var body = [];
    if (n.type === 'image') {
      body.push(h('div', { class: 'fb-n-img', text: n.mediaUrl ? '🖼️ ' + preview(n.mediaUrl.replace(/^https:\/\//, ''), 34) : '🖼️ No image yet' }));
    }
    if (n.type === 'goto') {
      var f = OTHER_FLOWS.filter(function (x) { return x.id === n.targetFlowId; })[0];
      body.push(h('div', { class: 'fb-n-text', text: '→ ' + (f ? f.name : 'choose a flow') }));
    } else if (n.text || n.type !== 'image') {
      body.push(h('div', { class: 'fb-n-text' + (n.text ? '' : ' empty'), text: preview(n.text, 120) || 'Empty message' }));
    }
    if (n.type === 'ask') {
      body.push(h('div', { class: 'fb-n-save', text: '💾 saves answer as ' + (n.saveAs || n.id) }));
    }
    var outs = [];
    if (t.choice) {
      n.options.forEach(function (o, i) {
        outs.push(h('div', { class: 'fb-n-opt' }, [
          h('span', { text: o.label || '(no text)' }),
          h('i', { class: 'fb-port out' + (o.target ? ' on' : ''), 'data-node': n.id, 'data-opt': i, title: 'Drag to connect' })
        ]));
      });
      if (n.type === 'buttons' && n.options.length > LIM.buttons) { outs.push(h('div', { class: 'fb-n-warn', text: 'Shown as a list (more than 3)' })); }
    } else if (t.next) {
      outs.push(h('div', { class: 'fb-n-opt fb-n-then' }, [h('span', { text: n.type === 'ask' ? 'After the answer' : 'Then' }),
        h('i', { class: 'fb-port out' + (n.next ? ' on' : ''), 'data-node': n.id, 'data-opt': 'next', title: 'Drag to connect' })]));
    } else {
      outs.push(h('div', { class: 'fb-n-final', text: n.type === 'handoff' ? 'Bot pauses, your team replies' : n.type === 'goto' ? 'Continues in that flow' : 'Conversation ends' }));
    }
    var el = h('div', { class: 'fb-node' + (sel && sel.kind === 'node' && sel.id === n.id ? ' sel' : '') + (testNode === n.id ? ' testing' : ''),
      'data-id': n.id, style: 'left:' + (n.x || 0) + 'px;top:' + (n.y || 0) + 'px;--c:' + t.color }, [
      h('i', { class: 'fb-port in', title: 'Incoming' }),
      h('div', { class: 'fb-n-head' }, [
        h('span', { class: 'fb-n-ico', text: t.icon }), h('span', { class: 'fb-n-type', text: t.name }),
        isStart ? h('span', { class: 'fb-n-start', text: 'START' }) : null,
        h('span', { class: 'fb-n-id', text: n.id })
      ]),
      h('div', { class: 'fb-n-body' }, body),
      h('div', { class: 'fb-n-outs' }, outs)
    ]);
    return el;
  }

  function portCenter(el) {
    var r = el.getBoundingClientRect(), w = world.getBoundingClientRect();
    return { x: (r.left + r.width / 2 - w.left) / view.z, y: (r.top + r.height / 2 - w.top) / view.z };
  }
  function edgePath(a, b) {
    var dx = Math.max(50, Math.abs(b.x - a.x) / 2);
    if (b.x < a.x + 30) { dx = Math.max(120, Math.abs(b.y - a.y) / 3); }
    return 'M' + a.x + ',' + a.y + ' C' + (a.x + dx) + ',' + a.y + ' ' + (b.x - dx) + ',' + b.y + ' ' + b.x + ',' + b.y;
  }
  function drawEdges() {
    while (svg.firstChild) { svg.removeChild(svg.firstChild); }
    var NS = 'http://www.w3.org/2000/svg';
    var defs = document.createElementNS(NS, 'defs');
    defs.innerHTML = '<marker id="fbArrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#94a3b8"/></marker>' +
      '<marker id="fbArrowSel" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#16a34a"/></marker>';
    svg.appendChild(defs);
    graph.nodes.forEach(function (n) {
      var links = [];
      if (TYPES[n.type].choice) { n.options.forEach(function (o, i) { if (o.target) { links.push({ opt: i, to: o.target }); } }); }
      else if (TYPES[n.type].next && n.next) { links.push({ opt: 'next', to: n.next }); }
      links.forEach(function (l) {
        var from = world.querySelector('.fb-port.out[data-node="' + n.id + '"][data-opt="' + l.opt + '"]');
        var toEl = world.querySelector('.fb-node[data-id="' + l.to + '"] .fb-port.in');
        if (!from || !toEl) { return; }
        var a = portCenter(from), b = portCenter(toEl);
        var d = edgePath(a, b);
        var isSel = sel && sel.kind === 'edge' && sel.from === n.id && String(sel.opt) === String(l.opt);
        var hit = document.createElementNS(NS, 'path');
        hit.setAttribute('d', d); hit.setAttribute('class', 'fb-edge-hit');
        hit.addEventListener('pointerdown', function (e) { e.stopPropagation(); sel = { kind: 'edge', from: n.id, opt: l.opt }; drawEdges(); renderPanel(); markSel(); });
        var p = document.createElementNS(NS, 'path');
        p.setAttribute('d', d); p.setAttribute('class', 'fb-edge' + (isSel ? ' sel' : ''));
        p.setAttribute('marker-end', isSel ? 'url(#fbArrowSel)' : 'url(#fbArrow)');
        svg.appendChild(p); svg.appendChild(hit);
      });
    });
  }
  function markSel() {
    world.querySelectorAll('.fb-node').forEach(function (e) { e.classList.toggle('sel', !!(sel && sel.kind === 'node' && sel.id === e.dataset.id)); });
  }

  // ---- auto layout: columns by distance from the start step
  function autoArrange(silent) {
    var depth = {}, order = [], q = [];
    if (node(graph.startNodeId)) { q.push(graph.startNodeId); depth[graph.startNodeId] = 0; }
    while (q.length) {
      var id = q.shift(), n = node(id); order.push(id);
      targets(n).forEach(function (t) { if (node(t) && depth[t] === undefined) { depth[t] = depth[id] + 1; q.push(t); } });
    }
    var maxD = 0; Object.keys(depth).forEach(function (k) { maxD = Math.max(maxD, depth[k]); });
    graph.nodes.forEach(function (n) { if (depth[n.id] === undefined) { depth[n.id] = maxD + 1; order.push(n.id); } });
    // measure heights from the DOM
    var heights = {};
    world.querySelectorAll('.fb-node').forEach(function (e) { heights[e.dataset.id] = e.offsetHeight || 140; });
    var colY = {};
    order.forEach(function (id) {
      var n = node(id), d = depth[id];
      n.x = 40 + d * 320; n.y = colY[d] === undefined ? 40 : colY[d]; colY[d] = n.y + (heights[id] || 140) + 36;
    });
    world.querySelectorAll('.fb-node').forEach(function (e) { var n = node(e.dataset.id); e.style.left = n.x + 'px'; e.style.top = n.y + 'px'; });
    drawEdges(); refreshChecks();
    document.getElementById('fbEmpty').hidden = graph.nodes.length > 0;
    if (!silent) { fit(); }
  }
  function targets(n) {
    if (!n) { return []; }
    if (TYPES[n.type].choice) { return n.options.map(function (o) { return o.target; }).filter(Boolean); }
    return TYPES[n.type].next && n.next ? [n.next] : [];
  }
  function fit() {
    if (!graph.nodes.length) { view = { x: 40, y: 30, z: 1 }; applyView(); return; }
    var minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
    world.querySelectorAll('.fb-node').forEach(function (e) {
      var n = node(e.dataset.id);
      minX = Math.min(minX, n.x); minY = Math.min(minY, n.y);
      maxX = Math.max(maxX, n.x + e.offsetWidth); maxY = Math.max(maxY, n.y + e.offsetHeight);
    });
    var r = canvas.getBoundingClientRect();
    // readable first: never shrink below 70%; if the flow is bigger, start at its top-left (pan to see the rest)
    var z = Math.min(1, Math.max(0.7, Math.min((r.width - 60) / (maxX - minX), (r.height - 60) / (maxY - minY))));
    var w = (maxX - minX) * z, hh = (maxY - minY) * z;
    view.z = z;
    view.x = (w < r.width - 40 ? (r.width - w) / 2 : 36) - minX * z;
    view.y = (hh < r.height - 40 ? Math.max(20, (r.height - hh) / 2) : 24) - minY * z;
    applyView();
  }

  // ---- pointer interactions: pan, move node, connect ports
  var drag = null;
  canvas.addEventListener('pointerdown', function (e) {
    if (e.button !== 0 || e.target.closest('.fb-quick, .fb-zoom, .fb-empty-box')) { return; }
    var port = e.target.closest('.fb-port.out');
    var nEl = e.target.closest('.fb-node');
    if (port) {
      e.preventDefault(); e.stopPropagation();
      var a = portCenter(port);
      var line = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      line.setAttribute('class', 'fb-edge drawing'); svg.appendChild(line);
      drag = { kind: 'connect', from: port.dataset.node, opt: port.dataset.opt, a: a, line: line };
    } else if (nEl) {
      var n = node(nEl.dataset.id);
      if (!sel || sel.kind !== 'node' || sel.id !== n.id) { sel = { kind: 'node', id: n.id }; markSel(); drawEdges(); renderPanel(); }
      drag = { kind: 'move', id: n.id, el: nEl, sx: e.clientX, sy: e.clientY, ox: n.x, oy: n.y, moved: false };
    } else {
      drag = { kind: 'pan', sx: e.clientX, sy: e.clientY, ox: view.x, oy: view.y, moved: false };
      canvas.classList.add('panning');
    }
    canvas.setPointerCapture(e.pointerId);
  });
  canvas.addEventListener('pointermove', function (e) {
    if (!drag) { return; }
    if (drag.kind === 'pan') {
      view.x = drag.ox + e.clientX - drag.sx; view.y = drag.oy + e.clientY - drag.sy; drag.moved = true; applyView();
    } else if (drag.kind === 'move') {
      var dx = (e.clientX - drag.sx) / view.z, dy = (e.clientY - drag.sy) / view.z;
      if (!drag.moved && Math.abs(dx) + Math.abs(dy) < 3) { return; }
      if (!drag.moved) { undoStack.push(snapshot()); redoStack = []; drag.moved = true; }
      var n = node(drag.id); n.x = Math.round(drag.ox + dx); n.y = Math.round(drag.oy + dy);
      drag.el.style.left = n.x + 'px'; drag.el.style.top = n.y + 'px'; drawEdges();
    } else if (drag.kind === 'connect') {
      var b = toWorld(e.clientX, e.clientY);
      drag.line.setAttribute('d', edgePath(drag.a, b)); svg.appendChild(drag.line);
      world.querySelectorAll('.fb-node.drop').forEach(function (x) { x.classList.remove('drop'); });
      var over = document.elementFromPoint(e.clientX, e.clientY);
      var t = over && over.closest && over.closest('.fb-node');
      if (t && t.dataset.id !== drag.from) { t.classList.add('drop'); }
    }
  });
  canvas.addEventListener('pointerup', function (e) {
    if (!drag) { return; }
    var d = drag; drag = null; canvas.classList.remove('panning');
    if (d.kind === 'pan' && !d.moved) { if (sel) { sel = null; markSel(); drawEdges(); renderPanel(); } }
    if (d.kind === 'move' && d.moved) { setDirty(true); }
    if (d.kind === 'connect') {
      d.line.remove();
      world.querySelectorAll('.fb-node.drop').forEach(function (x) { x.classList.remove('drop'); });
      var over = document.elementFromPoint(e.clientX, e.clientY);
      var t = over && over.closest && over.closest('.fb-node');
      if (t && t.dataset.id !== d.from) { connect(d.from, d.opt, t.dataset.id); }
      else if (!t && over && canvas.contains(over)) { quickAdd(e.clientX, e.clientY, d.from, d.opt); }
    }
  });
  canvas.addEventListener('wheel', function (e) {
    e.preventDefault();
    if (e.ctrlKey || e.metaKey) { zoomAt(view.z * Math.exp(-e.deltaY * 0.01), e.clientX, e.clientY); }
    else { view.x -= e.deltaX; view.y -= e.deltaY; applyView(); }
  }, { passive: false });

  function connect(from, opt, to) {
    change(function () {
      var n = node(from);
      if (opt === 'next') { n.next = to; } else { n.options[+opt].target = to; }
    }, { keepPanel: false });
  }

  // quick "add a connected step" menu when an arrow is dropped on empty canvas
  function quickAdd(cx, cy, from, opt) {
    closeQuick();
    var w = toWorld(cx, cy);
    var menu = h('div', { class: 'fb-quick', id: 'fbQuick', style: 'left:' + (cx - canvas.getBoundingClientRect().left) + 'px;top:' + (cy - canvas.getBoundingClientRect().top) + 'px' },
      [h('div', { class: 'fb-quick-t', text: 'Add a step here' })].concat(Object.keys(TYPES).filter(function (k) { return k !== 'goto' || OTHER_FLOWS.length; }).map(function (k) {
        return h('button', { type: 'button', onclick: function () { closeQuick(); addNode(k, w.x, w.y - 20, from, opt); } }, [TYPES[k].icon + ' ' + TYPES[k].name]);
      })));
    canvas.appendChild(menu);
  }
  function closeQuick() { var q = document.getElementById('fbQuick'); if (q) { q.remove(); } }
  document.addEventListener('pointerdown', function (e) { if (!e.target.closest('#fbQuick')) { closeQuick(); } }, true);

  function defaults(type) {
    return {
      text: { text: 'Type your message here' },
      buttons: { text: 'Please choose an option:', options: [{ label: 'Option 1', description: '', keywords: '', target: '' }, { label: 'Option 2', description: '', keywords: '', target: '' }] },
      list: { text: 'Please choose from the menu:', buttonLabel: 'View options', options: [{ label: 'Option 1', description: '', keywords: '', target: '' }, { label: 'Option 2', description: '', keywords: '', target: '' }, { label: 'Option 3', description: '', keywords: '', target: '' }] },
      ask: { text: 'What is your name?', saveAs: 'answer' + (graph.nodes.filter(function (n) { return n.type === 'ask'; }).length + 1) },
      image: { text: '', mediaUrl: '' },
      handoff: { text: 'Thanks! A team member will reply here shortly. 🙏' },
      end: { text: 'Thank you! 😊 Type "hi" anytime to start again.' },
      goto: { targetFlowId: OTHER_FLOWS.length ? OTHER_FLOWS[0].id : '' }
    }[type];
  }
  function addNode(type, x, y, from, opt) {
    var id = newId(type);
    change(function () {
      var n = normNode(Object.assign({ id: id, type: type, x: Math.round(x), y: Math.round(y) }, defaults(type)));
      graph.nodes.push(n);
      if (!graph.startNodeId || !node(graph.startNodeId)) { graph.startNodeId = id; }
      if (from) { var f = node(from); if (opt === 'next') { f.next = id; } else { f.options[+opt].target = id; } }
      sel = { kind: 'node', id: id };
    });
  }

  // palette: drag a step type onto the canvas, or click to add in the middle
  document.querySelectorAll('.fb-pal-item').forEach(function (it) {
    it.addEventListener('pointerdown', function (e) {
      e.preventDefault();
      var type = it.dataset.type;
      var ghost = h('div', { class: 'fb-ghost', text: TYPES[type].icon + ' ' + TYPES[type].name });
      document.body.appendChild(ghost);
      var moved = false, sx = e.clientX, sy = e.clientY;
      function mv(ev) {
        if (Math.abs(ev.clientX - sx) + Math.abs(ev.clientY - sy) > 4) { moved = true; }
        ghost.style.left = ev.clientX + 'px'; ghost.style.top = ev.clientY + 'px';
      }
      function up(ev) {
        document.removeEventListener('pointermove', mv); document.removeEventListener('pointerup', up); ghost.remove();
        var r = canvas.getBoundingClientRect();
        if (!moved) {
          var c = toWorld(r.left + r.width / 2, r.top + r.height / 3);
          addNode(type, c.x - 120 + (graph.nodes.length % 5) * 18, c.y + (graph.nodes.length % 5) * 18);
        } else if (ev.clientX > r.left && ev.clientX < r.right && ev.clientY > r.top && ev.clientY < r.bottom) {
          var w = toWorld(ev.clientX, ev.clientY); addNode(type, w.x - 120, w.y - 20);
        }
      }
      mv(e);
      document.addEventListener('pointermove', mv); document.addEventListener('pointerup', up);
    });
  });

  // ------------------------------------------------------------------ side panel
  var panel = document.getElementById('fbPanel');
  function field(label, input, help) {
    return h('label', { class: 'fb-f' }, [h('span', { class: 'fb-f-l', text: label }), input, help ? h('small', { class: 'fb-f-h', text: help }) : null]);
  }
  function counted(input, max) {
    var c = h('small', { class: 'fb-count' });
    function upd() { var l = input.value.length; c.textContent = l + ' / ' + max; c.classList.toggle('over', l > max); }
    input.addEventListener('input', upd); upd();
    return h('div', { class: 'fb-counted' }, [input, c]);
  }
  function textInput(val, max, oninput, attrs) {
    var i = h('input', Object.assign({ type: 'text', value: val || '' }, attrs || {}));
    i.addEventListener('focus', function () { i._snap = snapshot(); });
    i.addEventListener('input', function () { if (i._snap) { undoStack.push(i._snap); redoStack = []; i._snap = null; } oninput(i.value); setDirty(true); refreshNode(); });
    return max ? counted(i, max) : i;
  }
  function textArea(val, max, oninput, rows) {
    var i = h('textarea', { rows: rows || 4 }); i.value = val || '';
    i.addEventListener('focus', function () { i._snap = snapshot(); });
    i.addEventListener('input', function () { if (i._snap) { undoStack.push(i._snap); redoStack = []; i._snap = null; } oninput(i.value); setDirty(true); refreshNode(); });
    return counted(i, max);
  }
  function refreshNode() {
    if (!sel || sel.kind !== 'node') { return; }
    var n = node(sel.id), old = world.querySelector('.fb-node[data-id="' + n.id + '"]');
    if (old) { var el = nodeEl(n); old.replaceWith(el); }
    drawEdges(); refreshChecks(); renderPreview(n);
  }
  function targetSelect(val, onpick, selfId) {
    var s = h('select');
    s.appendChild(h('option', { value: '', text: '— end the chat here —' }));
    graph.nodes.forEach(function (n) {
      if (n.id === selfId) { return; }
      s.appendChild(h('option', { value: n.id, text: TYPES[n.type].icon + ' ' + n.id + '  ' + preview(n.text, 28), selected: n.id === val }));
    });
    s.appendChild(h('option', { value: '__new', text: '＋ New step…' }));
    s.addEventListener('change', function () {
      if (s.value === '__new') {
        var me = node(selfId);
        var id = newId('text');
        change(function () {
          graph.nodes.push(normNode(Object.assign({ id: id, type: 'text', x: (me.x || 0) + 320, y: (me.y || 0) + 40 }, defaults('text'))));
          onpick(id);
          sel = { kind: 'node', id: id };
        });
      } else { change(function () { onpick(s.value); }, { keepPanel: true }); }
    });
    return s;
  }
  function varChips(ta) {
    var vars = ['name'];
    graph.nodes.forEach(function (n) { if (n.type === 'ask') { var v = n.saveAs || n.id; if (vars.indexOf(v) < 0) { vars.push(v); } } });
    if (graph.nodes.some(function (n) { return TYPES[n.type].choice; })) { vars.push('lastChoice'); }
    return h('div', { class: 'fb-chips' }, [h('span', { text: 'Insert:' })].concat(vars.map(function (v) {
      return h('button', { type: 'button', text: '{{' + v + '}}', title: v === 'name' ? "Customer's WhatsApp name" : v === 'lastChoice' ? 'The option they last picked' : 'Answer saved by a question step', onclick: function () {
        var t = ta.querySelector('textarea'); var p = t.selectionStart || t.value.length;
        t.focus(); t.setRangeText('{{' + v + '}}', p, t.selectionEnd || p, 'end'); t.dispatchEvent(new Event('input'));
      } });
    })));
  }

  function renderPanel() {
    panel.innerHTML = '';
    if (sel && sel.kind === 'edge') {
      var n = node(sel.from);
      var label = sel.opt === 'next' ? 'Then' : (n.options[+sel.opt] || {}).label;
      panel.appendChild(h('div', { class: 'fb-p-head' }, [h('h3', { text: 'Connection' })]));
      panel.appendChild(h('p', { class: 'fb-muted', text: 'From ' + n.id + ' (' + label + ').' }));
      panel.appendChild(h('button', { type: 'button', class: 'fb-btn fb-btn-danger', text: 'Remove this connection', onclick: removeSelected }));
      panel.appendChild(h('p', { class: 'fb-muted', text: 'Tip: click an arrow and press Delete to remove it.' }));
      return;
    }
    if (!sel || sel.kind !== 'node' || !node(sel.id)) { renderFlowPanel(); return; }
    var nd = node(sel.id), t = TYPES[nd.type];
    var typeSel = h('select');
    Object.keys(TYPES).forEach(function (k) { if (k === 'goto' && !OTHER_FLOWS.length && nd.type !== 'goto') { return; } typeSel.appendChild(h('option', { value: k, text: TYPES[k].icon + ' ' + TYPES[k].name, selected: k === nd.type })); });
    typeSel.addEventListener('change', function () {
      change(function () {
        var nt = typeSel.value, d = defaults(nt);
        nd.type = nt;
        if (TYPES[nt].choice && !nd.options.length) { nd.options = d.options; }
        if (nt === 'list' && !nd.buttonLabel) { nd.buttonLabel = 'View options'; }
        if (nt === 'ask' && !nd.saveAs) { nd.saveAs = d.saveAs; }
        if (nt === 'goto') { nd.targetFlowId = d.targetFlowId; }
        if (!nd.text && d.text) { nd.text = d.text; }
      });
    });
    panel.appendChild(h('div', { class: 'fb-p-head', style: '--c:' + t.color }, [
      h('span', { class: 'fb-p-ico', text: t.icon }), h('h3', { text: t.name }),
      graph.startNodeId === nd.id ? h('span', { class: 'fb-n-start', text: 'START' }) : null
    ]));
    panel.appendChild(h('p', { class: 'fb-muted', text: t.hint + '.' }));
    panel.appendChild(field('Step type', typeSel));
    var idIn = h('input', { type: 'text', value: nd.id, maxlength: 20 });
    idIn.addEventListener('change', function () { renameNode(nd.id, idIn.value); });
    panel.appendChild(field('Step name', idIn, 'Letters, numbers and _ only. Used to link steps.'));

    if (nd.type === 'goto') {
      var fs = h('select');
      OTHER_FLOWS.forEach(function (f) { fs.appendChild(h('option', { value: f.id, text: f.name, selected: f.id === nd.targetFlowId })); });
      fs.addEventListener('change', function () { change(function () { nd.targetFlowId = fs.value; }, { keepPanel: true }); });
      panel.appendChild(field('Jump to flow', fs, 'The customer continues at the start of that flow.'));
    } else {
      if (nd.type === 'image') {
        panel.appendChild(field('Image link (https://)', textInput(nd.mediaUrl, null, function (v) { nd.mediaUrl = v.trim(); }, { placeholder: 'https://yoursite.com/photo.jpg' }),
          'Paste a public link to a JPG or PNG image.'));
      }
      if (t.choice) {
        panel.appendChild(field('Header (optional)', textInput(nd.header, LIM.header, function (v) { nd.header = v; })));
      }
      var ta = textArea(nd.text, t.choice || nd.type === 'image' ? LIM.body : LIM.text, function (v) { nd.text = v; }, nd.type === 'image' ? 2 : 4);
      panel.appendChild(field(nd.type === 'image' ? 'Caption (optional)' : nd.type === 'ask' ? 'Question' : 'Message', ta));
      panel.appendChild(varChips(ta));
      if (t.choice) {
        panel.appendChild(field('Footer (optional)', textInput(nd.footer, LIM.footer, function (v) { nd.footer = v; })));
        if (nd.type === 'list' || nd.options.length > LIM.buttons) {
          panel.appendChild(field('Menu button text', textInput(nd.buttonLabel, LIM.listBtn, function (v) { nd.buttonLabel = v; }, { placeholder: 'View options' })));
        }
        panel.appendChild(optionsEditor(nd));
      }
      if (nd.type === 'ask') {
        var sv = textInput(nd.saveAs, null, function (v) { nd.saveAs = v.replace(/[^A-Za-z0-9_]/g, ''); }, { placeholder: 'e.g. customerName' });
        panel.appendChild(field('Save the answer as', sv, 'Use it later in messages as {{' + (nd.saveAs || 'name') + '}}.'));
        var chk = h('select'), cur = ANSWER_CHECKS.filter(function (c) { return c.re === nd.validation; })[0];
        ANSWER_CHECKS.forEach(function (c) { chk.appendChild(h('option', { value: c.re, text: c.label, selected: cur ? c === cur : false })); });
        if (!cur) { chk.appendChild(h('option', { value: nd.validation, text: 'Custom pattern', selected: true })); }
        chk.addEventListener('change', function () { change(function () { nd.validation = chk.value; }, { keepPanel: true }); });
        panel.appendChild(field('Accept', chk, 'If the answer does not match, the bot asks again.'));
      }
      if (t.next) {
        panel.appendChild(field(nd.type === 'ask' ? 'After the answer, go to' : 'Then go to', targetSelect(nd.next, function (v) { nd.next = v; }, nd.id)));
      }
      if (nd.type === 'handoff') { panel.appendChild(h('div', { class: 'fb-info', text: 'The bot pauses for this customer and the chat shows in your Inbox under “Needs agent”. Click “Hand back to bot” there when done.' })); }
    }
    panel.appendChild(h('div', { class: 'fb-p-actions' }, [
      graph.startNodeId !== nd.id ? h('button', { type: 'button', class: 'fb-btn', text: '⚑ Set as start', onclick: function () { change(function () { graph.startNodeId = nd.id; }); } }) : null,
      h('button', { type: 'button', class: 'fb-btn', text: '⧉ Duplicate', onclick: function () { duplicate(nd.id); } }),
      h('button', { type: 'button', class: 'fb-btn fb-btn-danger', text: '🗑 Delete', onclick: removeSelected })
    ]));
    if (nd.type !== 'goto') {
      panel.appendChild(h('div', { class: 'fb-prev-t', text: 'WhatsApp preview' }));
      panel.appendChild(h('div', { class: 'fb-prev', id: 'fbPrev' }));
      renderPreview(nd);
    }
  }

  function optionsEditor(nd) {
    var isBtn = nd.type === 'buttons' && nd.options.length <= LIM.buttons;
    var max = isBtn ? LIM.btnLabel : LIM.rowLabel;
    var wrap = h('div', { class: 'fb-opts' }, [h('div', { class: 'fb-f-l', text: (nd.type === 'buttons' ? 'Buttons' : 'Menu options') + ' (' + nd.options.length + ')' })]);
    nd.options.forEach(function (o, i) {
      var box = h('div', { class: 'fb-opt' });
      box.appendChild(h('div', { class: 'fb-opt-row' }, [
        h('span', { class: 'fb-opt-n', text: String(i + 1) }),
        textInput(o.label, max, function (v) { o.label = v; }, { placeholder: 'Option text' }),
        h('button', { type: 'button', class: 'fb-icon', title: 'Move up', text: '↑', disabled: i === 0, onclick: function () { change(function () { nd.options.splice(i - 1, 0, nd.options.splice(i, 1)[0]); }); } }),
        h('button', { type: 'button', class: 'fb-icon', title: 'Remove', text: '✕', onclick: function () { change(function () { nd.options.splice(i, 1); }); } })
      ]));
      if (nd.type === 'list' || !isBtn) {
        box.appendChild(textInput(o.description, LIM.rowDesc, function (v) { o.description = v; }, { placeholder: 'Description (optional)' }));
      }
      box.appendChild(h('div', { class: 'fb-opt-go' }, [h('span', { text: '→' }), targetSelect(o.target, function (v) { o.target = v; }, nd.id)]));
      var kw = textInput(o.keywords, null, function (v) { o.keywords = v; }, { placeholder: 'Also match typed words (comma separated)' });
      kw.classList.add('fb-kw-in');
      box.appendChild(kw);
      wrap.appendChild(box);
    });
    wrap.appendChild(h('button', { type: 'button', class: 'fb-btn fb-btn-add', text: '＋ Add option', disabled: nd.options.length >= LIM.rows, onclick: function () {
      change(function () { nd.options.push({ label: 'Option ' + (nd.options.length + 1), description: '', keywords: '', target: '' }); });
    } }));
    wrap.appendChild(h('small', { class: 'fb-f-h', text: nd.type === 'buttons' ? 'Up to 3 show as buttons. 4 to 10 are shown as a list menu automatically.' : 'Up to 10 options. Customers can also reply with the number.' }));
    return wrap;
  }

  function fillVars(s) {
    var frag = document.createDocumentFragment();
    (s || '').split(/(\{\{[A-Za-z0-9_]+\}\})/).forEach(function (part) {
      if (/^\{\{.*\}\}$/.test(part)) { frag.appendChild(h('span', { class: 'fb-var', text: part.slice(2, -2) })); }
      else { frag.appendChild(document.createTextNode(part)); }
    });
    return frag;
  }
  function bubble(n, forTest, vars) {
    var txt = function (s) { return forTest ? document.createTextNode(renderVars(s, vars)) : fillVars(s); };
    var b = h('div', { class: 'wa-b' });
    if (n.type === 'image') { b.appendChild(n.mediaUrl ? h('img', { src: n.mediaUrl, alt: '', class: 'wa-img', onerror: function () { this.replaceWith(h('div', { class: 'wa-img ph', text: '🖼️ image' })); } }) : h('div', { class: 'wa-img ph', text: '🖼️ image' })); }
    if (n.header) { b.appendChild(h('div', { class: 'wa-h' }, [txt(n.header)])); }
    if (n.text) { var t = h('div', { class: 'wa-t' }); t.appendChild(txt(n.text)); b.appendChild(t); }
    if (n.footer && TYPES[n.type].choice) { b.appendChild(h('div', { class: 'wa-f' }, [txt(n.footer)])); }
    b.appendChild(h('div', { class: 'wa-time', text: '10:24' }));
    return b;
  }
  function renderPreview(n) {
    var p = document.getElementById('fbPrev');
    if (!p) { return; }
    p.innerHTML = '';
    p.appendChild(bubble(n));
    if (TYPES[n.type].choice && n.options.length) {
      if (n.type === 'buttons' && n.options.length <= LIM.buttons) {
        p.appendChild(h('div', { class: 'wa-btns' }, n.options.map(function (o) { return h('div', { class: 'wa-btn', text: o.label || '…' }); })));
      } else {
        p.appendChild(h('div', { class: 'wa-btns' }, [h('div', { class: 'wa-btn', text: '☰ ' + (n.buttonLabel || 'Options') })]));
        p.appendChild(h('div', { class: 'wa-rows' }, n.options.map(function (o) {
          return h('div', { class: 'wa-row' }, [h('b', { text: o.label || '…' }), o.description ? h('small', { text: o.description }) : null]);
        })));
      }
    }
  }

  function renderFlowPanel() {
    panel.appendChild(h('div', { class: 'fb-p-head' }, [h('span', { class: 'fb-p-ico', text: '🧭' }), h('h3', { text: 'Your bot' })]));
    panel.appendChild(h('ol', { class: 'fb-howto' }, [
      h('li', { text: 'Drag a step from the left onto the canvas (or click it).' }),
      h('li', { text: 'Drag from a ● dot to another step to connect them.' }),
      h('li', { text: 'Click a step to edit its text and options here.' }),
      h('li', { text: 'Press ▶ Test to chat with your bot, then Save.' })
    ]));
    panel.appendChild(h('div', { id: 'fbChecks' }));
    refreshChecks();
  }
  function renameNode(oldId, raw) {
    var id = (raw || '').toUpperCase().replace(/[^A-Z0-9_]+/g, '_').slice(0, 20);
    if (!id || id === oldId) { renderPanel(); return; }
    if (node(id)) { toast('A step called ' + id + ' already exists.', 'err'); renderPanel(); return; }
    change(function () {
      graph.nodes.forEach(function (n) {
        if (n.id === oldId) { n.id = id; }
        if (n.next === oldId) { n.next = id; }
        n.options.forEach(function (o) { if (o.target === oldId) { o.target = id; } });
      });
      if (graph.startNodeId === oldId) { graph.startNodeId = id; }
      sel = { kind: 'node', id: id };
    });
  }
  function duplicate(id) {
    var src = node(id), nid = newId(src.type);
    change(function () {
      var c = normNode(JSON.parse(JSON.stringify(src))); c.id = nid; c.x = src.x + 40; c.y = src.y + 40;
      graph.nodes.push(c); sel = { kind: 'node', id: nid };
    });
  }
  function removeSelected() {
    if (!sel) { return; }
    if (sel.kind === 'edge') {
      var s = sel;
      change(function () { var n = node(s.from); if (s.opt === 'next') { n.next = ''; } else { n.options[+s.opt].target = ''; } sel = null; });
      return;
    }
    var id = sel.id;
    change(function () {
      graph.nodes = graph.nodes.filter(function (n) { return n.id !== id; });
      graph.nodes.forEach(function (n) { if (n.next === id) { n.next = ''; } n.options.forEach(function (o) { if (o.target === id) { o.target = ''; } }); });
      if (graph.startNodeId === id) { graph.startNodeId = graph.nodes.length ? graph.nodes[0].id : ''; }
      sel = null;
    });
  }

  // ------------------------------------------------------------------ checks (mirror of server validation)
  function checks() {
    var errs = [], warns = [], ids = {};
    if (!graph.nodes.length) { errs.push({ msg: 'Add at least one step.' }); }
    graph.nodes.forEach(function (n) { ids[n.id] = true; });
    if (graph.nodes.length && !ids[graph.startNodeId]) { errs.push({ msg: 'Choose the start step (select a step, then “Set as start”).' }); }
    graph.nodes.forEach(function (n) {
      var t = TYPES[n.type], e = function (m) { errs.push({ id: n.id, msg: m }); };
      if ((t.choice || n.type === 'text' || n.type === 'ask') && !n.text.trim()) { e('the message is empty'); }
      if (n.text.length > (t.choice || n.type === 'image' ? LIM.body : LIM.text)) { e('the message is too long'); }
      if (n.type === 'image' && n.mediaUrl.indexOf('https://') !== 0) { e('add an image link starting with https://'); }
      if (n.type === 'goto' && !n.targetFlowId) { e('choose the flow to go to'); }
      if (n.header.length > LIM.header) { e('header is too long'); }
      if (n.footer.length > LIM.footer) { e('footer is too long'); }
      if (n.buttonLabel.length > LIM.listBtn) { e('menu button text is too long'); }
      if (t.next && n.next && !ids[n.next]) { e('its arrow points to a missing step'); }
      if (t.choice) {
        if (!n.options.length) { e('add at least one option'); }
        if (n.options.length > LIM.rows) { e('at most 10 options'); }
        var asBtn = n.type === 'buttons' && n.options.length <= LIM.buttons, seen = {};
        n.options.forEach(function (o, i) {
          var l = o.label.trim();
          if (!l) { e('option ' + (i + 1) + ' has no text'); }
          else if (l.length > (asBtn ? LIM.btnLabel : LIM.rowLabel)) { e('option “' + l + '” is too long'); }
          else if (seen[l.toLowerCase()]) { e('two options are called “' + l + '”'); }
          seen[l.toLowerCase()] = true;
          if (o.description.length > LIM.rowDesc) { e('description of “' + l + '” is too long'); }
          if (o.target && !ids[o.target]) { e('option “' + l + '” points to a missing step'); }
        });
      }
    });
    if (!errs.length) {
      var reach = {}, q = [graph.startNodeId];
      while (q.length) { var id = q.shift(); if (!ids[id] || reach[id]) { continue; } reach[id] = true; targets(node(id)).forEach(function (x) { q.push(x); }); }
      graph.nodes.forEach(function (n) { if (!reach[n.id]) { warns.push({ id: n.id, msg: 'is not connected, customers never reach it' }); } });
      graph.nodes.forEach(function (n) {
        if (TYPES[n.type].choice && n.options.some(function (o) { return !o.target; })) { warns.push({ id: n.id, msg: 'has an option that goes nowhere (the chat ends there)' }); }
      });
    }
    return { errs: errs, warns: warns };
  }
  var serverErrors = null;
  function refreshChecks() {
    var c = checks();
    var badge = document.getElementById('fbCheckBadge');
    badge.textContent = c.errs.length ? c.errs.length + ' to fix' : c.warns.length ? c.warns.length + ' tips' : '✓ Ready';
    badge.className = 'fb-badge ' + (c.errs.length ? 'err' : c.warns.length ? 'warn' : 'ok');
    world.querySelectorAll('.fb-node').forEach(function (el) {
      el.classList.toggle('has-err', c.errs.some(function (x) { return x.id === el.dataset.id; }));
    });
    var box = document.getElementById('fbChecks');
    if (!box) { return; }
    box.innerHTML = '';
    var list = (serverErrors || []).map(function (m) { return { msg: m, err: true, server: true }; })
      .concat(c.errs.map(function (x) { return Object.assign({ err: true }, x); })).concat(c.warns);
    box.appendChild(h('div', { class: 'fb-f-l', text: 'Checks' }));
    if (!list.length) { box.appendChild(h('div', { class: 'fb-ok', text: '✓ Everything looks good. Test it, then save.' })); return; }
    list.forEach(function (x) {
      box.appendChild(h('button', { type: 'button', class: 'fb-issue ' + (x.err ? 'err' : 'warn'), onclick: function () { if (x.id) { focusNode(x.id); } } },
        [h('span', { text: x.err ? '⛔' : '💡' }), h('span', { text: (x.id && !x.server ? x.id + ': ' : '') + x.msg })]));
    });
  }
  function focusNode(id) {
    var n = node(id); if (!n) { return; }
    sel = { kind: 'node', id: id };
    var r = canvas.getBoundingClientRect();
    view.x = r.width / 2 - (n.x + 130) * view.z; view.y = r.height / 3 - n.y * view.z; applyView();
    markSel(); drawEdges(); renderPanel();
  }

  // ------------------------------------------------------------------ toolbar
  var nameIn = document.getElementById('fbName'), kwIn = document.getElementById('fbKeywords'),
    liveIn = document.getElementById('fbLive'), defIn = document.getElementById('fbDefault');
  function syncMetaInputs() { nameIn.value = meta.flowName; kwIn.value = meta.triggerKeywords; liveIn.checked = meta.isActive; defIn.checked = meta.isDefault; }
  nameIn.addEventListener('input', function () { meta.flowName = nameIn.value; setDirty(true); });
  kwIn.addEventListener('input', function () { meta.triggerKeywords = kwIn.value; setDirty(true); });
  liveIn.addEventListener('change', function () { meta.isActive = liveIn.checked; setDirty(true); });
  defIn.addEventListener('change', function () { meta.isDefault = defIn.checked; setDirty(true); });
  syncMetaInputs();

  function save() {
    var c = checks();
    if (c.errs.length) { sel = null; renderPanel(); toast('Please fix ' + c.errs.length + ' problem' + (c.errs.length > 1 ? 's' : '') + ' first (see Checks).', 'err'); return; }
    var payload = Object.assign({}, meta, { startNodeId: graph.startNodeId, nodes: graph.nodes });
    var b = document.getElementById('fbSave'); b.disabled = true; b.textContent = 'Saving…';
    post(URLS.save, { flowId: FLOW_ID, graph: JSON.stringify(payload) }).then(function (d) {
      b.disabled = false;
      if (d.ok) { serverErrors = null; setDirty(false); toast('✓ ' + (d.message || 'Saved')); refreshChecks(); }
      else if (d.errors) { serverErrors = d.errors; setDirty(true); sel = null; renderPanel(); toast('Not saved: ' + d.errors[0], 'err'); }
      else { setDirty(true); toast(d.error || 'Could not save.', 'err'); }
    }).catch(function (e) { b.disabled = false; setDirty(true); toast(e.message, 'err'); });
  }
  document.getElementById('fbSave').addEventListener('click', save);
  document.getElementById('fbUndo').addEventListener('click', undo);
  document.getElementById('fbRedo').addEventListener('click', redo);
  document.getElementById('fbArrange').addEventListener('click', function () { change(function () { }, { noRender: true }); autoArrange(false); });
  document.getElementById('fbZoomIn').addEventListener('click', function () { zoomAt(view.z * 1.2); });
  document.getElementById('fbZoomOut').addEventListener('click', function () { zoomAt(view.z / 1.2); });
  document.getElementById('fbFit').addEventListener('click', fit);
  document.getElementById('fbAiKeyGo').addEventListener('click', function (e) { if (dirty && !confirm('You have unsaved changes. Leave without saving?')) { e.preventDefault(); } else { dirty = false; } });
  document.getElementById('fbBack').addEventListener('click', function (e) { if (dirty && !confirm('You have unsaved changes. Leave without saving?')) { e.preventDefault(); } });
  window.addEventListener('beforeunload', function (e) { if (dirty) { e.preventDefault(); e.returnValue = ''; } });
  document.addEventListener('keydown', function (e) {
    var typing = /INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName);
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault(); save(); return; }
    if (typing) { return; }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); if (e.shiftKey) { redo(); } else { undo(); } }
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y') { e.preventDefault(); redo(); }
    else if ((e.key === 'Delete' || e.key === 'Backspace') && sel) { e.preventDefault(); removeSelected(); }
    else if (e.key === 'Escape') { closeModal(); closeQuick(); if (sel) { sel = null; markSel(); drawEdges(); renderPanel(); } }
  });

  // ------------------------------------------------------------------ modals: AI and templates
  function openModal(id) { document.getElementById(id).hidden = false; }
  function closeModal() { document.querySelectorAll('.fb-modal').forEach(function (m) { m.hidden = true; }); }
  document.querySelectorAll('[data-close]').forEach(function (b) { b.addEventListener('click', closeModal); });
  document.querySelectorAll('.fb-modal').forEach(function (m) { m.addEventListener('pointerdown', function (e) { if (e.target === m) { closeModal(); } }); });

  document.getElementById('fbAiBtn').addEventListener('click', function () {
    if (!AI_ON) { openModal('fbAiKeyModal'); return; }
    var hasSteps = graph.nodes.length > 0;
    document.getElementById('fbAiModeRow').hidden = !hasSteps;
    document.querySelector('input[name=fbAiMode][value=' + (hasSteps ? 'edit' : 'new') + ']').checked = true;
    updateAiHint(); openModal('fbAiModal'); document.getElementById('fbAiText').focus();
  });
  function updateAiHint() {
    var edit = document.querySelector('input[name=fbAiMode]:checked').value === 'edit';
    document.getElementById('fbAiText').placeholder = edit
      ? 'Example: After “Book a store visit”, also ask for their phone number, and add an option “Gold rate today” that hands over to staff.'
      : 'Example: We are a jewellery shop. Show gold, diamond and silver collections, let customers book a store visit and talk to our staff.';
  }
  document.querySelectorAll('input[name=fbAiMode]').forEach(function (r) { r.addEventListener('change', updateAiHint); });
  document.querySelectorAll('[data-ai-idea]').forEach(function (b) { b.addEventListener('click', function () { var t = document.getElementById('fbAiText'); t.value = b.dataset.aiIdea; t.focus(); }); });
  document.getElementById('fbAiGo').addEventListener('click', function () {
    var text = document.getElementById('fbAiText').value.trim();
    if (text.length < 5) { toast('Describe what you want in a few words.', 'err'); return; }
    var mode = document.querySelector('input[name=fbAiMode]:checked').value;
    closeModal(); busy(true, mode === 'edit' ? 'Updating your flow with AI… (about 20-40 seconds)' : 'Designing your bot with AI… (about 20-40 seconds)');
    var cur = { startNodeId: graph.startNodeId, nodes: graph.nodes };
    post(URLS.ai, { flowId: FLOW_ID, description: text, mode: mode, graph: JSON.stringify(cur) }).then(function (d) {
      busy(false);
      if (d.error) { toast(d.error, 'err'); return; }
      loadGraph(d.graph, 'AI draft ready. Review it, press ▶ Test, then Save.');
    }).catch(function (e) { busy(false); toast(e.message, 'err'); });
  });

  var tplBox = document.getElementById('fbTplList');
  TEMPLATES.forEach(function (t) {
    tplBox.appendChild(h('button', { type: 'button', class: 'fb-tpl', onclick: function () {
      if (graph.nodes.length && !confirm('Replace the current steps with the “' + t.name + '” template? (You can undo.)')) { return; }
      closeModal(); loadGraph(t.graph, 'Template loaded. Edit the texts for your business, then Save.');
    } }, [h('span', { class: 'fb-tpl-ico', text: t.icon }), h('b', { text: t.name }), h('small', { text: t.description }), h('em', { text: t.graph.nodes.length + ' steps' })]));
  });
  document.getElementById('fbTplBtn').addEventListener('click', function () { openModal('fbTplModal'); });

  function loadGraph(g, msg) {
    change(function () {
      graph = { startNodeId: g.startNodeId, nodes: g.nodes.map(normNode) };
      if (!meta.triggerKeywords && g.triggerKeywords) { meta.triggerKeywords = g.triggerKeywords; }
      sel = null; serverErrors = null;
    }, { noRender: true });
    syncMetaInputs(); render(); renderPanel(); setTimeout(fit, 30);
    toast(msg);
  }

  // ------------------------------------------------------------------ test chat (runs the flow like the real bot)
  var chat = document.getElementById('fbChat'), chatLog = document.getElementById('fbChatLog'), chatIn = document.getElementById('fbChatIn');
  var sim = null;
  function renderVars(s, vars) { return (s || '').replace(/\{\{([A-Za-z0-9_]+)\}\}/g, function (m, k) { return vars[k] !== undefined ? vars[k] : ''; }); }
  function simStart() {
    chatLog.innerHTML = '';
    sim = { vars: { name: 'Test customer' }, wait: null, paused: false };
    addSys('Test chat — nothing is sent to WhatsApp. Type a message to start (e.g. “hi”).');
  }
  function addSys(t) { chatLog.appendChild(h('div', { class: 'wa-sys', text: t })); chatLog.scrollTop = chatLog.scrollHeight; }
  function addMe(t) { chatLog.appendChild(h('div', { class: 'wa-me' }, [h('div', { class: 'wa-b me', text: t })])); chatLog.scrollTop = chatLog.scrollHeight; }
  function addBot(n) {
    var row = h('div', { class: 'wa-bot' }, [bubble(n, true, sim.vars)]);
    if (TYPES[n.type].choice && n.options.length) {
      var asBtn = n.type === 'buttons' && n.options.length <= LIM.buttons;
      if (asBtn) {
        row.appendChild(h('div', { class: 'wa-btns' }, n.options.map(function (o, i) {
          return h('button', { type: 'button', class: 'wa-btn', text: o.label, onclick: function () { userSays(o.label, { node: n.id, opt: i }); } });
        })));
      } else {
        var rows = h('div', { class: 'wa-rows', hidden: true }, n.options.map(function (o, i) {
          return h('button', { type: 'button', class: 'wa-row', onclick: function () { rows.hidden = true; userSays(o.label, { node: n.id, opt: i }); } }, [h('b', { text: o.label }), o.description ? h('small', { text: o.description }) : null]);
        }));
        row.appendChild(h('div', { class: 'wa-btns' }, [h('button', { type: 'button', class: 'wa-btn', text: '☰ ' + (n.buttonLabel || 'Options'), onclick: function () { rows.hidden = !rows.hidden; chatLog.scrollTop = chatLog.scrollHeight; } })]));
        row.appendChild(rows);
      }
    }
    chatLog.appendChild(row); chatLog.scrollTop = chatLog.scrollHeight;
  }
  function highlight(id) {
    testNode = id;
    world.querySelectorAll('.fb-node').forEach(function (e) { e.classList.toggle('testing', e.dataset.id === id); });
  }
  function runFrom(id) {
    for (var step = 0; step < 10 && id; step++) {
      var n = node(id);
      if (!n) { addSys('⚠ The flow points to a step that does not exist. Conversation ends.'); sim.wait = null; return; }
      highlight(n.id);
      if (n.type === 'text' || n.type === 'image') { addBot(n); id = n.next; continue; }
      if (TYPES[n.type].choice || n.type === 'ask') { addBot(n); sim.wait = n.id; return; }
      if (n.type === 'handoff') { if (n.text) { addBot(n); } addSys('🙋 Chat handed to your team. The bot is paused for this customer.'); sim.paused = true; sim.wait = null; return; }
      if (n.type === 'goto') { var f = OTHER_FLOWS.filter(function (x) { return x.id === n.targetFlowId; })[0]; addSys('↪ Continues in the flow “' + (f ? f.name : '?') + '”. (Test that flow in its own builder.)'); sim.wait = null; return; }
      if (n.text) { addBot(n); } addSys('🏁 Conversation ended. Type “hi” to start again.'); sim.wait = null; return;
    }
    sim.wait = null;
  }
  function userSays(text, pick) {
    if (!sim) { simStart(); }
    addMe(text);
    setTimeout(function () {
      if (sim.paused) { addSys('Bot is paused (an agent would reply). Press ↺ to restart the test.'); return; }
      var lower = text.trim().toLowerCase();
      if (sim.wait) {
        var n = node(sim.wait);
        if (n && TYPES[n.type].choice) {
          var opt = null;
          if (pick && pick.node === n.id) { opt = n.options[pick.opt]; }
          else {
            n.options.forEach(function (o, i) {
              if (!opt && (lower === String(i + 1) || lower === o.label.trim().toLowerCase() ||
                  o.keywords.split(',').map(function (k) { return k.trim().toLowerCase(); }).indexOf(lower) >= 0)) { opt = o; }
            });
          }
          if (opt) { sim.vars.lastChoice = opt.label; sim.wait = null; if (opt.target) { runFrom(opt.target); } else { highlight(null); addSys('🏁 That option is not connected, so the conversation ends.'); } return; }
          if (!keywordHit(lower)) { addSys('Bot: “Please choose one of the options.”'); runFrom(n.id); return; }
        } else if (n && n.type === 'ask') {
          if (n.validation) {
            var re = n.validation, flags = '';
            if (re.indexOf('(?i)') === 0) { re = re.slice(4); flags = 'i'; }
            try { if (!new RegExp(re, flags).test(text)) { addSys('Bot: “That doesn\'t look right.” and asks again.'); addBot(n); return; } } catch (err) { /* ignore bad pattern in test */ }
          }
          sim.vars[n.saveAs || n.id] = text; sim.wait = null; if (n.next) { runFrom(n.next); } else { highlight(null); addSys('🏁 No next step after the answer, so the conversation ends.'); } return;
        }
      }
      // new conversation: this flow starts on its keywords (or on any message if it answers any message)
      if (!keywordHit(lower) && !meta.isDefault) { addSys('ℹ In real chats this flow starts only on its keywords (' + (meta.triggerKeywords || 'none set') + ') or when “Answers any message” is on. Starting it anyway for the test.'); }
      runFrom(graph.startNodeId);
    }, 250);
  }
  function keywordHit(lower) { return meta.triggerKeywords.split(',').map(function (k) { return k.trim().toLowerCase(); }).filter(Boolean).indexOf(lower) >= 0; }
  document.getElementById('fbTestBtn').addEventListener('click', function () {
    var open = chat.hidden; chat.hidden = !open; root.classList.toggle('testing', open);
    if (open) { simStart(); chatIn.focus(); } else { highlight(null); }
  });
  document.getElementById('fbChatClose').addEventListener('click', function () { chat.hidden = true; root.classList.remove('testing'); highlight(null); });
  document.getElementById('fbChatRestart').addEventListener('click', function () { highlight(null); simStart(); chatIn.focus(); });
  document.getElementById('fbChatForm').addEventListener('submit', function (e) { e.preventDefault(); var t = chatIn.value.trim(); if (!t) { return; } chatIn.value = ''; userSays(t); });

  // ------------------------------------------------------------------ start
  applyView();
  render();
  renderPanel();
  setDirty(false);
  setTimeout(fit, 30);
  var created = new URLSearchParams(location.search).get('created');
  if (created === 'ai') { toast('✨ Your AI bot is ready and saved. Press ▶ Test to try it.'); }
  else if (created === 'template') { toast('Template loaded and saved. Edit the texts for your business.'); }
  else if (created) { toast('Bot created. Drag steps from the left to build it.'); }
  window.FloChatBuilder = { graph: function () { return { meta: meta, graph: graph }; }, save: save, addNode: addNode, connect: connect };
})();

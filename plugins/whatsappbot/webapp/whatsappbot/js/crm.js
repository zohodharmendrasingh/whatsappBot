/* FloChat: Contacts, Broadcasts, team inbox tools and saved replies. Plain JS, no libraries. */
(function () {
  'use strict';
  function cxInit() {

  // ------------------------------------------------------------ helpers
  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
  function post(url, data) {
    var body = new URLSearchParams();
    Object.keys(data).forEach(function (k) {
      var v = data[k];
      if (Array.isArray(v)) { v.forEach(function (x) { body.append(k, x); }); } else if (v !== undefined && v !== null) { body.append(k, v); }
    });
    return fetch(url, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body.toString() })
      .then(function (r) { return r.json().catch(function () { return { ok: false, error: 'Unexpected response (' + r.status + '). Please reload the page.' }; }); })
      .catch(function () { return { ok: false, error: 'Network problem. Please try again.' }; });
  }
  function get(url) {
    return fetch(url, { credentials: 'same-origin', cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .catch(function () { return { ok: false, error: 'Network problem. Please try again.' }; });
  }
  function toast(msg) {
    var t = $('#cxToast'); if (!t) { return; }
    t.textContent = msg; t.classList.add('on');
    clearTimeout(t._h); t._h = setTimeout(function () { t.classList.remove('on'); }, 3200);
  }
  function showErr(el, msg) { if (!el) { return; } el.textContent = msg || ''; el.hidden = !msg; }
  function openModal(m) { if (m) { m.hidden = false; var f = m.querySelector('input:not([type=hidden]):not([type=checkbox]),textarea,select'); if (f) { setTimeout(function () { f.focus(); }, 30); } } }
  function closeModal(m) { if (m) { m.hidden = true; } }
  function esc(s) { var d = document.createElement('div'); d.textContent = s == null ? '' : String(s); return d.innerHTML; }

  document.addEventListener('click', function (e) {
    var o = e.target.closest('[data-open]');
    if (o) { openModal(document.getElementById(o.getAttribute('data-open'))); return; }
    var c = e.target.closest('[data-close]');
    if (c) { closeModal(c.closest('.cx-modal')); return; }
    if (e.target.classList && e.target.classList.contains('cx-modal')) { closeModal(e.target); }
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') { $$('.cx-modal').forEach(function (m) { if (!m.hidden) { closeModal(m); } }); }
  });

  // ------------------------------------------------------------ CSV
  /** Parse CSV text (comma, semicolon or tab; quoted cells; CRLF). Returns array of rows (arrays of strings). */
  function parseCsv(text) {
    text = text.replace(/^﻿/, '');
    var first = text.split(/\r?\n/, 1)[0] || '';
    var counts = { ',': (first.match(/,/g) || []).length, ';': (first.match(/;/g) || []).length, '\t': (first.match(/\t/g) || []).length };
    var d = Object.keys(counts).sort(function (a, b) { return counts[b] - counts[a]; })[0];
    if (!counts[d]) { d = ','; }
    var rows = [], row = [], cell = '', q = false;
    for (var i = 0; i < text.length; i++) {
      var ch = text[i];
      if (q) {
        if (ch === '"') { if (text[i + 1] === '"') { cell += '"'; i++; } else { q = false; } } else { cell += ch; }
      } else if (ch === '"') { q = true; }
      else if (ch === d) { row.push(cell); cell = ''; }
      else if (ch === '\n' || ch === '\r') {
        if (ch === '\r' && text[i + 1] === '\n') { i++; }
        row.push(cell); cell = '';
        if (row.some(function (x) { return x.trim() !== ''; })) { rows.push(row); }
        row = [];
      } else { cell += ch; }
    }
    row.push(cell);
    if (row.some(function (x) { return x.trim() !== ''; })) { rows.push(row); }
    return rows;
  }
  function guessColumn(header, sample) {
    var h = (header || '').toLowerCase().replace(/[^a-z]/g, '');
    if (/^(phone|mobile|whatsapp|number|phonenumber|mobilenumber|contactnumber|cell|wa|waid|msisdn)/.test(h) || /(phone|mobile|whatsapp)/.test(h)) { return 'phone'; }
    if (/^(name|fullname|customername|contactname|firstname)$/.test(h)) { return 'name'; }
    if (/mail/.test(h)) { return 'email'; }
    if (/^(tag|tags|label|labels|group|groups|segment)$/.test(h)) { return 'tags'; }
    if (!h && /^\+?[0-9 ()-]{8,}$/.test(sample || '')) { return 'phone'; }
    return null;
  }

  // ============================================================ Contacts page
  var root = $('#cxContacts');
  if (root) { initContacts(root); }

  function initContacts(root) {
    var U = root.dataset;
    // ---- selection + bulk actions
    var bulk = $('#cxBulk');
    function selected() { return $$('.cx-row-cb:checked').map(function (c) { return c.value; }); }
    function refreshBulk() {
      var n = selected().length;
      if (bulk) { bulk.hidden = n === 0; $('#cxSelCount').textContent = n; }
    }
    var all = $('#cxAll');
    if (all) { all.addEventListener('change', function () { $$('.cx-row-cb').forEach(function (c) { c.checked = all.checked; }); refreshBulk(); }); }
    $$('.cx-row-cb').forEach(function (c) { c.addEventListener('change', refreshBulk); });
    $$('[data-bulk]').forEach(function (b) {
      b.addEventListener('click', function () {
        var action = b.getAttribute('data-bulk'), ids = selected(), tag = ($('#cxBulkTag') || {}).value || '';
        if (!ids.length) { return; }
        if ((action === 'tag' || action === 'untag') && !tag.trim()) { toast('Type a tag name first.'); $('#cxBulkTag').focus(); return; }
        if (action === 'delete' && !window.confirm('Delete ' + ids.length + ' contact(s) and all their chat history? This cannot be undone.')) { return; }
        b.disabled = true;
        post(U.bulkUrl, { action: action, contactIds: ids.join(','), tag: tag }).then(function (d) {
          b.disabled = false;
          if (!d.ok) { toast(d.error || 'Could not update.'); return; }
          toast(d.count + ' contact(s) updated'); setTimeout(function () { window.location.reload(); }, 500);
        });
      });
    });

    // ---- add / edit
    var em = $('#cxEditModal'), form = $('#cxEditForm'), err = $('#cxEditErr');
    function openEdit(data) {
      form.reset(); showErr(err);
      var isNew = !data;
      $('#cxEditTitle').textContent = isNew ? 'Add contact' : 'Edit contact';
      $$('.cx-new-only', form).forEach(function (x) { x.hidden = !isNew; });
      $$('.cx-edit-only', form).forEach(function (x) { x.hidden = isNew; });
      form.contactId.value = isNew ? '' : data.contactId;
      form.optIn.checked = isNew ? true : data.optInStatus !== 'N';
      var info = $('#cxConsentInfo');
      if (!isNew) {
        $('.cx-phone-ro', form).innerHTML = '<span class="cx-muted">WhatsApp</span> <b>+' + esc(data.waId) + '</b>';
        form.profileName.value = data.profileName || '';
        form.email.value = data.email || '';
        form.tags.value = (data.tags || []).join(', ');
        Object.keys(data.fields || {}).forEach(function (k) { if (form['f_' + k]) { form['f_' + k].value = data.fields[k]; } });
        var src = { CHAT: 'messaged you on WhatsApp', KEYWORD: 'replied START', IMPORT: 'imported from CSV', MANUAL: 'set by your team', BROADCAST: 'added to a broadcast', API: 'added through the API' }[data.optSource] || '';
        info.textContent = data.optInStatus === 'N'
          ? 'Opted out' + (data.optOutDate ? ' on ' + data.optOutDate : '') + (src ? ' (' + src + ')' : '') + '. Broadcasts skip this person.'
          : 'Opted in' + (data.optInDate ? ' on ' + data.optInDate : '') + (src ? ' (' + src + ')' : '') + '. They can reply STOP at any time.';
      } else {
        info.textContent = 'Only add people who agreed to hear from you on WhatsApp. They can reply STOP at any time.';
      }
      openModal(em);
    }
    var add = $('#cxAdd');
    if (add) { add.addEventListener('click', function () { openEdit(null); }); }
    $$('[data-edit]').forEach(function (b) {
      b.addEventListener('click', function () {
        get(U.dataUrl + '?contactId=' + encodeURIComponent(b.getAttribute('data-edit'))).then(function (d) {
          if (d.error) { toast(d.error); return; }
          openEdit(d);
        });
      });
    });
    if (form) {
      form.addEventListener('submit', function (e) {
        e.preventDefault(); showErr(err);
        var data = {};
        $$('input,select,textarea', form).forEach(function (i) { if (i.name && i.type !== 'checkbox') { data[i.name] = i.value; } });
        data.optInStatus = form.optIn.checked ? 'Y' : 'N';
        var btn = form.querySelector('[type=submit]'); btn.disabled = true;
        post(U.saveUrl, data).then(function (d) {
          btn.disabled = false;
          if (!d.ok) { showErr(err, d.error); return; }
          closeModal(em); toast('Contact saved'); setTimeout(function () { window.location.reload(); }, 400);
        });
      });
    }

    // ---- custom fields
    var ff = $('#cxFieldForm');
    if (ff) {
      ff.addEventListener('submit', function (e) {
        e.preventDefault(); showErr($('#cxFieldErr'));
        post(U.fieldAddUrl, { label: ff.label.value }).then(function (d) {
          if (!d.ok) { showErr($('#cxFieldErr'), d.error); return; }
          window.location.reload();
        });
      });
    }
    $$('[data-remove-field]').forEach(function (b) {
      b.addEventListener('click', function () {
        if (!window.confirm('Remove this field? Existing values are hidden and no longer exported.')) { return; }
        post(U.fieldRemoveUrl, { fieldKey: b.getAttribute('data-remove-field') }).then(function (d) {
          if (!d.ok) { showErr($('#cxFieldErr'), d.error); return; }
          window.location.reload();
        });
      });
    });

    initImport(U);
  }

  // ------------------------------------------------------------ CSV import wizard
  function initImport(U) {
    var file = $('#cxFile');
    if (!file) { return; }
    var rows = [], header = [], hasHeader = true, fileName = '';
    var s1 = $('#cxImp1'), s2 = $('#cxImp2'), s3 = $('#cxImp3');
    function step(n) { s1.hidden = n !== 1; s2.hidden = n !== 2; s3.hidden = n !== 3; $('.cx-imp-close').hidden = n !== 1; }
    function load(f) {
      if (!f) { return; }
      if (f.size > 15 * 1024 * 1024) { toast('That file is too big (max 15 MB).'); return; }
      fileName = f.name;
      var r = new FileReader();
      r.onload = function () {
        var all = parseCsv(String(r.result || ''));
        if (all.length < 1) { toast('The file is empty.'); return; }
        // header row = first row with no phone-looking cells
        hasHeader = !all[0].some(function (c) { return /^\+?[0-9][0-9 ()-]{7,}$/.test(c.trim()); });
        header = hasHeader ? all[0] : all[0].map(function (_, i) { return 'Column ' + (i + 1); });
        rows = hasHeader ? all.slice(1) : all;
        buildMap(); step(2);
      };
      r.readAsText(f);
    }
    file.addEventListener('change', function () { load(file.files[0]); });
    var drop = $('#cxDrop');
    ['dragover', 'dragenter'].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.add('on'); }); });
    ['dragleave', 'drop'].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.remove('on'); }); });
    drop.addEventListener('drop', function (e) { load(e.dataTransfer.files[0]); });

    function buildMap() {
      $('#cxImpFile').textContent = fileName;
      $('#cxImpRows').textContent = rows.length.toLocaleString();
      var opts = $('#cxMapOptions').innerHTML;
      var t = $('#cxMap'), used = {};
      var html = '<thead><tr><th>Column in your file</th><th>Example</th><th>Save as</th></tr></thead><tbody>';
      header.forEach(function (h, i) {
        var sample = '';
        for (var r = 0; r < Math.min(rows.length, 20) && !sample; r++) { sample = (rows[r][i] || '').trim(); }
        html += '<tr><td><b>' + esc(h || ('Column ' + (i + 1))) + '</b></td><td class="cx-muted">' + esc(sample.slice(0, 40)) + '</td><td><select data-col="' + i + '">' + opts
          + (h && h.trim() ? '<option value="new:' + esc(h.trim()) + '">+ New field "' + esc(h.trim().slice(0, 30)) + '"</option>' : '') + '</select></td></tr>';
      });
      t.innerHTML = html + '</tbody>';
      $$('select', t).forEach(function (sel, i) {
        var g = guessColumn(header[i], (rows[0] || [])[i]);
        if (!g) {
          // matches an existing custom field by label?
          var lbl = (header[i] || '').trim().toLowerCase();
          $$('option', sel).forEach(function (o) { if (!g && o.value.indexOf('f:') === 0 && o.textContent.trim().toLowerCase() === lbl) { g = o.value; } });
        }
        if (g && !used[g]) { sel.value = g; used[g] = true; }
      });
    }
    $('#cxImpBack').addEventListener('click', function () { file.value = ''; step(1); });

    $('#cxImpGo').addEventListener('click', function () {
      var err = $('#cxImpErr'); showErr(err);
      var map = {}, newFields = [], phoneCol = -1;
      $$('#cxMap select').forEach(function (sel) {
        var v = sel.value, col = +sel.getAttribute('data-col');
        if (!v) { return; }
        if (v === 'phone') { phoneCol = col; }
        if (v.indexOf('new:') === 0) { newFields.push({ col: col, label: v.slice(4) }); } else { map[col] = v; }
      });
      if (phoneCol < 0) { showErr(err, 'Choose which column has the WhatsApp numbers.'); return; }
      if (!$('#cxImpConsent').checked) { showErr(err, 'Please confirm these people agreed to receive WhatsApp messages from you.'); return; }
      step(3);
      // 1. create new custom fields, one by one
      var chain = Promise.resolve();
      newFields.forEach(function (nf) {
        chain = chain.then(function () {
          return post(U.fieldAddUrl, { label: nf.label }).then(function (d) {
            if (d.ok) { map[nf.col] = 'f:' + d.key; } else { throw new Error('Field "' + nf.label + '": ' + d.error); }
          });
        });
      });
      chain.then(function () { return send(map); }).catch(function (e) {
        $('#cxImpStatus').textContent = e.message; $('#cxImpDone').hidden = false;
      });
    });

    function send(map) {
      var CHUNK = 500, total = rows.length, done = 0;
      var res = { created: 0, updated: 0, skipped: 0, invalid: [] };
      var payloads = [];
      for (var s = 0; s < total; s += CHUNK) {
        payloads.push({ first: s + (hasHeader ? 2 : 1), rows: rows.slice(s, s + CHUNK).map(function (r) {
          var o = { fields: {} };
          Object.keys(map).forEach(function (col) {
            var v = (r[col] || '').trim(), k = map[col];
            if (k.indexOf('f:') === 0) { if (v) { o.fields[k.slice(2)] = v; } }
            else if (k === 'tags') { o.tags = v.replace(/;/g, ','); }
            else { o[k] = v; }
          });
          return o;
        }) });
      }
      var chain = Promise.resolve();
      payloads.forEach(function (p) {
        chain = chain.then(function () {
          return post(U.importUrl, { channelId: $('#cxImpChannel').value, countryCode: $('#cxImpCc').value, tags: $('#cxImpTags').value,
            update: $('#cxImpUpdate').checked ? 'Y' : 'N', consent: 'Y', firstLine: p.first, rows: JSON.stringify(p.rows) }).then(function (d) {
            if (!d.ok) { throw new Error(d.error || 'Import failed.'); }
            res.created += d.created; res.updated += d.updated; res.skipped += d.skipped;
            res.invalid = res.invalid.concat(d.invalid || []);
            done += p.rows.length;
            $('#cxImpBar').style.width = Math.round(100 * done / total) + '%';
            $('#cxImpStatus').textContent = 'Imported ' + done.toLocaleString() + ' of ' + total.toLocaleString() + ' rows…';
          });
        });
      });
      return chain.then(function () {
        $('#cxImpStatus').textContent = 'Import finished.';
        var h = '<ul class="cx-result"><li><b>' + res.created + '</b> new contacts</li><li><b>' + res.updated + '</b> updated</li>'
          + (res.skipped ? '<li><b>' + res.skipped + '</b> already existed (not changed)</li>' : '')
          + (res.invalid.length ? '<li class="cx-danger"><b>' + res.invalid.length + '</b> rows skipped: not a valid number</li>' : '') + '</ul>';
        if (res.invalid.length) {
          h += '<p class="cx-muted">' + res.invalid.slice(0, 8).map(function (x) { return 'line ' + x.line + ': "' + esc(x.phone) + '"'; }).join(', ') + (res.invalid.length > 8 ? '…' : '') + '</p>';
        }
        $('#cxImpResult').innerHTML = h;
        $('#cxImpDone').hidden = false;
      });
    }
    $('#cxImpDone').addEventListener('click', function () { window.location.reload(); });
  }

  // ============================================================ Broadcast: new campaign
  var bc = $('#bcForm');
  if (bc) { initBroadcast(bc); }

  function initBroadcast(form) {
    var U = form.dataset;
    var tplSel = form.templateId, chSel = form.channelId;
    var tpls = $$('#bcTemplates option').map(function (o) {
      return { id: o.value, ch: o.getAttribute('data-ch'), name: o.textContent, body: o.getAttribute('data-body') || '', n: +o.getAttribute('data-n'), cat: o.getAttribute('data-cat') };
    });
    function fillTemplates() {
      var ch = chSel.value, cur = tplSel.value;
      tplSel.innerHTML = '<option value="">Choose an approved template</option>' + tpls.filter(function (t) { return t.ch === ch; }).map(function (t) {
        return '<option value="' + esc(t.id) + '">' + esc(t.name) + '</option>';
      }).join('');
      if (tpls.some(function (t) { return t.id === cur && t.ch === ch; })) { tplSel.value = cur; }
      $('#bcNoTpl').hidden = tpls.some(function (t) { return t.ch === ch; });
      renderParams();
    }
    function tpl() { var id = tplSel.value; return tpls.filter(function (t) { return t.id === id; })[0]; }
    function renderParams() {
      var t = tpl(), box = $('#bcParams');
      var old = $$('input', box).map(function (i) { return i.value; });
      box.innerHTML = '';
      $('#bcPreview').hidden = !t;
      if (!t) { return; }
      $('#bcCat').textContent = (t.cat || '').toLowerCase();
      for (var i = 1; i <= t.n; i++) {
        var l = document.createElement('label');
        l.innerHTML = 'Value for <code>{{' + i + '}}</code><input type="text" maxlength="200" placeholder="e.g. {{name}} or 20% off"/>';
        box.appendChild(l);
        var inp = l.querySelector('input'); inp.value = old[i - 1] || (i === 1 && /hi|hello|dear|namaste/i.test(t.body.split('{{1}}')[0].slice(-12)) ? '{{name}}' : '');
        inp.addEventListener('input', preview);
      }
      $('#bcParamHelp').hidden = t.n === 0;
      preview();
    }
    function preview() {
      var t = tpl(); if (!t) { return; }
      var vals = $$('#bcParams input').map(function (i) { return i.value; });
      var sample = { name: 'Ravi', phone: '919812345678', whatsapp: '+919812345678' };
      var txt = t.body.replace(/\{\{\s*(\d+)\s*}}/g, function (_, n) {
        var v = vals[+n - 1] || ('{{' + n + '}}');
        return v.replace(/\{\{\s*([A-Za-z0-9_]+)\s*(?:\|([^{}]*))?}}/g, function (m, k, fb) { return sample[k] || fb || ('[' + k + ']'); });
      });
      $('#bcBubble').textContent = txt;
    }
    chSel.addEventListener('change', function () { fillTemplates(); estimate(); });
    tplSel.addEventListener('change', renderParams);

    // audience
    function audience() { var r = form.querySelector('[name=audienceType]:checked'); return r ? r.value : 'TAGS'; }
    function syncAudience() {
      var a = audience();
      $('#bcTagsBox').hidden = a !== 'TAGS';
      $('#bcNumbersBox').hidden = a !== 'NUMBERS';
      $$('.bc-aud').forEach(function (l) { l.classList.toggle('on', l.querySelector('input').checked); });
      estimate();
    }
    $$('[name=audienceType]', form).forEach(function (r) { r.addEventListener('change', syncAudience); });
    function pickedTags(sel) { return $$(sel + ' input:checked').map(function (c) { return c.value; }).join(','); }
    $$('#bcTagPick input, #bcExcludePick input').forEach(function (c) { c.addEventListener('change', estimate); });
    form.numbers.addEventListener('input', function () { clearTimeout(form._t); form._t = setTimeout(estimate, 600); });

    var estSeq = 0;
    function estimate() {
      var my = ++estSeq, out = $('#bcEstimate');
      var a = audience();
      if (a === 'TAGS' && !pickedTags('#bcTagPick')) { out.innerHTML = '<span class="cx-muted">Pick one or more tags.</span>'; return; }
      if (a === 'NUMBERS' && !form.numbers.value.trim()) { out.innerHTML = '<span class="cx-muted">Paste numbers above.</span>'; return; }
      out.innerHTML = '<span class="cx-muted">Counting…</span>';
      var q = new URLSearchParams({ channelId: chSel.value, audienceType: a, tags: pickedTags('#bcTagPick'), excludeTags: pickedTags('#bcExcludePick') });
      if (a === 'NUMBERS') { q.set('numbers', form.numbers.value.slice(0, 200000)); }
      fetch(U.estimateUrl, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: q.toString() })
        .then(function (r) { return r.json(); }).then(function (d) {
          if (my !== estSeq) { return; }
          if (d.error) { out.textContent = d.error; return; }
          var h = '<b>' + d.eligible.toLocaleString() + '</b> will get this message';
          if (d.optedOut) { h += ' <span class="cx-muted">&middot; ' + d.optedOut + ' opted out or excluded (skipped)</span>'; }
          if (d.invalid && d.invalid.length) { h += '<br><span class="cx-danger">Not valid: ' + d.invalid.map(esc).join(', ') + '</span>'; }
          out.innerHTML = h;
        }).catch(function () {});
    }

    // when
    $$('[name=when]', form).forEach(function (r) { r.addEventListener('change', function () {
      $('#bcLaterBox').hidden = form.querySelector('[name=when]:checked').value !== 'later';
      $('#bcSend').textContent = form.querySelector('[name=when]:checked').value === 'later' ? 'Schedule broadcast' : 'Send broadcast';
    }); });
    var dt = form.sendAtLocal;
    if (dt) {
      var d0 = new Date(Date.now() + 3600e3); d0.setMinutes(0, 0, 0);
      var pad = function (n) { return (n < 10 ? '0' : '') + n; };
      dt.value = d0.getFullYear() + '-' + pad(d0.getMonth() + 1) + '-' + pad(d0.getDate()) + 'T' + pad(d0.getHours()) + ':' + pad(d0.getMinutes());
      $('#bcTz').textContent = (Intl.DateTimeFormat().resolvedOptions().timeZone || '') + ' time';
    }

    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var err = $('#bcErr'); showErr(err);
      var t = tpl();
      if (!t) { showErr(err, 'Choose a template.'); return; }
      var vals = $$('#bcParams input').map(function (i) { return i.value.trim(); });
      if (vals.some(function (v) { return !v; })) { showErr(err, 'Fill in every template value.'); return; }
      if (vals.some(function (v) { return v.replace(/\{\{[^{}]*}}/g, '').indexOf('|') >= 0; })) { showErr(err, 'Values cannot contain the | character (except inside {{ }}).'); return; }
      var later = form.querySelector('[name=when]:checked').value === 'later', sendAt = '';
      if (later) {
        var when = new Date(dt.value);
        if (isNaN(when.getTime()) || when.getTime() < Date.now() + 60e3) { showErr(err, 'Choose a date and time in the future.'); return; }
        sendAt = String(when.getTime());
      }
      var btn = $('#bcSend'); btn.disabled = true;
      post(U.createUrl, { campaignName: form.campaignName.value, channelId: chSel.value, templateId: t.id, bodyParams: vals.join('|'),
        audienceType: audience(), tags: pickedTags('#bcTagPick'), excludeTags: pickedTags('#bcExcludePick'), numbers: form.numbers.value, sendAt: sendAt })
        .then(function (d) {
          btn.disabled = false;
          if (!d.ok) { showErr(err, d.error); return; }
          window.location.href = U.reportUrl + '?campaignId=' + encodeURIComponent(d.campaignId) + '&created=' + (d.scheduled ? 'scheduled' : 'sending');
        });
    });
    fillTemplates(); syncAudience();
  }

  // Broadcast report: refresh while sending
  var rep = $('#bcReport');
  if (rep && rep.dataset.live === 'Y') {
    setTimeout(function () { window.location.reload(); }, 5000);
  }

  // ============================================================ Inbox: saved replies picker + note tab
  var reply = document.querySelector('form[name=ReplyForm] textarea[name=text]');
  var qrData = $('#cxQuickReplies');
  if (reply && qrData) { initQuickReplies(reply, qrData); }

  function initQuickReplies(ta, data) {
    var list = $$('li', data).map(function (li) { return { sc: li.getAttribute('data-sc'), body: li.textContent }; });
    var first = (data.getAttribute('data-first') || '').trim();
    var box = document.createElement('div'); box.className = 'cx-qr'; box.hidden = true;
    ta.parentNode.style.position = 'relative';
    ta.parentNode.appendChild(box);
    var bar = document.createElement('div'); bar.className = 'cx-qr-bar';
    bar.innerHTML = '<button type="button" class="cx-btn cx-btn-sm">⚡ Saved replies</button><span class="cx-muted">or type <b>/</b> in the box</span>';
    ta.parentNode.insertBefore(bar, ta);
    var idx = 0, shown = [];
    function fill(item) {
      var v = ta.value, i = v.lastIndexOf('/');
      var body = item.body.replace(/\{\{\s*name\s*(?:\|([^{}]*))?}}/g, function (m, fb) { return first || fb || 'there'; });
      ta.value = (i >= 0 && /^\/[\w-]*$/.test(v.slice(i)) ? v.slice(0, i) : v) + body;
      box.hidden = true; ta.focus();
    }
    function show(filter) {
      shown = list.filter(function (x) { return !filter || x.sc.indexOf(filter) === 0 || x.body.toLowerCase().indexOf(filter) >= 0; }).slice(0, 8);
      if (!list.length) {
        box.innerHTML = '<div class="cx-qr-empty">No saved replies yet. <a href="' + data.getAttribute('data-manage') + '">Add some in Settings</a>.</div>';
        box.hidden = false; return;
      }
      if (!shown.length) { box.hidden = true; return; }
      idx = Math.min(idx, shown.length - 1);
      box.innerHTML = shown.map(function (x, i) {
        return '<button type="button" data-i="' + i + '" class="' + (i === idx ? 'on' : '') + '"><b>/' + esc(x.sc) + '</b><span>' + esc(x.body.slice(0, 90)) + '</span></button>';
      }).join('') + '<a class="cx-qr-manage" href="' + data.getAttribute('data-manage') + '">Manage saved replies</a>';
      box.hidden = false;
    }
    box.addEventListener('mousedown', function (e) { var b = e.target.closest('button[data-i]'); if (b) { e.preventDefault(); fill(shown[+b.getAttribute('data-i')]); } });
    bar.querySelector('button').addEventListener('click', function () { if (box.hidden) { idx = 0; show(''); } else { box.hidden = true; } });
    ta.addEventListener('input', function () {
      var m = ta.value.match(/(?:^|\s)\/([\w-]*)$/);
      if (m) { idx = 0; show(m[1].toLowerCase()); } else { box.hidden = true; }
    });
    ta.addEventListener('keydown', function (e) {
      if (box.hidden || !shown.length) { return; }
      if (e.key === 'ArrowDown') { e.preventDefault(); idx = (idx + 1) % shown.length; show(ta.value.match(/\/([\w-]*)$/) ? RegExp.$1 : ''); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); idx = (idx - 1 + shown.length) % shown.length; show(ta.value.match(/\/([\w-]*)$/) ? RegExp.$1 : ''); }
      else if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); fill(shown[idx]); }
      else if (e.key === 'Escape') { box.hidden = true; }
    });
    ta.addEventListener('blur', function () { setTimeout(function () { box.hidden = true; }, 150); });
  }

  // ============================================================ AI Agent page
  var ag = $('#agPage');
  if (ag) { initAgent(ag); }

  function initAgent(root) {
    var U = root.dataset;
    function reload() { setTimeout(function () { window.location.reload(); }, 500); }
    // tabs
    $$('.ag-tabs a', root).forEach(function (a) {
      a.addEventListener('click', function (e) {
        e.preventDefault();
        $$('.ag-tabs a', root).forEach(function (x) { x.classList.toggle('on', x === a); });
        $$('.ag-pane', root).forEach(function (p) { p.hidden = p.getAttribute('data-pane') !== a.getAttribute('data-tab'); });
        showErr($('#agAddErr'));
      });
    });
    // on / off
    var en = $('#agEnabled');
    if (en) {
      en.addEventListener('change', function () {
        post(U.saveUrl, { enabled: en.checked ? 'Y' : 'N' }).then(function (d) {
          if (!d.ok) { en.checked = !en.checked; toast(d.error); return; }
          var st = $('#agState'); st.textContent = d.enabled ? 'On' : 'Off';
          st.className = 'cx-pill ' + (d.enabled ? 'cx-pill-green' : 'cx-st-CANCELLED');
          toast(d.enabled ? 'AI agent is on. It now answers your customers.' : 'AI agent is off.');
        });
      });
    }
    // settings
    var sf = $('#agSettings');
    if (sf && root.dataset.owner === 'Y') {
      sf.addEventListener('submit', function (e) {
        e.preventDefault();
        post(U.saveUrl, { instructions: sf.instructions.value, handoffMessage: sf.handoffMessage.value,
          answerInMenus: sf.answerInMenus.checked ? 'Y' : 'N', maxPerDay: sf.maxPerDay.value }).then(function (d) {
          if (!d.ok) { toast(d.error); return; }
          var s = $('#agSaved'); s.hidden = false; setTimeout(function () { s.hidden = true; }, 2500);
        });
      });
    }
    // file upload (raw body, one file at a time)
    var fileIn = $('#agFile'), list = $('#agUploads'), drop = $('#agDrop');
    function upload(files) {
      var chain = Promise.resolve(), any = false;
      Array.prototype.forEach.call(files, function (f) {
        var li = document.createElement('li');
        li.innerHTML = '<span class="ag-spin"></span> <b>' + esc(f.name) + '</b> <small class="cx-muted">reading…</small>';
        list.appendChild(li);
        chain = chain.then(function () {
          if (f.size > 10 * 1024 * 1024) { li.innerHTML = '❌ <b>' + esc(f.name) + '</b> <small class="cx-danger">too big (max 10 MB)</small>'; return; }
          return fetch(U.uploadUrl + '?fileName=' + encodeURIComponent(f.name), { method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/octet-stream' }, body: f })
            .then(function (r) { return r.json(); }).catch(function () { return { ok: false, error: 'Upload failed. Please try again.' }; })
            .then(function (d) {
              if (d.ok) { any = true; li.innerHTML = '✅ <b>' + esc(f.name) + '</b> <small class="cx-muted">' + Math.round(d.chars / 100) / 10 + 'k characters added</small>'; }
              else { li.innerHTML = '❌ <b>' + esc(f.name) + '</b> <small class="cx-danger">' + esc(d.error) + '</small>'; }
            });
        });
      });
      chain.then(function () { if (any) { toast('Knowledge updated'); setTimeout(function () { window.location.reload(); }, 1500); } });
    }
    if (fileIn) {
      fileIn.addEventListener('change', function () { upload(fileIn.files); fileIn.value = ''; });
      ['dragover', 'dragenter'].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.add('on'); }); });
      ['dragleave', 'drop'].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.remove('on'); }); });
      drop.addEventListener('drop', function (e) { upload(e.dataTransfer.files); });
    }
    // website
    var uf = $('#agUrlForm');
    uf.addEventListener('submit', function (e) {
      e.preventDefault(); showErr($('#agAddErr'));
      var b = uf.querySelector('button'); b.disabled = true;
      post(U.urlUrl, { url: uf.url.value, pages: uf.pages.value }).then(function (d) {
        b.disabled = false;
        if (!d.ok) { showErr($('#agAddErr'), d.error); return; }
        toast('Reading the website… this can take a minute.'); reload();
      });
    });
    // notes
    var tf = $('#agTextForm');
    tf.addEventListener('submit', function (e) {
      e.preventDefault(); showErr($('#agAddErr'));
      post(U.textUrl, { sourceId: tf.sourceId.value, title: tf.title.value, text: tf.text.value }).then(function (d) {
        if (!d.ok) { showErr($('#agAddErr'), d.error); return; }
        toast('Note saved'); reload();
      });
    });
    $('#agTextCancel').addEventListener('click', function () { tf.reset(); tf.sourceId.value = ''; this.hidden = true; $('#agTextSave').textContent = 'Save note'; });
    // source actions
    $$('[data-del]', root).forEach(function (b) {
      b.addEventListener('click', function () {
        if (!window.confirm('Remove this from the agent\'s knowledge?')) { return; }
        post(U.deleteUrl, { sourceId: b.getAttribute('data-del') }).then(function (d) { if (!d.ok) { toast(d.error); return; } reload(); });
      });
    });
    $$('[data-refresh]', root).forEach(function (b) {
      b.addEventListener('click', function () {
        post(U.refreshUrl, { sourceId: b.getAttribute('data-refresh') }).then(function (d) { if (!d.ok) { toast(d.error); return; } toast('Reading the website again…'); reload(); });
      });
    });
    $$('[data-view]', root).forEach(function (b) {
      b.addEventListener('click', function () {
        get(U.viewUrl + '?sourceId=' + encodeURIComponent(b.getAttribute('data-view'))).then(function (d) {
          if (!d.ok) { toast(d.error || 'Could not open it.'); return; }
          if (d.type === 'TEXT') {
            $$('.ag-tabs a', root).forEach(function (x) { if (x.getAttribute('data-tab') === 'text') { x.click(); } });
            tf.sourceId.value = d.sourceId; tf.title.value = d.title || ''; tf.text.value = d.text;
            $('#agTextCancel').hidden = false; $('#agTextSave').textContent = 'Update note';
            tf.scrollIntoView({ behavior: 'smooth', block: 'center' }); tf.text.focus();
            return;
          }
          $('#agViewTitle').textContent = d.title || d.url || 'Source';
          $('#agViewMeta').textContent = 'This is the text the AI agent reads' + (d.pages > 1 ? ' (' + d.pages + ' pages)' : '') + '. ' + Math.round(d.chars / 100) / 10 + 'k characters.';
          $('#agViewText').textContent = d.text + (d.truncated ? '\n\n…' : '');
          openModal($('#agViewModal'));
        });
      });
    });
    // poll while web pages are being read
    if ($$('#agSources li[data-status=PROCESSING]').length) {
      var poll = setInterval(function () {
        get(U.listUrl).then(function (d) {
          if (d.ok && !d.sources.some(function (s) { return s.status === 'PROCESSING'; })) { clearInterval(poll); window.location.reload(); }
        });
      }, 3000);
    }
    // test chat
    var chat = $('#agChat'), form = $('#agTestForm'), hist = [];
    function bubble(cls, html) {
      var hint = chat.querySelector('.ag-hint'); if (hint) { hint.remove(); }
      var d = document.createElement('div'); d.className = 'ag-b ' + cls; d.innerHTML = html; chat.appendChild(d); chat.scrollTop = chat.scrollHeight; return d;
    }
    function fmt(t) { return esc(t).replace(/\*([^*\n]+)\*/g, '<b>$1</b>').replace(/_([^_\n]+)_/g, '<i>$1</i>').replace(/\n/g, '<br>'); }
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var q = form.message.value.trim(); if (!q) { return; }
      form.message.value = '';
      bubble('me', fmt(q));
      var wait = bubble('bot ag-typing', '<span></span><span></span><span></span>');
      var btn = form.querySelector('button'); btn.disabled = true;
      post(U.testUrl, { message: q, history: JSON.stringify(hist) }).then(function (d) {
        btn.disabled = false; wait.remove();
        if (d.error) { bubble('sys cx-danger', '⚠️ ' + esc(d.error)); return; }
        var extra = '';
        if (d.action === 'handoff') { extra = '<div class="ag-tag ag-handoff">🙋 Handed to your team' + (d.reason ? ': ' + esc(d.reason) : '') + '</div>'; }
        else if (d.action === 'menu') { extra = '<div class="ag-tag">📋 Opens your main menu flow</div>'; }
        if (d.sources && d.sources.length && d.action === 'answer') { extra += '<div class="ag-tag ag-src">From: ' + d.sources.slice(0, 3).map(esc).join(', ') + '</div>'; }
        if (d.noKnowledge) { extra += '<div class="ag-tag">No knowledge added yet</div>'; }
        bubble('bot', (d.reply ? fmt(d.reply) : '<i class="cx-muted">(no text)</i>') + extra);
        hist.push(['user', q]); if (d.reply) { hist.push(['assistant', d.reply]); }
        if (hist.length > 12) { hist = hist.slice(-12); }
      });
    });
    $('#agTestClear').addEventListener('click', function () { hist = []; chat.innerHTML = ''; });
  }

  // ============================================================ Settings: saved replies
  var qrs = $('#cxQrSettings');
  if (qrs) {
    var U2 = qrs.dataset;
    var qf = $('#cxQrForm');
    qf.addEventListener('submit', function (e) {
      e.preventDefault(); showErr($('#cxQrErr'));
      post(U2.saveUrl, { quickReplyId: qf.quickReplyId.value, shortcut: qf.shortcut.value, body: qf.body.value }).then(function (d) {
        if (!d.ok) { showErr($('#cxQrErr'), d.error); return; }
        window.location.hash = 'replies'; window.location.reload();
      });
    });
    $$('[data-qr-edit]', qrs).forEach(function (b) {
      b.addEventListener('click', function () {
        var li = b.closest('li');
        qf.quickReplyId.value = b.getAttribute('data-qr-edit');
        qf.shortcut.value = li.getAttribute('data-sc');
        qf.body.value = li.querySelector('.cx-qr-body').textContent;
        $('#cxQrSave').textContent = 'Update reply'; $('#cxQrCancel').hidden = false; qf.body.focus();
      });
    });
    $('#cxQrCancel').addEventListener('click', function () { qf.reset(); qf.quickReplyId.value = ''; $('#cxQrSave').textContent = 'Save reply'; $('#cxQrCancel').hidden = true; });
    $$('[data-qr-del]', qrs).forEach(function (b) {
      b.addEventListener('click', function () {
        if (!window.confirm('Delete this saved reply?')) { return; }
        post(U2.deleteUrl, { quickReplyId: b.getAttribute('data-qr-del') }).then(function () { window.location.hash = 'replies'; window.location.reload(); });
      });
    });
  }
  }
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', cxInit); } else { cxInit(); }
})();

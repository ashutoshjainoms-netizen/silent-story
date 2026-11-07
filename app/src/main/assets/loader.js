/*
 * GhostView story loader — runs inside an invisible instagram.com story page,
 * before Instagram's own code.
 *  1) Blocks every request that could mark a story as seen (and all analytics beacons).
 *  2) Reads the story media that Instagram's own code downloads, and hands it to the app.
 */
(function () {
  if (window.__gvLoader) return;
  window.__gvLoader = true;

  function bridge() { try { return window.GVLoader; } catch (e) { return null; } }

  // ---------- 1) never send "seen" ----------
  function bodyStr(b) {
    if (b == null) return '';
    if (typeof b === 'string') return b;
    try {
      if (b instanceof URLSearchParams) return b.toString();
      if (b instanceof FormData) { var p = []; b.forEach(function (v, k) { p.push(k + '=' + (typeof v === 'string' ? v : '')); }); return p.join('&'); }
    } catch (e) {}
    return '';
  }
  function decode(s) { try { return decodeURIComponent(String(s).replace(/\+/g, ' ')); } catch (e) { return String(s); } }
  function blocked(url, body) {
    var s = decode(String(url || '')) + ' ' + decode(body || '');
    return /seen/i.test(s) || /logging_client_events|\/ajax\/bz|falco|\/ajax\/qm/i.test(s);
  }
  var FAKE = '{"status":"ok","data":{}}';

  // ---------- 2) collect story media from Instagram's own responses ----------
  var found = {}, timer = null;
  function bestBy(arr) {
    if (!arr || !arr.length) return null;
    return arr.slice().sort(function (a, b) { return (b.width * b.height) - (a.width * a.height); })[0];
  }
  function add(o, owner) {
    var pk = String(o.pk || o.id || '').split('_')[0];
    if (!pk || found[pk]) return;
    var v = bestBy(o.video_versions);
    var c = o.image_versions2 && bestBy(o.image_versions2.candidates);
    var url = v ? v.url : (c ? c.url : null);
    if (!url) return;
    found[pk] = { pk: pk, url: url, video: !!v, takenAt: o.taken_at || 0, user: owner || '' };
  }
  function walk(o, owner, depth) {
    if (!o || typeof o !== 'object' || depth > 60) return;
    if (Array.isArray(o)) { for (var i = 0; i < o.length; i++) walk(o[i], owner, depth + 1); return; }
    var u = (o.user && o.user.username) ? o.user.username : owner;
    if (o.image_versions2 && o.taken_at) add(o, u);
    for (var k in o) {
      if (k === 'image_versions2' || k === 'video_versions') continue;
      var x = o[k];
      if (x && typeof x === 'object') walk(x, u, depth + 1);
    }
  }
  function scan(text) {
    if (!text || typeof text !== 'string' || text.indexOf('image_versions2') < 0) return;
    var t = text.replace(/^for \(;;\);/, '');
    var parsed = false;
    try { walk(JSON.parse(t), null, 0); parsed = true; } catch (e) {}
    if (!parsed) t.split('\n').forEach(function (line) { try { walk(JSON.parse(line), null, 0); } catch (e) {} });
    report();
  }
  function report() {
    if (timer) clearTimeout(timer);
    timer = setTimeout(function () {
      var list = Object.keys(found).map(function (k) { return found[k]; });
      var b = bridge();
      if (b && list.length) b.onItems(JSON.stringify(list));
    }, 700);
  }

  // fetch
  var of = window.fetch;
  window.fetch = function (input, init) {
    var url = (typeof input === 'string') ? input : (input && input.url) || '';
    if (blocked(url, bodyStr(init && init.body))) {
      return Promise.resolve(new Response(FAKE, { status: 200, headers: { 'Content-Type': 'application/json' } }));
    }
    return of.apply(this, arguments).then(function (r) {
      try { r.clone().text().then(scan).catch(function () {}); } catch (e) {}
      return r;
    });
  };

  // XHR
  var xo = XMLHttpRequest.prototype.open, xs = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (m, u) { this.__gvUrl = u; return xo.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function (body) {
    var xhr = this;
    if (blocked(xhr.__gvUrl, bodyStr(body))) {
      setTimeout(function () {
        function def(k, v) { try { Object.defineProperty(xhr, k, { value: v, configurable: true }); } catch (e) {} }
        def('readyState', 4); def('status', 200); def('statusText', 'OK');
        def('responseText', FAKE); def('response', xhr.responseType === 'json' ? JSON.parse(FAKE) : FAKE);
        ['readystatechange', 'load', 'loadend'].forEach(function (t) { try { xhr.dispatchEvent(new Event(t)); } catch (e) {} });
      }, 0);
      return;
    }
    xhr.addEventListener('load', function () {
      try { if (!xhr.responseType || xhr.responseType === 'text') scan(xhr.responseText); } catch (e) {}
    });
    return xs.apply(this, arguments);
  };

  // analytics beacons: never sent
  try { navigator.sendBeacon = function () { return true; }; } catch (e) {}

  // data embedded in the page HTML
  function scanScripts() {
    var s = document.querySelectorAll('script[type="application/json"]');
    for (var i = 0; i < s.length; i++) scan(s[i].textContent);
  }
  document.addEventListener('DOMContentLoaded', scanScripts);
  window.addEventListener('load', scanScripts);

  // Keep everything silent and paused; press "View story" only if nothing loaded by itself.
  var tries = 0;
  var iv = setInterval(function () {
    tries++;
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) { try { vids[i].muted = true; vids[i].pause(); } catch (e) {} }
    if (Object.keys(found).length === 0 && tries >= 3 && tries % 2 === 1) {
      var els = document.querySelectorAll('button, div[role="button"], a[role="button"]');
      for (var j = 0; j < els.length; j++) {
        var txt = (els[j].innerText || '').trim();
        if (/^view stor(y|ies)$/i.test(txt) || /view story/i.test(txt)) { els[j].click(); break; }
      }
      scanScripts();
    }
    if (tries > 40) clearInterval(iv);
  }, 700);
})();

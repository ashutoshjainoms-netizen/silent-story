/*
 * GhostView page script — runs inside instagram.com before the page's own code.
 * 1) Anonymous mode: drops the "story seen" requests so you never appear in viewer lists.
 * 2) Downloader: window.__ghostDownload() finds the media for the current page
 *    (story, highlight, post, reel, profile) and hands the URLs to the Android app.
 */
(function () {
  if (window.__ghostInstalled) return;
  window.__ghostInstalled = true;

  var APP_ID = '936619743392459'; // Instagram web app id, required by its API
  var RESERVED = ['explore', 'direct', 'accounts', 'reels', 'reel', 'p', 'tv', 'stories',
    'about', 'legal', 'developer', 'emails', 'challenge', 'web', 'session', 'privacy'];

  function bridge() { try { return window.GhostBridge; } catch (e) { return null; } }
  function ghostOn() { var b = bridge(); try { return b ? b.isGhost() : true; } catch (e) { return true; } }
  function reportBlocked(what) { var b = bridge(); try { if (b) b.onBlocked(String(what)); } catch (e) {} }

  // ---------- 1) Anonymous story viewing ----------
  var SEEN_RE = /reel\/seen|reels?_?seen|stor(y|ies)[a-z0-9_]*seen|seen[a-z0-9_]*(stor|reel)|viewSeenAt/i;

  function bodyToString(b) {
    if (b == null) return '';
    if (typeof b === 'string') return b;
    try {
      if (b instanceof URLSearchParams) return b.toString();
      if (b instanceof FormData) {
        var parts = [];
        b.forEach(function (v, k) { parts.push(k + '=' + (typeof v === 'string' ? v : '')); });
        return parts.join('&');
      }
    } catch (e) {}
    return '';
  }

  function safeDecode(s) { try { return decodeURIComponent(s); } catch (e) { return s; } }

  function isSeenRequest(url, body) {
    if (!ghostOn()) return false;
    url = String(url || '');
    if (/\/stories\/reel\/seen/i.test(url)) return true;
    if (/graphql|\/api\//i.test(url)) {
      var text = safeDecode(String(body || '').replace(/\+/g, ' '));
      if (SEEN_RE.test(text)) return true;
    }
    return false;
  }

  var FAKE = '{"status":"ok","data":{}}';

  var origFetch = window.fetch;
  window.fetch = function (input, init) {
    try {
      var url = (typeof input === 'string') ? input : (input && input.url) || '';
      if (isSeenRequest(url, bodyToString(init && init.body))) {
        reportBlocked(url);
        return Promise.resolve(new Response(FAKE, { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
    } catch (e) {}
    return origFetch.apply(this, arguments);
  };

  var xOpen = XMLHttpRequest.prototype.open;
  var xSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__ghostUrl = url;
    return xOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function (body) {
    var xhr = this;
    var blocked = false;
    try { blocked = isSeenRequest(xhr.__ghostUrl, bodyToString(body)); } catch (e) {}
    if (!blocked) return xSend.apply(this, arguments);

    reportBlocked(xhr.__ghostUrl);
    setTimeout(function () {
      function def(k, v) { try { Object.defineProperty(xhr, k, { value: v, configurable: true }); } catch (e) {} }
      def('readyState', 4);
      def('status', 200);
      def('statusText', 'OK');
      def('responseText', FAKE);
      def('response', xhr.responseType === 'json' ? JSON.parse(FAKE) : FAKE);
      ['readystatechange', 'load', 'loadend'].forEach(function (t) {
        try { xhr.dispatchEvent(new Event(t)); } catch (e) {}
      });
    }, 0);
  };

  if (navigator.sendBeacon) {
    var origBeacon = navigator.sendBeacon.bind(navigator);
    navigator.sendBeacon = function (url, data) {
      try { if (isSeenRequest(url, bodyToString(data))) { reportBlocked(url); return true; } } catch (e) {}
      return origBeacon(url, data);
    };
  }

  // ---------- 2) Downloader ----------
  function csrf() { var m = document.cookie.match(/(?:^|;\s*)csrftoken=([^;]+)/); return m ? m[1] : ''; }

  function api(path) {
    return origFetch.call(window, 'https://www.instagram.com' + path, {
      credentials: 'include',
      headers: { 'X-IG-App-ID': APP_ID, 'X-Requested-With': 'XMLHttpRequest', 'X-CSRFToken': csrf() }
    }).then(function (r) {
      if (r.status === 401 || r.status === 403) throw new Error('Instagram refused the request — make sure you are logged in');
      if (r.status === 429) throw new Error('Instagram is rate-limiting you — wait a few minutes');
      if (!r.ok) throw new Error('Instagram error ' + r.status);
      return r.json();
    });
  }

  function pickBest(arr) {
    return arr.slice().sort(function (a, b) { return (b.width * b.height) - (a.width * a.height); })[0];
  }

  function mediaOf(item, name) {
    if (!item) return null;
    if (item.video_versions && item.video_versions.length)
      return { url: pickBest(item.video_versions).url, type: 'video', name: name };
    var c = item.image_versions2 && item.image_versions2.candidates;
    if (c && c.length) return { url: pickBest(c).url, type: 'image', name: name };
    return null;
  }

  function fromPost(item) {
    var user = (item.user && item.user.username) || 'post';
    var code = item.code || item.pk;
    if (item.carousel_media && item.carousel_media.length)
      return item.carousel_media.map(function (c, i) { return mediaOf(c, user + '_' + code + '_' + (i + 1)); });
    return [mediaOf(item, user + '_' + code)];
  }

  function storyItems(reel, currentPk, label) {
    var items = (reel && reel.items) || [];
    var user = (reel && reel.user && reel.user.username) || label;
    return items.map(function (it, i) {
      var m = mediaOf(it, user + '_' + label + '_' + (it.pk || i));
      if (m) m.current = !!currentPk && String(it.pk) === String(currentPk);
      return m;
    });
  }

  function reelFrom(j, key) {
    if (j && j.reels && j.reels[key]) return j.reels[key];
    if (j && j.reels_media && j.reels_media.length) return j.reels_media[0];
    return null;
  }

  function getUser(username) {
    return api('/api/v1/users/web_profile_info/?username=' + encodeURIComponent(username)).then(function (j) {
      var u = j && j.data && j.data.user;
      if (!u) throw new Error('Could not find @' + username);
      return u;
    });
  }

  function getStories(userId) {
    return api('/api/v1/feed/reels_media/?reel_ids=' + userId).then(function (j) { return reelFrom(j, String(userId)); });
  }

  function shortcodeToId(code) {
    var A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
    var id = BigInt(0);
    code = code.slice(0, 11);
    for (var i = 0; i < code.length; i++) id = id * BigInt(64) + BigInt(A.indexOf(code[i]));
    return id.toString();
  }

  window.__ghostDownload = function () {
    var p = location.pathname;
    var m;

    function fail(e) { var b = bridge(); try { if (b) b.onError(String((e && e.message) || e)); } catch (_) {} }
    function send(title, list) {
      list = (list || []).filter(function (x) { return x && x.url; });
      if (!list.length) return fail('No downloadable media found here');
      bridge().onMedia(JSON.stringify({ title: title, items: list }));
    }

    try {
      // Highlights: /stories/highlights/<id>/
      if ((m = p.match(/^\/stories\/highlights\/(\d+)/))) {
        var key = 'highlight:' + m[1];
        return api('/api/v1/feed/reels_media/?reel_ids=' + encodeURIComponent(key)).then(function (j) {
          send('Highlight', storyItems(reelFrom(j, key), p.split('/')[4], 'highlight'));
        }).catch(fail);
      }

      // Stories: /stories/<username>/<storyId>/
      if ((m = p.match(/^\/stories\/([A-Za-z0-9._]+)\/?(\d+)?/))) {
        var uname = m[1], cur = m[2];
        return getUser(uname).then(function (u) { return getStories(u.id); }).then(function (reel) {
          if (!reel) throw new Error('@' + uname + ' has no active stories');
          send('@' + uname + ' stories', storyItems(reel, cur, 'story'));
        }).catch(fail);
      }

      // Posts / reels: /p/<code>/, /reel/<code>/, /reels/<code>/, /tv/<code>/
      if ((m = p.match(/\/(?:p|reels?|tv)\/([A-Za-z0-9_-]+)/))) {
        return api('/api/v1/media/' + shortcodeToId(m[1]) + '/info/').then(function (j) {
          var it = j.items && j.items[0];
          if (!it) throw new Error('Post not found');
          send('Post', fromPost(it));
        }).catch(fail);
      }

      // Profile: /<username>/  -> HD profile photo + current stories
      if ((m = p.match(/^\/([A-Za-z0-9._]+)\/?$/)) && RESERVED.indexOf(m[1].toLowerCase()) < 0) {
        var name = m[1];
        return getUser(name).then(function (u) {
          var fallback = u.profile_pic_url_hd || u.profile_pic_url;
          var pic = api('/api/v1/users/' + u.id + '/info/').then(function (j) {
            var info = j.user && j.user.hd_profile_pic_url_info;
            return (info && info.url) || fallback;
          }).catch(function () { return fallback; });
          var stories = getStories(u.id).catch(function () { return null; });
          return Promise.all([pic, stories]).then(function (r) {
            var list = [{ url: r[0], type: 'image', name: name + '_profile_hd', label: 'Profile photo (HD)' }]
              .concat(storyItems(r[1], null, 'story'));
            send('@' + name, list);
          });
        }).catch(fail);
      }

      fail('Open a story, highlight, post, reel or profile, then tap download');
    } catch (e) { fail(e); }
  };
})();

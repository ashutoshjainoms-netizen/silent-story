/*
 * BlindView "Get code by email" helper. Runs on Instagram's own approval screen
 * and taps through Instagram's normal options: "Try another way" -> Email -> Send.
 * Returns one word so the app knows where it is:
 *   login   - still on the login form (log in first)
 *   another - tapped "Try another way"
 *   email   - picked the email option
 *   send    - tapped Send / Continue
 *   code    - the code box is showing
 *   none    - nothing to tap yet
 */
(function () {
  function vis(e) { var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  function txt(e) {
    return ((e.innerText || e.textContent || '') + ' ' + (e.getAttribute('aria-label') || ''))
      .replace(/\s+/g, ' ').trim().toLowerCase();
  }
  function clickables() {
    return Array.prototype.slice.call(document.querySelectorAll(
      'button,a,[role=button],[role=radio],[role=link],[role=option],label,input[type=radio],div[tabindex],span[tabindex]'
    )).filter(vis);
  }
  function find(re, max) {
    var c = clickables();
    for (var i = 0; i < c.length; i++) {
      var t = txt(c[i]);
      if (t && t.length <= (max || 60) && re.test(t)) return c[i];
    }
    return null;
  }
  function tap(e) { try { e.scrollIntoView({ block: 'center' }); } catch (x) {} e.click(); }

  var pass = document.querySelector('input[type=password]');
  if (pass && vis(pass)) return 'login';

  var code = document.querySelector(
    'input[name=verificationCode],input[name=security_code],input[autocomplete=one-time-code],input[inputmode=numeric],input[name=code]');
  if (code && vis(code)) return 'code';

  var step = window.__bvStep || 0;

  if (step < 2) {
    var opt = find(/e-?mail/, 120);
    if (opt) {
      var radio = opt.tagName === 'INPUT' ? opt : (opt.querySelector('input[type=radio]') || opt);
      tap(radio);
      window.__bvStep = 2;
      return 'email';
    }
  } else {
    var send = find(/^(send|send code|send security code|send login code|continue|next|confirm|get code)$/, 30);
    if (send) { tap(send); window.__bvStep = 3; return 'send'; }
  }

  if (step < 1) {
    var other = find(/try another way|use another (method|way)|another way|other (options|ways)|more options|can.t (approve|access|get)|didn.t get|use a code|get a code|send (a )?code/, 80);
    if (other) { tap(other); window.__bvStep = 1; return 'another'; }
    // The new "Check your notifications" screen hides the option: scroll to the bottom first.
    try { window.scrollTo(0, document.body.scrollHeight); } catch (x) {}
    var scrollers = document.querySelectorAll('div');
    for (var k = 0; k < scrollers.length; k++) {
      var d = scrollers[k];
      if (d.scrollHeight > d.clientHeight + 20) { try { d.scrollTop = d.scrollHeight; } catch (x) {} }
    }
    // Then try the "?" help button once; it lists the other ways to confirm.
    var tries = (window.__bvTries = (window.__bvTries || 0) + 1);
    if (!window.__bvHelp && tries >= 3) {
      var help = find(/^(help|\?|get help|need help\??|help center)$/, 20);
      if (!help) {
        var all = clickables();
        for (var j = 0; j < all.length; j++) {
          var a = (all[j].getAttribute('aria-label') || '').toLowerCase();
          if (/help|question/.test(a)) { help = all[j]; break; }
        }
      }
      if (help) { tap(help); window.__bvHelp = true; return 'help'; }
    }
  }
  return 'none';
})();

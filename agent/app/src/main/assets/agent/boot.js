/* Small, synchronous startup bridge. It loads before the bundled interface. */
(function () {
  'use strict';
  var token = '', ready = false, painted = false, failure = '', pending = [], revision = 0, polling = false;
  function transmit(phase, message, details) {
    var event = {version: 1, command: 'web_boot', phase: phase, message: String(message || '').slice(0, 1200)};
    if (details) { event.revision = details.revision; event.layout = details.layout; }
    if (!token || !window.AsterNative || typeof window.AsterNative.postMessage !== 'function') {
      pending.push(event); if (pending.length > 8) pending.shift(); return;
    }
    event.token = token;
    try { window.AsterNative.postMessage(JSON.stringify(event)); } catch (_) { pending.push(event); }
  }
  function connect(value) {
    token = String(value || token || '');
    if (!token && window.AsterNative && typeof window.AsterNative.bootstrapToken === 'function') {
      try { token = String(window.AsterNative.bootstrapToken()); } catch (_) { }
    }
    if (!token) return;
    var queued = pending; pending = [];
    for (var i = 0; i < queued.length; i++) transmit(queued[i].phase, queued[i].message, queued[i]);
    if (failure) transmit('error', failure);
    else if (ready) transmit('ready', 'UI script ready');
  }
  function fallback() {
    var root = document.getElementById('app');
    if (root) { root.textContent = 'Agent 界面启动失败。请在原生启动面板重试。\n' + failure; root.setAttribute('role', 'alert'); }
  }
  function fail(message) {
    failure = String(message || '未知脚本错误').slice(0, 1200);
    transmit('error', failure);
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fallback, {once: true});
    else fallback();
  }
  function pull() {
    if (!ready || failure) return;
    if (!token) connect();
    if (!token || !window.AsterNative || typeof window.AsterNative.readUiState !== 'function') return;
    try {
      var raw = window.AsterNative.readUiState(token, String(revision));
      if (!raw) return;
      var packet = JSON.parse(raw);
      if (packet.version !== 1 || typeof packet.revision !== 'number' || packet.revision <= revision ||
          !Number.isSafeInteger(packet.revision) || !packet.state || packet.state.token !== token) {
        fail('原生界面状态的认证或版本无效。'); return;
      }
      if (typeof window.AsterReceive !== 'function' || window.AsterReceive(packet.state, packet.revision) !== true) {
        fail('界面未接受工作区与会话状态。'); return;
      }
      revision = packet.revision;
    } catch (error) { fail('读取原生界面状态失败：' + String(error.message || error)); }
  }
  function visibleRect(selector) {
    var node = document.querySelector(selector);
    if (!node) return null;
    var box = node.getBoundingClientRect(), style = window.getComputedStyle(node);
    return box.width > 0 && box.height > 0 && box.right > 0 && box.bottom > 0 &&
      box.left < window.innerWidth && box.top < window.innerHeight && style.display !== 'none' && style.visibility !== 'hidden' ? box : null;
  }
  window.AsterBootstrap = connect;
  window.AsterBoot = {
    ready: function () {
      ready = true; connect(); pull();
      if (!polling) { polling = true; setInterval(pull, 120); }
    },
    rendered: function (value) {
      if (painted) return true;
      if (failure || !ready || !Number.isSafeInteger(value) || value <= 0) return false;
      var root = visibleRect('.agent-layout'), conversation = visibleRect('.conversation-viewport'), input = visibleRect('.task-input');
      if (!root || !conversation || !input) return false;
      painted = true;
      transmit('rendered', 'Visible Agent layout and composer confirmed', {revision: value, layout: {
        width: root.width, height: root.height, conversationHeight: conversation.height, inputHeight: input.height
      }});
      return true;
    },
    fail: fail
  };
  window.addEventListener('error', function (event) {
    var target = event.target;
    if (target && target !== window && (target.tagName === 'SCRIPT' || target.tagName === 'LINK')) {
      fail('界面资源加载失败：' + (target.src || target.href || 'unknown')); return;
    }
    if (event.message) fail(event.message + ' @ ' + (event.filename || '') + ':' + (event.lineno || 0));
  }, true);
  window.addEventListener('unhandledrejection', function (event) { fail(String(event.reason && event.reason.message || event.reason || '异步脚本失败')); });
  // replaceChildren is missing in older system WebViews; retain text-only DOM construction.
  function supportReplaceChildren(type) {
    if (type && !type.prototype.replaceChildren) type.prototype.replaceChildren = function () {
      while (this.firstChild) this.removeChild(this.firstChild);
      for (var i = 0; i < arguments.length; i++) this.appendChild(typeof arguments[i] === 'string' ? document.createTextNode(arguments[i]) : arguments[i]);
    };
  }
  supportReplaceChildren(window.Element); supportReplaceChildren(window.DocumentFragment);
  connect();
  setTimeout(function () { if (!ready && !failure) fail('界面脚本没有完成加载（8 秒）。'); }, 8000);
}());

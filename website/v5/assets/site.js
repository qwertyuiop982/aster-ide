(() => {
  'use strict';
  const stage = document.getElementById('phone-stage');
  if (!stage) return;
  // CSS fallback keeps the same hold-to-focus contract when WebGL is unavailable.
  let fallbackHold = 0, fallbackStart = null;
  const exitFallback = () => {
    clearTimeout(fallbackHold); fallbackStart = null;
    if (stage.dataset.scene !== 'ready') document.body.classList.remove('is-inspecting');
  };
  stage.addEventListener('pointerdown', e => {
    if (stage.dataset.scene === 'ready' || (e.pointerType === 'mouse' && e.button !== 0)) return;
    fallbackStart = [e.clientX, e.clientY]; clearTimeout(fallbackHold);
    fallbackHold = setTimeout(() => { if (stage.dataset.scene !== 'ready') document.body.classList.add('is-inspecting'); }, 440);
  });
  stage.addEventListener('pointermove', e => {
    if (fallbackStart && Math.hypot(e.clientX - fallbackStart[0], e.clientY - fallbackStart[1]) > 9) exitFallback();
  });
  for (const event of ['pointerup', 'pointercancel', 'lostpointercapture']) window.addEventListener(event, exitFallback);
  window.addEventListener('blur', exitFallback);
  document.addEventListener('visibilitychange', () => { if (document.hidden) exitFallback(); });
  stage.addEventListener('keydown', e => {
    if (stage.dataset.scene === 'ready') return;
    if (e.key.toLowerCase() === 'i') { e.preventDefault(); document.body.classList.toggle('is-inspecting'); }
    if (e.key === 'Escape') exitFallback();
  });
  const base = document.currentScript.src;
  const start = () => {
    import(new URL('scene.js' + new URL(base).search, base).href)
      .then(module => module.startScene(stage))
      .catch(() => {
        stage.dataset.scene = 'fallback';
        stage.querySelectorAll('canvas').forEach(canvas => canvas.remove());
        for (const id of ['motion-toggle', 'scene-reset']) document.getElementById(id).disabled = true;
      });
  };
  // Keep navigation and the static composition usable before WebGL initializes.
  requestAnimationFrame(() => requestAnimationFrame(start));
})();

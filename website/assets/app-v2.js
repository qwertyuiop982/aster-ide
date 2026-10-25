(() => {
  'use strict';
  const $ = selector => document.querySelector(selector);
  const menu = $('.nav-toggle');
  const nav = $('#primary-nav');
  const closeMenu = () => { menu?.setAttribute('aria-expanded', 'false'); nav?.classList.remove('open'); };
  menu?.addEventListener('click', () => {
    const open = menu.getAttribute('aria-expanded') !== 'true';
    menu.setAttribute('aria-expanded', String(open)); nav?.classList.toggle('open', open);
  });
  nav?.addEventListener('click', event => { if (event.target.closest('a')) closeMenu(); });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && menu?.getAttribute('aria-expanded') === 'true') { closeMenu(); menu.focus(); }
  });
  document.addEventListener('click', event => { if (!event.target.closest('.site-header')) closeMenu(); });
  window.matchMedia('(min-width: 681px)').addEventListener('change', closeMenu);

  const copy = $('#copy-sha');
  copy?.addEventListener('click', async () => {
    const feedback = $('#copy-feedback');
    try {
      await navigator.clipboard.writeText(copy.dataset.value);
      feedback.textContent = 'SHA-256 已复制';
    } catch {
      const code = $('.checksum code');
      const range = document.createRange(); range.selectNodeContents(code);
      const selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
      feedback.textContent = '已选中校验值，请长按或使用系统复制。';
    }
  });

  if (!$('#demo-panel')) return;
  const initialCode = $('#demo-visual').innerHTML;
  const configurations = {
    code: {
      path: 'hello-aster', file: 'K  Main.kt', kind: '编辑器', engine: 'Codex',
      request: '检查项目入口，并运行一次构建。',
      response: '我会先读取项目结构，再请求运行构建。',
      tools: ['读取项目结构', '运行构建', '检查执行结果'],
      approval: '项目结构已读取。是否允许这次构建？',
      result: '构建完成，已取得执行结果。你可以继续下一步。',
      sidebar: '<span class="demo-folder">⌄ &nbsp; HELLO-ASTER</span><span class="demo-file active">K &nbsp; Main.kt</span><span class="demo-file">G &nbsp; build.gradle.kts</span><span class="demo-file"># &nbsp; README.md</span>',
      visual: initialCode
    },
    web: {
      path: '轻舟浏览器', file: '◎  项目文档', kind: '网页快照', engine: 'Pi',
      request: '打开项目文档，搜索环境初始化指南。',
      response: '先观察网页元素，再请求填写搜索框。',
      tools: ['读取网页快照', '填写搜索框', '读取页面结果'],
      approval: '已找到搜索框。是否允许填写并检索？',
      result: '已填写搜索框并读取结果，网页流程完成。',
      sidebar: '<span class="demo-folder">⌄ &nbsp; 轻舟浏览器</span><span class="demo-file active">◎ &nbsp; 项目文档</span><span class="demo-file">⌖ &nbsp; 网页元素</span><span class="demo-file">↶ &nbsp; 页面导航</span>',
      visual: '<div class="demo-web"><div class="demo-web-bar">◎ &nbsp; Aster / 项目文档</div><h4>每一个开始，都有路径。</h4><p>查找项目环境与使用指南。</p><div class="demo-web-input"><span id="demo-search-text">搜索文档…</span><span>↗</span></div><div class="demo-web-result">快速开始<small>安装应用并打开第一个项目</small></div><div class="demo-web-result">运行环境<small>准备终端、语言服务和构建工具</small></div></div>'
    },
    desktop: {
      path: '设备自动化', file: '▣  界面观察', kind: '屏幕快照', engine: 'Gemini',
      request: '观察设备界面，并点击工作台入口。',
      response: '先获取界面信息，再请求执行一次点击。',
      tools: ['获取界面快照', '点击目标位置', '观察界面变化'],
      approval: '已找到目标位置。是否允许这次点击？',
      result: '点击完成，并重新观察了界面变化。',
      sidebar: '<span class="demo-folder">⌄ &nbsp; 设备工具</span><span class="demo-file active">▣ &nbsp; 屏幕快照</span><span class="demo-file">⌖ &nbsp; 坐标映射</span><span class="demo-file">✓ &nbsp; 操作结果</span>',
      visual: '<div class="demo-screen"><div class="mini-phone"><div class="phone-top">9:41 <span>▮▮▮</span></div><div class="phone-title" id="demo-phone-title">我的设备</div><div class="phone-apps"><span>⌘</span><span>✳</span><span>◎</span><span>▣</span></div><div class="phone-card"><i></i><b></b><b></b><span class="target-corner"></span></div><div class="phone-home"></div></div><div class="demo-screen-label">观察 → 确认 → 操作<small>Shizuku 已授权 · 示意</small><small id="demo-device-result">等待观察界面</small></div></div>'
    }
  };
  let scenario = 'code';
  let generation = 0;
  let phase = 'idle';
  const timers = new Set();
  const run = $('#demo-run');
  const approve = $('#demo-approve');
  const feedback = $('#demo-feedback');
  const status = $('#demo-status');
  const tabs = [...document.querySelectorAll('[data-scenario]')];
  const schedule = (action, milliseconds) => {
    const current = generation;
    const id = setTimeout(() => { timers.delete(id); if (current === generation) action(); }, milliseconds);
    timers.add(id);
  };
  const toolState = (index, state, label) => {
    const row = $('#demo-tools').children[index];
    row.className = state;
    row.querySelector('.tool-state').textContent = state === 'done' ? '✓' : '';
    row.querySelector('small').textContent = label;
  };
  const reset = () => {
    generation++; timers.forEach(clearTimeout); timers.clear(); phase = 'idle';
    const config = configurations[scenario];
    $('#demo-path').textContent = config.path; $('#demo-file-label').textContent = config.file;
    $('#demo-stage-kind').textContent = config.kind; $('.demo-agent .engine-label').textContent = config.engine;
    $('#demo-request').textContent = config.request; $('#demo-response').textContent = config.response;
    $('#demo-sidebar-items').innerHTML = config.sidebar; $('#demo-visual').innerHTML = config.visual;
    const list = $('#demo-tools'); list.replaceChildren();
    config.tools.forEach((label, index) => {
      const row = document.createElement('li');
      const icon = document.createElement('span'); icon.className = 'tool-state';
      const name = document.createElement('span'); name.textContent = label;
      const result = document.createElement('small'); result.textContent = index === 1 ? '待确认' : '待运行';
      row.append(icon, name, result); list.append(row);
    });
    status.textContent = '准备就绪'; feedback.textContent = '点击下方按钮，开始一段工作流。';
    run.hidden = false; run.disabled = false; run.textContent = '▶ 运行演示'; approve.hidden = true;
    tabs.forEach(tab => {
      const selected = tab.dataset.scenario === scenario;
      tab.setAttribute('aria-selected', String(selected)); tab.tabIndex = selected ? 0 : -1;
    });
    $('#demo-panel').setAttribute('aria-labelledby', `scenario-${scenario}`);
  };
  const choose = tab => { scenario = tab.dataset.scenario; reset(); };
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => choose(tab));
    tab.addEventListener('keydown', event => {
      let next;
      if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
      if (event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length;
      if (event.key === 'Home') next = 0;
      if (event.key === 'End') next = tabs.length - 1;
      if (next !== undefined) { event.preventDefault(); choose(tabs[next]); tabs[next].focus(); }
    });
  });
  $('#demo-reset').addEventListener('click', reset);
  run.addEventListener('click', () => {
    if (phase === 'complete') reset();
    if (phase !== 'idle') return;
    phase = 'observing'; run.disabled = true;
    toolState(0, 'running', '运行中'); status.textContent = '正在观察'; feedback.textContent = '先取得上下文，再决定下一步。';
    schedule(() => {
      toolState(0, 'done', '186 ms'); toolState(1, 'waiting', '等待确认');
      phase = 'approval'; status.textContent = '等待确认'; feedback.textContent = configurations[scenario].approval;
      run.hidden = true; approve.hidden = false;
      if (scenario === 'desktop') $('#demo-device-result').textContent = '目标已定位，等待确认';
    }, 650);
  });
  approve.addEventListener('click', () => {
    if (phase !== 'approval') return;
    phase = 'acting'; approve.hidden = true; run.hidden = false; run.disabled = true; run.textContent = '执行中…';
    status.textContent = '正在执行'; feedback.textContent = '此次操作已获确认，正在执行。'; toolState(1, 'running', '运行中');
    schedule(() => {
      toolState(1, 'done', '824 ms'); toolState(2, 'running', '观察中');
      if (scenario === 'code') {
        const terminal = $('.demo-terminal'); terminal.replaceChildren();
        const title = document.createElement('span'); title.textContent = '终端';
        const command = document.createElement('p'); command.textContent = '❯ ./gradlew assembleDebug';
        const output = document.createElement('p'); output.textContent = 'BUILD SUCCESSFUL'; output.className = 'code-green';
        terminal.append(title, command, output);
      }
      if (scenario === 'web') $('#demo-search-text').textContent = '环境初始化指南';
      if (scenario === 'desktop') { $('#demo-phone-title').textContent = '我的工作台'; $('#demo-device-result').textContent = '已点击，正在观察变化'; }
      schedule(() => {
        toolState(2, 'done', '242 ms'); phase = 'complete'; status.textContent = '演示完成';
        feedback.textContent = configurations[scenario].result; run.disabled = false; run.textContent = '↺ 再演示一次';
        if (scenario === 'desktop') $('#demo-device-result').textContent = '界面变化已观察';
      }, 600);
    }, 800);
  });
  document.addEventListener('visibilitychange', () => { if (document.hidden && phase !== 'idle') reset(); });
  reset();
})();
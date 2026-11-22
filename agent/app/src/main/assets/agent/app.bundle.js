(() => {
  // agent/app/src/main/assets/agent/locale.js
  function uiLocale() {
    var _a2;
    return typeof window !== "undefined" && ((_a2 = window.AsterBoot) == null ? void 0 : _a2.locale) === "zh-Hans" ? "zh-Hans" : "en";
  }
  function tr(chinese, english) {
    return uiLocale() === "zh-Hans" ? chinese : english;
  }

  // agent/app/src/main/assets/agent/icons.js
  var attachmentIcons = {
    image: [["rect", { width: 18, height: 18, x: 3, y: 3, rx: 2, ry: 2 }], ["circle", { cx: 9, cy: 9, r: 2 }], ["path", { d: "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21" }]],
    file: [["path", { d: "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8l6 6v12a2 2 0 0 1-2 2Z" }], ["path", { d: "M14 2v6h6" }]]
  };
  var icons = { "timer": [["circle", { "cx": "12", "cy": "14", "r": "8" }], ["path", { "d": "M12 10v4l2 2M9 2h6M12 2v4m6 2 2-2" }]], "menu": [["line", { "x1": "4", "x2": "20", "y1": "12", "y2": "12" }], ["line", { "x1": "4", "x2": "20", "y1": "6", "y2": "6" }], ["line", { "x1": "4", "x2": "20", "y1": "18", "y2": "18" }]], "chevron-down": [["path", { "d": "m6 9 6 6 6-6" }]], "chevron-right": [["path", { "d": "m9 18 6-6-6-6" }]], "chevron-left": [["path", { "d": "m15 18-6-6 6-6" }]], "plus": [["path", { "d": "M5 12h14" }], ["path", { "d": "M12 5v14" }]], "arrow-up": [["path", { "d": "m5 12 7-7 7 7" }], ["path", { "d": "M12 19V5" }]], "arrow-down": [["path", { "d": "M12 5v14" }], ["path", { "d": "m19 12-7 7-7-7" }]], "square": [["rect", { "width": "18", "height": "18", "x": "3", "y": "3", "rx": "2" }]], "x": [["path", { "d": "M18 6 6 18" }], ["path", { "d": "m6 6 12 12" }]], "ellipsis": [["circle", { "cx": "12", "cy": "12", "r": "1" }], ["circle", { "cx": "19", "cy": "12", "r": "1" }], ["circle", { "cx": "5", "cy": "12", "r": "1" }]], "folder": [["path", { "d": "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z" }]], "file-code-2": [["path", { "d": "M4 22h14a2 2 0 0 0 2-2V7l-5-5H6a2 2 0 0 0-2 2v4" }], ["path", { "d": "M14 2v4a2 2 0 0 0 2 2h4" }], ["path", { "d": "m5 12-3 3 3 3" }], ["path", { "d": "m9 18 3-3-3-3" }]], "code-xml": [["path", { "d": "m18 16 4-4-4-4" }], ["path", { "d": "m6 8-4 4 4 4" }], ["path", { "d": "m14.5 4-5 16" }]], "search": [["circle", { "cx": "11", "cy": "11", "r": "8" }], ["path", { "d": "m21 21-4.3-4.3" }]], "git-pull-request": [["circle", { "cx": "18", "cy": "18", "r": "3" }], ["circle", { "cx": "6", "cy": "6", "r": "3" }], ["path", { "d": "M13 6h3a2 2 0 0 1 2 2v7" }], ["line", { "x1": "6", "x2": "6", "y1": "9", "y2": "21" }]], "wrench": [["path", { "d": "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" }]], "list-checks": [["path", { "d": "m3 17 2 2 4-4" }], ["path", { "d": "m3 7 2 2 4-4" }], ["path", { "d": "M13 6h8" }], ["path", { "d": "M13 12h8" }], ["path", { "d": "M13 18h8" }]], "settings-2": [["path", { "d": "M20 7h-9" }], ["path", { "d": "M14 17H5" }], ["circle", { "cx": "17", "cy": "17", "r": "3" }], ["circle", { "cx": "7", "cy": "7", "r": "3" }]], "panel-right": [["rect", { "width": "18", "height": "18", "x": "3", "y": "3", "rx": "2" }], ["path", { "d": "M15 3v18" }]], "panel-left": [["rect", { "width": "18", "height": "18", "x": "3", "y": "3", "rx": "2" }], ["path", { "d": "M9 3v18" }]], "check": [["path", { "d": "M20 6 9 17l-5-5" }]], "circle": [["circle", { "cx": "12", "cy": "12", "r": "10" }]], "loader-circle": [["path", { "d": "M21 12a9 9 0 1 1-6.219-8.56" }]], "copy": [["rect", { "width": "14", "height": "14", "x": "8", "y": "8", "rx": "2", "ry": "2" }], ["path", { "d": "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2" }]], "trash-2": [["path", { "d": "M3 6h18" }], ["path", { "d": "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6" }], ["path", { "d": "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2" }], ["line", { "x1": "10", "x2": "10", "y1": "11", "y2": "17" }], ["line", { "x1": "14", "x2": "14", "y1": "11", "y2": "17" }]], "message-square": [["path", { "d": "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" }]], "sliders-horizontal": [["line", { "x1": "21", "x2": "14", "y1": "4", "y2": "4" }], ["line", { "x1": "10", "x2": "3", "y1": "4", "y2": "4" }], ["line", { "x1": "21", "x2": "12", "y1": "12", "y2": "12" }], ["line", { "x1": "8", "x2": "3", "y1": "12", "y2": "12" }], ["line", { "x1": "21", "x2": "16", "y1": "20", "y2": "20" }], ["line", { "x1": "12", "x2": "3", "y1": "20", "y2": "20" }], ["line", { "x1": "14", "x2": "14", "y1": "2", "y2": "6" }], ["line", { "x1": "8", "x2": "8", "y1": "10", "y2": "14" }], ["line", { "x1": "16", "x2": "16", "y1": "18", "y2": "22" }]], "paperclip": [["path", { "d": "M13.234 20.252 21 12.3" }], ["path", { "d": "m16 6-8.414 8.586a2 2 0 0 0 0 2.828 2 2 0 0 0 2.828 0l8.414-8.586a4 4 0 0 0 0-5.656 4 4 0 0 0-5.656 0l-8.415 8.585a6 6 0 1 0 8.486 8.486" }]], "layers": [["path", { "d": "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z" }], ["path", { "d": "M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12" }], ["path", { "d": "M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17" }]], "zap": [["path", { "d": "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z" }]], "terminal": [["polyline", { "points": "4 17 10 11 4 5" }], ["line", { "x1": "12", "x2": "20", "y1": "19", "y2": "19" }]], "history": [["path", { "d": "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" }], ["path", { "d": "M3 3v5h5" }], ["path", { "d": "M12 7v5l4 2" }]], "arrow-left-right": [["path", { "d": "M8 3 4 7l4 4" }], ["path", { "d": "M4 7h16" }], ["path", { "d": "m16 21 4-4-4-4" }], ["path", { "d": "M20 17H4" }]], "notebook-pen": [["path", { "d": "M13.4 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7.4" }], ["path", { "d": "M2 6h4" }], ["path", { "d": "M2 10h4" }], ["path", { "d": "M2 14h4" }], ["path", { "d": "M2 18h4" }], ["path", { "d": "M21.378 5.626a1 1 0 1 0-3.004-3.004l-5.01 5.012a2 2 0 0 0-.506.854l-.837 2.87a.5.5 0 0 0 .62.62l2.87-.837a2 2 0 0 0 .854-.506z" }]], "sparkles": [["path", { "d": "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z" }], ["path", { "d": "M20 3v4" }], ["path", { "d": "M22 5h-4" }], ["path", { "d": "M4 17v2" }], ["path", { "d": "M5 18H3" }]] };

  // agent/app/src/main/assets/agent/dom.js
  function el(tag, className = "", text) {
    const node = document.createElement(tag);
    node.className = className;
    if (text !== void 0) node.textContent = text;
    return node;
  }
  function icon(name) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    for (const [key2, value] of Object.entries({ viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", "stroke-width": "1.65", "stroke-linecap": "round", "stroke-linejoin": "round", "aria-hidden": "true", focusable: "false", class: "icon" })) svg.setAttribute(key2, value);
    for (const [tag, attributes] of icons[name] || attachmentIcons[name] || icons.circle) {
      const child = document.createElementNS(svg.namespaceURI, tag);
      for (const [key2, value] of Object.entries(attributes)) child.setAttribute(key2, value);
      svg.append(child);
    }
    return svg;
  }
  function button(label, action, className = "", symbol) {
    const node = el("button", className);
    node.type = "button";
    if (symbol) node.append(icon(symbol));
    if (label) node.append(el("span", "button-label", label));
    node.addEventListener("click", action);
    return node;
  }
  function iconButton(label, name, action, className = "") {
    const node = button("", action, "icon-button " + className, name);
    node.setAttribute("aria-label", label);
    node.title = label;
    return node;
  }
  function section(title, className = "") {
    const node = el("section", "info-section " + className);
    node.append(el("h2", "section-heading", title));
    return node;
  }
  function inlineText(target, text) {
    const parts = String(text).split(/(`[^`\n]+`|\*\*[^*\n]+\*\*)/g);
    for (const part of parts) {
      if (part.startsWith("`") && part.endsWith("`")) target.append(el("code", "inline-code", part.slice(1, -1)));
      else if (part.startsWith("**") && part.endsWith("**")) target.append(el("strong", "", part.slice(2, -2)));
      else target.append(document.createTextNode(part));
    }
  }
  function richText(target, text, copy) {
    const fragment = document.createDocumentFragment();
    const chunks = String(text).split(/```/);
    chunks.forEach((chunk, index) => {
      if (index % 2) {
        const nl = chunk.indexOf("\n");
        const language = nl < 0 ? "" : chunk.slice(0, nl).trim();
        const code = nl < 0 ? chunk : chunk.slice(nl + 1).replace(/\n$/, "");
        const block = el("div", "code-block"), bar = el("div", "code-header");
        bar.append(el("span", "", language.slice(0, 48) || tr("代码", "Code")), iconButton(tr("复制代码", "Copy code"), "copy", () => copy(code)));
        block.append(bar, el("pre", "code-content", code));
        fragment.append(block);
      } else {
        let paragraph = null, list = null;
        for (const line of chunk.split("\n")) {
          if (!line.trim()) {
            paragraph = null;
            list = null;
            continue;
          }
          const heading = line.match(/^(#{1,4})\s+(.+)/), bullet = line.match(/^\s*(?:[-*]|\d+\.)\s+(.+)/);
          if (heading) {
            paragraph = null;
            list = null;
            const h = el("h3");
            inlineText(h, heading[2]);
            fragment.append(h);
          } else if (bullet) {
            paragraph = null;
            if (!list) {
              list = el("ul");
              fragment.append(list);
            }
            const li = el("li");
            inlineText(li, bullet[1]);
            list.append(li);
          } else {
            list = null;
            if (!paragraph) {
              paragraph = el("p");
              fragment.append(paragraph);
            } else paragraph.append(document.createTextNode("\n"));
            inlineText(paragraph, line);
          }
        }
      }
    });
    target.replaceChildren(fragment);
  }

  // agent/app/src/main/assets/agent/message-renderer.js
  function createToolExpansionState() {
    const expanded = /* @__PURE__ */ new Set();
    return {
      has: (key2) => expanded.has(key2),
      set(key2, open) {
        expanded.delete(key2);
        if (open) expanded.add(key2);
        if (expanded.size > 512) expanded.delete(expanded.values().next().value);
      }
    };
  }
  function createMessageRenderer(container, request, dom = { el, icon, richText }, expansion = createToolExpansionState()) {
    const records = /* @__PURE__ */ new Map();
    let previousScope = null;
    return function render(scope, items, assistantLabel) {
      let changed = scope !== previousScope, previous = null;
      const retained = /* @__PURE__ */ new Set();
      for (const item of items) {
        const key2 = JSON.stringify([scope, item.id, item.role]);
        retained.add(key2);
        let record = records.get(key2);
        const tool = item.role === "tool" || item.role === "system";
        if (!record) {
          const row = dom.el(tool ? "details" : "article", "message " + (tool ? "tool-event" : item.role === "user" ? "user" : "assistant"));
          const body = dom.el("div", "message-body");
          let summary = null, label = null;
          if (tool) {
            const heading = dom.el("summary");
            summary = dom.el("span");
            heading.append(dom.icon("terminal"), summary, dom.icon("chevron-right"));
            row.append(heading, body);
            row.open = expansion.has(key2);
            row.addEventListener("toggle", () => {
              if (row.parentElement === container && row.isConnected !== false) expansion.set(key2, row.open);
            });
          } else {
            if (item.role !== "user") {
              label = dom.el("div", "assistant-label");
              row.append(label);
            }
            row.append(body);
          }
          record = { row, body, summary, label, text: null };
          records.set(key2, record);
          changed = true;
        }
        if (record.label && record.label.textContent !== assistantLabel) {
          record.label.textContent = assistantLabel;
          changed = true;
        }
        if (record.text !== item.text) {
          if (tool) {
            const heading = item.text.split("\n", 1)[0];
            record.summary.textContent = heading.slice(0, 110);
            record.row.dataset.failed = String(heading.includes(" · 失败"));
          }
          if (item.role === "user") record.body.textContent = item.text;
          else dom.richText(record.body, item.text, (text) => request("copy", { text }));
          record.text = item.text;
          changed = true;
        }
        if (record.row.parentElement !== container || record.row.previousElementSibling !== previous) {
          container.insertBefore(record.row, previous ? previous.nextSibling : container.firstChild);
          changed = true;
        }
        previous = record.row;
      }
      for (const [key2, record] of records) if (!retained.has(key2)) {
        if (record.summary && scope !== previousScope) expansion.set(key2, record.row.open);
        record.row.remove();
        records.delete(key2);
        changed = true;
      }
      previousScope = scope;
      return changed;
    };
  }

  // agent/app/src/main/assets/agent/appearance.js
  var DEFAULT_LEFT = "#713BC4";
  var DEFAULT_RIGHT = "#365DB7";
  var color = (value, fallback) => /^#[0-9a-f]{6}$/i.test(value || "") ? value.toUpperCase() : fallback;
  var rgb = (hex2) => [1, 3, 5].map((i) => parseInt(hex2.slice(i, i + 2), 16));
  var mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
  var shade = (a, amount) => a.map((v) => Math.floor(v * (1 - amount)));
  var hex = (a) => "#" + a.map((v) => v.toString(16).padStart(2, "0")).join("");
  var luminance = (a) => a.map((v) => {
    v /= 255;
    return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  }).reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0);
  function syncCodexCanvas(root) {
    if (!(root == null ? void 0 : root.isConnected)) return;
    const canvas = root.getBoundingClientRect();
    root.style.setProperty("--nebula-canvas-width", canvas.width + "px");
    root.style.setProperty("--nebula-canvas-height", canvas.height + "px");
    for (const node of root.querySelectorAll(".utility-dialog,.thinking-bubble,.agent-list-panel,.quick-picker")) {
      if (!node.getClientRects().length) continue;
      const box = node.getBoundingClientRect();
      node.style.setProperty("--nebula-canvas-x", canvas.left - box.left + "px");
      node.style.setProperty("--nebula-canvas-y", canvas.top - box.top + "px");
    }
  }
  function applyCodexAppearance(root, state) {
    const config = state.codexAppearance || {}, left = color(config.leftColor, DEFAULT_LEFT), right = color(config.rightColor, DEFAULT_RIGHT), all = state.engine === "codex" && config.wholeInterface === true;
    const changed = root.dataset.nebulaAll !== String(all);
    root.dataset.nebulaAll = String(all);
    const key2 = left + right;
    if (root.dataset.nebulaPalette !== key2) {
      root.dataset.nebulaPalette = key2;
      const a = rgb(left), b = rgb(right), average = mix(a, b, 0.5), samples = Array.from({ length: 17 }, (_, i) => luminance(mix(a, b, i / 16)));
      const dark = Math.min(...samples.map((v) => 1.05 / (v + 0.05))) >= Math.min(...samples.map((v) => (v + 0.05) / 0.05)), neutral = dark ? [0, 0, 0] : [255, 255, 255], ink = dark ? [255, 255, 255] : [12, 12, 16];
      let line = shade(average, 0.42);
      const limit = Math.min(...samples) * 0.48;
      for (let i = 0; i < 256 && luminance(line) > limit; i++) line = shade(line, 0.08);
      const values = {
        "--nebula-from": left,
        "--nebula-to": right,
        "--nebula-from-rgb": a.join(","),
        "--nebula-to-rgb": b.join(","),
        "--nebula-scheme": dark ? "dark" : "light",
        "--nebula-ink": hex(ink),
        "--nebula-muted": hex(mix(ink, average, 0.1)),
        "--nebula-line": hex(line),
        "--nebula-line-from": hex(shade(a, 0.42)),
        "--nebula-line-to": hex(shade(b, 0.42)),
        "--nebula-rail-from": hex(shade(a, 0.58)),
        "--nebula-rail-to": hex(shade(b, 0.58)),
        "--nebula-accent": hex(average),
        "--nebula-accent-ink": luminance(average) < 0.42 ? "#FFFFFF" : "#0C0C10",
        "--nebula-bg": hex(average),
        "--nebula-soft": hex(mix(average, neutral, 0.12)),
        "--nebula-hover": dark ? "rgba(0,0,0,.16)" : "rgba(0,0,0,.06)",
        "--nebula-surface-from": left,
        "--nebula-surface-to": right,
        "--nebula-panel-from": hex(mix(a, neutral, 0.12)),
        "--nebula-panel-to": hex(mix(b, neutral, 0.12)),
        "--nebula-bubble-from": left,
        "--nebula-bubble-to": right,
        "--nebula-control": hex(mix(average, neutral, 0.2)),
        "--nebula-highlight": dark ? "rgba(255,255,255,.16)" : "rgba(0,0,0,.08)"
      };
      for (const [name, value] of Object.entries(values)) root.style.setProperty(name, value);
    }
    if (all || changed) syncCodexCanvas(root);
  }

  // agent/app/src/main/assets/agent/bubbles.js
  function thinkingBubble(root, anchor, getState, request, notify) {
    const panel = el("dialog", "thinking-bubble");
    panel.setAttribute("aria-label", tr("思考设置", "Reasoning settings"));
    const bar = el("header", "bubble-heading");
    bar.append(el("h2", "", tr("思考", "Reasoning")), iconButton(tr("关闭思考设置", "Close reasoning settings"), "x", () => close()));
    const content = el("div", "thinking-content");
    panel.append(bar, content);
    root.append(panel);
    let last = "", previous, closing = false, finishTimer;
    const isOpen = () => panel.hasAttribute("open");
    function position() {
      if (!isOpen()) return;
      const a = anchor.getBoundingClientRect(), h = panel.offsetHeight;
      panel.style.left = Math.max(12, Math.min(innerWidth - panel.offsetWidth - 12, a.right - panel.offsetWidth)) + "px";
      const above = a.top - h - 12;
      panel.style.top = Math.max(12, Math.min(innerHeight - h - 12, above >= 12 ? above : a.bottom + 10)) + "px";
      syncCodexCanvas(root);
    }
    function finish() {
      clearTimeout(finishTimer);
      closing = false;
      panel.removeAttribute("data-closing");
      if (isOpen()) {
        if (panel.close) panel.close();
        else panel.removeAttribute("open");
      }
      const s = getState();
      panel.dataset.engine = s.engine || "";
      anchor.dataset.engine = s.engine || "";
      setUltra(s.engine === "codex" && s.thinkingLevel === "ultra");
      anchor.setAttribute("aria-expanded", "false");
      notify(false);
      if (previous == null ? void 0 : previous.isConnected) previous.focus();
    }
    function close(immediate = false) {
      if (!isOpen()) return false;
      if (immediate || getState().reducedMotion || matchMedia("(prefers-reduced-motion: reduce)").matches) {
        finish();
        return true;
      }
      if (!closing) {
        closing = true;
        panel.dataset.closing = "true";
        finishTimer = setTimeout(finish, 130);
      }
      return true;
    }
    function open() {
      if (isOpen()) {
        close();
        return;
      }
      const s = getState();
      if (s.busy || !s.session) return;
      previous = document.activeElement;
      last = "";
      update();
      if (panel.showModal) panel.showModal();
      else panel.setAttribute("open", "");
      position();
      anchor.setAttribute("aria-expanded", "true");
      notify(true);
      requestAnimationFrame(() => {
        var _a2;
        return (_a2 = content.querySelector("input")) == null ? void 0 : _a2.focus();
      });
    }
    anchor.addEventListener("click", open);
    anchor.setAttribute("aria-controls", "thinking-bubble");
    panel.id = "thinking-bubble";
    if (typeof ResizeObserver === "function") {
      const observer = new ResizeObserver(() => {
        if (isOpen()) syncCodexCanvas(root);
      });
      observer.observe(panel);
      panel._canvasObserver = observer;
    }
    panel.addEventListener("cancel", (e) => {
      e.preventDefault();
      close();
    });
    panel.addEventListener("click", (e) => {
      if (e.target === panel) {
        const r = panel.getBoundingClientRect();
        if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) close();
      }
    });
    function setUltra(active) {
      const value = String(!!active);
      panel.dataset.ultra = value;
      anchor.dataset.ultra = value;
    }
    function update() {
      const s = getState();
      panel.dataset.engine = s.engine || "";
      anchor.dataset.engine = s.engine || "";
      setUltra(s.engine === "codex" && s.thinkingLevel === "ultra");
      if (s.busy || !s.session) {
        close(true);
        return;
      }
      const key2 = JSON.stringify([s.engine, s.thinkingChoices, s.thinkingLevel, s.thinkingMode, s.thinkingBudget, s.thinkingBudgetRange, s.thinkingBudgetSupported]);
      if (key2 === last) return;
      last = key2;
      content.replaceChildren();
      const choices = (s.thinkingChoices || [{ id: "auto", label: tr("自动", "Auto") }]).map((c) => s.engine === "codex" ? { ...c, label: c.id === "ultra" ? "Ultra" : c.id.toLowerCase() } : c);
      const value = el("output", "thinking-value");
      value.hidden = true;
      const slider = el("input", "thinking-slider");
      slider.type = "range";
      slider.min = "0";
      slider.max = String(Math.max(1, choices.length - 1));
      slider.step = "1";
      slider.value = String(Math.max(0, choices.findIndex((c) => c.id === (s.thinkingLevel || "auto"))));
      slider.disabled = choices.length < 2;
      slider.setAttribute("aria-label", tr("思考等级", "Reasoning level"));
      function preview() {
        const choice = choices[Number(slider.value)] || choices[0];
        setUltra(s.engine === "codex" && choice.id === "ultra");
        value.textContent = choice.label;
        slider.setAttribute("aria-valuetext", choice.label);
        for (const tick of content.querySelectorAll(".thinking-tick")) tick.setAttribute("aria-pressed", String(tick.dataset.level === choice.id));
        slider.style.setProperty("--progress", 100 * Number(slider.value) / Number(slider.max) + "%");
      }
      slider.addEventListener("input", preview);
      slider.addEventListener("change", () => {
        if (!getState().busy) request("thinking", { id: choices[Number(slider.value)].id });
      });
      preview();
      const ticks = el("div", "thinking-ticks");
      for (const [i, c] of choices.entries()) {
        const tick = button(c.label, () => {
          slider.value = String(i);
          preview();
          request("thinking", { id: c.id });
        }, "thinking-tick");
        tick.dataset.level = c.id;
        tick.title = c.label;
        tick.setAttribute("aria-pressed", String(i === Number(slider.value)));
        ticks.append(tick);
      }
      content.append(value, slider, ticks);
      if (s.thinkingBudgetSupported) {
        const limits = s.thinkingBudgetRange || [128, 24576], group = el("details", "thinking-budget"), budgetBody = el("div", "thinking-budget-body"), label = el("label", "", tr("固定思考预算（tokens）", "Fixed reasoning budget (tokens)")), budget = el("input", "budget-input");
        budget.type = "number";
        budget.min = String(limits[0]);
        budget.max = String(limits[1]);
        budget.step = "1";
        budget.value = String(s.thinkingBudget || limits[0]);
        budget.id = "thinking-budget";
        label.htmlFor = budget.id;
        const error = el("p", "budget-error");
        error.setAttribute("role", "alert");
        const apply = button(tr("应用预算", "Apply budget"), () => {
          if (getState().busy) return;
          if (!budget.checkValidity() || !Number.isInteger(Number(budget.value))) {
            error.textContent = tr("请输入 ", "Enter a whole number from ") + limits[0] + "–" + limits[1] + tr(" 的整数", "");
            return;
          }
          error.textContent = "";
          request("thinking_budget", { value: Number(budget.value) });
        }, "budget-apply");
        budgetBody.append(label, budget, el("p", "small-text", limits[0] + "–" + limits[1] + tr(" · 与等级互斥", " · use instead of a reasoning level")), apply, error);
        group.append(el("summary", "", tr("预算设置", "Budget settings")), budgetBody);
        content.append(group);
        group.addEventListener("toggle", position);
      }
      const note = el("p", "selection-note", tr("下一次任务生效", "Applies to the next task"));
      note.title = s.thinkingNote || "";
      content.append(note);
      requestAnimationFrame(position);
    }
    window.addEventListener("resize", position);
    return { open, close, isOpen, update, dispose() {
      var _a2;
      (_a2 = panel._canvasObserver) == null ? void 0 : _a2.disconnect();
      close(true);
      clearTimeout(finishTimer);
      anchor.removeEventListener("click", open);
      window.removeEventListener("resize", position);
      panel.remove();
    } };
  }
  function subagentNavigator(root, main, viewport, composerArea, getState, refresh, request, notify) {
    let selected = "", listOpen = false, lastList = "", returnScroll = null, lastSelected = "", parentScope = "";
    let press = null, held = false, suppressClick = false, holdTimer, positionOverride = null;
    const positions = /* @__PURE__ */ new Map();
    const dock = el("aside", "agent-dock");
    dock.setAttribute("aria-label", tr("子代理导航", "Subagent navigation"));
    const trigger = button("Agent", (event) => {
      if (suppressClick && event.detail !== 0) {
        event.preventDefault();
        suppressClick = false;
        return;
      }
      toggle(!listOpen);
    }, "agent-bubble", "layers"), count = el("span", "agent-count", "0");
    trigger.append(count);
    trigger.setAttribute("aria-expanded", "false");
    trigger.setAttribute("aria-controls", "agent-list");
    const panel = el("section", "agent-list-panel");
    panel.id = "agent-list";
    panel.hidden = true;
    panel.setAttribute("aria-label", tr("所有子代理", "All subagents"));
    const heading = el("header", "bubble-heading");
    heading.append(el("h2", "", tr("子代理", "Subagents")), iconButton(tr("收起子代理", "Hide subagents"), "x", () => toggle(false)));
    const list = el("div", "agent-list");
    panel.append(heading, list);
    dock.append(trigger, panel);
    root.append(dock);
    const banner = el("header", "subagent-banner"), back = button(tr("返回主对话", "Back to main conversation"), () => select(""), "subagent-back", "arrow-left-right"), title = el("div", "subagent-title"), name = el("strong"), status = el("span", "small-text");
    title.append(name, status);
    banner.append(back, title);
    banner.hidden = true;
    main.insertBefore(banner, viewport);
    const footer = el("div", "subagent-footer");
    footer.append(el("p", "small-text", tr("子代理任务由主代理派发，消息实时更新。", "The main agent delegates tasks. Messages update live.")), button(tr("返回主对话", "Back to main conversation"), () => select(""), "subagent-return", "message-square"));
    const stop = button(tr("停止主任务", "Stop main task"), () => request("stop"), "subagent-stop", "square");
    footer.append(stop);
    footer.hidden = true;
    main.append(footer);
    const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
    function dragBounds() {
      const container = root.getBoundingClientRect(), area = viewport.getBoundingClientRect(), bubble = trigger.getBoundingClientRect();
      if (!container.width || !area.height || !bubble.width) return null;
      const left = area.left - container.left + 10, right = Math.max(left, area.right - container.left - bubble.width - 10);
      const top = area.top - container.top + 8, bottom = Math.max(top, area.bottom - container.top - bubble.height - 8);
      return { left, right, top, bottom, container };
    }
    function coordinates(point, bounds) {
      const { top, bottom, left, right } = bounds;
      return { x: left + point.x * (right - left), y: top + point.y * (bottom - top) };
    }
    function moveBubble(x, y) {
      dock.style.left = x + "px";
      dock.style.top = y + "px";
      dock.style.right = "auto";
    }
    function placeList() {
      if (!listOpen) return;
      const c = root.getBoundingClientRect(), b = dock.getBoundingClientRect(), pad = 10, gap = 9;
      const right = b.right - c.left + gap, left = b.left - c.left - panel.offsetWidth - gap;
      const x = right + panel.offsetWidth <= c.width - pad ? right : left >= pad ? left : clamp(right, pad, c.width - panel.offsetWidth - pad);
      panel.style.left = x - (b.left - c.left) + "px";
      panel.style.top = clamp(b.top - c.top, pad, c.height - panel.offsetHeight - pad) - (b.top - c.top) + "px";
      syncCodexCanvas(root);
    }
    function placeBubble() {
      if (held) return;
      const bounds = dragBounds();
      if (!bounds) return;
      const saved = positionOverride || getState().bubblePosition;
      const { x, y } = saved ? coordinates(saved, bounds) : coordinates({ x: 1, y: 0.08 }, bounds);
      moveBubble(x, y);
      placeList();
    }
    function finishPress(event, cancelled = false) {
      if (!press || event.pointerId !== press.id) return;
      clearTimeout(holdTimer);
      if (held && press.point && !cancelled && press.moved) {
        positionOverride = press.point;
        request("bubble_position", press.point);
      }
      press = null;
      held = false;
      delete dock.dataset.dragging;
      placeBubble();
    }
    trigger.addEventListener("pointerdown", (event) => {
      var _a2;
      if (event.button !== 0 || event.isPrimary === false || trigger.disabled || press) return;
      suppressClick = false;
      const bounds = dragBounds();
      if (!bounds) return;
      const b = dock.getBoundingClientRect();
      press = { id: event.pointerId, x: event.clientX, y: event.clientY, dx: event.clientX - b.left, dy: event.clientY - b.top };
      (_a2 = trigger.setPointerCapture) == null ? void 0 : _a2.call(trigger, event.pointerId);
      holdTimer = setTimeout(() => {
        if (!press) return;
        held = true;
        suppressClick = true;
        dock.dataset.dragging = "true";
        if (listOpen) toggle(false);
        request("bubble_hold");
      }, 450);
    });
    trigger.addEventListener("pointermove", (event) => {
      if (!press || press.id !== event.pointerId) return;
      const distance = Math.hypot(event.clientX - press.x, event.clientY - press.y);
      if (!held) {
        if (distance > 10) {
          clearTimeout(holdTimer);
          suppressClick = true;
        }
        return;
      }
      const b = dragBounds();
      if (!b) return;
      const x = clamp(event.clientX - press.dx - b.container.left, b.left, b.right);
      const y = clamp(event.clientY - press.dy - b.container.top, b.top, b.bottom);
      press.moved = press.moved || distance > 3;
      press.point = { x: (x - b.left) / (b.right - b.left || 1), y: (y - b.top) / (b.bottom - b.top || 1) };
      moveBubble(x, y);
    });
    trigger.addEventListener("pointerup", (event) => finishPress(event));
    trigger.addEventListener("pointercancel", (event) => finishPress(event, true));
    trigger.addEventListener("lostpointercapture", (event) => finishPress(event, true));
    trigger.addEventListener("contextmenu", (event) => event.preventDefault());
    const dockObserver = typeof ResizeObserver === "function" ? new ResizeObserver(placeBubble) : null;
    dockObserver == null ? void 0 : dockObserver.observe(viewport);
    window.addEventListener("resize", placeBubble);
    function select(id) {
      var _a2;
      if (id === selected) {
        toggle(false);
        return;
      }
      positions.set(selected, viewport.scrollTop);
      selected = id;
      returnScroll = (_a2 = positions.get(id)) != null ? _a2 : null;
      toggle(false);
      refresh();
      if (id) back.focus();
      else trigger.focus();
    }
    function toggle(open) {
      listOpen = open;
      panel.hidden = !open;
      trigger.setAttribute("aria-expanded", String(open));
      if (open) {
        renderList(true);
        placeList();
      }
      notify();
    }
    function renderList(force = false) {
      var _a2;
      const rows = getState().subagents || [], key2 = JSON.stringify([selected, rows.map((c) => [c.id, c.label, c.model, c.status, c.running])]);
      if (!force && lastList === key2) return;
      lastList = key2;
      const focused = (_a2 = document.activeElement) == null ? void 0 : _a2.dataset.child;
      list.replaceChildren();
      list.append(button(tr("主对话", "Main conversation"), () => select(""), "agent-row main-agent", "message-square"));
      for (const child of rows) {
        const row = button("", () => select(child.id), "agent-row");
        row.dataset.child = child.id;
        row.setAttribute("aria-label", child.label + " · " + child.status);
        row.setAttribute("aria-current", String(child.id === selected));
        const dot = el("span", "child-dot");
        dot.dataset.running = String(!!child.running);
        const detail = el("span", "child-detail");
        detail.append(el("strong", "", child.label || child.model || tr("子代理", "Subagents")), el("span", "small-text", child.status || tr("运行中", "Running")));
        row.append(dot, detail, icon("chevron-right"));
        list.append(row);
        if (focused === child.id) row.focus();
      }
      if (!rows.length) list.append(el("p", "empty-note", tr("尚未派发子代理。派发后将在这里显示。", "No subagents yet.")));
    }
    function outside(e) {
      if (listOpen && !dock.contains(e.target)) toggle(false);
    }
    function keydown(e) {
      if (e.key === "Escape" && listOpen) {
        e.preventDefault();
        toggle(false);
        trigger.focus();
      }
    }
    document.addEventListener("pointerdown", outside);
    document.addEventListener("keydown", keydown);
    function update() {
      var _a2, _b;
      const s = getState(), rows = s.subagents || [], scope = JSON.stringify([s.workspace, s.engine, s.session]);
      if (parentScope !== scope) {
        parentScope = scope;
        selected = "";
        lastSelected = "";
        lastList = "";
        returnScroll = null;
        positions.clear();
        if (listOpen) toggle(false);
      }
      if (selected && !rows.some((c) => c.id === selected)) {
        selected = "";
        returnScroll = (_a2 = positions.get("")) != null ? _a2 : null;
      }
      const child = rows.find((c) => c.id === selected);
      count.textContent = String(rows.length);
      trigger.title = tr("子代理 ", "Subagents ") + rows.length + tr(" · 运行中 ", " · Running ") + rows.filter((c) => c.running).length;
      trigger.dataset.running = String(rows.some((c) => c.running));
      trigger.disabled = !s.session;
      banner.hidden = !child;
      footer.hidden = !child;
      composerArea.hidden = !!child;
      root.dataset.childView = String(!!child);
      if (child) {
        name.textContent = child.label || child.model || tr("子代理", "Subagents");
        status.textContent = child.status || tr("运行中", "Running");
      }
      stop.hidden = !s.busy;
      stop.disabled = !!s.stopping;
      if (listOpen) renderList();
      placeBubble();
      if (lastSelected !== selected) {
        lastSelected = selected;
        viewport.scrollTop = 0;
        if (!s.reducedMotion && !matchMedia("(prefers-reduced-motion: reduce)").matches) (_b = viewport.animate) == null ? void 0 : _b.call(viewport, [{ opacity: 0.25 }, { opacity: 1 }], { duration: 170 });
      }
      return child;
    }
    return { update, get selected() {
      return selected;
    }, get isOpen() {
      return listOpen;
    }, restoreScroll() {
      if (returnScroll !== null) {
        viewport.scrollTop = returnScroll;
        returnScroll = null;
      }
    }, close() {
      if (listOpen) {
        toggle(false);
        return true;
      }
      if (selected) {
        select("");
        return true;
      }
      return false;
    }, dispose() {
      clearTimeout(holdTimer);
      dockObserver == null ? void 0 : dockObserver.disconnect();
      window.removeEventListener("resize", placeBubble);
      document.removeEventListener("pointerdown", outside);
      document.removeEventListener("keydown", keydown);
      dock.remove();
      banner.remove();
      footer.remove();
    } };
  }

  // agent/app/src/main/assets/agent/choices.js
  function choiceList(label, choices, selected, choose) {
    const list = el("div", "choice-list");
    list.setAttribute("role", "listbox");
    list.setAttribute("aria-label", label);
    list.id = "selection-options";
    for (const item of choices) {
      const node = button("", () => choose(item.id), "choice-option");
      node.setAttribute("role", "option");
      node.setAttribute("aria-selected", String(item.id === selected));
      node.dataset.value = item.id;
      node.disabled = item.disabled === true;
      node.tabIndex = -1;
      const mark = el("span", "choice-mark");
      mark.append(icon(item.icon || "sliders-horizontal"));
      const text = el("span", "choice-copy");
      text.append(el("span", "choice-title", item.label));
      if (item.detail) text.append(el("span", "choice-detail", item.detail));
      node.append(mark, text, icon(item.id === selected ? "check" : "circle"));
      list.append(node);
    }
    const enabled = () => Array.from(list.querySelectorAll("button:not(:disabled)"));
    const first = list.querySelector("[aria-selected=true]:not(:disabled)") || enabled()[0];
    if (first) first.tabIndex = 0;
    list.addEventListener("keydown", (event) => {
      if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
      const items = enabled();
      if (!items.length) return;
      event.preventDefault();
      const index = items.indexOf(document.activeElement);
      const next = event.key === "Home" ? 0 : event.key === "End" ? items.length - 1 : (Math.max(0, index) + (event.key === "ArrowDown" ? 1 : -1) + items.length) % items.length;
      for (const item of items) item.tabIndex = -1;
      items[next].tabIndex = 0;
      items[next].focus();
    });
    return list;
  }

  // agent/app/src/main/assets/agent/patterns.js
  var drawings = {
    codex: [
      "M12 19H29L40 30M12 53H29L40 42M120 19H103L92 30M120 53H103L92 42",
      "M49 14H83Q87 14 87 18V54Q87 58 83 58H49Q45 58 45 54V18Q45 14 49 14Z",
      "M57 6V14M75 6V14M57 58V66M75 58V66M4 36H26M106 36H128"
    ],
    gemini: [
      "M66 7L95 36L66 65L37 36Z",
      "M26 18L37 7M95 65L106 54M8 36H25M107 36H124",
      "M17 54H30V67M102 5V18H115M50 6L39 17M93 55L82 66"
    ],
    openclaude: [
      "M18 18H35M14 27H33M18 36H30M102 36H118M99 45H122M97 54H116",
      "M44 13H82L91 22V56H44ZM82 13V22H91",
      "M38 23V62H80M50 7H86L97 18V49"
    ],
    pi: [
      "M31 17H14V55H31M101 17H118V55H101",
      "M42 14H90M42 58H90M33 8H99M33 64H99",
      "M4 30H14M4 42H14M118 30H128M118 42H128M45 5V9M87 63V67"
    ]
  };
  function engineArtwork(profile) {
    const frame = el("div", "engine-art");
    frame.setAttribute("aria-hidden", "true");
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    for (const [name, value] of Object.entries({ viewBox: "0 0 132 72", fill: "none", class: "engine-pattern", focusable: "false", "stroke-width": "1.2", "stroke-linecap": "round", "stroke-linejoin": "round" })) svg.setAttribute(name, value);
    for (const [index, d] of drawings[profile.id].entries()) {
      const path = document.createElementNS(svg.namespaceURI, "path");
      path.setAttribute("d", d);
      path.setAttribute("pathLength", "1");
      path.setAttribute("class", "pattern-stroke pattern-" + index);
      svg.append(path);
    }
    frame.append(svg, icon(profile.mark));
    return frame;
  }
  function drawBackdrop(canvas, root, appearance) {
    const width = canvas.clientWidth, height = canvas.clientHeight;
    if (width < 1 || height < 1) return;
    const ratio = Math.min(2, window.devicePixelRatio || 1);
    const key2 = [width, height, ratio, appearance == null ? void 0 : appearance.leftColor, appearance == null ? void 0 : appearance.rightColor, appearance == null ? void 0 : appearance.wholeInterface].join(":");
    if (canvas.dataset.drawing === key2) return;
    const pen = canvas.getContext("2d");
    if (!pen) return;
    canvas.dataset.drawing = key2;
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
    pen.setTransform(ratio, 0, 0, ratio, 0, 0);
    const theme = getComputedStyle(root);
    const ink = theme.getPropertyValue("--fg").trim(), accent = theme.getPropertyValue("--accent").trim();
    const alternate = theme.getPropertyValue("--pattern-secondary").trim();
    const dark = document.body.dataset.engine === "pi" || root.dataset.nebulaAll === "true";
    pen.fillStyle = ink;
    pen.globalAlpha = dark ? 0.17 : 0.12;
    for (let y = 24; y < height; y += 42) for (let x = 22; x < width; x += 42) pen.fillRect(x, y, 1, 1);
    pen.lineWidth = 1;
    pen.lineJoin = "round";
    pen.lineCap = "round";
    for (let index = 0; index < 7; index++) {
      const side = index % 2 === 0 ? 1 : -1, offset = side === 1 ? 0 : width;
      const extent = Math.min(82 + 13 * (index % 3), width * 0.24);
      const y = (index + 1) * height / 8, turn = Math.min(23, height / 15) * (index % 3 === 0 ? -1 : 1);
      const points = [[0, y], [extent * 0.44, y], [extent * 0.69, y + turn], [extent, y + turn]];
      pen.strokeStyle = index % 3 === 0 ? alternate : accent;
      pen.globalAlpha = dark ? 0.18 : 0.16;
      pen.beginPath();
      points.forEach(([x, y2], step) => {
        if (step) pen.lineTo(offset + x * side, y2);
        else pen.moveTo(offset + x * side, y2);
      });
      pen.stroke();
      const [endX, endY] = points[3];
      pen.strokeRect(offset + endX * side - 2, endY - 2, 4, 4);
      pen.globalAlpha = dark ? 0.11 : 0.08;
      pen.beginPath();
      pen.moveTo(offset, y + 9);
      pen.lineTo(offset + extent * 0.23 * side, y + 9);
      pen.lineTo(offset + extent * 0.47 * side, y + 9 + turn);
      pen.stroke();
    }
  }

  // agent/app/src/main/assets/agent/components.js
  var quickTitles = { model: tr("选择模型", "Choose model"), collaboration: tr("子代理协作模式", "Subagent collaboration"), engine: tr("底层引擎", "Agent engine"), mode: tr("操作权限", "Task mode"), attachment: tr("添加附件", "Add attachments") };
  var setupLabels = {
    WORKSPACE: tr("准备工作区与会话", "Preparing workspace and conversation"),
    TERMUX: tr("准备内置 Termux", "Preparing the built-in Termux runtime"),
    NODE: tr("安装 Agent 运行环境", "Installing the Agent runtime"),
    BRIDGE: tr("启动 Python 工具桥接", "Starting the Python tool bridge"),
    ENGINES: tr("检查底层引擎", "Checking agent engines"),
    READY: tr("本地引擎检查通过（未测试模型接口）", "Local engines verified. Model access has yet to be checked."),
    FAILED: tr("环境准备失败", "Environment setup failed"),
    CANCELLED: tr("已停止环境准备", "Environment setup stopped")
  };
  var conversationTitle = (title) => !title || title === "新会话" ? tr("新会话", "New conversation") : title;
  function createPage(profile, send, local, toolExpansion2) {
    const preparedSelections = /* @__PURE__ */ new Map();
    let bubble, childNav;
    let state = {}, draftTimer, follow = true, lastHistory = "", lastTasks = "", lastChanges = "", lastFiles = "", lastAck, lastPrompt;
    let drawerMode = "", previousFocus = null, infoTab = "overview", navPinned = profile.navigation, infoPinned = profile.inspector, layout = "";
    const wide = matchMedia("(min-width: 1080px) and (min-height: 520px) and (orientation: landscape)");
    const dual = matchMedia("(min-width: 1360px) and (min-height: 520px) and (orientation: landscape)");
    const root = el("main", "agent-layout " + profile.id);
    root.dataset.empty = "true";
    const sidebarHost = el("aside", "sidebar-host"), main = el("section", "main-column"), infoHost = el("aside", "info-host");
    const backdrop = el("canvas", "conversation-backdrop");
    backdrop.setAttribute("aria-hidden", "true");
    main.append(backdrop);
    function paintBackdrop() {
      if (root.isConnected) drawBackdrop(backdrop, root, state.codexAppearance);
    }
    const backdropObserver = typeof ResizeObserver === "function" ? new ResizeObserver(paintBackdrop) : null;
    backdropObserver == null ? void 0 : backdropObserver.observe(main);
    const modal = el("aside", "utility-dialog");
    modal.hidden = true;
    modal.setAttribute("aria-label", tr("会话侧边栏", "Conversation sidebar"));
    modal.setAttribute("aria-hidden", "true");
    const modalBar = el("header", "dialog-bar"), modalTitle = el("h2");
    const sidebarBack = iconButton(tr("返回侧边栏", "Back to sidebar"), "chevron-left", () => openPanel("history"));
    sidebarBack.hidden = true;
    modalBar.append(sidebarBack, modalTitle, iconButton(tr("关闭侧边栏", "Close sidebar"), "x", closePanel));
    const modalBody = el("div", "dialog-body");
    modal.append(modalBar, modalBody);
    const routePage = el("section", "route-page");
    routePage.hidden = true;
    routePage.setAttribute("aria-label", tr("Agent 配置页面", "Agent settings"));
    routePage.id = "agent-config-page";
    routePage.tabIndex = -1;
    const routeBar = el("header", "route-bar"), routeTitle = el("h1");
    routeBar.append(iconButton(tr("返回对话", "Back to conversation"), "arrow-left", closePanel), routeTitle);
    const routeBody = el("div", "route-body");
    routePage.append(routeBar, routeBody);
    const quick = el("section", "quick-picker");
    quick.hidden = true;
    quick.id = "agent-quick-picker";
    quick.setAttribute("role", "dialog");
    quick.setAttribute("aria-labelledby", "quick-picker-title");
    const quickBar = el("header", "quick-picker-bar"), quickTitle = el("h2");
    quickTitle.id = "quick-picker-title";
    quickBar.append(quickTitle, iconButton(tr("关闭选项", "Close options"), "x", () => closeQuickPicker()));
    const quickBody = el("div", "quick-picker-body");
    quick.append(quickBar, quickBody);
    let quickMode = "", quickAnchor = null, quickFocus = null;
    const shade2 = el("div", "dialog-shade");
    shade2.hidden = true;
    const rail = el("nav", "navigation-rail");
    rail.setAttribute("aria-label", tr("Agent 导航", "Agent navigation"));
    const railBrand = el("div", "rail-brand");
    railBrand.append(icon(profile.mark));
    rail.append(
      railBrand,
      iconButton(tr("会话历史", "Conversation history"), "history", toggleHistory),
      iconButton(tr("新会话", "New conversation"), "plus", () => request("new")),
      iconButton(tr("工作信息", "Task details"), "panel-right", () => showInfo()),
      iconButton(tr("上下文文件", "Context files"), "paperclip", () => nativeRequest("context_files")),
      el("span", "rail-spacer"),
      iconButton(tr("模型设置", "Model settings"), "settings-2", () => nativeRequest("settings"))
    );
    root.append(rail, sidebarHost, main, infoHost, shade2, modal, routePage, quick);
    const request = (command, data = {}) => {
      if (command !== "draft") flushDraft();
      send(command, data);
    };
    function nativeRequest(command, data = {}) {
      closePanel();
      request(command, data);
    }
    function saveDraft() {
      local.text = input.value;
      local.dirty = true;
      clearTimeout(draftTimer);
      draftTimer = setTimeout(flushDraft, 250);
    }
    function flushDraft() {
      clearTimeout(draftTimer);
      if (local.dirty) {
        send("draft", { text: local.text || "" });
        local.dirty = false;
      }
    }
    const panelIsOpen = () => !modal.hidden || !routePage.hidden;
    function closePanel() {
      closeQuickPicker(false);
      bubble == null ? void 0 : bubble.close(true);
      if (!panelIsOpen()) return;
      modal.hidden = true;
      modal.setAttribute("aria-hidden", "true");
      routePage.hidden = true;
      shade2.hidden = true;
      drawerMode = "";
      setRouteActive(false);
      restorePanels();
      panelChanged();
      if (previousFocus == null ? void 0 : previousFocus.isConnected) previousFocus.focus();
    }
    function setRouteActive(active) {
      for (const node of [rail, sidebarHost, main, infoHost, root.querySelector(".agent-dock")].filter(Boolean)) {
        node.inert = active;
        if (active) node.setAttribute("aria-hidden", "true");
        else node.removeAttribute("aria-hidden");
      }
      root.dataset.routeOpen = String(active);
      if (active) {
        const focused = document.activeElement;
        if (focused && main.contains(focused)) focused.blur();
      }
    }
    function panelChanged() {
      send("panel", { id: panelIsOpen() || !quick.hidden || (bubble == null ? void 0 : bubble.isOpen()) || (childNav == null ? void 0 : childNav.selected) || (childNav == null ? void 0 : childNav.isOpen) ? "open" : "" });
      for (const node of root.querySelectorAll("[data-picker],.thinking-picker")) node.setAttribute("aria-expanded", String(node === thinking ? !!(bubble == null ? void 0 : bubble.isOpen()) : node.dataset.picker in quickTitles ? !quick.hidden && node.dataset.picker === quickMode : panelIsOpen() && node.dataset.picker === drawerMode));
    }
    const escapePanel = (event) => {
      if (event.key !== "Escape") return;
      if (!quick.hidden) {
        event.preventDefault();
        closeQuickPicker();
      } else if (panelIsOpen()) {
        event.preventDefault();
        closePanel();
      }
    };
    function closeQuickPicker(restoreFocus = true) {
      if (quick.hidden) return false;
      quick.hidden = true;
      quickMode = "";
      quickAnchor = null;
      panelChanged();
      if (restoreFocus && (quickFocus == null ? void 0 : quickFocus.isConnected)) quickFocus.focus();
      quickFocus = null;
      return true;
    }
    function positionQuickPicker() {
      if (quick.hidden || !(quickAnchor == null ? void 0 : quickAnchor.isConnected)) return;
      const anchor = quickAnchor.getBoundingClientRect();
      if (!anchor.width || !anchor.height) {
        closeQuickPicker(false);
        return;
      }
      const pad = 12, gap = 8, belowSpace = Math.max(0, innerHeight - pad - anchor.bottom - gap), aboveSpace = Math.max(0, anchor.top - pad - gap);
      quick.style.maxHeight = Math.max(96, Math.max(belowSpace, aboveSpace)) + "px";
      const width = quick.offsetWidth, height = quick.offsetHeight;
      const below = anchor.bottom + gap, above = anchor.top - height - gap;
      const top = quickMode === "attachment" && above >= pad ? above : below + height <= innerHeight - pad ? below : above >= pad ? above : Math.max(pad, Math.min(innerHeight - height - pad, below));
      quick.style.left = Math.max(pad, Math.min(innerWidth - width - pad, anchor.left)) + "px";
      quick.style.top = top + "px";
      syncCodexCanvas(root);
    }
    function openQuickPicker(kind, anchor) {
      if (state.busy || !state.session) return;
      if (!quick.hidden && quickMode === kind) {
        closeQuickPicker();
        return;
      }
      bubble == null ? void 0 : bubble.close(true);
      closeQuickPicker(false);
      if (!routePage.hidden) closePanel();
      quickMode = kind;
      quickAnchor = anchor;
      quickFocus = anchor || document.activeElement;
      quick.dataset.kind = kind;
      quickTitle.textContent = quickTitles[kind];
      quickBody.replaceChildren(selection(kind));
      quick.hidden = false;
      positionQuickPicker();
      panelChanged();
      requestAnimationFrame(() => {
        var _a2;
        if (quick.hidden || quickMode !== kind) return;
        positionQuickPicker();
        (_a2 = quickBody.querySelector("[aria-selected=true]:not(:disabled),button:not(:disabled)")) == null ? void 0 : _a2.focus();
      });
    }
    const quickOutside = (event) => {
      if (!quick.hidden && !quick.contains(event.target) && !(quickAnchor == null ? void 0 : quickAnchor.contains(event.target))) closeQuickPicker(false);
    };
    document.addEventListener("pointerdown", quickOutside);
    window.addEventListener("resize", positionQuickPicker);
    document.addEventListener("scroll", positionQuickPicker, true);
    document.addEventListener("keydown", escapePanel);
    function openPanel(kind, anchor) {
      if (kind in quickTitles) {
        openQuickPicker(kind, anchor);
        return;
      }
      closeQuickPicker(false);
      if (layout === "chat" && kind === "menu") kind = "history";
      bubble == null ? void 0 : bubble.close(true);
      if (panelIsOpen()) closePanel();
      previousFocus = document.activeElement;
      drawerMode = kind;
      modal.dataset.kind = kind;
      modal.dataset.side = String(layout === "chat");
      sidebarBack.hidden = layout !== "chat" || kind === "history";
      modalTitle.textContent = { history: tr("会话与工作区", "Conversations and workspaces"), menu: tr("会话选项", "Conversation options") }[kind] || "";
      if (kind === "history" || kind === "menu") {
        modalBody.replaceChildren(kind === "history" ? sidebar : menu);
        routePage.hidden = true;
        modal.hidden = false;
        modal.setAttribute("aria-hidden", "false");
        menuButton == null ? void 0 : menuButton.setAttribute("aria-expanded", "true");
        requestAnimationFrame(() => {
          var _a2;
          return (_a2 = modalBody.querySelector("button:not(:disabled),input:not(:disabled)")) == null ? void 0 : _a2.focus();
        });
      } else {
        modal.hidden = true;
        modal.setAttribute("aria-hidden", "true");
        routePage.hidden = false;
        routeTitle.textContent = { engine: tr("底层引擎", "Agent engine"), mode: tr("操作权限", "Task mode"), thinking: tr("思考等级", "Reasoning level"), info: tr("工作信息", "Task details") }[kind] || tr("设置", "Settings");
        routeBody.replaceChildren(kind === "info" ? inspector : selection(kind));
        setRouteActive(true);
        requestAnimationFrame(() => {
          var _a2;
          return (_a2 = routeBody.querySelector("[aria-selected=true]:not(:disabled),button:not(:disabled),input:not(:disabled)")) == null ? void 0 : _a2.focus();
        });
      }
      panelChanged();
    }
    function restorePanels() {
      if (drawerMode !== "history") sidebarHost.append(sidebar);
      if (drawerMode !== "info") infoHost.append(inspector);
      const navigation = layout !== "chat" && wide.matches && navPinned && (!infoPinned || dual.matches);
      root.dataset.navigation = String(navigation);
      root.dataset.inspector = String(layout !== "chat" && wide.matches && infoPinned);
      sidebarHost.hidden = !navigation;
      infoHost.hidden = !(layout !== "chat" && wide.matches && infoPinned);
      menuButton == null ? void 0 : menuButton.setAttribute("aria-expanded", String(navigation || drawerMode === "history"));
    }
    function toggleHistory() {
      if (layout === "chat") {
        if (!modal.hidden && drawerMode === "history") closePanel();
        else openPanel("history");
        return;
      }
      if (wide.matches) {
        navPinned = sidebarHost.hidden;
        if (navPinned && !dual.matches) infoPinned = false;
        restorePanels();
      } else openPanel("history");
    }
    function showInfo(tab = "overview") {
      infoTab = tab;
      renderTabs();
      openPanel("info");
    }
    const header = el("header", "topbar");
    const menuButton = iconButton(tr("打开会话历史", "Open conversation history"), "menu", toggleHistory);
    function picker(label, kind, symbol) {
      const node = button(label, (event) => openPanel(kind, event.currentTarget), kind + "-picker picker-button", symbol);
      node.dataset.picker = kind;
      node.setAttribute("aria-haspopup", "dialog");
      node.setAttribute("aria-expanded", "false");
      node.setAttribute("aria-controls", "agent-quick-picker");
      node.append(icon("chevron-down"));
      return node;
    }
    const engineSelect = picker(profile.label, "engine", profile.mark);
    engineSelect.setAttribute("aria-label", tr("底层引擎", "Agent engine"));
    const engineLabel = engineSelect;
    const engineChoices = [{ id: "codex", label: "Codex", icon: "code-xml" }, { id: "openclaude", label: "OpenClaude", icon: "message-square" }, { id: "gemini", label: "Gemini CLI", icon: "sparkles" }, { id: "pi", label: "Pi", icon: "terminal" }];
    const modeChoices = [{ id: "ask", label: tr("问答", "Ask"), detail: tr("只读", "Read only"), icon: "message-square" }, { id: "plan", label: tr("规划", "Plan"), detail: tr("只读 · 任务清单", "Read only · task list"), icon: "list-checks" }, { id: "execute", label: tr("执行", "Execute"), detail: tr("暂存修改 · 命令需审批", "Stage edits · commands need approval"), icon: "wrench" }];
    const collaborationChoices = [{ id: "extreme", label: tr("极端", "Extreme"), detail: tr("配合 Ultra · 主代理首批派发，子代理递归协作", "Use with Ultra · subagents may delegate further"), icon: "sparkles" }, { id: "team", label: tr("团队", "Team"), detail: tr("主代理只编排整合，全部执行委派子代理", "The main agent coordinates and delegates execution"), icon: "layers" }, { id: "assist", label: tr("辅助", "Assist"), detail: tr("根据任务大小适当委派", "Delegate based on task size"), icon: "sliders-horizontal" }, { id: "off", label: tr("关闭", "Off"), detail: tr("不注入协作提示词，代理工具保留", "Keep agent tools available without collaboration prompts"), icon: "circle" }];
    const attachmentOptions = [
      [tr("图片", "Images"), "image", "pick_images"],
      [tr("文件", "Files"), "file", "pick_documents"],
      [tr("工作区文件", "Workspace files"), "folder", "context_files"]
    ];
    function attachmentSelection() {
      const key2 = String(state.imagesSupported !== false), cached = preparedSelections.get("attachment");
      if ((cached == null ? void 0 : cached.key) === key2) return cached.node;
      const node = el("div", "attachment-options");
      for (const [label, symbol, command] of attachmentOptions) {
        const item = button(label, () => nativeRequest(command), "attachment-option", symbol);
        item.dataset.value = command;
        if (command === "pick_images" && state.imagesSupported === false) {
          item.disabled = true;
          item.title = tr("当前模型通道不支持图片", "This model connection does not support images");
          item.setAttribute("aria-label", label + tr("：当前模型通道不支持", ": unsupported by this model connection"));
        }
        node.append(item);
      }
      preparedSelections.set("attachment", { key: key2, node });
      return node;
    }
    function selection(kind) {
      if (kind === "attachment") return attachmentSelection();
      const choices = kind === "model" ? (state.models || []).map((id) => ({ id, label: id, detail: id === state.model ? tr("当前模型", "Current model") : tr("下一次任务使用", "Used for the next task") })) : kind === "collaboration" ? collaborationChoices : kind === "engine" ? engineChoices.map((item) => {
        const availability2 = (state.engines || []).find((engine) => engine.id === item.id);
        return { ...item, detail: !(availability2 == null ? void 0 : availability2.checked) ? tr("尚未检查", "Not checked") : availability2.available ? tr("本地资源可用", "Local resources available") : availability2.reason || tr("未安装", "Not installed") };
      }) : kind === "mode" ? modeChoices : state.thinkingChoices || [{ id: "auto", label: tr("自动", "Auto"), detail: tr("引擎默认", "Engine default") }];
      const value = kind === "model" ? state.model : kind === "collaboration" ? state.collaborationMode || "assist" : kind === "engine" ? state.engine : kind === "mode" ? state.mode : state.thinkingLevel || "auto";
      const cacheKey = JSON.stringify([choices, value, state.thinkingNote || "", state.modelSelectable]);
      const cached = preparedSelections.get(kind);
      if ((cached == null ? void 0 : cached.key) === cacheKey) return cached.node;
      const container = el("div", "selection-content");
      if (kind !== "model" || state.modelSelectable !== false) container.append(choiceList({ model: tr("选择模型", "Choose model"), collaboration: tr("子代理协作模式", "Subagent collaboration"), engine: tr("底层引擎", "Agent engine"), mode: tr("操作权限", "Task mode"), thinking: tr("思考等级", "Reasoning level") }[kind], choices, value, (id) => {
        if (state.busy) return;
        if (["model", "engine", "mode", "collaboration"].includes(kind)) closeQuickPicker();
        else closePanel();
        request(kind === "thinking" ? "thinking" : kind, kind === "model" ? { text: id } : { id });
      }));
      if (kind === "model") {
        if (state.modelSelectable === false) container.append(el("p", "selection-note", tr("免费服务的模型由账号方案决定。", "The account plan selects the model for the free service.")));
        else if (!choices.length) container.append(el("p", "selection-note", tr("尚未配置模型。支持逗号分隔，也可以从云端获取列表。", "Add models separated by commas, or fetch the list from your provider.")));
        container.append(button(tr("管理 API / 获取云端模型", "Manage API and fetch models"), () => nativeRequest("settings"), "menu-action", "settings-2"));
      }
      if (kind === "thinking" && ["gemini", "openclaude"].includes(state.engine)) container.replaceChildren(button(tr("思考预算 / 思考强度 / 手动填入", "Set a reasoning level or budget"), () => nativeRequest("settings"), "menu-action", "sliders-horizontal"));
      if (kind === "thinking" && state.thinkingNote) container.append(el("p", "selection-note", state.thinkingNote));
      preparedSelections.set(kind, { key: cacheKey, node: container });
      return container;
    }
    const topTitle = el("span", "top-title");
    const fresh = iconButton(tr("新会话", "New conversation"), "plus", () => request("new"));
    const more = iconButton(tr("会话选项", "Conversation options"), "ellipsis", () => openPanel("menu"));
    const logs = button(tr("日志", "Logs"), () => request("diagnostics"), "icon-button", "terminal");
    const topStop = iconButton(tr("停止任务", "Stop task"), "square", () => request("stop"), "top-stop");
    topStop.hidden = true;
    header.append(menuButton, engineLabel, topTitle, logs, fresh, more);
    const workspaceBar = el("div", "workspace-bar");
    const workspace = button(tr("工作区", "Workspace"), () => nativeRequest("workspaces"), "workspace-button", "folder");
    const workspaceName = workspace.querySelector("span");
    const workInfo = button(tr("工作信息", "Task details"), () => showInfo(), "work-info", "panel-right");
    const changesLink = button(tr("改动", "Changes"), () => nativeRequest("review"), "changes-link", "git-pull-request");
    const contextBadge = button(tr("上下文", "Context"), () => showInfo("context"), "context-badge", "layers");
    workspaceBar.append(workspace, contextBadge, workInfo, changesLink);
    const alert = el("div", "notice");
    alert.setAttribute("role", "status");
    const alertText = el("span");
    const retry = button(tr("连接环境", "Connect runtime"), () => request("connect"), "text-button");
    alert.append(alertText, retry);
    alert.hidden = true;
    const viewport = el("section", "conversation-viewport");
    viewport.setAttribute("aria-label", tr("对话记录", "Conversation"));
    const welcome = el("div", "welcome");
    if (profile.id !== "codex") welcome.append(engineArtwork(profile));
    welcome.append(el("h1", "", profile.greeting));
    if (profile.starters.length) {
      const starters = el("div", "task-starters");
      for (const [label, symbol, text] of profile.starters) {
        starters.append(button(label, () => {
          if (!input.value.trim()) {
            input.value = text;
            saveDraft();
            fitInput();
          }
          input.focus();
        }, "starter", symbol));
      }
      welcome.append(starters);
    }
    const welcomeWorkspace = el("p", "welcome-workspace");
    welcome.append(welcomeWorkspace);
    const messages = el("div", "messages");
    const renderMessages = createMessageRenderer(messages, request, void 0, toolExpansion2);
    viewport.append(welcome, messages);
    const jump = button(tr("回到最新", "Jump to latest"), () => {
      follow = true;
      viewport.scrollTop = viewport.scrollHeight;
      jump.hidden = true;
    }, "jump-latest", "arrow-down");
    jump.hidden = true;
    viewport.addEventListener("scroll", () => {
      follow = viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight < 72;
      jump.hidden = follow;
    }, { passive: true });
    const composerArea = el("div", "composer-area");
    const statusLine = el("div", "status-line");
    statusLine.setAttribute("role", "status");
    const statusDot = el("span", "status-dot"), statusText = el("span");
    statusLine.append(statusDot, statusText);
    const composer = el("form", "composer");
    const project = button(tr("选择工作区", "Choose workspace"), () => nativeRequest("workspaces"), "project-strip", "folder");
    const projectName = project.querySelector("span");
    project.append(icon("chevron-down"));
    project.hidden = true;
    const chips = el("div", "context-chips");
    chips.hidden = true;
    const input = el("textarea", "task-input");
    input.rows = 1;
    input.maxLength = 12e3;
    input.placeholder = profile.placeholder;
    input.setAttribute("aria-label", tr("任务描述", "Task description"));
    input.value = local.text || "";
    function fitInput() {
      input.rows = Math.min(5, Math.max(1, input.value.split("\n").length));
      const available = availability();
      submit.disabled = !!state.busy || !state.session || !(input.value.trim() || (state.attachments || []).length) || (available == null ? void 0 : available.checked) === true && available.available === false;
    }
    input.addEventListener("input", () => {
      saveDraft();
      fitInput();
    });
    input.addEventListener("blur", flushDraft);
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        if (composer.requestSubmit) composer.requestSubmit();
        else submit.click();
      }
    });
    const controls = el("div", "composer-controls");
    const attach = iconButton(tr("添加附件", "Add attachments"), "plus", (event) => openQuickPicker("attachment", event.currentTarget), "context-picker");
    attach.dataset.picker = "attachment";
    attach.setAttribute("aria-haspopup", "dialog");
    attach.setAttribute("aria-expanded", "false");
    attach.setAttribute("aria-controls", quick.id);
    const mode = picker(tr("执行", "Execute"), "mode");
    mode.setAttribute("aria-label", tr("操作权限", "Task mode"));
    const modeLabel = mode;
    const model = picker(tr("配置模型", "Configure model"), "model");
    model.classList.add("model-selector");
    const modelText = model.querySelector("span");
    model.setAttribute("aria-label", tr("选择模型", "Choose model"));
    const thinking = button(tr("自动", "Auto"), () => {
    }, "thinking-picker picker-button", "sliders-horizontal");
    thinking.setAttribute("aria-haspopup", "dialog");
    thinking.setAttribute("aria-expanded", "false");
    thinking.setAttribute("aria-label", tr("思考等级", "Reasoning level"));
    thinking.title = tr("思考等级", "Reasoning level");
    let longTaskTimer, longTaskPointer = null, longTaskHeld = false, longTaskOrigin;
    const longTask = iconButton(tr("长任务模式：关闭；长按查看说明", "Long task mode: off. Long press for details"), "timer", (event) => {
      if (longTaskHeld) {
        event.preventDefault();
        longTaskHeld = false;
        return;
      }
      if (state.busy || !state.session) return;
      request("long_task", { id: state.longTaskMode ? "off" : "on" });
    }, "long-task-toggle");
    longTask.setAttribute("aria-pressed", "false");
    function clearLongTaskPress() {
      clearTimeout(longTaskTimer);
      longTaskPointer = null;
    }
    function showLongTaskInfo() {
      clearLongTaskPress();
      if (!state.session) return;
      input.blur();
      nativeRequest("long_task_info");
    }
    longTask.addEventListener("pointerdown", (event) => {
      if (event.button !== 0 || longTaskPointer !== null) return;
      longTaskHeld = false;
      longTaskPointer = event.pointerId;
      longTaskOrigin = { x: event.clientX, y: event.clientY };
      longTaskTimer = setTimeout(() => {
        longTaskHeld = true;
        showLongTaskInfo();
      }, 500);
    });
    longTask.addEventListener("pointermove", (event) => {
      if (event.pointerId === longTaskPointer && Math.hypot(event.clientX - longTaskOrigin.x, event.clientY - longTaskOrigin.y) > 10) clearLongTaskPress();
    });
    for (const eventName of ["pointerup", "pointercancel", "pointerleave"]) longTask.addEventListener(eventName, clearLongTaskPress);
    longTask.addEventListener("contextmenu", (event) => {
      event.preventDefault();
      clearLongTaskPress();
      if (!longTaskHeld) {
        longTaskHeld = true;
        showLongTaskInfo();
      }
    });
    longTask.addEventListener("keydown", (event) => {
      if (event.key === "F1") {
        event.preventDefault();
        showLongTaskInfo();
      }
    });
    const collaboration = picker(tr("协作", "Collaboration"), "collaboration", "layers");
    const submit = iconButton(tr("发送任务", "Send"), "arrow-up", () => {
    }, "send-button");
    submit.type = "submit";
    submit.disabled = true;
    const stop = iconButton(tr("停止任务", "Stop task"), "square", () => request("stop"), "send-button stop-button");
    stop.hidden = true;
    controls.append(attach, modeLabel, model, thinking, longTask, collaboration);
    const inputRow = el("div", "composer-input-row"), sendLane = el("div", "send-lane");
    sendLane.append(submit, stop);
    inputRow.append(input, sendLane);
    composer.append(project, chips, inputRow, controls);
    composerArea.append(statusLine, composer);
    composer.addEventListener("submit", (e) => {
      e.preventDefault();
      if (!submit.disabled) request("send", { text: input.value });
    });
    main.append(header, workspaceBar, alert, viewport, jump, composerArea);
    bubble = thinkingBubble(root, thinking, () => state, request, () => panelChanged());
    childNav = subagentNavigator(root, main, viewport, composerArea, () => state, () => {
      follow = true;
      update(state);
      panelChanged();
    }, request, panelChanged);
    const sidebar = el("div", "sidebar-content");
    sidebar.append(el("div", "sidebar-brand", profile.id === "pi" ? "Pi" : "Aster"));
    const newNav = button(tr("新会话", "New conversation"), () => {
      closePanel();
      request("new");
    }, "new-conversation", "plus");
    const workspaceNav = button(tr("工作区", "Workspace"), () => {
      closePanel();
      request("workspaces");
    }, "nav-link", "folder");
    const promptNav = button(tr("提示词", "Instructions"), () => showInfo("prompt"), "nav-link", "notebook-pen");
    const search = el("input", "history-search");
    search.placeholder = tr("搜索会话", "Search conversations");
    search.setAttribute("aria-label", tr("搜索会话", "Search conversations"));
    search.type = "search";
    const history = el("div", "history-list");
    search.addEventListener("input", () => renderHistory(true));
    const sidebarControls = el("div", "sidebar-controls");
    const footer = el("div", "sidebar-footer");
    const nativeSettings = button(tr("模型设置", "Model settings"), () => {
      closePanel();
      request("settings");
    }, "nav-link", "settings-2");
    const workMode = button(tr("切换工作模式", "Switch work mode"), () => {
      closePanel();
      request("work_mode");
    }, "nav-link", "arrow-left-right");
    footer.append(button(tr("环境安装设置", "Environment setup"), () => {
      closePanel();
      request("environment");
    }, "nav-link", "terminal"), nativeSettings, button(tr("浏览器", "Browser"), () => nativeRequest("browser"), "nav-link", "layers"), button(tr("设备权限", "Device permissions"), () => nativeRequest("device_permissions"), "nav-link", "settings-2"), workMode);
    footer.insertBefore(button(tr("界面配置", "Appearance"), () => nativeRequest("appearance"), "nav-link", "sparkles"), nativeSettings);
    sidebar.append(newNav, workspaceNav, promptNav, el("h2", "sidebar-heading", tr("最近会话", "Recent conversations")), search, history, footer);
    const inspector = el("div", "inspector-content");
    const sessionFacts = section(tr("当前会话", "Current conversation"), "session-facts"), modelFact = el("p", "session-model"), thinkingFact = el("p", "small-text");
    sessionFacts.append(modelFact, thinkingFact);
    const inspectorHeader = el("header", "inspector-header");
    inspectorHeader.append(el("h2", "", tr("工作信息", "Task details")), iconButton(tr("收起工作信息", "Hide task details"), "panel-right", () => {
      infoPinned = false;
      restorePanels();
    }));
    const tabs = el("div", "info-tabs");
    tabs.setAttribute("role", "tablist");
    const tabNodes = {}, tabContent = {};
    for (const [key2, label] of [["overview", tr("进度", "Progress")], ["context", tr("上下文", "Context")], ["prompt", tr("提示词", "Instructions")]]) {
      const b = button(label, () => {
        infoTab = key2;
        renderTabs();
      }, "info-tab");
      b.setAttribute("role", "tab");
      b.id = "tab-" + key2;
      b.setAttribute("aria-controls", "panel-" + key2);
      tabs.append(b);
      tabNodes[key2] = b;
      const panel = el("div", "info-tab-content");
      panel.setAttribute("role", "tabpanel");
      panel.id = "panel-" + key2;
      panel.setAttribute("aria-labelledby", b.id);
      tabContent[key2] = panel;
    }
    function renderTabs() {
      for (const key2 of Object.keys(tabNodes)) {
        tabNodes[key2].setAttribute("aria-selected", String(key2 === infoTab));
        tabNodes[key2].tabIndex = key2 === infoTab ? 0 : -1;
        tabContent[key2].hidden = key2 !== infoTab;
      }
    }
    tabs.addEventListener("keydown", (event) => {
      if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
      event.preventDefault();
      const keys = Object.keys(tabNodes), index = keys.indexOf(infoTab);
      infoTab = keys[event.key === "Home" ? 0 : event.key === "End" ? keys.length - 1 : (index + (event.key === "ArrowRight" ? 1 : -1) + keys.length) % keys.length];
      renderTabs();
      tabNodes[infoTab].focus();
    });
    const tasks = section(tr("任务进度", "Task progress"), "tasks-section"), taskList = el("ul", "task-list");
    tasks.append(taskList);
    const directory = section(tr("工作目录", "Working directory"));
    const directoryAction = button(tr("工作区", "Workspace"), () => request("workspaces"), "directory-action", "folder");
    directory.append(directoryAction);
    const changes = section(tr("修改审阅", "Review changes")), changeList = el("div", "change-list");
    const review = button(tr("审阅修改", "Review changes"), () => request("review"), "outline-button", "git-pull-request");
    changes.append(changeList, review);
    const contextOverview = section(tr("上下文", "Context"), "context-overview"), overviewFiles = el("div");
    contextOverview.append(overviewFiles, button(tr("查看上下文", "View context"), () => showInfo("context"), "outline-button", "layers"));
    tabContent.overview.append(sessionFacts, tasks, directory, contextOverview, changes);
    const context = section(tr("上下文用量", "Context usage")), usage = el("p", "context-usage"), meter = el("progress");
    meter.max = 1;
    meter.setAttribute("aria-label", tr("上下文用量", "Context usage"));
    const compactions = el("p", "small-text"), configuredWindow = el("p", "small-text context-configured"), measurement = el("p", "small-text context-measurement"), policyNote = el("p", "small-text context-policy");
    const contextSettings = button(tr("调整上下文窗口", "Set context window"), () => request("settings"), "outline-button", "settings-2");
    const compact = button(tr("压缩上下文", "Compact context"), () => request("compact"), "outline-button", "layers");
    const compactNote = el("p", "small-text");
    context.append(usage, meter, configuredWindow, measurement, policyNote, contextSettings, compactions, compact, compactNote);
    const files = section(tr("已选择的文件", "Selected files")), fileList = el("div", "context-files"), pickFiles = button(tr("选择文件", "Select files"), () => request("context_files"), "outline-button", "paperclip");
    files.append(fileList, pickFiles);
    const runtime = section(tr("运行环境", "Runtime")), runtimeText = el("p", "small-text"), connect = button(tr("连接环境", "Connect runtime"), () => request("connect"), "outline-button", "terminal");
    runtime.append(runtimeText, connect, button(tr("运行详情与诊断", "Runtime diagnostics"), () => request("diagnostics"), "outline-button", "terminal"));
    tabContent.context.append(context, files, runtime);
    const prompt = el("textarea", "prompt-input");
    prompt.rows = 9;
    prompt.maxLength = 8e3;
    prompt.setAttribute("aria-label", tr("工作指令", "Workspace instructions"));
    const savePrompt = button(tr("保存提示词", "Save instructions"), () => request("prompt", { text: prompt.value }), "primary-button");
    tabContent.prompt.append(el("p", "small-text", tr("这些指令会随工作区任务发送给当前引擎。", "These instructions are sent to the current engine with workspace tasks.")), prompt, savePrompt);
    inspector.append(inspectorHeader, tabs, ...Object.values(tabContent));
    const menu = el("div", "action-menu");
    const menuActions = [[tr("工作信息", "Task details"), "panel-right", () => showInfo()], [tr("上下文与压缩", "Context and compaction"), "layers", () => showInfo("context")], [tr("提示词", "Instructions"), "notebook-pen", () => showInfo("prompt")], [tr("修改审阅", "Review changes"), "git-pull-request", () => nativeRequest("review")], [tr("运行详情与诊断", "Runtime diagnostics"), "terminal", () => nativeRequest("diagnostics")], [tr("环境安装设置", "Environment setup"), "terminal", () => nativeRequest("environment")], [tr("界面配置", "Appearance"), "sparkles", () => nativeRequest("appearance")], [tr("模型与上下文设置", "Model and context settings"), "settings-2", () => nativeRequest("settings")], [tr("切换工作模式", "Switch work mode"), "arrow-left-right", () => nativeRequest("work_mode")]];
    for (const [label, symbol, action] of menuActions) menu.append(button(label, action, "menu-action", symbol));
    function applyLayout(next) {
      const value = next.uiLayout === "classic" ? "classic" : "chat";
      if (value === layout) return;
      closePanel();
      layout = value;
      root.dataset.uiLayout = value;
      if (value === "chat") {
        header.replaceChildren(menuButton, model, thinking, topStop, fresh);
        inputRow.insertBefore(attach, input);
        sidebarControls.replaceChildren(engineSelect, mode, longTask, collaboration);
        sidebar.insertBefore(sidebarControls, workspaceNav);
        sidebar.insertBefore(menu, footer);
        fresh.replaceChildren(icon("notebook-pen"));
        input.placeholder = tr("询问 ", "Ask ") + profile.label + "…";
      } else {
        header.replaceChildren(menuButton, engineLabel, topTitle, logs, fresh, more);
        controls.replaceChildren(attach, mode, model, thinking, longTask, collaboration);
        sidebarControls.remove();
        menu.remove();
        fresh.replaceChildren(icon("plus"));
        input.placeholder = profile.placeholder;
      }
      restorePanels();
      requestAnimationFrame(() => {
        positionJump();
        syncCodexCanvas(root);
      });
    }
    function availability() {
      return (state.engines || []).find((e) => e.id === state.engine);
    }
    function renderHistory(force = false) {
      const key2 = JSON.stringify(state.history || []);
      if (!force && key2 === lastHistory) return;
      lastHistory = key2;
      history.replaceChildren();
      const query = search.value.trim().toLocaleLowerCase();
      const entries = (state.history || []).filter((e) => e.title.toLocaleLowerCase().includes(query));
      for (const entry of entries) {
        const title = conversationTitle(entry.title);
        const row = el("div", "history-row");
        const b = button(title, () => {
          closePanel();
          request("history", { id: entry.id });
        }, "history-link", "message-square");
        b.dataset.session = entry.id;
        b.title = title;
        const remove = iconButton(tr("删除会话 ", "Delete conversation ") + title, "trash-2", () => request("delete", { id: entry.id }), "history-delete");
        b.disabled = !!state.busy;
        remove.disabled = !!state.busy;
        row.append(b, remove);
        history.append(row);
      }
      if (!entries.length) history.append(el("p", "empty-note", query ? tr("没有匹配的会话", "No matching conversations") : tr("暂无历史会话", "No conversation history")));
    }
    function update(next) {
      var _a2, _b, _c, _d;
      state = next;
      root.dataset.reducedMotion = String(!!state.reducedMotion);
      applyLayout(state);
      applyCodexAppearance(root, state);
      paintBackdrop();
      if (!quick.hidden) {
        if (state.busy || !state.session) closeQuickPicker();
        else {
          const content = selection(quickMode);
          if (quickBody.firstChild !== content) {
            const focused = quickBody.contains(document.activeElement) ? (_a2 = document.activeElement) == null ? void 0 : _a2.dataset.value : null;
            quickBody.replaceChildren(content);
            if (focused) (_b = Array.from(quickBody.querySelectorAll("[data-value]")).find((node) => node.dataset.value === focused)) == null ? void 0 : _b.focus();
          }
          positionQuickPicker();
        }
      }
      if (!routePage.hidden && drawerMode === "thinking") routeBody.replaceChildren(selection(drawerMode));
      bubble.update();
      const child = childNav.update();
      const displayMessages = child ? child.messages || [] : state.messages || [];
      const empty = !displayMessages.length;
      root.dataset.empty = String(empty);
      root.dataset.busy = String(!!state.busy);
      welcome.hidden = !empty || !!child;
      messages.hidden = empty;
      topTitle.textContent = (child == null ? void 0 : child.label) || conversationTitle(state.title);
      engineSelect.querySelector(".button-label").textContent = profile.label;
      workspaceName.textContent = state.workspaceName || tr("选择工作区", "Choose workspace");
      projectName.textContent = state.workspaceName || tr("选择工作区", "Choose workspace");
      welcomeWorkspace.textContent = state.workspaceName || "";
      workspace.title = state.workspaceName || tr("选择工作区", "Choose workspace");
      const selectedThinking = (state.thinkingChoices || []).find((item) => item.id === (state.thinkingLevel || "auto"));
      const thinkingLabel = state.engine === "codex" ? state.thinkingLevel === "ultra" ? "Ultra" : String(state.thinkingLevel || "auto").toLowerCase() : state.thinkingLabel || (selectedThinking == null ? void 0 : selectedThinking.label) || tr("自动", "Auto");
      thinking.querySelector(".button-label").textContent = thinkingLabel;
      thinking.dataset.engine = state.engine || "";
      thinking.dataset.ultra = String(state.engine === "codex" && state.thinkingLevel === "ultra");
      thinking.dataset.explicit = String((state.thinkingLevel || "auto") !== "auto");
      thinking.title = tr("思考等级 · ", "Reasoning level · ") + thinkingLabel;
      thinking.setAttribute("aria-label", thinking.title);
      collaboration.title = tr("子代理协作模式：", "Subagent collaboration: ") + (((_c = collaborationChoices.find((c) => c.id === (state.collaborationMode || "assist"))) == null ? void 0 : _c.label) || tr("辅助", "Assist"));
      collaboration.setAttribute("aria-label", collaboration.title);
      collaboration.disabled = !!state.busy || !state.session;
      longTask.setAttribute("aria-pressed", String(!!state.longTaskMode));
      longTask.title = tr("长任务模式：", "Long task mode: ") + (state.longTaskMode ? tr("开启 · 无人守候", "on · unattended") : tr("关闭 · 优先提问", "off · ask first")) + tr("；长按查看说明", ". Long press for details");
      longTask.setAttribute("aria-label", longTask.title);
      longTask.setAttribute("aria-disabled", String(!!state.busy || !state.session));
      if (!state.session) clearLongTaskPress();
      thinkingFact.textContent = tr("思考 · ", "Reasoning · ") + thinkingLabel;
      modelFact.textContent = state.model || tr("未配置模型", "No model configured");
      const knownContext = ["estimate", "hybrid", "engine"].includes(state.contextSource);
      contextBadge.querySelector(".button-label").textContent = knownContext ? Math.round(100 * (state.tokens || 0) / Math.max(1, state.window || 1)) + "%" : tr("上下文", "Context");
      contextBadge.title = knownContext ? tr("最近上下文用量", "Latest context usage") : tr("尚无有效用量报告", "No usage report yet");
      directoryAction.querySelector("span").textContent = state.workspaceName || tr("尚未选择", "Not selected");
      const fileCount = (state.contextFiles || []).length;
      workInfo.querySelector("span").textContent = tr("工作信息", "Task details");
      changesLink.querySelector("span").textContent = tr("改动 ", "Changes ") + (state.changes || []).length;
      changesLink.hidden = !(state.changes || []).length;
      workInfo.hidden = false;
      const available = availability();
      const warning = state.saveError || state.runtimeError || ((available == null ? void 0 : available.checked) && !available.available ? available.reason : "");
      alertText.textContent = warning;
      alert.hidden = !warning;
      retry.hidden = !!state.saveError;
      retry.disabled = !!state.busy;
      statusText.textContent = state.approval ? tr("等待命令审批", "Waiting for command approval") : state.stopping ? tr("正在停止…", "Stopping…") : state.busy ? state.running ? state.status : tr("处理中…", "Working…") : state.status;
      statusLine.hidden = !state.session || !state.busy && (state.status === "准备就绪" || !state.status);
      statusDot.classList.toggle("active", !!state.busy);
      mode.querySelector(".button-label").textContent = ((_d = modeChoices.find((item) => item.id === state.mode)) == null ? void 0 : _d.label) || tr("执行", "Execute");
      modelText.textContent = state.model || tr("配置模型", "Configure model");
      model.title = state.model || tr("配置模型", "Configure model");
      if (state.busy && ["engine", "mode", "thinking"].includes(drawerMode)) closePanel();
      for (const button2 of rail.querySelectorAll("button")) button2.disabled = (!!state.busy || !state.session) && [tr("新会话", "New conversation"), tr("上下文文件", "Context files"), tr("模型设置", "Model settings")].includes(button2.getAttribute("aria-label"));
      for (const node of [engineSelect, fresh, workspace, project, newNav, workspaceNav, mode, model, thinking, attach, nativeSettings, workMode, review, pickFiles, savePrompt, directoryAction, connect, contextSettings]) node.disabled = !!state.busy || !state.session;
      review.disabled = review.disabled || !(state.changes || []).length;
      changes.hidden = !(state.changes || []).length;
      stop.hidden = !state.busy;
      submit.hidden = !!state.busy;
      stop.disabled = !!state.stopping;
      topStop.hidden = !state.busy || layout !== "chat";
      topStop.disabled = !!state.stopping;
      for (const b of menu.querySelectorAll("button")) b.disabled = !!state.busy && [tr("修改审阅", "Review changes"), tr("模型与上下文设置", "Model and context settings"), tr("切换工作模式", "Switch work mode")].includes(b.textContent);
      if (lastAck !== void 0 && next.acceptance !== lastAck && input.value.trim() === next.acceptedText) {
        input.value = "";
        local.text = "";
        local.dirty = false;
      }
      lastAck = next.acceptance;
      fitInput();
      requestAnimationFrame(positionJump);
      if (lastPrompt !== state.prompt) {
        if (document.activeElement !== prompt) prompt.value = state.prompt || "";
        lastPrompt = state.prompt;
      }
      const source = state.contextSource || "unknown", known = ["estimate", "hybrid", "engine"].includes(source);
      const labels = { estimate: tr("估算用量", "Estimated usage"), hybrid: tr("usage + 尾部估算", "Reported usage + estimated tail"), engine: tr("引擎报告用量", "Reported by engine") };
      usage.textContent = known ? labels[source] + tr("：", ": ") + (state.tokens || 0).toLocaleString(uiLocale()) + " / " + (state.window || 0).toLocaleString(uiLocale()) + " tokens" : tr("尚无有效用量报告", "No usage report yet");
      meter.hidden = !known;
      meter.value = Math.min(1, (state.tokens || 0) / Math.max(1, state.window || 1));
      configuredWindow.textContent = tr("已保存窗口：", "Configured window: ") + (state.configuredWindow || state.window || 0).toLocaleString(uiLocale()) + tr(" tokens · 下一次任务使用", " tokens · applies to the next task");
      measurement.textContent = known ? tr("这是最近一次采样，不是实时账单。", "Latest sample. Live billing totals may differ.") + (state.contextUpdatedAt ? tr(" 更新于 ", " Updated at ") + new Date(state.contextUpdatedAt).toLocaleTimeString(uiLocale()) : "") : tr("开始任务后显示估算或原生报告；未测量不等于已使用 0 tokens。", "Usage appears once a task produces a report.");
      policyNote.textContent = state.contextPolicy || "";
      compactions.textContent = tr("已压缩 ", "Compactions: ") + (state.compactions || 0) + tr(" 次", "");
      compact.disabled = !!state.busy || empty || state.compactSupported === false;
      compactNote.textContent = state.compactSupported === false ? tr("此引擎暂不支持手动压缩。", "This engine currently does not support manual compaction.") : "";
      compactNote.hidden = !compactNote.textContent;
      runtimeText.textContent = (state.runtime || tr("环境未连接", "Runtime disconnected")) + " · " + (setupLabels[state.setupPhaseId] || state.setupPhase || tr("尚未检查", "Not checked")) + tr("\n构建底层：", "\nEngine: ") + profile.label + " " + (state.engineVersion || "") + tr("；资源可用不等于模型请求成功。", ". Local resources are checked independently of model requests.");
      const messageScope = JSON.stringify([state.workspace || "", state.engine || "", state.session || "", (child == null ? void 0 : child.id) || ""]);
      if (renderMessages(messageScope, displayMessages, (child == null ? void 0 : child.label) || profile.label)) {
        if (follow) requestAnimationFrame(() => {
          viewport.scrollTop = viewport.scrollHeight;
          childNav.restoreScroll();
        });
        else jump.hidden = false;
      }
      renderHistory();
      for (const b of history.querySelectorAll("button")) {
        b.disabled = !!state.busy;
        if (b.dataset.session) b.setAttribute("aria-current", String(b.dataset.session === state.session));
      }
      const taskKey = JSON.stringify(state.tasks || []);
      if (taskKey !== lastTasks) {
        taskList.replaceChildren();
        for (const item of state.tasks || []) {
          const done = ["completed", "done"].includes(item.status);
          const running = ["in_progress", "running", "active"].includes(item.status);
          const li = el("li", done ? "done" : running ? "active" : "pending");
          li.append(icon(done ? "check" : running ? "loader-circle" : "circle"), el("span", "", item.task));
          taskList.append(li);
        }
        if (!(state.tasks || []).length) taskList.append(el("li", "empty-note", tr("尚未生成任务清单", "No tasks yet")));
        lastTasks = taskKey;
      }
      const changesKey = JSON.stringify(state.changes || []);
      if (changesKey !== lastChanges) {
        changeList.replaceChildren();
        for (const item of state.changes || []) changeList.append(button(item.path, () => request("review"), "file-row", "file-code-2"));
        if (!(state.changes || []).length) changeList.append(el("p", "empty-note", tr("暂无待应用修改", "No changes to apply")));
        lastChanges = changesKey;
      }
      const attachments = state.attachments || [], filesKey = JSON.stringify([state.contextFiles || [], attachments]);
      if (filesKey !== lastFiles) {
        chips.replaceChildren();
        fileList.replaceChildren();
        overviewFiles.replaceChildren();
        for (const item of attachments) {
          const chip = el("div", "attachment-chip"), remove = iconButton(tr("移除 ", "Remove ") + item.name, "x", () => request("remove_attachment", { id: item.id }), "attachment-remove");
          chip.title = item.name;
          chip.append(icon(item.image ? "image" : "file"), el("span", "button-label", item.name), remove);
          chips.append(chip);
        }
        for (const path of state.contextFiles || []) {
          const item = button(path.split("/").pop(), () => nativeRequest("context_files"), "file-chip", "file-code-2");
          item.title = path;
          chips.append(item);
          fileList.append(el("p", "file-reference", path));
          overviewFiles.append(el("p", "file-reference", path));
        }
        if (!fileCount) {
          fileList.append(el("p", "empty-note", tr("未额外选择文件。Agent 可按当前权限读取工作区。", "No extra files selected. The Agent can read the workspace under its current permissions.")));
          overviewFiles.append(el("p", "small-text", tr("使用当前工作区", "Using the current workspace")));
        }
        chips.hidden = !fileCount && !attachments.length;
        lastFiles = filesKey;
      }
      for (const item of chips.querySelectorAll("button")) item.disabled = !!state.busy;
    }
    function positionJump() {
      if (root.isConnected) jump.style.bottom = composerArea.offsetHeight + 12 + "px";
    }
    const composerObserver = typeof ResizeObserver === "function" ? new ResizeObserver(positionJump) : null;
    composerObserver == null ? void 0 : composerObserver.observe(composerArea);
    window.addEventListener("resize", positionJump);
    window.addEventListener("resize", paintBackdrop);
    input.addEventListener("input", positionJump);
    for (const kind of ["engine", "mode"]) selection(kind);
    renderTabs();
    restorePanels();
    const resized = () => {
      syncCodexCanvas(root);
      if (layout !== "chat" && panelIsOpen() && ["history", "info"].includes(drawerMode) && wide.matches) closePanel();
      restorePanels();
    };
    for (const query of [wide, dual]) {
      if (query.addEventListener) query.addEventListener("change", resized);
      else query.addListener(resized);
    }
    const visibility = () => {
      if (document.hidden) flushDraft();
    };
    document.addEventListener("visibilitychange", visibility);
    return { root, update, closePanel: () => {
      if (bubble.close()) return true;
      if (closeQuickPicker()) return true;
      if (panelIsOpen()) {
        closePanel();
        return true;
      }
      const closed = childNav.close();
      panelChanged();
      return closed;
    }, dispose() {
      closeQuickPicker(false);
      document.removeEventListener("pointerdown", quickOutside);
      window.removeEventListener("resize", positionQuickPicker);
      document.removeEventListener("scroll", positionQuickPicker, true);
      quick.remove();
      bubble.dispose();
      childNav.dispose();
      clearLongTaskPress();
      flushDraft();
      clearTimeout(draftTimer);
      composerObserver == null ? void 0 : composerObserver.disconnect();
      backdropObserver == null ? void 0 : backdropObserver.disconnect();
      window.removeEventListener("resize", paintBackdrop);
      window.removeEventListener("resize", positionJump);
      for (const query of [wide, dual]) {
        if (query.removeEventListener) query.removeEventListener("change", resized);
        else query.removeListener(resized);
      }
      document.removeEventListener("visibilitychange", visibility);
      document.removeEventListener("keydown", escapePanel);
      closePanel();
    } };
  }

  // agent/app/src/main/assets/agent/engines/codex.js
  var codex_default = {
    "id": "codex",
    "label": "Codex",
    "mark": "code-xml",
    "greeting": tr("我们该构建什么？", "What should we build?"),
    "placeholder": tr("描述任务、目标或需要修复的问题…", "Describe a task, goal, or issue…"),
    "navigation": false,
    "inspector": false,
    "starters": []
  };

  // agent/app/src/main/assets/agent/engines/openclaude.js
  var openclaude_default = {
    "id": "openclaude",
    "label": "OpenClaude",
    "mark": "message-square",
    "greeting": tr("一起完成下一个任务", "Work on your next task"),
    "placeholder": tr("输入任务或补充要求…", "Enter a task or add details…"),
    "navigation": true,
    "inspector": true,
    "starters": []
  };

  // agent/app/src/main/assets/agent/engines/gemini.js
  var gemini_default = {
    "id": "gemini",
    "label": "Gemini",
    "mark": "sparkles",
    "greeting": tr("从哪里开始？", "Where should we start?"),
    "placeholder": tr("描述任务，或补充需要参考的内容…", "Describe a task or add context…"),
    "navigation": false,
    "inspector": false,
    "starters": [
      [
        tr("理解项目", "Understand the project"),
        "folder",
        tr("请说明这个项目的结构和关键流程。", "Explain how this project is organized and how its main workflows operate.")
      ],
      [
        tr("分析问题", "Analyze an issue"),
        "search",
        tr("请分析以下问题的原因：\n", "Analyze what caused the following issue:\n")
      ],
      [
        tr("制定方案", "Plan a change"),
        "list-checks",
        tr("请为以下需求制定实现方案：\n", "Plan an implementation for the following requirements:\n")
      ],
      [
        tr("实现修改", "Implement a change"),
        "code-xml",
        tr("请实现以下修改：\n", "Implement the following changes:\n")
      ]
    ]
  };

  // agent/app/src/main/assets/agent/engines/pi.js
  var pi_default = {
    "id": "pi",
    "label": "Pi",
    "mark": "terminal",
    "greeting": tr("开始新的对话", "Start a conversation"),
    "placeholder": tr("输入消息…", "Type a message…"),
    "navigation": true,
    "inspector": false,
    "starters": []
  };

  // agent/app/src/main/assets/agent/app.js
  var profiles = { codex: codex_default, openclaude: openclaude_default, gemini: gemini_default, pi: pi_default };
  var toolExpansion = createToolExpansionState();
  var drafts = /* @__PURE__ */ new Map();
  var page;
  var holder;
  var key = "";
  var confirmingFrame = false;
  window.AsterReceive = (state, revision = 0) => {
    var _a2;
    try {
      if (!state || !profiles[state.engine] || !state.workspace || !state.session) return false;
      const next = [state.workspace, state.engine, state.session].join(":");
      if (next !== key) {
        page == null ? void 0 : page.dispose();
        key = next;
        holder = { value: state };
        document.body.dataset.engine = state.engine;
        const local = drafts.get(key) || { text: state.draft || "", dirty: false };
        drafts.set(key, local);
        while (drafts.size > 32) drafts.delete(drafts.keys().next().value);
        const scope = holder;
        page = createPage(profiles[state.engine], (command, data = {}) => {
          if (!window.AsterNative) return;
          const current = scope.value;
          window.AsterNative.postMessage(JSON.stringify({ version: 1, token: current.token, workspace: current.workspace, session: current.session, engine: current.engine, command, ...data }));
        }, local, toolExpansion);
        document.getElementById("app").replaceChildren(page.root);
      }
      holder.value = state;
      page.update(state);
      if (!confirmingFrame && revision > 0) {
        confirmingFrame = true;
        let attempts = 0;
        const confirm = () => {
          var _a3;
          if (((_a3 = window.AsterBoot) == null ? void 0 : _a3.rendered(revision)) || ++attempts >= 120) {
            confirmingFrame = false;
            return;
          }
          requestAnimationFrame(confirm);
        };
        requestAnimationFrame(confirm);
      }
      return true;
    } catch (error) {
      (_a2 = window.AsterBoot) == null ? void 0 : _a2.fail(error.message || String(error));
      return false;
    }
  };
  window.AsterClosePanel = () => (page == null ? void 0 : page.closePanel()) || false;
  if (!page) {
    const profile = profiles.pi, local = { text: "", dirty: false };
    const initial = createPage(profile, () => {
    }, local, toolExpansion);
    initial.update({ engine: "pi", busy: true, status: "", messages: [], history: [], tasks: [], changes: [], contextFiles: [], engines: [] });
    document.body.dataset.engine = "pi";
    document.getElementById("app").replaceChildren(initial.root);
    page = initial;
  }
  var _a;
  (_a = window.AsterBoot) == null ? void 0 : _a.ready();
})();

((request) => {
    const selector = 'a[href],button,input,textarea,select,summary,[role=button],[role=link],' +
        '[role=checkbox],[role=radio],[role=tab],[role=menuitem],[contenteditable]:not([contenteditable=false])';
    const snapshotKey = '__asterBrowserSnapshot';
    const refresh = 'Element unavailable; take a fresh browser snapshot';
    const fail = error => ({ ok: false, error });
    const text = (value, max = 200) => String(value || '').replace(/\s+/g, ' ').trim().slice(0, max);

    function visible(element) {
        if (element.closest('[inert],[aria-hidden=true]')) return false;
        const bounds = element.getBoundingClientRect();
        const style = element.ownerDocument.defaultView.getComputedStyle(element);
        return bounds.width > 0 && bounds.height > 0 &&
            style.visibility !== 'hidden' && style.visibility !== 'collapse';
    }

    function isDisabled(element) {
        return element.matches(':disabled,[aria-disabled=true]');
    }

    function describe(element) {
        const labels = (element.getAttribute('aria-labelledby') || '').split(/\s+/)
            .map(id => id && element.ownerDocument.getElementById(id))
            .filter(Boolean).map(label => label.textContent).join(' ');
        return text(labels || element.getAttribute('aria-label') ||
            Array.from(element.labels || []).map(label => label.innerText).join(' ') ||
            element.innerText || element.getAttribute('placeholder') || element.getAttribute('alt') ||
            element.getAttribute('title'));
    }

    function canScroll(element, axis) {
        const style = element.ownerDocument.defaultView.getComputedStyle(element);
        const vertical = /^(auto|scroll|overlay)$/.test(style.overflowY) && element.scrollHeight > element.clientHeight + 1;
        const horizontal = /^(auto|scroll|overlay)$/.test(style.overflowX) && element.scrollWidth > element.clientWidth + 1;
        return axis === 'y' ? vertical : axis === 'x' ? horizontal : vertical || horizontal;
    }

    function scrollTarget(element, axis) {
        if (element) {
            for (let node = element; node; node = node.parentElement) {
                if (canScroll(node, axis)) return node;
            }
        }
        const owner = element ? element.ownerDocument : document;
        return owner.scrollingElement || owner.documentElement;
    }

    function frameChainIsCurrent(frames) {
        try {
            return frames.every(({ frame, doc, url }) => frame.isConnected && frame.contentDocument === doc &&
                doc.location.href === url && visible(frame));
        } catch (_) {
            return false;
        }
    }

    function capture() {
        if (!document.body) return fail('The page is still loading');
        const entries = [];
        const elements = [];
        const bodyText = [];
        let remainingText = 18000;
        let frameBudget = 24;
        function visit(doc, frames, offsetX, offsetY) {
            if (!doc.body || remainingText < 0 || frameBudget-- <= 0) return;
            const pageText = (doc.body.innerText || '').trim().slice(0, remainingText);
            if (pageText) {
                bodyText.push(pageText);
                remainingText -= pageText.length + 2;
            }
            for (const element of doc.querySelectorAll(selector)) {
                if (elements.length >= 200) break;
                if (!visible(element) || element.type === 'hidden') continue;
                const bounds = element.getBoundingClientRect();
                const parent = scrollTarget(element);
                const id = entries.length;
                entries.push({ element, frames });
                const descriptor = {
                    id,
                    ref: 'e' + (id + 1),
                    tag: element.tagName,
                    role: element.getAttribute('role') || '',
                    text: describe(element),
                    type: element.type || '',
                    disabled: isDisabled(element),
                    scrollable: canScroll(element),
                    scrollContainer: { tag: parent.tagName, id: text(parent.id) },
                    bounds: { x: offsetX + bounds.x, y: offsetY + bounds.y, width: bounds.width, height: bounds.height }
                };
                if (frames.length) descriptor.frame = frames.length;
                elements.push(descriptor);
            }
            if (frames.length >= 4) return;
            for (const frame of doc.querySelectorAll('iframe,frame')) {
                if (frameBudget <= 0) break;
                if (!visible(frame)) continue;
                let child;
                let url;
                try {
                    child = frame.contentDocument;
                    if (!child) continue;
                    url = child.location.href;
                } catch (_) {
                    continue;
                }
                const rect = frame.getBoundingClientRect();
                visit(child, [...frames, { frame, doc: child, url }],
                    offsetX + rect.x + frame.clientLeft, offsetY + rect.y + frame.clientTop);
            }
        }
        visit(document, [], 0, 0);
        window[snapshotKey] = { id: request.snapshotId, url: location.href, entries };
        return { ok: true, url: location.href, title: document.title, text: bodyText.join('\n\n'), elements };
    }

    function fill(element, value) {
        const realm = element.ownerDocument.defaultView;
        element.focus();
        if (element.isContentEditable) {
            element.textContent = value;
        } else if ((element.tagName === 'INPUT' && /^(text|search|email|url|tel|number|password)$/.test(element.type) ||
            element.tagName === 'TEXTAREA') && !element.readOnly) {
            const prototype = element.tagName === 'INPUT' ? realm.HTMLInputElement.prototype : realm.HTMLTextAreaElement.prototype;
            Object.getOwnPropertyDescriptor(prototype, 'value').set.call(element, value);
        } else {
            return fail('The selected element is not an editable text field');
        }
        element.dispatchEvent(new realm.Event('input', { bubbles: true, composed: true }));
        element.dispatchEvent(new realm.Event('change', { bubbles: true }));
        return { ok: true };
    }

    if (request.action === 'snapshot') return capture();
    const snapshot = window[snapshotKey];
    window[snapshotKey] = null;
    if (!snapshot || snapshot.id !== request.snapshotId || snapshot.url !== location.href) return fail(refresh);
    const entry = snapshot.entries[request.elementId];
    if (request.elementId !== undefined && (!entry || !entry.element.isConnected ||
        !frameChainIsCurrent(entry.frames) || !visible(entry.element))) return fail(refresh);
    const element = entry && entry.element;
    if (request.action === 'scroll') {
        const axis = /^(left|right)$/.test(request.direction) ? 'x' : 'y';
        const distance = /^(left|up)$/.test(request.direction) ? -request.distance : request.distance;
        const target = scrollTarget(element, axis);
        const owner = target.ownerDocument;
        const before = [target.scrollLeft, target.scrollTop];
        if (typeof target.scrollBy === 'function') {
            target.scrollBy({ left: axis === 'x' ? distance : 0, top: axis === 'y' ? distance : 0, behavior: 'instant' });
        } else if (axis === 'x') {
            target.scrollLeft += distance;
        } else {
            target.scrollTop += distance;
        }
        return {
            ok: true, target: target === owner.scrollingElement ? 'document' : target.tagName,
            moved: target.scrollLeft !== before[0] || target.scrollTop !== before[1],
            scrollLeft: target.scrollLeft, scrollTop: target.scrollTop
        };
    }
    if (!element || isDisabled(element)) return fail(refresh);
    if (request.action === 'fill') return fill(element, request.text);
    if (request.action === 'click') {
        element.scrollIntoView({ block: 'nearest', inline: 'nearest' });
        element.click();
        return { ok: true };
    }
    return fail('Unsupported browser operation');
})

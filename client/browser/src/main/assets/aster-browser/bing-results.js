((request) => {
    const results = [];
    const seen = new Set();
    const isBing = host => host === 'bing.com' || host.endsWith('.bing.com');
    const text = (value, max) => String(value || '').replace(/\s+/g, ' ').trim().slice(0, max);
    const nodeText = node => node && (node.innerText || node.textContent);

    function webUrl(value) {
        try {
            const url = new URL(value || '', location.href);
            if (!value || url.protocol !== 'http:' && url.protocol !== 'https:' ||
                url.username || url.password || url.href.length > 4096) return '';
            return url.href;
        } catch (_) {
            return '';
        }
    }

    function destination(value) {
        const safe = webUrl(value);
        if (!safe) return '';
        const url = new URL(safe);
        if (!isBing(url.hostname)) return url.href;
        if (!/^\/(?:ck\/a|aclick)/.test(url.pathname)) return '';
        const encoded = url.searchParams.get('u') || '';
        if (encoded.startsWith('a1')) {
            try {
                const bytes = Uint8Array.from(atob(encoded.slice(2).replace(/-/g, '+').replace(/_/g, '/')),
                    ch => ch.charCodeAt(0));
                return webUrl(new TextDecoder().decode(bytes)) || url.href;
            } catch (_) {
                return url.href;
            }
        }
        return webUrl(encoded.startsWith('http') ? encoded : '') || url.href;
    }

    function add(entry, key) {
        if (!key || seen.has(key)) return;
        seen.add(key);
        results.push(entry);
    }

    function imageResults() {
        for (const tile of document.querySelectorAll('a.iusc, a[m], #mmComponent_images_1 a[href*="view=detail"]')) {
            let metadata = {};
            try {
                const raw = tile.getAttribute('m');
                if (raw && raw.length < 20000) metadata = JSON.parse(raw) || {};
            } catch (_) { /* The thumbnail can still be read from the DOM. */ }
            const image = tile.querySelector('img');
            const details = new URL(tile.href || location.href, location.href);
            const imageUrl = webUrl(metadata.murl || details.searchParams.get('mediaurl'));
            const thumbnailUrl = webUrl(metadata.turl || image &&
                (image.getAttribute('data-src') || image.currentSrc || image.getAttribute('src')));
            const sourceUrl = webUrl(metadata.purl);
            const title = text(metadata.t || (image && image.alt) || tile.getAttribute('aria-label'), 240);
            add({ title, imageUrl, thumbnailUrl, sourceUrl }, imageUrl || thumbnailUrl);
            if (results.length >= request.maxResults) break;
        }
    }

    function webResults() {
        const titleSelector = '.b_algo h2 a, .b_algo h3 a, #b_results h2 a, #b_results h3 a, ' +
            '.b_algo .b_algoheader > a[href] > h2, .b_algo .b_algoheader > a[href] > h3';
        for (const titleNode of document.querySelectorAll(titleSelector)) {
            const link = titleNode.tagName === 'A' ? titleNode : titleNode.parentElement;
            if (link.closest('.b_ad,[data-bm="ad"]')) continue;
            const url = destination(link.href);
            const title = text(nodeText(titleNode), 240);
            if (!title || !url) continue;
            const card = link.closest('.b_algo,li');
            const summary = card && card.querySelector('.b_caption p,.b_paractl,.b_algoSlug,p');
            add({ title, url, snippet: text(nodeText(summary), 700) }, url);
            if (results.length >= request.maxResults) break;
        }
    }

    if (!isBing(location.hostname)) return { status: 'blocked', results };
    if (request.type === 'image') imageResults();
    else webResults();
    const blocked = /\/(?:challenge|captcha)(?:\/|$)/i.test(location.pathname) ||
        document.querySelector('#b_captcha,#captcha,#bnp_container iframe[src*="challenge"],form[action*="/challenge"]');
    const noResults = document.querySelector('.b_no,#b_no,li.b_no,.dgNoResults');
    return { status: blocked ? 'blocked' : noResults ? 'empty' : 'ready', results };
})

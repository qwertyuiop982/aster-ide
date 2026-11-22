import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const asset = name => readFileSync(new URL(`../client/browser/src/main/assets/aster-browser/${name}`, import.meta.url), 'utf8');
const load = (file, globals) => {
    const fn = vm.runInNewContext(asset(file), { URL, TextDecoder, atob, ...globals });
    return args => JSON.parse(JSON.stringify(fn(args)));
};

class Event {
    constructor(type) { this.type = type; }
}

class Input {
    get value() { return this.currentValue; }
    set value(value) { this.currentValue = value; }
}

function page(url, title, text) {
    const elements = [];
    const frames = [];
    const doc = {
        title,
        location: new URL(url),
        defaultView: {
            Event,
            HTMLInputElement: Input,
            getComputedStyle: item => item.style
        },
        getElementById: () => null,
        querySelectorAll: selector => selector === 'iframe,frame' ? frames : elements
    };
    const node = (tagName, props = {}) => Object.assign(tagName === 'INPUT' ? new Input() : {}, {
        tagName,
        ownerDocument: doc,
        isConnected: true,
        id: '',
        innerText: '',
        clientWidth: 100,
        clientHeight: 100,
        scrollWidth: 100,
        scrollHeight: 100,
        scrollLeft: 0,
        scrollTop: 0,
        style: {},
        closest: () => null,
        matches: () => false,
        getAttribute: () => null,
        getBoundingClientRect: () => ({ x: 10, y: 20, width: 50, height: 30 }),
        scrollBy({ left, top }) { this.scrollLeft += left; this.scrollTop += top; }
    }, props);
    doc.body = node('BODY', { innerText: text });
    doc.scrollingElement = node('HTML');
    return { doc, elements, frames, node };
}

test('DOM snapshots include same-origin frames and prevent using a stale element reference', () => {
    const outer = page('https://example.test/', 'Fixture', 'Outer text');
    const inner = page('https://example.test/frame', 'Frame', 'Iframe text');
    const otherOrigin = outer.node('IFRAME', { contentDocument: null });
    const frame = outer.node('IFRAME', {
        contentDocument: inner.doc,
        clientLeft: 0,
        clientTop: 0
    });
    const clicks = [];
    outer.frames.push(otherOrigin, frame);
    outer.elements.push(outer.node('BUTTON', { innerText: '    Save   now ' }));
    inner.elements.push(inner.node('BUTTON', {
        innerText: 'Frame button',
        scrollIntoView() {},
        click: () => clicks.push('frame')
    }));
    const dom = load('browser-dom.js', { document: outer.doc, location: outer.doc.location, window: {} });
    const snapshot = dom({ action: 'snapshot', snapshotId: 'one' });
    assert.equal(snapshot.text, 'Outer text\n\nIframe text');
    assert.deepEqual(snapshot.elements.map(element => [element.ref, element.text, element.frame]), [
        ['e1', 'Save now', undefined], ['e2', 'Frame button', 1]
    ]);
    assert.equal(snapshot.elements[1].bounds.y, 40);
    assert.ok(dom({ action: 'click', snapshotId: 'one', elementId: 1 }).ok);
    assert.equal(dom({ action: 'click', snapshotId: 'one', elementId: 1 }).ok, false);
    assert.deepEqual(clicks, ['frame']);
    dom({ action: 'snapshot', snapshotId: 'two' });
    inner.doc.location = new URL('https://example.test/different-frame');
    assert.equal(dom({ action: 'click', snapshotId: 'two', elementId: 1 }).ok, false);
});

test('DOM actions dispatch form events and scroll the nearest pane that supports the requested axis', () => {
    const fixture = page('https://example.test/', 'Fixture', 'Rendered form');
    const vertical = fixture.node('DIV', { style: { overflowY: 'auto' }, scrollHeight: 600 });
    const horizontal = fixture.node('DIV', {
        style: { overflowX: 'scroll' }, scrollWidth: 600, parentElement: vertical
    });
    const events = [];
    const input = fixture.node('INPUT', {
        type: 'text', parentElement: horizontal,
        focus: () => events.push('focus'),
        dispatchEvent: event => events.push(event.type)
    });
    fixture.elements.push(input);
    const dom = load('browser-dom.js', { document: fixture.doc, location: fixture.doc.location, window: {} });
    dom({ action: 'snapshot', snapshotId: 'one' });
    assert.ok(dom({ action: 'fill', snapshotId: 'one', elementId: 0, text: 'sample' }).ok);
    assert.equal(input.value, 'sample');
    assert.deepEqual(events, ['focus', 'input', 'change']);
    dom({ action: 'snapshot', snapshotId: 'two' });
    assert.ok(dom({ action: 'scroll', snapshotId: 'two', elementId: 0, direction: 'down', distance: 100 }).moved);
    assert.equal(vertical.scrollTop, 100);
    assert.equal(horizontal.scrollTop, 0);
    dom({ action: 'snapshot', snapshotId: 'three' });
    assert.ok(dom({ action: 'scroll', snapshotId: 'three', elementId: 0, direction: 'right', distance: 150 }).moved);
    assert.equal(horizontal.scrollLeft, 150);
    dom({ action: 'snapshot', snapshotId: 'four' });
    assert.equal(dom({ action: 'scroll', snapshotId: 'four', direction: 'down', distance: 200 }).target, 'document');
    assert.equal(fixture.doc.scrollingElement.scrollTop, 200);
});

test('Bing web results can appear after initial rendering and are bounded and deduplicated', () => {
    const links = [];
    const document = { querySelectorAll: () => links, querySelector: () => null };
    const location = new URL('https://www.bing.com/search?q=aster');
    const search = load('bing-results.js', { document, location });
    const query = { type: 'web', maxResults: 2 };
    const target = 'https://example.test/aster';
    const trackedUrl = `https://www.bing.com/ck/a?!&&u=a1${Buffer.from(target).toString('base64url')}`;
    const link = (href, innerText, snippet, ad = false) => ({
        tagName: 'A', href, innerText,
        closest: selector => selector.includes('.b_ad') ? ad : {
            querySelector: () => ({ textContent: snippet })
        }
    });
    assert.deepEqual(search(query).results, []);
    links.push(
        link('https://example.test/ad', 'Ad', '', true),
        link(trackedUrl, '  First   result  ', 'Dynamically rendered snippet'),
        link(target, 'Duplicate', ''),
        link('javascript:alert(1)', 'Unsafe link', ''),
        link('https://example.test/second', 'Second result', 'Second snippet'),
        link('https://example.test/third', 'Third result', 'Ignored by the limit')
    );
    assert.deepEqual(search(query).results, [
        { title: 'First result', url: target, snippet: 'Dynamically rendered snippet' },
        { title: 'Second result', url: 'https://example.test/second', snippet: 'Second snippet' }
    ]);
    location.pathname = '/challenge';
    assert.equal(search(query).status, 'blocked');
});

test('Bing mobile web results have headings inside title links', () => {
    const card = { querySelector: () => ({ textContent: 'Mobile snippet' }) };
    const heading = {
        tagName: 'H2',
        textContent: 'Mobile result',
        parentElement: {
            href: 'https://example.test/mobile',
            closest: selector => selector === '.b_algo,li' ? card : null
        }
    };
    const document = {
        querySelector: () => null,
        querySelectorAll: selector =>
            selector.split(',').map(part => part.trim()).includes('.b_algo .b_algoheader > a[href] > h2') ? [heading] : []
    };
    const search = load('bing-results.js', {
        document,
        location: new URL('https://www.bing.com/search?q=aster')
    });
    assert.deepEqual(search({ type: 'web', maxResults: 5 }).results, [
        { title: 'Mobile result', url: 'https://example.test/mobile', snippet: 'Mobile snippet' }
    ]);
});

test('Bing image results read image metadata and fall back to a lazy thumbnail', () => {
    const tile = (href, raw, image = null) => ({
        href,
        getAttribute: key => key === 'm' ? raw : null,
        querySelector: () => image
    });
    const document = {
        querySelector: () => null,
        querySelectorAll: () => [
            tile('', JSON.stringify({
                murl: 'https://example.test/photo.png',
                turl: 'https://example.test/thumb.png',
                purl: 'https://example.test/source',
                t: 'Original image'
            })),
            tile('https://www.bing.com/images/search?view=detail', '{ malformed m', {
                alt: 'Lazy thumbnail',
                currentSrc: 'data:image/gif;base64,R0lGODlhAQAB',
                getAttribute: key => key === 'data-src' ? 'https://example.test/lazy.png' : null
            }),
            tile('', '{"murl":"file:///etc/passwd"}')
        ]
    };
    const search = load('bing-results.js', {
        document,
        location: new URL('https://www.bing.com/images/search?q=aster')
    });
    assert.deepEqual(search({ type: 'image', maxResults: 3 }).results, [
        {
            title: 'Original image',
            imageUrl: 'https://example.test/photo.png',
            thumbnailUrl: 'https://example.test/thumb.png',
            sourceUrl: 'https://example.test/source'
        },
        { title: 'Lazy thumbnail', imageUrl: '', thumbnailUrl: 'https://example.test/lazy.png', sourceUrl: '' }
    ]);
});

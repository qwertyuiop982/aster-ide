import assert from 'node:assert/strict';
import test from 'node:test';
import { configureNativePaths } from '../research/gemini-agent/native-paths.mjs';

function workspace(root) {
  return {
    getDirectories() { return [root]; },
    isPathWithinWorkspace(path) { return path === root || path.startsWith(root + '/'); },
    isPathReadable(path) { return this.isPathWithinWorkspace(path); },
  };
}

function fixture() {
  let current = workspace('/project');
  const config = {
    getWorkspaceContext() { return current; },
    isPathAllowed(path) { return this.getWorkspaceContext().isPathReadable(path); },
    validatePathAccess(path) {
      if (typeof path !== 'string' || path.includes('\0')) throw new Error('invalid path');
      return this.isPathAllowed(path);
    },
  };
  return { config, scope(root) { current = workspace(root); return current; } };
}

test('review mode retains its workspace boundary and original methods', () => {
  const { config } = fixture();
  const original = config.getWorkspaceContext();
  const getContext = config.getWorkspaceContext;
  const allowed = config.isPathAllowed;
  const readable = original.isPathReadable;
  const within = original.isPathWithinWorkspace;
  configureNativePaths(config, false);
  assert.equal(config.getWorkspaceContext, getContext);
  assert.equal(config.getWorkspaceContext(), original);
  assert.equal(config.isPathAllowed, allowed);
  assert.equal(original.isPathReadable, readable);
  assert.equal(original.isPathWithinWorkspace, within);
  assert.equal(config.isPathAllowed('/project/src/main.py'), true);
  assert.equal(config.isPathAllowed('/sdcard/Download/outside.txt'), false);
});

test('native mode accepts paths outside the project and keeps directory enumeration rooted', () => {
  const { config } = fixture();
  const original = config.getWorkspaceContext();
  const directories = original.getDirectories;
  const validate = config.validatePathAccess;
  configureNativePaths(config, true);
  assert.equal(config.getWorkspaceContext(), original);
  assert.equal(config.validatePathAccess, validate);
  assert.equal(original.getDirectories, directories);
  assert.deepEqual(original.getDirectories(), ['/project']);
  for (const path of ['/project/a.txt', '/sdcard/Download/outside.txt',
                      '/data/data/dev.aster.account/files/work/a.txt', '/']) {
    assert.equal(original.isPathWithinWorkspace(path), true, path);
    assert.equal(original.isPathReadable(path), true, path);
    assert.equal(config.isPathAllowed(path), true, path);
    assert.equal(config.validatePathAccess(path), true, path);
  }
});

test('native mode still rejects relative, malformed and non-string paths', () => {
  const { config } = fixture();
  configureNativePaths(config, true);
  const context = config.getWorkspaceContext();
  for (const path of ['', 'relative.txt', '../outside.txt', '/tmp/invalid\0name',
                      null, undefined, 42, {}, []]) {
    assert.equal(context.isPathWithinWorkspace(path), false);
    assert.equal(context.isPathReadable(path), false);
    assert.equal(config.isPathAllowed(path), false);
  }
  assert.throws(() => config.validatePathAccess('/tmp/invalid\0name'), /invalid path/);
});

test('new scoped contexts inherit native access and retain their own scan roots', () => {
  const { config, scope } = fixture();
  configureNativePaths(config, true);
  const parent = config.getWorkspaceContext();
  const child = scope('/project/subagent');
  assert.equal(config.getWorkspaceContext(), child);
  const readable = child.isPathReadable;
  assert.equal(config.getWorkspaceContext().isPathReadable, readable);
  assert.deepEqual(parent.getDirectories(), ['/project']);
  assert.deepEqual(child.getDirectories(), ['/project/subagent']);
  assert.equal(parent.isPathReadable('/sdcard/Download/parent.txt'), true);
  assert.equal(child.isPathReadable('/sdcard/Download/child.txt'), true);
  assert.equal(config.isPathAllowed('/sdcard/Download/child.txt'), true);
});

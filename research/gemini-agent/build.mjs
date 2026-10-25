import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { readFileSync, writeFileSync, cpSync } from 'node:fs';
const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const vendor = resolve(root, 'vendor/gemini-cli');
const result = await build({
  entryPoints: [resolve(here, 'runner.ts')], bundle: true, platform: 'node', format: 'esm', target: 'node22',
  outfile: resolve(root, 'core/app/src/main/assets/bridge/agent_vendor/gemini-agent.mjs'),
  nodePaths: [resolve(here, 'node_modules')], minify: false, metafile: true, legalComments: 'eof',
  external: ['@github/keytar', '@lydell/node-pty', 'node-pty', 'cpu-features', 'ssh2'],
  alias: { '@google/gemini-cli-core': resolve(vendor, 'packages/core/index.ts') },
  define: { 'process.env.CLI_VERSION': JSON.stringify('0.64.0'), __dirname: 'import.meta.dirname', __filename: 'import.meta.filename' },
  banner: { js: 'import { createRequire } from "node:module"; const require = createRequire(import.meta.url); console.log = console.error; console.info = console.error; console.debug = console.error;' },
  plugins: [{ name: 'aster-openai-protocol', setup(builder) {
    builder.onLoad({ filter: /core[/]contentGenerator[.]ts$/ }, args => {
      const source = readFileSync(args.path, 'utf8');
      const anchor = '  const generator = await (async () => {';
      if (!source.includes(anchor)) throw new Error('Gemini generator anchor changed');
      return { contents: 'import { OpenAIContentGenerator } from ' + JSON.stringify(resolve(here, 'openai-content-generator.ts')) + ';\n' + source.replace(anchor, anchor + '\n    if (process.env.ASTER_GEMINI_PROTOCOL === "openai") return new OpenAIContentGenerator(config);'), loader: 'ts' };
    });
  } }, { name: 'pinned-git-version', setup(builder) {
    builder.onResolve({ filter: /generated.*git-commit[.]js$/ }, () => ({ path: 'git-commit', namespace: 'aster-version' }));
    builder.onLoad({ filter: /.*/, namespace: 'aster-version' }, () => ({ contents: "export const GIT_COMMIT_INFO = '4a0749aceac5643e6f85a36d7420314d85e13b52'; export const CLI_VERSION = '0.64.0';" }));
  } }, { name: 'aster-context-budget', setup(builder) {
    builder.onLoad({ filter: /[\/]core[\/]tokenLimits[.]ts$/ }, args => {
      const source = readFileSync(args.path, 'utf8');
      const declaration = 'export function tokenLimit(model: Model): TokenCount {';
      if (!source.includes(declaration)) throw new Error('Pinned Gemini tokenLimit signature changed');
      // Adapt only platform-supplied capacity. Preserve upstream model limits and
      // every consumer: native overflow checks and the native compression service.
      const contents = source.replace(declaration, 'function upstreamTokenLimit(model: Model): TokenCount {') + `
export function tokenLimit(model: Model): TokenCount {
  const native = upstreamTokenLimit(model);
  const requested = Number(process.env.ASTER_GEMINI_CONTEXT_WINDOW);
  return Number.isFinite(requested) && requested >= 8192 ? Math.min(native, requested) : native;
}
`;
      return { contents, loader: 'ts' };
    });
  } }],
  loader: { '.wasm': 'file' }, assetNames: 'gemini-assets/[name]-[hash]',
});
const unexpected = Object.keys(result.metafile.inputs).filter(path => path.includes('/packages/cli/') || path.includes('/node_modules/ink/'));
if (unexpected.length) throw new Error('TUI dependency leaked: ' + unexpected.join(', '));
writeFileSync(resolve(here, 'bundle-inputs.json'), JSON.stringify(result.metafile.inputs, null, 2));
console.log(JSON.stringify({ engine: 'Gemini CLI 0.64.0', inputs: Object.keys(result.metafile.inputs).length, tui: false }));

cpSync(resolve(vendor, 'packages/core/src/policy/policies'), resolve(root, 'core/app/src/main/assets/bridge/agent_vendor/policies'), { recursive: true });

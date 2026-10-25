import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { writeFileSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const vendor = resolve(root, 'vendor/pi');
const target = resolve(root, 'core/app/src/main/assets/bridge/agent_vendor');
const result = await build({
  entryPoints: [resolve(here, 'runner.ts')], outfile: resolve(target, 'pi-agent.mjs'),
  bundle: true, platform: 'node', format: 'esm', target: 'node22',
  minify: true, legalComments: 'eof', metafile: true,
  nodePaths: [resolve(here, 'node_modules')],
  alias: {
    '@earendil-works/pi-agent-core': resolve(vendor, 'packages/agent/src/index.ts'),
    '@earendil-works/pi-ai/compat': resolve(here, 'provider-boundary.ts'),
    '@earendil-works/pi-ai': resolve(vendor, 'packages/ai/src/index.ts'),
  },
  plugins: [{ name: 'aster-session-paths', setup(builder) {
    builder.onResolve({ filter: /\/config\.ts$/ }, args => {
      if (args.importer.includes('/packages/coding-agent/')) return { path: resolve(here, 'platform.ts') };
    });
  } }],
  banner: { js: '/* Aster Agent: Pi 1.3.0; MIT. See PI-LICENSE.txt and PI-SOURCE.json. */\nimport { createRequire as asterCreateRequire } from "node:module"; const require = asterCreateRequire(import.meta.url);' },
});
writeFileSync(resolve(here, 'bundle-inputs.json'), JSON.stringify(result.metafile.inputs, null, 2) + '\n');
const bytes = readFileSync(resolve(target, 'pi-agent.mjs'));
console.log(JSON.stringify({ bundleBytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), inputs: Object.keys(result.metafile.inputs).length }));
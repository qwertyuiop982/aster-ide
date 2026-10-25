// Use the project's pinned esbuild; the application does not need Node to display its UI.
import { createRequire } from 'node:module';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const require=createRequire(resolve(root,'research/pi-agent/package.json'));
const {build,version}=require('esbuild');
if(version!=='0.28.2')throw new Error('Expected pinned esbuild 0.28.2, found '+version);
const directory=resolve(root,'agent/app/src/main/assets/agent');
const result=await build({entryPoints:[resolve(directory,'app.js')],outfile:resolve(directory,'app.bundle.js'),bundle:true,format:'iife',platform:'browser',
  target:['chrome61'],charset:'utf8',legalComments:'inline',minify:false,metafile:true,write:true});
const inputs={};for(const file of Object.keys(result.metafile.inputs))inputs[file]=createHash('sha256').update(await readFile(file)).digest('hex');
const output=await readFile(resolve(directory,'app.bundle.js'));
await writeFile(resolve(root,'agent/design/UI-BUNDLE.json'),JSON.stringify({tool:'esbuild',version,target:'chrome61',format:'iife',sha256:createHash('sha256').update(output).digest('hex'),inputs},null,2));
console.log('Bundled local Agent UI:',output.length,'bytes; no runtime module-loader dependency.');
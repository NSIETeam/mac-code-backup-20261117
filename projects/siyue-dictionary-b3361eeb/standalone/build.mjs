import {build as bundle} from 'esbuild';
import {build} from 'vite';
import {fileURLToPath} from 'node:url';
import {verifyPublicCatalog} from './verify-public-catalog.mjs';
await Promise.all([
  bundle({entryPoints:[fileURLToPath(new URL('../lib/validation.ts',import.meta.url))],bundle:true,platform:'node',format:'esm',outfile:fileURLToPath(new URL('./runtime/validation.mjs',import.meta.url)),logLevel:'warning'}),
  bundle({entryPoints:[fileURLToPath(new URL('../lib/server-catalog.ts',import.meta.url))],bundle:true,platform:'node',format:'esm',outfile:fileURLToPath(new URL('./runtime/catalog-data.mjs',import.meta.url)),logLevel:'warning'}),
]);
await build({configFile:fileURLToPath(new URL('./vite.config.ts',import.meta.url))});
const {getCatalog} = await import('./runtime/catalog-data.mjs');
const boundary = await verifyPublicCatalog(fileURLToPath(new URL('../standalone-dist', import.meta.url)), getCatalog());
console.log(`Public content boundary verified: ${boundary.files} files, ${boundary.protectedPhrases} source-specific phrases.`);

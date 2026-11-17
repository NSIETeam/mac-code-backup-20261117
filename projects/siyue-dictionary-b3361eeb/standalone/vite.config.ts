import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';
import {fileURLToPath} from 'node:url';
import {normalizeBasePath} from '../app/app-paths.mjs';

const base = normalizeBasePath(process.env.SIYUE_BASE_PATH ?? '/');
export default defineConfig({
 root:fileURLToPath(new URL('.',import.meta.url)),
 base,
 define:{__SIYUE_BASE_PATH__:JSON.stringify(base)},
 publicDir:fileURLToPath(new URL('../public',import.meta.url)),
 plugins:[react()],
 resolve:{alias:{'@':fileURLToPath(new URL('..',import.meta.url))}},
 build:{outDir:fileURLToPath(new URL('../standalone-dist',import.meta.url)),emptyOutDir:true},
 server:{host:'127.0.0.1',port:5319,proxy:{[`${base}api`]:{
  target:'http://127.0.0.1:5320',
  rewrite:(path:string)=>base==='/'?path:path.slice(base.length-1),
 }}},
});

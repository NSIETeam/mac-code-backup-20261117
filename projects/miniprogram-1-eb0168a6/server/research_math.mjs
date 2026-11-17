import fs from 'node:fs';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const katex=require('./AcademicPilotAssets/math/katex.min.js');
const input=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const math={};let count=0;
function hold(xml){const key=`SYRMATH${++count}END`;math[key]=xml;return key;}
let html=input.replace(/<math\b[\s\S]*?<\/math\s*>/gi,hold);
html=html.replace(/\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]|\\\(([\s\S]+?)\\\)|(?<!\\)\$([^$\n]+?)\$/g,(_,a,b,c,d)=>hold(katex.renderToString(a??b??c??d,{output:'mathml',displayMode:!!(a||b),trust:false,throwOnError:true,maxExpand:1000}).match(/<math\b[\s\S]*<\/math>/)[0]));
if(!/<(?:p|div|table|ul|ol)\b/i.test(html))html=html.replace(/\n/g,'<br>');
fs.writeFileSync(process.argv[3],JSON.stringify({html,math}));

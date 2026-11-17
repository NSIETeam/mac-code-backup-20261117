// CSV stays in the browser. A preview never creates projects or infers missing dates.
export function parseCsv(text){
 const rows=[];let row=[],field='',quoted=false;text=String(text).replace(/^\uFEFF/,'');
 for(let i=0;i<text.length;i++){const c=text[i];if(quoted){if(c==='"'&&text[i+1]==='"'){field+='"';i++;}else if(c==='"')quoted=false;else field+=c;}
 else if(c==='"'&&field==='')quoted=true;else if(c===','){row.push(field);field='';}else if(c==='\n'||c==='\r'){if(c==='\r'&&text[i+1]==='\n')i++;row.push(field);rows.push(row);row=[];field='';}else field+=c;}
 if(quoted)throw Error('CSV 引号未闭合，请检查原文件');if(field||row.length){row.push(field);rows.push(row);}return rows;
}
export function previewCsv(text){const rows=parseCsv(text);if(!rows.length)throw Error('文件为空');const headers=rows[0];return {headers,rows:rows.slice(1).map((cells,index)=>({sourceRow:index+2,cells})).filter(row=>row.cells.some(c=>c.trim())),warnings:['此页面仅预览，不创建项目或发送通知。','原文状态保留，不能直接认定为已验收或已上线。','负责人、年份和日期缺失时保持待补充，不自动推断。']};}

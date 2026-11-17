export function handoutUploadHelp({singleLecture=false}={}) {
 return `<aside class="handout-template-help" aria-label="标准讲义模板与上传说明">
  <div class="handout-template-downloads"><strong>先下载标准讲义模板</strong><a href="./templates/handout-template-v1.docx" download="思跃标准讲义模板.docx">Word 模板（可编辑）</a><a href="./templates/handout-template-v1.pdf" download="思跃标准讲义示例.pdf">PDF 示例</a></div>
  <p>保留“第1讲”讲次标题和“例1：”“练1：”题号，替换示例后可直接上传 Word，也可导出标准 PDF 上传。${singleLecture?'本入口只上传当前这一讲，请删除模板中的其他讲次。':'上传整本时，可在设置中选择“按原稿讲次标题自动识别”。'}</p>
  <details><summary>为什么要用标准 PDF？怎么制作？</summary>
   <p>这里的“标准 PDF”指按模板排版、从 Word 直接导出，并且正文文字可以选择、复制的 PDF。</p>
   <ul><li><strong>题号能直接读取：</strong>清晰的文字和正确的阅读顺序，能减少扫描识别错字造成的漏题、错切。</li><li><strong>版面位置固定：</strong>文字、公式和图片的位置保留下来，便于按原稿切题、预览和打印。</li><li><strong>完整内容更好核对：</strong>避免拍照歪斜、扫描模糊和页面缺边；图形与小问应紧跟所属题目。</li></ul>
   <ol><li>用 Word 打开模板，替换示例题目；讲次标题单独一行，每讲从新页开始。</li><li>题号直接输入在每题开头，并用冒号隔开题干，例如“例1：题目内容”“练1：题目内容”；小问用“（1）（2）”。采用单栏排版，不把题号放进图片或浮动文本框。</li><li>在 Word 中选择“另存为”或“导出”，文件类型选择 PDF。不要把整页截图再转为 PDF。</li><li>打开导出的 PDF，复制一行正文检查是否正常，并查看公式、图片、跨页内容有无缺失。能选中文字只是快速自查，解析后仍需核对拆分预览。</li></ol>
  </details>
 </aside>`;
}

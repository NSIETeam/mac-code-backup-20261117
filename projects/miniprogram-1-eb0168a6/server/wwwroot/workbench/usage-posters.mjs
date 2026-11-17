const width = 1440;
const height = 1800;
const inset = 92;
const right = width - inset;
const font = '"PingFang SC","Noto Sans CJK SC","Microsoft YaHei",system-ui,sans-serif';
const color = {
  paper: '#fbfcf5', ink: '#102b22', subtle: '#425b51', fine: '#cbd8cd',
  track: '#e6eee5', light: '#bfe5ce', green: '#157a56', deep: '#134c38', white: '#ffffff',
};
const heat = ['#e7eee5','#c0e8cd','#87d4ad','#459d75','#15593e'];
const charts = [
  {key:'campuses', title:'各校区使用情况', measure:'总使用人数、总打印份数、人均打印份数'},
  {key:'teachers', title:'最佳使用老师', measure:'按累计学生讲次排列', unit:'学生讲次'},
  {key:'heatmap', title:'各校区每日使用热力图', measure:'每日登记的错题本份数', unit:'份'},
];

function text(ctx, value, x, y, {size = 42, fill = color.ink, weight = 500, align = 'left', maxWidth} = {}) {
  ctx.font = `${weight} ${size}px ${font}`;
  ctx.textAlign = align;
  ctx.fillStyle = fill;
  if (maxWidth) ctx.fillText(String(value), x, y, maxWidth);
  else ctx.fillText(String(value), x, y);
}

function rect(ctx, fill, x, y, w, h, radius = 0) {
  if (w <= 0 || h <= 0) return;
  ctx.fillStyle = fill;
  if (!radius) { ctx.fillRect(x, y, w, h); return; }
  const r = Math.min(radius, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.fill();
}

const shortDay = iso => iso.slice(5).replace('-', '.');
const longDay = iso => iso.replaceAll('-', '.');

function frame(ctx, chart, report, notes) {
  rect(ctx, color.paper, 0, 0, width, height);
  rect(ctx, color.green, 0, 0, width, 20);
  text(ctx, chart.title, inset, 215, {size: chart.key === 'heatmap' ? 101 : 128, weight: 800, maxWidth: right - inset});
  text(ctx, longDay(report.start) + ' — ' + longDay(report.end), inset + 2, 304, {size: 51, fill: color.deep, weight: 700});
  rect(ctx, color.fine, inset, 349, right - inset, 3);
  if (chart.unit) {
    text(ctx, chart.measure, inset, 419, {size: 44, fill: color.subtle, weight: 650});
    text(ctx, chart.unit, right, 419, {size: 38, fill: color.subtle, align: 'right'});
  }
  rect(ctx, color.fine, inset, 1580, right - inset, 3);
  notes.forEach((note, i) => text(ctx, note, inset, 1642 + i * 50, {size: 32, fill: color.subtle, maxWidth: right - inset}));
  text(ctx, '思跃 · 错题打印', inset, 1750, {size: 36, weight: 700});
  text(ctx, '北京时间 · 按任务提交日期', right, 1750, {size: 31, fill: color.subtle, align: 'right'});
}

function campusBars(ctx, report) {
  frame(ctx, charts[0], report, [
    '按打印机所属校区统计提交的错题本份数。使用人数按账号去重。',
    '各列按各自刻度比较。人均＝总打印份数 ÷ 总使用人数，— 表示无使用人数。',
  ]);
  const left = 284;
  const gap = 28;
  const columnWidth = (right - left - 2 * gap) / 3;
  const valueWidth = 96;
  const barWidth = columnWidth - valueWidth - 14;
  const pitch = 1000 / report.branches.length;
  const metrics = [
    {key:'activeUsers', label:'总使用人数', unit:'人', fill:'#157a56', track:'#e4efe5'},
    {key:'copies', label:'总打印份数', unit:'份', fill:'#255bb4', track:'#e4ebf8'},
    {key:'copiesPerUser', label:'人均打印份数', unit:'份/人', fill:'#a35910', track:'#f3e8d7', decimals:1},
  ].map((metric, i) => ({
    ...metric,
    x: left + i * (columnWidth + gap),
    max: Math.max(0, ...report.branches.map(branch => branch[metric.key] ?? 0)),
  }));
  text(ctx, '校区', inset, 430, {size: 40, fill: color.subtle, weight: 650});
  for (const [i, metric] of metrics.entries()) {
    if (i > 0) rect(ctx, color.fine, metric.x - gap / 2, 396, 2, 1114);
    rect(ctx, metric.fill, metric.x, 401, 31, 31, 7);
    text(ctx, metric.label, metric.x + 44, 430, {size: 40, fill: metric.fill, weight: 750, maxWidth: columnWidth - 44});
    const scale = metric.max ? '0–' + metric.max.toFixed(metric.decimals ?? 0) : '0';
    text(ctx, scale + ' ' + metric.unit, metric.x, 481, {size: 31, fill: color.subtle, maxWidth: columnWidth});
  }
  report.branches.forEach((branch, i) => {
    const y = 518 + (i + 0.5) * pitch;
    text(ctx, branch.name, inset, y + 23, {size: 68, weight: 750});
    for (const metric of metrics) {
      const count = branch[metric.key];
      const filled = metric.max ? barWidth * (count ?? 0) / metric.max : 0;
      rect(ctx, metric.track, metric.x, y - 28, barWidth, 56, 11);
      rect(ctx, metric.fill, metric.x, y - 28, filled, 56, 11);
      const label = count == null ? '—' : count.toFixed(metric.decimals ?? 0);
      text(ctx, label, metric.x + columnWidth, y + 25, {size: 70, weight: 800, align: 'right', fill: metric.fill, maxWidth: valueWidth});
    }
  });
}

function teacherBars(ctx, report) {
  const listed = report.teachers.length;
  frame(ctx, charts[1], report, [
    '提交任务即计入。同一学生不同讲次分别计，同一学生同一讲次去重。',
    `五个校区共 ${report.eligibleTeachers} 位老师有可识别讲次的记录，展示前 ${listed} 位。`,
  ]);
  if (!listed) {
    text(ctx, '这个日期范围内暂无带讲次的已提交记录', width / 2, 892, {size: 57, fill: color.subtle, align: 'center'});
    return;
  }
  const max = report.teachers[0].studentLessons;
  const pitch = Math.min(172, 1010 / listed);
  const left = 436;
  const barWidth = 710;
  report.teachers.forEach((teacher, i) => {
    const y = 493 + i * pitch;
    text(ctx, i + 1, inset, y + 54, {size: 33, fill: color.subtle, weight: 650});
    text(ctx, teacher.name, inset + 72, y + 45, {size: 47, weight: 700, maxWidth: 255});
    text(ctx, teacher.city || '归属待核对', inset + 72, y + 82, {size: 30, fill: color.subtle, maxWidth: 255});
    rect(ctx, color.track, left, y + 12, barWidth, 62, 9);
    rect(ctx, color.green, left, y + 12, barWidth * teacher.studentLessons / max, 62, 9);
    text(ctx, teacher.studentLessons, right, y + 69, {size: 67, weight: 800, align: 'right', fill: color.deep, maxWidth: 165});
  });
}

const heatTier = n => n === 0 ? 0 : n <= 4 ? 1 : n <= 9 ? 2 : n <= 19 ? 3 : 4;

function dailyHeatmap(ctx, report) {
  const startIndex = report.days.indexOf(report.heatmapStart);
  const visibleDays = startIndex < 0 ? [] : report.days.slice(startIndex);
  frame(ctx, charts[2], visibleDays.length ? {...report, start:visibleDays[0]} : report, [
    '任务在平台登记后按份数计入，归属所用打印机所在校区。',
    '从所选日期内首个有记录的日期开始，随后无记录的日期显示 0。',
  ]);
  if (!visibleDays.length) {
    text(ctx, '所选日期内暂无打印任务记录', width / 2, 876, {size: 55, fill: color.subtle, align:'center'});
    text(ctx, report.branches.map(branch => branch.name).join(' · '), width / 2, 969, {size: 39, fill: color.subtle, align:'center'});
    return;
  }
  const split = visibleDays.length > 16 ? Math.ceil(visibleDays.length / 2) : visibleDays.length;
  const starts = visibleDays.length > split ? [0, split] : [0];
  const singlePanel = starts.length === 1;
  const rowPitch = singlePanel ? 159 : 69;
  const cellHeight = singlePanel ? 139 : 61;
  const labelX = inset + 1;
  const gridX = inset + 176;
  const cell = (right - gridX) / Math.max(5, split);
  for (const [panelIndex, offset] of starts.entries()) {
    const days = visibleDays.slice(offset, offset + split);
    const y = 498 + panelIndex * 489;
    text(ctx, shortDay(days[0]) + ' — ' + shortDay(days.at(-1)), labelX, y + 37, {size: 39, weight: 700, fill: color.deep});
    days.forEach((day, i) => text(ctx, day.slice(-2), gridX + i * cell + cell / 2, y + 95, {size: 33, align:'center', fill: color.subtle}));
    report.branches.forEach((branch, row) => {
      const rowY = y + 112 + row * rowPitch;
      text(ctx, branch.name, labelX, rowY + (singlePanel ? 88 : 43), {size: singlePanel ? 55 : 45, weight: 750});
      days.forEach((_, i) => {
        const n = branch.daily[startIndex + offset + i];
        const level = heatTier(n);
        const x = gridX + i * cell;
        rect(ctx, heat[level], x + 3, rowY, cell - 6, cellHeight, 9);
        text(ctx, n, x + cell / 2, rowY + (singlePanel ? 89 : 44), {
          size: singlePanel ? 52 : 34, weight: 700, align: 'center', maxWidth: cell - 13, fill: level >= 3 ? color.white : color.ink,
        });
      });
    });
  }
  ['0','1–4','5–9','10–19','20+'].forEach((label,i) => {
    const x = inset + i * 183;
    rect(ctx, heat[i], x, 1490, 56, 43, 7);
    text(ctx, label, x + 72, 1524, {size: 33, weight: 650, fill: color.subtle});
  });
}

const renderers = {campuses:campusBars, teachers:teacherBars, heatmap:dailyHeatmap};
export function renderUsagePoster(key, report, canvas) {
  canvas.width = width;
  canvas.height = height;
  renderers[key](canvas.getContext('2d'), report);
  return canvas;
}

function savePng(canvas, filename) {
  canvas.toBlob(blob => {
    if (!blob) return;
    const url = URL.createObjectURL(blob);
    const download = document.createElement('a');
    download.href = url;
    download.download = filename;
    download.click();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  }, 'image/png');
}

export function mountUsagePosters(container, report) {
  for (const chart of charts) {
    const panel = document.createElement('article');
    panel.className = 'card usage-poster';
    const canvas = renderUsagePoster(chart.key, report, document.createElement('canvas'));
    canvas.setAttribute('role', 'img');
    const start = chart.key === 'heatmap' ? report.heatmapStart ?? report.start : report.start;
    canvas.setAttribute('aria-label', chart.title + '，' + chart.measure + '，' + start + ' 至 ' + report.end);
    canvas.dataset.poster = chart.key;
    const download = document.createElement('button');
    download.type = 'button';
    download.className = 'quiet';
    download.textContent = '导出 PNG';
    download.setAttribute('aria-label', '导出' + chart.title + '海报 PNG');
    download.addEventListener('click', () => savePng(canvas, chart.title + '_' + start + '_' + report.end + '.png'));
    panel.append(canvas, download);
    container.append(panel);
  }
}

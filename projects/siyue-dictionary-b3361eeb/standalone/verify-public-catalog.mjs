import { readdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';

function protectedPhrases(catalog) {
  const text = [
    ...catalog.roles.flatMap(role => [role.summary, role.scope, ...role.duties.map(item => item.text), ...role.schedule.map(item => item.text), ...role.checks.map(item => item.text), ...role.questions]),
    ...catalog.processes.flatMap(process => [process.summary, ...process.steps.map(step => step.text), ...process.questions]),
    ...catalog.policies.flatMap(policy => [policy.summary, ...policy.sections.flatMap(section => section.paragraphs)]),
    ...[...catalog.sources, ...catalog.policySources].map(source => source.excerpt),
    ...Object.values(catalog.roleGuidance).flatMap(guidance => [guidance.summary, ...[...guidance.requirements, ...guidance.checks, ...guidance.handoffs].map(item => item.text)]),
  ];
  // Long, source-specific sentences catch real disclosure without banning
  // innocuous shared UI words such as “教务”, “考勤” or “工作资料”.
  return [...new Set(text.filter(value => typeof value === 'string' && value.length >= 40).map(value => value.slice(0, 70)))];
}

export async function verifyPublicCatalog(directory, catalog) {
  const phrases = protectedPhrases(catalog);
  let scanned = 0;
  async function visit(path) {
    for (const entry of await readdir(path, { withFileTypes: true })) {
      const file = join(path, entry.name);
      if (entry.isDirectory()) { await visit(file); continue; }
      if (!/\.(?:js|mjs|json|map|html|txt)$/i.test(entry.name)) continue;
      const content = await readFile(file, 'utf8');
      scanned++;
      for (const phrase of phrases) {
        const escaped = JSON.stringify(phrase).slice(1, -1);
        if (content.includes(phrase) || content.includes(escaped)) {
          throw new Error(`公开构建包含受限参考正文：${file}。请移除客户端对资料模块的导入。`);
        }
      }
    }
  }
  await visit(directory);
  if (!scanned) throw new Error('没有找到可验证的公开构建文件。');
  return { files: scanned, protectedPhrases: phrases.length };
}

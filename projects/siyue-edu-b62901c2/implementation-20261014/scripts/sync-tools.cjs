#!/usr/bin/env node
"use strict";
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const builder = require("./build-tools.cjs");
/** Regenerates only the explicit tool runtime files after a successful strict compilation. */
function sync(root) {
    if (!path.isAbsolute(root))
        throw Error('必须提供绝对项目路径');
    root = fs.realpathSync(root);
    builder.assertMetadata(JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8')));
    const temporary = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'siyue-tool-sync-'));
    fs.chmodSync(temporary, 0o700);
    const names = [...builder.FILES.filter(file => file.endsWith('.cjs')), 'scripts/build-tools.cjs', 'scripts/sync-tools.cjs'];
    const backups = new Map();
    const replaced = [];
    try {
        const compiled = path.join(temporary, 'compiled');
        builder.compileTools(root, compiled);
        for (const name of names) {
            const target = path.join(root, name);
            const parent = path.dirname(target);
            if (fs.realpathSync(parent) !== parent)
                throw Error('生成物目录不能包含符号链接');
            const stat = fs.existsSync(target) ? fs.lstatSync(target) : null;
            if (stat && (!stat.isFile() || stat.isSymbolicLink()))
                throw Error('生成物必须是普通文件');
            backups.set(name, stat ? { bytes: fs.readFileSync(target), mode: stat.mode & 0o777 } : null);
            if (!fs.statSync(path.join(compiled, name)).isFile())
                throw Error('缺少编译输出');
        }
        for (const name of names) {
            const target = path.join(root, name), staged = path.join(temporary, 'next');
            fs.writeFileSync(staged, fs.readFileSync(path.join(compiled, name)), { mode: backups.get(name)?.mode ?? 0o644 });
            fs.renameSync(staged, target);
            replaced.push(name);
        }
    }
    catch (error) {
        for (const name of replaced.reverse()) {
            const backup = backups.get(name), target = path.join(root, name);
            if (backup)
                fs.writeFileSync(target, backup.bytes, { mode: backup.mode });
            else
                fs.rmSync(target);
        }
        throw error;
    }
    finally {
        fs.rmSync(temporary, { recursive: true, force: true });
    }
}
if (require.main === module) {
    try {
        if (process.argv.length !== 4 || process.argv[2] !== '--root')
            throw Error('用法：sync-tools --root /absolute/project');
        sync(process.argv[3]);
        console.log('严格编译完成，工具运行文件已同步');
    }
    catch (error) {
        console.error(error instanceof Error ? error.message : String(error));
        process.exitCode = 1;
    }
}
module.exports = { sync };

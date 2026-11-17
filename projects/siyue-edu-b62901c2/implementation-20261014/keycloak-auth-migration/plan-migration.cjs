#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const crypto = require('node:crypto');
const { buildPlan, readPrivateSource, writePackage } = require('./plan.cjs');

function args(argv) {
  const result = { write: false };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--write') { result.write = true; continue; }
    if (!['--source', '--issuer', '--realm', '--out'].includes(argv[i]) || !argv[i + 1]) {
      throw new Error('用法: plan-migration.cjs --source <绝对路径> --issuer <HTTPS issuer> --realm <realm> [--write --out <0700目录>]');
    }
    result[argv[i].slice(2)] = argv[++i];
  }
  if (!result.source || !result.issuer || !result.realm || (result.write && !result.out)) {
    throw new Error('必须提供source、issuer、realm；写文件时还必须提供out');
  }
  return result;
}

try {
  const options = args(process.argv.slice(2));
  const state = readPrivateSource(options.source);
  const sourceSha256 = crypto.createHash('sha256').update(fs.readFileSync(options.source)).digest('hex');
  const plan = buildPlan(state, options.issuer, options.realm, sourceSha256);
  const summary = {
    mode: options.write ? 'private-package-written' : 'dry-run',
    realm: options.realm,
    accountCount: plan.realmRepresentation.users.length,
    disabledCount: plan.principalManifest.principals.filter(x => x.disabled).length,
    mustChangePasswordCount: plan.principalManifest.principals.filter(x => x.mustChangePassword).length,
    sourceSha256,
  };
  if (options.write) {
    writePackage(options.out, plan);
    const manifest = { ...summary, issuer: plan.principalManifest.issuer,
      keycloakArtifact: 'keycloak-partial-import.json', javaArtifact: 'java-principal-steps.json',
      trustedManifestArtifact: 'trusted-manifest.json' };
    const dirStat = fs.lstatSync(options.out);
    if (!dirStat.isDirectory() || dirStat.isSymbolicLink() || (dirStat.mode & 0o077)) throw new Error('输出目录必须为0700独立目录');
    const manifestFile = `${options.out}/migration-manifest.json`;
    const temp = `${manifestFile}.${crypto.randomBytes(8).toString('hex')}.tmp`;
    const fd = fs.openSync(temp, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_NOFOLLOW, 0o600);
    try { fs.writeFileSync(fd, JSON.stringify(manifest, null, 2) + '\n'); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
    fs.renameSync(temp, manifestFile);
  }
  process.stdout.write(JSON.stringify(summary) + '\n');
} catch (error) {
  process.stderr.write(`${error.code || 'MIGRATION_PLAN_ERROR'}: ${error.message}\n`);
  process.exitCode = 1;
}

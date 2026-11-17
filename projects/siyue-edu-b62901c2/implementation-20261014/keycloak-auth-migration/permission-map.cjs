'use strict';

const fs = require('node:fs');
const path = require('node:path');

const PERMISSION_MAPPING_VERSION = 'siyue-permission-map-v1';
const MAP_FILE = path.join(__dirname, 'permission-map-v1.json');

function readPermissionMap(file = MAP_FILE) {
  const value = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (value.version !== PERMISSION_MAPPING_VERSION || !Array.isArray(value.mappings)) {
    throw new Error('权限映射版本或结构无效');
  }
  const bySource = new Map(), byTarget = new Map();
  for (const item of value.mappings) {
    if (!item || typeof item.source !== 'string' || typeof item.target !== 'string'
        || typeof item.evidence !== 'string' || !item.evidence.trim()) throw new Error('权限映射条目无效');
    if (bySource.has(item.source)) throw new Error(`权限映射源代码重复 ${item.source}`);
    if (byTarget.has(item.target)) throw new Error(`权限映射存在多对一冲突 ${item.target}`);
    bySource.set(item.source, item);
    byTarget.set(item.target, item);
  }
  return { version: value.version, bySource };
}

function mapPermissionCodes(codes, mapping = readPermissionMap()) {
  if (!Array.isArray(codes) || new Set(codes).size !== codes.length) throw new Error('旧权限包含无效或重复值');
  return codes.map(code => {
    const item = mapping.bySource.get(code);
    if (!item) throw new Error(`权限映射 ${mapping.version} 尚未覆盖旧权限 ${code}`);
    return item.target;
  }).sort();
}

module.exports = { PERMISSION_MAPPING_VERSION, mapPermissionCodes, readPermissionMap };

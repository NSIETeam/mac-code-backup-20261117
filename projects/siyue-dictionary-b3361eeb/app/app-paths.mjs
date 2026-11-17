/* global __SIYUE_BASE_PATH__ */
// Vite defines this alongside its asset base; non-Vite/local usage defaults to /.

/** @param {string} [value] */
export function normalizeBasePath(value = "/") {
  const path = value.trim() || "/";
  if (!path.startsWith("/") || path.startsWith("//") || /[?#\\\s]/u.test(path) || path.split("/").some(segment => segment === "." || segment === "..")) {
    throw new Error("SIYUE_BASE_PATH 必须是同站点绝对路径，例如 / 或 /work/。");
  }
  if (path.includes("//")) throw new Error("SIYUE_BASE_PATH 不能包含重复的斜线。");
  return path.endsWith("/") ? path : `${path}/`;
}

export const APP_BASE_PATH = normalizeBasePath(typeof __SIYUE_BASE_PATH__ === "string" ? __SIYUE_BASE_PATH__ : "/");

/** @param {string} path @param {string} [basePath] */
export function appPath(path, basePath = APP_BASE_PATH) {
  return `${normalizeBasePath(basePath)}${path.replace(/^\/+/, "")}`;
}

/** @param {string} path @param {RequestInit} [init] */
export function appFetch(path, init) {
  return fetch(appPath(path), init);
}

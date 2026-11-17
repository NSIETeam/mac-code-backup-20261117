import { randomBytes } from "node:crypto";
import { readFile } from "node:fs/promises";
import { dirname, extname, join, normalize, resolve, sep } from "node:path";
import z from "@deepseek-ai/schemastery";
//#region lib/types/index.js
/**
* @deepseek-ai/dsh-host-frontend-static — SPA dist server over the webserver
* fallback seat: serves the built frontend directory with explicit index
* entry points. A readable index renders at the dist root and configured index
* path; missing paths return 404, traversal outside the dist root is 403,
* unknown extensions ship as octet-stream, and non-GET/HEAD is 405. Every
* index response first passes Connection's browser authentication, then the
* webserver's index render (structured injection rows, then raw taps).
* Non-index assets stay public. The dist location is workspace knowledge of
* the composing application, so `distIndex` is typically supplied through a
* `!!js` expression, never hardcoded by a deployment.
* @module @deepseek-ai/dsh-host-frontend-static
*/
/** Stable Cordis plugin name. */
const name = "frontend-static";
/** Services required before the authenticated fallback seat can be claimed. */
const inject = ["webServer", "connection"];
const Config = z.object({ distIndex: z.string().required() });
const HTML_MIME = "text/html; charset=utf-8";
const MIME = {
	".html": HTML_MIME,
	".js": "text/javascript; charset=utf-8",
	".css": "text/css; charset=utf-8",
	".svg": "image/svg+xml",
	".json": "application/json",
	".map": "application/json",
	".webmanifest": "application/manifest+json",
	".gz": "application/gzip"
};
const STATIC_MISS_CODES = new Set([
	"ENOENT",
	"EISDIR",
	"ENOTDIR"
]);
/**
* The page policy is a fixed constant, deliberately independent of the served
* content. A content-derived policy (hashes of inline blocks, nonce/unsafe
* interplay) regressed on frontend changes twice — the module system's
* top-level `new Function` for !!js patch expressions and runtime-injected
* plugin stylesheets each broke a release with a blank window, because the
* policy and the payload evolve on different lines. A static policy that
* allows everything the local app legitimately does cannot drift out of sync
* with any frontend build. The remaining value: everything stays same-origin
* and the page cannot be framed.
*/
function pageContentSecurityPolicy() {
	return [
		"default-src 'self'",
		"script-src 'self' 'unsafe-eval' 'wasm-unsafe-eval' 'unsafe-inline'",
		"style-src 'self' 'unsafe-inline'",
		"connect-src 'self'",
		"img-src 'self' data: blob:",
		"font-src 'self' data:",
		"media-src 'self' data: blob:",
		"worker-src 'self' blob:",
		"frame-src 'self' blob:",
		"object-src 'none'",
		"base-uri 'self'",
		"frame-ancestors 'none'"
	].join("; ");
}
/**
* Serve one GET/HEAD static request from the dist root.
* @param pathname - decoded URL pathname of the request.
* @param res - the node:http response to write.
* @param distRoot - absolute dist root directory (resolved by the caller).
* @param distIndex - absolute path of index.html inside distRoot.
* @param authorizeIndex - authenticates an index response before its bytes are read.
* @param renderIndex - produces the index.html body (structured injection
* rendering) for the dist root and configured index path.
*/
async function serveStatic(pathname, res, distRoot, distIndex, authorizeIndex, renderIndex) {
	const target = resolve(normalize(join(distRoot, pathname)));
	if (target !== distRoot && !target.startsWith(distRoot + sep)) {
		res.writeHead(403);
		res.end();
		return;
	}
	let body;
	let type;
	let contentSecurityPolicy;
	try {
		if (target === distRoot || target === distIndex) {
			if (!authorizeIndex()) return;
			const styleNonce = randomBytes(18).toString("base64");
			const scriptNonce = randomBytes(18).toString("base64");
			body = await renderIndex();
			const head = /<head(?:\s[^>]*)?>/iu;
			if (!head.test(body)) throw new Error("Rendered index must contain a head element for runtime nonces.");
			body = body.replace(head, (open) => `${open}<meta name="dsh-style-nonce" content="${styleNonce}"><meta name="dsh-script-nonce" content="${scriptNonce}">`);
			type = HTML_MIME;
			contentSecurityPolicy = pageContentSecurityPolicy();
		} else {
			body = await readFile(target);
			type = MIME[extname(target)] ?? "application/octet-stream";
		}
	} catch (error) {
		if (!STATIC_MISS_CODES.has(error.code)) throw error;
		res.writeHead(404);
		res.end();
		return;
	}
	res.writeHead(200, {
		"content-type": type,
		...contentSecurityPolicy === void 0 ? {} : { "content-security-policy": contentSecurityPolicy }
	});
	res.end(body);
}
/**
* Claim the webserver fallback seat and serve the dist.
* @param ctx - plugin context carrying the webServer service.
* @param config - validated {@link Config}.
*/
function apply(ctx, config) {
	const distIndex = config.distIndex;
	const distRoot = dirname(distIndex);
	const renderIndex = async () => {
		return ctx.webServer.renderIndex(await readFile(distIndex, "utf8")).replace(/<head(?:\s[^>]*)?>/i, (open) => `${open}<base href="/">`);
	};
	ctx.effect(() => ctx.webServer.registerFallback(async (req, res) => {
		if (req.method !== "GET" && req.method !== "HEAD") {
			res.writeHead(405);
			res.end();
			return;
		}
		/* v8 ignore next -- node:http always sets url on server requests */
		const rawPath = new URL(req.url ?? "/", "http://x").pathname;
		await serveStatic(decodeURIComponent(rawPath), res, distRoot, distIndex, () => ctx.connection.authorizeIndex(req, res), renderIndex);
	}), "frontend-static: fallback seat");
}
//#endregion
export { Config, apply, inject, name, serveStatic };

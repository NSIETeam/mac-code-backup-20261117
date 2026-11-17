/**
 * Browser-half closure evaluation: the package source runs as the body of an
 * async function whose parameters ARE the symbol surface. Shadowing parameters
 * (setTimeout/fetch/require/…) turn the ambient browser globals into teaching
 * redirects without touching the page. The host syntax-prechecked the source at
 * define time; SyntaxError handling here is the engine-divergence fallback and
 * reaches the model through the load report.
 */
import * as React from 'react';
const TIMER_REDIRECT = 'browser timer globals are unavailable in dynamic packages. Declare inject: [\'timer\'] on the returned plugin, '
    + 'query Client Service.listService for the exact API, and close over that plugin ctx. In React, create timers '
    + 'from an event handler or React.useEffect and return callback-form disposers from the effect cleanup.';
/**
 * Where each withheld browser global sends the author instead. One home for two
 * consumers: the closure traps below throw these, and a render crash whose
 * message names one of them gets the same redirect appended — a package that
 * reached the global some other way (`window.setInterval`) crashes with the
 * engine's own bare text, and the author needs the redirect either way.
 */
export const DYNAMIC_CLIENT_REDIRECTS = {
    setTimeout: TIMER_REDIRECT,
    setInterval: TIMER_REDIRECT,
    clearTimeout: TIMER_REDIRECT,
    clearInterval: TIMER_REDIRECT,
    fetch: 'network belongs to the HOST half: register a handler there with harness.handle(method, fn) and call it here via host.call(method, args).',
    require: 'modules cannot be imported here. React arrives as the `React` closure symbol; everything else goes through ctx services or host.call.',
};
/** Callable teaching traps shadowing the ambient globals the closure must not reach. */
function closureTraps() {
    const traps = {};
    for (const [name, redirect] of Object.entries(DYNAMIC_CLIENT_REDIRECTS)) {
        traps[name] = () => {
            throw new Error(`${name} is not available in a dynamic client half — ${redirect}`);
        };
    }
    return traps;
}
/** The `harness` seat exists only host-side; any touch teaches the split. */
function harnessTrap() {
    return new Proxy({}, {
        get(_target, prop) {
            throw new Error(`harness.${String(prop)} belongs to the HOST half (\`code\`): register handlers there with harness.handle(method, fn); `
                + 'the browser half calls them via host.call(method, args).');
        },
    });
}
/** Per-package style-tag bookkeeping behind the `styles.insert` symbol. */
export class DynamicCordisStyles {
    pluginId;
    tags = new Set();
    /** @param pluginId - owning Plugin ID, stamped as `data-dyn` on every tag. */
    constructor(pluginId) {
        this.pluginId = pluginId;
    }
    /**
     * Inject one stylesheet, removed automatically on package unload.
     * @param css - raw CSS text.
     * @returns disposer removing this one tag early.
     */
    insert(css) {
        if (typeof css !== 'string')
            throw new Error('styles.insert(css) needs a CSS string');
        const tag = document.createElement('style');
        const nonce = document.querySelector('meta[name="dsh-style-nonce"]')?.content;
        if (nonce)
            tag.nonce = nonce;
        tag.dataset.dyn = this.pluginId;
        tag.textContent = css;
        document.head.append(tag);
        this.tags.add(tag);
        return () => {
            this.tags.delete(tag);
            tag.remove();
        };
    }
    /** Live tag count (load-report contribution summary). */
    get count() {
        return this.tags.size;
    }
    /** Remove every tag this package still owns (unload path). */
    dispose() {
        for (const tag of this.tags)
            tag.remove();
        this.tags.clear();
    }
}
/** Stringify one console argument for the error mirror. */
function errorText(arg) {
    if (arg instanceof Error)
        return arg.message;
    if (Object.prototype.toString.call(arg) === '[object Error]')
        return arg.message;
    if (typeof arg === 'string')
        return arg;
    if (arg === undefined)
        return 'undefined';
    try {
        return JSON.stringify(arg);
    }
    catch {
        // A circular or otherwise non-serializable console argument: the mirror
        // carries the message, and nothing else here can fail.
        return '[unserializable console argument]';
    }
}
/** Tagged write-through console; error lines additionally copy into the load report. */
function taggedConsole(pluginId, noteError) {
    const tag = `[cordis:${pluginId}]`;
    const forward = (level) => (...args) => {
        console[level](tag, ...args);
        if (level !== 'error')
            return;
        noteError(args.map(errorText).join(' ').slice(0, 500));
    };
    return {
        ...console,
        log: forward('log'),
        info: forward('info'),
        warn: forward('warn'),
        error: forward('error'),
        debug: forward('debug'),
    };
}
/**
 * Narrow a closure return value to a mountable plugin (host guard mirror).
 * @param value - whatever the closure returned.
 * @returns whether the value is mountable.
 */
export function isDynamicCordisPlugin(value) {
    if (typeof value === 'function')
        return true;
    return typeof value === 'object' && value !== null
        && typeof value.apply === 'function';
}
let compilationSequence = 0;
/** Compile Host-authorized source through an ephemeral, nonce-authorized script. */
function compileClientHalf(parameters, clientCode) {
    const script = document.createElement('script');
    const nonce = document.querySelector('meta[name="dsh-script-nonce"]')?.content;
    if (nonce)
        script.nonce = nonce;
    const sourceUrl = new URL(`dsh-dynamic-client-${String(++compilationSequence)}.js`, document.baseURI).href;
    let compiled;
    let parseFailure;
    script.receive = (closure) => { compiled = closure; };
    // Only getClientCode for an authorized, Session-owned active run reaches here.
    // textContent preserves source text without interpreting HTML delimiters.
    script.textContent = `document.currentScript.receive(function(${parameters.join(',')}) { return (async () => {\n${clientCode}\n})() });\n//# sourceURL=${sourceUrl}`;
    const onError = (event) => {
        if (event.filename !== sourceUrl && document.currentScript !== script)
            return;
        parseFailure = event;
        event.preventDefault();
    };
    window.addEventListener('error', onError);
    try {
        document.head.append(script);
    }
    finally {
        window.removeEventListener('error', onError);
        script.remove();
        delete script.receive;
    }
    if (parseFailure !== undefined)
        throw new SyntaxError(parseFailure.message);
    if (compiled === undefined)
        throw new Error('Content Security Policy refused the dynamic client half script.');
    return compiled;
}
/**
 * Evaluate one package's browser half and return the (un-guarded) plugin.
 * @param pluginId - stable Plugin ID (console tag and style ownership).
 * @param clientCode - source fetched from the Host for this Session's authorized active run, never rendered content.
 * @param env - runner wiring for `host.call` and error mirroring.
 * @param styles - the package's style bookkeeping (owned by the caller so unload can dispose it).
 * @returns the plugin the closure returned.
 * @throws teaching errors for syntax failures and non-plugin returns.
 */
export async function evaluateClientHalf(pluginId, clientCode, env, styles) {
    const traps = closureTraps();
    const parameters = ['React', 'console', 'styles', 'host', 'harness', ...Object.keys(traps), 'process', 'Buffer'];
    let closure;
    try {
        closure = compileClientHalf(parameters, clientCode);
    }
    catch (error) {
        if (!(error instanceof SyntaxError))
            throw error;
        // Engine-divergence fallback: the host precheck already carried the
        // line/caret teaching; browsers give only the message.
        throw new Error(`client half failed to parse in this browser: ${error.message}\n`
            + 'The browser half is plain JavaScript (no JSX, no TypeScript); build elements with React.createElement.');
    }
    const host = {
        /**
         * Call a host-half handler of THIS package (harness.handle pairing). A call
         * with nothing to pass omits the argument: it arrives at the handler as
         * `null`, because the wire carries JSON and `undefined` is not JSON —
         * requiring `host.call('m', {})` would be a ritual, and defaulting to `{}`
         * would invent an empty argument the caller never wrote.
         */
        call: (method, args = null) => env.invoke(method, args),
    };
    const returned = await closure(React, taggedConsole(pluginId, (message) => { env.noteError(message); }), styles, host, harnessTrap(), ...Object.values(traps), undefined, // process: undefined keeps `typeof process` probes safe
    undefined);
    if (!isDynamicCordisPlugin(returned)) {
        if (returned === undefined) {
            throw new Error('client half returned `undefined` — did you forget `return`?\n'
                + '  ✓ return (ctx) => { … }\n'
                + '  ✓ return { name: \'…\', inject: [\'slots\'], apply(ctx) { … } }');
        }
        throw new Error('client half must `return` a plugin: a function, or an object with an `apply(ctx)` method');
    }
    return returned;
}
//# sourceMappingURL=evaluator.js.map
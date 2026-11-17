/**
 * Whole-client test carrier: boots an {@link AssemblyPlan} through the
 * production `bootClient` over an in-process module table, with a
 * `RemoteMock` installed as the Connection carrier through `__DSH_TRANSPORT__.rpc`.
 * @module @deepseek-ai/dsh-client-test-runtime/src/assembly/test-client
 */
import { Context } from '@deepseek-ai/cordis';
import { tearDownEntryFiber } from '@deepseek-ai/dsh-client-hmr/client';
import { bootClient } from '@deepseek-ai/dsh-client-web/src/boot-client.ts';
import { mountClient } from '@deepseek-ai/dsh-client-web/src/mount.ts';
import { act } from '@testing-library/react';
import { createInProcessModules, loadPluginModules } from "./modules.js";
import { assertPlan, graphFromRoster } from "./roster.js";
import { REMOTES_PACKAGE, remoteNamespacesOf, remoteProxiesPlugin } from "./remote-proxies.js";
const transportGlobal = globalThis;
/**
 * Process globals every live client in this worker shares: the transport the
 * `connection` plugin reads at apply, and the jsdom shims. The first holder
 * installs them and remembers what was there; the last release removes the
 * shims and restores the transport. A holder installs its transport for its
 * own boot and for each rebuild it runs, both under the worker's boot turn, so
 * overlapping clients and out-of-order disposal neither clobber a booting or
 * rebuilding client nor leak into later tests.
 */
class SharedGlobals {
    holders = 0;
    previousTransport;
    removeShims;
    /**
     * Point the transport global at `transport` for the boot or rebuild about to run; the holder must already hold.
     * @param transport - carrier the next `connection` apply reads.
     */
    install(transport) {
        transportGlobal.__DSH_TRANSPORT__ = transport;
    }
    /**
     * Hold the globals with `transport` installed.
     * @param transport - carrier the next boot reads.
     * @returns the release for this holder.
     */
    acquire(transport) {
        if (this.holders === 0) {
            this.previousTransport = transportGlobal.__DSH_TRANSPORT__;
            this.removeShims = installJsdomShims();
        }
        this.holders += 1;
        this.install(transport);
        return () => {
            this.holders -= 1;
            if (this.holders > 0)
                return;
            this.removeShims?.();
            this.removeShims = undefined;
            if (this.previousTransport === undefined)
                delete transportGlobal.__DSH_TRANSPORT__;
            else
                transportGlobal.__DSH_TRANSPORT__ = this.previousTransport;
            this.previousTransport = undefined;
        };
    }
}
const sharedGlobals = new SharedGlobals();
/**
 * Boots and entry rebuilds run one at a time per worker: the transport global
 * must stay the acting client's until its `connection` row applies.
 */
let bootTurn = Promise.resolve();
/** Run `work` as the next boot turn; its failure is the caller's, never the next turn's. */
function takeTurn(work) {
    const turn = bootTurn.then(work);
    bootTurn = turn.catch(() => undefined);
    return turn;
}
/** Default readiness budget; the mock answers `$events` immediately, so a miss means a boot-time fixture is absent. */
const DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
/**
 * Browser globals jsdom lacks that roster plugins touch at apply or mount:
 * client-hmr opens an `EventSource`, layout components observe element size.
 * Inert stand-ins, installed only where the global is absent.
 */
const JSDOM_SHIMS = {
    EventSource: class {
        addEventListener() { }
        close() { }
    },
    ResizeObserver: class {
        observe() { }
        disconnect() { }
    },
};
/** Install a shim for each absent global; the disposer deletes exactly those. */
function installJsdomShims() {
    const globals = globalThis;
    const installed = Object.keys(JSDOM_SHIMS).filter(name => globals[name] === undefined);
    for (const name of installed)
        globals[name] = JSDOM_SHIMS[name];
    return () => { for (const name of installed)
        Reflect.deleteProperty(globals, name); };
}
/** The Error a thrown value stands for: itself, or a new Error carrying its string form. */
function toError(error) {
    return error instanceof Error ? error : new Error(String(error));
}
function messageOf(error) {
    return toError(error).message;
}
function connectionOf(ctx) {
    const connection = ctx.get('connection');
    if (connection === undefined) {
        throw new Error('client-test-runtime: the roster provides no `connection` service');
    }
    return connection;
}
function resolveMountPoint(mount) {
    if (mount === undefined || mount === false)
        return { element: undefined, owned: false };
    if (typeof document === 'undefined') {
        throw new Error('client-test-runtime: mount requires a DOM; add `// @vitest-environment jsdom` to the spec');
    }
    if (mount === true) {
        const element = document.createElement('div');
        document.body.appendChild(element);
        return { element, owned: true };
    }
    return { element: mount, owned: false };
}
/** Run `fn` inside React `act` when a DOM exists; plain await otherwise. */
async function settle(fn) {
    if (typeof document === 'undefined') {
        await fn();
        return;
    }
    await act(async () => { await fn(); });
}
async function awaitConnected(ctx, mock, timeoutMs) {
    const { state } = connectionOf(ctx);
    if (state.getSnapshot() === 'connected')
        return;
    await new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            unsubscribe();
            const unmatched = mock.log.unmatched().map(row => `${row.mode} ${row.endpoint}`);
            const streams = mock.log.streams().map(row => `${row.endpoint} (${row.state})`);
            reject(new Error(`client-test-runtime: connection state is ${String(state.getSnapshot())} after ${timeoutMs}ms; `
                + `unmatched: [${unmatched.join(', ')}]; streams: [${streams.join(', ')}]`));
        }, timeoutMs);
        const unsubscribe = state.subscribe(() => {
            if (state.getSnapshot() !== 'connected')
                return;
            clearTimeout(timer);
            unsubscribe();
            resolve();
        });
    });
}
/** A booted client under test. */
export class TestClient {
    ctx;
    mock;
    container;
    restore;
    /**
     * Load the roster's modules, then, holding this worker's boot turn, install
     * the mock as the Connection carrier and the jsdom shims and boot through
     * `bootClient` over the synthesized boot graph; afterwards optionally mount
     * and wait for the connection. The `@deepseek-ai/dsh-api-remotes` row is
     * dropped from the roster: its generated Remote clients exist only in built
     * `lib/`, and the `remote.<ns>` services the roster injects (plus the
     * namespaces the mock has rules for at this point) are provided as
     * contract-free proxies over the same Connection instead; a `provide` entry
     * for that row is refused. On any failure the context is disposed, an owned
     * mount removed, and this client's hold on the globals released before the
     * original error is rethrown.
     * @param plan - roster and annotations.
     * @param mock - Remote mock answering every Gateway call.
     * @param options - mount and readiness options.
     * @returns the booted client.
     */
    static async start(plan, mock, options = {}) {
        assertPlan(plan);
        if (plan.provide?.[REMOTES_PACKAGE] !== undefined) {
            throw new Error(`client-test-runtime: ${REMOTES_PACKAGE} cannot be provided; its remote.<ns> services are the tier's proxies`);
        }
        const roster = plan.roster.rows.some(row => row.name === REMOTES_PACKAGE)
            ? plan.roster.without([REMOTES_PACKAGE])
            : plan.roster;
        const ctx = new Context();
        const transport = { rpc: mock.rpc };
        let mountPoint = { element: undefined, owned: false };
        let release;
        const restore = () => {
            if (mountPoint.owned)
                mountPoint.element?.remove();
            release?.();
        };
        try {
            const modules = await loadPluginModules({ ...plan, roster });
            mountPoint = resolveMountPoint(options.mount);
            await takeTurn(async () => {
                release = sharedGlobals.acquire(transport);
                const system = createInProcessModules(graphFromRoster(roster.rows), modules);
                ctx.plugin(remoteProxiesPlugin(remoteNamespacesOf(modules.values(), mock), mock));
                await bootClient({ ctx, modules: system, manifest: system.manifest });
            });
            if (mountPoint.element !== undefined) {
                if (ctx.get('uiRenderer') === undefined) {
                    throw new Error('client-test-runtime: mount requested, but the roster provides no `uiRenderer`');
                }
                await mountClient(ctx, mountPoint.element);
            }
            if (options.awaitConnected !== false) {
                await awaitConnected(ctx, mock, options.connectTimeoutMs ?? DEFAULT_CONNECT_TIMEOUT_MS);
            }
        }
        catch (error) {
            try {
                await settle(() => ctx.fiber.dispose());
            }
            catch {
                // The boot failure is the error to report; a half-built tree failing to dispose adds nothing to it.
            }
            finally {
                restore();
            }
            throw error;
        }
        return new TestClient(ctx, mock, mountPoint.element, restore);
    }
    disposing;
    constructor(ctx, mock, container, restore) {
        this.ctx = ctx;
        this.mock = mock;
        this.container = container;
        this.restore = restore;
    }
    /** The carrier this client's `connection` row reads when it applies. */
    get transport() {
        return { rpc: this.mock.rpc };
    }
    /** The roster's Connection service (no `Context` augmentation declares it); throws when the roster provides none. */
    get connection() {
        return connectionOf(this.ctx);
    }
    /** Flush pending React work and microtasks inside `act` (plain microtask flush without a DOM). */
    async flush() {
        await settle(() => Promise.resolve());
    }
    /**
     * Rebuild one Loader entry: client-hmr's registry-first fiber teardown, then `entry.refresh()`. The rebuild
     * takes the worker's boot turn with this client's carrier installed, so a rebuilt `connection` row reads its
     * own mock even while another client is live. Requires a live client: after `dispose()` the Loader holds no
     * entries and the lookup throws before anything is installed.
     * @param name - package name of the row.
     */
    async reload(name) {
        const entry = this.entryOf(name);
        await takeTurn(async () => {
            sharedGlobals.install(this.transport);
            await tearDownEntryFiber(entry);
            await entry.refresh();
            await this.ctx.loader.await();
        });
    }
    /**
     * Remove one Loader entry.
     * @param name - package name of the row.
     */
    async unload(name) {
        await this.ctx.loader.remove(this.entryOf(name).id);
    }
    /**
     * Dispose the plugin tree, then drop an owned mount and release this
     * client's hold on the shared globals even when the tree fails to dispose,
     * then `mock.assertNoUnmatched()` last so its failure is the test's reason
     * without skipping the cleanup; when both the tree and the check fail, one
     * error carries both messages. The first call owns the teardown and reports
     * its failure; every later call waits for that teardown and resolves.
     */
    async dispose() {
        if (this.disposing !== undefined) {
            await this.disposing.catch(() => undefined);
            return;
        }
        this.disposing = this.teardown();
        await this.disposing;
    }
    async teardown() {
        let failure;
        try {
            await settle(() => this.ctx.fiber.dispose());
        }
        catch (error) {
            failure = toError(error);
        }
        finally {
            this.restore();
        }
        if (failure === undefined) {
            this.mock.assertNoUnmatched();
            return;
        }
        try {
            this.mock.assertNoUnmatched();
        }
        catch (unmatched) {
            throw new Error(`${messageOf(unmatched)}\nclient-test-runtime: the plugin tree also failed to dispose: ${failure.message}`, { cause: failure });
        }
        throw failure;
    }
    entryOf(name) {
        const entries = [...this.ctx.loader.entries()];
        const entry = entries.find(candidate => candidate.options.name === name);
        if (entry === undefined) {
            throw new Error(`client-test-runtime: no Loader entry named ${name}; entries: ${entries.map(row => row.options.name).join(', ')}`);
        }
        return entry;
    }
}
//# sourceMappingURL=test-client.js.map
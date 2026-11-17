/**
 * Node half of the client module system (`dsh.client` dual-face package): scans
 * the host Loader's entries for packages declaring `dsh.client`, composes the
 * `window.__DSH_BOOT__` entry graph (wire single source: {@link WebBootEntry}
 * in `./client/manifest.ts`) in module-graph order, serves one-or-more-plugin
 * combo scripts plus their source maps,
 * contributes the registration facade, application preloads, bootstrap scripts,
 * and graph to the webserver's index injection table, and provides the
 * `clientModuleHost` service (the HMR node half's registration/notification
 * face).
 *
 * Scanning is incremental per package — there is no full-rescan code path.
 * Every cordis `internal/plugin` emission (fiber construction/disposal) marks
 * the fiber's entry name dirty; a microtask flush reconciles each dirty name
 * against the live loader entries. The activation pass seeds the same dirty
 * set with all current entries and flushes synchronously, so first scan and
 * steady state share one implementation. Package metadata (including the
 * negative "not a client package" verdict) is cached per Loader specifier and
 * owning-tree base URL until restart. The manifest package name identifies
 * the browser module; distinct active Loader sources for that package are a
 * composition error. Bundle content changes reach the graph only through
 * {@link ClientModuleRegistry.rebuilt}.
 * @module @deepseek-ai/dsh-client-modules
 */
import { Service } from '@deepseek-ai/cordis';
import type { Context } from '@deepseek-ai/cordis';
import z from '@deepseek-ai/schemastery';
import type { IndexInjection } from '@deepseek-ai/dsh-host-webserver';
import type { WebBootEntry, WebBootGraph } from './client/manifest.ts';
export { stripClientSuffix } from './client/manifest.ts';
export type { BootManifest, BootModuleRow, BootPluginRow, WebBootBatch, WebBootBatchPhase, WebBootEntry, WebBootGraph, } from './client/manifest.ts';
declare module '@deepseek-ai/cordis' {
    interface Context {
        /** The web plugin table (provided by the client-modules node half). */
        clientModules: ClientModuleRegistry;
    }
}
/** Filesystem baseline captured before a client artifact snapshot is read. */
export interface ClientArtifactBaseline {
    /** Absolute path of the client bundle. */
    readonly path: string;
    /** Bundle modification time in milliseconds. */
    readonly mtimeMs: number;
    /** Bundle size in bytes. */
    readonly size: number;
}
/** Client rows whose owning desktop bundle passed installation preflight. */
export declare const Config: z<Schemastery.ObjectS<{
    optionalPackages: z<string[], string[]>;
}>, Schemastery.ObjectT<{
    optionalPackages: z<string[], string[]>;
}>>;
/** Host profile configuration accepted by the client module registry. */
export interface Config {
    /** Package names whose failed browser bundles may be isolated after desktop preflight. */
    optionalPackages: string[];
}
/**
 * Order composed rows so every requested dynamic package precedes its
 * consumers. An `external` specifier is either the package row it names
 * (`<pkg>/client` aliases the bare package) or a static-table name that adds no
 * graph edge.
 * @param entries - composed rows in scan order.
 * @param optionalPackages - optional packages whose artifacts may be absent from the graph.
 * @returns the same rows reordered; scan order breaks every tie.
 * @throws {Error} when a row requests itself or when the module graph has a
 * cycle; the message lists the packages on it.
 */
export declare function orderByModuleGraph(entries: readonly WebBootEntry[], optionalPackages?: ReadonlySet<string>): WebBootEntry[];
/**
 * The boot protocol as index injection rows. The inline registration queue
 * precedes the application-batch preload and the blocking bootstrap batch. Its
 * `create()` method materializes the modules
 * bundle, delegates construction to that bundle, and leaves the same facade
 * in live-registration mode. The graph global follows before the shell reads
 * it.
 * @param graph - the composed entry graph.
 * @returns head rows in execution order: queue script, application preloads,
 * blocking bootstrap scripts, graph global.
 */
export declare function bootInjections(graph: WebBootGraph): IndexInjection[];
/**
 * The web plugin table service: incremental `dsh.client` scan + wire composition
 * + bundle route + index injection rows. Construction runs the activation scan
 * synchronously. Malformed metadata and missing artifacts skip explicitly
 * optional feature clients with a warning; required clients fail activation.
 */
export declare class ClientModuleRegistry extends Service {
    private readonly config;
    static inject: string[];
    static Config: z<Schemastery.ObjectS<{
        optionalPackages: z<string[], string[]>;
    }>, Schemastery.ObjectT<{
        optionalPackages: z<string[], string[]>;
    }>>;
    private readonly table;
    private readonly sources;
    private readonly knownOptionalClientIds;
    private readonly pkgMeta;
    private readonly rebuildListeners;
    private readonly graphListeners;
    private readonly dirty;
    private readonly initialRevisionNonce;
    private nextInitialRevision;
    private responses;
    private batchResponses;
    /** One prior graph generation covers a request racing the HMR recomposition that replaced its URL. */
    private previousBatchResponses;
    private flushQueued;
    private composed;
    /**
     * Build the service: subscribe, seed, and run the activation flush.
     * @param ctx - plugin context carrying Loader and an optional Web carrier.
     */
    constructor(ctx: Context, config?: Config);
    /**
     * Current composed entry graph (stable object between changes).
     * @returns the graph served as `window.__DSH_BOOT__`.
     */
    graph(): WebBootGraph;
    /**
     * Absolute path of an entry's client bundle.
     * @param id - entry id (package name).
     * @returns the path, or undefined for an unknown id.
     */
    clientPath(id: string): string | undefined;
    /**
     * Serve an advertised revisioned bundle or source map without a Web server.
     * Unknown URLs return 404, unsupported methods return 405, and `HEAD`
     * returns the same immutable headers without a body.
     * @param request - shell-carrier request for a `/plugins` resource.
     * @returns the exact response also exposed by the optional Web route.
     */
    fetchBundle(request: Request): Response;
    /**
     * Filesystem baseline captured before an entry's current bytes were read.
     * HMR compares it with the live files when installing a watch, so a write
     * between startup composition and watch installation cannot disappear into
     * the watcher's initial state.
     * @param id - entry id (package name).
     * @returns the path and baseline, or undefined for an unknown id.
     */
    artifactBaseline(id: string): ClientArtifactBaseline | undefined;
    /**
     * Re-hash one bundle (the HMR watch's registration hook — the only entry
     * point through which bundle content changes reach the graph).
     * @param id - entry id (package name).
     * @returns the new rev, or undefined for an unknown id.
     */
    rebuilt(id: string): string | undefined;
    /**
     * Subscribe to bundle rebuilds; fires only when the re-hash changed the rev.
     * @param listener - receives the entry id and its new bundle rev.
     * @returns the unsubscriber.
     */
    onRebuilt(listener: (id: string, rev: string) => void): () => void;
    /**
     * Fires after any flush that recomposed the graph (row added/removed, or a
     * rebuilt rev change). Pull model: listeners re-read {@link graph}.
     * @param listener - notified with no payload.
     * @returns the unsubscriber.
     */
    onGraphChanged(listener: () => void): () => void;
    private compose;
    private notifyGraphChanged;
    private resolveMeta;
    /** Omit one explicitly optional feature package and retain an actionable diagnostic. */
    private skipOptionalClient;
    /**
     * Locate the manifest of the package the Loader mounts for a row. The row's
     * module location is authoritative: the specifier resolves through the same
     * Loader resolution that imported the row's host half — including any
     * active ESM hooks — and the nearest ancestor manifest declaring the name
     * owns the module. Tree-anchored `require` resolution remains only for
     * runtimes without Node internals.
     * @param loaderName - module specifier of the loader row.
     * @param baseUrl - resolution base of the tree that owns the row.
     * @returns the manifest path, or `undefined` when the name resolves to no package root.
     */
    private locatePkgJson;
    private nearestPackage;
    private sourceKey;
    /** Capture the bundle stats before reading its bytes. */
    private captureArtifactBaseline;
    /** Allocate an opaque initial row revision without inspecting artifact bytes. */
    private allocateInitialRevision;
    /**
     * Read the activation-time bundle and optional source-map snapshots.
     * @param pkgName - package that declares the client bundle.
     * @param clientPath - absolute path of the built client artifact.
     * @returns the immutable bytes plus the pre-read filesystem baseline.
     * @throws {MissingClientBundleError} when the read fails with `ENOENT`; other filesystem errors are rethrown unchanged.
     */
    private initialBundleSnapshot;
    /** Treat a missing, torn, or malformed development map as an identity-mapped artifact revision. */
    private readSourceMapSnapshot;
    /** Reconcile one entry name against the live Loader sources. @returns whether the table changed. */
    private processOne;
    private resolveSource;
    private reconcilePackage;
    private flush;
    private bundleResource;
    private readonly serveBundle;
}
export default ClientModuleRegistry;
//# sourceMappingURL=index.d.ts.map
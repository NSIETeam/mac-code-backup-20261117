/**
 * Production client composition without the page: mount the Loader over a
 * module system, activate required rows, audit them, and start optional rows
 * without waiting for their activation. `AppWebEntry` and the whole-client
 * test carrier both call it.
 * @module @deepseek-ai/dsh-client-web/src/boot-client
 */
import type { Context } from '@deepseek-ai/cordis';
import type { BootManifest, ClientModuleLoader } from '@deepseek-ai/dsh-client-modules/client';
import { STATE_LABELS } from './loader-status.ts';
/** Entry state label as the boot page renders it. */
export type EntryStateLabel = (typeof STATE_LABELS)[keyof typeof STATE_LABELS] | 'loading' | 'failed';
/** Inputs of {@link bootClient}. */
export interface ClientBootOptions {
    /** Fresh root Context that will own the plugin tree. */
    readonly ctx: Context;
    /** Module system installed as `loader.internal`. */
    readonly modules: ClientModuleLoader;
    /** Parsed manifest whose `plugins` rows become Loader entries (entry name = row id). */
    readonly manifest: BootManifest;
    /** Per-entry state reporting (the boot page); omitted when no one renders progress. */
    readonly onEntryState?: (name: string, state: EntryStateLabel) => void;
}
/**
 * Compose the client: activate and audit required rows before returning, then
 * start optional rows in the background. A required row whose module cannot be
 * imported rejects `loader.create`, so that import error propagates from here.
 * @param options - context, module system, manifest, optional progress sink.
 * @returns resolves after every entry is active; rejects with the audit report otherwise.
 */
export declare function bootClient(options: ClientBootOptions): Promise<void>;
/**
 * Reject entries that failed import/apply or still wait on missing services.
 * @param ctx - root Context carrying the Loader.
 * @throws {Error} listing every non-active entry with its reason.
 */
export declare function assertEntriesActive(ctx: Context): void;
//# sourceMappingURL=boot-client.d.ts.map
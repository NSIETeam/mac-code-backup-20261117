/**
 * Persistent shell PTY backend over the subprocess terminal primitive, shared
 * sandbox policy, bounded output, and provider-owned session cleanup.
 * @module @deepseek-ai/dsh-terminal-bash
 */
import { Context } from '@deepseek-ai/cordis';
import type { TerminalBackend, TerminalBackendSpawnSpec } from '@deepseek-ai/dsh-terminal';
import type { SubprocessTerminalHandle, SubprocessTerminalSpawnSpec } from '@deepseek-ai/dsh-subprocess';
import { type Config, type ResolvedConfig } from './config.ts';
import { LocalPtySession } from './session.ts';
export { Config } from './config.ts';
export type { Config as TerminalLocalConfig } from './config.ts';
/** Cordis plugin name. */
export declare const name = "terminal-bash";
/** Required services: terminal registry, shared confinement policy, projection registry, and process substrate. */
export declare const inject: string[];
/**
 * The pwsh prompt function that emits the shared OSC `133;D;` + BEL marker
 * before every prompt, mirroring bash's PROMPT_COMMAND. `[char]27`/`[char]7`
 * build the control bytes at runtime because raw ESC characters in submitted
 * input are unreliable under PSReadLine.
 */
export declare const PWSH_PROMPT_SETUP: string;
/**
 * Removes PSReadLine from the hosted pwsh session on Windows. ConPTY
 * re-serializes PSReadLine's multi-pass syntax-highlight rendering into the
 * output stream as erase sequences and literal space runs, and the
 * append-only sanitized scrollback keeps that residue beside real command
 * output. POSIX keeps PSReadLine: its renderer is single-pass there, and the
 * no-PSReadLine fallback editor is not PTY-safe.
 */
export declare const PWSH_DISABLE_PSREADLINE = "Remove-Module PSReadLine -ErrorAction SilentlyContinue; ";
/**
 * The bootstrap line installed as the pwsh session's first submitted command.
 * @param platform - the host platform; PSReadLine removal is Windows-only.
 * @returns the full bootstrap expression.
 */
export declare function pwshBootstrap(platform: NodeJS.Platform): string;
/** Local shell backend registered under the configured type. */
export declare class BashTerminalBackend implements TerminalBackend {
    private readonly ctx;
    private readonly config;
    private readonly spawnTerminal;
    private readonly createSession;
    private readonly platform;
    readonly type: string;
    constructor(ctx: Context, config: ResolvedConfig, spawnTerminal?: (spec: SubprocessTerminalSpawnSpec) => Promise<SubprocessTerminalHandle>, createSession?: (terminal: SubprocessTerminalHandle, config: ResolvedConfig) => LocalPtySession, platform?: NodeJS.Platform);
    spawn(spec: TerminalBackendSpawnSpec): Promise<LocalPtySession>;
}
/** Register the local PTY backend. */
export declare function apply(ctx: Context, config: Config): void;
//# sourceMappingURL=index.d.ts.map
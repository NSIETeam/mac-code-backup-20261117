/**
 * Provider-routed model-request retry policy on the agent loop's request
 * recovery extension point. Each scheduled retry is durable before its cancellable wait.
 *
 * @module @deepseek-ai/dsh-llm-retry
 */
import { randomUUID } from 'node:crypto';
import z from '@deepseek-ai/schemastery';
import { z as zod } from 'zod';
import { CONTEXT_WINDOW_EXCEEDED_CODE, QUOTA_EXCEEDED_CODE } from '@deepseek-ai/dsh-llm';
import { RetryId } from "./brand.js";
export { RetryId } from "./brand.js";
export const name = 'llm-retry';
export const inject = ['agents', 'sessionProjections'];
/** Runtime schema for {@link Config}. */
export const Config = z.object({});
function validateConfig(config) {
    const [key] = Object.keys(config);
    if (key === undefined)
        return;
    if (key === 'retryPolicy') {
        throw new Error('llm-retry: retryPolicy belongs under each provider configuration');
    }
    throw new Error(`llm-retry: unknown key "${key}"`);
}
async function settleDownstream(next) {
    try {
        return { type: 'decision', decision: await next() };
    }
    catch (error) {
        return { type: 'error', error };
    }
}
function localDelay(config, retry, random) {
    const exponent = Math.min(retry - 1, 1024);
    const exponential = Math.min(config.initialDelayMs * 2 ** exponent, config.maxDelayMs);
    const jitter = 1 - config.jitterRatio + 2 * config.jitterRatio * random();
    return Math.min(exponential * jitter, config.maxDelayMs);
}
function retryPolicyKey(policy) {
    return policy.mode === 'always'
        ? JSON.stringify([policy.mode, policy.initialDelayMs, policy.maxDelayMs, policy.jitterRatio])
        : JSON.stringify([
            policy.mode,
            policy.maxRetries,
            [...policy.retryableCodes].sort(),
            policy.initialDelayMs,
            policy.maxDelayMs,
            policy.jitterRatio,
        ]);
}
function retryStateKey(provider, policyKey) {
    return JSON.stringify([provider, policyKey]);
}
/**
 * Failures that cannot become valid through another identical provider request.
 * Always mode remains useful for transient outages, but must not create an
 * unbounded request storm for credentials, quota, or an oversized prompt.
 */
function isPermanentFailure(failure) {
    return failure.status === 401
        || failure.status === 403
        || (failure.code === 'AUTH' && (/\b(?:401|403|unauthori[sz]ed|forbidden)\b/i.test(failure.message)
            || /\b(?:invalid|missing|expired|bad)\s+(?:api\s+)?(?:key|credential|token)\b/i.test(failure.message)))
        || failure.code === QUOTA_EXCEEDED_CODE
        || failure.code === CONTEXT_WINDOW_EXCEEDED_CODE
        || failure.code === 'INVALID_REQUEST'
        || failure.code === 'INVALID_CREDENTIAL'
        || failure.code === 'MISSING_CREDENTIAL'
        || failure.code === 'UNKNOWN_MODEL'
        || failure.code === 'NO_ADAPTER'
        || failure.code === 'UNSUPPORTED_OPTION';
}
function cancellableDelay(delayMs, signal) {
    if (signal.aborted)
        return Promise.resolve(false);
    return new Promise((resolve) => {
        const timer = setTimeout(() => {
            signal.removeEventListener('abort', onAbort);
            resolve(true);
        }, delayMs);
        function onAbort() {
            clearTimeout(timer);
            resolve(false);
        }
        signal.addEventListener('abort', onAbort, { once: true });
    });
}
// The cast bridges the branded retry id, which Zod cannot express directly.
const llmRetryStateSchema = zod.record(zod.string(), zod.object({
    retry: zod.number().int().nonnegative(),
    retryId: zod.string(),
}));
export function apply(ctx, config = {}, internals = {}) {
    validateConfig(config);
    ctx.sessionProjections.register({
        key: 'llmRetry',
        stateVersion: 1,
        stateSchema: llmRetryStateSchema,
        init: () => ({}),
        apply: (state, event) => {
            if (event.type === 'step/start' || event.type === 'turn/end')
                return {};
            if (event.type !== 'llm/retry')
                return state;
            const key = retryStateKey(event.data.provider, event.data.policyKey);
            const entry = state[key];
            if (entry?.retry === event.data.retry && entry.retryId === event.data.retryId)
                return state;
            return { ...state, [key]: { retry: event.data.retry, retryId: event.data.retryId } };
        },
    });
    const random = internals.random ?? Math.random;
    const lifetime = new AbortController();
    const active = new Set();
    function track(operation) {
        const tracked = operation.finally(() => active.delete(tracked));
        active.add(tracked);
        return tracked;
    }
    async function backoff(agent, turn, step, failure, provider, policy, policyKey, retry, retryId, delayMs, signal) {
        const fusedSignal = AbortSignal.any([signal, lifetime.signal]);
        if (fusedSignal.aborted)
            return;
        const eventData = policy.mode === 'normal'
            ? {
                retryId,
                turn,
                step,
                provider,
                mode: policy.mode,
                policyKey,
                retry,
                maxRetries: policy.maxRetries,
                delayMs,
                failure,
            }
            : {
                retryId,
                turn,
                step,
                provider,
                mode: policy.mode,
                policyKey,
                retry,
                delayMs,
                failure,
            };
        agent.session.append('llm/retry', eventData);
        if (!await cancellableDelay(delayMs, fusedSignal))
            return;
        agent.session.append('llm/retry-started', { retryId, turn, step, retry });
        return { kind: 'retry' };
    }
    async function recover({ agent, turn, step, provider, failure, retryPolicy: policy, signal }, next) {
        if (policy === undefined)
            return next();
        if (policy.mode === 'always') {
            if (signal.aborted || lifetime.signal.aborted)
                return;
            const fusedSignal = AbortSignal.any([signal, lifetime.signal]);
            // The loop and plugin lifetime stay open until delegated recovery settles.
            // An abort then wins before the decision or fallback can mutate later state.
            const downstream = await settleDownstream(next);
            if (fusedSignal.aborted)
                return;
            if (isPermanentFailure(failure)) {
                if (downstream.type === 'error')
                    throw downstream.error;
                return downstream.decision;
            }
            if (downstream.type === 'error') {
                ctx.logger.warn(`llm-retry: provider "${provider}" always policy ignored a downstream recovery failure: %o`, downstream.error);
            }
            if (downstream.type === 'decision' && downstream.decision?.kind === 'retry') {
                return downstream.decision;
            }
        }
        else if (isPermanentFailure(failure) || !policy.retryableCodes.includes(failure.code)) {
            return next();
        }
        const policyKey = retryPolicyKey(policy);
        const retryState = ctx.sessionProjections.stateOf(agent.session, 'llmRetry');
        const previous = retryState[retryStateKey(provider, policyKey)];
        const previousRetry = previous?.retry ?? 0;
        if (policy.mode === 'normal' && previousRetry >= policy.maxRetries)
            return next();
        const retry = previousRetry + 1;
        const retryId = previous?.retryId ?? RetryId(randomUUID());
        let delayMs;
        if (failure.providerRetryAfterMs !== undefined
            && Number.isFinite(failure.providerRetryAfterMs)
            && failure.providerRetryAfterMs > 0) {
            if (failure.providerRetryAfterMs > policy.maxDelayMs) {
                if (policy.mode === 'normal')
                    return next();
                delayMs = localDelay(policy, retry, random);
            }
            else {
                delayMs = failure.providerRetryAfterMs;
            }
        }
        else {
            delayMs = localDelay(policy, retry, random);
        }
        return backoff(agent, turn, step, failure, provider, policy, policyKey, retry, retryId, delayMs, signal);
    }
    const disposeListener = ctx.on('agent/request-error', (payload, next) => {
        // A waterfall may have captured this callback before its registration was
        // removed. Lifetime cancellation must prevent that stale callback from
        // entering a downstream policy after disposal.
        if (lifetime.signal.aborted)
            return Promise.resolve(undefined);
        return track(recover(payload, next));
    });
    ctx.effect(() => async () => {
        disposeListener();
        lifetime.abort(new Error('llm-retry plugin disposed'));
        await Promise.allSettled([...active]);
    }, 'llm-retry: abort and drain active recovery');
}
//# sourceMappingURL=index.js.map
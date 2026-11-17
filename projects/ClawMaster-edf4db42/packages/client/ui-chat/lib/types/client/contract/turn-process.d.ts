/** Current process range and finalized answer boundary derived from one Turn. */
export interface TurnProcessSpec {
    readonly turn: number;
    /** Stable control-node anchor source, including currently ineligible evidence. */
    readonly controlAnchorSeq: number;
    readonly processStartSeq: number;
    readonly answerAnchorSeq: number | null;
    readonly answerStep: number | null;
    readonly inlineReasoning: boolean;
    /** Reply-bearing durable Assistant messages before the final answer. */
    readonly messageCount: number;
    /** Durable non-subagent Tool calls recorded by this Turn. */
    readonly toolCallCount: number;
    /** Tool calls whose configured name identifies a subagent delegation. */
    readonly subagentCount: number;
}
/**
 * Newest folded process evidence, shown beside the running disclosure label so
 * a reader can tell what the Turn is doing without expanding it.
 */
export type TurnProcessActivity = {
    readonly kind: 'reasoning';
} | {
    readonly kind: 'message';
} | {
    readonly kind: 'tool';
    readonly name: string | null;
} | {
    readonly kind: 'context';
};
/**
 * Compare two running-activity values by their published fields.
 * @param left - previous activity, or null when none is published.
 * @param right - next activity, or null.
 * @returns whether both describe the same activity line.
 */
export declare function sameTurnProcessActivity(left: TurnProcessActivity | null, right: TurnProcessActivity | null): boolean;
/**
 * Whether the layout placed one Node inside its Turn's process region. Evidence
 * recorded before the opening human message is laid out behind that message —
 * the request prompt is the shipped case — and evidence from the process start
 * onward is what the Turn produced in place.
 * @param anchorSeq - the Node's own anchor.
 * @param processStartSeq - first anchor the Turn's process window covers.
 * @param openingHumanAnchor - anchor of the Turn's opening human message, if any.
 * @returns whether the Node folds with the Turn's process.
 */
export declare function insideTurnProcessRegion(anchorSeq: number, processStartSeq: number, openingHumanAnchor: number | undefined): boolean;
/**
 * Chat Node kinds that remain independent of a Turn's process disclosure.
 * A workspace or system prompt is deliberately absent: it is process evidence
 * like any injected context, so folding a Turn leaves exactly one summary row.
 */
export declare const TURN_PROCESS_INDEPENDENT_KINDS: ReadonlySet<string>;
/**
 * Compare immutable Turn-process specifications by their published fields.
 * @param left - previous specification.
 * @param right - next specification.
 * @returns whether both values describe the same process presentation.
 */
export declare function sameTurnProcessSpec(left: TurnProcessSpec, right: TurnProcessSpec): boolean;
/**
 * Recognize the shipped subagent delegation name and its configured variants.
 * Control tools use distinct names such as `send_message` and `list_agents`.
 * @param name - durable Tool-call name.
 * @returns whether the call creates or forks a subagent.
 */
export declare function isSubagentDelegationTool(name: string): boolean;
//# sourceMappingURL=turn-process.d.ts.map
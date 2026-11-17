/**
 * Compare two running-activity values by their published fields.
 * @param left - previous activity, or null when none is published.
 * @param right - next activity, or null.
 * @returns whether both describe the same activity line.
 */
export function sameTurnProcessActivity(left, right) {
    if (left === right)
        return true;
    if (left === null || right === null || left.kind !== right.kind)
        return false;
    return left.kind !== 'tool' || right.kind !== 'tool' || left.name === right.name;
}
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
export function insideTurnProcessRegion(anchorSeq, processStartSeq, openingHumanAnchor) {
    if (openingHumanAnchor === undefined)
        return anchorSeq >= processStartSeq;
    return anchorSeq < openingHumanAnchor || anchorSeq >= processStartSeq;
}
const TURN_PROCESS_INDEPENDENT_KIND_LIST = [
    'user',
    'steering',
    'turn-process',
    'turn-error',
    'turn-max-tokens',
    'turn-tail',
];
/**
 * Chat Node kinds that remain independent of a Turn's process disclosure.
 * A workspace or system prompt is deliberately absent: it is process evidence
 * like any injected context, so folding a Turn leaves exactly one summary row.
 */
export const TURN_PROCESS_INDEPENDENT_KINDS = new Set(TURN_PROCESS_INDEPENDENT_KIND_LIST);
/**
 * Compare immutable Turn-process specifications by their published fields.
 * @param left - previous specification.
 * @param right - next specification.
 * @returns whether both values describe the same process presentation.
 */
export function sameTurnProcessSpec(left, right) {
    return left.turn === right.turn
        && left.controlAnchorSeq === right.controlAnchorSeq
        && left.processStartSeq === right.processStartSeq
        && left.answerAnchorSeq === right.answerAnchorSeq
        && left.answerStep === right.answerStep
        && left.inlineReasoning === right.inlineReasoning
        && left.messageCount === right.messageCount
        && left.toolCallCount === right.toolCallCount
        && left.subagentCount === right.subagentCount;
}
/**
 * Recognize the shipped subagent delegation name and its configured variants.
 * Control tools use distinct names such as `send_message` and `list_agents`.
 * @param name - durable Tool-call name.
 * @returns whether the call creates or forks a subagent.
 */
export function isSubagentDelegationTool(name) {
    return name === 'subagent' || name.startsWith('subagent_');
}
//# sourceMappingURL=turn-process.js.map
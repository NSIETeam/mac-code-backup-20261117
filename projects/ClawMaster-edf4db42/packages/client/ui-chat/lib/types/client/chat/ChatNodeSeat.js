import { jsx as _jsx } from "react/jsx-runtime";
import { memo, useCallback, useMemo } from 'react';
import { JsonBlock } from '@deepseek-ai/dsh-client-ui-primitives';
import { insideTurnProcessRegion, TURN_PROCESS_INDEPENDENT_KINDS } from "../contract/turn-process.js";
import { storedTurnProcessEntry } from "../stores.js";
import { useSearchableHidden } from "./searchable-hidden.js";
import css from './ChatView.module.css';
/**
 * Disclosure key for a Turn whose answer step is not finalized yet. A stored
 * entry under this key never equals a closed Turn's real answer step, so an
 * expanded running row returns to the default collapsed state when it closes.
 */
const LIVE_PROCESS_STEP = -1;
/**
 * Evidence the running fold swallows. A live Turn folds exactly what a reader
 * asked to fold — reasoning, recalled or injected context, Tool calls and the
 * request prompt — so every other row keeps its place: a retry in flight, a
 * running compaction and any kind a future module adds stay readable until the
 * Turn closes, when the full process window applies as before.
 */
const RUNNING_FOLD_KINDS = new Set([
    'assistant-step', 'context', 'system-prompt', 'tool-call',
]);
function turnDataOf(node) {
    const location = node?.location;
    return location?.kind === 'turn' || location?.kind === 'step' ? location.turn.data : undefined;
}
function turnOf(node) {
    const location = node?.location;
    return location?.kind === 'turn' || location?.kind === 'step' ? location.turn.turn : undefined;
}
/** Subscribe, apply Turn-process visibility, and dispatch one stable Context key. */
export const ChatNodeSeat = memo(function ChatNodeSeat({ nodeKey, useChatNode, useChatNodeProcess, historyIncomplete, compactTranscript, cwd, openFile, openSkill, inspectCall, forkAt, loadImage, renderMessageImages, fileMentions, useStore, actions, renderSlot, t, }) {
    const node = useChatNode(nodeKey);
    const routedNode = node;
    const turn = turnOf(routedNode);
    const processPresentation = useChatNodeProcess(nodeKey);
    const processSpec = processPresentation?.spec;
    const storedEntry = useStore(state => processSpec === undefined
        ? undefined
        : storedTurnProcessEntry(state, processSpec.turn));
    // A running Turn has no finalized answer step yet, so its disclosure state is
    // kept under the live bucket and moves to the real answer step once it closes.
    const processStep = processSpec === undefined
        ? null
        : processSpec.answerStep ?? LIVE_PROCESS_STEP;
    const processEntry = processStep !== null && storedEntry?.answerStep === processStep
        ? storedEntry
        : undefined;
    const processOpen = processEntry !== undefined;
    const setOpen = useCallback((open) => {
        if (processSpec !== undefined && processStep !== null) {
            actions.setTurnProcessOpen(processSpec.turn, processStep, open);
        }
    }, [actions, processSpec, processStep]);
    const processWindowReady = processSpec !== undefined
        && processPresentation !== undefined
        && compactTranscript
        && processPresentation.turn === processSpec.turn
        && !historyIncomplete
        && (processPresentation.running || processSpec.answerAnchorSeq !== null);
    const processMember = routedNode !== undefined
        && processWindowReady
        && !TURN_PROCESS_INDEPENDENT_KINDS.has(routedNode.kind)
        && (!processPresentation.running || RUNNING_FOLD_KINDS.has(routedNode.kind))
        && insideTurnProcessRegion(routedNode.anchorSeq, processSpec.processStartSeq, processPresentation.openingHumanAnchor)
        && (processSpec.answerAnchorSeq === null || routedNode.anchorSeq < processSpec.answerAnchorSeq)
        && routedNode.key !== processPresentation.liveAnswerKey;
    const processAnswer = routedNode !== undefined
        && processWindowReady
        && routedNode.kind === 'assistant-step'
        && routedNode.data.step === processSpec.answerStep;
    const ownsDisclosure = routedNode?.kind === 'turn-process' || processAnswer;
    const foldable = processWindowReady
        && (processMember || (ownsDisclosure
            && (processPresentation.hasExternalProcess || processSpec.inlineReasoning)));
    const turnProcess = useMemo(() => processSpec === undefined || processPresentation === undefined
        ? undefined
        : {
            spec: processSpec,
            foldable,
            open: processOpen,
            running: processPresentation.running,
            activity: processPresentation.activity,
            setOpen,
        }, [
        foldable, processOpen, processPresentation, processSpec, setOpen,
    ]);
    const controllerInactive = routedNode?.kind === 'turn-process'
        && !foldable;
    const compactAnswer = processAnswer
        && foldable
        && processPresentation.compactAnswer
        && !processOpen;
    const processHidden = controllerInactive || (foldable && processMember && !processOpen);
    const revealProcess = useCallback(() => {
        if (processMember)
            setOpen(true);
    }, [processMember, setOpen]);
    const wrapperRef = useSearchableHidden(processHidden, revealProcess);
    const owner = useMemo(() => node === undefined
        ? null
        : {
            cwd,
            openFile,
            openSkill,
            inspectCall,
            forkAt,
            loadImage,
            renderMessageImages,
            fileMentions,
            turnProcess,
        }, [
        node, cwd, openFile, openSkill, inspectCall, forkAt,
        loadImage, renderMessageImages, fileMentions, turnProcess,
    ]);
    if (routedNode === undefined || owner === null)
        return null;
    const turnData = turnDataOf(routedNode);
    // Runtime dispatch owns the correlation: every Node's discriminant is the
    // keyed-slot entry passed alongside that same Node. TypeScript does not
    // distribute an object containing a union into a union of objects itself.
    const routedOwner = { ...owner, node: routedNode };
    return (_jsx("div", { ref: wrapperRef, className: css.flowItem, "data-chat-anchor-key": routedNode.key, "data-chat-flow-key": routedNode.key, "data-chat-flow-kind": routedNode.kind, "data-chat-turn": turn, "data-turn-process-member": processMember || undefined, "data-turn-process-hidden": processHidden || undefined, "data-turn-process-answer": compactAnswer || undefined, children: renderSlot('conversation.chat.node', routedOwner, {
            entryKey: routedNode.kind,
            hookContext: turnData,
            fallback: (_jsx(JsonBlock, { label: t('message.unknownSurface', { type: routedNode.kind }), payload: routedNode.data, truncatedLabel: total => t('json.truncated', { total }) })),
        }) }));
});
//# sourceMappingURL=ChatNodeSeat.js.map
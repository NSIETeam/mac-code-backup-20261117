import type { EditorConfig, LexicalEditor, SerializedTextNode } from 'lexical';
import { TextNode } from 'lexical';
/** JSON form of one text-ref node. */
export type SerializedTextRefNode = SerializedTextNode;
/** One matched plain-text reference as a styled, fully editable text node. */
export declare class TextRefNode extends TextNode {
    /**
     * Lexical node registry type tag.
     * @returns the serialized reference-node discriminator.
     */
    static getType(): string;
    /**
     * Clone with identity (Lexical writable-copy contract).
     * @param node - node to clone.
     * @returns a copy carrying the same NodeKey.
     */
    static clone(node: TextRefNode): TextRefNode;
    /**
     * Rebuild one text-ref from its JSON form.
     * @param json - serialized node.
     * @returns a fresh node.
     */
    static importJSON(json: SerializedTextRefNode): TextRefNode;
    /**
     * Serialize to the JSON node form.
     * @returns text state with the reference-node discriminator.
     */
    exportJSON(): SerializedTextRefNode;
    /**
     * Style the span the base TextNode mounts.
     * @param config - Lexical editor configuration used by the base node.
     * @returns the styled, editable reference element.
     */
    createDOM(config: EditorConfig): HTMLElement;
    /**
     * Entity nodes never merge with plain siblings (the transform owns their bounds).
     * @returns true to retain the reference as a text entity.
     */
    isTextEntity(): true;
    /**
     * Editing continues inside; the transform re-evaluates match shape per edit.
     * @returns true to permit insertion at the start of the reference.
     */
    canInsertTextBefore(): boolean;
}
/**
 * Register the plain-text reference entity transform. The claim decoration
 * has precedence on the leading-token seat: while a command claim holds, the
 * claimed token must stay a plain TextNode (transforms register per concrete
 * node class, so a TextRefNode would never receive the TextNode claim
 * transform and the warn color would be lost).
 * @param editor - the shell-owned editor.
 * @param lexiconOf - live per-trigger name-roll accessor (the controller's aggregated store).
 * @param activeToken - live claim token accessor; null while unclaimed.
 * @returns the unregister disposer.
 */
export declare function registerTextRefDecoration(editor: LexicalEditor, lexiconOf: () => ReadonlyMap<'/' | '@', readonly string[]>, activeToken: () => string | null): () => void;
/**
 * Force a re-scan of the whole document (transforms only visit dirty nodes;
 * a lexicon roll change dirties nothing on its own). Queued, not discrete —
 * the caller may sit inside an update listener.
 * @param editor - the shell-owned editor.
 */
export declare function rescanTextRefs(editor: LexicalEditor): void;
//# sourceMappingURL=text-ref.d.ts.map
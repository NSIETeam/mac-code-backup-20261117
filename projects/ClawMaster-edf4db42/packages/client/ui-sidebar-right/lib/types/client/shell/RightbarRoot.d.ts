/** Root-scoped controller for the right Sidebar's Session content. */
import type { PropsRenderSlots, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots';
/**
 * Keep the current Session's Sidebar mounted across global panel navigation.
 * @param props - frame geometry, panel selection, and the authorized Session renderer.
 * @returns the current Session's right Sidebar with its presentation visibility.
 */
export declare function RightbarRoot({ usePanelInfo, SessionProvider, renderSlot, width, viewportWidth, canShow, }: PropsRuntime<'rightbar'> & PropsRenderSlots<'rightbar.session'>): import("react").JSX.Element;
//# sourceMappingURL=RightbarRoot.d.ts.map
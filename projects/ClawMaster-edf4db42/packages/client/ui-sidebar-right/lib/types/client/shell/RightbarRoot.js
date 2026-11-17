import { jsx as _jsx } from "react/jsx-runtime";
/**
 * Keep the current Session's Sidebar mounted across global panel navigation.
 * @param props - frame geometry, panel selection, and the authorized Session renderer.
 * @returns the current Session's right Sidebar with its presentation visibility.
 */
export function RightbarRoot({ usePanelInfo, SessionProvider, renderSlot, width, viewportWidth, canShow, }) {
    const visible = usePanelInfo(info => info.activePanelId === null);
    return (_jsx(SessionProvider, { children: renderSlot('rightbar.session', { width, viewportWidth, canShow, visible }) }));
}
//# sourceMappingURL=RightbarRoot.js.map
// Generated from miniprogram/services/printer-status.ts; parity is covered by tests.
export function printerDisplayName(p) {
    if (p.name && p.queueName && p.name !== p.queueName)
        return p.name;
    return p.deviceName ? p.deviceName.replace(/东侧打印机$/, ' 东').replace(/西侧打印机$/, ' 西') : p.name;
}
function fresh(value, age, now) {
    const time = value ? Date.parse(value) : NaN;
    return Number.isFinite(time) && time <= now + 120000 && now - time < age;
}
export function printerStatus(p, now = Date.now()) {
    if (!fresh(p.lastSeen, 90000, now))
        return '设备离线 · 任务将排队';
    if (!fresh(p.statusAt, 45000, now))
        return '设备在线 · 打印机状态待更新';
    if (p.deviceReachable === false && fresh(p.deviceCheckedAt, 45000, now))
        return '打印机网络端口不可达 · 任务等待';
    const labels = { ready: '驱动未报告异常', busy: '正在打印', warning: '耗材等状态提醒', offline: '打印机脱机', paused: '打印机已暂停', error: '打印机异常', missing: '打印队列已移除', unknown: '打印机状态未知' };
    return labels[p.status || 'unknown'] || '打印机状态未知';
}
export function printerLoad(p, now = Date.now()) {
    const count = (value) => Number.isFinite(value) && value >= 0 ? Math.floor(value) : 0;
    const windows = count(p.jobCount), waiting = count(p.serverQueuedCount), active = count(p.serverActiveCount);
    // Claimed/submitting jobs may already be in Windows. This estimate avoids counting them twice.
    const estimated = waiting + Math.max(windows, active);
    const countsKnown = Number.isInteger(p.jobCount) && p.jobCount >= 0 && Number.isInteger(p.serverQueuedCount) && p.serverQueuedCount >= 0 && Number.isInteger(p.serverActiveCount) && p.serverActiveCount >= 0;
    const current = fresh(p.lastSeen, 90000, now) && fresh(p.statusAt, 45000, now) && fresh(p.loadObservedAt, 45000, now);
    const eligible = countsKnown && current && (p.status === 'ready' || p.status === 'busy') && !(p.deviceReachable === false && fresh(p.deviceCheckedAt, 45000, now));
    const label = !countsKnown || !current ? '排队情况待更新' : !eligible ? '暂不推荐' : estimated === 0 ? (p.status === 'busy' ? '正在打印' : '空闲') : estimated < 4 ? '有任务排队' : '较拥堵';
    const detail = current && countsKnown ? '电脑队列 ' + windows + ' 项 · 服务端待下发 ' + waiting + ' 项 · 执行中 ' + active + ' 项' : '上次电脑队列 ' + windows + ' 项，等待最新状态';
    return { eligible, estimated, label, detail };
}
export function recommendPrinter(printers, preferredId, duplex = false, now = Date.now()) {
    const candidates = printers.filter(p => (!duplex || p.duplex) && printerLoad(p, now).eligible);
    candidates.sort((a, b) => printerLoad(a, now).estimated - printerLoad(b, now).estimated || Number(a.status === 'busy') - Number(b.status === 'busy') || Number(b.id === preferredId) - Number(a.id === preferredId) || a.id.localeCompare(b.id));
    return candidates[0]?.id || '';
}

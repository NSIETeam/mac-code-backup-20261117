export function errorHelp(value) {
    const x = value;
    const raw = typeof value === 'string' ? value : (x === null || x === void 0 ? void 0 : x.message) || '';
    const safe = /[\u3400-\u9fff]/.test(raw) && !/(?:sk-|api.?key|Traceback|Exception|\/opt\/)/i.test(raw) ? raw : '本次操作暂未完成';
    let title = '请检查后继续', message = safe, steps = '请根据上方说明调整当前选择或填写内容，再点击原操作按钮。', action = 'none', label = '知道了';
    if ((x === null || x === void 0 ? void 0 : x.status) === 428 || /先修改.*密码|首次登录/.test(raw)) {
        title = '请先修改密码';
        steps = '使用当前密码填写改密页面；新密码至少8位，不能与初始密码相同。';
        action = 'password';
        label = '修改密码';
    }
    else if (/账号已存在/.test(raw)) {
        title = '此姓名账号已存在';
        steps = '先检查是否已经创建；如确为同名教师，请在姓名后添加校区或数字区分，再创建。';
    }
    else if (/账号或密码错误/.test(raw)) {
        title = '姓名或密码不正确';
        steps = '请核对姓名是否含多余空格、密码大小写；初次登录用管理员提供的初始密码，忘记密码请联系管理员重置。';
    }
    else if ((x === null || x === void 0 ? void 0 : x.status) === 401 || /登录.*过期|请先登录/.test(raw)) {
        title = '需要重新登录';
        steps = '重新输入姓名和密码后，回到原班级继续操作。';
        action = 'login';
        label = '重新登录';
    }
    else if (/合法域名|url not in domain/.test(raw)) {
        title = '小程序连接配置异常';
        message = '当前连接被微信拦截。';
        steps = '请联系管理员核对微信后台服务器域名；老师无需修改手机设置。';
    }
    else if (/连接|网络|中断|超时/.test(raw) || (x === null || x === void 0 ? void 0 : x.status) === 502 || (x === null || x === void 0 ? void 0 : x.status) === 503 || (x === null || x === void 0 ? void 0 : x.status) === 504) {
        title = '连接暂未完成';
        steps = '先确认网络可用，再点击原按钮继续。如果刚刚提交了打印，请先查看打印记录，避免重复打印。';
        if (/print|打印/.test(((x === null || x === void 0 ? void 0 : x.path) || '') + raw)) {
            action = 'jobs';
            label = '打印记录';
        }
    }
    else if (/结果待核实|可能已经|仍在处理|未完成.*任务/.test(raw)) {
        title = '请先核对打印结果';
        steps = '打开打印记录查看原任务，并让现场人员核对是否出纸。确认没有打印后，再明确发起补打。';
        action = 'jobs';
        label = '打印记录';
    }
    else if (/未绑定|尚未绑定|设备已停用|打印机尚未启用/.test(raw)) {
        title = '这台打印机暂不可用';
        steps = '选择另一台已连接的打印机；管理员可在设置的校区设备中检查绑定、启用状态及电脑上的打印助手。';
    }
    else if (/文档.*未生成|仍在生成|处理中/.test(raw)) {
        title = '文件还在准备中';
        steps = '稍后刷新生成记录；直接打印已提交的任务会在生成完成后继续，无需再次下单。';
    }
    else if (/文件.*过期|下载链接|内容不完整/.test(raw)) {
        title = '请重新获取文件';
        steps = '回到预览页重新点击下载，系统会获取新的下载链接。请勿继续使用旧链接。';
    }
    else if (/扫描|无法识别|没有.*题目|解析|加密|损坏/.test(raw)) {
        title = '讲义需要处理';
        steps = '检查文件能否正常打开、是否加密或为扫描图片；可尝试导出为可复制文字的PDF后重新上传。已有成功题目请先保留并校对。';
    }
    else if ((x === null || x === void 0 ? void 0 : x.status) === 403) {
        title = '当前账号没有此操作权限';
        steps = '确认登录的是自己的账号。需要调整任课班级、管理资料或设备时，请联系管理员分配权限。';
    }
    else if ((x === null || x === void 0 ? void 0 : x.status) === 404) {
        title = '内容已调整或入口已过期';
        steps = '返回班级页重新打开当前课次或文件；如果仍找不到，请联系管理员核对是否已被移除。';
    }
    else if ((x === null || x === void 0 ? void 0 : x.status) === 409) {
        title = '内容已发生变化';
        steps = '先保留当前填写内容，再使用页面的刷新或重新打开按钮载入最新内容后操作。已提交的打印请先查看打印记录。';
    }
    else if ((x === null || x === void 0 ? void 0 : x.status) === 413 || /50MB|文件大小|过大/.test(raw)) {
        title = '文件超出大小限制';
        steps = '请将文件压缩或拆成每份不超过50MB，再上传未成功的文件。';
    }
    else if ((x === null || x === void 0 ? void 0 : x.status) === 429) {
        title = '操作较频繁';
        steps = '请等待片刻后再点击原按钮。已提交的任务会继续处理，无需连续点击。';
    }
    else if (((x === null || x === void 0 ? void 0 : x.status) || 0) >= 500 || safe !== raw) {
        title = '系统暂未完成操作';
        steps = '请保留当前页面，稍后再试。如持续失败，复制问题信息交给管理员排查。';
    }
    const details = [title, message, (x === null || x === void 0 ? void 0 : x.traceId) ? '问题编号：' + x.traceId : ''].filter(Boolean).join('\n');
    return { title, message, steps, action, label, details };
}

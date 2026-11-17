export async function openHomeworkPdf(url: string, header?: Record<string, string>): Promise<void> {
  const path = await new Promise<string>((resolve, reject) => wx.downloadFile({
    url, header, timeout: 20000,
    success: result => result.statusCode === 200 ? resolve(result.tempFilePath) : reject(Error('作业资料暂不可用')),
    fail: () => reject(Error('下载作业资料失败，请重试'))
  }))
  await new Promise<void>((resolve, reject) => wx.openDocument({
    filePath: path, fileType: 'pdf', showMenu: true,
    success: () => resolve(), fail: () => reject(Error('无法打开 PDF 作业资料'))
  }))
}

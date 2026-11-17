const libraryUrl = 'https://www.siyuejn.com/work/?view=library'

Page({
  data: { src: libraryUrl, failed: false },
  loadFailed() { this.setData({ failed: true }) },
  retry() { this.setData({ failed: false }) },
  copyLink() { wx.setClipboardData({ data: libraryUrl }) }
})

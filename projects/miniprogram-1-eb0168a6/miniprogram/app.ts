import { settings } from './services/api'
App<IAppOption>({
  globalData: {},
  onLaunch() {
    // Discard the old local-preview identity when switching to the campus server.
    if (wx.getStorageSync('siyue_api_origin') !== settings.baseUrl) {
      wx.removeStorageSync('siyue_token'); wx.removeStorageSync('siyue_user')
      wx.setStorageSync('siyue_api_origin', settings.baseUrl)
    }
  },
})

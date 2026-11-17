/// <reference path="./types/index.d.ts" />

interface IAppOption {
  globalData: {
    userInfo?: WechatMiniprogram.UserInfo,
  }
  userInfoReadyCallback?: WechatMiniprogram.GetUserInfoSuccessCallback,
}
declare namespace WechatMiniprogram {
  interface Wx {
    shareFileMessage(options: { filePath: string; fileName?: string; success?: (result: GeneralCallbackResult) => void; fail?: (result: GeneralCallbackResult) => void; complete?: (result: GeneralCallbackResult) => void }): void;
  }
}

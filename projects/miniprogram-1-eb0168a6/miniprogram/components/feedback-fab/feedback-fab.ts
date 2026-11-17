import {feedbackContext} from '../../services/feedback-context'
Component({methods:{open(){wx.setStorageSync('siyue_feedback_entry',feedbackContext());wx.navigateTo({url:'/pages/feedback/feedback'})}}})

package com.kaopuip.app

import android.app.Dialog
import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.alipay.sdk.app.PayTask
import com.tencent.mm.opensdk.modelpay.*;
import android.widget.TextView
import com.kaopuip.app.common.WaitingDialog.endWaitingDialog
import com.kaopuip.app.common.WaitingDialog.showWaitingDialog
import com.kaopuip.app.common.app
import com.kaopuip.app.common.readHttpText
import com.alipay.sdk.app.EnvUtils
import com.google.gson.Gson
import com.kaopuip.core.UserInfo
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.layoutInflater
import org.jetbrains.anko.toast
import org.jetbrains.anko.uiThread

class CheckoutSheet(context: Context,goods: GoodsActivity.GoodsItem){
    private val ALI_ORDER_INFO_URL = "http://user.kaopuip.com:6710/alipay_app.do"
    private val WX_ORDER_INFO_URL = "http://user.kaopuip.com:6710/wxpay_app.do"
    private val TAG = "CheckoutSheet"
    private val mBaseView: View = context.layoutInflater.inflate(R.layout.checkout_sheet,null)
    private val mDialog: Dialog
    private var mUserInfoUpdateCallback:((UserInfo?)->Unit)? = null
    init {
        EnvUtils.setEnv(EnvUtils.EnvEnum.SANDBOX)
        mBaseView.findViewById<TextView>(R.id.goodsName).text = goods.Name
        mBaseView.findViewById<TextView>(R.id.goodsPrice).text = goods.Price
        mDialog = Dialog(context,
            R.style.ActionSheetViewDialogStyle
        )
        mBaseView.findViewById<View>(R.id.alipay).setOnClickListener {
            this.dialog().dismiss()
            context.showWaitingDialog("处理中")
            doAsync {
                val info =
                    readHttpText("$ALI_ORDER_INFO_URL?email=${app().api.getLoginInfo()!!.user}&goodname=${goods.Name}")
                if(info.isEmpty()){
                    uiThread {
                        context.endWaitingDialog()
                        context.toast(context.getString(R.string.error_network_error))
                    }
                }else{
                    Log.d(TAG,info)
                    val alipay = PayTask(context as GoodsActivity)
                    val result = alipay.payV2(info,false)
                    uiThread {
                        context.endWaitingDialog()
                        alipayHaveResult(context,result)
                    }
                }
            }
        }
        mBaseView.findViewById<View>(R.id.wxpay).setOnClickListener {
            this.dialog().dismiss()
            context.showWaitingDialog("处理中")
            doAsync {
                val info =
                    readHttpText("$WX_ORDER_INFO_URL?email=$${app().api.getLoginInfo()!!.user}&goodname=${goods.Name}")
                if(info.isEmpty()){
                    uiThread {
                        context.endWaitingDialog()
                        context.toast(context.getString(R.string.error_network_error))
                    }
                }else{
                    var param:MutableMap<String,String> = mutableMapOf()
                    param = Gson().fromJson(info,param.javaClass)
                    val payReq = PayReq()
                    payReq.appId = param["appid"]
                    payReq.partnerId = param["partnerid"]
                    payReq.prepayId = param["prepayid"]
                    payReq.packageValue = param["package"]
                    payReq.nonceStr = param["noncestr"]
                    payReq.timeStamp = param["timestamp"]
                    payReq.sign = param["sign"]
                    val api: IWXAPI = WXAPIFactory.createWXAPI(context,context.getString(
                        R.string.wx_appid
                    ))
                    api.sendReq(payReq)
                    uiThread {
                        context.endWaitingDialog()
                    }
                }
            }
        }
        mDialog.setContentView(mBaseView)
        mDialog.window?.setGravity(Gravity.BOTTOM)
        val lp = mDialog.window?.attributes
        lp?.width =  WindowManager.LayoutParams.MATCH_PARENT
        lp?.height= WindowManager.LayoutParams.WRAP_CONTENT
        mDialog.window?.attributes = lp
        mDialog.setCanceledOnTouchOutside(true)
    }
    private  fun alipayHaveResult(context: Context,result:Map<String,String>){
        if(result["resultStatus"] == "9000"){
            context.toast(R.string.msg_payment_successful)
            doAsync {
                val info = app().api.updateUserInfo()
                uiThread {
                    if (info.status == 0 &&mUserInfoUpdateCallback!=null){
                        mUserInfoUpdateCallback?.let { it1 -> it1(info.content!!) }
                    }
                }
            }
        }else{
            context.toast(R.string.msg_connect_failed)
        }
    }

    fun setUpdateCallback(callback:(UserInfo?)->Unit): CheckoutSheet {
        mUserInfoUpdateCallback = callback
        return this
    }

    fun dialog()=mDialog
}
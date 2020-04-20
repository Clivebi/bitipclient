package com.kaopuip.app.wxapi

import androidx.appcompat.app.AppCompatActivity
import com.tencent.mm.opensdk.constants.ConstantsAPI
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import org.jetbrains.anko.toast
import com.kaopuip.app.R

class WXPayEntryActivity: AppCompatActivity(),IWXAPIEventHandler {

    override fun onReq(p0: BaseReq?) {
    }

    override fun onResp(p0: BaseResp?) {
        if(p0==null){
            return
        }
        if(p0.type == ConstantsAPI.COMMAND_PAY_BY_WX){
            if(p0.errCode == BaseResp.ErrCode.ERR_OK){
                toast(R.string.msg_payment_successful)
            }else{
                toast(R.string.msg_connect_failed)
            }
            finish()
        }
    }
}
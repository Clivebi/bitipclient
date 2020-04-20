package com.kaopuip.app.common


import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.widget.TextView
import com.kaopuip.app.R


@SuppressWarnings("MemberVisibilityCanBePrivate")
object  WaitingDialog {
    private var sDialog:Dialog? = null
    fun Context.showWaitingDialog(msg: String){
        if(isWaitingDialogShowing()){
            return
        }
        if(sDialog == null){
            val dlg =  Dialog(this,R.style.loading_dialog)
            dlg.setContentView(R.layout.wait_dialog)
            dlg.findViewById<ActivityIndicatorView>(R.id.imageView).startAnimation(Color.WHITE)
            dlg.setCancelable(false)
            dlg.setCanceledOnTouchOutside(false)
            sDialog = dlg
        }
        sDialog?.findViewById<TextView>(R.id.textView)?.text = msg
        sDialog?.show()
    }

    private fun Context.isWaitingDialogShowing():Boolean{
        return true == sDialog?.isShowing
    }

    fun Context.endWaitingDialog(){
        if(isWaitingDialogShowing()){
            sDialog?.dismiss()
            sDialog = null
        }
    }
}
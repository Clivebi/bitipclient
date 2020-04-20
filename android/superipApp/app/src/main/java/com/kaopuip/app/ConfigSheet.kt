package com.kaopuip.app

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.kaopuip.app.common.SelectorSheet
import com.kaopuip.app.common.app
import com.kaopuip.core.ServerAPIProvider
import org.jetbrains.anko.layoutInflater

class ConfigSheet(private val context: Context):
    SelectorSheet.Listener{
    interface Listener{
        fun onConfigSheetOK(config: ServerAPIProvider.IPSelector)
    }
    private val mBaseView: View = context.layoutInflater.inflate(R.layout.config_sheet,null)
    private var mListener: Listener? = null
    private val mDialog: Dialog

    fun setListener(listener: Listener): ConfigSheet {
        this.mListener = listener
        return this
    }

    init {
        mBaseView.findViewById<TextView>(R.id.province).text = context.getString(
            R.string.title_all
        )
        mBaseView.findViewById<TextView>(R.id.city).text = context.getString(
            R.string.title_all
        )
        mBaseView.findViewById<TextView>(R.id.carrier).text = context.getString(
            R.string.title_all
        )
        mBaseView.findViewById<Switch>(R.id.ignoreusedip).isChecked = false
        mDialog = Dialog(context,
            R.style.ActionSheetViewDialogStyle
        )
        mBaseView.findViewById<Button>(R.id.cancel).setOnClickListener {
            this.dialog().dismiss()
        }
        mBaseView.findViewById<Button>(R.id.ok).setOnClickListener {
            val config = ServerAPIProvider.IPSelector(mBaseView.findViewById<TextView>(
                R.id.province
            ).text.toString(),
                mBaseView.findViewById<TextView>(R.id.city).text.toString(),
                mBaseView.findViewById<TextView>(R.id.carrier).text.toString(),
                mBaseView.findViewById<Switch>(R.id.ignoreusedip).isChecked)
            mListener?.onConfigSheetOK(config)
            this.dialog().dismiss()
        }
        mBaseView.findViewById<ConstraintLayout>(R.id.alipay).setOnClickListener {
            val select = SelectorSheet(
                context,
                app().mAPIProvider.getAvailableProvince(),
                "province"
            )
            select.setListener(this).dialog().show()
        }

        mBaseView.findViewById<ConstraintLayout>(R.id.wxpay).setOnClickListener {
            val select = SelectorSheet(
                context,
                app().mAPIProvider.getAvailableCity(mBaseView.findViewById<TextView>(R.id.province).text.toString()),
                "city"
            )
            select.setListener(this).dialog().show()
        }

        mBaseView.findViewById<ConstraintLayout>(R.id.carriercontainer).setOnClickListener {
            val select = SelectorSheet(
                context,
                app().mAPIProvider.getAvailableCarrier(),
                "carrier"
            )
            select.setListener(this).dialog().show()
        }

        mDialog.setContentView(mBaseView)
        mDialog.window?.setGravity(Gravity.BOTTOM)
        val lp = mDialog.window?.attributes
        lp?.width =  WindowManager.LayoutParams.MATCH_PARENT
        lp?.height= WindowManager.LayoutParams.WRAP_CONTENT
        mDialog.window?.attributes = lp
        mDialog.setCanceledOnTouchOutside(true)
    }

    fun dialog()=mDialog

    override fun actionSheetViewCancel(key: String) {

    }

    override fun actionSheetViewDidSelect(key: String, value:String) {
        when(key){
            "province"-> {
                mBaseView.findViewById<TextView>(R.id.province).text = value
            }
            "city"->{
                mBaseView.findViewById<TextView>(R.id.city).text = value
            }
            "carrier"->{
                mBaseView.findViewById<TextView>(R.id.carrier).text = value
            }
        }
    }

}
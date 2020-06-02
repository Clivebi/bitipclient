package com.kaopuip.app.common

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.kaopuip.app.R
import org.jetbrains.anko.backgroundColor
import org.jetbrains.anko.layoutInflater
import org.jetbrains.anko.textColor
import org.jetbrains.anko.windowManager

class SelectorSheet(private val context: Context, private val items:Array<String>,val key:String){
    interface Listener{
        fun actionSheetViewCancel(key:String)
        fun actionSheetViewDidSelect(key:String,value:String)
    }
    private val mBaseView: View = context.layoutInflater.inflate(R.layout.selector_sheet,null)
    private val mCancelView: Button
    private val mItemContainer: LinearLayout
    private var mListener: Listener? = null
    private lateinit var mDialog: Dialog

    fun setListener(listener: Listener): SelectorSheet {
        this.mListener = listener
        return this
    }

    init {
        mBaseView.findViewById<LimitScrollView>(R.id.sview).mMaxHeight = context.windowManager.defaultDisplay.height/2
        mItemContainer = mBaseView.findViewById(R.id.itemsView)
        mItemContainer.removeAllViews()
        mCancelView = mBaseView.findViewById(R.id.cancel)
        mCancelView.setOnClickListener {
            mDialog.dismiss()
            mListener?.actionSheetViewCancel(key)
        }
        for (v in items.withIndex()){
            val view = TextView(context)
            view.text = v.value
            view.textSize = 18f
            view.textColor = ContextCompat.getColor(context, R.color.editText)
            view.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp2px(50))
            view.gravity = Gravity.CENTER
            view.isClickable = true
            view.backgroundColor = ContextCompat.getColor(context,R.color.colorPrimary)
            view.setOnClickListener {
                mDialog.dismiss()
                mListener?.actionSheetViewDidSelect(key,v.value)
            }
            mItemContainer.addView(view)
        }
        mDialog = Dialog(context,
            R.style.ActionSheetViewDialogStyle
        )
        mDialog.setContentView(mBaseView)
        mDialog.window?.setGravity(Gravity.BOTTOM)
        val lp = mDialog.window?.attributes
        lp?.width =  WindowManager.LayoutParams.MATCH_PARENT
        lp?.height= WindowManager.LayoutParams.WRAP_CONTENT
        mDialog.window?.attributes = lp
        mDialog.setCanceledOnTouchOutside(false)
        mDialog.setOnDismissListener {
            mItemContainer.removeAllViews()
        }
    }

    fun dialog()=mDialog

}
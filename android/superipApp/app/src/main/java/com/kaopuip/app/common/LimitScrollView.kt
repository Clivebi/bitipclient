package com.kaopuip.app.common

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

class LimitScrollView(context: Context, attrs: AttributeSet? = null):ScrollView(context,attrs) {
    var mMaxHeight:Int =0
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (mMaxHeight > 0){
            val heightMeasureSpec = MeasureSpec.makeMeasureSpec(mMaxHeight,MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
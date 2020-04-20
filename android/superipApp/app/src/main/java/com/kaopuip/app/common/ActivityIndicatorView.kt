package com.kaopuip.app.common

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AnimationDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

class ActivityIndicatorView(context: Context,attrs: AttributeSet? = null) : AppCompatImageView(context, attrs) {
    fun startAnimation(color:Int=Color.GRAY){
        val animation = AnimationDrawable()
        for (i in 0 until 12){
            val frame = context.getDrawable(context.resources.getIdentifier("indicator_$i","drawable",context.packageName))
            if (frame != null){
                if (color != Color.WHITE){
                    val copy =frame.constantState!!.newDrawable().mutate()
                    copy.colorFilter = PorterDuffColorFilter(Color.GRAY,PorterDuff.Mode.MULTIPLY)
                    animation.addFrame(copy,100)
                }else{
                    animation.addFrame(frame,100)
                }
            }
        }
        this.background = animation
        animation.start()
    }
    fun stopAnimation(){
        (this.background as? AnimationDrawable)?.stop()
        this.background = null
    }
}
package com.kaopuip.app.common

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.kaopuip.app.MainApp
import java.lang.StringBuilder
import java.security.MessageDigest

fun AppCompatActivity.fullScreen(){
    window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
    supportActionBar?.hide()
}

fun Context.statusBarHeight():Int{
    val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
    return resources.getDimensionPixelSize(resourceId)
}


fun AppCompatActivity.translucentActionBar(){
    supportActionBar?.hide()
    val window = this.window
    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    //window.statusBarColor = Color.TRANSPARENT
}
fun Context.dp2px(dp:Float):Float{
    return dp*resources.displayMetrics.density
}

fun Context.dp2px(dp:Int):Int{
    return dp2px(dp.toFloat()).toInt()
}
fun <T>T.app(): MainApp {
    return MainApp.getInstance()
}
fun String.md5():ByteArray{
    val md = MessageDigest.getInstance("MD5")
    md.update(this.toByteArray(Charsets.UTF_8))
    return md.digest()
}

fun String.decodeHexString():ByteArray{
    val s = this.replace(" ", "")
    val bs = ByteArray(s.length/2)
    for (i in 0 until s.length/2){
        bs[i] = s.substring(i*2, i*2+2).toInt(16).toByte()
    }
    return bs
}

fun ByteArray.hexString():String{
    if (this.isEmpty()){
        return ""
    }
    val build = StringBuilder("")
    for (v in this){
        val vd = String.format("%02x",v.toInt() and 0xFF)
        build.append(vd)
    }
    return build.toString()
}

fun ByteArray.md5():ByteArray{
    val md = MessageDigest.getInstance("MD5")
    md.update(this)
    return md.digest()
}

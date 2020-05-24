package com.kaopuip.app.common

import android.app.ActivityManager
import android.content.Context
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

fun readHttpText(urlText: String,connectTimeout:Int=1000*5,readTimeout:Int=30*1000): String {
    for (i in 0..2) {
        try {
            val url = URL(urlText)
            val httpConnection = url.openConnection() as HttpURLConnection
            httpConnection.requestMethod = "GET"
            httpConnection.connectTimeout = connectTimeout
            httpConnection.readTimeout = readTimeout
            httpConnection.doInput = true
            httpConnection.connect()
            if (httpConnection.responseCode == HttpURLConnection.HTTP_OK){
                val stream = BufferedInputStream(httpConnection.inputStream)
                val buf = stream.readBytes()
                stream.close()
                httpConnection.disconnect()
                return String(buf)
            }
            return ""
        }catch (exp: IOException){
            exp.printStackTrace()
        }
    }
    return ""
}

@Suppress("DEPRECATION")
fun isServiceRunning(context: Context,className:String):Boolean{
    val mgr = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val list = mgr.getRunningServices(50)
    for (v in list){
        if (v.service.className == className){
            return true
        }
    }
    return false
}
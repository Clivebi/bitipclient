package com.kaopuip.app.common

import android.content.Context
import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable
import java.io.IOException
import java.lang.Exception
import java.lang.RuntimeException

class Storage(val context: Context){
    private fun readFile(name:String):ByteArray?{
        var ret:ByteArray? = null
        try {
            val r = context.openFileInput(name)
            ret = r.readBytes()
            r.close()
        }catch (exp: Exception){
            exp.printStackTrace()
        }
        return ret
    }

    private fun writeFile(data:ByteArray,name: String):Boolean{
        var ret = false
        try {
            context.deleteFile(name)
            val w = context.openFileOutput(name,0)
            w.write(data)
            ret = true
        }catch (exp: Exception){
            exp.printStackTrace()
        }
        return ret
    }

    fun <T: Parcelable> saveObject(name:String, obj:T?):Boolean{
        if(obj == null){
            context.deleteFile(name)
            return false
        }
        var result = false
        try {
            val parcel = Parcel.obtain()
            parcel.writeParcelable(obj,0)
            parcel.setDataPosition(0)
            val content = parcel.marshall()
            result = writeFile(content,name)
            parcel.recycle()
        }catch (exp: IOException){
            exp.printStackTrace()
        }catch (exp: RuntimeException){
            exp.printStackTrace()
        }
        return result
    }

    fun <T: Parcelable> loadObject(name: String, loader:ClassLoader?):T?{
        var result:T? = null
        try {
            val data = readFile(name)
            if (data != null){
                val parcel = Parcel.obtain()
                parcel.unmarshall(data,0,data.size)
                parcel.setDataPosition(0)
                result = parcel.readParcelable(loader)
                parcel.recycle()
            }
        }catch (exp: BadParcelableException){
            exp.printStackTrace()
        }catch (exp: IOException){
            exp.printStackTrace()
        }catch (exp: RuntimeException){
            exp.printStackTrace()
        }
        return result
    }
}
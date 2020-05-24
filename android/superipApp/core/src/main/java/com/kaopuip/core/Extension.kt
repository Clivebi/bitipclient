/**
 * 辅助函数，仅仅供内部使用
 */
package com.kaopuip.core

import java.lang.StringBuilder
import java.security.MessageDigest

internal fun String.isMd5Text(): Boolean {
    if (length != 32) {
        return false
    }
    for (v in this) {
        if (v in '0'..'9') {
            continue
        }
        if (v in 'a'..'f') {
            continue
        }
        return false
    }
    return true
}

internal fun String.md5():ByteArray{
    val md = MessageDigest.getInstance("MD5")
    md.update(this.toByteArray(Charsets.UTF_8))
    return md.digest()
}

internal fun String.decodeHexString():ByteArray{
    val s = this.replace(" ", "")
    val bs = ByteArray(s.length/2)
    for (i in 0 until s.length/2){
        bs[i] = s.substring(i*2, i*2+2).toInt(16).toByte()
    }
    return bs
}

internal fun ByteArray.hexString():String{
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

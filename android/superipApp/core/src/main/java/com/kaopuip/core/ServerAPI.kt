package com.kaopuip.core

import android.os.Parcelable
import java.net.URL
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.android.parcel.Parcelize
import java.io.BufferedInputStream
import java.io.IOException
import java.lang.StringBuilder
import java.net.HttpURLConnection

class ResultWithError<T>(val status: Int, val msg: String, val content: T)
data class IPAddress(val address: String, val port: Int)

/**
 * @param name  用户名
 * @param expire 过期时间
 * @param ipsize 同时登陆用户数
 * @param ustatus 状态 1 为禁用
 * @param utype 账户类型，保留使用
 */
@Parcelize
data class UserInfo(
    val name: String,  //用户账户
    val expire: String, //用户过期时间
    val ipsize: String, //用户可同时连接的最大终端数
    val ustatus: String,//用户状态，1为被禁用
    val utype: String //用户账户，类型，1共享IP，2独享IP
): Parcelable

/**
 * @param name 机器ID
 * @param address 机器的当前端口，地址和端口可能随时会改变，连接前用getRealTimeAddress获取最新地址
 * @param port 端口
 * @param province IP所在省份
 * @param city IP所在城市
 * @param carrier IP运营商
 */
@Parcelize
data class VPNNode(
    val name: String,     //机器GUID
    var address: String,  //机器的当前地址
    var port: Int,        //机器的当前端口，地址和端口可能随时会改变，连接前用getRealTimeAddress获取最新地址
    val province: String, //IP所在省份
    val city: String,     //IP所在城市
    val carrier: String   //IP运营商
): Parcelable

internal  class ServerAPI(var serverAddress: IPAddress) {
    private data class InternalResponse(val status: Int, val message: String, val body: String)

    class StringCoder constructor(text: String) {
        private val decoder: Map<Int, String>

        init {
            var src = mutableMapOf<String, Int>()
            src = Gson().fromJson(text, src.javaClass)
            val map = mutableMapOf<Int, String>()
            for (v in src) {
                map[v.value.toInt()] = v.key
            }
            decoder = map.toMap()
        }

        fun decode(n: Int): String {
            return decoder[n] ?: "Unknown"
        }
    }

    companion object {
        private  const val URL_LOGIN = "http://%s:%d/login.do"
        private  const val URL_GETLIST = "http://%s:%d/getips2.do"
        private  const val URL_GETIP = "http://%s:%d/getip2.do"
        private  const val URL_CHECKIP = "http://%s:%d/checkip.do"
        private  const val URL_PROVINCE = "http://%s:%d/getpcoder.do"
        private  const val URL_STRINGCODER = "http://%s:%d/getcoder.do"

        //
        private  const val URL_SEND_PIN = "http://%s:%d/sendpin.do"
        private  const val URL_REGISTER = "http://%s:%d/register.do"
        private  const val URL_RESETPASSWORD = "http://%s:%d/resetpassword.do"

        //网络错误，连接失败或者超时，需要尝试下一个服务器
        const val NetworkErrorText = "Network error"
        //默认端口
        private const val defaultPort = 8808
        //连接超时
        var       connectTimeout = 1000*2
        //读取数据超时，单位s
        var       readTimeout = 1000*15
        //重试次数
        var       retryCount = 2

        private fun ByteArray.hexString(offset: Int, count: Int): String {
            if (this.isEmpty()) {
                return ""
            }
            val build = StringBuilder("")
            for (v in 0 until count) {
                val vd = String.format("%02x", this[v + offset].toInt() and 0xFF)
                build.append(vd)
            }
            return build.toString()
        }

        private fun decodeNode(
            src: ByteArray,
            offset: Int,
            pb: StringCoder,
            sb: StringCoder
        ): VPNNode {
            val ip = String.format(
                "%d.%d.%d.%d",
                src[offset].toInt() and 0xff,
                src[offset + 1].toInt() and 0xff,
                src[offset + 2].toInt() and 0xff,
                src[offset + 3].toInt() and 0xff
            )
            val name = src.hexString(offset + 4, 3)
            val province = src[offset + 7].toInt() and 0x3f
            val carrier = (src[offset + 7].toInt() and 0xff) shr 6
            val city = (src[offset+8].toInt() shl 8) + src[offset+9].toInt()
            return VPNNode(
                name,
                ip,
                defaultPort,
                pb.decode(province),
                sb.decode(city),
                sb.decode(carrier)
            )
        }

        private fun decodeNodeList(
            src: ByteArray,
            pb: StringCoder,
            sb: StringCoder
        ): ArrayList<VPNNode> {
            if (src.size % 10 != 0) {
                return arrayListOf()
            }
            var offset = 0
            val ret = ArrayList<VPNNode>()
            while (true) {
                val item =
                    decodeNode(
                        src,
                        offset,
                        pb,
                        sb
                    )
                ret.add(item)
                offset += 10
                if (offset == src.size) {
                    break
                }
            }
            return ret
        }

        fun parseServerList(
            src: ByteArray,
            provinceCoder: StringCoder,
            stringCoder: StringCoder
        ): Array<VPNNode> {
            return decodeNodeList(
                src,
                provinceCoder,
                stringCoder
            ).toTypedArray()
        }

    }


    private fun readHttpBytes(urlText: String): ResultWithError<ByteArray?> {
        val result:ByteArray?
        for (i in 0..retryCount) {
            try {
                val url = URL(urlText)
                val httpConnection = url.openConnection() as HttpURLConnection
                httpConnection.requestMethod = "GET"
                httpConnection.connectTimeout =
                    connectTimeout
                httpConnection.readTimeout =
                    readTimeout
                httpConnection.doInput = true
                httpConnection.connect()
                return if (httpConnection.responseCode == HttpURLConnection.HTTP_OK){
                    val stream = BufferedInputStream(httpConnection.inputStream)
                    result = stream.readBytes()
                    stream.close()
                    httpConnection.disconnect()
                    ResultWithError(
                        0,
                        "",
                        result
                    )
                }else{
                    var errorMessage = httpConnection.responseMessage
                    if (httpConnection.contentLength != 0){
                        val stream = BufferedInputStream(httpConnection.errorStream)
                        result = stream.readBytes()
                        errorMessage = String(result)
                        stream.close()
                    }
                    httpConnection.disconnect()
                    ResultWithError(
                        Error.ProtocolError.raw,
                        errorMessage,
                        null
                    )
                }
            }catch (exp:IOException){
                exp.printStackTrace()
            }
        }
        return ResultWithError(
            Error.NetworkError.raw,
            NetworkErrorText,
            null
        )
    }

    fun sendPin(phone:String,type:String): ResultWithError<Boolean> {
        val resp = readHttpBytes("${String.format(URL_SEND_PIN,serverAddress.address, serverAddress.port)}?email=$phone&type=$type")
        if (resp.status !=0){
            return ResultWithError(
                resp.status,
                resp.msg,
                false
            )
        }
        val text = resp.content!!.toString(Charsets.UTF_8)

        val content: InternalResponse = Gson().fromJson(text, InternalResponse::class.java)
        if (content.status != 0) {
            return ResultWithError(
                content.status,
                content.message,
                false
            )
        }
        return ResultWithError(
            content.status,
            content.message,
            true
        )
    }

    fun register(phone: String,key: String,pin:String): ResultWithError<UserInfo?> {
        val resp = readHttpBytes("${String.format(
            URL_REGISTER,serverAddress.address,
            serverAddress.port)}?email=$phone&pass=$key&pin=$pin")
        if (resp.status !=0){
            return ResultWithError(
                resp.status,
                resp.msg,
                null
            )
        }
        val text = resp.content!!.toString(Charsets.UTF_8)

        val content: InternalResponse = Gson().fromJson(text, InternalResponse::class.java)
        if (content.status != 0) {
            return ResultWithError(
                content.status,
                content.message,
                null
            )
        }
        val body: JsonObject = Gson().fromJson(content.body, JsonObject::class.java)
        val ipsize: String = body.get("ipsize").asString
        val expire: String = body.get("expire").asString
        val ustatus: String = body.get("status").asString
        val utype = body.get("utype").asString
        return ResultWithError(
            0,
            "ok",
            UserInfo(
                phone,
                expire,
                ipsize,
                ustatus,
                utype
            )
        )
    }

    fun resetPassword(phone: String,key: String,pin:String): ResultWithError<UserInfo?> {
        val resp = readHttpBytes("${String.format(
            URL_RESETPASSWORD,serverAddress.address,
            serverAddress.port)}?email=$phone&newpass=$key&pin=$pin")
        if (resp.status !=0){
            return ResultWithError(
                resp.status,
                resp.msg,
                null
            )
        }
        val text = resp.content!!.toString(Charsets.UTF_8)

        val content: InternalResponse = Gson().fromJson(text, InternalResponse::class.java)
        if (content.status != 0) {
            return ResultWithError(
                content.status,
                content.message,
                null
            )
        }
        val body: JsonObject = Gson().fromJson(content.body, JsonObject::class.java)
        val ipsize: String = body.get("ipsize").asString
        val expire: String = body.get("expire").asString
        val ustatus: String = body.get("status").asString
        val utype = body.get("utype").asString
        return ResultWithError(
            0,
            "ok",
            UserInfo(
                phone,
                expire,
                ipsize,
                ustatus,
                utype
            )
        )
    }

    /*
    获取省份字符串解码配置
    */
    fun getProvinceCoderText(): ResultWithError<ByteArray?> {
        return  readHttpBytes(String.format(URL_PROVINCE, serverAddress.address, serverAddress.port))
    }

    /*
    获取通用字符串解码配置
    */
    fun getStringCoderText(): ResultWithError<ByteArray?> {
        return readHttpBytes(
            String.format(
                URL_STRINGCODER,
                serverAddress.address,
                serverAddress.port
            )
        )
    }

    /*
    获取用户信息
    */
    fun getUserInfo(user: String, key: String): ResultWithError<UserInfo?> {
        val url = "${String.format(
            URL_LOGIN,
            serverAddress.address,
            serverAddress.port
        )}?email=$user&pass=$key"
        val resp= readHttpBytes(url)
        if (resp.status !=0){
            return ResultWithError(
                resp.status,
                resp.msg,
                null
            )
        }
        val text = resp.content!!.toString(Charsets.UTF_8)

        val content: InternalResponse = Gson().fromJson(text, InternalResponse::class.java)
        if (content.status != 0) {
            return ResultWithError(
                content.status,
                content.message,
                null
            )
        }
        val body: JsonObject = Gson().fromJson(content.body, JsonObject::class.java)
        val ipsize: String = body.get("ipsize").asString
        val expire: String = body.get("expire").asString
        val ustatus: String = body.get("status").asString
        val utype = body.get("utype").asString
        return ResultWithError(
            0,
            "ok",
            UserInfo(
                user,
                expire,
                ipsize,
                ustatus,
                utype
            )
        )
    }

    /*
    获取服务器列表
    */
    fun getServerListBytes(user: String, key: String): ResultWithError<ByteArray?> {
        return readHttpBytes(
            "${String.format(
                URL_GETLIST,
                serverAddress.address,
                serverAddress.port
            )}?email=$user&pass=$key"
        )
    }

    /*
    获取VPN节点的实时地址，name 为VPNNode中的name字段
    */
    fun getRealTimeAddress(user: String, key: String, name: String): ResultWithError<IPAddress?> {
        val url = "${String.format(
            URL_GETIP,
            serverAddress.address,
            serverAddress.port
        )}?email=$user&pass=$key&name=$name"
        val resp = readHttpBytes(url)
        if (resp.status !=0){
            return ResultWithError(
                resp.status,
                resp.msg,
                null
            )
        }
        val list = resp.content!!.toString(Charsets.UTF_8).split(":")
        return ResultWithError(
            0,
            "",
            IPAddress(
                list.first(),
                list.last().toInt()
            )
        )
    }

    /*
    检查IP是否被自己的账户用过，
    如果没有被用过，则增加到已经使用列表中，返回false
    如果已经使用过，返回true
    只有需要全局过滤自己所有终端已经使用过的IP的客户才需要调用此接口
    */
    fun checkIP(user: String, key: String, ip: String): ResultWithError<Boolean> {
        val url = "${String.format(
            URL_CHECKIP,
            serverAddress.address,
            serverAddress.port
        )}?user=$user&ip=$ip&pass=$key"
        val resp = readHttpBytes(url)
        if (resp.status !=0){
            return ResultWithError(
                resp.status,
                resp.msg,
                false
            )
        }
        return ResultWithError(
            0,
            "ok",
            resp.content!!.toString(Charsets.UTF_8) == "true"
        )
    }

}


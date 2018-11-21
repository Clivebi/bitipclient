package org.kotlin.examples.localvpn

import java.net.URL
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.lang.Exception

data class InternalIPCheckResult(val isused:Boolean)
data class InternalResponse(val status:Int,val message:String,val body:String)

data class IPAddress(val address: String,val port:Int)
data class UserInfo(val status:Int,    //请求状态，首先需要判断是否是0，是0，其余字段才有效
                    val message:String,//如果请求失败，这个是失败的原因
                    val email:String,  //用户账户
                    val expire:String, //用户过期时间
                    val ipsize:String, //用户可同时连接的最大终端数
                    val ustatus:String,//用户状态，1为被禁用
                    val utype:String)  //用户账户，类型，1共享IP，2独享IP

data class VPNNode(val name:String,     //机器GUID
                   val address:String,  //机器的当前地址
                   val port:Int,        //机器的当前端口，地址和端口可能随时会改变，连接前用getRealTimeAddress获取最新地址
                   val province:String, //IP所在省份
                   val city:String)     //IP所在城市

data class ServerListResult(val status:Int,     //如果请求失败，这个是失败的原因
                            val message:String, //如果请求失败，这个是失败的原因
                            val list:List<VPNNode>)

data class GetIPResult(val status:Int,      //如果请求失败，这个是失败的原因
                       val message:String,  //如果请求失败，这个是失败的原因
                       val address:IPAddress)

data class IPCheckResult(val status:Int,      //如果请求失败，这个是失败的原因
                         val message:String,  //如果请求失败，这个是失败的原因
                         val isused: Boolean)


class ServerAPI {

    val NetworkError:Int = 100
    val NetworkErorText:String = "Network error"


    private  fun readHttpText(url:String):String{
        for ( i in 0..4){
            try {
                val resp:String = URL(url).readText()
                return resp
            }catch (e:Exception){

            }
        }
        return ""
    }
    /*
    获取用户信息
    */
    public  fun getUserInfo(user:String,key:String):UserInfo{
        var index:Int =1;
        val url:String = "http://120.39.243.128:1815/login.do?email="+user+"&pass="+key
        val resp:String = readHttpText(url)
        if (resp.isEmpty()) {
            return UserInfo(NetworkError,NetworkErorText,"","","","","")
        }
        val content:InternalResponse = Gson().fromJson(resp,InternalResponse::class.java)
        if (content.status != 0) {
            return UserInfo(content.status,content.message,user,"","","","")
        }
        val body:JsonObject = Gson().fromJson(content.body,JsonObject::class.java)
        val ipsize:String = body.get("ipsize").asString
        val expire:String = body.get("expire").asString
        val ustatus:String = body.get("status").asString
        val utype = body.get("utype").asString
        return UserInfo(content.status,content.message,user,expire,ipsize,ustatus,utype)
    }

    /*
    获取服务器列表
    */
    public  fun getServerList(user:String,key:String):ServerListResult{
        var map = mutableMapOf<Int, String>()
        var ret  = mutableListOf<VPNNode>()
        var index:Int =1;
        val url:String = "http://120.39.243.128:1815/getips.do?email="+user+"&pass="+key
        val resp = readHttpText(url)
        if (resp.isEmpty()) {
            return ServerListResult(NetworkError,NetworkErorText,ret)
        }
        val content:InternalResponse = Gson().fromJson(resp,InternalResponse::class.java)
        if (content.status != 0) {
            return ServerListResult(content.status,content.message,ret)
        }
        val body:JsonArray = Gson().fromJson(content.body,JsonArray::class.java)
        val first:JsonObject = body[0] as JsonObject

        for ( v in first.keySet()) {
            val value:Int = first.get(v).asInt
            map[value] = v
        }
        while(index < body.count()){
            val obj = body[index]as JsonObject
            index++
            if (obj.size() ==0) {
                break
            }
            val name:String = obj.get("a").asString
            val port:Int = obj.get("b").asInt
            var province:String? = map[obj.get("d").asInt]
            var city:String? = map[obj.get("e").asInt]
            val address:String = obj.get("g").asString
            if (province == null) {
                province = "Unknown"
            }
            if (city==null) {
                city = "Unknown"
            }
            ret.add(0, VPNNode(name,address,port,province,city))
        }
        return ServerListResult(content.status,content.message,ret)
    }

    /*
    获取VPN节点的实时地址，name 为VPNNode中的name字段
    */
    public  fun getRealTimeAddress(user:String,key:String,name:String):GetIPResult{
        val url:String = "http://120.39.243.128:1815/getip.do?email="+user+"&pass="+key+"&name="+name
        val resp = readHttpText(url)
        if (resp.isEmpty()) {
            return GetIPResult(NetworkError,NetworkErorText,IPAddress("0.0.0.0",0))
        }
        val content:InternalResponse = Gson().fromJson(resp,InternalResponse::class.java)
        if (content.status != 0) {
            return GetIPResult(content.status,content.message, IPAddress("0.0.0.0",0))
        }
        val texts:List<String> = content.body.split(":")
        if (texts.count() != 2) {
            return GetIPResult(101,"invalid response text",IPAddress("0.0.0.0",0))
        }
        return GetIPResult(content.status,content.message,IPAddress(texts[0],texts[1].toInt()))
    }
    /*
    检查IP是否被自己的账户用过，
    如果没有被用过，则增加到已经使用列表中，返回false
    如果已经使用过，返回true
    只有需要全局过滤自己所有终端已经使用过的IP的客户才需要调用此接口
    */
    public  fun checkIP(user:String,key:String,ip:String):IPCheckResult {
        val url:String = "http://120.39.243.128:7000/checkip.do?user="+user+"&ip="+ip+"&pass="+key
        val resp = readHttpText(url)
        if (resp.isEmpty()) {
            return IPCheckResult(NetworkError,NetworkErorText,false)
        }
        val content:InternalIPCheckResult = Gson().fromJson(resp,InternalIPCheckResult::class.java)
        return IPCheckResult(0,"",content.isused)
    }

}


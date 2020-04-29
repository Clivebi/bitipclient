package com.kaopuip.core

import android.content.Context
import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import kotlinx.android.parcel.Parcelize
import java.io.IOException
import java.lang.Exception
import java.lang.RuntimeException
import java.util.*
import kotlin.collections.ArrayList

class ServerAPIProvider(private  val context: Context) {
    private  val mNodeList:ArrayList<VPNNode> = arrayListOf()
    private  var mLoginInfo:LoginInfo? = null
    private  var mTimestamp: UpdateTimestamp =
        UpdateTimestamp(
            (0L).unixToDate(),
            (0L).unixToDate(),
            (0L).unixToDate()
        )
    private var mProvinceCoder: ServerAPI.StringCoder? = null
    private var mStringCoder: ServerAPI.StringCoder? = null
    private var mLastActiveAddress:String = ""

    @Parcelize
    class LoginInfo(val user:String,val key:String):Parcelable {}
    @Parcelize
    private  class UpdateTimestamp(var nodeList:Date,var stringCoder:Date,var userInfo:Date):Parcelable {}
    @Parcelize
    class IPSelector(var province:String,var city:String,var carrier:String,var ignoreUsedIP:Boolean): Parcelable {}

    init {
        val obj = loadObject<UpdateTimestamp>(
            UPDATE_TIMESTAMP_FILE_NAME,
            UpdateTimestamp::class.java.classLoader)
        if (obj != null){
            mTimestamp = obj
        }
        val login = loadObject<LoginInfo>(
            LOGIN_INFO_FILE_NAME,
            LoginInfo::class.java.classLoader)
        if (login != null){
            mLoginInfo = login
        }
        initCoder()
        mNodeList.addAll(getNodeListFromCache())
        Log.d(TAG,"Cache Count:${mNodeList.size}")
    }

    /*
    login 登录，登录成功，返回用户信息
    @user 用户手机号码
    @key 用户密码md5值
     */
    fun login(user: String,key: String):ResultWithError<UserInfo?>{
        val api = ServerAPI(
            IPAddress(
                defaultAddress,
                defaultPort
            )
        )
        val rsp = api.getUserInfo(user,key)
        if (rsp.status == 0){
            saveObject(USER_INFO_FILE_NAME,rsp.content!!)
            mLoginInfo = LoginInfo(user,key)
            save()
        }
        return rsp
    }

    fun sendPin(phone:String,type:String): ResultWithError<Boolean>{
        return ServerAPI(IPAddress(defaultAddress, defaultPort)).sendPin(phone,type)
    }

    fun register(phone: String,key: String,pin:String): ResultWithError<UserInfo?>{
        return ServerAPI(IPAddress(defaultAddress, defaultPort)).register(phone,key,pin)
    }

    fun resetPassword(phone: String,key: String,pin:String): ResultWithError<UserInfo?>{
        return ServerAPI(IPAddress(defaultAddress, defaultPort)).resetPassword(phone,key,pin)
    }
    /*
      getLoginInfo 查询是否有登录信息，如果没有，就需要执行一次login
     */
    fun getLoginInfo():LoginInfo?{
        return mLoginInfo
    }

    fun cleanLoginInfo(){
        mLoginInfo = null
        save()
    }
    /*
    getNodeList 获取节点列表
   */

    fun getNodeList():Array<VPNNode>{
        updateServerList(false)
        return mNodeList.toTypedArray()
    }

    /*
        getUserInfo 获取缓存的用户信息
     */
    fun getUserInfo():UserInfo?{
        return  loadObject(USER_INFO_FILE_NAME,UserInfo::class.java.classLoader)
    }
    /*
       getUserInfo 更新用户信息
    */
    fun updateUserInfo():UserInfo?{
        val api = ServerAPI(
            IPAddress(
                defaultAddress,
                defaultPort
            )
        )
        val rsp = api.getUserInfo(mLoginInfo!!.user,mLoginInfo!!.key)
        if (rsp.status == 0){
            saveObject(USER_INFO_FILE_NAME,rsp.content!!)
        }
        return rsp.content
    }

    /*
    获取节点的实时地址
     */
    fun getRealTimeAddress(name: String): IPAddress?{
        val api = ServerAPI(
            IPAddress(
                defaultAddress,
                defaultPort
            )
        )
        var result = api.getRealTimeAddress(mLoginInfo!!.user,mLoginInfo!!.key,name)
        if (result.status == 0){
            return result.content
        }
        if(result.status != ServerAPI.NetworkError){
            return null
        }
        if(mLastActiveAddress.isNotEmpty()){
            api.serverAddress = IPAddress(mLastActiveAddress,
                defaultPort
            )
            result = api.getRealTimeAddress(mLoginInfo!!.user,mLoginInfo!!.key,name)
            if (result.status == 0){
                return result.content
            }
        }
        for (v in mNodeList){
            api.serverAddress = IPAddress(v.address,
                defaultPort
            )
            result = api.getRealTimeAddress(mLoginInfo!!.user,mLoginInfo!!.key,name)
            if (result.status == 0){
                mLastActiveAddress = v.address
                return result.content
            }
            if (result.status != ServerAPI.NetworkError){
                return null
            }
        }
        return null
    }

    fun isAddressUsed(address:String):Boolean{
        val api = ServerAPI(
            IPAddress(
                defaultAddress,
                defaultPort
            )
        )
        val result = api.checkIP(mLoginInfo!!.user,mLoginInfo!!.key,address)
        if (result.status == 0 && result.content){
            return  result.content
        }
        return  false
    }
    /*
      获取可用省份
    */
    fun getAvailableProvince():Array<String>{
        updateServerList(false)
        val maps = mutableMapOf<String,String>()
        for (v in mNodeList){
            maps[v.province] = " "
        }
        return maps.keys.toTypedArray()
    }
    /*
       获取可用城市
     */
    fun getAvailableCity(province:String):Array<String>{
        updateServerList(false)
        val maps = mutableMapOf<String,String>()
        for (v in mNodeList){
            if(v.province == province){
                maps[v.city] = ""
            }
        }
        return maps.keys.toTypedArray()
    }

    fun getAvailableCarrier():Array<String>{
        return arrayOf("电信","移动","联通")
    }

    fun selectOneNode(selector:IPSelector):VPNNode?{
        updateServerList(false)
        for (i in 0 until mNodeList.size){
            selectSeed++
            val v = mNodeList[selectSeed%mNodeList.size]
            if (selector.province.isNotEmpty() && selector.province != v.province){
                continue
            }
            if (selector.city.isNotEmpty() && selector.city != v.city){
                continue
            }

            if (selector.carrier.isNotEmpty() && selector.carrier != v.carrier){
                continue
            }

            if (selector.province.isNotEmpty() && selector.province != v.province){
                continue
            }
            val address = getRealTimeAddress(v.name) ?: continue
            v.address = address.address
            v.port = address.port
            if(!selector.ignoreUsedIP && isAddressUsed(address.address)){
                continue
            }
            return v
        }
        return null
    }

    private fun save(){
        saveObject(UPDATE_TIMESTAMP_FILE_NAME,mTimestamp)
        saveObject(LOGIN_INFO_FILE_NAME,mLoginInfo)
    }

    private fun getNodeListFromCache():Array<VPNNode>{
        if(mProvinceCoder == null || mStringCoder == null){
            return arrayOf()
        }
        val rsp = readFile(NODE_LIST_FILE_NAME) ?: return arrayOf()
        return ServerAPI.parseServerList(rsp,mProvinceCoder!!,mStringCoder!!)
    }

    private  fun initCoder(){
        var coder = readFile(PROVINCE_FILE_NAME)
        if (coder != null){
            mProvinceCoder = ServerAPI.StringCoder(String(coder))
        }
        coder = readFile(STRING_FILE_NAME)
        if (coder != null){
            mStringCoder = ServerAPI.StringCoder(String(coder))
        }
    }

    private fun updateCoder(force:Boolean){
        var cloud = force
        if (Date().unixTime() - mTimestamp.nodeList.unixTime() > 1000*60*60*4){
            cloud = true
        }
        if (mProvinceCoder== null || mStringCoder == null || cloud){
            Log.d(TAG,"update coder from cloud")
            val api = ServerAPI(
                IPAddress(
                    defaultAddress,
                    defaultPort
                )
            )
            var result = api.getProvinceCoderText()
            if (result.status == 0 && result.content!=null&& result.content!!.isNotEmpty()){
                writeFile(result.content!!,
                    PROVINCE_FILE_NAME
                )
                mProvinceCoder = ServerAPI.StringCoder(result.content!!.toString(Charsets.UTF_8))
                mTimestamp.stringCoder = Date()
            }else{
                Log.d(TAG,"update pcoder error ${result.msg}")
            }
            result = api.getStringCoderText()
            if (result.status == 0 &&  result.content!=null&& result.content!!.isNotEmpty()){
                writeFile(result.content!!,
                    STRING_FILE_NAME
                )
                mStringCoder = ServerAPI.StringCoder(result.content!!.toString(Charsets.UTF_8))
                mTimestamp.stringCoder = Date()
            }
            else{
                Log.d(TAG,"update scoder error ${result.msg}")
            }
        }
    }

    private fun updateServerList(forceUpdate:Boolean):Boolean{
        updateCoder(forceUpdate)
        var cloud = forceUpdate
        if (mNodeList.size == 0){
            cloud = true
        }
        if(Date().unixTime() - mTimestamp.nodeList.unixTime() > 1000*60*30){
            cloud = true
        }
        if(cloud){
            Log.d(TAG,"update nodelist from cloud")
            val api = ServerAPI(
                IPAddress(
                    defaultAddress,
                    defaultPort
                )
            )
            var rsp = api.getServerListBytes(mLoginInfo!!.user,mLoginInfo!!.key)
            if (rsp.status == 0&&rsp.content != null){
                mNodeList.clear()
                mNodeList.addAll(ServerAPI.parseServerList(rsp.content!!,mProvinceCoder!!,mStringCoder!!))
                mTimestamp.nodeList = Date()
                writeFile(rsp.content!!,
                    NODE_LIST_FILE_NAME
                )
                return true
            }else{
                Log.d(TAG,"update nodelist error ${rsp.msg}")
            }
            if (rsp.status == ServerAPI.NetworkError){
                if(mLastActiveAddress.isNotEmpty()){
                    api.serverAddress = IPAddress(mLastActiveAddress,
                        defaultPort
                    )
                    rsp = api.getServerListBytes(mLoginInfo!!.user,mLoginInfo!!.key)
                    if (rsp.status == 0&&rsp.content != null){
                        mNodeList.clear()
                        mNodeList.addAll(ServerAPI.parseServerList(rsp.content!!,mProvinceCoder!!,mStringCoder!!))
                        mTimestamp.nodeList = Date()
                        writeFile(rsp.content!!,
                            NODE_LIST_FILE_NAME
                        )
                        return true
                    }
                }
                for (v in mNodeList) {
                    api.serverAddress = IPAddress(v.address,
                        defaultPort
                    )
                    rsp = api.getServerListBytes(mLoginInfo!!.user, mLoginInfo!!.key)
                    if (rsp.status == 0 && rsp.content != null) {
                        mLastActiveAddress = v.address
                        mNodeList.clear()
                        mNodeList.addAll(
                            ServerAPI.parseServerList(
                                rsp.content!!,
                                mProvinceCoder!!,
                                mStringCoder!!
                            )
                        )
                        mTimestamp.nodeList = Date()
                        writeFile(rsp.content!!,
                            NODE_LIST_FILE_NAME
                        )
                        return true
                    }
                    if (rsp.status == ServerAPI.ProtocolError){
                        break
                    }
                }
            }
        }
        return false
    }

    private fun readFile(name:String):ByteArray?{
        var ret:ByteArray? = null
        try {
            val r = context.openFileInput(name)
            ret = r.readBytes()
            r.close()
        }catch (exp:Exception){
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
        }catch (exp:Exception){
            exp.printStackTrace()
        }
        return ret
    }

    private fun <T: Parcelable> saveObject(name:String,obj:T?):Boolean{
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

    private fun <T:Parcelable> loadObject(name: String,loader:ClassLoader?):T?{
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
    private fun Long.unixToDate():Date{
        return  Date(this*1000)
    }

    private fun Date.unixTime():Long{
        return time/1000
    }

    companion object{
        const val TAG ="ServerAPIProvider"
        const val PROVINCE_FILE_NAME = "1"
        const val STRING_FILE_NAME = "2"
        const val NODE_LIST_FILE_NAME = "3"
        const val UPDATE_TIMESTAMP_FILE_NAME = "6"
        const val LOGIN_INFO_FILE_NAME="7"
        const val USER_INFO_FILE_NAME = "8"

        var  defaultAddress:String = "cmnet.kaopuip.com"
        var  defaultPort:Int = 6709
        private var selectSeed:Int = System.currentTimeMillis().toInt() and 0xFFFF
    }
}
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
import kotlin.random.Random

/**
 * 库版本号
 */
@Suppress("unused")
const val  coreLibVersion:String = "1.0.9"

/**
 * ServerAPIProvider 提供通用的服务端API接口，使用时候传递Application 作为context获取单例
 *在调用其它所有接口前，需要先检测getLoginInfo是否返回空，如果为空，需要调用登录接口后再调用
 *其它接口
 * 使用步骤：
 * 1) 在 Application中初始化核心库 ServerAPIProvider.init(this,serveraddr,serverport)
 * 2) 在主activity里面，检查是否保存有登录凭据，如果没有，启动登录activity,为了提高更好的抗DDOS能力，登录只需要调用一次，而不是每次打开软件都去调用
 *  if(ServerAPIProvider.getInstance().getLoginInfo()==null){<br>
 *      startActivity<Login>()<br>
 *  }<br>
 * 3) 切换IP
 *  LocalVpnService.stopVPNService()<br>
 *  ServerAPIProvider.getInstance().selectOneNode<br>
 *  LocalVpnService.startVPNService()<br>
 *
 */
@Suppress("unused")
class ServerAPIProvider private constructor(private val context: Context) {
    @Parcelize
    class LoginInfo(val user: String, val key: String) : Parcelable

    /**
     * IPSelector ip选择条件
     * @param province 省份
     * @param city 城市
     * @param carrier 运营商
     * @param skipUsedIP 跳过使用过的IP
     */
    @Parcelize
    class IPSelector(
        var province: String,
        var city: String,
        var carrier: String,
        var skipUsedIP: Boolean
    ) : Parcelable

    private val mNodeList: ArrayList<VPNNode> = arrayListOf()
    private var mLoginInfo: LoginInfo? = null
    private var mTimestamp: UpdateTimestamp =
        UpdateTimestamp(
            (0L).unixToDate(),
            (0L).unixToDate(),
            (0L).unixToDate()
        )
    private var mProvinceCoder: ServerAPI.StringCoder? = null
    private var mStringCoder: ServerAPI.StringCoder? = null
    private var mLastActiveAddress: String = ""

    @Parcelize
    private class UpdateTimestamp(var nodeList: Date, var stringCoder: Date, var userInfo: Date) :
        Parcelable

    /**
     * @hide
     */
    init {
        val obj = loadObject<UpdateTimestamp>(
            UPDATE_TIMESTAMP_FILE_NAME,
            UpdateTimestamp::class.java.classLoader
        )
        if (obj != null) {
            mTimestamp = obj
        }
        val login = loadObject<LoginInfo>(
            LOGIN_INFO_FILE_NAME,
            LoginInfo::class.java.classLoader
        )
        if (login != null) {
            mLoginInfo = login
        }
        initCoder()
        mNodeList.addAll(getNodeListFromCache())
        Log.d(TAG, "Cache Count:${mNodeList.size}")
    }

    private fun doLoginWithAntiDDOS(
        user: String,
        key: String
    ): ResultWithError<UserInfo?> {
        val api = ServerAPI(IPAddress(defaultAddress, defaultPort))
        var rsp = api.getUserInfo(user, key)
        if (rsp.status != Error.NetworkError.raw) {
            return rsp
        }
        if (mLastActiveAddress.isNotEmpty()) {
            api.serverAddress = IPAddress(mLastActiveAddress, defaultPort)
            rsp = api.getUserInfo(user, key)
            if (rsp.status != Error.NetworkError.raw) {
                return rsp
            }
        }
        val checkList = randOrderNodeList(100)
        for (v in checkList) {
            api.serverAddress = IPAddress(v.address, defaultPort)
            rsp = api.getUserInfo(user, key)
            if (rsp.status != Error.NetworkError.raw) {
                mLastActiveAddress = v.address
                return rsp
            }
        }
        return rsp
    }

    /**
     *登录并返回用户信息,只有在app首次使用的时候或者切换账号的时候才需要调用这个接口
     * @param user 用户名(手机号或者电子邮箱）
     * @param key 用户密码,可以传递明文或者md5，函数内部会判断是不是md5
     * @return 成功返回用户信息，失败返回错误信息
     */
    fun login(user: String, key: String): ResultWithError<UserInfo?> {
        val xKey = if (key.isMd5Text()) {
            key.toLowerCase(Locale.getDefault())
        } else {
            key.md5().hexString()
        }
        val saved = mLoginInfo
        if (saved != null){
            if (saved.user == user && saved.key == xKey){
                return ResultWithError(0,"ok",getUserInfo())
            }
        }
        val rsp = doLoginWithAntiDDOS(user, xKey)
        if (rsp.status == 0) {
            saveObject(USER_INFO_FILE_NAME, rsp.content!!)
            mTimestamp.userInfo = Date()
            mLoginInfo = LoginInfo(user, xKey)
            save()
        }
        return rsp
    }

    /**
     *发送验证码，不带抗ddos功能
     * @param user 用户名(手机号或者电子邮箱）
     * @param type 注册的时候传递"0"，重置密码的时候传递"1"
     * @return 成功返回true，失败返回错误信息
     */
    fun sendPin(user: String, type: String): ResultWithError<Boolean> {
        return ServerAPI(IPAddress(defaultAddress, defaultPort)).sendPin(user, type)
    }

    /**
     *注册账户，不带抗ddos功能
     * @param user 用户名(手机号或者电子邮箱）
     * @param key 用户密码,可以传递明文或者md5，函数内部会判断是不是md5
     * @param pin 用户收到的验证码
     * @return 成功返回用户信息，失败返回错误信息
     */
    fun register(user: String, key: String, pin: String): ResultWithError<UserInfo?> {
        val xKey = if (key.isMd5Text()) {
            key.toLowerCase(Locale.getDefault())
        } else {
            key.md5().hexString()
        }
        return ServerAPI(IPAddress(defaultAddress, defaultPort)).register(user, xKey, pin)
    }

    /**
     *重置密码，不带抗ddos功能
     * @param user 用户名
     * @param key 用户密码,可以传递明文或者md5，函数内部会判断是不是md5
     * @param pin 用户收到的验证码
     * @return 成功返回用户信息，失败返回错误信息
     */
    fun resetPassword(user: String, key: String, pin: String): ResultWithError<UserInfo?> {
        val xKey = if (key.isMd5Text()) {
            key.toLowerCase(Locale.getDefault())
        } else {
            key.md5().hexString()
        }
        return ServerAPI(IPAddress(defaultAddress, defaultPort)).resetPassword(user, xKey, pin)
    }

    /**
     *获取保存的登录信息
     * @return 成功返回用登录信息，没有保存信息返回null
     */
    fun getLoginInfo(): LoginInfo? {
        return mLoginInfo
    }

    /**
     *清空登录信息
     */
    fun cleanLoginInfo() {
        mLoginInfo = null
        save()
    }

    /**
     * 获取节点列表，getNodeList，getRealTimeAddress，checkAddressIsUsed这几个函数仅作为扩展保留，实际使用的时候应该调用selectOneNode来获取一个节点
     * @return 成功返回节点列表，失败返回错误信息
     */
    fun getNodeList(): ResultWithError<Array<VPNNode>> {
        if (mLoginInfo == null) {
            return ResultWithError(Error.UserNotLogin.raw, "User Not Login", arrayOf())
        }
        val result = updateServerList()
        if (result.status != 0) {
            return ResultWithError(result.status, result.msg, arrayOf())
        }
        return ResultWithError(0, "ok", mNodeList.toTypedArray())
    }

    /**
     * 获取本地保存的用户信息,这个接口不会产生网络通信
     * @return 如果本地有保存，返回用户信息，失败返回null
     */
    fun getUserInfo(): UserInfo? {
        return loadObject(USER_INFO_FILE_NAME, UserInfo::class.java.classLoader)
    }

    /**
     * 从云端获取用户信息，不带抗ddos功能，每天启动后可以异步更新用户信息一次，当订单支付完成后调用一次，
     * 因为本接口不带抗DDOS功能，UI调用的时候需要考虑函数失败但是不影响核心功能使用
     * @return 成功返回用户信息，失败返回错误信息
     */
    fun updateUserInfo(): ResultWithError<UserInfo?> {
        if (mLoginInfo == null) {
            return ResultWithError(Error.UserNotLogin.raw, "User Not Login", null)
        }
        val api = ServerAPI(IPAddress(defaultAddress, defaultPort))
        val rsp = api.getUserInfo(mLoginInfo!!.user, mLoginInfo!!.key)
        if (rsp.status == 0) {
            saveObject(USER_INFO_FILE_NAME, rsp.content!!)
        }
        return rsp
    }

    /**
     * 获取节点准实时IP地址，getNodeList，getRealTimeAddress，checkAddressIsUsed这几个函数仅作为扩展保留，实际使用的时候应该调用selectOneNode来获取一个节点
     * @return 成功返回IP地址，失败返回错误信息
     */
    fun getRealTimeAddress(name: String): ResultWithError<IPAddress?> {
        if (mLoginInfo == null) {
            return ResultWithError(Error.UserNotLogin.raw, "User Not Login", null)
        }
        return getRealTimeAddressWithAntiDDOS(mLoginInfo!!.user, mLoginInfo!!.key, name)
    }

    /**
     * 判断地址是否被自己使用过，getNodeList，getRealTimeAddress，checkAddressIsUsed这几个函数仅作为扩展保留，实际使用的时候应该调用selectOneNode来获取一个节点
     * @return 成功返回是否使用信息，失败返回错误信息
     */
    fun checkAddressIsUsed(address: String): ResultWithError<Boolean> {
        if (mLoginInfo == null) {
            return ResultWithError(Error.UserNotLogin.raw, "User Not Login", false)
        }
        return checkAddressIsUsedWithAntiDDOS(mLoginInfo!!.user, mLoginInfo!!.key, address)
    }

    /**
     * 获取可用省份，辅助函数
     * @return 返回省份列表
     */
    fun getAvailableProvince(): Array<String> {
        updateServerList()
        val maps = mutableMapOf<String, String>()
        for (v in mNodeList) {
            maps[v.province] = " "
        }
        return maps.keys.toTypedArray()
    }

    /**
     * 获取可用城市，辅助函数
     * @param province 省份名字
     * @return 返回城市列表
     */
    fun getAvailableCity(province: String): Array<String> {
        updateServerList()
        val maps = mutableMapOf<String, String>()
        for (v in mNodeList) {
            if (v.province == province) {
                maps[v.city] = ""
            }
        }
        return maps.keys.toTypedArray()
    }

    /**
     * 获取可用运营商，辅助函数，目前固定返回电信，移动，联通
     * @return 返回运营商列表
     */
    fun getAvailableCarrier(): Array<String> {
        return arrayOf("电信", "移动", "联通")
    }

    /**
     * 选取一个节点，getNodeList，getRealTimeAddress，checkAddressIsUsed这几个函数仅作为扩展保留，实际使用的时候应该调用selectOneNode来获取一个节点
     * @param selector 选取条件
     * @return 返回省份列表
     */
    fun selectOneNode(selector: IPSelector): ResultWithError<VPNNode?> {
        val rsp = updateServerList()
        if (rsp.status != 0 && mNodeList.isEmpty()) {
            Log.d(TAG,rsp.msg)
            return ResultWithError(rsp.status, rsp.msg, null)
        }
        if (mNodeList.isEmpty()) {
            return ResultWithError(Error.NoServerAvailable.raw, "No Server Available", null)
        }
        for (i in 0 until mNodeList.size) {
            selectSeed++
            val v = mNodeList[selectSeed % mNodeList.size]
            if (selector.province.isNotEmpty() && selector.province != v.province) {
                continue
            }
            if (selector.city.isNotEmpty() && selector.city != v.city) {
                continue
            }

            if (selector.carrier.isNotEmpty() && selector.carrier != v.carrier) {
                continue
            }

            if (selector.province.isNotEmpty() && selector.province != v.province) {
                continue
            }
            val result = getRealTimeAddress(v.name)
            if (result.status != 0) {
                Log.d(TAG,rsp.msg)
                return ResultWithError(result.status, result.msg, null)
            }
            v.address = result.content!!.address
            v.port = result.content.port
            if (selector.skipUsedIP) {
                val checkRs = checkAddressIsUsed(v.address)
                if (checkRs.status != 0) {
                    return ResultWithError(result.status, result.msg, null)
                }
                if (checkRs.content) {
                    continue
                }
            }
            return ResultWithError(0, "ok", v)
        }
        return ResultWithError(Error.NoServerAvailable.raw, "No Server Available", null)
    }

    private fun save() {
        saveObject(UPDATE_TIMESTAMP_FILE_NAME, mTimestamp)
        saveObject(LOGIN_INFO_FILE_NAME, mLoginInfo)
    }

    private fun getNodeListFromCache(): Array<VPNNode> {
        if (mProvinceCoder == null || mStringCoder == null) {
            return arrayOf()
        }
        val rsp = readFile(NODE_LIST_FILE_NAME) ?: return arrayOf()
        return ServerAPI.parseServerList(rsp, mProvinceCoder!!, mStringCoder!!)
    }

    private fun initCoder() {
        var coder = readFile(PROVINCE_FILE_NAME)
        if (coder != null) {
            mProvinceCoder = ServerAPI.StringCoder(String(coder))
        }
        coder = readFile(STRING_FILE_NAME)
        if (coder != null) {
            mStringCoder = ServerAPI.StringCoder(String(coder))
        }
    }

    private fun randOrderNodeList(count:Int):ArrayList<VPNNode>{
        val rand = Random(System.currentTimeMillis())
        if (mNodeList.isEmpty()){
            return ArrayList()
        }
        val ret = arrayListOf<VPNNode>()
        for (i in 0 until count){
            ret.add(mNodeList.random(rand))
        }
        return ret
    }


    private fun updateStringCoderWithAntiDDOS() {
        val api = ServerAPI(IPAddress(defaultAddress, defaultPort))
        var rsp = api.getStringCoderText()
        if (rsp.status == 0){
            if (rsp.content!!.isNotEmpty()) {
                writeFile(rsp.content!!, STRING_FILE_NAME)
                mStringCoder = ServerAPI.StringCoder(rsp.content!!.toString(Charsets.UTF_8))
                mTimestamp.stringCoder = Date()
            }
            return
        }
        if (rsp.status != Error.NetworkError.raw) {
            return
        }
        if (mLastActiveAddress.isNotEmpty()) {
            api.serverAddress = IPAddress(mLastActiveAddress, defaultPort)
            rsp = api.getStringCoderText()
            if (rsp.status == 0){
                if (rsp.content!!.isNotEmpty()) {
                    writeFile(rsp.content!!, STRING_FILE_NAME)
                    mStringCoder = ServerAPI.StringCoder(rsp.content!!.toString(Charsets.UTF_8))
                    mTimestamp.stringCoder = Date()
                }
                return
            }
            if (rsp.status != Error.NetworkError.raw) {
                return
            }
        }
        val checkList = randOrderNodeList(200)
        for (v in checkList) {
            api.serverAddress = IPAddress(v.address, defaultPort)
            rsp = api.getStringCoderText()
            if (rsp.status == 0){
                if (rsp.content!!.isNotEmpty()) {
                    writeFile(rsp.content!!, STRING_FILE_NAME)
                    mStringCoder = ServerAPI.StringCoder(rsp.content!!.toString(Charsets.UTF_8))
                    mTimestamp.stringCoder = Date()
                }
            }
            if (rsp.status != Error.NetworkError.raw) {
                return
            }
        }
    }

    private fun updateProvinceCoderWithAntiDDOS() {
        val api = ServerAPI(IPAddress(defaultAddress, defaultPort))
        var rsp = api.getProvinceCoderText()
        if (rsp.status == 0){
            if (rsp.content!!.isNotEmpty()) {
                writeFile(rsp.content!!, PROVINCE_FILE_NAME)
                mProvinceCoder = ServerAPI.StringCoder(rsp.content!!.toString(Charsets.UTF_8))
                mTimestamp.stringCoder = Date()
            }
            return
        }
        if (rsp.status != Error.NetworkError.raw) {
            return
        }
        if (mLastActiveAddress.isNotEmpty()) {
            api.serverAddress = IPAddress(mLastActiveAddress, defaultPort)
            rsp = api.getProvinceCoderText()
            if (rsp.status == 0){
                if (rsp.content!!.isNotEmpty()) {
                    writeFile(rsp.content!!, PROVINCE_FILE_NAME)
                    mProvinceCoder = ServerAPI.StringCoder(rsp.content!!.toString(Charsets.UTF_8))
                    mTimestamp.stringCoder = Date()
                }
                return
            }
            if (rsp.status != Error.NetworkError.raw) {
                return
            }
        }
        val checkList = randOrderNodeList(50)
        for (v in checkList) {
            api.serverAddress = IPAddress(v.address, defaultPort)
            rsp = api.getProvinceCoderText()
            if (rsp.status == 0){
                if (rsp.content!!.isNotEmpty()) {
                    writeFile(rsp.content!!, PROVINCE_FILE_NAME)
                    mProvinceCoder = ServerAPI.StringCoder(rsp.content!!.toString(Charsets.UTF_8))
                    mTimestamp.stringCoder = Date()
                }
                return
            }
            if (rsp.status != Error.NetworkError.raw) {
                return
            }
        }
    }

    private fun updateCoder(): ResultWithError<Boolean> {
        var cloud = false
        if (Date().unixTime() - mTimestamp.nodeList.unixTime() > 1000 * 60 * 60) {
            cloud = true
        }
        if (!cloud && mProvinceCoder != null && mStringCoder != null) {
            return ResultWithError(0, "ok", true)
        }
        Log.d(TAG, "update coder from cloud")
        updateStringCoderWithAntiDDOS()
        updateProvinceCoderWithAntiDDOS()
        if (mProvinceCoder != null && mStringCoder != null) {
            return ResultWithError(0, "ok", true)
        }
        return ResultWithError(Error.NetworkError.raw, ServerAPI.NetworkErrorText, true)
    }

    private fun getServerListBytesWithAntiDDOS(
        user: String,
        key: String
    ): ResultWithError<ByteArray?> {
        val api = ServerAPI(IPAddress(defaultAddress, defaultPort))
        var rsp = api.getServerListBytes(user, key)
        if (rsp.status != Error.NetworkError.raw) {
            return rsp
        }
        if (mLastActiveAddress.isNotEmpty()) {
            api.serverAddress = IPAddress(mLastActiveAddress, defaultPort)
            rsp = api.getServerListBytes(user, key)
            if (rsp.status != Error.NetworkError.raw) {
                return rsp
            }
        }
        val checkList = randOrderNodeList(200)
        for (v in checkList) {
            api.serverAddress = IPAddress(v.address, defaultPort)
            rsp = api.getServerListBytes(user, key)
            if (rsp.status != Error.NetworkError.raw) {
                mLastActiveAddress = v.address
                return rsp
            }
        }
        return rsp
    }

    private fun updateServerList(): ResultWithError<Boolean> {
        if (mLoginInfo == null) {
            return ResultWithError(Error.UserNotLogin.raw, "User Not Login", false)
        }
        val rspCoder = updateCoder()
        if (rspCoder.status != 0){
            return rspCoder
        }
        var cloud = false
        if (mNodeList.size == 0) {
            cloud = true
        }
        if (Date().unixTime() - mTimestamp.nodeList.unixTime() > 1000 * 60 * 10) {
            cloud = true
        }
        if (!cloud) {
            return ResultWithError(0, "ok", true)
        }
        val rsp = getServerListBytesWithAntiDDOS(mLoginInfo!!.user, mLoginInfo!!.key)
        if (rsp.status != 0) {
            return ResultWithError(rsp.status, rsp.msg, false)
        }
        mNodeList.clear()
        mNodeList.addAll(ServerAPI.parseServerList(rsp.content!!, mProvinceCoder!!, mStringCoder!!))
        mTimestamp.nodeList = Date()
        writeFile(rsp.content, NODE_LIST_FILE_NAME)
        return ResultWithError(0, "ok", true)
    }

    private fun getRealTimeAddressWithAntiDDOS(
        user: String, key: String,
        name: String
    ): ResultWithError<IPAddress?> {
        val api = ServerAPI(IPAddress(defaultAddress, defaultPort))
        var rsp = api.getRealTimeAddress(user, key, name)
        if (rsp.status != Error.NetworkError.raw) {
            return rsp
        }
        if (mLastActiveAddress.isNotEmpty()) {
            api.serverAddress = IPAddress(mLastActiveAddress, defaultPort)
            rsp = api.getRealTimeAddress(user, key, name)
            if (rsp.status != Error.NetworkError.raw) {
                return rsp
            }
        }
        val checkList = randOrderNodeList(200)
        for (v in checkList) {
            api.serverAddress = IPAddress(v.address, defaultPort)
            rsp = api.getRealTimeAddress(user, key, name)
            if (rsp.status != Error.NetworkError.raw) {
                mLastActiveAddress = v.address
                return rsp
            }
        }
        return rsp
    }

    private fun checkAddressIsUsedWithAntiDDOS(
        user: String, key: String,
        address: String
    ): ResultWithError<Boolean> {
        val api = ServerAPI(IPAddress(defaultAddress, defaultPort))
        var rsp = api.checkIP(user, key, address)
        if (rsp.status != Error.NetworkError.raw) {
            return rsp
        }
        if (mLastActiveAddress.isNotEmpty()) {
            api.serverAddress = IPAddress(mLastActiveAddress, defaultPort)
            rsp = api.checkIP(user, key, address)
            if (rsp.status != Error.NetworkError.raw) {
                return rsp
            }
        }
        val checkList = randOrderNodeList(200)
        for (v in checkList) {
            api.serverAddress = IPAddress(v.address, defaultPort)
            rsp = api.checkIP(user, key, address)
            if (rsp.status != Error.NetworkError.raw) {
                mLastActiveAddress = v.address
                return rsp
            }
        }
        return rsp
    }

    private fun readFile(name: String): ByteArray? {
        var ret: ByteArray? = null
        try {
            val r = context.openFileInput(name)
            ret = r.readBytes()
            r.close()
        } catch (exp: Exception) {
            exp.printStackTrace()
        }
        return ret
    }

    private fun writeFile(data: ByteArray, name: String): Boolean {
        var ret = false
        try {
            context.deleteFile(name)
            val w = context.openFileOutput(name, 0)
            w.write(data)
            ret = true
        } catch (exp: Exception) {
            exp.printStackTrace()
        }
        return ret
    }

    private fun <T : Parcelable> saveObject(name: String, obj: T?): Boolean {
        if (obj == null) {
            context.deleteFile(name)
            return false
        }
        var result = false
        try {
            val parcel = Parcel.obtain()
            parcel.writeParcelable(obj, 0)
            parcel.setDataPosition(0)
            val content = parcel.marshall()
            result = writeFile(content, name)
            parcel.recycle()
        } catch (exp: IOException) {
            exp.printStackTrace()
        } catch (exp: RuntimeException) {
            exp.printStackTrace()
        }
        return result
    }

    private fun <T : Parcelable> loadObject(name: String, loader: ClassLoader?): T? {
        var result: T? = null
        try {
            val data = readFile(name)
            if (data != null) {
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                result = parcel.readParcelable(loader)
                parcel.recycle()
            }
        } catch (exp: BadParcelableException) {
            exp.printStackTrace()
        } catch (exp: IOException) {
            exp.printStackTrace()
        } catch (exp: RuntimeException) {
            exp.printStackTrace()
        }
        return result
    }

    private fun Long.unixToDate(): Date {
        return Date(this * 1000)
    }

    private fun Date.unixTime(): Long {
        return time / 1000
    }

    companion object {
        private const val TAG = "ServerAPIProvider"
        private const val PROVINCE_FILE_NAME = "core_province.dat"
        private const val STRING_FILE_NAME = "core_string.dat"
        private const val NODE_LIST_FILE_NAME = "core_node_list.dat"
        private const val UPDATE_TIMESTAMP_FILE_NAME = "core_timestamp.dat"
        private const val LOGIN_INFO_FILE_NAME = "core_login.dat"
        private const val USER_INFO_FILE_NAME = "core_user_info.dat"
        private var defaultAddress: String = ""
        private var defaultPort: Int = 0
        private var selectSeed: Int = System.currentTimeMillis().toInt() and 0xFFFF
        private var sInstance: ServerAPIProvider? = null

        /**
         * init 初始化单例对象
         * @param context 内部会持有context到进程结束，所以建议传递application作为context
         * @param address 接入服务器地址
         * @param port 接入服务器端口
         * @return true for success
         */
        fun init(context: Context,address: String,port:Int):Boolean{
            if (address.isEmpty() || port == 0){
                return false
            }
            if (sInstance == null) {
                sInstance = ServerAPIProvider(context)
            }
            defaultAddress = address
            defaultPort = port
            return sInstance != null
        }

        /**
         * getInstance 获取ServerAPIProvider单例对象，如果没有初始化，抛出异常
         * @return {返回ServerAPIProvider对象}
         * @throws RuntimeException
         */
        fun getInstance(): ServerAPIProvider {
            if (sInstance == null ) {
               throw RuntimeException("class not initialize")
            }
            if (defaultAddress.isEmpty() || defaultPort == 0){
                throw RuntimeException("server address/port not set")
            }
            return sInstance!!
        }
    }
}
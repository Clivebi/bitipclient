/**
 *
 */
package com.kaopuip.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.util.Log
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*


/**
 * @see LocalVpnService VPN服务实现
 */
@Suppress("unused")
class LocalVpnService : VpnService() {
    /**
     * @see CommandParameter 启动参数
     * @param user 用户名
     * @param key 用户密码,可用用户名明文或者md5 hex字符串
     * @param vpnNode 连接的VPN节点
     * @param packageName 调用者的包名
     */
    @Parcelize
    class CommandParameter(
        val user: String,
        var key: String,
        val vpnNode: VPNNode,
        val packageName: String
    ) : Parcelable

    /**
     * @see UIOption 通知栏选项
     * @param largeIcon 大图标图片
     */
    @Parcelize
    class UIOption(val largeIcon: Bitmap) : Parcelable

    private var mNetwork: ProtocolTCPClient? = null
    private var mVPN: ParcelFileDescriptor? = null
    private var mSendBytes: Long = 0
    private var mRecvBytes: Long = 0
    private var mSendSpeed: Int = 0
    private var mRecvSpeed: Int = 0
    private val mHandler = Handler()

    // private val mUpdater = Updater()
    private var mUIOption: UIOption? = null
    private lateinit var mParameter: CommandParameter

    private inner class Updater : Runnable {
        var isConnecting: Boolean = false
        var isExit: Boolean = false
        private var drawIndex: Int = 0
        override fun run() {
            if (isExit) {
                return
            }
            mSendBytes += mSendSpeed
            mRecvBytes += mRecvSpeed
            val send = "${convertValue(mSendSpeed.toLong(), "/s")} ${convertValue(mSendBytes, "")}"
            val recv = "${convertValue(mRecvSpeed.toLong(), "/s")} ${convertValue(mRecvBytes, "")}"
            mSendSpeed = 0
            mRecvSpeed = 0
            val speedInfo = String.format("↑%s ↓%s", send, recv)
            updateRemoteView(speedInfo)
            mHandler.postDelayed(this, 1000)
        }

        private fun convertValue(value: Long, suffix: String): String {
            if (value > 1024 * 1024 * 1024) {
                return "${value / (1024 * 1024 * 1024)} G$suffix"
            }
            if (value > 1024 * 1024) {
                return "${value / (1024 * 1024)} M$suffix"
            }
            if (value > 1024) {
                return "${value / (1024)} KB$suffix"
            }
            return "${value / (1024)} Byte$suffix"
        }

        @Suppress("DEPRECATION")
        private fun updateRemoteView(text: String) {
            val icons = arrayOf(R.drawable.ic_stat_vpn, R.drawable.ic_stat_vpn_empty_halo)
            if (mUIOption == null) {
                return
            }
            val address =
                "${mParameter.vpnNode.province}.${mParameter.vpnNode.city}.${mParameter.vpnNode.carrier} " +
                        mParameter.vpnNode.address
            val small = if (isConnecting) {
                drawIndex++
                icons[drawIndex % 2]
            } else {
                icons[0]
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this@LocalVpnService, "28E8B700-54E4-49A2-ABF0-67D0DACBC61F")
            } else {
                Notification.Builder(this@LocalVpnService)
            }
            builder.setContentTitle(address)
                .setContentText(text)
                .setLargeIcon(mUIOption!!.largeIcon)
                .setSmallIcon(small)
                .setOnlyAlertOnce(true)
            val notify = builder.build()
            notify.flags = Notification.FLAG_ONGOING_EVENT or notify.flags
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(200, notify)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "28E8B700-54E4-49A2-ABF0-67D0DACBC61F",
                "vpninfo",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setSound(null,null)
            channel.enableLights(false)
            channel.setShowBadge(false)
            mgr.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("COMMAND") == "STOP") {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.cancel(200)
            this.stopVPN()
        } else {
            mParameter = intent!!.getParcelableExtra(KEY_START_PARAMETER)!!
            if (mParameter.key.isMd5Text()) {
                mParameter.key = mParameter.key.toLowerCase(Locale.getDefault())
            } else {
                mParameter.key = mParameter.key.md5().hexString()
            }
            mUIOption = intent.getParcelableExtra(KEY_UI_OPTION)
            val id = intent.getStringExtra(KEY_START_ID)!!
            GlobalScope.launch {
                vpnRunLoop(id)
            }
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(200)
        stopSelf()
    }

    private fun setupVPN(): Boolean {
        val builder = Builder()
            .addAddress("10.0.1.15", 24)
            .addDnsServer("223.5.5.5")
            .addDnsServer("223.6.6.6")
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)
            .setMtu(1500)
            .setSession("bitipVPN")
            .addDisallowedApplication(mParameter.packageName)
        var result = false
        try {
            mVPN = builder.establish()
            Log.d(TAG, "VPN interface has established")
            result = true
        } catch (exp: Exception) {
            exp.printStackTrace()
        }
        return result
    }

    private fun broadcastConnectResult(id: String, state: Int, error: String) {
        val intent = Intent(ACTION_VPN_STATE_CHANGED)
        intent.putExtra(KEY_START_ID, id)
        intent.putExtra(KEY_START_ERROR, error)
        intent.putExtra(KEY_SATE_VALUE, state)
        sendBroadcast(intent)
    }

    private fun vpnRunLoop(id: String) {
        Log.d(TAG, "start running loop")
        broadcastConnectResult(id, STATE_CONNECTING, "")
        val updater = Updater()
        updater.isConnecting = true
        updater.isExit = false
        mHandler.post(updater)
        val result =
            ProtocolTCPClient.newClientWithError(
                mParameter.user,
                mParameter.key,
                mParameter.vpnNode.address,
                mParameter.vpnNode.port
            )
        if (result.result == null) {
            broadcastConnectResult(id, STATE_CONNECT_FAILED, result.error)
            Log.e(TAG, "connect server failed:" + result.error)
            updater.isConnecting = false
            updater.isExit = true
            stopVPN()
            return
        }
        val con = result.result
        this.protect(con.socket)
        if (!setupVPN()) {
            con.close()
            broadcastConnectResult(id, STATE_CONNECT_FAILED, ESTABLISH_ERROR)
            Log.e(
                TAG,
                ESTABLISH_ERROR
            )
            updater.isConnecting = false
            updater.isExit = true
            stopVPN()
            return
        }
        val vpnInputStream = FileInputStream(mVPN!!.fileDescriptor).channel
        val vpnOutputStream = FileOutputStream(mVPN!!.fileDescriptor).channel
        var alive = true
        GlobalScope.launch {
            loop@ while (alive) {
                try {
                    val buffer = ByteBuffer.allocate(2000)
                    val readBytes = vpnInputStream.read(buffer)
                    if (readBytes <= 0) {
                        alive = false
                        Log.d(TAG, "read packet from tun failed")
                        break
                    }
                    mSendSpeed += readBytes
                    if (buffer.hasArray()) {
                        con.writeFrame(buffer.array(), 0, readBytes)
                    }
                } catch (e: IOException) {
                    alive = false
                    break
                }

            }
        }
        mNetwork = con
        broadcastConnectResult(id, STATE_CONNECT_OK, "connect ok")
        updater.isConnecting = false
        loop@ while (alive) {
            try {
                val frame = con.readFrame()
                if (frame == null) {
                    Log.d(TAG, "read packet from tcp failed")
                    alive = false
                    break
                }
                mRecvSpeed += frame.size
                vpnOutputStream.write(ByteBuffer.wrap(frame))
            } catch (e: IOException) {
                alive = false
                break
            }
        }
        updater.isExit = true
        broadcastConnectResult(id, STATE_DISCONNECTED, "")
        Log.i(TAG, "exit loop")
        stopVPN()
    }

    private fun stopVPN() {
        mVPN?.close()
        mVPN = null
        if (mNetwork != null) {
            mNetwork!!.close()
        }
        stopSelf()
        Log.i(TAG, "Stopped VPN")
    }

    companion object {
        /**
         * @hide
         */
        private const val TAG = "VPNService"

        /**
         * @hide
         */
        private const val ESTABLISH_ERROR = "establish vpn failed"

        /**
         * @hide
         */
        private const val KEY_UI_OPTION = "start.ui.options"

        /**
         * @hide
         */
        private const val KEY_START_PARAMETER = "start.parameter"

        /**
         * 状态广播消息，VPN连接状态变化会广播此消息
         */
        const val ACTION_VPN_STATE_CHANGED = "com.bitip.vpn.state_changed"

        /**
         * VPN状态 intent KEY_NAME
         */
        const val KEY_SATE_VALUE = "state"

        /**
         * 正在连接中
         */
        const val STATE_CONNECTING = 0x1

        /**
         * 连接失败，错误信息位于：KEY_START_ERROR
         */
        const val STATE_CONNECT_FAILED = 0x2

        /**
         * 连接成功
         */
        const val STATE_CONNECT_OK = 0x3

        /**
         * 连接断开，并不是每次调用stopservice都会收到这个消息，收到这个消息的前提是前一个状态是STATE_CONNECT_OK
         */
        const val STATE_DISCONNECTED = 0x4

        /**
         * 连接ID，由startService 传入
         */
        const val KEY_START_ID = "start.id"

        /**
         * 错误信息，当状态是 (STATE_CONNECT_FAILED)的时候标志错误信息
         */
        const val KEY_START_ERROR = "start.error"

        /**
         *启动VPN服务
         * @param  context 调用者的context
         * @param param 启动参数，参考CommandParameter
         * @param id 启动序列号，由调用者使用，这个id会在ACTION_VPN_STATE_CHANGED广播中原样传回
         * @param uiOption 可选参数，如果传递null，则不显示通知栏，否则显示通知栏
         */
        fun startVPNService(
            context: Context,
            param: CommandParameter,
            id: String,
            uiOption: UIOption? = null
        ): ComponentName? {
            val intent = Intent(context, LocalVpnService::class.java)
            intent.putExtra("COMMAND", "START")
            intent.putExtra(KEY_START_PARAMETER, param)
            intent.putExtra(KEY_START_ID, id)
            if (uiOption != null) {
                intent.putExtra(KEY_UI_OPTION, uiOption)
            }
            return context.startService(intent)
        }

        /**
         *停止VPN服务
         * @param context 调用者的context
         */
        fun stopVPNService(context: Context) {
            val intent = Intent(context, LocalVpnService::class.java)
            intent.putExtra("COMMAND", "STOP")
            context.startService(intent)
            return
        }
    }
}

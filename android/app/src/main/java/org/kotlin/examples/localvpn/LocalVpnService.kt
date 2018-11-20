package org.kotlin.examples.localvpn

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.experimental.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import android.support.v4.content.LocalBroadcastManager
import android.app.ActivityManager
import android.content.Context
import android.os.Binder
import android.os.Binder.getCallingPid


const val TAG = "bitipVPN"
const val VPN_BROADCAST_ACTION_NAME = "com.bitip.vpn.service.status"



class LocalVpnService : VpnService() {

    private var client:ProtocolTcpClient? = null
    private var vpnInterface: ParcelFileDescriptor? = null

    private var username:String = ""
    private var token:String = ""
    private var server:String = ""
    private var port:Int = 0
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("COMMAND") == "STOP") {
            this.stopVpn()
        }else {
            username = intent!!.getStringExtra("USER")
            token = intent!!.getStringExtra("PASSWORD")
            server = intent!!.getStringExtra("SERVER")
            port = intent!!.getIntExtra("PORT",0)
            this.startVpn()
        }
        return Service.START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSelf()
    }

    private fun setupVpn() {
        var builder = Builder()
                .addAddress("10.0.1.15", 24)
                .addDnsServer("223.5.5.5")
                .addDnsServer("223.6.6.6")
                .addRoute("0.0.0.0", 0)
                .setBlocking(true)
                .setMtu(1500)
                .setSession(TAG)
                .addDisallowedApplication(getAppPkg(Binder.getCallingPid()))
        vpnInterface = builder.establish()
        Log.d(TAG, "VPN interface has established")
    }

    private fun startVpn() {
        launch { vpnRunLoop() }
    }

    private fun getAppPkg(pid: Int): String {
        var processName = ""
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (activityManager != null) {
            val list = activityManager.runningAppProcesses
            for (info in list) {
                if (info.pid == pid) {
                    processName = info.processName
                    break
                }
            }
        }
        return processName
    }


    private  fun broadcastStatus(error:String) {
        var intent = Intent(VPN_BROADCAST_ACTION_NAME)
        intent.putExtra("error",error)
        sendBroadcast(intent)
    }

    suspend fun vpnRunLoop() {
        Log.d(TAG, "running loop")
        this.client = ProtocolTcpClient(username,token,server,port)
        if (!client!!.isConnected) {
            this.broadcastStatus(this.client!!.errorMsg)
            Log.i(TAG, "connect server failed ..."+client!!.errorMsg)
            stopVpn()
            return
        }else{
            this.broadcastStatus(this.client!!.errorMsg)
        }
        this.protect(client!!.socket)
        setupVpn()
        val vpnInputStream = FileInputStream(vpnInterface!!.fileDescriptor).channel
        val vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor).channel
        var alive = true
        launch {
            loop@ while (alive) {
                try {
                    val buffer = ByteBuffer.allocate(2000)
                    val readBytes = vpnInputStream.read(buffer)
                    if (readBytes <= 0) {
                        alive = false
                        Log.d(TAG,"read packet from tun failed")
                        break
                    }
                    if (buffer.hasArray()) {
                        client!!.writeFrame(buffer.array(),0,readBytes)
                    }
                }catch(e:IOException){
                    Log.d(TAG,"IOCopy0 Exception"+e.localizedMessage)
                    alive = false
                    break
                }

            }
        }

        loop@ while (alive) {
            try {
                val frame = client!!.readFrame()
                if (frame == null) {
                    Log.d(TAG,"read packet from tcp failed")
                    alive = false
                    break
                }
                vpnOutputStream.write(ByteBuffer.wrap(frame))
            }catch(e:IOException){
                Log.d(TAG,"IOCopy1 Exception"+e.localizedMessage)
                alive = false
                break
            }
        }
        stopVpn()
        Log.i(TAG, "exit loop")
    }

    private fun stopVpn() {
        vpnInterface?.close()
        if (client != null) {
            client!!.Close()
        }
        stopSelf()
        Log.i(TAG, "Stopped VPN")
    }
}

package org.kotlin.examples.localvpn

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Exception
import java.nio.ByteBuffer

class LocalVpnService : VpnService() {
    class CommandParameter(val user:String,val token:String,val server:String,val port:Int)
    private var mNetwork:ProtocolTCPClient? = null
    private var mVPN: ParcelFileDescriptor? = null
    private lateinit var mParameter:CommandParameter

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("COMMAND") == "STOP") {
            this.stopVPN()
        }else {
            mParameter = CommandParameter(intent!!.getStringExtra(KEY_USER_NAME),
                     intent.getStringExtra(KEY_PASSWORD),
                     intent.getStringExtra(KEY_SERVER),
                     intent.getIntExtra(KEY_SERVER_PORT,8080))
            val id = intent.getStringExtra(KEY_START_ID)
            GlobalScope.launch {
                vpnRunLoop(id)
            }
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSelf()
    }

    private fun setupVPN():Boolean {
        val builder = Builder()
                .addAddress("10.0.1.15", 24)
                .addDnsServer("223.5.5.5")
                .addDnsServer("223.6.6.6")
                .addRoute("0.0.0.0", 0)
                .setBlocking(true)
                .setMtu(1500)
                .setSession("bitipVPN")
                .addDisallowedApplication(packageName)
        var result = false
        try {
            mVPN = builder.establish()
            Log.d(TAG, "VPN interface has established")
            result = true
        }catch (exp:Exception){
            exp.printStackTrace()
        }
        return result
    }

    private  fun broadcastConnectResult(id:String,result:String){
        val intent = Intent(VPN_SERVICE_ACTION_RESULT)
        intent.putExtra(KEY_START_ID,id)
        intent.putExtra(KEY_START_ERROR,result)
        sendBroadcast(intent)
    }

    private fun vpnRunLoop(id:String) {
        Log.d(TAG, "start running loop")
        val result = ProtocolTCPClient.newClientWithError(mParameter.user,mParameter.token,mParameter.server,mParameter.port)
        if (result.result == null){
            broadcastConnectResult(id,result.error)
            Log.e(TAG,"connect server failed:"+result.error)
            stopVPN()
            return
        }
        val con = result.result
        this.protect(con.socket)
        if(!setupVPN()){
            broadcastConnectResult(id, ESTABLISH_ERROR)
            Log.e(TAG, ESTABLISH_ERROR)
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
                        Log.d(TAG,"read packet from tun failed")
                        break
                    }
                    if (buffer.hasArray()) {
                        con.writeFrame(buffer.array(),0,readBytes)
                    }
                }catch(e:IOException){
                    alive = false
                    break
                }

            }
        }
        mNetwork = con
        broadcastConnectResult(id,"connect ok")
        loop@ while (alive) {
            try {
                val frame = con.readFrame()
                if (frame == null) {
                    Log.d(TAG,"read packet from tcp failed")
                    alive = false
                    break
                }
                vpnOutputStream.write(ByteBuffer.wrap(frame))
            }catch(e:IOException){
                alive = false
                break
            }
        }
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
    companion object{
        private  const val TAG ="VPNService"
        private  const val ESTABLISH_ERROR = "establish vpn failed"
        const val VPN_SERVICE_ACTION_RESULT = "com.bitip.vpn.service.start.result"
        const val KEY_START_ID = "start.id"
        const val KEY_START_ERROR = "start.error"
        const val KEY_USER_NAME = "start.name"
        const val KEY_PASSWORD = "start.password"
        const val KEY_SERVER = "start.server"
        const val KEY_SERVER_PORT = "start.server.port"
    }
}

package org.kotlin.examples.localvpn

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.net.VpnService
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.View
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.experimental.launch
import java.util.*
import kotlin.math.log
import android.widget.Toast
import android.content.BroadcastReceiver
import android.content.IntentFilter


class MainActivity : AppCompatActivity() {

    inner class AsyncHandler:Handler() {
        var activity:MainActivity?=null
        override fun handleMessage(msg: Message?) {
            super.handleMessage(msg)
            activity?.asyncStartVPN()
        }
    }

    inner class ServiceStausReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent){
            var text:String = textView.text.toString()
            text += "\r\nstatus:"
            text += intent.getStringExtra("error")
            textView.text = text
        }
    }
    val  statusReceiver:ServiceStausReceiver = ServiceStausReceiver()
    val  handle:AsyncHandler = AsyncHandler()
    var  list:List<VPNNode> = mutableListOf<VPNNode>()
    var  index:Int = 0
    var  updateDate:Date = Date()
    val  user:String = "clive@admin.com"
    val  key:String = "e10adc3949ba59abbe56e057f20f883e"
    var  selectedAddrss:IPAddress = IPAddress("",0)
    var  selectedNode:VPNNode = VPNNode("","",0,"","")

    companion object {
        private const val TAG="LocalVPN";
        private const val VPN_REQUEST_CODE = 0x0F
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        //监听服务广播消息
        val filter = IntentFilter(VPN_BROADCAST_ACTION_NAME)
        registerReceiver(statusReceiver,filter)
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    fun stopVpn(view: View){
        Log.d(TAG,"Stop VPN")
        val intent = Intent(this, LocalVpnService::class.java)
        intent.putExtra("COMMAND", "STOP")
        startService(intent)
    }

    fun asyncStartVPN(){
        Log.d(TAG,"Start VPN")
        var text:String = "selected node:"+selectedNode.name+"\r\n"
        text += ("province:"+selectedNode.province)
        text += ("city:"+selectedNode.city+"\r\n")
        text += ("address:"+selectedAddrss.address)
        textView.text= text
        handle.activity = null
        val intent= VpnService.prepare(this)
        if (intent!=null){
            startActivityForResult(intent, VPN_REQUEST_CODE);
        }else{
            onActivityResult(VPN_REQUEST_CODE, Activity.RESULT_OK, null);
        }
    }

    fun workThread():Boolean{
        // 1、第一步，获取服务器列表，并缓存
        // 2、第二步，遍历服务器列表，可以根据喜好对省份进行过滤，选中节点
        // 3、第三步，获取节点的实时服务IP地址和端口
        // 4、第四步，根据需要判断IP是否被自己的账户使用过
        // 5、第五步，传递参数，启动VPN服务
        selectedAddrss = IPAddress("",0)
        val serverAPI:ServerAPI = ServerAPI()
        if (list.count()==0 || (Date().time-this.updateDate.time) > 20*60) {
            // 1、第一步，获取服务器列表，并缓存
            val result = serverAPI.getServerList(user,key)
            if (result.status != 0) {
                Log.d(TAG,result.message)
                return false
            }
            this.list = result.list
        }
        if (this.list.count() == 0) {
            Log.d(TAG,"NO VPN Node Server")
            return false
        }
        // 2、第二步，遍历服务器列表，可以根据喜好对省份进行过滤，选中节点
        // 3、第三步，获取节点的实时服务IP地址和端口
        if (this.index >= this.list.count()) {
            this.index = 0
        }
        for (i in (index..this.list.count())){
            val x = this.list[i]
            val ipResult = serverAPI.getRealTimeAddress(user,key,x.name)
            if (ipResult.status != 0) {
                Log.d(TAG,ipResult.message)
                return false
            }
            if (ipResult.address.port == 0) {
                continue
            }
            // 4、第四步，根据需要判断IP是否被自己的账户使用过
            val isUsed = serverAPI.checkIP(user,key,ipResult.address.address)
            if (isUsed.status != 0) {
                Log.d(TAG,isUsed.message)
                continue
            }
            if (isUsed.isused) {
                continue
            }
            selectedAddrss = ipResult.address
            selectedNode = x
            index = i+1
            break

        }
        if (selectedAddrss.address.length == 0) {
            Log.d(TAG,"NO Selected Address")
            return false
        }
        // 5、第五步，传递参数，启动VPN服务
        handle.sendMessage(Message())
        return true
    }

    fun startVpn(view: View){
        if (handle.activity != null) {
            Log.d(TAG,"connecting...,Waiting")
            Toast.makeText(this, "请等待上一个操作完成", Toast.LENGTH_SHORT).show();
            return
        }
        handle.activity = this
        stopVpn(view)
        launch {
            if (!workThread()){
                handle.activity = null
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode== Activity.RESULT_OK){
            val intent = Intent(this, LocalVpnService::class.java)
            intent.putExtra("COMMAND", "START")
            intent.putExtra("USER",user)
            intent.putExtra("PASSWORD",key)
            intent.putExtra("SERVER",selectedAddrss.address)
            intent.putExtra("PORT",selectedAddrss.port)
            startService(intent)
        }
    }
}

package org.kotlin.examples.localvpn

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.net.VpnService
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*
import android.widget.Toast
import android.content.BroadcastReceiver
import android.content.IntentFilter
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    var  isWorking:Boolean = false
    var  currentID:String = Date().toString()
    var  list:List<VPNNode> = mutableListOf<VPNNode>()
    var  index:Int = 0
    var  updateDate:Date = Date()
    var  connectTime:Long = System.currentTimeMillis()
    private val  receiver:ServiceStartActionReceiver = ServiceStartActionReceiver()
    private val  handler = Handler()
    private  val  user:String = "test@admin.com"
    private  val  key:String = "99816876t"
    var  selectedAddress:IPAddress = IPAddress("",0)
    var  selectedNode:VPNNode = VPNNode("","",0,"","","")

    inner class ServiceStartActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent){
            val id = intent.getStringExtra(LocalVpnService.KEY_START_ID)
            val result = intent.getStringExtra(LocalVpnService.KEY_START_ERROR)
            if (id == currentID){
                addLog("status:${result}")
                addLog("connect time:${System.currentTimeMillis()-connectTime} millis")
                isWorking = false
            }
            Log.d(TAG,"${id} ${result} ${currentID}")
        }
    }
    companion object {
        private const val TAG="LocalVPN";
        private const val VPN_REQUEST_CODE = 0x0F
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        //监听连接结果广播
        registerReceiver(receiver,IntentFilter(LocalVpnService.VPN_SERVICE_ACTION_RESULT))
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    fun addLog(log:String){
        handler.post {
            textView.text = "${textView.text}\r\n${log}"
        }
    }
    private fun clearLog(){
        textView.text = ""
    }

    fun stopVpn(view: View){
        Log.d(TAG,"Stop VPN")
        val intent = Intent(this, LocalVpnService::class.java)
        intent.putExtra("COMMAND", "STOP")
        startService(intent)
    }

    private fun startService(){
        addLog("start VPN service")
        addLog("selected node:${selectedNode.name}")
        addLog("province:${selectedNode.province}")
        addLog("city:${selectedNode.city}")
        addLog("carrier:${selectedNode.carrier}")
        addLog("address:${selectedAddress.address}")
        val intent= VpnService.prepare(this)
        if (intent!=null){
            startActivityForResult(intent, VPN_REQUEST_CODE);
        }else{
            onActivityResult(VPN_REQUEST_CODE, Activity.RESULT_OK, null);
        }
    }

    private fun selectNode():Boolean{
        // 1、第一步，获取服务器列表，并缓存
        // 2、第二步，遍历服务器列表，可以根据喜好对省份进行过滤，选中节点
        // 3、第三步，获取节点的实时服务IP地址和端口
        // 4、第四步，根据需要判断IP是否被自己的账户使用过
        // 5、第五步，传递参数，启动VPN服务
        selectedAddress = IPAddress("",0)
        val serverAPI:ServerAPI = ServerAPI()
        if (list.count()==0 || (Date().time-this.updateDate.time) > 20*60) {
            // 1、第一步，获取服务器列表，并缓存
            val result = serverAPI.getServerList(user,key)
            if (result.status != 0) {
                addLog("getServerList error :${result.message}")
                return false
            }
            this.list = result.list
        }
        if (this.list.count() == 0) {
            addLog("server list unavailable")
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
                //Log.d(TAG,ipResult.message)
                addLog("get real time address error:${ipResult.message}")
                return false
            }
            if (ipResult.address.port == 0) {
                continue
            }
            // 4、第四步，根据需要判断IP是否被自己的账户使用过
            //val isUsed = serverAPI.checkIP(user,key,ipResult.address.address)
            //if (isUsed.status != 0) {
            //    Log.d(TAG,isUsed.message)
            //    continue
            //}
            //if (isUsed.isused) {
            //    continue
            //}
            selectedAddress = ipResult.address
            selectedNode = x
            index = i+1
            break

        }
        if (selectedAddress.address.isEmpty()) {
            addLog("no server match request")
            return false
        }
        // 5、第五步，传递参数，启动VPN服务
        return true
    }

    fun startVpn(view: View){
        if (this.isWorking){
            Log.d(TAG,"connecting...,Waiting")
            Toast.makeText(this, "请等待上一个操作完成", Toast.LENGTH_SHORT).show();
            return
        }
        this.isWorking = true
        currentID = Date().toString()
        clearLog()
        stopVpn(view)
        GlobalScope.launch{
            addLog("start select node...")
            var time = System.currentTimeMillis()
            if(selectNode()){
                addLog("select time= ${System.currentTimeMillis()-time} millis")
                handler.post {
                    startService()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode== Activity.RESULT_OK){
            currentID = Date().toString()
            connectTime = System.currentTimeMillis()
            val intent = Intent(this, LocalVpnService::class.java)
            intent.putExtra("COMMAND", "START")
            intent.putExtra(LocalVpnService.KEY_USER_NAME,user)
            intent.putExtra(LocalVpnService.KEY_PASSWORD,key)
            intent.putExtra(LocalVpnService.KEY_SERVER,selectedAddress.address)
            intent.putExtra(LocalVpnService.KEY_SERVER_PORT,selectedAddress.port)
            intent.putExtra(LocalVpnService.KEY_START_ID,currentID)
            startService(intent)
        }
    }
}

package com.kaopuip.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kaopuip.app.common.*
import com.kaopuip.core.IPAddress
import com.kaopuip.core.LocalVpnService
import com.kaopuip.core.ServerAPIProvider
import com.kaopuip.core.VPNNode
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.startActivity
import org.jetbrains.anko.toast
import org.jetbrains.anko.uiThread
import java.lang.Exception
import java.util.*
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity(),
    ConfigSheet.Listener {
    companion object{
        private const val VPN_REQUEST_CODE = 0x0F
        const val TAG = "MainActivity"
    }
    private var mSelected:Int = -1
    private var mIsConnected:Boolean = false
    private val mAdapter: ConfigListAdapter = ConfigListAdapter()
    private val mReceiver: ServiceStartActionReceiver = ServiceStartActionReceiver()
    private var mIsConnecting = false
    private var mCurrentID = Date().toString()
    private var mNode: VPNNode = VPNNode("","",0,"","","")
    private var mConfigs:ArrayList<ServerAPIProvider.IPSelector> = arrayListOf()
    inner class ServiceStartActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent){
            val id = intent.getStringExtra(LocalVpnService.KEY_START_ID)
            val result = intent.getStringExtra(LocalVpnService.KEY_START_ERROR)
            if (id == mCurrentID){
                mIsConnecting = false
            }
            if (result == "connect ok"){
                mIsConnected = true
                changeIP.isSelected = true
                exit.visibility = View.VISIBLE
                status.text = status.text.toString()+" "+getString(R.string.msg_connect_ok)
            }else{
                changeIP.isSelected = false
                status.text = status.text.toString()+" "+getString(R.string.msg_connect_failed)
            }
        }
    }
    inner class ConfigListAdapter: RecyclerView.Adapter<ConfigListAdapter.ViewHolder>(){
        override fun getItemCount(): Int {
            return mConfigs.size
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(this@MainActivity).inflate(R.layout.item_listview,parent,false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            super.onBindViewHolder(holder, position, payloads)
            holder.container.isSelected = (mSelected == position)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val layout = holder.itemView as SwipeMenuLayout
            val data = mConfigs[position]
            layout.isIos = true
            layout.isSwipeEnable = true
            holder.delete.text = getString(R.string.title_delete)
            holder.title.text = getTitle(data)
            holder.subtitle.text = getSubtitle(data)
            holder.subtitle2.text = getSubtitle2(data)
            holder.container.isSelected = (mSelected == position)
            holder.container.setOnClickListener {
                val old = mSelected
                holder.container.isSelected = true
                mSelected = position
                if (old != -1){
                    notifyItemChanged(old,"view")
                }
                notifyItemChanged(position,"view")
            }
            holder.delete.setOnClickListener {
                mConfigs.removeAt(position)
                this.notifyItemRemoved(position)
            }
        }

        private  fun getTitle(src: ServerAPIProvider.IPSelector):String {
            if (src.province == this@MainActivity.getString(R.string.title_all) || src.province.isEmpty()){
                return this@MainActivity.getString(R.string.title_unset_province)
            }
            return src.province
        }
        private fun getSubtitle(src: ServerAPIProvider.IPSelector): String {
            val city =
                if (src.city == this@MainActivity.getString(R.string.title_all) ||src.city.isEmpty()) {
                    this@MainActivity.getString(R.string.title_unset_city)
                } else {
                    src.city
                }
            val carrier =
                if (src.carrier == this@MainActivity.getString(R.string.title_all) || src.carrier.isEmpty()) {
                    this@MainActivity.getString(R.string.title_unset_carrier)
                } else {
                    src.carrier
                }
            return "$city $carrier"
        }
        private  fun getSubtitle2(src: ServerAPIProvider.IPSelector):String {
            if (!src.ignoreUsedIP){
                return this@MainActivity.getString(R.string.title_unset_used_ip)
            }
            return this@MainActivity.getString(R.string.title_set_used_ip)
        }
        inner class ViewHolder(view: View): RecyclerView.ViewHolder(view){
            var container: ConstraintLayout =view.findViewById(R.id.container)
            var header: ImageView =view.findViewById(R.id.header)
            var title: TextView =view.findViewById(R.id.province)
            var subtitle: TextView =view.findViewById(R.id.citycarrier)
            var subtitle2: TextView =view.findViewById(R.id.ignoreusedip)
            var delete: TextView =view.findViewById(R.id.delete)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        translucentActionBar()
        setContentView(R.layout.activity_main)
        if(null == app().mStorage.getLoginInfo()){
            startActivity<LoginActivity>()
            finishAffinity()
        }
        mConfigs.add(ServerAPIProvider.IPSelector("","","",false))
        registerReceiver(mReceiver, IntentFilter(LocalVpnService.VPN_SERVICE_ACTION_RESULT))
        toolbar.layoutParams.height += statusBarHeight()
        toolbar.requestLayout()
        moreview.setOnClickListener {
            doAsync {
                app().mStorage.getNodeList()
                uiThread {
                    ConfigSheet(this@MainActivity).setListener(this@MainActivity).dialog().show()
                }
            }
        }
        recyclerview.layoutManager = LinearLayoutManager(this)
        recyclerview.adapter = mAdapter
        val div = DividerItemDecorationWithOffset(
            dp2px(16), this, DividerItemDecoration.VERTICAL
        )
        div.setDrawable(getDrawable(R.drawable.divider)!!)
        recyclerview.addItemDecoration(div)
        recyclerview.itemAnimator = DefaultItemAnimator()
        changeIP.setOnClickListener {
            startVPN()
        }
        exit.setOnClickListener {
            stopVPN()
            status.text = ""
        }
        buy.setOnClickListener {
            startActivity<GoodsActivity>()
        }
        doAsync {
            app().mStorage.updateUserInfo()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if(isServiceRunning(
                this,
                LocalVpnService::class.java.name
            )
        ){
            mIsConnected = true
            changeIP.isSelected = true
            exit.visibility = View.VISIBLE
            Log.d(TAG,"Service is running")
        }else{
            exit.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        unregisterReceiver(mReceiver)
        super.onDestroy()
    }

    override fun onConfigSheetOK(config: ServerAPIProvider.IPSelector) {
        if (config.province == getString(R.string.title_all)){
            config.province = ""
        }
        if (config.city == getString(R.string.title_all)){
            config.city = ""
        }
        if (config.carrier == getString(R.string.title_all)){
            config.carrier = ""
        }
        val count = mConfigs.size
        mConfigs.add(config)
        mAdapter.notifyItemInserted(count)
    }

    private fun stopVPN(){
        LocalVpnService.stopVPNService(this)
    }

    private fun startService(){
        val intent= VpnService.prepare(this)
        if (intent!=null){
            startActivityForResult(intent,
                VPN_REQUEST_CODE
            );
        }else{
            onActivityResult(VPN_REQUEST_CODE, Activity.RESULT_OK, null);
        }
    }
    private fun startVPN(){
        if (mIsConnecting){
            toast(R.string.title_wait)
            return
        }
        stopVPN()
        if(mSelected == -1 || mSelected+1 > mConfigs.size){
            toast(R.string.title_select_config)
            return
        }
        status.text = ""
        mIsConnecting = true
        mIsConnected  = false
        mCurrentID = Date().toString()
        val selector = mConfigs[mSelected]
        doAsync {
            var ret:VPNNode? = null
            try {
                ret = app().mStorage.selectOneNode(selector)
            }catch (exp:Exception){
                exp.printStackTrace()
            }
            uiThread {
                if(ret==null){
                    mIsConnecting = false
                    mIsConnected = false
                    toast(R.string.title_no_server_available)
                }else{
                    mNode = ret
                    status.text = "${mNode.province} ${mNode.city} ${mNode.carrier} ${mNode.address} "
                    startService()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode== Activity.RESULT_OK){
            LocalVpnService.startVPNService(this,LocalVpnService.CommandParameter(
                app().mStorage.getLoginInfo()!!.user,
                app().mStorage.getLoginInfo()!!.key,
                mNode.address,
                mNode.port,
                packageName
            ),mCurrentID)
            return
        }
        if(resultCode == VPN_REQUEST_CODE){
            toast("system error")
            mIsConnected = false
            mIsConnecting = false
            changeIP.isSelected = false
        }

    }
}

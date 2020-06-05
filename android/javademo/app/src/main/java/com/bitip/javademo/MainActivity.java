package com.bitip.javademo;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.kaopuip.core.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.kaopuip.core.ServerAPIProviderKt.coreLibVersion;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE = 378;
    private static final String User = "";
    private static final String Key = "";

    private ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private Handler mHandler = new Handler();
    private VPNNode mVPN = null;
    private ServiceStateChangeReceive   mReceive = new ServiceStateChangeReceive();
    private TextView mLogView = null;

    //接收服务广播消息
    class ServiceStateChangeReceive extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(LocalVpnService.KEY_SATE_VALUE, 100);
            String id = intent.getStringExtra(LocalVpnService.KEY_START_ID);
            switch (state) {
                case LocalVpnService.STATE_CONNECTING: {
                    logText(id+":Connecting...");
                }
                break;
                case LocalVpnService.STATE_CONNECT_FAILED: {
                    String error = intent.getStringExtra(LocalVpnService.KEY_START_ERROR);
                    logText(id+":Connect Failed "+error);
                }
                break;
                case LocalVpnService.STATE_DISCONNECTED:{
                    logText(id+":Disconnected ");
                }
                break;
                case LocalVpnService.STATE_CONNECT_OK:{
                    logText(id+":Connect OK ");
                }
                break;
                default:
                    break;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //监听广播
        registerReceiver(mReceive,new IntentFilter(LocalVpnService.ACTION_VPN_STATE_CHANGED));
        //初始化，只需要调用一次
        ServerAPIProvider.Companion.init(this, "cmnet.kaopuip.com", 6709);

        mLogView = (TextView)findViewById(R.id.log);

        //连接、断开连接事件绑定
        ((Button)findViewById(R.id.connect)).setOnClickListener((v)->{
            //开始切换IP
            if (User.length() == 0 || Key.length() == 0){
                reportError("请设置用户名和密码");
                return;
            }
            mExecutor.execute(this::executeChangeIP);
        });
        ((Button)findViewById(R.id.disconnect)).setOnClickListener((v)->{
            LocalVpnService.Companion.stopVPNService(this);
        });
        logText("CoreLibVersion:"+coreLibVersion);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mReceive);
        super.onDestroy();
    }

    protected void reportError(String msg) {
        mHandler.post(() -> {
            internalReportError(msg);
        });
    }

    protected void internalReportError(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    protected void logText(String text){
        mHandler.post(()->{
            internalLogText(text);
        });
    }
    protected void internalLogText(String text){
        mLogView.append(text+"\r\n");
    }


    protected void executeChangeIP() {
        //第一步检查是否需要登录
        if (null == ServerAPIProvider.Companion.getInstance().getLoginInfo()) {
            final ResultWithError<UserInfo> res = ServerAPIProvider.Companion.getInstance().login(User, Key);
            if (res.getStatus() != 0) {
                //登录失败
                reportError(res.getMsg());
                return;
            }
        }
        //第二步根据条件获取一个节点
        final ResultWithError<VPNNode> node = ServerAPIProvider.Companion.getInstance().selectOneNode(
                new ServerAPIProvider.IPSelector(
                        "", //省份
                        "", //城市
                        "",//运营商
                        false));
        if (node.getStatus() != 0) {
            //选择节点失败
            reportError(node.getMsg());
            return;
        }
        logText("selected node:"+node.getContent().getProvince() +" "+
                node.getContent().getCity()+" "+
                node.getContent().getCarrier()+" "+ node.getContent().getAddress());
        //第三步，启动VPN服务连接节点
        mHandler.post(() -> {
            startVPN(node.getContent());
        });
    }

    protected void startVPN(VPNNode node) {
        mVPN = node;
        Intent intent = VpnService.prepare(this);
        if (intent == null) {
            onActivityResult(REQUEST_CODE, Activity.RESULT_OK, null);
        } else {
            startActivityForResult(intent, REQUEST_CODE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE) {
            LocalVpnService.Companion.startVPNService(
                    this,
                    new LocalVpnService.CommandParameter(User, Key, mVPN, getPackageName()),
                    Long.toString(System.currentTimeMillis()),
                    null
            );
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
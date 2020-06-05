
# Android开发指引  

## coreLib集成   
### 集成
Project 的build.gradle添加maven依赖  

```
allprojects {  

    repositories {  

        google()  

        jcenter()  

        maven{ url "https://raw.githubusercontent.com/Clivebi/bitipclient/master/android/superipApp/core/maven"}  

    }  

}
```

Module 的build.gradle  添加  

`implementation 'com.bitip:core:1.0.8'`

AndroidManifest.xml添加权限    

`<uses-permission android:name="android.permission.INTERNET" />`

### 接口指引
1. 在Application实例或者Activity实例onCreate中初始化coreLib库，推荐在Application的onCreate中初始化,初始化参数为接入服务器的域名和端口  
`
@Override

    protected void onCreate .... {

        .....

        ServerAPIProvider.Companion.init(this, "cmnet.kaopuip.com", 6709);

        .....

    }
`
2. 在activity onCreate里面注册广播，监听服务连接广播消息
`
@Override

    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        //监听广播
        registerReceiver(mReceive,new IntentFilter(LocalVpnService.ACTION_VPN_STATE_CHANGED));

    }
`
3. 需要的时候选择节点，进行连接
`
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
`
完整例子参考：https://github.com/Clivebi/bitipclient/tree/master/android/javademo  



## 代码结构说明  
superipApp--app 
一个官方app，具有完备的业务逻辑，包括，注册，登录，找回密码，购买充值等完整业务逻辑  

superipApp--core 
core库的完整实现

javademo，java调用core库切换ip的例子，无业务逻辑功能，主要给第三方集成切换IP功能参考   

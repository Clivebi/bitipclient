
# Windows开发指引  

## coreLib集成   
### 工作模式
Windows端通过bitipconnector.exe在本地监听http请求，集成方通过向bitipconnector.exe发起http请求来来操控各种功能  
***bitipconnector.exe需要以管理员权限运行***
### 集成流程
1.  环境检测，以管理员权限启动bitipconnector.exe，命令行传递 /c ，GetProcessExitCode读取退出码，退出码不是0，需要运行tap-windows.exe 执行tap驱动安装，安装完成后再次检测，只有退出码是0才能进行后续操作  
2.  以管理员权限启动bitipconnector.exe，命令行不带参数或者/p 端口号启动，未指定端口，默认在8978端口  
3.  bitipconnector.exe 启动后会在127.0.0.1:8978上启动http服务器，UI进程通过http协议和bitipconnector.exe进行交互  
4.  用户退出UI进程，UI进程结束前TerminateProcess 结束bitipconnector  
5.  UI进程结束前，请执行一次 ipconfig /renew /s 命令，以防用户无法联网  

## HTTP API 接口 （GET POST同时支持）
### 获取版本号
```
http://127.0.0.1:8978/bitip/version.do
参数：无
返回：返回当前版本号
     
```
### 登录  
调用其他接口前需要先调用此接口  
```
http://127.0.0.1:8978/bitip/login.do?username=手机号码&password=密码
参数：username 用户名，通常为手机号码或者电子邮箱地址
     password 用户密码
返回： 
     返回用户信息，包含到期时间，等json数据
```
### 连接状态查询
```
http://127.0.0.1:8978/bitip/status.do
参数：无
返回：已经连接返回 connected
     未连接返回 not connected
```

### 获取节点列表
```
http://127.0.0.1:8978/bitip/getnodelist.do
参数：无
返回：返回节点对象json数组
```

### 设置节点选取条件
```
http://127.0.0.1:8978/bitip/options.do?ignoreusedip=true&province=x&city=y&carrier=
参数：ignoreusedip  true/false 是否跳过已经使用过的IP，默认为false
     province 指定省份,默认所有省份
     city  指定城市，默认所有城市
     carrier 指定运营商,三个值（电信，移动，联通）其中一个，默认所有运营商
返回：设置成功后的配置json
```

### 连接
```
http://127.0.0.1:8978/bitip/changeip.do
参数：无
返回：失败返回错误信息，成功返回连接的节点信息
```

### 断开  
***UI进程在退出前结束bitipconnector进程之前需要调用此接口，否则可能导致用户无法联网***
```
http://127.0.0.1:8978/bitip/shutdown.do
参数：无
返回：固定返回ok
```

## bitipconnector.exe 源码 https://github.com/Clivebi/bitipclient/tree/master/native-go
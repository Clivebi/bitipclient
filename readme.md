# bitipclient 
## 说明  
本项目为各个平台上bitip的客户端连接库，bitip是一个第三代动态/静态IP服务解决方案，拥有完整的动态/静态IP服务基础组件，bitip包含以下几大组件：  
1. 认证、充值、计费、用户管理组件  
   提供业内高并发的认证解决方案，支持支付宝，微信全自动化充值，支持流量计费，时长计费的计费方式
2. NAS（网络接入服务）管理模块  
   支持管理50W+的NAS服务器，全程监控NAS健康状态，掉线检测自动剔除掉线IP，保证NAS高可用性
3. 高性能用户IP去重服务
   提供快速的IP去重服务，单用户支持100W个IP快速去重
4. 自研bitip协议
   bitip解决方案使用自研的bitip协议，最大限度保护防止IP行为检测，高效全自动化部署方案，一小时上线几万NAS不再是梦，解放运营压力  
   轻量级的通信协议，能够保护数据安全的前提下实现IP快速切换，NAS连接速度业界领先
5. 分布式抗DDOS接入服务
   使用分布式抗DDOS接入服务框架，从此告别DDOS困扰，最大限度的保护业务安全，从此丢掉每个月几十万的高防机器的费用  

## native-go
包含bitip在 windows端，mac端，linux端的客户端代码实现


## windows
包含windows端SDK以及第三方集成文档   
查看文档：https://github.com/Clivebi/bitipclient/tree/master/windows/readme.md

##android
包含Android端完整源码，sdk等
查看文档：https://github.com/Clivebi/bitipclient/blob/master/android/readme.md
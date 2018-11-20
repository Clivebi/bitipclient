BITIP IP切换器深度集成源码包


接口使用，使用总共有个5个步骤，详细调用请参考MainActivity.kt
1、第一步，获取节点服务器列表，并缓存
   使用ServerAPI  public  fun getServerList(user:String,key:String):ServerListResult
2、第二步，遍历服务器列表，可以根据喜好对省份进行过滤，选中节点
   这个步骤中，对上述返回的列表根据自己的喜好，通过城市，或者省份选择一个节点
3、第三步，获取节点的实时服务IP地址和端口
   选择节点之后，获取节点服务器的当前IP和端口，因为一旦节点服务器没人使用，可能会随时变更IP
   使用ServerAPI public  fun getRealTimeAddress(user:String,key:String,name:String):GetIPResult
4、第四步，根据需要判断IP是否被自己的账户使用过
   如果用户使用时，有很多个终端在运行，可能存在一种需求就是，这个IP被一个终端使用了，就不应该再被另外一个终端使用，
   判断IP有没有被自己使用过，使用如下接口
   使用ServerAPI public  fun checkIP(user:String,key:String,ip:String):Boolean
5、第五步，传递参数，启动VPN服务
   BITIP使用私有的VPN协议，而不是众所周知的pptp，l2tp，所以需要启动客户端服务，同时，可以监听服务广播的错误消息，用于判断是否连接成功


其它接口
获取账户信息，包括剩余时间，类型等信息
public  fun getUserInfo(user:String,key:String):UserInfo

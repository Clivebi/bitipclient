native-go 
  
bitip golang 连接内核  
  
通过http接口控制，有如下几个接口  
  
登录，只有先调用了这个接口，其它功能才能使用  
127.0.0.1:8978/bitip/login.do?username=yyy&password=x 
参数:  
username 	 账号名  
password 	 密码  
  
配置sdk  
127.0.0.1:8978/bitip/options.do?ignoreusedip=true&province=x&city=y  
参数:  
ignoreusedip 是否过滤已经使用的ip，true or false ,默认 false，开启此功能，被自己使用过的ip不会再次使用  
province	 只使用这个指定的省份  
city	     只使用这个指定的城市  
  
获取节点列表，返回json格式  
127.0.0.1:8978/bitip/getnodelist.do  
参数：  
无  
  
切换IP  
127.0.0.1:8978/bitip/changeip.do  
参数：  
无  
  
断开连接，程序推出前一定要调用此接口，否则，可能导致用户无法连接网络  
127.0.0.1:8978/bitip/shutdown.do  
参数：  
无  
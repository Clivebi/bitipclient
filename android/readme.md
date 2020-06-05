## Android版SDK集成指南  
Project 的build.gradle添加maven依赖  
allprojects {  
    repositories {  
        google()  
        jcenter()  
        maven{ url "https://raw.githubusercontent.com/Clivebi/bitipclient/master/android/superipApp/core/maven"}  
    }  
}

Module 的build.gradle  添加  
`implementation 'com.bitip:core:1.0.8'`

添加权限  
<uses-permission android:name="android.permission.INTERNET" />


## 代码结构说明  
superipApp--app 
一个官方app，具有完备的业务逻辑，包括，注册，登录，找回密码，购买充值等完整业务逻辑  

superipApp--core 
core库的完整实现

javademo，java调用core库切换ip的例子，无业务逻辑功能，主要给第三方集成切换IP功能参考   

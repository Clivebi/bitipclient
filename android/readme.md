## Android版SDK集成指南  
Project 的build.gradle添加maven依赖  
`allprojects {  
    repositories {  
        google()  
        jcenter()  
        maven{ url "https://raw.githubusercontent.com/Clivebi/bitipclient/master/android/superipApp/core/maven"}  
    }  
}`

Module 的build.gradle  添加  
`implementation 'com.bitip:core:1.0.8'`
package com.kaopuip.app

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.kaopuip.app.common.*
import kotlinx.android.synthetic.main.activity_login.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.startActivity
import org.jetbrains.anko.toast
import org.jetbrains.anko.uiThread

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        translucentActionBar()
        setContentView(R.layout.activity_login)
        login.setOnClickListener {
            val user = text.text.toString()
            val key  = password.text.toString()
            if(user.isEmpty() || key.isEmpty()){
                toast(R.string.title_invalid_input)
                return@setOnClickListener
            }
            val hash = key.md5().hexString()
            doAsync {
                val result = app().mStorage.login(user,hash)
                uiThread {
                    if (result.status !=0){
                        toast(result.msg)
                        return@uiThread
                    }
                    startActivity<MainActivity>()
                    finish()
                }
            }
        }
        register.setOnClickListener {
            startActivity<RegisterActivity>()
        }
        resetpassword.setOnClickListener {
            startActivity<RegisterActivity>(Pair(
                RegisterActivity.KEY_IS_REGISTER,false))
        }
    }
}

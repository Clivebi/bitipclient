package com.kaopuip.app

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import com.kaopuip.app.common.*
import kotlinx.android.synthetic.main.activity_register.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.toast
import org.jetbrains.anko.uiThread

class RegisterActivity : AppCompatActivity(),TextWatcher {
    private var mRegister:Boolean = false
    private var mUser:String = ""
    private var mPin:String = ""
    private var mKey:String = ""
    private enum class State{
        STATE_PHONE,
        STATE_PIN,
        STATE_PASSWORD,
        STATE_PASSWORD2,
        STATE_DONE
    }

    private var mState: State =
        State.STATE_PHONE
        set(value){
            field = value
            updateTextView()
        }

    private fun updateTextView(){
        when(mState){
            State.STATE_PHONE ->{
                text.inputType = InputType.TYPE_CLASS_PHONE
                text.hint = getString(R.string.title_user)
            }
            State.STATE_PIN ->{
                text.inputType = InputType.TYPE_CLASS_NUMBER
                text.hint = getString(R.string.title_pin_code)
                text.text.clear()
            }
            State.STATE_PASSWORD ->{
                text.inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                text.hint = getString(R.string.title_password)
                text.text.clear()
            }
            State.STATE_PASSWORD2 ->{
                text.inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                text.hint = getString(R.string.title_repassword)
                text.text.clear()
            }
            State.STATE_DONE ->{

            }
        }
    }

    override fun afterTextChanged(s: Editable?) {
        if (s == null){
            return
        }
        when(mState){
            State.STATE_PHONE ->{
                if(s.length>=11){
                    button.visibility = View.VISIBLE
                    button.text = getString(R.string.title_send_pin)
                }else{
                    button.visibility = View.INVISIBLE
                }
            }
            State.STATE_PIN ->{
               if(s.isNotEmpty()){
                   button.visibility = View.VISIBLE
                   button.text = getString(R.string.title_next)
               }else{
                   button.visibility = View.INVISIBLE
               }
            }
            State.STATE_PASSWORD ->{
                if(s.isNotEmpty()){
                    button.visibility = View.VISIBLE
                    button.text = getString(R.string.title_next)
                }else{
                    button.visibility = View.INVISIBLE
                }
            }
            State.STATE_PASSWORD2 ->{
                if(s.isNotEmpty()){
                    button.visibility = View.VISIBLE
                    if(mRegister){
                        button.text = getString(R.string.title_register)
                    }else{
                        button.text = getString(R.string.title_reset_password)
                    }
                }else{
                    button.visibility = View.INVISIBLE
                }
            }
            State.STATE_DONE ->{

            }
        }
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        translucentActionBar()
        setContentView(R.layout.activity_register)
        mRegister = intent.getBooleanExtra(KEY_IS_REGISTER,true)
        text.addTextChangedListener(this)
        button.visibility = View.INVISIBLE
        button.setOnClickListener {
            val text = text.text.toString()
            if (text.isEmpty() || text.isBlank()){
                return@setOnClickListener
            }
            val type = if(mRegister){
                "0"
            }else{
                "1"
            }
            when(mState){
                State.STATE_PHONE ->{
                    mUser = text
                    button.visibility = View.INVISIBLE
                    doAsync {
                        val rsp = app().api .sendPin(mUser,type)
                        uiThread {
                            if (rsp.status !=0){
                                toast(rsp.msg)
                                button.text = getString(R.string.title_send_pin)
                                button.visibility = View.VISIBLE
                            }else{
                                mState =
                                    State.STATE_PIN
                                button.text = getString(R.string.title_send_pin)
                                button.visibility = View.VISIBLE
                            }
                        }
                    }
                }
                State.STATE_PIN ->{
                    mPin = text
                    mState =
                        State.STATE_PASSWORD
                    button.visibility = View.VISIBLE
                }
                State.STATE_PASSWORD ->{
                    mKey = text.md5().hexString()
                    mState =
                        State.STATE_PASSWORD2
                    button.visibility = View.VISIBLE
                }
                State.STATE_PASSWORD2 ->{
                    if(mKey != text.md5().hexString()){
                        toast("两次输入密码不一致")
                        mState =
                            State.STATE_PASSWORD
                        return@setOnClickListener
                    }
                    button.visibility = View.INVISIBLE
                    doAsync {
                        val rsp = if (mRegister){
                             app().api.register(mUser,mKey,mPin)
                        }else{
                            app().api.resetPassword(mUser,mKey,mPin)
                        }
                        uiThread {
                            if (rsp.status !=0){
                                toast(rsp.msg)
                                button.visibility = View.VISIBLE
                            }else{
                                mState =
                                    State.STATE_DONE
                                finish()
                            }
                        }
                    }
                }
                State.STATE_DONE ->{

                }
            }
        }
    }

    companion object{
        const val KEY_IS_REGISTER = "isRegister"
    }

}

package com.kaopuip.core
/**
 *Error
 * 错误码
 */
enum class Error(val raw:Int) {
    /*网络错误，通常网络不通*/
    NetworkError(100),
    /*协议错误，本错误是因为服务端返回了错误*/
    ProtocolError(101),
    /*没有调用登录接口*/
    UserNotLogin(102),
    /*根据条件目前没有服务器可以使用*/
    NoServerAvailable(103);
}
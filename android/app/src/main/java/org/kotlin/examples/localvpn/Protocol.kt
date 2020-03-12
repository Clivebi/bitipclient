package org.kotlin.examples.localvpn
import java.io.*
import java.security.MessageDigest;
import java.net.Socket
import java.security.interfaces.RSAPublicKey;
import java.util.*
import javax.crypto.Cipher;
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import android.util.Base64
import java.lang.Exception


const val globalPublicKey = "-----BEGIN PUBLIC KEY-----\n" +
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3v7WLZMVm7FUGaWWWhaR\n"+
        "OGQQy2LVtRKKLLs0xpyS8HsKxp4scoPvE83pRSA/U3zgHXF4p1i6tJHZqZBH8wp6\n"+
        "dQjchiti09BmaZBuLKLu+7UROe9Z9bLy4XOvPCo2rtzG9MbSS3IxgFeUyYHq073n\n"+
        "Fw1xXlr7tk/EGM9AsDwMhW82iwYDHibDnVpYmVQN+XucQr/jh4Z18/1yYjE4tTd4\n"+
        "Ao78Vb5gVHnBgHdZp6Vyu2Y8JmegOis9IpskU7V4OpqfG4BhSchGHnAlDhQSYMCc\n"+
        "NUNoa0XGuzPosZY3BK1hwEq/edTlFN5+wTj1AjULjTadPfcLiCKcqFe2wXFfL4JC\n"+
        "9wIDAQAB\n"+
        "-----END PUBLIC KEY-----"

object EncodeHelper {

    const  val OFFSET_TOTAL_SIZE = 0
    const  val OFFSET_PROTOCOL = 4
    const  val OFFSET_PROTOCOL_SIZE =4
    const  val OFFSET_DATA_TYPE =8
    const  val OFFSET_PAYLOAD =9
    const  val RESERVED_SIZE = OFFSET_PAYLOAD+16
    private const  val MAX_PACKET_SIZE =1024*64
    const  val DATA_TYPE_AUTH:Byte = 1
    const  val DATA_TYPE_TUN:Byte = 2

    fun writeInt(src:ByteArray, offset:Int, value:Int) {
        src[offset+3] = value.toByte()
        src[offset+2] = value.shr(8).toByte()
        src[offset+1] = value.shr(16).toByte()
        src[offset] = value.shr(24).toByte()
    }

    fun readInt(src:ByteArray, offset: Int):Int {
        var value:Int =src[offset+3].toInt() and 0xFF
        value += (src[offset+2].toInt() and 0xFF).shl(8)
        value += (src[offset+1].toInt() and 0xFF).shl(16)
        value += (src[offset+0].toInt() and 0xFF).shl(24)
        return value
    }

    fun randBuffer(len:Int):ByteArray {
        val date = Date()
        val rand = Random(date.time)
        val ret = ByteArray(len)
        for (i in 0 until len) {
            ret[i] = rand.nextInt(255).toByte()
        }
        return ret
    }

    private fun readToLength(input:InputStream,buffer: ByteArray,offset: Int,size: Int){
        var readSize = 0
        while (readSize < size) {
            readSize += input.read(buffer,offset+readSize,size-readSize)
        }
    }

    fun readPacket(input:InputStream,crypt: CryptBlock):RAWPacket?{
        val bsize = ByteArray(4)
        readToLength(input,bsize,0,4)
        val size = readInt(bsize,0)
        if (size <= 0 || size > MAX_PACKET_SIZE) {
            //Log.d(TAG,"packet size "+size.toString()+" not invalid")
            return null
        }
        val buffer  = ByteArray(size+4)
        System.arraycopy(bsize,0,buffer,0,4)
        readToLength(input,buffer,4,size)
        val ret = RAWPacket(buffer,crypt)
        if (ret.isVerified()) {
            return  ret
        }
        return null
    }
}

interface CryptBlock {
    fun encrypt(src: ByteArray,offset:Int): ByteArray
    fun decrypt(src:ByteArray,offset:Int):ByteArray
}


class RC4CryptBlock constructor(key:ByteArray,writeIV:ByteArray,readIV:ByteArray):CryptBlock {
    private  val reader:RC4
    private  val writer:RC4
    init {
        val keyRead = ByteArray(48)
        val keyWrite = ByteArray(48)
        System.arraycopy(key,0,keyRead,0,32)
        System.arraycopy(key,0,keyWrite,0,32)
        System.arraycopy(readIV,0,keyRead,32,16)
        System.arraycopy(writeIV,0,keyWrite,32,16)
        reader = RC4(keyRead)
        writer = RC4(keyWrite)
    }

    override fun encrypt(src: ByteArray,offset:Int): ByteArray {
        writer.XORStream(src,offset)
        return  src
    }
    override fun decrypt(src:ByteArray,offset:Int):ByteArray {
        reader.XORStream(src,offset)
        return  src
    }
}

class RSACryptBlock:CryptBlock{
    private val publicKey:RSAPublicKey
    init{
        var pem:String = globalPublicKey
        pem = pem.replace("-----BEGIN PUBLIC KEY-----","")
        pem = pem.replace("-----END PUBLIC KEY-----","")
        pem = pem.replace("\n","")

        val data = Base64.decode(pem,Base64.DEFAULT)
        val kf = KeyFactory.getInstance("RSA")
        publicKey = kf.generatePublic(X509EncodedKeySpec(data)) as RSAPublicKey
    }
    override fun encrypt(src: ByteArray,offset:Int): ByteArray {
        val chiper = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        chiper.init(Cipher.ENCRYPT_MODE,publicKey)
        val buf = chiper.doFinal(src,offset,src.size-offset)
        val ret  = ByteArray(buf.size+4)
        EncodeHelper.writeInt(ret,EncodeHelper.OFFSET_TOTAL_SIZE,buf.size)
        System.arraycopy(buf,0,ret,4,buf.size)
        return ret
    }
    override fun decrypt(src:ByteArray,offset:Int):ByteArray {
        assert(false)
        return src
    }
}

class AuthPacket constructor(username:ByteArray,token:ByteArray,method:Byte,secret:ByteArray) {
    val buffer:ByteArray
    init{
        val size = username.size+token.size+2+1+secret.size
        var offset = 0
        buffer = ByteArray(size)
        buffer[offset++] = username.size.toByte()
        System.arraycopy(username,0,buffer,offset,username.size)
        offset += username.size
        buffer[offset++] = token.size.toByte()
        System.arraycopy(token,0,buffer,offset,token.size)
        offset += token.size
        buffer[offset++] = method
        System.arraycopy(secret,0,buffer,offset,secret.size)
    }
}

class RAWPacket {
    private  var base:ByteArray
    private  var verified:Boolean

    constructor(payloadSize:Int) {
        base = ByteArray(payloadSize+EncodeHelper.RESERVED_SIZE)
        verified = false
    }

    constructor(buffer:ByteArray,crypt: CryptBlock) {
        verified = false
        val hash  = MessageDigest.getInstance("md5")
        this.base = crypt.decrypt(buffer,EncodeHelper.OFFSET_PROTOCOL)
        if (getPayloadSize() + EncodeHelper.RESERVED_SIZE > base.size) {
            return
        }
        hash.update(base,EncodeHelper.OFFSET_PROTOCOL,getPayloadSize()+5)
        val md5 = hash.digest()
        val offset = EncodeHelper.OFFSET_PROTOCOL+5+getPayloadSize()
        for (i in 0..15) {
            if (base[offset+i] != md5[i]) {
                return
            }
        }
        verified = true
    }

    fun isVerified():Boolean {
        return verified
    }

    fun setDataType(type:Byte) {
        base[EncodeHelper.OFFSET_DATA_TYPE] = type
    }

    fun getPayload():ByteArray {
        val size = getPayloadSize()
        if (size == 0) {
            return ByteArray(0)
        }
        return base.copyOfRange(EncodeHelper.OFFSET_PAYLOAD,EncodeHelper.OFFSET_PAYLOAD+size)
    }

    fun setPayload(data:ByteArray) {
        System.arraycopy(data,0,base,EncodeHelper.OFFSET_PAYLOAD,data.size)
        setPayloadSize(data.size)
    }

    fun setPayload(data:ByteArray,offset: Int,len: Int) {
        System.arraycopy(data,offset,base,EncodeHelper.OFFSET_PAYLOAD,len)
        setPayloadSize(len)
    }

    fun getSendData(crypt:CryptBlock):ByteArray{
        val hash  = MessageDigest.getInstance("md5")
        hash.update(base,EncodeHelper.OFFSET_PROTOCOL,getPayloadSize()+5)
        val md5 = hash.digest()
        val offset = EncodeHelper.OFFSET_PROTOCOL+5+getPayloadSize()
        System.arraycopy(md5,0,base,offset,16)
        var ret = base.copyOfRange(0,offset+16)
        ret = crypt.encrypt(ret,EncodeHelper.OFFSET_PROTOCOL)
        EncodeHelper.writeInt(ret,0,ret.size-4)
        return ret
    }

    private fun getPayloadSize():Int {
        val size = EncodeHelper.readInt(base,EncodeHelper.OFFSET_PROTOCOL_SIZE)
        if (size < 5) {
            return 0
        }
        return size -5
    }

    private  fun setPayloadSize(size:Int){
        EncodeHelper.writeInt(base,EncodeHelper.OFFSET_PROTOCOL_SIZE,size+5)
    }
}

class ProtocolTCPClient(private val cryptBlock: CryptBlock,val socket: Socket)  {

    fun close(){
        socket.close()
    }

    fun readFrame():ByteArray? {
        val rsp = EncodeHelper.readPacket(socket.getInputStream(),cryptBlock) ?: return null
        return rsp.getPayload()
    }

    fun writeFrame(frame:ByteArray,offset: Int,len: Int) {
        val pkt = RAWPacket(len)
        pkt.setDataType(EncodeHelper.DATA_TYPE_TUN)
        pkt.setPayload(frame,offset,len)
        val send = pkt.getSendData(cryptBlock)
        socket.getOutputStream().write(send)
    }

    companion object{
        data class ResultWithError<T>(val result:T,val error:String)
        fun newClientWithError(username:String,
                      token:String,
                      host:String,
                      port:Int):ResultWithError<ProtocolTCPClient?>
        {
            var connected  = false
            try {
                val secret = EncodeHelper.randBuffer(64)
                val cryptBlock = RC4CryptBlock(secret.copyOfRange(0,32),secret.copyOfRange(48,64),secret.copyOfRange(32,48))
                val socket = Socket(host,port)
                if (!socket.isConnected){
                    return ResultWithError(null,"socket connect server failed")
                }
                connected = true
                val auth = AuthPacket(username.toByteArray(),token.toByteArray(),1,secret)
                val crypt = RSACryptBlock()
                val pkt = RAWPacket(auth.buffer.size)
                pkt.setDataType(EncodeHelper.DATA_TYPE_AUTH)
                pkt.setPayload(auth.buffer)
                val send = pkt.getSendData(crypt)
                socket.getOutputStream().write(send)
                EncodeHelper.readPacket(socket.getInputStream(),cryptBlock) ?: return ResultWithError(null,"invalid username/password")
                return ResultWithError(ProtocolTCPClient(cryptBlock,socket),"connect ok")
            }catch (exp:Exception){
                exp.printStackTrace()
                return if(connected){
                    ResultWithError(null,"invalid username/password")
                }else{
                    ResultWithError(null,"network I/O exception")
                }
            }
        }
    }
}

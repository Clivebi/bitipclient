package org.kotlin.examples.localvpn
import java.io.*
import java.security.MessageDigest;
import java.net.Socket
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.*
import javax.crypto.Cipher;
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import android.util.Base64
import android.util.Log


const val gpublickey = "-----BEGIN PUBLIC KEY-----\n" +
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
    const  val MAX_PACKET_SIZE =1024*64
    const  val DATA_TYPE_AUTH:Byte = 1
    const  val DATA_TYPE_TUN:Byte = 2

    fun WriteInt(src:ByteArray,offset:Int,value:Int) {
        src[offset+3] = value.toByte()
        src[offset+2] = value.shr(8).toByte()
        src[offset+1] = value.shr(16).toByte()
        src[offset] = value.shr(24).toByte()
    }

    fun ReadInt(src:ByteArray,offset: Int):Int {
        var value:Int =src[offset+3].toInt() and 0xFF
        value += (src[offset+2].toInt() and 0xFF).shl(8)
        value += (src[offset+1].toInt() and 0xFF).shl(16)
        value += (src[offset+0].toInt() and 0xFF).shl(24)
        return value
    }

    fun randBuffer(len:Int):ByteArray {
        val date = Date()
        val rand = Random(date.time)
        var ret = ByteArray(len)
        for (i in 0 ..len-1) {
            ret[i] = rand.nextInt(255).toByte()
        }
        return ret
    }

    fun readToLength(input:InputStream,buffer: ByteArray,offset: Int,size: Int){
        var readSize = 0
        while (readSize < size) {
            readSize += input.read(buffer,offset+readSize,size-readSize)
        }
    }

    fun readPacket(input:InputStream,crypt: CryptBlock):RAWPacket?{
        val bsize = ByteArray(4)
        readToLength(input,bsize,0,4)
        val size = ReadInt(bsize,0)
        if (size <= 0 || size > MAX_PACKET_SIZE) {
            Log.d(TAG,"packet size "+size.toString()+" not invalid")
            return null
        }
        var buffer  = ByteArray(size+4)
        System.arraycopy(bsize,0,buffer,0,4)
        readToLength(input,buffer,4,size)
        val ret = RAWPacket(buffer,crypt)
        if (ret.Verifyed()) {
            return  ret
        }
        return null
    }
}

interface CryptBlock {
    fun Encrypt(src: ByteArray,offset:Int): ByteArray
    fun Decrypt(src:ByteArray,offset:Int):ByteArray
}


class RC4CryptBlock:CryptBlock {
    private  val reader:RC4
    private  val writer:RC4
    constructor(key:ByteArray,writeIV:ByteArray,readIV:ByteArray) {
        val keyRead = ByteArray(48)
        val keyWrite = ByteArray(48)
        System.arraycopy(key,0,keyRead,0,32)
        System.arraycopy(key,0,keyWrite,0,32)
        System.arraycopy(readIV,0,keyRead,32,16)
        System.arraycopy(writeIV,0,keyWrite,32,16)
        reader = RC4(keyRead)
        writer = RC4(keyWrite)
    }

    override fun Encrypt(src: ByteArray,offset:Int): ByteArray {
        writer.XORStream(src,offset)
        return  src
    }
    override fun Decrypt(src:ByteArray,offset:Int):ByteArray {
        reader.XORStream(src,offset)
        return  src
    }
}

class RSACryptBlock:CryptBlock{
    val publicKey:RSAPublicKey
    constructor(file: InputStream){
        val factory = CertificateFactory.getInstance("X.509")
        val cert = factory.generateCertPath(file) as X509Certificate
        publicKey = cert.publicKey as RSAPublicKey
    }
    constructor(){
        var pem:String = gpublickey
        pem = pem.replace("-----BEGIN PUBLIC KEY-----","")
        pem = pem.replace("-----END PUBLIC KEY-----","")
        pem = pem.replace("\n","")

        var data = Base64.decode(pem,Base64.DEFAULT)
        val kf = KeyFactory.getInstance("RSA")
        publicKey = kf.generatePublic(X509EncodedKeySpec(data)) as RSAPublicKey
    }
    override fun Encrypt(src: ByteArray,offset:Int): ByteArray {
        val chiper = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        chiper.init(Cipher.ENCRYPT_MODE,publicKey)
        val buf = chiper.doFinal(src,offset,src.size-offset)
        val ret  = ByteArray(buf.size+4)
        EncodeHelper.WriteInt(ret,EncodeHelper.OFFSET_TOTAL_SIZE,buf.size)
        System.arraycopy(buf,0,ret,4,buf.size)
        return ret
    }
    override fun Decrypt(src:ByteArray,offset:Int):ByteArray {
        assert(false)
        return src
    }
}

class AuthPacket {
    public  val buffer:ByteArray
    constructor(username:ByteArray,token:ByteArray,method:Byte,secrect:ByteArray) {
        val size = username.size+token.size+2+1+secrect.size
        var offset = 0
        buffer = ByteArray(size)
        buffer[offset++] = username.size.toByte()
        System.arraycopy(username,0,buffer,offset,username.size)
        offset += username.size
        buffer[offset++] = token.size.toByte()
        System.arraycopy(token,0,buffer,offset,token.size)
        offset += token.size
        buffer[offset++] = method
        System.arraycopy(secrect,0,buffer,offset,secrect.size)
    }
}

class RAWPacket {
    private  var base:ByteArray
    private  var isVerifyed:Boolean

    constructor(payloadSize:Int) {
        base = ByteArray(payloadSize+EncodeHelper.RESERVED_SIZE)
        isVerifyed = false
    }

    constructor(buffer:ByteArray,crypt: CryptBlock) {
        isVerifyed = false
        val hash  = MessageDigest.getInstance("md5")
        this.base = crypt.Decrypt(buffer,EncodeHelper.OFFSET_PROTOCOL)
        if (getPayloadSize() + EncodeHelper.RESERVED_SIZE > base.size) {
            Log.d(TAG,"payloadsize+Reserved_size > base.size")
            return
        }
        hash.update(base,EncodeHelper.OFFSET_PROTOCOL,getPayloadSize()+5)
        val md5 = hash.digest()
        val offset = EncodeHelper.OFFSET_PROTOCOL+5+getPayloadSize()
        for (i in 0..15) {
            if (base[offset+i] != md5[i]) {
                Log.d(TAG,"check packet hash failed"+md5.toString())
                return
            }
        }
        isVerifyed = true
    }

    public fun Verifyed():Boolean {
        return isVerifyed
    }

    public fun setTotalSize(size:Int) {
        EncodeHelper.WriteInt(base,EncodeHelper.OFFSET_TOTAL_SIZE,size)
    }

    public fun getTotalSize():Int {
        return EncodeHelper.ReadInt(base,EncodeHelper.OFFSET_TOTAL_SIZE)
    }

    public fun setDataType(type:Byte) {
        base[EncodeHelper.OFFSET_DATA_TYPE] = type
    }

    public fun getDataType():Byte {
        return base[EncodeHelper.OFFSET_DATA_TYPE]
    }

    public fun getPayload():ByteArray {
        val size = getPayloadSize()
        if (size == 0) {
            return ByteArray(0)
        }
        return base.copyOfRange(EncodeHelper.OFFSET_PAYLOAD,EncodeHelper.OFFSET_PAYLOAD+size)
    }

    public fun setPayload(data:ByteArray) {
        System.arraycopy(data,0,base,EncodeHelper.OFFSET_PAYLOAD,data.size)
        setPayloadSize(data.size)
    }

    public fun setPayload(data:ByteArray,offset: Int,len: Int) {
        System.arraycopy(data,offset,base,EncodeHelper.OFFSET_PAYLOAD,len)
        setPayloadSize(len)
    }

    public fun getSendData(crypt:CryptBlock):ByteArray{
        val hash  = MessageDigest.getInstance("md5")
        hash.update(base,EncodeHelper.OFFSET_PROTOCOL,getPayloadSize()+5)
        val md5 = hash.digest()
        val offset = EncodeHelper.OFFSET_PROTOCOL+5+getPayloadSize()
        System.arraycopy(md5,0,base,offset,16)
        var ret = base.copyOfRange(0,offset+16)
        ret = crypt.Encrypt(ret,EncodeHelper.OFFSET_PROTOCOL)
        EncodeHelper.WriteInt(ret,0,ret.size-4)
        return ret
    }

    public  fun getPayloadSize():Int {
        val size = EncodeHelper.ReadInt(base,EncodeHelper.OFFSET_PROTOCOL_SIZE)
        if (size < 5) {
            return 0
        }
        return size -5
    }

    private  fun setPayloadSize(size:Int){
        EncodeHelper.WriteInt(base,EncodeHelper.OFFSET_PROTOCOL_SIZE,size+5)
    }
}

class ProtocolTcpClient {
    private  var cryptBlock:CryptBlock? =null
    var socket:Socket? = null
    var isConnected:Boolean = false
    var errorMsg:String = "NetworkFailed"



    constructor(username:String,token:String,host:String,port:Int) {
        try {
            val secrect = EncodeHelper.randBuffer(64)
            cryptBlock = RC4CryptBlock(secrect.copyOfRange(0,32),secrect.copyOfRange(48,64),secrect.copyOfRange(32,48))
            socket = Socket(host,port)
            if (!socket!!.isConnected) {
                return
            }
            errorMsg = "AuthFailed"
            val auth = AuthPacket(username.toByteArray(),token.toByteArray(),1,secrect)
            val crypt = RSACryptBlock()
            val pkt = RAWPacket(auth.buffer.size)
            pkt.setDataType(EncodeHelper.DATA_TYPE_AUTH)
            pkt.setPayload(auth.buffer)
            val send = pkt.getSendData(crypt)
            socket!!.getOutputStream().write(send)
            val rsp = EncodeHelper.readPacket(socket!!.getInputStream(),cryptBlock!!)
            if (rsp == null) {
                return
            }
            errorMsg = "Connect OK"
            isConnected = true
        }catch (e:IOException) {
        }
    }

    public  fun Close(){
        if (socket!= null) {
            socket!!.close()
        }
    }

    public  fun readFrame():ByteArray? {
        val rsp = EncodeHelper.readPacket(socket!!.getInputStream(),cryptBlock!!)
        if (rsp == null) {
            return null
        }
        return rsp.getPayload()
    }

    public  fun writeFrame(frame:ByteArray,offset: Int,len: Int) {
        val pkt = RAWPacket(len)
        pkt.setDataType(EncodeHelper.DATA_TYPE_TUN)
        pkt.setPayload(frame,offset,len)
        val send = pkt.getSendData(cryptBlock!!)
        val size = EncodeHelper.ReadInt(send,0);
        socket!!.getOutputStream().write(send)
    }

}

package bitipclient

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"crypto/rsa"
	"github.com/songgao/water"
	"log"
	mrand "math/rand"
	"net"
	"strings"
	"time"
)

type VPNConnector struct {
	conf    *ClientConfig
	rsaKey  *rsa.PublicKey
	ifc     *water.Interface
	Chiper  CryptBlock
	special *OSSepcialSetup
	con     net.Conn
}

func CheckEvn() error {
	return nil
}

func FixNetwork() {

}

func NewConnector(conf *ClientConfig, rsaKey *rsa.PublicKey) (Connector, error) {
	cn := &VPNConnector{
		conf:   conf,
		rsaKey: rsaKey,
	}
	return cn, nil
}

func (o *VPNConnector) close() {
	if o.special != nil {
		o.special.Rollback()
		o.special = nil
	}
	if o.con != nil {
		o.con.Close()
		o.con = nil
	}
	if o.ifc != nil {
		o.ifc.Close()
		o.ifc = nil
	}
}

func (o *VPNConnector) randKey() []byte {
	mrand.Seed(time.Now().Unix())
	ret := make([]byte, 64)
	for i := 0; i < 64; i++ {
		ret[i] = byte(mrand.Intn(255))
	}
	return ret
}

func (o *VPNConnector) setupTunDevice(address string) error {
	arrays := strings.Split(address, ":")
	Exclude := make([]string, len(o.conf.Exclude))
	copy(Exclude, o.conf.Exclude)
	Exclude = append(Exclude, arrays[0]+"/32")
	if o.ifc == nil {
		ifce, err := water.New(getWaterConfig())
		if err != nil {
			return err
		}
		o.ifc = ifce
	}
	o.special = NewOSSepcialSetup()
	return o.special.Setup(o.ifc.Name(), "10.1.0.10/24", "10.1.0.1", 1500, o.conf.DNS, Exclude)
}

func (o *VPNConnector) IsWorking() bool {
	return o.special != nil
}

func (o *VPNConnector) Connect(address string) error {
	o.close()
	dial := net.Dialer{
		Timeout: time.Second * 5,
	}
	con, err := dial.Dial("tcp", address)
	if err != nil {
		return err
	}
	req := &startup_request{
		secrect: o.randKey(),
		user:    o.conf.UserName,
		authkey: o.conf.Password,
		method:  CRYPT_METHOD_RC4,
	}
	o.Chiper, _ = NewRC4CryptBlock(req.secrect[0:32], req.secrect[48:], req.secrect[32:48])
	wbuf := bytes.NewBuffer(nil)
	req.WriteToIO(wbuf)
	pro := &protocol_layer{
		data:    wbuf.Bytes(),
		command: command_startup,
	}
	wbuf = bytes.NewBuffer(nil)
	pro.WriteToIO(wbuf)
	buf, err := rsa.EncryptPKCS1v15(rand.Reader, o.rsaKey, wbuf.Bytes())
	if err != nil {
		o.close()
		return err
	}
	net := &network_layer{
		data: buf,
	}
	err = net.WriteToIO(con)
	if err != nil {
		o.close()
		return err
	}
	rspNet := &network_layer{}
	err = rspNet.ReadFromIO(con)
	if err != nil {
		o.close()
		return err
	}
	buf, err = o.Chiper.Decrypt(rspNet.data)
	if err != nil {
		o.close()
		return err
	}
	err = o.setupTunDevice(address)
	if err != nil {
		o.close()
		return err
	}
	go o.copyLocalToRemote(con)
	go o.copyRemoteToLocal(con)
	o.con = con
	return nil
}

func (o *VPNConnector) Close() {
	o.close()
}

func (o *VPNConnector) copyLocalToRemote(con net.Conn) {
	packet := make([]byte, 3000)
	for {
		n, err := o.ifc.Read(packet)
		if err != nil {
			log.Println("read from tun faield:" + err.Error())
			break
		}
		pro := &protocol_layer{
			data:    packet[:n],
			command: command_tun_data,
		}
		wbuf := bytes.NewBuffer(nil)
		pro.WriteToIO(wbuf)
		buf := o.Chiper.Encrypt(wbuf.Bytes())
		net := &network_layer{
			data: buf,
		}
		wbuf = bytes.NewBuffer(nil)
		net.WriteToIO(wbuf)
		_, err = con.Write(wbuf.Bytes())
		if err != nil {
			log.Println("write to remote failed:" + err.Error())
			break
		}
	}
}

func (o *VPNConnector) copyRemoteToLocal(con net.Conn) {
	bio := bufio.NewReader(con)
	defer con.Close()
	for {
		net := &network_layer{}
		err := net.ReadFromIO(bio)
		if err != nil {
			log.Println("read from remote faield:" + err.Error())
			break
		}
		buf, err := o.Chiper.Decrypt(net.data)
		if err != nil {
			log.Println("decrypt network data faield:" + err.Error())
			break
		}
		pro := &protocol_layer{}
		err = pro.ReadFromBuffer(buf)
		if err != nil {
			log.Println("decode protocol layer failed:" + err.Error())
			break
		}
		if pro.command != command_tun_data {
			log.Println("invalid command type")
			break
		}
		o.ifc.Write(pro.data)
	}
}

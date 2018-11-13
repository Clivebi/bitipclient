package bitipclient

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"github.com/songgao/water"
	"log"
	mrand "math/rand"
	"net"
	"os"
	"os/signal"
	"strings"
	"time"
)

type ClientConfig struct {
	ServerAddress string   `json:"server"`
	UserName      string   `json:"username"`
	Password      string   `json:"password"`
	DNS           []string `json:"dns"`
	Exclude       []string `json:"exclude"`
}

type VPNConnector struct {
	conf    *ClientConfig
	rasKey  *rsa.PublicKey
	ifc     *water.Interface
	Link    net.Conn
	Chiper  CryptBlock
	special *OSSepcialSetup
}

func (o *VPNConnector) close() {
	if o.Link != nil {
		o.Link.Close()
	}
	if o.special != nil {
		o.special.Rollback()
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

func (o *VPNConnector) setupTun() error {
	arrays := strings.Split(o.conf.ServerAddress, ":")
	o.conf.Exclude = append(o.conf.Exclude, arrays[0]+"/32")
	ifce, err := water.New(getWaterConfig())
	if err != nil {
		return err
	}
	o.ifc = ifce
	o.special = NewOSSepcialSetup()
	return o.special.Setup(o.ifc.Name(), "10.1.0.10/24", "10.1.0.1", 1500, o.conf.DNS, o.conf.Exclude)
}

func (o *VPNConnector) internalConnect() (net.Conn, error) {
	//if o.conf.UDP {
	//	return reliable.DialWithOptions(o.conf.ServerAddress, time.Second*5)
	//}
	return net.Dial("tcp4", o.conf.ServerAddress)
}

func (o *VPNConnector) connect() error {
	log.Println("start connect server " + o.conf.ServerAddress)
	con, err := o.internalConnect()
	if err != nil {
		return err
	}
	log.Println("start auth use username=" + o.conf.UserName)
	o.Link = con
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
	buf, err := rsa.EncryptPKCS1v15(rand.Reader, o.rasKey, wbuf.Bytes())
	if err != nil {
		return err
	}
	net := &network_layer{
		data: buf,
	}
	err = net.WriteToIO(o.Link)
	if err != nil {
		return err
	}
	rspNet := &network_layer{}
	err = rspNet.ReadFromIO(o.Link)
	if err != nil {
		return err
	}
	buf, err = o.Chiper.Decrypt(rspNet.data)
	log.Println("connect server ok")
	return nil
}

func (o *VPNConnector) copyLocalToRemote(ex chan error) {
	packet := make([]byte, 3000)
	for {
		n, err := o.ifc.Read(packet)
		if err != nil {
			ex <- err
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
		_, err = o.Link.Write(wbuf.Bytes())
		if err != nil {
			ex <- errors.New("write packet to tcp  " + err.Error())
			break
		}
	}
}

func (o *VPNConnector) copyRemoteToLocal(ex chan error) {
	var err error
	var buf []byte
	bio := bufio.NewReader(o.Link)
	for {
		net := &network_layer{}
		err = net.ReadFromIO(bio)
		if err != nil {
			err = errors.New("read packet from tcp failed " + err.Error())
			break
		}
		buf, err = o.Chiper.Decrypt(net.data)
		if err != nil {
			err = errors.New("decrypt tcp data failed " + err.Error())
			break
		}
		pro := &protocol_layer{}
		err = pro.ReadFromBuffer(buf)
		if err != nil {
			err = errors.New("decode protocol layer failed " + err.Error())
			break
		}
		if pro.command != command_tun_data {
			err = errors.New("invalid command type " + err.Error())
			break
		}
		_, err = o.ifc.Write(pro.data)
		if err != nil {
			err = errors.New("write packet to tun failed " + err.Error())
			break
		}
	}
	ex <- err
}

func RunClient() error {
	buf, err := LoadLocalConfigFile("public.pem")
	if err != nil {
		return err
	}
	block, _ := pem.Decode(buf)
	if block == nil || block.Type != "PUBLIC KEY" {
		return errors.New("failed to decode PEM block containing public key " + err.Error())
	}

	puk, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return errors.New("failed to parse public key" + err.Error())
	}
	buf, err = LoadLocalConfigFile("client.conf")
	if err != nil {
		return err
	}
	conf := &ClientConfig{}
	err = json.Unmarshal(buf, conf)
	if err != nil {
		return err
	}

	client := &VPNConnector{
		conf:   conf,
		rasKey: puk.(*rsa.PublicKey),
	}
	defer client.close()
	err = client.connect()
	if err != nil {
		log.Println("connect failed :" + err.Error())
		return err
	}
	err = client.setupTun()
	if err != nil {
		log.Println("setup tun failed " + err.Error())
		return err
	}
	ex := make(chan error)
	go func() {
		c := make(chan os.Signal, 1)
		signal.Notify(c)
		<-c
		ex <- errors.New("user exit the app")
	}()
	log.Println("start packets transcation...")
	go client.copyLocalToRemote(ex)
	go client.copyRemoteToLocal(ex)
	err = <-ex
	log.Println("exit with error " + err.Error())
	return err
}

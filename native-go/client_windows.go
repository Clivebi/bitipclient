package bitipclient

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"crypto/rsa"
	"errors"
	"github.com/songgao/water"
	"log"
	mrand "math/rand"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

type VPNConnector struct {
	conf           *ClientConfig
	rsaKey         *rsa.PublicKey
	ifc            *water.Interface
	con            net.Conn
	rollbackentrys []string
	tunInput       chan []byte
	tunOutput      chan []byte
	wg             *sync.WaitGroup
	working        bool
}

func CheckEvn() error {
	water, err := water.New(getWaterConfig())
	if err != nil {
		return err
	}
	log.Println(water.Name())
	water.Close()
	return nil
}

func NewConnector(conf *ClientConfig, rsaKey *rsa.PublicKey) (Connector, error) {
	w, err := water.New(getWaterConfig())
	if err != nil {
		return nil, err
	}
	cn := &VPNConnector{
		conf:           conf,
		rsaKey:         rsaKey,
		ifc:            w,
		rollbackentrys: []string{},
		tunInput:       make(chan []byte, 1024),
		tunOutput:      make(chan []byte, 1024),
		wg:             &sync.WaitGroup{},
		con:            nil,
		working:        false,
	}
	err = cn.init()
	if err != nil {
		cn.Close()
		return nil, err
	}
	go cn.readTUNLoop()
	go cn.writeTUNLoop()
	return cn, err
}

func (o *VPNConnector) IsWorking() bool {
	return o.working
}

func (o *VPNConnector) Close() {
	o.close()
	o.rollback()
	if o.ifc != nil {
		o.ifc.Close()
	}
	close(o.tunOutput)
	close(o.tunInput)
	AddIncludeTableEntry("0.0.0.0/0")
}

func (o *VPNConnector) close() {
	if o.con != nil {
		o.con.Close()
		o.con = nil
		o.wg.Wait()
	}
	o.working = false
}

func (o *VPNConnector) init() error {
	err := o.setDeviceNetworkParameters(o.ifc.Name(), "10.1.0.10/24", "10.1.0.1", strconv.Itoa(1500))
	if err != nil {
		return err
	}
	err = o.setDeviceNameServer(o.ifc.Name(), o.conf.DNS)
	if err != nil {
		return err
	}
	list := []string{}
	for _, v := range o.conf.DNS {
		list = append(list, v+"/32")
	}
	o.addIncludeAddress(list)
	o.addExcudeAddress(o.conf.Exclude)
	gateWay := net.ParseIP("10.1.0.1")
	initRuouteHelp(o.ifc.Name(), ipToInt(gateWay))
	return nil
}

func (o *VPNConnector) rollback() {
	for _, v := range o.rollbackentrys {
		n, err := DeleteTableEntry(v)
		if err != nil || n != 0 {
			//log.Println("DeleteTableEntry :", n, " ", err)
		}
	}
	o.rollbackentrys = []string{}
}

func (o *VPNConnector) addExcudeAddress(exclude []string) {
	for _, v := range exclude {
		n, err := AddExcludeTableEntry(v)
		if err != nil || n != 0 {
			//log.Println("AddExcludeTableEntry :", n, " ", err)
		}
		o.rollbackentrys = append(o.rollbackentrys, v)
	}
}

func (o *VPNConnector) addIncludeAddress(include []string) {
	for _, v := range include {
		n, err := AddIncludeTableEntry(v)
		if err != nil || n != 0 {
			//log.Println("AddIncludeTableEntry :", n, " ", err)
		}
		o.rollbackentrys = append(o.rollbackentrys, v)
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

func (o *VPNConnector) readTUNLoop() {
	defer func() {
		AddExcludeTableEntry("0.0.0.0/0")
		o.working = false
	}()

	for {
		packet := make([]byte, 1600)
		n, err := o.ifc.Read(packet)
		if err != nil {
			log.Println("read from tun faield:" + err.Error())
			break
		}
		o.tunInput <- packet[:n]
	}
}

func (o *VPNConnector) writeTUNLoop() {
	defer func() {
		AddExcludeTableEntry("0.0.0.0/0")
		o.working = false
	}()
	for {
		packet := <-o.tunOutput
		_, err := o.ifc.Write(packet)
		if err != nil {
			log.Println("write tun faield:" + err.Error())
			break
		}
	}
}

func (o *VPNConnector) Connect(address string) error {
	o.close()
	o.addExcudeAddress([]string{strings.Split(address, ":")[0] + "/32"})
	dial := net.Dialer{
		Timeout: time.Second * 5,
	}
	con, err := dial.Dial("tcp", address)
	if err != nil {
		o.close()
		return err
	}
	req := &startup_request{
		secrect: o.randKey(),
		user:    o.conf.UserName,
		authkey: o.conf.Password,
		method:  CRYPT_METHOD_RC4,
	}
	chiper, _ := NewRC4CryptBlock(req.secrect[0:32], req.secrect[48:], req.secrect[32:48])
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
	buf, err = chiper.Decrypt(rspNet.data)
	if err != nil {
		o.Close()
		return err
	}
	ech := make(chan error, 2)
	o.wg.Add(2)
	go o.moveLocaltoRemote(con, chiper, ech)
	go o.moveRemoteToLocal(con, chiper, ech)
	AddIncludeTableEntry("0.0.0.0/0")
	o.con = con
	return nil
}

func (o *VPNConnector) moveLocaltoRemote(con net.Conn, chiper CryptBlock, ech chan error) {
	defer func() {
		o.wg.Done()
	}()
	for {
		select {
		case p := <-o.tunInput:
			if p == nil {
				continue
			}
			pro := &protocol_layer{
				data:    p,
				command: command_tun_data,
			}
			wbuf := bytes.NewBuffer(nil)
			pro.WriteToIO(wbuf)
			buf := chiper.Encrypt(wbuf.Bytes())
			net := &network_layer{
				data: buf,
			}
			wbuf = bytes.NewBuffer(nil)
			net.WriteToIO(wbuf)
			_, err := con.Write(wbuf.Bytes())
			if err != nil {
				log.Println("write to remote failed:" + err.Error())
				return
			}
		case e := <-ech:
			log.Println("network thread exit with error:", e)
			return
		}
	}
}

func (o *VPNConnector) moveRemoteToLocal(con net.Conn, chiper CryptBlock, ech chan error) {
	var err error
	bio := bufio.NewReader(con)
	defer func() {
		con.Close()
		o.wg.Done()
		close(ech)
		AddExcludeTableEntry("0.0.0.0/0")
		o.working = false
	}()
	for {
		net := &network_layer{}
		err = net.ReadFromIO(bio)
		if err != nil {
			log.Println("read from remote faield:" + err.Error())
			break
		}
		buf, err := chiper.Decrypt(net.data)
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
			err = errors.New("invalid command type")
			break
		}
		o.tunOutput <- pro.data
	}
	ech <- err
}

package bitipclient

import (
	"crypto/md5"
	"crypto/rsa"
	"crypto/x509"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io/ioutil"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	FILE_P_CODER   = "1.json"
	FILE_S_CODER   = "2.json"
	FILE_NODE_LIST = "3.json"
	CMNET_HOST     = "cmnet.kaopuip.com"
	URL_P_CODER    = "http://%v:6709/getpcoder.do"
	URL_S_CODER    = "http://%v:6709/getcoder.do"
	URL_LOGIN      = "http://%v:6709/login.do"
	URL_IP_LIST    = "http://%v:6709/getips2.do"
	URL_IP         = "http://%v:6709/getip2.do"
	URL_IP_CHECK   = "http://%v:6709/checkip.do"
	defaultPort    = 8808
)

type CommandResponse struct {
	Status  uint32 `json:"status"`
	Message string `json:"message"`
	Body    string `json:"body"`
}

type VPNNode struct {
	Name     string `json:"name"`
	Address  string `json:"address"`
	Port     int    `json:"port"`
	Province string `json:"province"`
	City     string `json:"city"`
	Carrier  string `json:"carrier"`
}

func (o VPNNode) String() string {
	buf, err := json.Marshal(o)
	if err != nil {
		return err.Error()
	}
	return string(buf)
}

type SDKConfig struct {
	UserName     string `json:"_"`
	Password     string `json:"_"`
	Province     string `json:"province"`
	City         string `json:"city"`
	IgnoreUsedIP bool   `json:"ignoreusedip"`
	Carrier      string `json:"carrier"`
}

type OutAddr struct {
	addr string
}

func (o OutAddr) Network() string {
	return "tcp"
}
func (o OutAddr) String() string {
	return o.addr + ":0"
}

type SDKServer struct {
	conf           *SDKConfig
	nodes          []*VPNNode
	index          uint
	con            Connector
	pcoder         map[int]string
	scoder         map[int]string
	path           string
	coderExp       time.Time
	listExp        time.Time
	lastActiveNode *VPNNode
	cmnetip        []string
	localip        *net.TCPAddr
}

func NewServer() *SDKServer {
	s := &SDKServer{
		conf:           nil,
		nodes:          []*VPNNode{},
		index:          uint(time.Now().UnixNano()) % 10000,
		con:            nil,
		pcoder:         make(map[int]string),
		scoder:         make(map[int]string),
		coderExp:       time.Unix(0, 0),
		listExp:        time.Unix(0, 0),
		lastActiveNode: nil,
		cmnetip:        []string{},
	}
	home, _ := Home()
	s.path = filepath.Join(home, "kaopuIP")
	os.MkdirAll(s.path, os.ModePerm)
	log.Println("datapath:", s.path)
	s.init()
	return s
}

func (s *SDKServer) isHttpError(err error) bool {
	return strings.Contains(err.Error(), "http response")
}

//使用默认的接口出去，以便绕过自己的代理
func (s *SDKServer) DoHttpRequestWithTimeout(url string, timeout time.Duration) ([]byte, error) {
	client := http.Client{
		Timeout: timeout,
	}
	rsp, err := client.Get(url)
	if err != nil {
		log.Println(err)
		return nil, err
	}
	defer rsp.Body.Close()
	if rsp.StatusCode != http.StatusOK {
		return nil, errors.New("http response error :" + rsp.Status)
	}
	return ioutil.ReadAll(rsp.Body)
}

func (s *SDKServer) getFilePath(name string) string {
	return filepath.Join(s.path, name)
}

func (s *SDKServer) init() {
	buf, err := ioutil.ReadFile(s.getFilePath(FILE_P_CODER))
	if err == nil && len(buf) > 0 {
		s.pcoder = s.BuildStringDecoder(buf)
	}
	buf, err = ioutil.ReadFile(s.getFilePath(FILE_S_CODER))
	if err == nil && len(buf) > 0 {
		s.scoder = s.BuildStringDecoder(buf)
	}
	buf, err = ioutil.ReadFile(s.getFilePath(FILE_NODE_LIST))
	if err == nil && len(buf) > 0 {
		list := s.decodeNodeList(buf, s.pcoder, s.scoder)
		if len(list) > 0 {
			s.nodes = list
		}
	}
}

func (s *SDKServer) DoCommandRequest(url string) (string, error) {
	buf, err := s.DoHttpRequestWithTimeout(url, time.Second*15)
	if err != nil {
		return "", err
	}
	cmd := &CommandResponse{}
	err = json.Unmarshal(buf, &cmd)
	if err != nil {
		return "", err
	}
	if cmd.Status != 0 {
		return "", errors.New(cmd.Message)
	}
	return cmd.Body, nil
}

func (s *SDKServer) BuildStringDecoder(buf []byte) map[int]string {
	src := map[string]interface{}{}
	json.Unmarshal(buf, &src)
	ret := make(map[int]string)
	for k, v := range src {
		if rv, ok := v.(float64); ok {
			ret[int(rv)] = k
		}
	}
	return ret
}

func (s *SDKServer) decodeNode(src []byte, pd map[int]string, bd map[int]string) *VPNNode {
	node := &VPNNode{}
	node.Address = net.IP(src[:4]).String()
	node.Name = hex.EncodeToString(src[4:7])
	node.Province = pd[int(uint32(src[7])&0x3f)]
	node.Carrier = bd[int(uint32(src[7])>>6)]
	node.City = bd[int(uint32(binary.BigEndian.Uint16(src[8:])))]
	node.Port = defaultPort
	return node
}

func (s *SDKServer) decodeNodeList(src []byte, pd map[int]string, bd map[int]string) []*VPNNode {
	if len(src)%10 != 0 {
		log.Println("buffer not align to 10")
		return []*VPNNode{}
	}
	offset := 0
	ret := make([]*VPNNode, len(src)/10)
	i := 0
	for {
		if offset == len(src) {
			break
		}
		item := s.decodeNode(src[offset:], pd, bd)
		ret[i] = item
		i++
		offset += 10
	}
	return ret[:i]
}

func (s *SDKServer) DoUpdateCoder() error {
	if !time.Now().After(s.coderExp) {
		return nil
	}
	buf, err := s.DoHttpRequestWithTimeout(fmt.Sprintf(URL_P_CODER, CMNET_HOST), time.Second*15)
	if err != nil {
		log.Println(err)
		return err
	}
	ioutil.WriteFile(s.getFilePath(FILE_P_CODER), buf, os.ModePerm)
	s.pcoder = s.BuildStringDecoder(buf)

	buf, err = s.DoHttpRequestWithTimeout(fmt.Sprintf(URL_S_CODER, CMNET_HOST), time.Second*15)
	if err != nil {
		log.Println(err)
		return err
	}
	ioutil.WriteFile(s.getFilePath(FILE_S_CODER), buf, os.ModePerm)
	s.scoder = s.BuildStringDecoder(buf)
	s.coderExp = time.Now().Add(time.Minute * 20)
	return nil
}

func (s *SDKServer) DoUpdateNodeList() error {
	s.DoUpdateCoder()
	if len(s.pcoder) == 0 {
		return errors.New("Network error")
	}
	if !time.Now().After(s.listExp) {
		return nil
	}
	url := fmt.Sprintf(URL_IP_LIST, CMNET_HOST) + "?email=" + s.conf.UserName + "&pass=" + s.conf.Password
	buf, err := s.DoHttpRequestWithTimeout(url, time.Second*15)
	if err != nil && !s.isHttpError(err) {
		log.Println("update node list with network error ,try last active node")
		if s.lastActiveNode != nil {
			url = fmt.Sprintf(URL_IP_LIST, s.lastActiveNode.Address) + "?email=" + s.conf.UserName + "&pass=" + s.conf.Password
			buf, err = s.DoHttpRequestWithTimeout(url, time.Second*15)
		}
		log.Println("update node list with network error ,try cached node list")
		if err != nil && !s.isHttpError(err) {
			for _, v := range s.nodes {
				url = fmt.Sprintf(URL_IP_LIST, v.Address) + "?email=" + s.conf.UserName + "&pass=" + s.conf.Password
				buf, err = s.DoHttpRequestWithTimeout(url, time.Second*15)
				if err == nil || s.isHttpError(err) {
					s.lastActiveNode = v
					break
				}
			}
		}
	}
	if err != nil || buf == nil || len(buf) == 0 {
		return errors.New("Get Node List Error")
	}
	list := s.decodeNodeList(buf, s.pcoder, s.scoder)
	if len(list) == 0 {
		return errors.New("Node List empty")
	}
	ioutil.WriteFile(s.getFilePath(FILE_NODE_LIST), buf, os.ModePerm)
	s.nodes = list
	s.listExp = time.Now().Add(time.Minute * 8)
	return nil
}

func (s *SDKServer) GetRealTimeAddress(name string) (string, error) {
	url := fmt.Sprintf(URL_IP, CMNET_HOST) + "?email=" + s.conf.UserName + "&pass=" + s.conf.Password + "&name=" + name
	buf, err := s.DoHttpRequestWithTimeout(url, time.Second*15)
	if err != nil && !s.isHttpError(err) {
		log.Println("get real time address with network error ,try last active node")
		if s.lastActiveNode != nil {
			url = fmt.Sprintf(URL_IP, s.lastActiveNode.Address) + "?email=" + s.conf.UserName + "&pass=" + s.conf.Password
			buf, err = s.DoHttpRequestWithTimeout(url, time.Second*15)
		}
		log.Println("get real time address with network error ,try cached node list")
		if err != nil && !s.isHttpError(err) {
			for _, v := range s.nodes {
				url = fmt.Sprintf(URL_IP, v.Address) + "?email=" + s.conf.UserName + "&pass=" + s.conf.Password
				buf, err = s.DoHttpRequestWithTimeout(url, time.Second*15)
				if err == nil || s.isHttpError(err) {
					s.lastActiveNode = v
					break
				}
			}
		}
	}
	if err != nil || buf == nil || len(buf) == 0 {
		return "", errors.New("network error")
	}
	ls := strings.Split(string(buf), ":")
	if len(ls) != 2 {
		return "", errors.New("invalid response")
	}
	return ls[0], nil
}

func (s *SDKServer) CheckIPIsUsed(Address string) (bool, error) {
	url := fmt.Sprintf(URL_IP_CHECK, CMNET_HOST) + "user=" + s.conf.UserName + "&pass=" + s.conf.Password + "&ip=" + Address
	buf, err := s.DoHttpRequestWithTimeout(url, time.Second*15)
	if err != nil {
		return false, err
	}
	return string(buf) == "1", nil
}

func (s *SDKServer) getLocalDefaultAddress() string {
	con, err := net.Dial("tcp", "wwww.baidu.com:80")
	if err != nil {
		return "0.0.0.0"
	}
	defer con.Close()
	addr := (con.(*net.TCPConn)).LocalAddr().String()
	ls := strings.Split(addr, ":")
	return ls[0]
}

// /bitip/login.do
func (s *SDKServer) HandleLogin(r *http.Request) ([]byte, error) {
	conf := &SDKConfig{}
	conf.UserName = r.FormValue("username")
	conf.Password = r.FormValue("password")
	conf.Province = ""
	conf.City = ""
	conf.IgnoreUsedIP = false
	conf.Province = r.FormValue("province")
	conf.City = r.FormValue("city")
	conf.Carrier = r.FormValue("carrier")

	localAddr := s.getLocalDefaultAddress()
	s.localip, _ = net.ResolveTCPAddr("tcp", localAddr+":0")

	_, err := hex.DecodeString(conf.Password)
	if err != nil || len(conf.Password) != 32 {
		sh := md5.Sum([]byte(conf.Password))
		conf.Password = hex.EncodeToString(sh[:])
	}

	conf.IgnoreUsedIP = (r.FormValue("ignoreusedip") == "true")
	url := fmt.Sprintf(URL_LOGIN, CMNET_HOST) + "?" + "email=" + conf.UserName + "&pass=" + conf.Password
	text, err := s.DoCommandRequest(url)
	if err != nil {
		return nil, err
	}
	s.cmnetip, _ = net.LookupHost(CMNET_HOST)
	s.conf = conf
	return []byte(text), err
}

// /bitip/options.do
func (s *SDKServer) HandleOption(r *http.Request) ([]byte, error) {
	if s.conf == nil {
		return nil, errors.New("user not login")
	}
	s.conf.Province = r.FormValue("province")
	s.conf.City = r.FormValue("city")
	s.conf.Carrier = r.FormValue("carrier")
	s.conf.IgnoreUsedIP = (r.FormValue("ignoreusedip") == "true")
	return json.Marshal(s.conf)
}

// /bitip/getnodelist.do
func (s *SDKServer) HandleGetNodeList(r *http.Request) ([]byte, error) {
	if s.conf == nil {
		return nil, errors.New("user not login")
	}
	err := s.DoUpdateNodeList()
	if err != nil {
		return nil, err
	}
	if len(s.nodes) == 0 {
		return nil, errors.New("node unavaliable")
	}
	return json.Marshal(s.nodes)
}

// /bitip/changeip.do
func (s *SDKServer) HandleChangeIP(r *http.Request) ([]byte, error) {
	var node *VPNNode = nil
	if s.conf == nil {
		return nil, errors.New("user not login")
	}
	err := s.DoUpdateNodeList()
	if err != nil {
		return nil, err
	}
	if len(s.nodes) == 0 {
		return nil, errors.New("node unavaliable")
	}
	count := 0
	for {
		s.index++
		count++
		i := int(s.index % uint(len(s.nodes)))
		if count > len(s.nodes) {
			break
		}
		if len(s.conf.Province) != 0 {
			if s.conf.Province != s.nodes[i].Province {
				log.Println("Provice flt")
				continue
			}
		}
		if len(s.conf.City) != 0 {
			if s.conf.City != s.nodes[i].City {
				log.Println("city flt")
				continue
			}
		}
		if len(s.conf.Carrier) != 0 {
			if s.conf.Carrier != s.nodes[i].Carrier {
				log.Println("carrier flt")
			}
		}
		addr, err := s.GetRealTimeAddress(s.nodes[i].Name)
		if err != nil {
			log.Println("get realtime address failed:", err)
			continue
		}
		if s.conf.IgnoreUsedIP {
			used, err := s.CheckIPIsUsed(addr)
			if err != nil || used {
				log.Println("check ip failed:", err, used)
				continue
			}
		}
		node = s.nodes[i]
		node.Address = addr
		break
	}
	if node == nil {
		return nil, errors.New("select node failed")
	}
	err = s.connect(node)
	if err != nil {
		return nil, err
	}
	return json.Marshal(node)
}

func (s *SDKServer) connect(n *VPNNode) error {
	addr := n.Address + ":" + strconv.Itoa(n.Port)
	if s.con == nil {
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
		exp := make([]string, len(s.cmnetip))
		for i, v := range s.cmnetip {
			exp[i] = v + "/32"
		}
		conf := &ClientConfig{
			UserName: s.conf.UserName,
			Password: s.conf.Password,
			DNS:      []string{"223.5.5.5", "223.6.6.6"},
			Exclude:  exp,
		}
		s.con, err = NewConnector(conf, puk.(*rsa.PublicKey))
		if err != nil {
			s.con = nil
			return err
		}
	}
	return s.con.Connect(addr)
}

func (o *SDKServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	//  /bitip/login.do
	//  /bitip/getnodelist.do
	//  /bitip/changeip.do
	var buf []byte
	var err error
	r.ParseForm()
	if r.URL.EscapedPath() == "/bitip/login.do" {
		buf, err = o.HandleLogin(r)
	}
	if r.URL.EscapedPath() == "/bitip/getnodelist.do" {
		buf, err = o.HandleGetNodeList(r)
	}
	if r.URL.EscapedPath() == "/bitip/changeip.do" {
		buf, err = o.HandleChangeIP(r)
	}
	if r.URL.EscapedPath() == "/bitip/options.do" {
		buf, err = o.HandleOption(r)
	}
	if r.URL.EscapedPath() == "/bitip/status.do" {
		err = nil
		if o.con != nil && o.con.IsWorking() {
			buf = []byte("connected")
		} else {
			buf = []byte("not connected")
		}
	}
	if r.URL.EscapedPath() == "/bitip/shutdown.do" {
		if o.con != nil {
			o.con.Close()
			o.con = nil
		}
		err = nil
		buf = []byte("ok")
	}
	if err != nil {
		log.Println(err)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Write(buf)
	return
}

func (o *SDKServer) ServerHttp(bind string) {
	server := http.Server{}
	server.Addr = bind
	server.Handler = o
	server.ReadTimeout = 6 * time.Minute
	server.WriteTimeout = 6 * time.Minute
	log.Println("work on: " + bind)
	server.ListenAndServe()
}

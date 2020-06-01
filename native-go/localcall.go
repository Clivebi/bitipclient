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
	"math/rand"
	"net"
	"net/http"
	"net/url"
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
	libVersion     = "1.0.6"
)

type NotifyClose interface {
	UserClosed()
}

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
	notify         NotifyClose
}

func NewServer(notify NotifyClose) *SDKServer {
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
		notify:         notify,
	}
	home, _ := Home()
	s.path = filepath.Join(home, "kaopuIP")
	os.MkdirAll(s.path, os.ModePerm)
	s.init()
	return s
}

func (s *SDKServer) isHttpError(err error) bool {
	return strings.Contains(err.Error(), "http response")
}

//DoHttpRequestWithTimeout 带超时http请求
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
		text := ""
		if rsp.Body != nil {
			buf, _ := ioutil.ReadAll(rsp.Body)
			text = string(buf)
		} else {
			text = rsp.Status
		}
		return nil, errors.New("http response error :" + text)
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

func (s *SDKServer) randOrderNodeList(count int) []*VPNNode {
	r := rand.New(rand.NewSource(time.Now().UnixNano()))
	if len(s.nodes) == 0 {
		return []*VPNNode{}
	}
	ret := make([]*VPNNode, count)
	for i := 0; i < count; i++ {
		ret[i] = s.nodes[r.Intn(len(s.nodes))]
	}
	return ret
}

func (s *SDKServer) DoHttpRequestWithAntiDDOS(formatURL string, param *url.Values, timeout time.Duration, maxcount int) ([]byte, error) {
	url := fmt.Sprintf(formatURL, CMNET_HOST) + "?" + param.Encode()
	buf, err := s.DoHttpRequestWithTimeout(url, timeout)
	if err == nil {
		return buf, err
	}
	if s.isHttpError(err) {
		return buf, err
	}
	if nil != s.CheckNetwork() {
		s.FixNetwork()
		buf, err := s.DoHttpRequestWithTimeout(url, timeout)
		if err == nil {
			return buf, err
		}
		if s.isHttpError(err) {
			return buf, err
		}
		if nil != s.CheckNetwork() {
			return nil, errors.New("network not avaliable")
		}
	}
	if s.lastActiveNode != nil {
		log.Println("network error <", formatURL, "> try last active node")
		url := fmt.Sprintf(formatURL, s.lastActiveNode.Address) + "?" + param.Encode()
		buf, err := s.DoHttpRequestWithTimeout(url, timeout)
		if err == nil {
			return buf, err
		}
		if s.isHttpError(err) {
			return buf, err
		}
	}
	log.Println("network error <", formatURL, "> try cached node list")
	checkList := s.randOrderNodeList(maxcount)

	for _, v := range checkList {
		url := fmt.Sprintf(formatURL, v.Address) + "?" + param.Encode()
		buf, err := s.DoHttpRequestWithTimeout(url, timeout)
		if err == nil {
			return buf, err
		}
		if s.isHttpError(err) {
			return buf, err
		}
	}
	return nil, err
}

func (s *SDKServer) DoUpdateNodeList() error {
	s.DoUpdateCoder()
	if len(s.pcoder) == 0 {
		return errors.New("Network error")
	}
	if !time.Now().After(s.listExp) {
		return nil
	}
	param := &url.Values{}
	param.Add("email", s.conf.UserName)
	param.Add("pass", s.conf.Password)
	buf, err := s.DoHttpRequestWithAntiDDOS(URL_IP_LIST, param, time.Second*10, 10000)
	if err != nil || buf == nil || len(buf) == 0 {
		if err != nil {
			return err
		}
		return errors.New("Node List empty")
	}
	list := s.decodeNodeList(buf, s.pcoder, s.scoder)
	if len(list) == 0 {
		return errors.New("Node List empty")
	}
	ioutil.WriteFile(s.getFilePath(FILE_NODE_LIST), buf, os.ModePerm)
	s.nodes = list
	s.listExp = time.Now().Add(time.Minute * 10)
	return nil
}

func (s *SDKServer) GetRealTimeAddress(name string) (string, error) {
	param := &url.Values{}
	param.Add("email", s.conf.UserName)
	param.Add("pass", s.conf.Password)
	param.Add("name", name)
	buf, err := s.DoHttpRequestWithAntiDDOS(URL_IP, param, time.Second*3, 10000)
	if err != nil || buf == nil || len(buf) == 0 {
		if err != nil {
			log.Println("get realtime address error :", err)
			return "", err
		}
		return "", errors.New("network error")
	}
	ls := strings.Split(string(buf), ":")
	if len(ls) != 2 {
		return "", errors.New("invalid response")
	}
	return ls[0], nil
}

func (s *SDKServer) CheckIPIsUsed(Address string) (bool, error) {
	param := &url.Values{}
	param.Add("email", s.conf.UserName)
	param.Add("pass", s.conf.Password)
	param.Add("ip", Address)
	buf, err := s.DoHttpRequestWithAntiDDOS(URL_IP_CHECK, param, time.Second*3, 100)
	if err != nil || buf == nil || len(buf) == 0 {
		if err != nil {
			log.Println("check ip error :", err)
			return false, err
		}
		return false, errors.New("network error")
	}
	return string(buf) == "true", nil
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

	_, err := hex.DecodeString(conf.Password)
	if err != nil || len(conf.Password) != 32 {
		sh := md5.Sum([]byte(conf.Password))
		conf.Password = hex.EncodeToString(sh[:])
	}

	conf.IgnoreUsedIP = (r.FormValue("ignoreusedip") == "true")

	param := &url.Values{}
	param.Add("email", conf.UserName)
	param.Add("pass", conf.Password)

	buf, err := s.DoHttpRequestWithAntiDDOS(URL_LOGIN, param, time.Second*3, 1000)
	if err != nil {
		return nil, err
	}
	cmd := &CommandResponse{}
	err = json.Unmarshal(buf, &cmd)
	if err != nil {
		return nil, err
	}
	if cmd.Status != 0 {
		return nil, errors.New(cmd.Message)
	}
	s.cmnetip, _ = net.LookupHost(CMNET_HOST)
	s.conf = conf
	return []byte(cmd.Body), err
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

func (s *SDKServer) ChangeIPWithNasName(name string) ([]byte, error) {
	var node *VPNNode = nil
	for _, v := range s.nodes {
		if v.Name == name {
			node = v
			break
		}
	}
	if node == nil {
		return nil, errors.New("node not found")
	}
	addr, err := s.GetRealTimeAddress(name)
	if err != nil {
		log.Println("get realtime address failed:", err)
		return nil, errors.New("get realtime address failed")
	}
	if s.conf.IgnoreUsedIP {
		used, err := s.CheckIPIsUsed(addr)
		if err != nil {
			log.Println("check ip failed:", err, used)
			return nil, errors.New("check ip failed")
		}
		if used {
			log.Println("check ip failed:", err, used)
			return nil, errors.New("node is used")
		}
	}
	node.Address = addr
	err = s.connect(node)
	if err != nil {
		return nil, err
	}
	return json.Marshal(node)
}

func (s *SDKServer) CheckNetwork() error {
	_, err := s.DoHttpRequestWithTimeout("http://www.baidu.com", time.Second*3)
	return err
}

func (s *SDKServer) FixNetwork() {
	if s.con != nil {
		s.con.Close()
		s.con = nil
	}
	FixNetwork()
}

// /bitip/changeip.do
func (s *SDKServer) HandleChangeIP(r *http.Request) ([]byte, error) {
	var node *VPNNode = nil
	name := r.FormValue("name")
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
	if len(name) != 0 {
		return s.ChangeIPWithNasName(name)
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
	if r.URL.EscapedPath() == "/bitip/version.do" {
		buf = []byte(libVersion)
		err = nil
	}
	if r.URL.EscapedPath() == "/bitip/shutdown.do" {
		if o.con != nil {
			o.con.Close()
			o.con = nil
		}
		err = nil
		buf = []byte("ok")
		if o.notify != nil {
			o.notify.UserClosed()
		}
	}
	if err != nil {
		log.Println(r.URL.EscapedPath(), err)
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

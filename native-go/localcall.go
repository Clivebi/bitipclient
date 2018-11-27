package bitipclient

import (
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io/ioutil"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const (
	URL_LOGIN    = "http://120.39.243.128:1815/login.do"
	URL_IP_LIST  = "http://120.39.243.128:1815/getips.do"
	URL_IP       = "http://120.39.243.128:1815/getip.do"
	URL_IP_CHECK = "http://120.39.243.128:7000/checkip.do"
)

type CommandResponse struct {
	Status  uint32 `json:"status"`
	Message string `json:"message"`
	Body    string `json:"body"`
}

type CheckResponse struct {
	IsUsed bool `json:"isused"`
}

type VPNNode struct {
	Name     string `json:"name"`
	Address  string `json:"address"`
	Port     int    `json:"port"`
	Province string `json:"province"`
	City     string `json:"city"`
}

func (o VPNNode) String() string {
	buf, err := json.Marshal(o)
	if err != nil {
		return err.Error()
	}
	return string(buf)
}

type SDKConfig struct {
	UserName     string
	Password     string
	Province     string
	City         string
	IgnoreUsedIP bool
}

type SDKServer struct {
	conf   *SDKConfig
	nodes  []*VPNNode
	expire time.Time
	index  int
	con    *VPNConnector
}

func NewServer() *SDKServer {
	s := &SDKServer{
		conf:   nil,
		nodes:  []*VPNNode{},
		expire: time.Now(),
		index:  0,
		con:    nil,
	}
	return s
}

func (s *SDKServer) DoHttpRequest(url string) ([]byte, error) {
	resp, err := http.Get(url)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, errors.New("Internal error:" + resp.Status)
	}
	defer resp.Body.Close()
	ret, err := ioutil.ReadAll(resp.Body)
	return ret, err
}

func (s *SDKServer) DoCommandRequest(url string) (string, error) {
	buf, err := s.DoHttpRequest(url)
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

func (s *SDKServer) BuildStringDecoder(src map[string]interface{}) map[int]string {
	ret := make(map[int]string)
	for k, v := range src {
		if rv, ok := v.(float64); ok {
			ret[int(rv)] = k
		}
	}
	return ret
}

func (s *SDKServer) ParseNode(text string) error {
	root := []map[string]interface{}{}
	err := json.Unmarshal([]byte(text), &root)
	if err != nil {
		return err
	}
	if len(root) < 2 {
		return errors.New("invalid node list text")
	}
	decoder := s.BuildStringDecoder(root[0])
	ret := []*VPNNode{}
	for i := 1; i < len(root); i++ {
		item := root[i]
		if len(item) == 0 {
			break
		}
		n := &VPNNode{}
		if v, ok := item["a"]; ok {
			if rv, ok := v.(string); ok {
				n.Name = rv
			}
		}
		if v, ok := item["b"]; ok {
			if rv, ok := v.(float64); ok {
				n.Port = int(rv)
			}
		}
		if v, ok := item["d"]; ok {
			if rv, ok := v.(float64); ok {
				n.Province = decoder[int(rv)]
			}
		}
		if v, ok := item["e"]; ok {
			if rv, ok := v.(float64); ok {
				n.City = decoder[int(rv)]
			}
		}
		if v, ok := item["g"]; ok {
			if rv, ok := v.(string); ok {
				n.Address = rv
			}
		}
		if len(n.Name) == 0 || len(n.Province) == 0 || len(n.City) == 0 {
			continue
		}
		ret = append(ret, n)
	}
	s.nodes = ret
	s.expire = time.Now().Add(time.Minute * 10)
	return nil
}

// /bitip/login.do
func (s *SDKServer) HandleLogin(r *http.Request) ([]byte, error) {
	conf := &SDKConfig{}
	conf.UserName = r.FormValue("username")
	conf.Password = r.FormValue("password")
	conf.Province = r.FormValue("province")
	conf.City = r.FormValue("city")
	conf.IgnoreUsedIP = (r.FormValue("ignoreusedip") == "true")
	url := URL_LOGIN + "?" + "email=" + conf.UserName + "&pass=" + conf.Password
	text, err := s.DoCommandRequest(url)
	if err != nil {
		return nil, err
	}
	s.conf = conf
	return []byte(text), err
}

func (s *SDKServer) updateVPNNode() error {
	if time.Now().After(s.expire) {
		url := URL_IP_LIST + "?" + "email=" + s.conf.UserName + "&pass=" + s.conf.Password
		text, err := s.DoCommandRequest(url)
		if err != nil {
			return err
		}
		err = s.ParseNode(text)
		if err != nil {
			return err
		}
		return err
	}
	return nil
}

// /bitip/getnodelist.do
func (s *SDKServer) HandleGetNodeList(r *http.Request) ([]byte, error) {
	if s.conf == nil {
		return nil, errors.New("user not login")
	}
	err := s.updateVPNNode()
	if err != nil {
		return nil, err
	}
	if len(s.nodes) == 0 {
		return nil, errors.New("node unavaliable")
	}
	return json.Marshal(s.nodes)
}

func (s *SDKServer) GetRealTimeAddress(name string) (string, int, error) {
	url := URL_IP + "?" + "email=" + s.conf.UserName + "&pass=" + s.conf.Password + "&name=" + name
	text, err := s.DoCommandRequest(url)
	if err != nil {
		return "", 0, err
	}
	ls := strings.Split(text, ":")
	if len(ls) != 2 {
		return "", 0, errors.New("invalid response")
	}
	port, err := strconv.Atoi(ls[1])
	if err != nil {
		return "", 0, err
	}
	return ls[0], port, nil
}

func (s *SDKServer) CheckIPIsUsed(Address string) (bool, error) {
	url := URL_IP_CHECK + "?" + "user=" + s.conf.UserName + "&pass=" + s.conf.Password + "&ip=" + Address
	buf, err := s.DoHttpRequest(url)
	if err != nil {
		return false, err
	}
	rsp := &CheckResponse{}
	err = json.Unmarshal(buf, rsp)
	if err != nil {
		return false, err
	}
	return rsp.IsUsed, nil
}

// /bitip/changeip.do
func (s *SDKServer) HandleChangeIP(r *http.Request) ([]byte, error) {
	var node *VPNNode = nil
	if s.conf == nil {
		return nil, errors.New("user not login")
	}
	err := s.updateVPNNode()
	if err != nil {
		return nil, err
	}
	if len(s.nodes) == 0 {
		return nil, errors.New("node unavaliable")
	}
	for i := s.index % len(s.nodes); i < len(s.nodes); i++ {
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
		addr, port, err := s.GetRealTimeAddress(s.nodes[i].Name)
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
		node.Port = port
		s.index += i
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
		conf := &ClientConfig{
			UserName: s.conf.UserName,
			Password: s.conf.Password,
			DNS:      []string{"223.5.5.5", "223.6.6.6"},
			Exclude:  []string{"120.39.243.128/32"},
		}
		s.con = &VPNConnector{
			conf:   conf,
			rasKey: puk.(*rsa.PublicKey),
		}
	}
	return s.con.connectVPN(addr)
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

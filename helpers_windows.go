package bitipclient

import (
	"bufio"
	"bytes"
	"github.com/songgao/water"
	"log"
	"net"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

func getWaterConfig() water.Config {
	return water.Config{
		DeviceType: water.TUN,
		PlatformSpecificParams: water.PlatformSpecificParams{
			ComponentID: "tap0901",
			Network:     "10.1.0.10/24",
		},
	}
}

type routeEntry struct {
	address    string
	viaAddress string
}

type OSSepcialSetup struct {
	rollbackentrys       []routeEntry
	defaultGateWay       string
	defaultGateWayDevice string
}

func NewOSSepcialSetup() *OSSepcialSetup {
	o := &OSSepcialSetup{
		rollbackentrys: []routeEntry{},
	}
	return o
}
func (o *OSSepcialSetup) addRollbackRouteEntry(addr, gateway string) {
	o.rollbackentrys = append(o.rollbackentrys, routeEntry{
		address:    addr,
		viaAddress: gateway,
	})
}

func (o *OSSepcialSetup) exec(command string, args []string) error {
	cmd := exec.Command(command, args...)
	log.Println(command, args)
	e := cmd.Run()
	if e != nil {
		log.Println("Command failed: ", e)
	}
	return e
}

func (o *OSSepcialSetup) Rollback() {
	for _, v := range o.rollbackentrys {
		o.delRoute(v.address)
	}
	o.setDefaultGateway(o.defaultGateWay)
}

func (o *OSSepcialSetup) Setup(ifName, address, gateway string, mtu int, dns []string, exclude []string) error {
	err := o.setDeviceNetworkParameters(ifName, address, gateway, strconv.Itoa(mtu))
	if err != nil {
		return err
	}
	time.Sleep(time.Second * 1)
	err = o.setDeviceNameServer(ifName, dns)
	if err != nil {
		return err
	}
	time.Sleep(time.Second * 1)
	o.defaultGateWay, o.defaultGateWayDevice, err = o.getDefaultGateway()
	if err != nil {
		return err
	}
	time.Sleep(time.Second * 1)
	err = o.setDefaultGateway(gateway)
	if err != nil {
		return err
	}
	for _, v := range dns {
		addr := v + "/32"
		o.addRoute(addr, gateway)
		o.addRollbackRouteEntry(addr, gateway)
		time.Sleep(time.Second * 1)
	}
	for _, v := range exclude {
		o.addRoute(v, o.defaultGateWay)
		o.addRollbackRouteEntry(v, o.defaultGateWay)
		time.Sleep(time.Second * 1)
	}
	err = o.setDefaultGateway(gateway)
	return nil
}

func (o *OSSepcialSetup) setDeviceNetworkParameters(iName string, address string, gatway string, mtu string) error {
	ip, mask, err := net.ParseCIDR(address)
	if err != nil {
		return err
	}
	//netsh interface ip set address name=”本地连接” source=static addr=192.168.0.3 mask=255.255.255.0 gateway=192.168.0.1 /32
	args := []string{"interface", "ipv4", "set", "address", "name=" + iName, "source=static",
		"addr=" + ip.String(),
		"mask=" + net.IP(mask.Mask).String(),
		"gateway=" + gatway,
	}
	err = o.exec("netsh", args)
	if err != nil {
		return err
	}
	//netsh interface ipv4 set subinterface "本地连接" mtu=1480 store=persistent
	args = []string{"interface", "ipv4", "set", "subinterface", iName, "mtu=" + mtu, "store=persistent"}
	return o.exec("netsh", args)
}

func (o *OSSepcialSetup) setDeviceNameServer(iName string, dns []string) error {
	//netsh interface ip set dns name=”本地连接” source=static addr=218.85.157.99 register=primary
	args := []string{"interface", "ipv4", "set", "dns", "name=" + iName,
		"source=static",
		"addr=" + dns[0],
		"register=primary",
	}
	err := o.exec("netsh", args)
	if err != nil {
		return err
	}
	if len(dns) < 2 {
		return nil
	}
	args = []string{"interface", "ipv4", "add", "dns", "name=" + iName,
		"address=" + dns[1],
		"index=2",
	}
	o.exec("netsh", args)
	return nil
}

func (o *OSSepcialSetup) getDefaultGateway() (string, string, error) {
	cmd := exec.Command("route", "print", "-p", "0.0.0.0")
	buf, err := cmd.Output()
	if err != nil {
		return "", "", err
	}
	r := bytes.NewReader(buf)
	br := bufio.NewReader(r)
	for {
		l, err := br.ReadSlice('\n')
		if err != nil {
			break
		}
		if !strings.Contains(string(l), "0.0.0.0") {
			continue
		}
		l = bytes.Replace(l, []byte{'\r'}, []byte{}, -1)
		l = bytes.Replace(l, []byte{'\n'}, []byte{}, -1)
		ls := bytes.Split(l, []byte{32})
		xl := [][]byte{}
		for _, v := range ls {
			if bytes.Contains(v, []byte{32}) {
				continue
			}
			if len(v) == 0 {
				continue
			}
			xl = append(xl, v)
		}
		if len(xl) < 5 || string(xl[0]) != "0.0.0.0" {
			continue
		}
		return string(xl[2]), string(xl[3]), nil
	}
	return "", "", nil
}

func (o *OSSepcialSetup) addRoute(addr, viaAddr string) error {
	ip, mask, err := net.ParseCIDR(addr)
	if err != nil {
		return err
	}
	//route add 192.168.100.0 mask 255.255.255.248 192.168.1.1 metric 3 if 2
	args := []string{"add", ip.String(), "mask", net.IP(mask.Mask).String(), viaAddr}
	return o.exec("route", args)
}

func (o *OSSepcialSetup) delRoute(addr string) error {
	//route delete 192.168.100.0
	ip, _, err := net.ParseCIDR(addr)
	if err != nil {
		return err
	}
	args := []string{"delete", ip.String()}
	return o.exec("route", args)
}

func (o *OSSepcialSetup) setDefaultGateway(gateWay string) error {
	args := []string{"delete", "0.0.0.0"}
	o.exec("route", args)
	//route add 192.168.100.0 mask 255.255.255.248 192.168.1.1 metric 3 if 2
	args = []string{"add", "0.0.0.0", "mask", "0.0.0.0", gateWay}
	return o.exec("route", args)
}

package bitipclient

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"github.com/songgao/water"
	"log"
	"net"
	"os/exec"
	"regexp"
	"strings"
)

func getWaterConfig() water.Config {
	return water.Config{
		DeviceType: water.TUN,
	}
}

type netHardware struct {
	Port    string
	Device  string
	Address string
}

func getPairValue(src string) (key string, value string) {
	src = strings.Replace(src, "\r", "", -1)
	src = strings.Replace(src, "\n", "", -1)
	index := strings.Index(src, ": ")
	if index+2 > len(src) {
		return "", ""
	}
	key = src[:index]
	value = src[index+2:]
	return key, value
}

func getNetworkHardware() []netHardware {
	//networksetup -listallhardwareports
	ret := []netHardware{}
	cmd := exec.Command("networksetup", "-listallhardwareports")
	buf, err := cmd.Output()
	if err != nil {
		return ret
	}

	r := bytes.NewReader(buf)
	br := bufio.NewReader(r)
	item := netHardware{}
	for {
		l, err := br.ReadSlice('\n')
		if err != nil {
			break
		}
		if !strings.Contains(string(l), ":") {
			continue
		}
		key, value := getPairValue(string(l))
		if len(key) == 0 || len(value) == 0 {
			continue
		}
		switch key {
		case "Hardware Port":
			item.Port = value
		case "Device":
			item.Device = value
		case "Ethernet Address":
			item.Address = value
			ret = append(ret, item)
		}

	}
	return ret
}

type routeEntry struct {
	address    string
	viaAddress string
	ifName     string
}

type OSSepcialSetup struct {
	rollbackentrys       []routeEntry
	rollbackDNS          []string
	ifName               string
	defaultGateWay       string
	defaultGateWayDevice string
	mtu                  int
}

func NewOSSepcialSetup() *OSSepcialSetup {
	o := &OSSepcialSetup{
		rollbackentrys: []routeEntry{},
		rollbackDNS:    []string{},
	}
	return o
}
func (o *OSSepcialSetup) addRollbackRouteEntry(ifName, addr, gateway string) {
	o.rollbackentrys = append(o.rollbackentrys, routeEntry{
		address:    addr,
		ifName:     ifName,
		viaAddress: gateway,
	})
}

func (o *OSSepcialSetup) exec(command string, args []string) error {
	cmd := exec.Command(command, args...)
	//log.Println("exec "+command+": ", args)
	e := cmd.Run()
	if e != nil {
		log.Println("Command failed: ", e)
	}
	return e
}

func (o *OSSepcialSetup) Rollback() {
	o.setNameServer(o.defaultGateWayDevice, o.rollbackDNS)
	o.setInterfaceStatus(o.ifName, false, o.mtu)
	for _, v := range o.rollbackentrys {
		err := o.delRoute(v.ifName, v.address, v.viaAddress)
		if err != nil {
			log.Printf("Error: Route delete %s (%s on %s) - %s\n", v.address, v.viaAddress, v.ifName, err.Error())
		}
	}
	o.exec("route", []string{"add", "default", o.defaultGateWay})
}

func (o *OSSepcialSetup) Setup(ifName, address, gateway string, mtu int, dns []string, exclude []string) error {
	o.ifName = ifName
	o.mtu = mtu
	err := o.setDeviceIP(ifName, address)
	if err != nil {
		return err
	}
	o.defaultGateWay, o.defaultGateWayDevice, err = o.getDefaultGateway()
	if err != nil {
		return err
	}
	err = o.setInterfaceStatus(ifName, true, mtu)
	if err != nil {
		return err
	}
	for _, v := range dns {
		addr := v + "/32"
		o.addRoute(ifName, addr, gateway)
		o.addRollbackRouteEntry(ifName, addr, gateway)
	}
	for _, v := range exclude {
		o.addRoute(o.defaultGateWayDevice, v, o.defaultGateWay)
		o.addRollbackRouteEntry(o.defaultGateWayDevice, v, o.defaultGateWay)
	}
	o.rollbackDNS = o.getNameServer(o.defaultGateWayDevice)
	err = o.setNameServer(o.defaultGateWayDevice, dns)
	if err != nil {
		return err
	}
	return o.setDefaultGateway(ifName, gateway)
}

func (o *OSSepcialSetup) deviceNameToNetworkservice(dev string) string {
	ls := getNetworkHardware()
	for _, v := range ls {
		if v.Device == dev {
			return v.Port
		}
	}
	return ""
}

func (o *OSSepcialSetup) getNameServer(deviceName string) []string {
	ret := []string{}
	service := o.deviceNameToNetworkservice(deviceName)
	if len(service) == 0 {
		log.Println("get networkservice name for " + deviceName + " failed")
		return ret
	}
	cmd := exec.Command("networksetup", "-getdnsservers", service)
	buf, err := cmd.Output()
	if err != nil {
		log.Println("get dns failed" + err.Error())
		return ret
	}
	if strings.Contains(string(buf), "There") {
		return ret
	}

	r := bytes.NewReader(buf)
	br := bufio.NewReader(r)
	for {
		l, err := br.ReadSlice('\n')
		if err != nil {
			break
		}
		dns := string(l)
		if len(dns) == 0 {
			continue
		}
		ret = append(ret, dns)
	}
	return ret
}

func (o *OSSepcialSetup) setNameServer(deviceName string, dns []string) error {
	service := o.deviceNameToNetworkservice(deviceName)
	if len(service) == 0 {
		return errors.New("get networkservice name for " + deviceName + " failed")
	}
	args := []string{"-setdnsservers", service}
	if len(dns) == 0 {
		args = append(args, "Empty")
	} else {
		args = append(args, dns...)
	}
	_, err := exec.Command("networksetup", args...).Output()
	return err
}

func (o *OSSepcialSetup) setInterfaceStatus(iName string, up bool, mtu int) error {
	statusString := "down"
	if up {
		statusString = "up"
	}

	//TODO: Support setting the QLEN
	sargs := fmt.Sprintf("%s %s mtu %d", iName, statusString, mtu)
	return o.exec("ifconfig", strings.Split(sargs, " "))
}

func (o *OSSepcialSetup) setDeviceIP(iName string, address string) error {
	ip, mask, err := net.ParseCIDR(address)
	if err != nil {
		log.Println("ParseCIDR failed" + err.Error())
		return err
	}
	log.Printf("%s %s %s\n", ip.String(), mask.IP.String(), mask.Mask.String())
	sargs := fmt.Sprintf("set %s MANUAL %s 0x%s", iName, ip.String(), mask.Mask)
	return o.exec("ipconfig", strings.Split(sargs, " "))
}

func (o *OSSepcialSetup) setDefaultGateway(iName, gateWay string) error {
	sargs := fmt.Sprintf("-n change default -interface %s", iName)
	args := strings.Split(sargs, " ")
	return o.exec("route", args)
}

func (o *OSSepcialSetup) addRoute(iName string, addr, viaAddr string) error {
	// route -n add   -net 10.0.0.0/8      10.13.31.1
	sargs := fmt.Sprintf("-n add -net %s %s -ifscope %s", addr, viaAddr, iName)
	args := strings.Split(sargs, " ")
	return o.exec("route", args)
}

func (o *OSSepcialSetup) delRoute(iName string, addr, viaAddr string) error {
	sargs := fmt.Sprintf("-n delete -net %s %s -ifscope %s", addr, viaAddr, iName)
	args := strings.Split(sargs, " ")
	return o.exec("route", args)
}

var parseRouteGetRegex = regexp.MustCompile(`(?m)^\W*([^\:]+):\W(.*)$`)

func (o *OSSepcialSetup) getDefaultGateway() (gw, dev string, err error) {
	cmd := exec.Command("route", "-n", "get", "default")
	output, e := cmd.Output()
	if e != nil {
		return "", "", e
	}

	matches := parseRouteGetRegex.FindAllSubmatch(output, -1)
	defaultRouteInfo := map[string]string{}
	for _, match := range matches {
		defaultRouteInfo[string(match[1])] = string(match[2])
	}

	_, gatewayExists := defaultRouteInfo["gateway"]
	_, interfaceExists := defaultRouteInfo["interface"]
	if !gatewayExists || !interfaceExists {
		return "", "", errors.New("internal error: could not read gateway or interface")
	}

	return defaultRouteInfo["gateway"], defaultRouteInfo["interface"], nil
}

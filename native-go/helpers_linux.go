package bitipclient

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"github.com/songgao/water"
	"log"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
)

func getWaterConfig() water.Config {
	return water.Config{
		DeviceType: water.TUN,
	}
}

type routeEntry struct {
	address    string
	viaAddress string
	ifName     string
}

type OSSepcialSetup struct {
	rollbackentrys       []routeEntry
	ifName               string
	defaultGateWay       string
	defaultGateWayDevice string
	mtu                  int
}

func NewOSSepcialSetup() *OSSepcialSetup {
	o := &OSSepcialSetup{
		rollbackentrys: []routeEntry{},
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
	log.Println(command, args)
	e := cmd.Run()
	if e != nil {
		log.Println("Command failed: ", e)
	}
	return e
}

func (o *OSSepcialSetup) Rollback() {
	o.backupDNS(false)
	o.setInterfaceStatus(o.ifName, false)
	for _, v := range o.rollbackentrys {
		err := o.delRoute(v.ifName, v.address, v.viaAddress)
		if err != nil {
			log.Printf("Error: Route delete %s (%s on %s) - %s\n", v.address, v.viaAddress, v.ifName, err.Error())
		}
	}
	o.setDefaultGateway(o.defaultGateWay, o.defaultGateWayDevice)
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
	err = o.setInterfaceStatus(ifName, true)
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
	err = o.setNameServer(o.defaultGateWayDevice, dns)
	if err != nil {
		return err
	}
	//o.delRoute(o.defaultGateWayDevice, "0.0.0.0/0", gateway)
	//route del default dev eth0
	o.exec("route", []string{"del", "default", "dev", o.defaultGateWayDevice})
	return o.setDefaultGateway(gateway, ifName)
}

func (o *OSSepcialSetup) backupDNS(backup bool) error {
	if !backup {
		return o.exec("cp", []string{"-f", "/etc/resolv.conf.iostunclient.backup", "/etc/resolv.conf"})
	}
	return o.exec("cp", []string{"-f", "/etc/resolv.conf", "/etc/resolv.conf.iostunclient.backup"})
}

func (o *OSSepcialSetup) setNameServer(deviceName string, dns []string) error {
	err := o.backupDNS(true)
	if err != nil {
		return err
	}
	text := ""
	for _, v := range dns {
		text += "nameserver "
		text += v
		text += "\n"
	}
	os.Remove("/etc/resolv.conf")
	f, err := os.Create("/etc/resolv.conf")
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = f.WriteString(text)
	return err
}

//SetInterfaceStatus brings up or down a network interface.
func (o *OSSepcialSetup) setInterfaceStatus(iName string, up bool) error {
	statusString := "down"
	if up {
		statusString = "up"
	}
	sargs := fmt.Sprintf("link set dev %s %s mtu %d qlen %d", iName, statusString, o.mtu, 300)
	args := strings.Split(sargs, " ")
	return o.exec("ip", args)
}

func (o *OSSepcialSetup) setDeviceIP(iName string, address string) error {
	ip, mask, err := net.ParseCIDR(address)
	if err != nil {
		log.Println("ParseCIDR failed" + err.Error())
		return err
	}
	//
	sargs := fmt.Sprintf("%s %s netmask %s", iName, ip.String(), net.IP(mask.Mask).String())
	args := strings.Split(sargs, " ")
	return o.exec("ifconfig", args)
}

// SetDefaultGateway sets the systems gateway to the IP / device specified.
func (o *OSSepcialSetup) setDefaultGateway(gw, iName string) error {
	sargs := fmt.Sprintf("add default gw %s dev %s", gw, iName)
	args := strings.Split(sargs, " ")
	return o.exec("route", args)
}

func (o *OSSepcialSetup) addRoute(ifName, addr, gateway string) error {
	ip, mask, err := net.ParseCIDR(addr)
	if err != nil {
		return err
	}
	//route add -net 149.28.41.235 netmask 255.255.255.255 gw 192.168.1.1 dev eth0
	args := []string{"add", "-net", ip.String(), "netmask", net.IP(mask.Mask).String(), "gw", gateway, "dev", ifName}
	return o.exec("route", args)
}

func (o *OSSepcialSetup) delRoute(ifName, addr, gateway string) error {
	ip, mask, err := net.ParseCIDR(addr)
	if err != nil {
		return err
	}
	args := []string{"del", "-net", ip.String(), "netmask", net.IP(mask.Mask).String(), "gw", gateway, "dev", ifName}
	return o.exec("route", args)
}

// GetNetGateway return net gateway (default route) and nic.
// Credit: https://github.com/bigeagle/gohop/blob/master/hop/iface.go
func (o *OSSepcialSetup) getDefaultGateway() (gw, dev string, err error) {
	file, err := os.Open("/proc/net/route")
	if err != nil {
		return "", "", err
	}

	defer file.Close()
	rd := bufio.NewReader(file)

	s2byte := func(s string) byte {
		b, _ := strconv.ParseUint(s, 16, 8)
		return byte(b)
	}

	for {
		line, isPrefix, err := rd.ReadLine()

		if err != nil {
			return "", "", err
		}
		if isPrefix {
			return "", "", errors.New("Parse error: Line too long")
		}
		buf := bytes.NewBuffer(line)
		scanner := bufio.NewScanner(buf)
		scanner.Split(bufio.ScanWords)
		tokens := make([]string, 0, 8)

		for scanner.Scan() {
			tokens = append(tokens, scanner.Text())
		}

		iface := tokens[0]
		dest := tokens[1]
		gw := tokens[2]
		mask := tokens[7]

		if bytes.Equal([]byte(dest), []byte("00000000")) &&
			bytes.Equal([]byte(mask), []byte("00000000")) {
			a := s2byte(gw[6:8])
			b := s2byte(gw[4:6])
			c := s2byte(gw[2:4])
			d := s2byte(gw[0:2])

			ip := net.IPv4(a, b, c, d)

			return ip.String(), iface, nil
		}

	}
}

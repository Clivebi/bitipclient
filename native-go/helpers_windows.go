package bitipclient

import (
	"github.com/songgao/water"
	//"golang.org/x/sys/windows"
	"golang.org/x/text/encoding/simplifiedchinese"
	"log"
	"net"
	"os/exec"
	"syscall"
	"unsafe"
)

var (
	ptrInit,
	ptrDeleteTableEntry,
	ptrAddIncludeTableEntry,
	ptrAddExcludeTableEntry uintptr
)

func init() {
	lib, err := syscall.LoadLibrary("RouteHelp.dll")
	if err != nil {
		panic("LoadLibrary " + err.Error())
	}

	ptrInit = getProcAddr(lib, "Init")
	ptrDeleteTableEntry = getProcAddr(lib, "DeleteTableEntry")
	ptrAddIncludeTableEntry = getProcAddr(lib, "AddIncludeTableEntry")
	ptrAddExcludeTableEntry = getProcAddr(lib, "AddExcludeTableEntry")
}

func getProcAddr(lib syscall.Handle, name string) uintptr {
	addr, err := syscall.GetProcAddress(lib, name)
	if err != nil {
		panic(name + " " + err.Error())
	}
	return addr
}

func ipToInt(ip net.IP) int {
	s := ip.To4()
	rs := 0
	rs |= (int([]byte(s)[0]))
	rs |= (int([]byte(s)[1]) << 8)
	rs |= (int([]byte(s)[2]) << 16)
	rs |= (int([]byte(s)[3]) << 24)
	return rs
}

func initRuouteHelp(ifc string, gateway int) (int, error) {
	frindName, err := syscall.UTF16PtrFromString(ifc)
	if err != nil {
		return -1, err
	}
	name := uintptr(unsafe.Pointer(frindName))
	ret, _, callErr := syscall.Syscall(ptrInit, 2, name, uintptr(gateway), 0)
	if callErr != 0 {
		return -1, callErr
	}
	return int(ret), err
}

func AddIncludeTableEntry(cidr string) (int, error) {
	ip, ipm, err := net.ParseCIDR(cidr)
	if err != nil {
		return -1, err
	}
	ret, _, callErr := syscall.Syscall(ptrAddIncludeTableEntry, 2, uintptr(ipToInt(ip)), uintptr(ipToInt(net.IP(ipm.Mask))), 0)
	if callErr != 0 {
		return -1, callErr
	}
	return int(ret), err
}

func AddExcludeTableEntry(cidr string) (int, error) {
	ip, ipm, err := net.ParseCIDR(cidr)
	if err != nil {
		return -1, err
	}
	ret, _, callErr := syscall.Syscall(ptrAddExcludeTableEntry, 2, uintptr(ipToInt(ip)), uintptr(ipToInt(net.IP(ipm.Mask))), 0)
	if callErr != 0 {
		return -1, callErr
	}
	return int(ret), err
}

func DeleteTableEntry(cidr string) (int, error) {
	ip, ipm, err := net.ParseCIDR(cidr)
	if err != nil {
		return -1, err
	}
	ret, _, callErr := syscall.Syscall(ptrDeleteTableEntry, 2, uintptr(ipToInt(ip)), uintptr(ipToInt(net.IP(ipm.Mask))), 0)
	if callErr != 0 {
		return -1, callErr
	}
	return int(ret), err
}

func GB2312toUTF8(s []byte) []byte {
	d, _ := simplifiedchinese.GBK.NewDecoder().Bytes(s)
	return d
}

func getWaterConfig() water.Config {
	return water.Config{
		DeviceType: water.TUN,
		PlatformSpecificParams: water.PlatformSpecificParams{
			ComponentID: "tap0901",
			Network:     "10.1.0.10/24",
		},
	}
}

func (o *VPNConnector) exec(command string, args []string) error {
	cmd := exec.Command(command, args...)
	log.Println(command, args)
	buf, err := cmd.Output()
	if err != nil {
		log.Println("Command failed: ", string(GB2312toUTF8(buf)))
	}
	log.Println(string(GB2312toUTF8(buf)))
	return err
}

func (o *VPNConnector) setDeviceNetworkParameters(iName string, address string, gatway string, mtu string) error {
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

func (o *VPNConnector) setDeviceNameServer(iName string, dns []string) error {
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

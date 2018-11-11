package bitipclient

import (
	"errors"
	"io/ioutil"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

const (
	PRODUCT_NAME = "bitipclient"
)

func PullHttpFile(url string) (ret []byte, err error) {
	var resp *http.Response
	resp, err = http.Get(url)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, errors.New("get http response error!")
	}
	defer resp.Body.Close()
	ret, err = ioutil.ReadAll(resp.Body)
	return
}

func LoadFile(url string) (ret []byte, err error) {
	if strings.HasPrefix(url, "http://") || strings.HasPrefix(url, "https://") {
		return PullHttpFile(url)
	}
	ret, err = ioutil.ReadFile(url)
	return
}

func GetCurrentDirectory() string {
	execPath, _ := exec.LookPath(os.Args[0])
	path, _ := filepath.Abs(execPath)
	dir := filepath.Dir(path)
	return dir
}

func LoadLocalConfigFile(servername string) ([]byte, error) {
	var path string = GetCurrentDirectory() + string(filepath.Separator) + "conf" + string(filepath.Separator)
	path += servername
	if !strings.Contains(servername, ".") {
		path += ".conf"
	}
	b, err := ioutil.ReadFile(path)
	if err == nil {
		return b, err
	}
	path = "/etc/" + PRODUCT_NAME + "/"
	path += servername
	if !strings.Contains(servername, ".") {
		path += ".conf"
	}
	return ioutil.ReadFile(path)
}

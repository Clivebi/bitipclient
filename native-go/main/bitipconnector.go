package main

import (
	"bitip/bitipclient/native-go"
	_ "fmt"
	_ "os"
)

func main() {
	s := bitipclient.NewServer()
	s.ServerHttp("127.0.0.1:8978")
}

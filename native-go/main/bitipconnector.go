package main

import (
	"bitip/bitipclient/native-go"
	_ "fmt"
	"os"
)

func main() {
	if len(os.Args) == 2 {
		if os.Args[1] == "-C" || os.Args[1] == "-c" || os.Args[1] == "/c" || os.Args[1] == "/C" {
			err := bitipclient.CheckEvn()
			if err != nil {
				os.Exit(-1)
				return
			}
			os.Exit(0)
			return
		}
	}
	s := bitipclient.NewServer()
	s.ServerHttp("127.0.0.1:8978")
}

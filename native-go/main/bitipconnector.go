package main

import (
	"bitip/bitipclient/native-go"
	_ "fmt"
	"log"
	"os"
	"path/filepath"
)

type LogWriter struct {
	f *os.File
}

func (o *LogWriter) UserClosed() {
	o.f.Sync()
}

func main() {
	var addr = "127.0.0.1:8978"
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

	if len(os.Args) == 3 {
		if os.Args[1] == "-p" || os.Args[1] == "/p" || os.Args[1] == "/P" || os.Args[1] == "-p" {
			addr = "127.0.0.1:" + os.Args[2]
		}
	}

	f, err := os.OpenFile(filepath.Join(os.Getenv("TEMP"), "bitipconnector.log"), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		log.Println("open log failed")
	} else {
		log.SetOutput(f)
	}
	notify := &LogWriter{f: f}
	s := bitipclient.NewServer(notify)
	s.ServerHttp(addr)
	if f != nil {
		f.Close()
	}
}

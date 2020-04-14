package bitipclient

import (
	"bytes"
	"crypto/md5"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
)

const (
	command_startup     = byte(1)
	command_tun_data    = byte(2)
	command_mproxy_data = byte(3)
)

const (
	command_tcp_connect    = byte(1)
	command_tcp_disconnect = byte(2)
	command_tcp_data       = byte(3)
)

const (
	CRYPT_METHOD_RC4      = 1
	CRYPT_METHOD_CHACHA20 = 2
	CRYPT_METHOD_AES256   = 3
)

const (
	secrectlen = 64
)

const (
	address_ipv4 = byte(1)
	address_ipv6 = byte(2)
	address_host = byte(3)
)

const (
	max_content_size = 1024 * 64
	token_size       = 16
	secrect_size     = 64
)

type netaddress struct {
	address string
	cookie  uint32
}

func (o *netaddress) WriteToIO(w io.Writer) error {
	length := byte(len(o.address))
	if err := binary.Write(w, binary.BigEndian, length); err != nil {
		return err
	}
	if err := binary.Write(w, binary.BigEndian, []byte(o.address)); err != nil {
		return err
	}
	if err := binary.Write(w, binary.BigEndian, o.cookie); err != nil {
		return err
	}
	return nil
}

func (o *netaddress) ReadFromIO(r io.Reader) error {
	length := byte(0)
	if err := binary.Read(r, binary.BigEndian, &length); err != nil {
		fmt.Println("read address length failed")
		return err
	}
	buf := make([]byte, length)
	if err := binary.Read(r, binary.BigEndian, buf); err != nil {
		fmt.Println("read address failed", length)
		return err
	}
	o.address = string(buf)
	if err := binary.Read(r, binary.BigEndian, &o.cookie); err != nil {
		fmt.Println("read cookie failed")
		return err
	}
	if len(o.address) != int(length) {
		return errors.New("read address failed")
	}
	return nil
}

func (o *netaddress) ReadFromBuffer(b []byte) error {
	r := bytes.NewReader(b)
	return o.ReadFromIO(r)
}

type mproxy_command struct {
	cmd     byte
	status  byte
	session uint32
	address netaddress
	data    []byte
}

func (o *mproxy_command) WriteToIO(w io.Writer) error {
	if err := binary.Write(w, binary.BigEndian, o.cmd); err != nil {
		return err
	}
	if err := binary.Write(w, binary.BigEndian, o.status); err != nil {
		return err
	}
	if err := binary.Write(w, binary.BigEndian, o.session); err != nil {
		return err
	}
	switch o.cmd {
	case command_tcp_connect:
		return o.address.WriteToIO(w)
	case command_tcp_data:
		length := int32(len(o.data))
		if err := binary.Write(w, binary.BigEndian, length); err != nil {
			return err
		}
		if err := binary.Write(w, binary.BigEndian, o.data); err != nil {
			return err
		}
	default:
		break
	}
	return nil
}

func (o *mproxy_command) ReadFromIO(r io.Reader) error {
	if err := binary.Read(r, binary.BigEndian, &o.cmd); err != nil {
		return err
	}
	if err := binary.Read(r, binary.BigEndian, &o.status); err != nil {
		fmt.Println("read status failed cmd ", o.cmd)
		return err
	}
	if err := binary.Read(r, binary.BigEndian, &o.session); err != nil {
		fmt.Println("read session failed cmd ", o.cmd)
		return err
	}
	switch o.cmd {
	case command_tcp_connect:
		return o.address.ReadFromIO(r)
	case command_tcp_data:
		length := int32(0)
		if err := binary.Read(r, binary.BigEndian, &length); err != nil {
			fmt.Println("read data length failed", length)
			return err
		}
		o.data = make([]byte, int(length))
		if err := binary.Read(r, binary.BigEndian, o.data); err != nil {
			fmt.Println("read data failed length:", length)
			return err
		}
	default:
		break
	}
	return nil
}

func (o *mproxy_command) ReadFromBuffer(b []byte) error {
	r := bytes.NewReader(b)
	return o.ReadFromIO(r)
}

type startup_request struct {
	user    string
	authkey string
	method  byte
	secrect []byte
}

func (o startup_request) String() string {
	return o.user + " " + o.authkey
}

func (o startup_request) WriteToIO(w io.Writer) error {
	if err := binary.Write(w, binary.BigEndian, byte(len(o.user))); err != nil {
		return err
	}

	if err := binary.Write(w, binary.BigEndian, []byte(o.user)); err != nil {
		return err
	}

	if err := binary.Write(w, binary.BigEndian, byte(len(o.authkey))); err != nil {
		return err
	}

	if err := binary.Write(w, binary.BigEndian, []byte(o.authkey)); err != nil {
		return err
	}

	if err := binary.Write(w, binary.BigEndian, o.method); err != nil {
		return err
	}
	if err := binary.Write(w, binary.BigEndian, o.secrect); err != nil {
		return err
	}
	return nil
}

func (o *startup_request) ReadFromIO(r io.Reader) error {
	var tlen byte = 0
	if err := binary.Read(r, binary.BigEndian, &tlen); err != nil {
		return err
	}
	buf := make([]byte, int(tlen))
	if err := binary.Read(r, binary.BigEndian, buf); err != nil {
		return err
	}
	o.user = string(buf)

	if err := binary.Read(r, binary.BigEndian, &tlen); err != nil {
		return err
	}
	buf = make([]byte, int(tlen))
	if err := binary.Read(r, binary.BigEndian, buf); err != nil {
		return err
	}
	o.authkey = string(buf)

	if err := binary.Read(r, binary.BigEndian, &o.method); err != nil {
		return err
	}
	o.secrect = make([]byte, secrectlen)
	if err := binary.Read(r, binary.BigEndian, o.secrect); err != nil {
		return err
	}
	if len(o.secrect) != secrectlen {
		return fmt.Errorf("invalid secrect length %d", len(o.secrect))
	}
	return nil
}

func (o *startup_request) ReadFromBuffer(buf []byte) error {
	r := bytes.NewReader(buf)
	return o.ReadFromIO(r)
}

type startup_response struct {
	address []byte
	mtu     uint32
}

func (o startup_response) WriteToIO(w io.Writer) error {
	if len(o.address) != 4 {
		return errors.New("invalid length")
	}
	if err := binary.Write(w, binary.BigEndian, o.address); err != nil {
		return err
	}
	return binary.Write(w, binary.BigEndian, o.mtu)
}

func (o *startup_response) ReadFromIO(r io.Reader) error {
	o.address = make([]byte, 4)
	if err := binary.Read(r, binary.BigEndian, o.address); err != nil {
		return err
	}
	if err := binary.Read(r, binary.BigEndian, &o.mtu); err != nil {
		return err
	}
	if len(o.address) != 4 {
		return errors.New("invalid length")
	}
	return nil
}

func (o *startup_response) ReadFromBuffer(buf []byte) error {
	r := bytes.NewReader(buf)
	return o.ReadFromIO(r)
}

type protocol_layer struct {
	length  uint32
	command byte
	data    []byte
}

func (o *protocol_layer) WriteToIO(w io.Writer) error {
	wi := bytes.NewBuffer(nil)
	o.length = uint32(len(o.data)) + 5
	if err := binary.Write(wi, binary.BigEndian, o.length); err != nil {
		return err
	}
	if err := binary.Write(wi, binary.BigEndian, o.command); err != nil {
		return err
	}
	if err := binary.Write(wi, binary.BigEndian, o.data); err != nil {
		return err
	}
	hash := md5.Sum(wi.Bytes())
	if err := binary.Write(w, binary.BigEndian, wi.Bytes()); err != nil {
		return err
	}
	if err := binary.Write(w, binary.BigEndian, hash); err != nil {
		return err
	}
	return nil
}

func (o *protocol_layer) ReadFromIO(r io.Reader) error {
	oldhash := make([]byte, 16)
	if err := binary.Read(r, binary.BigEndian, &o.length); err != nil {
		return err
	}
	if o.length < 5 || o.length > max_content_size {
		return fmt.Errorf("invalid length : %d", o.length)
	}
	if err := binary.Read(r, binary.BigEndian, &o.command); err != nil {
		return err
	}
	if o.length == 5 {
		return nil
	}
	o.data = make([]byte, o.length-5)
	if err := binary.Read(r, binary.BigEndian, o.data); err != nil {
		return err
	}
	if err := binary.Read(r, binary.BigEndian, oldhash); err != nil {
		return err
	}
	if uint32(len(o.data)) != (o.length - 5) {
		return errors.New("invalid length")
	}
	hash := md5.New()
	binary.Write(hash, binary.BigEndian, o.length)
	binary.Write(hash, binary.BigEndian, o.command)
	binary.Write(hash, binary.BigEndian, o.data)
	if !bytes.Equal(hash.Sum(nil), oldhash) {
		return errors.New("verify buffer md5 error")
	}
	return nil
}

func (o *protocol_layer) ReadFromBuffer(buf []byte) error {
	r := bytes.NewReader(buf)
	return o.ReadFromIO(r)
}

type network_layer struct {
	length uint32
	data   []byte
}

func (o *network_layer) WriteToIO(w io.Writer) error {
	o.length = uint32(len(o.data))
	if o.length > max_content_size {
		return errors.New("netowrk layer data too large")
	}
	if err := binary.Write(w, binary.BigEndian, o.length); err != nil {
		return err
	}
	if err := binary.Write(w, binary.BigEndian, o.data); err != nil {
		return err
	}
	return nil
}

func (o *network_layer) ReadFromIO(r io.Reader) error {
	if err := binary.Read(r, binary.BigEndian, &o.length); err != nil {
		return err
	}
	if o.length < 5 || o.length > max_content_size {
		return errors.New("invalid length")
	}
	o.data = make([]byte, o.length)
	if err := binary.Read(r, binary.BigEndian, o.data); err != nil {
		return err
	}
	if uint32(len(o.data)) != o.length {
		return errors.New("invalid length")
	}
	return nil
}

func (o *network_layer) ReadFromBuffer(buf []byte) error {
	r := bytes.NewReader(buf)
	return o.ReadFromIO(r)
}

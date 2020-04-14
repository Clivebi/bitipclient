package bitipclient

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rc4"
	"errors"
	"github.com/aead/chacha20"
	"io/ioutil"
	"os"
)

var (
	confkey = []byte{0x16, 0x9E, 0xA6, 0x63, 0x3C, 0x38, 0x31, 0x04, 0x33, 0xAA, 0x09, 0x50, 0x47, 0xC3, 0xE6, 0x32,
		0x20, 0xC8, 0x42, 0x05, 0x84, 0xE7, 0xF3, 0xB5, 0xA3, 0x76, 0x32, 0xE3, 0xD3, 0x71, 0xA8, 0x84,
		0xA4, 0x5E, 0x13, 0x1A, 0x80, 0x6A, 0x84, 0x49, 0x89, 0xE3, 0x1E, 0x26, 0xC3, 0x74, 0x52, 0xF3,
		0xEA, 0x53, 0x2D, 0xAD, 0x7E, 0x70, 0xDD, 0xE4, 0xF4, 0x39, 0x35, 0x10, 0x43, 0x2C, 0x9F, 0x56}
)

func EncryptFile(src, dst string) error {
	buf, err := ioutil.ReadFile(src)
	if err != nil {
		return err
	}
	ch, err := rc4.NewCipher(confkey)
	if err != nil {
		return err
	}
	ch.XORKeyStream(buf, buf)
	os.Remove(dst)
	return ioutil.WriteFile(dst, buf, os.ModePerm)
}

func DecryptBuffer(buf []byte) {
	ch, err := rc4.NewCipher(confkey)
	if err != nil {
		return
	}
	ch.XORKeyStream(buf, buf)
}

type CryptBlock interface {
	Encrypt(src []byte) []byte
	Decrypt(src []byte) ([]byte, error)
}

type AESCryptBlock struct {
	block  cipher.Block
	writer cipher.BlockMode
	reader cipher.BlockMode
}

func NewAESCryptBlock(key []byte, writerIV []byte, readIV []byte) (CryptBlock, error) {
	var err error
	o := &AESCryptBlock{}
	o.block, err = aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	o.writer = cipher.NewCBCEncrypter(o.block, writerIV)
	o.reader = cipher.NewCBCDecrypter(o.block, readIV)
	return o, nil
}

func (o *AESCryptBlock) Encrypt(src []byte) []byte {
	plantText := o.pkcs7Padding(src, o.block.BlockSize())
	ciphertext := make([]byte, len(plantText))
	o.writer.CryptBlocks(ciphertext, plantText)
	return ciphertext
}

func (o *AESCryptBlock) pkcs7Padding(ciphertext []byte, blockSize int) []byte {
	padding := blockSize - len(ciphertext)%blockSize
	if padding == 0 {
		padding = blockSize
	}
	padtext := bytes.Repeat([]byte{byte(padding)}, padding)
	return append(ciphertext, padtext...)
}

func (o *AESCryptBlock) Decrypt(src []byte) ([]byte, error) {
	if len(src) == 0 || (len(src)%o.block.BlockSize()) != 0 {
		return nil, errors.New("align error")
	}
	plantText := make([]byte, len(src))
	o.reader.CryptBlocks(plantText, src)
	return o.pkcs7UnPadding(plantText, o.block.BlockSize())
}

func (o *AESCryptBlock) pkcs7UnPadding(plantText []byte, blockSize int) ([]byte, error) {
	nLen := len(plantText)
	if nLen < blockSize {
		return nil, errors.New("length invalid ")
	}
	pading := int(plantText[nLen-1])
	srclen := nLen - pading
	if srclen < 0 {
		return nil, errors.New("length invalid ")
	}
	return plantText[:srclen], nil
}

type StreamCryptBlock struct {
	writer cipher.Stream
	reader cipher.Stream
}

func (o *StreamCryptBlock) Encrypt(src []byte) []byte {
	dst := make([]byte, len(src))
	o.writer.XORKeyStream(dst, src)
	return dst
}

func (o *StreamCryptBlock) Decrypt(src []byte) ([]byte, error) {
	dst := make([]byte, len(src))
	o.reader.XORKeyStream(dst, src)
	return dst, nil
}

func NewRC4CryptBlock(key []byte, writerIV []byte, readIV []byte) (CryptBlock, error) {
	var err error
	o := &StreamCryptBlock{}
	fullKey := key
	fullKey = append(fullKey, writerIV...)
	o.writer, err = rc4.NewCipher(fullKey)
	if err != nil {
		return nil, err
	}
	fullKey = key
	fullKey = append(fullKey, readIV...)
	o.reader, err = rc4.NewCipher(fullKey)
	if err != nil {
		return nil, err
	}
	return o, nil
}

func NewChaCha20CryptBlock(key []byte, writerIV []byte, readIV []byte) (CryptBlock, error) {
	var err error
	o := &StreamCryptBlock{}
	o.writer, err = chacha20.NewCipher(writerIV[:8], key)
	if err != nil {
		return nil, err
	}
	o.reader, err = chacha20.NewCipher(readIV[:8], key)
	if err != nil {
		return nil, err
	}
	return o, nil
}

package bitipclient

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rc4"
	"errors"
	"github.com/aead/chacha20"
)

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

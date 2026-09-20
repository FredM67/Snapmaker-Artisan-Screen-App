package main

import (
	"bytes"
	"encoding/base64"
	"fmt"
)

const (
	maxJPEGBytes     = 512 * 1024
	dataChannelChunk = 12 * 1024
	maxBufferedBytes = 512 * 1024
	maxIPCLineBytes  = 2 * 1024 * 1024
)

// Obico's MJPEG-over-Janus client expects a binary header followed by ordered
// binary DataChannel messages containing one base64-encoded JPEG. The header
// lengths let the browser find frame boundaries without relying on SCTP packet
// boundaries or a fixed chunk size.
func mjpegMessages(jpeg []byte) ([][]byte, error) {
	if len(jpeg) < 4 || len(jpeg) > maxJPEGBytes ||
		!bytes.HasPrefix(jpeg, []byte{0xff, 0xd8}) ||
		!bytes.HasSuffix(jpeg, []byte{0xff, 0xd9}) {
		return nil, fmt.Errorf("invalid or oversized JPEG frame")
	}
	encoded := make([]byte, base64.StdEncoding.EncodedLen(len(jpeg)))
	base64.StdEncoding.Encode(encoded, jpeg)
	messages := make([][]byte, 0, 2+len(encoded)/dataChannelChunk)
	messages = append(messages, []byte(fmt.Sprintf("\r\n%d:%d\r\n", len(encoded), len(jpeg))))
	for offset := 0; offset < len(encoded); offset += dataChannelChunk {
		end := offset + dataChannelChunk
		if end > len(encoded) {
			end = len(encoded)
		}
		messages = append(messages, encoded[offset:end])
	}
	return messages, nil
}

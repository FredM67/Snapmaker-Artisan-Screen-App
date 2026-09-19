package main

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"testing"
)

func TestMJPEGMessagesRoundTripAndBounds(t *testing.T) {
	jpeg := append([]byte{0xff, 0xd8}, bytes.Repeat([]byte{0x41}, 65*1024)...)
	jpeg = append(jpeg, 0xff, 0xd9)
	messages, err := mjpegMessages(jpeg)
	if err != nil {
		t.Fatal(err)
	}
	if len(messages) < 3 {
		t.Fatalf("expected chunked frame, got %d messages", len(messages))
	}
	var encodedLength, rawLength int
	if _, err := fmt.Sscanf(string(messages[0]), "\r\n%d:%d\r\n", &encodedLength, &rawLength); err != nil {
		t.Fatal(err)
	}
	if rawLength != len(jpeg) {
		t.Fatalf("raw length %d, want %d", rawLength, len(jpeg))
	}
	var combined []byte
	for _, part := range messages[1:] {
		if len(part) > dataChannelChunk {
			t.Fatalf("oversized DataChannel message: %d", len(part))
		}
		combined = append(combined, part...)
	}
	if len(combined) != encodedLength {
		t.Fatalf("encoded length %d, want %d", len(combined), encodedLength)
	}
	decoded, err := base64.StdEncoding.DecodeString(string(combined))
	if err != nil || !bytes.Equal(decoded, jpeg) {
		t.Fatal("frame did not round-trip")
	}
}

func TestMJPEGMessagesRejectInvalidOrLarge(t *testing.T) {
	for _, frame := range [][]byte{nil, []byte("not jpeg"), append([]byte{0xff, 0xd8}, bytes.Repeat([]byte{0}, maxJPEGBytes)...)} {
		if _, err := mjpegMessages(frame); err == nil {
			t.Fatalf("accepted invalid frame of %d bytes", len(frame))
		}
	}
}

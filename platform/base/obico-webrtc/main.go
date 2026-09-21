package main

import (
	"bufio"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// One JSON object per UTF-8 line. The only command-line values are Java's
// ephemeral loopback port and a random one-use nonce. The Obico token arrives
// only in the authenticated init message, never argv or logs.
type ipcMessage struct {
	Type      string `json:"type"`
	Nonce     string `json:"nonce,omitempty"`
	AuthToken string `json:"auth_token,omitempty"`
	Payload   string `json:"payload,omitempty"`
	JPEG      string `json:"jpeg,omitempty"`
	State     string `json:"state,omitempty"`
	Message   string `json:"message,omitempty"`
}

type lineWriter struct {
	mu   sync.Mutex
	conn net.Conn
}

func (w *lineWriter) send(message ipcMessage) error {
	encoded, err := json.Marshal(message)
	if err != nil {
		return err
	}
	if len(encoded)+1 > maxIPCLineBytes {
		return errors.New("outbound IPC line too large")
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if err := w.conn.SetWriteDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return err
	}
	_, err = w.conn.Write(append(encoded, '\n'))
	return err
}

func readLine(scanner *bufio.Scanner, message *ipcMessage) error {
	if !scanner.Scan() {
		if err := scanner.Err(); err != nil {
			return err
		}
		return errors.New("IPC connection closed")
	}
	if err := json.Unmarshal(scanner.Bytes(), message); err != nil {
		return errors.New("invalid IPC JSON")
	}
	return nil
}

func serveIPC(conn net.Conn, nonce string) error {
	defer conn.Close()
	scanner := bufio.NewScanner(conn)
	scanner.Buffer(make([]byte, 4096), maxIPCLineBytes)
	writer := &lineWriter{conn: conn}
	if err := writer.send(ipcMessage{Type: "hello", Nonce: nonce}); err != nil {
		return err
	}
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	var hello ipcMessage
	if err := readLine(scanner, &hello); err != nil {
		return err
	}
	if hello.Type != "hello" || hello.Nonce != nonce || hello.AuthToken != "" || hello.Payload != "" {
		return errors.New("loopback peer authentication failed")
	}
	_ = conn.SetReadDeadline(time.Time{})
	b := newBridge(newPionPeer,
		func(response any) {
			encoded, err := json.Marshal(response)
			if err != nil || writer.send(ipcMessage{Type: "janus", Payload: string(encoded)}) != nil {
				_ = conn.Close()
			}
		},
		func(state, message string) {
			if writer.send(ipcMessage{Type: "status", State: state, Message: message}) != nil {
				_ = conn.Close()
			}
		})
	defer b.close()
	for {
		var request ipcMessage
		if err := readLine(scanner, &request); err != nil {
			return err
		}
		switch request.Type {
		case "init":
			if err := b.init(request.AuthToken); err != nil {
				_ = writer.send(ipcMessage{Type: "status", State: "error", Message: "Invalid Obico peer settings"})
			}
		case "janus":
			if err := b.handleJanus([]byte(request.Payload)); err != nil {
				_ = writer.send(ipcMessage{Type: "status", State: "error", Message: "Invalid Janus relay message"})
				return err
			}
		case "frame":
			if len(request.JPEG) > base64.StdEncoding.EncodedLen(maxJPEGBytes) {
				continue
			}
			jpeg, err := base64.StdEncoding.DecodeString(request.JPEG)
			if err == nil && len(jpeg) <= maxJPEGBytes && len(jpeg) >= 4 &&
				bytes.HasPrefix(jpeg, []byte{0xff, 0xd8}) &&
				bytes.HasSuffix(jpeg, []byte{0xff, 0xd9}) {
				b.queueFrame(jpeg)
			}
		default:
			_ = writer.send(ipcMessage{Type: "status", State: "error", Message: "Unsupported peer command"})
			return errors.New("unsupported IPC command")
		}
	}
}

func main() {
	if len(os.Args) != 3 {
		os.Exit(2)
	}
	port, err := strconv.Atoi(os.Args[1])
	nonce := os.Args[2]
	if err != nil || port < 1 || port > 65535 || len(nonce) < 24 || len(nonce) > 128 ||
		strings.ContainsAny(nonce, "\r\n\x00") {
		os.Exit(2)
	}
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 5*time.Second)
	if err != nil {
		os.Exit(1)
	}
	if serveIPC(conn, nonce) != nil {
		os.Exit(1)
	}
}

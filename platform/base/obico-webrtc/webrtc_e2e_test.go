package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"sync"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"
)

// Negotiates the Janus-compatible server offer with a real Pion client over
// loopback ICE, then verifies the browser-style DataChannel MJPEG framing.
// No Obico account, printer operation, TURN server, or browser is involved.
func TestJanusWebRTCDataChannelEndToEnd(t *testing.T) {
	var repliesMu sync.Mutex
	var replies []map[string]any
	states := make(chan string, 16)
	var serverPeer *pionPeer
	b := newBridge(func(_ string, onCandidate func(candidate), onOpen func(bool)) (videoPeer, error) {
		created, err := newPionPeerConfigured(webrtc.Configuration{}, onCandidate, onOpen)
		if err == nil {
			serverPeer = created.(*pionPeer)
		}
		return created, err
	}, func(value any) {
		encoded, err := json.Marshal(value)
		if err != nil {
			t.Error(err)
			return
		}
		var reply map[string]any
		if err := json.Unmarshal(encoded, &reply); err != nil {
			t.Error(err)
			return
		}
		repliesMu.Lock()
		replies = append(replies, reply)
		repliesMu.Unlock()
	}, func(state, _ string) {
		select {
		case states <- state:
		default:
		}
	})
	defer b.close()
	if err := b.init("local-test-token"); err != nil {
		t.Fatal(err)
	}
	send := func(request map[string]any) {
		t.Helper()
		encoded, err := json.Marshal(request)
		if err != nil {
			t.Fatal(err)
		}
		if err := b.handleJanus(encoded); err != nil {
			t.Fatal(err)
		}
	}
	waitReply := func(match func(map[string]any) bool) map[string]any {
		t.Helper()
		deadline := time.Now().Add(10 * time.Second)
		for time.Now().Before(deadline) {
			repliesMu.Lock()
			for _, reply := range replies {
				if match(reply) {
					repliesMu.Unlock()
					return reply
				}
			}
			repliesMu.Unlock()
			time.Sleep(10 * time.Millisecond)
		}
		t.Fatal("timed out waiting for Janus response")
		return nil
	}
	send(map[string]any{"janus": "create", "transaction": "create"})
	created := waitReply(func(r map[string]any) bool { return r["transaction"] == "create" })
	sessionID := uint64(created["data"].(map[string]any)["id"].(float64))
	send(map[string]any{"janus": "attach", "transaction": "attach",
		"plugin": streamingPlugin, "session_id": sessionID})
	attached := waitReply(func(r map[string]any) bool { return r["transaction"] == "attach" })
	handleID := uint64(attached["data"].(map[string]any)["id"].(float64))
	send(map[string]any{"janus": "message", "transaction": "watch",
		"session_id": sessionID, "handle_id": handleID,
		"body": map[string]any{"request": "watch", "id": streamID}})
	prepared := waitReply(func(r map[string]any) bool {
		return r["janus"] == "event" && r["transaction"] == "watch"
	})
	offer := prepared["jsep"].(map[string]any)["sdp"].(string)
	if !bytes.Contains([]byte(offer), []byte("m=application")) {
		t.Fatal("offer does not contain a DataChannel m-line")
	}

	client, err := webrtc.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	messages := make(chan []byte, 128)
	client.OnDataChannel(func(dc *webrtc.DataChannel) {
		dc.OnMessage(func(message webrtc.DataChannelMessage) {
			if !message.IsString {
				messages <- append([]byte(nil), message.Data...)
			}
		})
	})
	if err := client.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: offer}); err != nil {
		t.Fatal(err)
	}
	answer, err := client.CreateAnswer(nil)
	if err != nil {
		t.Fatal(err)
	}
	complete := webrtc.GatheringCompletePromise(client)
	if err := client.SetLocalDescription(answer); err != nil {
		t.Fatal(err)
	}
	select {
	case <-complete:
	case <-time.After(10 * time.Second):
		t.Fatal("client ICE gathering timed out")
	}
	send(map[string]any{"janus": "message", "transaction": "start",
		"session_id": sessionID, "handle_id": handleID,
		"body": map[string]any{"request": "start"},
		"jsep": map[string]any{"type": "answer", "sdp": client.LocalDescription().SDP}})

	// Pion uses trickle ICE for the server offer, just as Janus does. Forward
	// every candidate from the bridge to the client until SCTP opens.
	deadline := time.Now().Add(10 * time.Second)
	candidateIndex := 0
	streaming := false
	for !streaming && time.Now().Before(deadline) {
		repliesMu.Lock()
		newReplies := append([]map[string]any(nil), replies[candidateIndex:]...)
		candidateIndex = len(replies)
		repliesMu.Unlock()
		for _, reply := range newReplies {
			if reply["janus"] != "trickle" {
				continue
			}
			encoded, _ := json.Marshal(reply["candidate"])
			var c candidate
			if err := json.Unmarshal(encoded, &c); err != nil {
				t.Fatal(err)
			}
			if c.Completed {
				continue
			}
			if err := client.AddICECandidate(webrtc.ICECandidateInit{
				Candidate: c.Candidate, SDPMid: c.SDPMid, SDPMLineIndex: c.SDPMLineIndex,
			}); err != nil {
				t.Fatal(err)
			}
		}
		select {
		case state := <-states:
			streaming = state == "streaming"
		default:
		}
		if !streaming {
			time.Sleep(10 * time.Millisecond)
		}
	}
	if !streaming {
		t.Fatal("WebRTC DataChannel did not open")
	}

	imageData := image.NewRGBA(image.Rect(0, 0, 32, 32))
	for y := 0; y < 32; y++ {
		for x := 0; x < 32; x++ {
			imageData.SetRGBA(x, y, color.RGBA{R: uint8(x * 7), G: uint8(y * 7), B: 70, A: 255})
		}
	}
	var jpegBuffer bytes.Buffer
	if err := jpeg.Encode(&jpegBuffer, imageData, &jpeg.Options{Quality: 75}); err != nil {
		t.Fatal(err)
	}
	b.queueFrame(jpegBuffer.Bytes())
	var encodedLength, rawLength int
	var encodedFrame []byte
	for time.Now().Before(deadline.Add(5 * time.Second)) {
		select {
		case part := <-messages:
			if encodedLength == 0 {
				if _, err := fmt.Sscanf(string(part), "\r\n%d:%d\r\n", &encodedLength, &rawLength); err != nil {
					t.Fatal(err)
				}
				continue
			}
			encodedFrame = append(encodedFrame, part...)
			if len(encodedFrame) >= encodedLength {
				if len(encodedFrame) != encodedLength || rawLength != jpegBuffer.Len() {
					t.Fatal("bad MJPEG frame boundaries")
				}
				decoded, err := base64.StdEncoding.DecodeString(string(encodedFrame))
				if err != nil || !bytes.Equal(decoded, jpegBuffer.Bytes()) {
					t.Fatal("decoded DataChannel frame differs from source JPEG")
				}
				if serverPeer == nil || !serverPeer.open.Load() {
					t.Fatal("server peer did not report open DataChannel")
				}
				serverPeer.connectionStateChanged(webrtc.PeerConnectionStateDisconnected)
				if serverPeer.open.Load() {
					t.Fatal("disconnected peer stayed marked open")
				}
				serverPeer.connectionStateChanged(webrtc.PeerConnectionStateConnected)
				if !serverPeer.open.Load() {
					t.Fatal("reconnected peer did not resume open DataChannel")
				}
				return
			}
		case <-time.After(50 * time.Millisecond):
		}
	}
	t.Fatal("MJPEG frame did not arrive over DataChannel")
}

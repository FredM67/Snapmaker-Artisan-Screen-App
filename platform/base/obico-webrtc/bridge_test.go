package main

import (
	"encoding/json"
	"strings"
	"sync"
	"testing"
)

type fakePeer struct {
	mu       sync.Mutex
	frames   int
	closed   bool
	answer   string
	trickles []candidate
	onOpen   func(bool)
}

func (p *fakePeer) Offer() (string, error) { return "v=0\r\n", nil }
func (p *fakePeer) OfferSent()             {}
func (p *fakePeer) Answer(sdp string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.answer = sdp
	return nil
}
func (p *fakePeer) Trickle(c candidate) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.trickles = append(p.trickles, c)
	return nil
}
func (p *fakePeer) QueueFrame(_ []byte) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.frames++
}
func (p *fakePeer) Close() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.closed = true
}

func TestJanusTwoViewersTrickleAndStop(t *testing.T) {
	var mu sync.Mutex
	var replies []map[string]any
	var peers []*fakePeer
	var states []string
	var diagnostics []string
	b := newBridge(func(_ string, _ func(candidate), onOpen func(bool)) (videoPeer, error) {
		p := &fakePeer{onOpen: onOpen}
		peers = append(peers, p)
		return p, nil
	}, func(value any) {
		encoded, _ := json.Marshal(value)
		var reply map[string]any
		_ = json.Unmarshal(encoded, &reply)
		mu.Lock()
		replies = append(replies, reply)
		mu.Unlock()
	}, func(state, message string) {
		mu.Lock()
		states = append(states, state)
		if state == "diagnostic" {
			diagnostics = append(diagnostics, message)
		}
		mu.Unlock()
	})
	if err := b.init("test-token"); err != nil {
		t.Fatal(err)
	}
	if len(states) != 1 || states[0] != "ready" {
		t.Fatalf("unexpected initial states: %#v", states)
	}
	send := func(v map[string]any) {
		t.Helper()
		encoded, _ := json.Marshal(v)
		if err := b.handleJanus(encoded); err != nil {
			t.Fatal(err)
		}
	}
	lastID := func() uint64 {
		t.Helper()
		mu.Lock()
		defer mu.Unlock()
		last := replies[len(replies)-1]
		return uint64(last["data"].(map[string]any)["id"].(float64))
	}
	var sessions, handles [2]uint64
	for i := 0; i < 2; i++ {
		send(map[string]any{"janus": "create", "transaction": "create"})
		sessions[i] = lastID()
		send(map[string]any{"janus": "attach", "plugin": streamingPlugin,
			"transaction": "attach", "session_id": sessions[i]})
		handles[i] = lastID()
		send(map[string]any{"janus": "message", "transaction": "watch",
			"session_id": sessions[i], "handle_id": handles[i],
			"body": map[string]any{"request": "watch", "id": streamID}})
		mu.Lock()
		event := replies[len(replies)-1]
		mu.Unlock()
		if event["janus"] != "event" || event["jsep"].(map[string]any)["type"] != "offer" {
			t.Fatalf("watch did not return JSEP offer: %#v", event)
		}
	}
	if sessions[0] == sessions[1] || handles[0] == handles[1] || len(peers) != 2 {
		t.Fatal("viewer IDs or peers were reused")
	}
	for i := 0; i < 2; i++ {
		send(map[string]any{"janus": "trickle", "transaction": "ice",
			"session_id": sessions[i], "handle_id": handles[i],
			"candidate": map[string]any{"candidate": "candidate:1 1 udp 1 127.0.0.1 10000 typ host"}})
		send(map[string]any{"janus": "message", "transaction": "start",
			"session_id": sessions[i], "handle_id": handles[i],
			"body": map[string]any{"request": "start"},
			"jsep": map[string]any{"type": "answer", "sdp": "v=0\r\n"}})
		peers[i].onOpen(true)
	}
	b.queueFrame([]byte{0xff, 0xd8, 0xff, 0xd9})
	if peers[0].frames != 1 || peers[1].frames != 1 || len(peers[0].trickles) != 1 ||
		peers[0].answer == "" {
		t.Fatal("both viewers did not receive frame/signaling")
	}
	send(map[string]any{"janus": "message", "transaction": "stop",
		"session_id": sessions[0], "handle_id": handles[0],
		"body": map[string]any{"request": "stop"}})
	peers[0].onOpen(true) // a late callback from a closed peer must be ignored
	b.queueFrame([]byte{0xff, 0xd8, 0xff, 0xd9})
	if !peers[0].closed || peers[0].frames != 1 || peers[1].frames != 2 {
		t.Fatal("stopped viewer kept receiving frames or affected the other viewer")
	}
	send(map[string]any{"janus": "destroy", "transaction": "destroy", "session_id": sessions[1]})
	if !peers[1].closed || states[len(states)-1] != "idle" {
		t.Fatalf("remaining viewer did not close: %#v", states)
	}
	joined := strings.Join(diagnostics, " ")
	for _, stage := range []string{"janus_create", "janus_attach", "janus_watch", "peer_offer_sent",
		"peer_answer_accepted", "data_channel_open", "frames_in=1,queued=2,viewers=2"} {
		if !strings.Contains(joined, stage) {
			t.Fatalf("missing diagnostic %q in %q", stage, joined)
		}
	}
	if strings.Contains(joined, "test-token") || strings.Contains(joined, "candidate:") ||
		strings.Contains(joined, "v=0") {
		t.Fatalf("diagnostics contained sensitive signaling: %q", joined)
	}
}

func TestJanusInfoListAndInvalidMountpoint(t *testing.T) {
	var replies []map[string]any
	b := newBridge(func(_ string, _ func(candidate), _ func(bool)) (videoPeer, error) {
		return &fakePeer{}, nil
	}, func(value any) {
		encoded, _ := json.Marshal(value)
		var decoded map[string]any
		_ = json.Unmarshal(encoded, &decoded)
		replies = append(replies, decoded)
	}, func(string, string) {})
	if err := b.init("token"); err != nil {
		t.Fatal(err)
	}
	for _, raw := range []string{
		`{"janus":"info","transaction":"i"}`,
		`{"janus":"create","transaction":"c"}`,
	} {
		if err := b.handleJanus([]byte(raw)); err != nil {
			t.Fatal(err)
		}
	}
	if replies[0]["janus"] != "server_info" || replies[0]["data_channels"] != true {
		t.Fatalf("bad info: %#v", replies[0])
	}
	session := uint64(replies[1]["data"].(map[string]any)["id"].(float64))
	attach, _ := json.Marshal(map[string]any{"janus": "attach", "transaction": "a",
		"session_id": session, "plugin": streamingPlugin})
	if err := b.handleJanus(attach); err != nil {
		t.Fatal(err)
	}
	handle := uint64(replies[2]["data"].(map[string]any)["id"].(float64))
	for _, request := range []string{"list", "info", "watch"} {
		body, _ := json.Marshal(map[string]any{"janus": "message", "transaction": request,
			"session_id": session, "handle_id": handle,
			"body": map[string]any{"request": request, "id": 999}})
		if err := b.handleJanus(body); err != nil {
			t.Fatal(err)
		}
	}
	data := replies[3]["plugindata"].(map[string]any)["data"].(map[string]any)
	if data["streaming"] != "list" {
		t.Fatalf("bad mount list: %#v", data)
	}
	for _, reply := range replies[4:] {
		data := reply["plugindata"].(map[string]any)["data"].(map[string]any)
		if data["error_code"] == nil {
			t.Fatalf("invalid stream id accepted: %#v", reply)
		}
	}
}

func TestPionQueuesRemoteICEBeforeAnswer(t *testing.T) {
	created, err := newPionPeer("test-token", func(candidate) {}, func(bool) {})
	if err != nil {
		t.Fatal(err)
	}
	p := created.(*pionPeer)
	defer p.Close()
	if err := p.Trickle(candidate{Candidate: "candidate:1 1 udp 1 127.0.0.1 10000 typ host"}); err != nil {
		t.Fatal(err)
	}
	p.mu.Lock()
	queued := len(p.remoteICE)
	p.mu.Unlock()
	if queued != 1 {
		t.Fatalf("early ICE candidates queued: %d, want 1", queued)
	}
}

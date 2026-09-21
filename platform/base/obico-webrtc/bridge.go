package main

import (
	"crypto/rand"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
)

const (
	streamID        = 2
	streamingPlugin = "janus.plugin.streaming"
	maxViewers      = 4
)

type candidate struct {
	Candidate     string  `json:"candidate,omitempty"`
	SDPMid        *string `json:"sdpMid,omitempty"`
	SDPMLineIndex *uint16 `json:"sdpMLineIndex,omitempty"`
	Completed     bool    `json:"completed,omitempty"`
}

type videoPeer interface {
	Offer() (string, error)
	OfferSent()
	Answer(string) error
	Trickle(candidate) error
	QueueFrame([]byte)
	Close()
}

// diagnosticPeer is optional so test peers and future transports can remain
// small. Diagnostics are fixed stage names or aggregate counters, never SDP,
// ICE candidates, addresses, or credentials.
type diagnosticPeer interface {
	SetDiagnostic(func(string))
}

type peerFactory func(authToken string, onCandidate func(candidate), onOpen func(bool)) (videoPeer, error)

type janusRequest struct {
	Janus       string          `json:"janus"`
	Transaction string          `json:"transaction"`
	SessionID   uint64          `json:"session_id"`
	HandleID    uint64          `json:"handle_id"`
	Plugin      string          `json:"plugin"`
	Body        json.RawMessage `json:"body"`
	JSEP        json.RawMessage `json:"jsep"`
	Candidate   json.RawMessage `json:"candidate"`
	Candidates  json.RawMessage `json:"candidates"`
}

type mountRequest struct {
	Request string `json:"request"`
	ID      int    `json:"id"`
}

type jsepDescription struct {
	Type string `json:"type"`
	SDP  string `json:"sdp"`
}

type viewer struct {
	peer  videoPeer
	open  bool
	epoch uint64
}

type janusSession struct {
	handles map[uint64]*viewer
}

type bridge struct {
	mu              sync.Mutex
	auth            string
	nextID          uint64
	sessions        map[uint64]*janusSession
	factory         peerFactory
	emit            func(any)
	status          func(string, string)
	frameIn         uint64
	frameOut        uint64
	lastFrameReport time.Time
	diagMu          sync.Mutex
	diagLast        map[string]time.Time
}

func newBridge(factory peerFactory, emit func(any), status func(string, string)) *bridge {
	var seed [8]byte
	if _, err := rand.Read(seed[:]); err != nil {
		panic("secure randomness unavailable")
	}
	return &bridge{
		nextID:   binary.BigEndian.Uint64(seed[:])%1_000_000_000 + 1_000_000,
		sessions: make(map[uint64]*janusSession),
		factory:  factory,
		emit:     emit,
		status:   status,
		diagLast: make(map[string]time.Time),
	}
}

func (b *bridge) init(authToken string) error {
	if authToken == "" || len(authToken) > 512 || strings.ContainsAny(authToken, "\r\n\x00") {
		return errors.New("invalid Obico authentication token")
	}
	b.mu.Lock()
	old := b.closeAllLocked()
	b.auth = authToken
	b.frameIn = 0
	b.frameOut = 0
	b.lastFrameReport = time.Time{}
	b.mu.Unlock()
	for _, peer := range old {
		peer.Close()
	}
	b.status("ready", "")
	return nil
}

func (b *bridge) diagnostic(stage string) {
	if !strings.HasPrefix(stage, "frames_") {
		b.diagMu.Lock()
		now := time.Now()
		if previous, seen := b.diagLast[stage]; seen && now.Sub(previous) < 5*time.Second {
			b.diagMu.Unlock()
			return
		}
		b.diagLast[stage] = now
		b.diagMu.Unlock()
	}
	b.status("diagnostic", stage)
}

func (b *bridge) close() {
	b.mu.Lock()
	old := b.closeAllLocked()
	b.auth = ""
	b.mu.Unlock()
	for _, peer := range old {
		peer.Close()
	}
}

func (b *bridge) closeAllLocked() []videoPeer {
	var old []videoPeer
	for _, session := range b.sessions {
		for _, handle := range session.handles {
			if handle.peer != nil {
				old = append(old, handle.peer)
			}
		}
	}
	b.sessions = make(map[uint64]*janusSession)
	return old
}

func (b *bridge) nextIDLocked() uint64 {
	b.nextID++
	// Janus identifiers travel through JavaScript numbers; stay below 2^53.
	if b.nextID >= 9_000_000_000_000_000 {
		b.nextID = 1_000_000
	}
	return b.nextID
}

func (b *bridge) handleJanus(raw []byte) error {
	if len(raw) == 0 || len(raw) > 256*1024 {
		return errors.New("invalid Janus message length")
	}
	var req janusRequest
	if err := json.Unmarshal(raw, &req); err != nil || req.Janus == "" || len(req.Transaction) > 128 {
		return errors.New("invalid Janus message")
	}
	b.mu.Lock()
	initialized := b.auth != ""
	b.mu.Unlock()
	if !initialized {
		return errors.New("Obico peer has not been initialized")
	}
	switch req.Janus {
	case "info":
		b.emit(map[string]any{
			"janus": "server_info", "transaction": req.Transaction,
			"name": "FabScreen Obico WebRTC", "version": 1, "version_string": "1",
			"data_channels": true, "ipv6": false,
			"plugins": map[string]any{streamingPlugin: map[string]any{
				"name": "FabScreen MJPEG streaming", "version": 1,
			}},
		})
	case "create":
		b.mu.Lock()
		id := b.nextIDLocked()
		b.sessions[id] = &janusSession{handles: make(map[uint64]*viewer)}
		b.mu.Unlock()
		b.emit(map[string]any{"janus": "success", "transaction": req.Transaction,
			"data": map[string]any{"id": id}})
		b.diagnostic("janus_create")
	case "attach":
		if req.Plugin != streamingPlugin {
			b.janusError(req, 460, "Plugin not available")
			return nil
		}
		b.mu.Lock()
		session := b.sessions[req.SessionID]
		if session == nil {
			b.mu.Unlock()
			b.janusError(req, 458, "No such session")
			return nil
		}
		id := b.nextIDLocked()
		session.handles[id] = &viewer{}
		b.mu.Unlock()
		b.emit(map[string]any{"janus": "success", "transaction": req.Transaction,
			"session_id": req.SessionID, "data": map[string]any{"id": id}})
		b.diagnostic("janus_attach")
	case "keepalive":
		if !b.sessionExists(req.SessionID) {
			b.janusError(req, 458, "No such session")
			return nil
		}
		b.ack(req)
	case "message":
		b.handleMessage(req)
	case "trickle":
		b.handleTrickle(req)
	case "detach", "hangup":
		peer, exists := b.detach(req.SessionID, req.HandleID, req.Janus == "detach")
		if !exists {
			b.janusError(req, 459, "No such handle")
			return nil
		}
		if peer != nil {
			peer.Close()
		}
		b.emit(map[string]any{"janus": "success", "transaction": req.Transaction,
			"session_id": req.SessionID})
		b.reportViewing()
	case "destroy":
		b.mu.Lock()
		session := b.sessions[req.SessionID]
		delete(b.sessions, req.SessionID)
		b.mu.Unlock()
		if session == nil {
			b.janusError(req, 458, "No such session")
			return nil
		}
		for _, handle := range session.handles {
			if handle.peer != nil {
				handle.peer.Close()
			}
		}
		b.emit(map[string]any{"janus": "success", "transaction": req.Transaction,
			"session_id": req.SessionID})
		b.diagnostic("janus_destroy")
		b.reportViewing()
	default:
		b.janusError(req, 457, "Unsupported Janus request")
	}
	return nil
}

func (b *bridge) sessionExists(id uint64) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.sessions[id] != nil
}

func (b *bridge) viewerSnapshot(sessionID, handleID uint64) (videoPeer, uint64, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	session := b.sessions[sessionID]
	if session == nil {
		return nil, 0, false
	}
	handle := session.handles[handleID]
	if handle == nil {
		return nil, 0, false
	}
	return handle.peer, handle.epoch, true
}

func (b *bridge) detach(sessionID, handleID uint64, remove bool) (videoPeer, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	session := b.sessions[sessionID]
	if session == nil || session.handles[handleID] == nil {
		return nil, false
	}
	handle := session.handles[handleID]
	peer := handle.peer
	handle.peer = nil
	handle.open = false
	handle.epoch++
	if remove {
		delete(session.handles, handleID)
	}
	return peer, true
}

func (b *bridge) handleMessage(req janusRequest) {
	currentPeer, _, exists := b.viewerSnapshot(req.SessionID, req.HandleID)
	if !exists {
		b.janusError(req, 459, "No such handle")
		return
	}
	var body mountRequest
	if err := json.Unmarshal(req.Body, &body); err != nil {
		b.janusError(req, 456, "Invalid plugin request")
		return
	}
	switch body.Request {
	case "list":
		b.pluginSuccess(req, map[string]any{"streaming": "list", "list": []any{mountInfo()}})
	case "info":
		if body.ID != streamID {
			b.pluginError(req, 415, "No such mountpoint")
			return
		}
		b.pluginSuccess(req, map[string]any{"streaming": "info", "info": mountInfo()})
		b.diagnostic("janus_stream_info")
	case "watch":
		if body.ID != streamID {
			b.pluginError(req, 415, "No such mountpoint")
			return
		}
		b.mu.Lock()
		count := 0
		for _, session := range b.sessions {
			for _, v := range session.handles {
				if v.peer != nil {
					count++
				}
			}
		}
		token := b.auth
		b.mu.Unlock()
		if count >= maxViewers {
			b.pluginError(req, 416, "Too many viewers")
			return
		}
		if currentPeer != nil {
			b.pluginError(req, 428, "Already watching")
			return
		}
		b.mu.Lock()
		session := b.sessions[req.SessionID]
		if session == nil || session.handles[req.HandleID] == nil ||
			session.handles[req.HandleID].peer != nil {
			b.mu.Unlock()
			b.pluginError(req, 428, "Already watching")
			return
		}
		handle := session.handles[req.HandleID]
		handle.epoch++
		epoch := handle.epoch
		b.mu.Unlock()
		peer, err := b.factory(token,
			func(c candidate) { b.candidateEvent(req.SessionID, req.HandleID, epoch, c) },
			func(open bool) { b.setViewerOpen(req.SessionID, req.HandleID, epoch, open) })
		if err != nil {
			b.diagnostic("peer_create_failed")
			b.pluginError(req, 499, "Could not create WebRTC peer")
			return
		}
		if diagnostic, ok := peer.(diagnosticPeer); ok {
			diagnostic.SetDiagnostic(b.diagnostic)
		}
		b.diagnostic("janus_watch")
		b.mu.Lock()
		if session := b.sessions[req.SessionID]; session != nil && session.handles[req.HandleID] == handle && handle.epoch == epoch {
			handle.peer = peer
		} else {
			b.mu.Unlock()
			peer.Close()
			return
		}
		b.mu.Unlock()
		offer, err := peer.Offer()
		if err != nil {
			b.diagnostic("peer_offer_failed")
			b.detach(req.SessionID, req.HandleID, false)
			peer.Close()
			b.pluginError(req, 499, "Could not negotiate WebRTC")
			return
		}
		b.ack(req)
		b.pluginEvent(req, "preparing", map[string]any{"type": "offer", "sdp": offer, "trickle": true})
		b.diagnostic("peer_offer_sent")
		peer.OfferSent()
	case "start":
		if currentPeer == nil {
			b.pluginError(req, 428, "Not watching")
			return
		}
		var jsep jsepDescription
		if err := json.Unmarshal(req.JSEP, &jsep); err != nil || jsep.Type != "answer" || jsep.SDP == "" {
			b.pluginError(req, 456, "Missing JSEP answer")
			return
		}
		if err := currentPeer.Answer(jsep.SDP); err != nil {
			b.diagnostic("peer_answer_failed")
			b.pluginError(req, 499, "Invalid JSEP answer")
			return
		}
		b.diagnostic("peer_answer_accepted")
		b.ack(req)
		b.pluginEvent(req, "starting", nil)
	case "stop":
		peer, _ := b.detach(req.SessionID, req.HandleID, false)
		if peer != nil {
			peer.Close()
		}
		b.ack(req)
		b.pluginEvent(req, "stopped", nil)
		b.reportViewing()
	default:
		b.pluginError(req, 412, "Unsupported streaming request")
	}
}

func (b *bridge) handleTrickle(req janusRequest) {
	currentPeer, _, exists := b.viewerSnapshot(req.SessionID, req.HandleID)
	if !exists {
		b.janusError(req, 459, "No such handle")
		return
	}
	if currentPeer == nil {
		b.ack(req) // Janus can receive early candidates before watch completes.
		return
	}
	var candidates []candidate
	if len(req.Candidates) > 0 {
		if err := json.Unmarshal(req.Candidates, &candidates); err != nil {
			b.janusError(req, 456, "Invalid ICE candidates")
			return
		}
	} else {
		var one candidate
		if err := json.Unmarshal(req.Candidate, &one); err != nil {
			b.janusError(req, 456, "Invalid ICE candidate")
			return
		}
		candidates = append(candidates, one)
	}
	if len(candidates) > 32 {
		b.janusError(req, 456, "Too many ICE candidates")
		return
	}
	for _, c := range candidates {
		if err := currentPeer.Trickle(c); err != nil {
			b.janusError(req, 456, "Invalid ICE candidate")
			return
		}
	}
	b.ack(req)
}

func (b *bridge) queueFrame(jpeg []byte) {
	b.mu.Lock()
	var peers []videoPeer
	for _, session := range b.sessions {
		for _, handle := range session.handles {
			if handle.open && handle.peer != nil {
				peers = append(peers, handle.peer)
			}
		}
	}
	b.frameIn++
	b.frameOut += uint64(len(peers))
	var report string
	if len(peers) > 0 && (b.lastFrameReport.IsZero() || time.Since(b.lastFrameReport) >= 15*time.Second) {
		report = fmt.Sprintf("frames_in=%d,queued=%d,viewers=%d", b.frameIn, b.frameOut, len(peers))
		b.lastFrameReport = time.Now()
	}
	b.mu.Unlock()
	if report != "" {
		b.diagnostic(report)
	}
	for _, peer := range peers {
		peer.QueueFrame(jpeg)
	}
}

func (b *bridge) setViewerOpen(sessionID, handleID, epoch uint64, open bool) {
	b.mu.Lock()
	session := b.sessions[sessionID]
	valid := session != nil && session.handles[handleID] != nil &&
		session.handles[handleID].epoch == epoch && session.handles[handleID].peer != nil
	if valid {
		session.handles[handleID].open = open
	}
	b.mu.Unlock()
	if !valid {
		return
	}
	if open {
		b.diagnostic("data_channel_open")
	} else {
		b.diagnostic("data_channel_closed")
	}
	b.reportViewing()
	if open {
		b.pluginEvent(janusRequest{SessionID: sessionID, HandleID: handleID}, "started", nil)
	}
}

func (b *bridge) reportViewing() {
	b.mu.Lock()
	streaming := false
	for _, session := range b.sessions {
		for _, handle := range session.handles {
			streaming = streaming || handle.open
		}
	}
	initialized := b.auth != ""
	b.mu.Unlock()
	if !initialized {
		return
	}
	if streaming {
		b.status("streaming", "")
	} else {
		b.status("idle", "")
	}
}

func (b *bridge) ack(req janusRequest) {
	b.emit(map[string]any{"janus": "ack", "transaction": req.Transaction, "session_id": req.SessionID})
}

func (b *bridge) janusError(req janusRequest, code int, reason string) {
	b.emit(map[string]any{"janus": "error", "transaction": req.Transaction,
		"session_id": req.SessionID, "error": map[string]any{"code": code, "reason": reason}})
}

func (b *bridge) pluginError(req janusRequest, code int, reason string) {
	b.pluginSuccess(req, map[string]any{"streaming": "event", "error_code": code, "error": reason})
}

func (b *bridge) pluginSuccess(req janusRequest, data map[string]any) {
	b.emit(map[string]any{"janus": "success", "transaction": req.Transaction,
		"session_id": req.SessionID, "sender": req.HandleID,
		"plugindata": map[string]any{"plugin": streamingPlugin, "data": data}})
}

func (b *bridge) pluginEvent(req janusRequest, state string, jsep map[string]any) {
	message := map[string]any{"janus": "event", "session_id": req.SessionID,
		"sender": req.HandleID,
		"plugindata": map[string]any{"plugin": streamingPlugin,
			"data": map[string]any{"streaming": "event", "result": map[string]any{"status": state}}}}
	if req.Transaction != "" {
		message["transaction"] = req.Transaction
	}
	if jsep != nil {
		message["jsep"] = jsep
	}
	b.emit(message)
}

func (b *bridge) candidateEvent(sessionID, handleID, epoch uint64, c candidate) {
	b.mu.Lock()
	session := b.sessions[sessionID]
	valid := session != nil && session.handles[handleID] != nil &&
		session.handles[handleID].epoch == epoch && session.handles[handleID].peer != nil
	b.mu.Unlock()
	if !valid {
		return
	}
	b.emit(map[string]any{"janus": "trickle", "session_id": sessionID,
		"sender": handleID, "candidate": c})
}

func mountInfo() map[string]any {
	return map[string]any{
		"id": streamID, "name": "FabScreen Camera", "description": "FabScreen Camera",
		"type": "rtp", "enabled": true,
		"media": []any{map[string]any{"mid": "0", "type": "data", "label": "MJPEG"}},
	}
}

func (b *bridge) describe() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return fmt.Sprintf("sessions=%d", len(b.sessions))
}

package main

import (
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/webrtc/v4"
)

type pionPeer struct {
	pc           *webrtc.PeerConnection
	dc           *webrtc.DataChannel
	onCandidate  func(candidate)
	onOpen       func(bool)
	frames       chan []byte
	done         chan struct{}
	closed       atomic.Bool
	open         atomic.Bool
	once         sync.Once
	mu           sync.Mutex
	offerSent    bool
	pending      []candidate
	answered     bool
	remoteICE    []candidate
	diagnosticMu sync.RWMutex
	diagnostic   func(string)
	sentFrames   uint64
}

func newPionPeer(authToken string, onCandidate func(candidate), onOpen func(bool)) (videoPeer, error) {
	// Obico's Janus configuration uses the linked printer token for the TURN
	// username and password. The token never leaves this process in logs or argv.
	configuration := webrtc.Configuration{ICEServers: []webrtc.ICEServer{
		{URLs: []string{"stun:stun.l.google.com:19302"}},
		{URLs: []string{"turn:turn.obico.io:80?transport=tcp"},
			Username: authToken, Credential: authToken},
	}}
	return newPionPeerConfigured(configuration, onCandidate, onOpen)
}

func newPionPeerConfigured(configuration webrtc.Configuration, onCandidate func(candidate), onOpen func(bool)) (videoPeer, error) {
	pc, err := webrtc.NewPeerConnection(configuration)
	if err != nil {
		return nil, err
	}
	dc, err := pc.CreateDataChannel("JanusDataChannel", nil)
	if err != nil {
		pc.Close()
		return nil, err
	}
	p := &pionPeer{pc: pc, dc: dc, onCandidate: onCandidate, onOpen: onOpen,
		frames: make(chan []byte, 1), done: make(chan struct{})}
	dc.OnOpen(func() {
		if !p.closed.Load() && p.open.CompareAndSwap(false, true) {
			p.onOpen(true)
		}
	})
	dc.OnClose(func() { p.markClosed() })
	pc.OnICEConnectionStateChange(func(state webrtc.ICEConnectionState) {
		switch state {
		case webrtc.ICEConnectionStateChecking:
			p.emitDiagnostic("ice_checking")
		case webrtc.ICEConnectionStateConnected, webrtc.ICEConnectionStateCompleted:
			p.emitDiagnostic("ice_connected")
		case webrtc.ICEConnectionStateDisconnected:
			p.emitDiagnostic("ice_disconnected")
		case webrtc.ICEConnectionStateFailed:
			p.emitDiagnostic("ice_failed")
		}
	})
	pc.OnConnectionStateChange(p.connectionStateChanged)
	pc.OnICECandidate(func(ice *webrtc.ICECandidate) {
		if p.closed.Load() {
			return
		}
		c := candidate{Completed: ice == nil}
		if ice != nil {
			init := ice.ToJSON()
			c = candidate{Candidate: init.Candidate,
				SDPMid: init.SDPMid, SDPMLineIndex: init.SDPMLineIndex}
		}
		p.mu.Lock()
		if !p.offerSent {
			if len(p.pending) < 64 {
				p.pending = append(p.pending, c)
			}
			p.mu.Unlock()
			return
		}
		p.mu.Unlock()
		p.onCandidate(c)
	})
	go p.sendFrames()
	return p, nil
}

func (p *pionPeer) connectionStateChanged(state webrtc.PeerConnectionState) {
	if state == webrtc.PeerConnectionStateConnected &&
		p.dc.ReadyState() == webrtc.DataChannelStateOpen &&
		!p.closed.Load() && p.open.CompareAndSwap(false, true) {
		// ICE can recover without a second DataChannel OnOpen callback.
		p.onOpen(true)
	}
	if state == webrtc.PeerConnectionStateFailed ||
		state == webrtc.PeerConnectionStateClosed ||
		state == webrtc.PeerConnectionStateDisconnected {
		p.markClosed()
	}
}

func (p *pionPeer) SetDiagnostic(callback func(string)) {
	p.diagnosticMu.Lock()
	p.diagnostic = callback
	p.diagnosticMu.Unlock()
}

func (p *pionPeer) emitDiagnostic(stage string) {
	p.diagnosticMu.RLock()
	callback := p.diagnostic
	p.diagnosticMu.RUnlock()
	if callback != nil {
		callback(stage)
	}
}

func (p *pionPeer) Offer() (string, error) {
	offer, err := p.pc.CreateOffer(nil)
	if err != nil {
		return "", err
	}
	if err := p.pc.SetLocalDescription(offer); err != nil {
		return "", err
	}
	return offer.SDP, nil
}

func (p *pionPeer) OfferSent() {
	p.mu.Lock()
	p.offerSent = true
	pending := p.pending
	p.pending = nil
	p.mu.Unlock()
	for _, c := range pending {
		if !p.closed.Load() {
			p.onCandidate(c)
		}
	}
}

func (p *pionPeer) Answer(sdp string) error {
	if p.closed.Load() || len(sdp) == 0 || len(sdp) > 256*1024 {
		return errors.New("invalid JSEP answer")
	}
	if err := p.pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: sdp}); err != nil {
		return err
	}
	p.mu.Lock()
	p.answered = true
	pending := p.remoteICE
	p.remoteICE = nil
	p.mu.Unlock()
	for _, c := range pending {
		if err := p.addICE(c); err != nil {
			return err
		}
	}
	return nil
}

func (p *pionPeer) Trickle(c candidate) error {
	if p.closed.Load() {
		return errors.New("peer closed")
	}
	if c.Completed {
		return nil
	}
	if len(c.Candidate) == 0 || len(c.Candidate) > 4096 {
		return errors.New("invalid ICE candidate")
	}
	p.mu.Lock()
	if !p.answered {
		if len(p.remoteICE) >= 64 {
			p.mu.Unlock()
			return errors.New("too many early ICE candidates")
		}
		p.remoteICE = append(p.remoteICE, c)
		p.mu.Unlock()
		return nil
	}
	p.mu.Unlock()
	return p.addICE(c)
}

func (p *pionPeer) addICE(c candidate) error {
	return p.pc.AddICECandidate(webrtc.ICECandidateInit{
		Candidate: c.Candidate, SDPMid: c.SDPMid, SDPMLineIndex: c.SDPMLineIndex,
	})
}

func (p *pionPeer) QueueFrame(jpeg []byte) {
	if !p.open.Load() || p.closed.Load() {
		return
	}
	select {
	case p.frames <- jpeg:
	default:
		// A slow viewer receives the newest image, never an unbounded backlog.
		select {
		case <-p.frames:
		default:
		}
		select {
		case p.frames <- jpeg:
		default:
		}
	}
}

func (p *pionPeer) sendFrames() {
	for {
		select {
		case <-p.done:
			return
		case jpeg := <-p.frames:
			if !p.open.Load() || p.closed.Load() {
				continue
			}
			messages, err := mjpegMessages(jpeg)
			if err != nil {
				continue
			}
			for _, message := range messages {
				if !p.waitForCapacity() {
					p.emitDiagnostic("frame_backpressure_timeout")
					p.Close() // Prevent a partial frame corrupting subsequent frames.
					return
				}
				if err := p.dc.Send(message); err != nil {
					p.emitDiagnostic("frame_send_failed")
					p.Close()
					return
				}
			}
			p.sentFrames++
			if p.sentFrames == 1 || p.sentFrames%50 == 0 {
				p.emitDiagnostic(fmt.Sprintf("frames_sent=%d", p.sentFrames))
			}
		}
	}
}

func (p *pionPeer) waitForCapacity() bool {
	deadline := time.Now().Add(2 * time.Second)
	for p.dc.BufferedAmount() > maxBufferedBytes {
		if p.closed.Load() || time.Now().After(deadline) {
			return false
		}
		select {
		case <-p.done:
			return false
		case <-time.After(5 * time.Millisecond):
		}
	}
	return !p.closed.Load()
}

func (p *pionPeer) markClosed() {
	if p.open.CompareAndSwap(true, false) {
		p.onOpen(false)
	}
}

func (p *pionPeer) Close() {
	p.once.Do(func() {
		p.closed.Store(true)
		close(p.done)
		p.markClosed()
		_ = p.pc.Close()
	})
}

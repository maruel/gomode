// Provides real Opus WebRTC media and transport observations for Android voice acceptance tests.

// Copyright 2026 Marc-Antoine Ruel. All Rights Reserved. Use of this
// source code is governed by the Apache v2 license that can be found in the
// LICENSE file.

package main

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
	voiceapi "github.com/maruel/gomode/voicegateway/api"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
	"github.com/maruel/gopus"
	"github.com/pion/rtcp"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
)

type fixtureStats struct {
	FrontendPreparations int               `json:"frontendPreparations"`
	FrontendPublished    int               `json:"frontendPublished"`
	MCPToolLists         int               `json:"mcpToolLists"`
	TextActive           int               `json:"textActive"`
	TextSetups           int               `json:"textSetups"`
	TextTurns            int               `json:"textTurns"`
	RTCActive            int               `json:"rtcActive"`
	RTCOffers            int               `json:"rtcOffers"`
	RTCSetups            int               `json:"rtcSetups"`
	MicFrames            int               `json:"micFrames"`
	AssistantPackets     int               `json:"assistantPackets"`
	ReceiverReports      int               `json:"receiverReports"`
	ToolResults          []json.RawMessage `json:"toolResults"`
}

type mediaPeer struct {
	fixture     *fixture
	id          string
	pc          *webrtc.PeerConnection
	ctx         context.Context
	cancel      context.CancelFunc
	closed      sync.Once
	dataChannel *webrtc.DataChannel
}

func (f *fixture) handleStats(w http.ResponseWriter, _ *http.Request) {
	f.mu.Lock()
	stats := f.stats
	stats.ToolResults = append([]json.RawMessage(nil), f.stats.ToolResults...)
	f.mu.Unlock()
	writeJSON(w, stats)
}

func (f *fixture) handleConfig(w http.ResponseWriter, r *http.Request) {
	var cfg struct {
		SettingsDelayMS int  `json:"settingsDelayMs"`
		FrontendBlocked bool `json:"frontendBlocked"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&cfg); err != nil || cfg.SettingsDelayMS < 0 || cfg.SettingsDelayMS > 30_000 {
		http.Error(w, "settingsDelayMs must be between 0 and 30000", http.StatusBadRequest)
		return
	}
	f.mu.Lock()
	f.settingsDelay = time.Duration(cfg.SettingsDelayMS) * time.Millisecond
	if f.frontendBlocked != cfg.FrontendBlocked {
		f.frontendBlocked = cfg.FrontendBlocked
		close(f.frontendChanged)
		f.frontendChanged = make(chan struct{})
	}
	f.mu.Unlock()
	writeJSON(w, map[string]bool{"ok": true})
}

func (f *fixture) handleFrontend(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	f.stats.FrontendPreparations++
	for f.frontendBlocked {
		changed := f.frontendChanged
		f.mu.Unlock()
		select {
		case <-changed:
		case <-r.Context().Done():
			return
		}
		f.mu.Lock()
	}
	f.mu.Unlock()
	writeJSON(w, []any{})
}

func (f *fixture) handleOffer(w http.ResponseWriter, r *http.Request) {
	var req voicev1.VoiceRTCOfferReq
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
		http.Error(w, "invalid offer", http.StatusBadRequest)
		return
	}
	answer, id, err := f.answer(r.Context(), req.SDP)
	if err != nil {
		f.log.WarnContext(r.Context(), "offer failed", "err", err)
		voiceapi.WriteError(w, http.StatusBadRequest, voiceapi.CodeVoiceOfferFailed, "invalid fixture offer")
		return
	}
	writeJSON(w, voicev1.VoiceRTCAnswerResp{SDP: answer, SessionID: id})
}

func (f *fixture) handleClose(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	peer := f.peers[r.PathValue("sessionID")]
	f.mu.Unlock()
	if peer != nil {
		peer.close()
	}
	writeJSON(w, map[string]string{"status": "ok"})
}

func (f *fixture) answer(ctx context.Context, offer string) (string, string, error) {
	var settings webrtc.SettingEngine
	settings.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	settings.SetIncludeLoopbackCandidate(true)
	if f.rtcIP != "" {
		// The stock emulator reaches host loopback through its documented 10.0.2.2 alias.
		// Unlike HTTP, adb reverse cannot forward ICE's bidirectional UDP traffic.
		if err := settings.SetICEAddressRewriteRules(webrtc.ICEAddressRewriteRule{
			External: []string{f.rtcIP}, AsCandidateType: webrtc.ICECandidateTypeHost,
			Mode: webrtc.ICEAddressRewriteReplace,
		}); err != nil {
			return "", "", err
		}
	}
	pc, err := webrtc.NewAPI(webrtc.WithSettingEngine(settings)).NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		return "", "", err
	}
	// A WebRTC session outlives its HTTP offer exchange; close/call teardown owns cancellation.
	mediaCtx, cancel := context.WithCancel(context.WithoutCancel(ctx))
	f.mu.Lock()
	f.stats.RTCOffers++
	id := fmt.Sprintf("fixture-%d", f.stats.RTCOffers)
	peer := &mediaPeer{fixture: f, id: id, pc: pc, ctx: mediaCtx, cancel: cancel}
	if f.peers == nil {
		f.peers = make(map[string]*mediaPeer)
	}
	f.peers[id] = peer
	f.stats.RTCActive++
	f.mu.Unlock()
	failed := true
	defer func() {
		if failed {
			peer.close()
		}
	}()
	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		if state == webrtc.PeerConnectionStateFailed || state == webrtc.PeerConnectionStateClosed {
			// PeerConnection.Close can wait for its callbacks. Finish closure outside that callback.
			go peer.close()
		}
	})
	pc.OnTrack(peer.receiveMic)
	pc.OnICEConnectionStateChange(func(state webrtc.ICEConnectionState) {
		if state == webrtc.ICEConnectionStateDisconnected {
			go peer.close()
		}
	})
	pc.OnDataChannel(peer.handleMessages)
	track, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{
		MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2,
	}, "fixture-assistant", "fixture")
	if err != nil {
		return "", "", err
	}
	sender, err := pc.AddTrack(track)
	if err != nil {
		return "", "", err
	}
	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: offer}); err != nil {
		return "", "", err
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		return "", "", err
	}
	gathered := webrtc.GatheringCompletePromise(pc)
	if err := pc.SetLocalDescription(answer); err != nil {
		return "", "", err
	}
	select {
	case <-gathered:
	case <-ctx.Done():
		return "", "", ctx.Err()
	}
	go peer.receiveReports(sender)
	go peer.sendTone(track)
	failed = false
	return pc.LocalDescription().SDP, id, nil
}

func (p *mediaPeer) close() {
	p.closed.Do(func() {
		p.cancel()
		if err := p.pc.Close(); err != nil {
			p.fixture.log.Warn("close fixture peer", "err", err)
		}
		p.fixture.mu.Lock()
		delete(p.fixture.peers, p.id)
		p.fixture.stats.RTCActive--
		p.fixture.mu.Unlock()
	})
}

func (p *mediaPeer) handleMessages(dc *webrtc.DataChannel) {
	p.fixture.mu.Lock()
	p.dataChannel = dc
	p.fixture.mu.Unlock()
	dc.OnClose(func() { go p.close() })
	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		var env voicev1.MessageEnvelope
		if err := json.Unmarshal(msg.Data, &env); err != nil {
			p.fixture.log.Warn("decode RTC message", "err", err)
			return
		}
		if env.Kind == voicev1.MessageKindToolResult {
			p.fixture.recordToolResult(msg.Data)
		}
		if env.Kind != voicev1.MessageKindSessionSetup {
			return
		}
		p.fixture.mu.Lock()
		p.fixture.stats.RTCSetups++
		p.fixture.mu.Unlock()
		for _, message := range []any{
			voicev1.SessionReady{Kind: voicev1.MessageKindSessionReady},
			voicev1.TranscriptDelta{
				Kind: voicev1.MessageKindTranscriptDelta, Speaker: voicev1.SpeakerUser,
				Text: "How is the service doing?",
			},
			voicev1.TranscriptDelta{
				Kind: voicev1.MessageKindTranscriptDelta, Speaker: voicev1.SpeakerAssistant,
				Text: "Your service is ready.",
			},
		} {
			data, err := json.Marshal(message)
			if err != nil {
				p.fixture.log.Error("encode RTC message", "err", err)
				return
			}
			if err := dc.SendText(string(data)); err != nil {
				p.fixture.log.Warn("send RTC message", "err", err)
				return
			}
		}
	})
}

func (p *mediaPeer) receiveMic(track *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
	decoder, err := gopus.NewDecoder(48000, 1)
	if err != nil {
		p.fixture.log.Error("create mic decoder", "err", err)
		return
	}
	pcm := make([]int16, 5760)
	for {
		packet, _, err := track.ReadRTP()
		if err != nil {
			return
		}
		n, err := decoder.Decode(packet.Payload, pcm)
		if err != nil {
			p.fixture.log.Warn("decode microphone Opus", "err", err)
			continue
		}
		if n > 0 {
			p.fixture.mu.Lock()
			p.fixture.stats.MicFrames++
			p.fixture.mu.Unlock()
		}
	}
}

func (p *mediaPeer) receiveReports(sender *webrtc.RTPSender) {
	for {
		packets, _, err := sender.ReadRTCP()
		if err != nil {
			return
		}
		for _, packet := range packets {
			report, ok := packet.(*rtcp.ReceiverReport)
			if !ok {
				continue
			}
			for _, reception := range report.Reports {
				if reception.LastSequenceNumber > 0 {
					p.fixture.mu.Lock()
					p.fixture.stats.ReceiverReports++
					p.fixture.mu.Unlock()
				}
			}
		}
	}
}

func (p *mediaPeer) sendTone(track *webrtc.TrackLocalStaticSample) {
	encoder, err := gopus.NewEncoder(48000, 1, gopus.AppVoIP)
	if err != nil {
		p.fixture.log.Error("create assistant encoder", "err", err)
		return
	}
	pcm := make([]int16, 960)
	for i := range pcm {
		pcm[i] = int16(6000 * math.Sin(float64(i)*2*math.Pi*440/48000))
	}
	encoded := make([]byte, 4000)
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-p.ctx.Done():
			return
		case <-ticker.C:
			if p.pc.ConnectionState() != webrtc.PeerConnectionStateConnected {
				continue
			}
			n, err := encoder.Encode(pcm, encoded)
			if err != nil {
				p.fixture.log.Error("encode assistant Opus", "err", err)
				return
			}
			if err := track.WriteSample(media.Sample{Data: encoded[:n], Duration: 20 * time.Millisecond}); err != nil {
				p.fixture.log.Warn("write assistant audio", "err", err)
				return
			}
			p.fixture.mu.Lock()
			p.fixture.stats.AssistantPackets++
			p.fixture.mu.Unlock()
		}
	}
}

func (f *fixture) recordToolResult(data []byte) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.stats.ToolResults) == 20 {
		f.stats.ToolResults = f.stats.ToolResults[1:]
	}
	f.stats.ToolResults = append(f.stats.ToolResults, append(json.RawMessage(nil), data...))
}

func (f *fixture) handleTool(w http.ResponseWriter, r *http.Request) {
	var call voicev1.ToolCall
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&call); err != nil {
		http.Error(w, "invalid tool call", http.StatusBadRequest)
		return
	}
	call.Kind = voicev1.MessageKindToolCall
	data, err := json.Marshal(call)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	f.mu.Lock()
	text := f.textPeer
	var channel *webrtc.DataChannel
	for _, peer := range f.peers {
		if peer.dataChannel != nil {
			channel = peer.dataChannel
			break
		}
	}
	f.mu.Unlock()
	switch {
	case channel != nil:
		err = channel.SendText(string(data))
	case text != nil:
		err = text.Write(r.Context(), websocket.MessageText, data)
	default:
		http.Error(w, "no connected transport", http.StatusConflict)
		return
	}
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeJSON(w, map[string]bool{"ok": true})
}

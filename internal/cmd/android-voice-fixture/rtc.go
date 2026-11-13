// Provides real Opus WebRTC media and transport observations for Android voice acceptance tests.

// Copyright 2026 Marc-Antoine Ruel. All Rights Reserved. Use of this
// source code is governed by the Apache v2 license that can be found in the
// LICENSE file.

package main

import (
	"context"
	"encoding/json"
	"fmt"
	"maps"
	"math"
	"net/http"
	"slices"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
	voiceapi "github.com/maruel/gomode/voicegateway/api"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
	"github.com/pion/rtcp"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
	"github.com/tphakala/go-opus/opus"
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
	// RTCSetup is the most recent WebRTC session.setup frame.
	RTCSetup json.RawMessage `json:"rtcSetup"`
	// RTCUserMessages lists user.message texts received over WebRTC.
	RTCUserMessages []string `json:"rtcUserMessages"`
	RTCTurnCancels  int      `json:"rtcTurnCancels"`

	// MicFrameSamples counts decoded microphone packets by samples per packet,
	// which is the sender's frame size at the 48kHz decode rate.
	MicFrameSamples map[string]int `json:"micFrameSamples"`
}

type mediaPeer struct {
	fixture     *fixture
	id          string
	pc          *webrtc.PeerConnection
	ctx         context.Context
	cancel      context.CancelFunc
	closed      sync.Once
	dataChannel *webrtc.DataChannel
	mu          sync.Mutex
	closing     bool
	workers     sync.WaitGroup
	lifecycle   sync.WaitGroup
	done        chan struct{}
	// speech holds the stage owners session.setup selected; nil before setup.
	speech *voicev2.SpeechConfig
	// held is true while a "hold" reply waits for turn.cancel.
	held bool
	// clientOutput silences gateway audio while the client owns speech.
	clientOutput atomic.Bool
}

func (f *fixture) handleStats(w http.ResponseWriter, _ *http.Request) {
	f.mu.Lock()
	stats := f.stats
	stats.ToolResults = append([]json.RawMessage(nil), f.stats.ToolResults...)
	stats.MicFrameSamples = maps.Clone(f.stats.MicFrameSamples)
	stats.RTCUserMessages = append([]string{}, f.stats.RTCUserMessages...)
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
	var req voicev2.VoiceRTCOfferReq
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
	writeJSON(w, voicev2.VoiceRTCAnswerResp{SDP: answer, SessionID: id})
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
	mediaCtx, cancel := context.WithCancel(context.WithoutCancel(ctx)) //nolint:gosec // The peer owns cancellation beyond the offer request.
	f.mu.Lock()
	f.stats.RTCOffers++
	id := fmt.Sprintf("fixture-%d", f.stats.RTCOffers)
	peer := &mediaPeer{fixture: f, id: id, pc: pc, ctx: mediaCtx, cancel: cancel, done: make(chan struct{})}
	peer.lifecycle.Go(func() {
		<-mediaCtx.Done()
		peer.mu.Lock()
		peer.closing = true
		peer.mu.Unlock()
		if err := pc.Close(); err != nil {
			f.log.Warn("close fixture peer", "err", err)
		}
		peer.workers.Wait()
		f.mu.Lock()
		delete(f.peers, id)
		f.stats.RTCActive--
		f.mu.Unlock()
		close(peer.done)
	})
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
			peer.requestClose()
		}
	})
	pc.OnTrack(peer.receiveMic)
	pc.OnICEConnectionStateChange(func(state webrtc.ICEConnectionState) {
		if state == webrtc.ICEConnectionStateDisconnected {
			peer.requestClose()
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
	peer.mu.Lock()
	if !peer.closing {
		peer.workers.Go(func() { peer.receiveReports(sender) })
		peer.workers.Go(func() { peer.sendTone(track) })
	}
	peer.mu.Unlock()
	failed = false
	return pc.LocalDescription().SDP, id, nil
}

func (p *mediaPeer) close() {
	p.requestClose()
	select {
	case <-p.done:
		p.lifecycle.Wait()
	case <-time.After(5 * time.Second):
		p.fixture.log.Warn("timed out closing fixture peer", "session", p.id)
	}
}

func (p *mediaPeer) requestClose() {
	p.closed.Do(p.cancel)
}

// handleMessages answers the data channel. A session without client stages
// gets a canned greeting: a user transcript and an assistant transcript. With a
// client stage, gateway recognition produces that user transcript and a reply,
// while client recognition waits for user.message. Replies stream
// assistant.text.delta for client speech and transcripts between
// speech.started and speech.ended for gateway speech. The user text "hold"
// starts a reply that only turn.cancel ends, with an interrupted message.
func (p *mediaPeer) handleMessages(dc *webrtc.DataChannel) {
	p.fixture.mu.Lock()
	p.dataChannel = dc
	p.fixture.mu.Unlock()
	dc.OnClose(p.requestClose)
	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		var env voicev2.MessageEnvelope
		if err := json.Unmarshal(msg.Data, &env); err != nil {
			p.fixture.log.Warn("decode RTC message", "err", err)
			return
		}
		switch env.Kind {
		case voicev2.MessageKindToolResult:
			p.fixture.recordToolResult(msg.Data)
		case voicev2.MessageKindSessionSetup:
			p.handleSetup(dc, msg.Data)
		case voicev2.MessageKindUserMessage:
			var user voicev2.UserMessage
			if err := json.Unmarshal(msg.Data, &user); err != nil {
				p.fixture.log.Warn("decode user message", "err", err)
				return
			}
			p.fixture.mu.Lock()
			p.fixture.stats.RTCUserMessages = append(p.fixture.stats.RTCUserMessages, user.Text)
			speech := p.speech
			p.held = user.Text == "hold"
			p.fixture.mu.Unlock()
			if speech == nil {
				return
			}
			if user.Text == "hold" {
				p.send(dc, p.replyStart(speech, "Holding the reply.")...)
				return
			}
			p.send(dc, p.reply(speech, "Your service is ready.")...)
		case voicev2.MessageKindTurnCancel:
			p.fixture.mu.Lock()
			p.fixture.stats.RTCTurnCancels++
			held := p.held
			p.held = false
			p.fixture.mu.Unlock()
			if held {
				p.send(dc, voicev2.Interrupted{Kind: voicev2.MessageKindInterrupted, Source: voicev2.InterruptSourceUser})
			}
		default:
		}
	})
}

func (p *mediaPeer) handleSetup(dc *webrtc.DataChannel, data []byte) {
	var setup voicev2.SessionSetup
	if err := json.Unmarshal(data, &setup); err != nil {
		p.fixture.log.Warn("decode session setup", "err", err)
		return
	}
	speech := setup.Speech
	if speech != nil && speech.Input == voicev2.SpeechLocationGateway && speech.Output == voicev2.SpeechLocationGateway {
		speech = nil
	}
	p.clientOutput.Store(speech != nil && speech.Output == voicev2.SpeechLocationClient)
	p.fixture.mu.Lock()
	p.fixture.stats.RTCSetups++
	p.fixture.stats.RTCSetup = append(json.RawMessage(nil), data...)
	p.speech = speech
	p.held = false
	p.fixture.mu.Unlock()
	userTranscript := voicev2.TranscriptDelta{
		Kind: voicev2.MessageKindTranscriptDelta, Speaker: voicev2.SpeakerUser,
		Text: "How is the service doing?",
	}
	switch {
	case speech == nil:
		p.send(dc,
			voicev2.SessionReady{Kind: voicev2.MessageKindSessionReady},
			userTranscript,
			voicev2.TranscriptDelta{
				Kind: voicev2.MessageKindTranscriptDelta, Speaker: voicev2.SpeakerAssistant,
				Text: "Your service is ready.",
			},
		)
	case speech.Input == voicev2.SpeechLocationGateway:
		p.send(dc, append([]any{voicev2.SessionReady{Kind: voicev2.MessageKindSessionReady}, userTranscript},
			p.reply(speech, "Your service is ready.")...)...)
	default:
		p.send(dc, voicev2.SessionReady{Kind: voicev2.MessageKindSessionReady})
	}
}

// replyStart starts an assistant turn that has not finished speaking.
func (p *mediaPeer) replyStart(speech *voicev2.SpeechConfig, text string) []any {
	thinking := voicev2.TurnStatus{Kind: voicev2.MessageKindTurnStatus, State: voicev2.TurnStateThinking}
	if speech.Output == voicev2.SpeechLocationClient {
		return []any{thinking, voicev2.AssistantTextDelta{Kind: voicev2.MessageKindAssistantTextDelta, Text: text}}
	}
	return []any{
		thinking,
		voicev2.SpeechStarted{Kind: voicev2.MessageKindSpeechStarted, Speaker: voicev2.SpeakerAssistant},
		voicev2.TranscriptDelta{Kind: voicev2.MessageKindTranscriptDelta, Speaker: voicev2.SpeakerAssistant, Text: text},
	}
}

// reply is a complete assistant turn.
func (p *mediaPeer) reply(speech *voicev2.SpeechConfig, text string) []any {
	messages := p.replyStart(speech, text)
	if speech.Output == voicev2.SpeechLocationGateway {
		messages = append(messages, voicev2.SpeechEnded{Kind: voicev2.MessageKindSpeechEnded, Speaker: voicev2.SpeakerAssistant})
	}
	return append(messages, voicev2.TurnStatus{Kind: voicev2.MessageKindTurnStatus, State: voicev2.TurnStateIdle})
}

func (p *mediaPeer) send(dc *webrtc.DataChannel, messages ...any) {
	for _, message := range messages {
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
}

func (p *mediaPeer) receiveMic(track *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
	decoder, err := opus.NewDecoder(48000, 1)
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
			if p.fixture.stats.MicFrameSamples == nil {
				p.fixture.stats.MicFrameSamples = map[string]int{}
			}
			p.fixture.stats.MicFrameSamples[strconv.Itoa(n)]++
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
	encoder, err := opus.NewEncoder(opus.EncoderConfig{
		SampleRate: 48000,
		Channels:   1,
		Bitrate:    40000,
		// Match the gateway encoder: libopus' constrained VBR and complexity.
		ConstrainedVBR: true,
		Complexity:     9,
	})
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
			// Client speech leaves the gateway's audio track silent.
			if p.pc.ConnectionState() != webrtc.PeerConnectionStateConnected || p.clientOutput.Load() {
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
	var call voicev2.ToolCall
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&call); err != nil {
		http.Error(w, "invalid tool call", http.StatusBadRequest)
		return
	}
	call.Kind = voicev2.MessageKindToolCall
	data, err := json.Marshal(call)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	f.mu.Lock()
	text := f.textPeer
	channel := f.rtcChannelLocked()
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

// rtcChannelLocked returns a connected WebRTC data channel, or nil. f.mu must be held.
func (f *fixture) rtcChannelLocked() *webrtc.DataChannel {
	for _, peer := range f.peers {
		if peer.dataChannel != nil {
			return peer.dataChannel
		}
	}
	return nil
}

// handleInterrupt reports user speech interrupting the current WebRTC turn,
// as gateway recognition does on barge-in.
func (f *fixture) handleInterrupt(w http.ResponseWriter, _ *http.Request) {
	f.mu.Lock()
	channel := f.rtcChannelLocked()
	f.mu.Unlock()
	if channel == nil {
		http.Error(w, "no connected WebRTC session", http.StatusConflict)
		return
	}
	data, err := json.Marshal(voicev2.Interrupted{Kind: voicev2.MessageKindInterrupted, Source: voicev2.InterruptSourceUser})
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if err := channel.SendText(string(data)); err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeJSON(w, map[string]bool{"ok": true})
}

// handleDrop closes every WebRTC peer without the client's close request, as a
// lost gateway does. The client sees ICE disconnect and recovers.
func (f *fixture) handleDrop(w http.ResponseWriter, _ *http.Request) {
	f.mu.Lock()
	peers := slices.Collect(maps.Values(f.peers))
	f.mu.Unlock()
	if len(peers) == 0 {
		http.Error(w, "no WebRTC session", http.StatusConflict)
		return
	}
	for _, peer := range peers {
		peer.close()
	}
	writeJSON(w, map[string]bool{"ok": true})
}

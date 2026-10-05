// Tests for voice gateway transcript and tool activity log persistence.

package voicertc

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	activitydata "github.com/maruel/gomode/voicegateway/voicertc/data"

	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

func TestActivityLog(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	log, err := openActivityLog(dir, "session-1")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := log.close(); err != nil {
			t.Fatal(err)
		}
	})

	for _, event := range []struct {
		source  activitydata.Source
		message string
	}{
		{activitydata.SourceClient, `{"kind":"session.setup","context":{"systemInstruction":"Initial context","text":"Current task"},"extension":{"future":[true,null,42]}}`},
		{activitydata.SourceGateway, `{"kind":"transcript.delta","speaker":"user","text":"hello"}`},
		{activitydata.SourceGateway, `{"kind":"tool.call","id":"call-1","name":"tasks_list","args":{}}`},
		{activitydata.SourceClient, `{"kind":"tool.result","id":"call-1","name":"tasks_list","result":{"tasks":[]}}`},
		{activitydata.SourceGateway, `{"kind":"speech.ended","speaker":"assistant"}`},
	} {
		if err := log.record(event.source, []byte(event.message)); err != nil {
			t.Fatal(err)
		}
	}

	if err := log.close(); err != nil {
		t.Fatal(err)
	}
	paths, err := filepath.Glob(filepath.Join(dir, "????-??-??-??-??-session-1.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	if len(paths) != 1 {
		t.Fatalf("activity log paths = %v, want one timestamped session log", paths)
	}
	data, err := os.ReadFile(paths[0])
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(data, []byte(`"src":"client"`)) || bytes.Contains(data, []byte(`"dir"`)) {
		t.Fatalf("log fields = %s, want src without dir", data)
	}
	var records []activitydata.Record
	for line := range bytes.SplitSeq(data, []byte{'\n'}) {
		if len(line) == 0 {
			continue
		}
		var fields map[string]json.RawMessage
		if err := json.Unmarshal(line, &fields); err != nil {
			t.Fatal(err)
		}
		if len(fields) != 4 || fields["ts"] == nil || fields["src"] == nil || fields["kind"] == nil || fields["msg"] == nil {
			t.Fatalf("activity record shape = %s", line)
		}
		var record activitydata.Record
		if err := json.Unmarshal(line, &record); err != nil {
			t.Fatal(err)
		}
		if record.Timestamp.IsZero() || record.Timestamp.Nanosecond()%1_000_000 != 0 {
			t.Fatalf("timestamp = %s, want milliseconds", record.Timestamp)
		}
		records = append(records, record)
	}
	if len(records) != 4 {
		t.Fatalf("records = %d, want 4", len(records))
	}
	if records[0].Source != activitydata.SourceClient || records[0].Kind != string(voicev1.MessageKindSessionSetup) || !bytes.Contains(records[0].Message, []byte("Initial context")) {
		t.Fatalf("first record = %+v, want initial context", records[0])
	}
	if !bytes.Contains(records[0].Message, []byte(`"extension":{"future":[true,null,42]}`)) {
		t.Fatalf("opaque extension lost: %s", records[0].Message)
	}
	if records[1].Kind != string(voicev1.MessageKindTranscriptDelta) {
		t.Fatalf("second record = %+v, want user transcript", records[1])
	}
	if records[2].Kind != string(voicev1.MessageKindToolCall) || records[3].Kind != string(voicev1.MessageKindToolResult) || records[3].Source != activitydata.SourceClient {
		t.Fatalf("tool records = %+v, want call then client result", records[2:])
	}
}

// Tests deadline-bounded Server-Sent Event frame delivery.

package sse

import (
	"errors"
	"net"
	"net/http"
	"os"
	"sync"
	"testing"
	"time"
)

type pipeWriter struct {
	net.Conn

	started chan struct{}
}

func (w pipeWriter) Header() http.Header { return make(http.Header) }
func (w pipeWriter) WriteHeader(int)     {}
func (w pipeWriter) Flush()              {}
func (w pipeWriter) Write(b []byte) (int, error) {
	select {
	case w.started <- struct{}{}:
	default:
	}
	return w.Conn.Write(b)
}

func TestStream(t *testing.T) {
	t.Parallel()
	t.Run("Writef", func(t *testing.T) {
		t.Parallel()
		t.Run("deadline", func(t *testing.T) {
			t.Parallel()
			server, client := net.Pipe()
			t.Cleanup(func() { _ = server.Close() })
			t.Cleanup(func() { _ = client.Close() })
			started := make(chan struct{}, 1)
			stream := New(pipeWriter{Conn: server, started: started})
			errs := make(chan error, 1)
			var wg sync.WaitGroup
			wg.Go(func() { errs <- stream.Writef("data: blocked\n\n") })
			select {
			case <-started:
			case <-time.After(time.Second):
				t.Fatal("SSE write did not start")
			}
			if err := server.SetWriteDeadline(time.Now()); err != nil {
				t.Fatal(err)
			}
			select {
			case err := <-errs:
				wg.Wait()
				if !errors.Is(err, os.ErrDeadlineExceeded) {
					t.Errorf("Writef error = %v, want deadline exceeded", err)
				}
			case <-time.After(time.Second):
				t.Fatal("blocked SSE write did not return")
			}
		})
	})
}

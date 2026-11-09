// HTTP access logging with streaming and upgrade support.

package httpserver

import (
	"bufio"
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"time"
)

// AccessLog wraps an HTTP handler and logs each request after it completes.
//
// It records the response status, transmitted byte count, duration, and
// hijack state. The ip field uses ClientIP from request context; origin is
// included only when Origin is nonempty. NewHandler populates this metadata. Responses below 300 use SuccessLevel; all other
// responses are logged at info level.
type AccessLog struct {
	http.Handler

	Logger *slog.Logger
	// SuccessLevel controls logging for responses below 300. Zero is Info.
	SuccessLevel slog.Level
}

// ServeHTTP implements http.Handler.
func (h AccessLog) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	start := time.Now()
	rw := &recordingWriter{ResponseWriter: w}
	ww := http.ResponseWriter(rw)
	if _, ok := w.(http.Hijacker); ok {
		ww = &hijackRecordingWriter{recordingWriter: rw}
	}
	h.Handler.ServeHTTP(ww, r)
	h.log(r.Context(), r, rw, time.Since(start))
}

func (h AccessLog) log(ctx context.Context, r *http.Request, rw *recordingWriter, d time.Duration) {
	if h.Logger == nil {
		panic("logger is required")
	}
	status := rw.statusCode()
	level := slog.LevelInfo
	if status < http.StatusMultipleChoices {
		level = h.SuccessLevel
	}
	method := r.Method
	attrs := []slog.Attr{
		slog.String("m", method),
		slog.String("p", r.URL.Path),
		slog.Int("s", status),
		slog.Duration("d", roundDuration(d)),
		slog.Int("b", rw.size),
		slog.String("ip", ClientIP(ctx)),
	}
	if rw.hijacked {
		attrs[0] = slog.String("m", "HIJACKED")
		attrs = append(attrs, slog.Bool("hijacked", true))
	}
	if origin := Origin(ctx); origin != "" {
		attrs = append(attrs, slog.String("origin", origin))
	}
	h.Logger.LogAttrs(ctx, level, "http", attrs...)
}

// recordingWriter wraps http.ResponseWriter to capture status code and response size.
type recordingWriter struct {
	http.ResponseWriter

	wroteHeader bool
	hijacked    bool
	status      int
	size        int
}

func (rw *recordingWriter) WriteHeader(code int) {
	if rw.wroteHeader {
		return
	}
	if code >= 100 && code < 200 && code != http.StatusSwitchingProtocols {
		rw.ResponseWriter.WriteHeader(code)
		return
	}
	rw.wroteHeader = true
	rw.status = code
	rw.ResponseWriter.WriteHeader(code)
}

func (rw *recordingWriter) Write(b []byte) (int, error) {
	if !rw.wroteHeader {
		rw.WriteHeader(http.StatusOK)
	}
	n, err := rw.ResponseWriter.Write(b)
	rw.size += n
	return n, err
}

// Flush implements http.Flusher for streaming handlers.
func (rw *recordingWriter) Flush() { _ = rw.FlushError() }

// FlushError lets http.ResponseController propagate transport flush failures.
func (rw *recordingWriter) FlushError() error {
	err := http.NewResponseController(rw.ResponseWriter).Flush()
	// A supported flush commits the implicit status before flushing buffered
	// transport data, even when that flush fails. Unsupported flushes leave
	// headers writable so the handler can send a fallback response.
	if !errors.Is(err, http.ErrNotSupported) && !rw.wroteHeader {
		rw.wroteHeader = true
		rw.status = http.StatusOK
	}
	return err
}

// Unwrap returns the underlying ResponseWriter for http.ResponseController.
func (rw *recordingWriter) Unwrap() http.ResponseWriter {
	return rw.ResponseWriter
}

func (rw *recordingWriter) statusCode() int {
	if rw.status == 0 {
		return http.StatusOK
	}
	return rw.status
}

type hijackRecordingWriter struct {
	*recordingWriter
}

// Hijack implements http.Hijacker for handlers such as WebSocket upgrades.
func (rw *hijackRecordingWriter) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	conn, brw, err := http.NewResponseController(rw.ResponseWriter).Hijack()
	if err == nil {
		rw.hijacked = true
	}
	return conn, brw, err
}

// roundDuration rounds d to 3 significant digits with minimum 1us precision.
func roundDuration(d time.Duration) time.Duration {
	for t := 100 * time.Second; t >= 100*time.Microsecond; t /= 10 {
		if d >= t {
			return d.Round(t / 100)
		}
	}
	return d.Round(time.Microsecond)
}

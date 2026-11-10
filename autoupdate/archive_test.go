// Tests exact executable extraction and corrupt or ambiguous archive rejection.

package autoupdate

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"testing"
)

type archiveEntry struct{ name, body string }

func makeArchive(t *testing.T, format string, entries []archiveEntry) []byte {
	var b bytes.Buffer
	if format == "zip" {
		w := zip.NewWriter(&b)
		for _, e := range entries {
			f, err := w.Create(e.name)
			if err != nil {
				t.Fatal(err)
			}
			if _, err = f.Write([]byte(e.body)); err != nil {
				t.Fatal(err)
			}
		}
		if err := w.Close(); err != nil {
			t.Fatal(err)
		}
	} else {
		gz := gzip.NewWriter(&b)
		w := tar.NewWriter(gz)
		for _, e := range entries {
			if err := w.WriteHeader(&tar.Header{Name: e.name, Mode: 0755, Size: int64(len(e.body))}); err != nil {
				t.Fatal(err)
			}
			if _, err := w.Write([]byte(e.body)); err != nil {
				t.Fatal(err)
			}
		}
		if err := w.Close(); err != nil {
			t.Fatal(err)
		}
		if err := gz.Close(); err != nil {
			t.Fatal(err)
		}
	}
	return b.Bytes()
}
func TestExtract(t *testing.T) {
	for _, format := range []string{"tar.gz", "zip"} {
		t.Run(format, func(t *testing.T) {
			t.Run("valid", func(t *testing.T) {
				data := makeArchive(t, format, []archiveEntry{{"../settings", "ignored"}, {"contrib/config.toml", "ignored"}, {"app", "executable"}})
				p := filepath.Join(t.TempDir(), "archive")
				if err := os.WriteFile(p, data, 0600); err != nil {
					t.Fatal(err)
				}
				var dst bytes.Buffer
				if err := extract(t.Context(), p, "app."+format, "app", &dst); err != nil {
					t.Fatal(err)
				}
				if dst.String() != "executable" {
					t.Fatalf("got %q", dst.String())
				}
			})
			t.Run("error", func(t *testing.T) {
				badCRC := makeArchive(t, format, []archiveEntry{{"app", "binary"}})
				if format == "zip" {
					badCRC[bytes.Index(badCRC, []byte("PK\x01\x02"))+16] ^= 1
				} else {
					badCRC[len(badCRC)-8] ^= 1
				}
				for _, tc := range []struct {
					name string
					data []byte
				}{
					{"missing", makeArchive(t, format, []archiveEntry{{"dir/app", "not root"}})},
					{"duplicate", makeArchive(t, format, []archiveEntry{{"app", "one"}, {"app", "two"}})},
					{"empty", makeArchive(t, format, []archiveEntry{{"app", ""}})},
					{"corrupt", []byte("not an archive")},
					{"bad CRC", badCRC},
				} {
					t.Run(tc.name, func(t *testing.T) {
						p := filepath.Join(t.TempDir(), "archive")
						if err := os.WriteFile(p, tc.data, 0600); err != nil {
							t.Fatal(err)
						}
						if err := extract(t.Context(), p, "app."+format, "app", &bytes.Buffer{}); err == nil {
							t.Fatal("accepted invalid archive")
						}
					})
				}
			})
		})
	}
}

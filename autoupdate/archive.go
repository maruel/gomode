// Bounded release archive readers extract only the exact executable member.

package autoupdate

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
)

func extract(ctx context.Context, path, name, member string, dst io.Writer) (err error) {
	f, err := os.Open(path) //nolint:gosec // Owned staging file beside the executable.
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, f.Close()) }()
	if strings.HasSuffix(name, ".zip") {
		st, err := f.Stat()
		if err != nil {
			return err
		}
		if err := checkZipDirectory(f, st.Size()); err != nil {
			return err
		}
		zr, err := zip.NewReader(f, st.Size())
		if err != nil {
			return err
		}
		var selected *zip.File
		for _, entry := range zr.File {
			if entry.Name != member {
				continue
			}
			if selected != nil {
				return errors.New("archive contains duplicate executable")
			}
			selected = entry
		}
		if selected == nil {
			return fmt.Errorf("archive has no executable %q", member)
		}
		if !selected.Mode().IsRegular() || selected.UncompressedSize64 == 0 || selected.UncompressedSize64 > maxBinarySize {
			return errors.New("archive executable must be a regular file of at most 256 MiB")
		}
		r, err := selected.Open()
		if err != nil {
			return err
		}
		_, err = io.Copy(dst, contextReader{ctx, io.LimitReader(r, maxBinarySize+1)})
		return errors.Join(err, r.Close())
	}
	gz, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, gz.Close()) }()
	expanded := &io.LimitedReader{R: contextReader{ctx, gz}, N: maxArchiveSize + 1}
	tr := tar.NewReader(expanded)
	found := false
	for {
		hdr, err := tr.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return err
		}
		if hdr.Name != member {
			continue
		}
		if found {
			return errors.New("archive contains duplicate executable")
		}
		found = true
		if hdr.Typeflag != tar.TypeReg || hdr.Size <= 0 || hdr.Size > maxBinarySize {
			return errors.New("archive executable must be a regular file of at most 256 MiB")
		}
		if _, err = io.Copy(dst, io.LimitReader(tr, hdr.Size)); err != nil {
			return err
		}
	}
	// Read through the gzip footer, including ignored trailing data, before commit.
	if _, err := io.Copy(io.Discard, expanded); err != nil {
		return err
	}
	if expanded.N == 0 {
		return errors.New("expanded archive exceeds 512 MiB")
	}
	if !found {
		return fmt.Errorf("archive has no executable %q", member)
	}
	return nil
}

// Bound ZIP metadata before archive/zip allocates central-directory entries. Release
// binaries fit ordinary ZIP; ZIP64 and multi-disk archives are intentionally rejected.
func checkZipDirectory(f *os.File, size int64) error {
	if size < 22 {
		return errors.New("truncated ZIP")
	}
	buf := make([]byte, min(size, 65557))
	if _, err := f.ReadAt(buf, size-int64(len(buf))); err != nil {
		return err
	}
	for i := len(buf) - 22; i >= 0; i-- {
		b := buf[i:]
		if binary.LittleEndian.Uint32(b) != 0x06054b50 || i+22+int(binary.LittleEndian.Uint16(b[20:])) != len(buf) {
			continue
		}
		count := binary.LittleEndian.Uint16(b[10:])
		length := binary.LittleEndian.Uint32(b[12:])
		off := binary.LittleEndian.Uint32(b[16:])
		if binary.LittleEndian.Uint32(b[4:]) != 0 || binary.LittleEndian.Uint16(b[8:]) != count || count > 4096 || length > 1<<20 || int64(off)+int64(length) != size-int64(len(buf))+int64(i) {
			return errors.New("ZIP directory exceeds release metadata limits or uses unsupported ZIP64/multi-disk layout")
		}
		return nil
	}
	return errors.New("ZIP end directory not found")
}

type contextReader struct {
	ctx context.Context
	r   io.Reader
}

func (r contextReader) Read(p []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	return r.r.Read(p)
}

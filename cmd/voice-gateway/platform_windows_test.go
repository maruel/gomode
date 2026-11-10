// Tests Windows shutdown requests pending at the restart handoff boundary.

package main

import (
	"context"
	"sync/atomic"
	"testing"

	"golang.org/x/sys/windows"
)

func TestShutdownWindowState(t *testing.T) {
	t.Run("pending stop during cleanup", func(t *testing.T) {
		stop, err := windows.CreateEvent(nil, 0, 1, nil)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			if err := windows.CloseHandle(stop); err != nil {
				t.Error(err)
			}
		})
		wake, err := windows.CreateEvent(nil, 0, 1, nil)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			if err := windows.CloseHandle(wake); err != nil {
				t.Error(err)
			}
		})
		cleaned := make(chan struct{})
		close(cleaned)
		var stopped atomic.Bool
		s := shutdownWindowState{cancel: context.CancelCauseFunc(func(error) { stopped.Store(true) }), cleaned: cleaned}
		if err := s.run(stop, wake, make(chan struct{})); err != nil {
			t.Fatal(err)
		}
		if !stopped.Load() {
			t.Fatal("pending external stop was discarded during cleanup")
		}
	})
}

// Windows process output redirection and graceful Task Scheduler shutdown.

package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"runtime"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	shutdownUser32            = windows.NewLazySystemDLL("user32.dll")
	registerClassEx           = shutdownUser32.NewProc("RegisterClassExW")
	unregisterClass           = shutdownUser32.NewProc("UnregisterClassW")
	createWindowEx            = shutdownUser32.NewProc("CreateWindowExW")
	destroyWindow             = shutdownUser32.NewProc("DestroyWindow")
	defWindowProc             = shutdownUser32.NewProc("DefWindowProcW")
	peekMessage               = shutdownUser32.NewProc("PeekMessageW")
	msgWaitForMultipleObjects = shutdownUser32.NewProc("MsgWaitForMultipleObjects")
	dispatchMessage           = shutdownUser32.NewProc("DispatchMessageW")
)

const (
	wmClose           = 0x0010
	wmQueryEndSession = 0x0011
	wmEndSession      = 0x0016
)

type shutdownWindowState struct {
	cancel  context.CancelCauseFunc
	cleaned <-chan struct{}
}

func (s *shutdownWindowState) windowProc(hwnd uintptr, msg uint32, wp, lp uintptr) uintptr {
	if msg == wmQueryEndSession {
		return 1 // Allow the session to end; WM_ENDSESSION confirms whether it will.
	}
	if msg == wmClose || (msg == wmEndSession && wp != 0) {
		slog.Info("Windows requested shutdown", "message", msg)
		s.cancel(nil)
		if msg == wmEndSession {
			// Windows can end the session once this callback returns. Give cleanup
			// a bounded chance to finish without holding up shutdown indefinitely.
			select {
			case <-s.cleaned:
			case <-time.After(4 * time.Second):
			}
		}
		return 0
	}
	r, _, _ := defWindowProc.Call(hwnd, uintptr(msg), wp, lp)
	return r
}

// Layouts from winuser.h; pointer-sized fields preserve both 32-bit and 64-bit ABI alignment.
type windowClassEx struct {
	Size, Style                   uint32
	WndProc                       uintptr
	ClassExtra, WindowExtra       int32
	Instance, Icon, Cursor, Brush windows.Handle
	MenuName, ClassName           *uint16
	SmallIcon                     windows.Handle
}

type windowMessage struct {
	Window         uintptr
	Message        uint32
	WParam, LParam uintptr
	Time           uint32
	X, Y           int32
	Private        uint32
}

func startPlatformShutdown(cancel context.CancelCauseFunc) (func() error, error) {
	// The installer signals this event directly: no window discovery or native
	// interop is needed in PowerShell. Local keeps it in the signed-in session,
	// and the PID distinguishes gateway processes in that session.
	name := windows.StringToUTF16Ptr(fmt.Sprintf(`Local\GoModeVoiceGateway-%d`, os.Getpid()))
	stop, err := windows.CreateEvent(nil, 0, 0, name)
	if err != nil {
		// CreateEvent returns an open handle as well as ERROR_ALREADY_EXISTS.
		if stop != 0 {
			err = errors.Join(err, windows.CloseHandle(stop))
		}
		return nil, fmt.Errorf("create shutdown event: %w", err)
	}
	cleaned := make(chan struct{})
	wake, err := windows.CreateEvent(nil, 0, 0, nil)
	if err != nil {
		return nil, errors.Join(err, windows.CloseHandle(stop))
	}
	s := &shutdownWindowState{cancel: cancel, cleaned: cleaned}
	ready := make(chan struct{})
	done := make(chan error, 1)
	go func() {
		err := s.run(stop, wake, ready)
		if err != nil {
			cancel(err)
		}
		done <- err
	}()
	select {
	case err := <-done:
		return nil, errors.Join(err, windows.CloseHandle(stop), windows.CloseHandle(wake))
	case <-ready:
		return func() error {
			// This runs after gateway cleanup. Release WM_ENDSESSION before waiting
			// for the window thread, since that callback may be waiting for cleanup.
			close(cleaned)
			err := windows.SetEvent(wake)
			return errors.Join(err, <-done, windows.CloseHandle(stop), windows.CloseHandle(wake))
		}, nil
	}
}

func (s *shutdownWindowState) run(stop, wake windows.Handle, ready chan<- struct{}) (err error) {
	// Window creation, dispatch and destruction must all run on the same OS thread.
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	var instance windows.Handle
	if err := windows.GetModuleHandleEx(windows.GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT, nil, &instance); err != nil {
		return fmt.Errorf("get window module: %w", err)
	}
	name := windows.StringToUTF16Ptr(fmt.Sprintf("GoModeVoiceGateway-%d", windows.GetCurrentThreadId()))
	wc := windowClassEx{
		Size: uint32(unsafe.Sizeof(windowClassEx{})),
		// The gateway creates one window for its lifetime. NewCallback retains s
		// until process exit; native resources are still released explicitly.
		WndProc:   windows.NewCallback(s.windowProc),
		Instance:  instance,
		ClassName: name,
	}
	if atom, _, err := registerClassEx.Call(uintptr(unsafe.Pointer(&wc))); atom == 0 { // #nosec G103 -- wc has the WNDCLASSEXW ABI layout and stays live during the call.
		return fmt.Errorf("register shutdown window class: %w", err)
	}
	defer func() {
		if ok, _, e := unregisterClass.Call(uintptr(unsafe.Pointer(name)), uintptr(instance)); ok == 0 { // #nosec G103 -- name is a live, NUL-terminated UTF-16 class name.
			err = errors.Join(err, fmt.Errorf("unregister shutdown window class: %w", e))
		}
	}()
	// The named event covers installer stops, but Windows does not signal it on
	// logoff or system shutdown. Keep a hidden top-level window for WM_ENDSESSION;
	// message-only windows do not receive those session notifications.
	hwnd, _, e := createWindowEx.Call(0, uintptr(unsafe.Pointer(name)), uintptr(unsafe.Pointer(name)), 0, 0, 0, 0, 0, 0, 0, uintptr(instance), 0) // #nosec G103 -- name is live for both UTF-16 arguments during the call.
	if hwnd == 0 {
		return fmt.Errorf("create shutdown window: %w", e)
	}
	defer func() {
		if ok, _, e := destroyWindow.Call(hwnd); ok == 0 {
			err = errors.Join(err, fmt.Errorf("destroy shutdown window: %w", e))
		}
	}()
	close(ready)
	handles := [2]windows.Handle{stop, wake}
	for {
		// Wait for gateway cleanup, installer stop requests or window messages.
		r, _, e := msgWaitForMultipleObjects.Call(2, uintptr(unsafe.Pointer(&handles[0])), 0, windows.INFINITE, 0x04ff) // #nosec G103 -- QS_ALLINPUT; handles contains two live HANDLEs, matching the count argument.
		switch r {
		case windows.WAIT_OBJECT_0:
			slog.Info("Windows shutdown event signaled")
			s.cancel(nil)
		case windows.WAIT_OBJECT_0 + 1, windows.WAIT_OBJECT_0 + 2:
			var msg windowMessage
			for {
				ok, _, _ := peekMessage.Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0, 1) // #nosec G103 -- PM_REMOVE; msg is a live MSG output buffer with the Win32 ABI layout.
				if ok == 0 {
					break
				}
				if msg.Message == 0x0012 { // WM_QUIT
					return errors.New("shutdown window message loop quit unexpectedly")
				}
				// DispatchMessage returns the window procedure's result, not a Win32 error.
				_, _, _ = dispatchMessage.Call(uintptr(unsafe.Pointer(&msg))) // #nosec G103 -- msg is the live MSG populated by PeekMessageW.
			}
		default:
			return fmt.Errorf("wait for shutdown window message: %w", e)
		}
		if r == windows.WAIT_OBJECT_0+1 {
			// The final message drain above and this external-event poll are the
			// handoff boundary. Requests arriving after their respective checks
			// cannot cancel a relaunch.
			pending, err := windows.WaitForSingleObject(stop, 0)
			if err != nil {
				return fmt.Errorf("poll shutdown event: %w", err)
			}
			if pending == windows.WAIT_OBJECT_0 {
				s.cancel(nil)
			}
			return nil
		}
	}
}

func redirectOutput(path string) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600) // #nosec G304 -- The operator selects the CLI log path.
	if err != nil {
		return err
	}
	// SetStdHandle redirects runtime panic output too; assigning os.Stderr alone
	// would only redirect writes made through that Go file object.
	for _, handle := range []uint32{windows.STD_OUTPUT_HANDLE, windows.STD_ERROR_HANDLE} {
		if err := windows.SetStdHandle(handle, windows.Handle(f.Fd())); err != nil {
			return errors.Join(err, f.Close())
		}
	}
	// The process owns these streams until exit, including main's final error report.
	os.Stdout, os.Stderr = f, f
	return nil
}

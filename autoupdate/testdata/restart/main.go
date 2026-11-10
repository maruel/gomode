// Exercises a real release executable's graceful replacement handoff.
package main

import (
	"bufio"
	"fmt"
	"os"
	"time"

	"github.com/maruel/gomode/autoupdate"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "version" {
		fmt.Println(autoupdate.Version())
		return
	}
	taskName := ""
	if len(os.Args) > 1 && os.Args[1] == "task" {
		taskName = os.Args[2]
	}
	u, err := autoupdate.New(autoupdate.Config{Repository: "maruel/gomode", CurrentVersion: autoupdate.Version(), Binary: "fixture", TaskName: taskName, ArchiveName: func(string) string { return "fixture.tar.gz" }})
	if err != nil {
		panic(err)
	}
	if len(os.Args) > 1 && os.Args[1] == "task" {
		if autoupdate.Version() == "v1.1.0" {
			if err := os.WriteFile(os.Args[3], []byte(autoupdate.Version()), 0600); err != nil {
				panic(err)
			}
			return
		}
		if err := os.WriteFile(os.Args[3]+".ready", []byte(fmt.Sprint(os.Getpid())), 0600); err != nil {
			panic(err)
		}
		deadline := time.Now().Add(3 * time.Minute)
		for {
			if _, err := os.Stat(os.Args[4]); err == nil {
				break
			} else if !os.IsNotExist(err) {
				panic(err)
			}
			if time.Now().After(deadline) {
				panic("task drain signal timed out")
			}
			time.Sleep(50 * time.Millisecond)
		}
		if err := u.Restart(); err != nil {
			panic(err)
		}
		return
	}
	if len(os.Args) > 1 && os.Args[1] == "child" {
		if err := os.WriteFile(os.Args[2], []byte(autoupdate.Version()), 0600); err != nil {
			panic(err)
		}
		return
	}
	fmt.Println("ready")
	if _, err := bufio.NewReader(os.Stdin).ReadString('\n'); err != nil {
		panic(err)
	}
	os.Args = append([]string{os.Args[0], "child"}, os.Args[1:]...)
	if err := u.Restart(); err != nil {
		panic(err)
	}
}

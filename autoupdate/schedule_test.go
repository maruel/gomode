// Tests local maintenance scheduling across calendar and daylight-saving boundaries.

package autoupdate

import (
	"testing"
	"time"
)

func TestSchedule(t *testing.T) {
	t.Run("Next", func(t *testing.T) {
		loc, err := time.LoadLocation("America/New_York")
		if err != nil {
			t.Fatal(err)
		}
		for _, tc := range []struct{ expr, now, want string }{
			{"50 4 * * *", "2026-11-09T04:50:00-05:00", "2026-11-10T04:50:00-05:00"},
			{"0 0 29 2 *", "2025-01-01T00:00:00-05:00", "2028-02-29T00:00:00-05:00"},
			{"30 2 * * *", "2026-03-08T00:00:00-05:00", "2026-03-09T02:30:00-04:00"},
			{"30 1 * * *", "2026-11-01T01:30:00-04:00", "2026-11-01T01:30:00-05:00"},
			{"*/15 9 * * 1", "2026-11-09T09:00:00-05:00", "2026-11-09T09:15:00-05:00"},
		} {
			t.Run(tc.expr+tc.now, func(t *testing.T) {
				s, err := ParseSchedule(tc.expr)
				if err != nil {
					t.Fatal(err)
				}
				now, err := time.Parse(time.RFC3339, tc.now)
				if err != nil {
					t.Fatal(err)
				}
				if got := s.Next(now.In(loc)).Format(time.RFC3339); got != tc.want {
					t.Fatalf("got %s want %s", got, tc.want)
				}
			})
		}
	})
}
func TestParseSchedule(t *testing.T) {
	t.Run("error", func(t *testing.T) {
		for _, v := range []string{"", "* * * *", "60 * * * *", "*/0 * * * *", "-1 * * * *", "1,,2 * * * *", "0 0 30 2 *", "0 24 * * *", "0 0 * 13 *", "0 0 * * 7"} {
			if _, err := ParseSchedule(v); err == nil {
				t.Errorf("accepted %q", v)
			}
		}
	})
}

// Local-time maintenance schedules shared by applications and release updates.

package autoupdate

import (
	"fmt"
	"strconv"
	"strings"
	"time"
)

// DefaultSchedule checks for updates daily at 04:50 local time.
const DefaultSchedule = "50 4 * * *"

// Schedule is an immutable parsed five-field maintenance schedule.
// Day-of-month and day-of-week constraints both apply.
type Schedule struct{ fields [5]uint64 }

// ParseSchedule parses minute, hour, day-of-month, month and weekday fields.
// Fields support '*', integers, comma-separated lists, '*/N' and 'start/N'.
func ParseSchedule(expr string) (Schedule, error) {
	var s Schedule
	parts := strings.Fields(expr)
	if len(parts) != 5 {
		return s, fmt.Errorf("schedule requires five fields: %q", expr)
	}
	bounds := [5][2]int{{0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 6}}
	for i, p := range parts {
		lo, hi := bounds[i][0], bounds[i][1]
		for part := range strings.SplitSeq(p, ",") {
			start, step, ok := strings.Cut(part, "/")
			n := 1
			if ok {
				var err error
				n, err = strconv.Atoi(step)
				if err != nil || n <= 0 || n > hi-lo+1 {
					return Schedule{}, fmt.Errorf("invalid schedule step %q", part)
				}
			}
			a, b := lo, hi
			if start != "*" {
				var err error
				a, err = strconv.Atoi(start)
				if err != nil || a < lo || a > hi {
					return Schedule{}, fmt.Errorf("invalid schedule value %q", part)
				}
				if !ok {
					b = a
				}
			}
			for v := a; v <= b; v += n {
				s.fields[i] |= uint64(1) << v
			}
		}
	}
	for m := 1; m <= 12; m++ {
		if s.match(3, m) {
			for d := 1; d <= time.Date(2000, time.Month(m)+1, 0, 0, 0, 0, 0, time.UTC).Day(); d++ {
				if s.match(2, d) {
					return s, nil
				}
			}
		}
	}
	return Schedule{}, fmt.Errorf("schedule has no possible calendar date: %q", expr)
}

// Next returns the next matching local minute strictly after now. It returns zero
// if no minute matches within a complete 400-year Gregorian calendar cycle.
func (s Schedule) Next(now time.Time) time.Time {
	for _, field := range s.fields {
		if field == 0 {
			return time.Time{}
		}
	}
	t := now.Truncate(time.Minute).Add(time.Minute)
	limit := t.AddDate(400, 0, 0)
	for t.Before(limit) {
		if !s.match(3, int(t.Month())) || !s.match(2, t.Day()) || !s.match(4, int(t.Weekday())) {
			next := time.Date(t.Year(), t.Month(), t.Day()+1, 0, 0, 0, 0, t.Location())
			if !next.After(t) {
				next = t.Add(time.Hour)
			}
			t = next
			continue
		}
		if s.match(1, t.Hour()) && s.match(0, t.Minute()) {
			return t
		}
		t = t.Add(time.Minute)
	}
	return time.Time{}
}
func (s Schedule) match(field, value int) bool { return s.fields[field]&(uint64(1)<<value) != 0 }

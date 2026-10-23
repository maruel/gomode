"""Generate the shared thinking-piano score from the supplied sheet music.

Music by Hans Zimmer; adapted to panio by Philipp A. G. Grzywaczyk. The
supplied continuation covers measures 45–106. The accepted opening arrangement
repeats its accompanied passage before 45.
Run with uv run --locked python scripts/generate_thinking_score.py.
"""

import argparse
import json
import math
from dataclasses import dataclass
from pathlib import Path

BEAT = 60 / 80
OUTPUT = Path(__file__).resolve().parent.parent / "assets"
OPENING_BARS = [
    ([64, 60, 64, 60, 64, 60], [], []),
    ([64, 60, 64, 60, 64, 60], [], []),
    ([64, 60, 64, 60, 64, 60], [], []),
    ([64, 60, 64, 60, 64, 60], [], []),
    ([64, 60, 64, 60, 64, 60], [57, 69], []),
    ([64, 62, 64, 62, 64, 62], [59, 71], []),
    ([64, 60, 64, 62, 64, 62], [], [[45, 57], [47, 59], [48, 60]]),
    ([64, 62, 64, 62, 64, 62], [], [[47, 59], [45, 57], [47, 59]]),
    ([64, 62, 64, 62, 64, 62], [43, 55], []),
    ([64, 62, 64, 62, 64, 62], [41, 53], []),
]


@dataclass(frozen=True)
class ScoreNote:
    measure: int
    beat: float
    length: float
    midi: int
    staff: str
    level: float = 1.0


def new_page() -> list[ScoreNote]:
    notes = []

    def add(measure: int, beat: float, length: float, pitches: list[int], staff: str, level: float = 1.0) -> None:
        notes.extend(ScoreNote(measure, beat, length, pitch, staff, level) for pitch in pitches)

    # Six groups of sixteenth-note triplets per measure, in the original 3/4.
    triplets = {
        45: [77, 72, 69] * 3 + [79, 72, 69] * 3,
        46: [83, 79, 76] * 6,
        47: [84, 79, 76] * 6,
        48: [86, 79, 76] * 6,
    }
    for measure, pitches in triplets.items():
        for step, pitch in enumerate(pitches):
            add(measure, step / 6, 0.16, [pitch], "treble", 1 if step % 3 == 0 else 0.8)
    for measure, chord in {45: [53, 60, 65], 46: [55, 62, 67], 47: [57, 64, 69], 48: [55, 62, 67]}.items():
        add(measure, 0, 3, chord, "bass")

    # The long notes across 49–50 are single strikes, with their ties preserved.
    add(49, 0, 6, [76], "treble")
    add(49, 0, 2, [64], "treble", 0.8)
    add(49, 2, 1, [64], "treble", 0.8)
    for beat in range(3):
        add(50, beat, 1, [64], "treble", 0.8)
    add(49, 0, 6, [40, 52], "bass", 0.8)

    # Day One: melody durations are in quarter-note beats, at quarter = 93.
    melody = {
        51: [(69, 3)],
        52: [(71, 6)],
        53: [],
        54: [(69, 1), (71, 1), (72, 1)],
        55: [(71, 1), (69, 1), (71, 1)],
        56: [(72, 3)],
        57: [(71, 6)],
        58: [],
        59: [(69, 2), (76, 1)],
        60: [(72, 3)],
        61: [(71, 6)],
        62: [],
        63: [(69, 1), (76, 1), (72, 1)],
        64: [(71, 1), (69, 1), (71, 1)],
        65: [(72, 3)],
        66: [(71, 6)],
        67: [],
        68: [(69, 2), (76, 1)],
        69: [(72, 3)],
        70: [(71, 6)],
        71: [],
        72: [(69, 1), (76, 1), (72, 1)],
        73: [(71, 1), (69, 1), (71, 1)],
        74: [(72, 3)],
        75: [(74, 3)],
        76: [(76, 3)],
    }
    for measure, phrase in melody.items():
        beat = 0
        for pitch, length in phrase:
            add(measure, beat, length, [pitch], "treble")
            beat += length
    eighths = {
        77: [69, 71, 72, 71, 69, 72],
        78: [71, 69, 67, 69, 71, 67],
        79: [71, 69, 67, 76, 71, 67],
        80: [69, 72, 71, 74, 72, 76],
        81: [71, 76, 69, 72, 71, 67],
        82: [72, 74, 72, 71, 69, 72],
        83: [71, 69, 67, 69, 71, 67],
    }
    for measure, pitches in eighths.items():
        for step, pitch in enumerate(pitches):
            add(measure, step / 2, 0.5, [pitch], "treble", 1 if step % 2 == 0 else 0.8)

    # The repeating upper bass voice is separate from the moving lower voice.
    moving_bass = {
        54: [57, 59, 60],
        55: [59, 57, 59],
        63: [57, 64, 60],
        64: [59, 57, 59],
        72: [57, 64, 60],
        73: [59, 57, 59],
        80: [57, 59, 60],
        81: [59, 57, 59],
    }
    for measure in range(51, 84):
        for beat in range(3):
            add(measure, beat, 1, [64], "bass", 0.8)
    for measure, pitches in moving_bass.items():
        for beat, pitch in enumerate(pitches):
            # A shared unison at E4 is one key strike, rather than two layers.
            if pitch != 64:
                add(measure, beat, 1, [pitch], "bass")
    sustained_bass = {
        51: ([57], 3),
        52: ([59], 6),
        56: ([60], 3),
        57: ([59], 6),
        59: ([57], 2),
        60: ([60], 3),
        61: ([59], 6),
        65: ([60], 3),
        66: ([59], 6),
        68: ([57], 2),
        69: ([60], 3),
        70: ([59], 6),
        74: ([60], 3),
        75: ([62], 3),
        77: ([52, 57], 3),
        78: ([52, 59], 6),
        80: ([52], 3),
        81: ([52], 3),
        82: ([52, 60], 3),
        83: ([52, 59], 6),
    }
    for measure, (pitches, length) in sustained_bass.items():
        add(measure, 0, length, pitches, "bass")
    eighths = {
        84: [71, 69, 67, 76, 71, 67],
        85: [69, 71, 72, 71, 69, 72],
        86: [71, 69, 67, 69, 71, 67],
        87: [71, 69, 67, 76, 71, 67],
        88: [69, 72, 71, 74, 72, 76],
        89: [71, 76, 69, 72, 71, 67],
        90: [72, 74, 72, 71, 69, 72],
        91: [71, 69, 67, 69, 71, 67],
        92: [71, 69, 67, 76, 71, 67],
    }
    for measure, pitches in eighths.items():
        for step, pitch in enumerate(pitches):
            add(measure, step / 2, 0.5, [pitch], "treble", 1 if step % 2 == 0 else 0.8)
    sixteenths = {
        93: [69, 64, 71, 64, 72, 64, 71, 64, 69, 64, 72, 64],
        94: [71, 64, 67, 64, 69, 64, 67, 64, 71, 64, 67, 64],
        95: [71, 64, 69, 64, 67, 64, 76, 64, 71, 64, 67, 64],
        96: [69, 64, 72, 64, 71, 64, 74, 64, 72, 64, 76, 64],
        97: [71, 64, 76, 64, 69, 64, 72, 64, 71, 64, 67, 64],
        98: [72, 64, 74, 64, 72, 64, 71, 64, 69, 64, 72, 64],
        99: [71, 64, 69, 64, 67, 64, 69, 64, 71, 64, 67, 64],
        100: [71, 64, 67, 64, 69, 64, 76, 64, 71, 64, 67, 64],
        101: [69, 64, 71, 64, 72, 64, 71, 64, 69, 64, 72, 64],
        102: [71, 64, 67, 64, 69, 64, 67, 64, 71, 64, 67, 64],
        103: [71, 64, 67, 64, 69, 64, 76, 64, 71, 64, 67, 64],
        104: [69, 64, 72, 64, 71, 64, 74, 64, 72, 64, 76, 64],
        105: [71, 64, 76, 64, 69, 64, 72, 64, 71, 64, 67, 64],
        106: [72, 64, 74, 64, 72, 64, 71, 64, 69, 64, 72, 64],
    }
    for measure, pitches in sixteenths.items():
        for step, pitch in enumerate(pitches):
            add(measure, step / 4, 0.25, [pitch], "treble", 1 if step % 2 == 0 else 0.8)
    for measure in range(84, 93):
        for beat in range(3):
            add(measure, beat, 1, [64], "bass", 0.8)
    for measure, pitches in {88: [57, 59, 60], 89: [59, 57, 59]}.items():
        add(measure, 0, 3, [52], "bass")
        for beat, pitch in enumerate(pitches):
            add(measure, beat, 1, [pitch], "bass")
    for measure, (pitches, length) in {
        85: ([52, 57], 3),
        86: ([52, 59], 6),
        90: ([52, 60], 3),
        91: ([52, 59], 6),
        93: ([45, 52, 57], 3),
        94: ([47, 52, 59], 3),
        95: ([48, 52, 60], 3),
        98: ([48, 52, 60], 3),
        99: ([47, 52, 59], 6),
        101: ([45, 52, 57], 3),
        102: ([47, 52, 59], 3),
        103: ([47, 52, 59], 3),
        106: ([48, 52, 60], 3),
    }.items():
        add(measure, 0, length, pitches, "bass")
    for measure, roots in {96: [45, 47, 48], 97: [47, 45, 47], 104: [45, 47, 48], 105: [47, 45, 47]}.items():
        for beat, root in enumerate(roots):
            add(measure, beat, 1, [root, 52, root + 12], "bass")
    return notes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check", action="store_true", help="Check that the shared score is current without writing it."
    )
    args = parser.parse_args()
    events = []
    # The opening arrangement has 25 bars, with its introduction played once.
    score_bars = list(range(len(OPENING_BARS)))
    while len(score_bars) < 25:
        score_bars.append(4 + (len(score_bars) - len(OPENING_BARS)) % 6)
    for bar, score_bar in enumerate(score_bars):
        eighth_notes, held_notes, bass_beats = OPENING_BARS[score_bar]
        start = 0.3 + bar * 3 * BEAT
        pedal_until = start + 3 * BEAT - 0.04
        for step, pitch in enumerate(eighth_notes):
            events.append((start + step * BEAT / 2, pitch, pedal_until, 0.5 * (0.9 if step % 2 == 0 else 0.72)))
        for pitch in held_notes:
            events.append((start, pitch, pedal_until, 0.58 * (0.5 if score_bar in (4, 5) else 1)))
        for beat, pitches in enumerate(bass_beats):
            for pitch in pitches:
                events.append((start + beat * BEAT, pitch, pedal_until, 0.76))

    page_start = 0.3 + len(score_bars) * 3 * BEAT
    day_one_start = page_start + 6 * 3 * BEAT
    page_notes = new_page()
    for note in page_notes:
        beat_duration = BEAT if note.measure < 51 else 60 / 93
        measure_start = (
            page_start + (note.measure - 45) * 3 * BEAT
            if note.measure < 51
            else day_one_start + (note.measure - 51) * 3 * beat_duration
        )
        start = measure_start + note.beat * beat_duration
        end = start + note.length * beat_duration
        # Pedal carries fast figures to the bar line; tied notes keep their
        # written length. The shared strike renderer supplies the key release.
        pedal_until = max(end - 0.025, measure_start + 3 * beat_duration - 0.04)
        staff_level = 0.45 if note.staff == "treble" else 0.76
        events.append((start, note.midi, pedal_until, staff_level * note.level))
    score = {
        "duration": math.ceil(day_one_start + 56 * 3 * 60 / 93 + 5),
        "gain": 0.045,
        "attack": 0.004,
        "release": 0.28,
        "partials": [
            {"ratio": 1.0, "level": 1.0, "decay": 1.0},
            {"ratio": 2.001, "level": 0.36, "decay": 1.6},
            {"ratio": 3.004, "level": 0.20, "decay": 2.2},
            {"ratio": 4.009, "level": 0.10, "decay": 2.8},
            {"ratio": 5.018, "level": 0.055, "decay": 3.4},
            {"ratio": 6.032, "level": 0.025, "decay": 4.0},
        ],
        "echoes": [
            {"delay": 0.5625, "feedback": 0.25, "level": 0.20},
            {"delay": 0.113, "feedback": 0.45, "level": 0.13},
        ],
        "notes": [
            {"at": round(at, 6), "midi": midi, "duration": round(end - at, 6), "level": level}
            for at, midi, end, level in sorted(events)
        ],
    }
    path = OUTPUT / "thinking-score.json"
    content = json.dumps(score, separators=(",", ":")) + "\n"
    if args.check:
        if not path.is_file() or path.read_text() != content:
            parser.exit(1, "Thinking score is stale; run uv run --locked python scripts/generate_thinking_score.py\n")
    else:
        OUTPUT.mkdir(exist_ok=True)
        path.write_text(content)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

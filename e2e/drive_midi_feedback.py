#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""drive DroidPad controls over MIDI, the way a DAW would.

writes raw MIDI to an alsa rawmidi device, so it needs no library and no
sequencer client. the control pad decodes the messages through its mapping
table and updates whichever items those numbers are mapped to.

  drive_midi_feedback.py --ramp-cc 6 --cycle-cc 5 --toggle-note 37

--ramp-cc     sweeps a controller up and down, for a GAUGE or a SLIDER
--cycle-cc    steps a controller through off, on and blink, for an LED
--toggle-note alternates note on and note off, for a SWITCH
"""

import argparse
import sys
import time

NOTE_OFF = 0x80
NOTE_ON = 0x90
CONTROL_CHANGE = 0xB0

MAX_DATA = 127
FULL_VELOCITY = 127

# where an LED's three states sit in the value range it decodes
LED_CYCLE = [("OFF", 0), ("ON", 64), ("BLINK", MAX_DATA)]


class Port:
    """an alsa rawmidi device, written unbuffered so nothing waits for a flush."""

    def __init__(self, path):
        self.file = open(path, "wb", buffering=0)

    def control_change(self, channel, number, value):
        self.file.write(bytes((CONTROL_CHANGE | (channel - 1), number, value)))

    def note(self, channel, number, on):
        status = NOTE_ON if on else NOTE_OFF
        self.file.write(bytes((status | (channel - 1), number, FULL_VELOCITY if on else 0)))

    def close(self):
        self.file.close()


def triangle(steps):
    """a sweep up and back down, so a ramped control reaches both ends."""
    up = [round(i * MAX_DATA / steps) for i in range(steps + 1)]
    return up + up[-2:0:-1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default="/dev/snd/midiC1D0")
    parser.add_argument("--channel", type=int, default=1)
    parser.add_argument("--ramp-cc", type=int, action="append", default=[])
    parser.add_argument("--cycle-cc", type=int, action="append", default=[])
    parser.add_argument("--toggle-note", type=int, action="append", default=[])
    parser.add_argument("--step-seconds", type=float, default=0.05)
    parser.add_argument("--slow-every", type=int, default=20, help="ramp steps between slow changes")
    args = parser.parse_args()

    if not (args.ramp_cc or args.cycle_cc or args.toggle_note):
        parser.error("nothing to drive: pass at least one of --ramp-cc, --cycle-cc, --toggle-note")

    sweep = triangle(steps=40)
    port = Port(args.device)
    print(
        f"driving {args.device} on channel {args.channel}: "
        f"ramp cc {args.ramp_cc or '-'}, cycle cc {args.cycle_cc or '-'}, toggle note {args.toggle_note or '-'}",
        flush=True,
    )

    step = 0
    slow = 0
    try:
        while True:
            value = sweep[step % len(sweep)]
            for number in args.ramp_cc:
                port.control_change(args.channel, number, value)

            if step % args.slow_every == 0:
                name, led_value = LED_CYCLE[slow % len(LED_CYCLE)]
                for number in args.cycle_cc:
                    port.control_change(args.channel, number, led_value)
                for number in args.toggle_note:
                    port.note(args.channel, number, on=slow % 2 == 0)

                on = "on" if slow % 2 == 0 else "off"
                print(
                    f"{time.strftime('%H:%M:%S')} ramp {value:3d}/127  led {name:<5} note {on}",
                    flush=True,
                )
                slow += 1

            step += 1
            time.sleep(args.step_seconds)
    finally:
        port.close()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        pass
    except OSError as error:
        print(f"write failed, is the device still attached? {error}", file=sys.stderr)
        sys.exit(1)

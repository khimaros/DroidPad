#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""independent check of the MIDI bytes DroidPad emits.

MidiVectorExporterTest runs the app's encoder over a fixed set of control pad
interactions and writes the resulting messages to a json file. this script
decodes those bytes with its own MIDI 1.0 parser and checks they say what the
mapping asked for, so a mistake in the encoder (a wrong status nibble, a
swapped pitch bend half, a missing note off) fails here even though both sides
agree on the json.
"""

import json
import math
import sys
from collections import defaultdict

NOTE_OFF = 0x80
NOTE_ON = 0x90
CONTROL_CHANGE = 0xB0
PROGRAM_CHANGE = 0xC0
PITCH_BEND = 0xE0

MESSAGE_LENGTHS = {
    NOTE_OFF: 3,
    NOTE_ON: 3,
    CONTROL_CHANGE: 3,
    PROGRAM_CHANGE: 2,
    PITCH_BEND: 3,
}

MAX_DATA = 127
MAX_BEND = 16383
CENTER_BEND = 8192

# status bytes a mapping of each type is allowed to produce
ALLOWED_STATUS = {
    "NOTE": {NOTE_ON, NOTE_OFF},
    "CONTROL_CHANGE": {CONTROL_CHANGE},
    "PROGRAM_CHANGE": {PROGRAM_CHANGE},
    "PITCH_BEND": {PITCH_BEND},
}

CONTINUOUS_KINDS = {"SLIDER", "STEERING_WHEEL", "JOYSTICK"}


def decode(message):
    """parse one MIDI 1.0 channel voice message given as hex bytes."""
    raw = [int(part, 16) for part in message.split()]
    if not raw:
        raise ValueError("empty message")

    status = raw[0]
    if status < 0x80:
        raise ValueError(f"0x{status:02x} is not a status byte")

    kind = status & 0xF0
    if kind not in MESSAGE_LENGTHS:
        raise ValueError(f"0x{kind:02x} is not a channel voice message")

    expected_length = MESSAGE_LENGTHS[kind]
    if len(raw) != expected_length:
        raise ValueError(f"expected {expected_length} bytes, got {len(raw)}")

    for byte in raw[1:]:
        if byte > MAX_DATA:
            raise ValueError(f"data byte 0x{byte:02x} has its high bit set")

    decoded = {"kind": kind, "channel": status & 0x0F, "data": raw[1:]}
    if kind == PITCH_BEND:
        decoded["bend"] = raw[1] | (raw[2] << 7)
    return decoded


def normalized(item, event):
    """where the event sits in the item's range, as the encoder sees it."""
    low, high = item["minValue"], item["maxValue"]
    value = event["x"] if event["kind"] == "JOYSTICK" else event["value"]
    if high == low:
        return 0.0
    return min(1.0, max(0.0, (value - low) / (high - low)))


def scaled(fraction, full_scale):
    # the encoder rounds halves up rather than to even
    return math.floor(fraction * full_scale + 0.5)


def check_vector(vector):
    errors = []
    mapping = vector["mappings"][0]
    event = vector["event"]
    item = vector["item"]

    try:
        messages = [decode(message) for message in vector["messages"]]
    except ValueError as error:
        return [f"undecodable message: {error}"]

    for message in messages:
        if message["kind"] not in ALLOWED_STATUS[mapping["messageType"]]:
            errors.append(
                f"status 0x{message['kind']:02x} is not a {mapping['messageType']} message"
            )
        if message["channel"] != mapping["channel"] - 1:
            errors.append(
                f"channel {message['channel'] + 1} does not match the mapped channel {mapping['channel']}"
            )
        if message["kind"] in (NOTE_ON, NOTE_OFF, CONTROL_CHANGE):
            if message["data"][0] != mapping["number"]:
                errors.append(
                    f"number {message['data'][0]} does not match the mapped number {mapping['number']}"
                )

    if event["kind"] in CONTINUOUS_KINDS:
        errors += check_continuous(mapping, item, event, messages)
    else:
        errors += check_momentary(mapping, event, messages)

    return errors


def check_continuous(mapping, item, event, messages):
    if len(messages) != 1:
        return [f"a continuous control should send one message, sent {len(messages)}"]

    message = messages[0]
    fraction = normalized(item, event)

    if mapping["messageType"] == "PITCH_BEND":
        expected = scaled(fraction, MAX_BEND)
        if message["bend"] != expected:
            return [f"bend {message['bend']} should be {expected}"]
        return []

    expected = scaled(fraction, MAX_DATA)
    actual = message["data"][-1]
    if actual != expected:
        return [f"value {actual} should be {expected}"]
    return []


def check_momentary(mapping, event, messages):
    state = event["state"]
    message_type = mapping["messageType"]

    # a program change selects a patch, there is nothing to release
    if message_type == "PROGRAM_CHANGE":
        expected_count = 0 if state == "RELEASE" else 1
        if len(messages) != expected_count:
            return [f"{state} should send {expected_count} program change(s), sent {len(messages)}"]
        return []

    expected_count = 2 if state == "CLICK" else 1
    if len(messages) != expected_count:
        return [f"{state} should send {expected_count} message(s), sent {len(messages)}"]

    if message_type != "NOTE":
        return []

    errors = []
    if state in ("PRESS", "TRUE"):
        if messages[0]["kind"] != NOTE_ON or messages[0]["data"][1] != MAX_DATA:
            errors.append("a press should be a note on at full velocity")
    elif state in ("RELEASE", "FALSE"):
        if messages[0]["kind"] != NOTE_OFF:
            errors.append("a release should be a note off")
    elif state == "CLICK":
        if messages[0]["kind"] != NOTE_ON or messages[1]["kind"] != NOTE_OFF:
            errors.append("a click should be a note on followed by a note off")
    return errors


def check_sweeps(vectors):
    """a swept control should rise with its input and reach both endpoints."""
    sweeps = defaultdict(list)
    for vector in vectors:
        name = vector["name"]
        if " sweep " in name:
            sweeps[name.rsplit(" ", 1)[0]].append(vector)

    if not sweeps:
        return ["no sweep vectors were exported"]

    errors = []
    for name, group in sorted(sweeps.items()):
        group.sort(key=lambda vector: vector["event"]["value"])
        values = []
        for vector in group:
            message = decode(vector["messages"][0])
            values.append(message["bend"] if message["kind"] == PITCH_BEND else message["data"][-1])

        full_scale = MAX_BEND if decode(group[0]["messages"][0])["kind"] == PITCH_BEND else MAX_DATA

        if any(later < earlier for earlier, later in zip(values, values[1:])):
            errors.append(f"{name}: values are not monotonic: {values}")
        if values[0] != 0:
            errors.append(f"{name}: the low end of the range should send 0, sent {values[0]}")
        if values[-1] != full_scale:
            errors.append(f"{name}: the high end should send {full_scale}, sent {values[-1]}")

    return errors


def main():
    if len(sys.argv) != 2:
        print("usage: test_midi_wire_format.py <midi-vectors.json>", file=sys.stderr)
        return 2

    with open(sys.argv[1]) as handle:
        vectors = json.load(handle)["vectors"]

    failures = []
    for vector in vectors:
        for error in check_vector(vector):
            failures.append(f"{vector['name']}: {error}")

    failures += check_sweeps(vectors)

    # a control resting at half travel has to land on the bend center,
    # otherwise anything mapped to pitch bend sits detuned at rest
    center = scaled(0.5, MAX_BEND)
    if center != CENTER_BEND:
        failures.append(f"a half scale bend is {center}, not the {CENTER_BEND} center")

    for failure in failures:
        print(f"FAIL {failure}")

    print(f"checked {len(vectors)} vectors, {len(failures)} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

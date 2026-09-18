# design

how DroidPad is put together, focused on the path an interaction takes from a
control on screen to the other end of a connection.

## layers

single activity Jetpack Compose app, MVVM, Hilt for injection, Room for storage.

- `data/database` - Room entities, DAOs and migrations
- `data/repositories`, `data/repositoriesimp` - repository interfaces and their
  implementations
- `data/connectionconfig` - one serializable config per connection type, stored
  as a json string on `ConnectionConfig.configJson`
- `data/connection` - one `Connection` per connection type, built by
  `ConnectionFactory`
- `data/util` - platform wrappers (bluetooth, midi, vibrator, qr codes), each an
  interface plus an `Imp` so view models stay testable off device
- `ui/screens` - a composable and a view model per screen
- `ui/components` - the control pad widgets and their properties editors

## the control pad model

A `ControlPad` owns `ControlPadItem`s, one `ConnectionConfig` and any attached
`ControlPadSensor`s. Both an item's appearance (`ControlPadItem.properties`) and
a connection's settings (`ConnectionConfig.configJson`) are json blobs, so
adding a property or a connection setting needs no schema migration.

`ControlPadItem.itemIdentifier` is the user facing name of a control and is what
travels over the connection. It is distinct from the database `id`.

## sending an interaction

1. A component raises a `ControlPadPlayScreenEvent`.
2. `ControlPadPlayScreenViewModel` turns it into a `ControlPadEvent`
   (`data/Events.kt`) and hands it to its `send()`.
3. `send()` encodes the event for the active connection and writes it:
   - MIDI connections encode through `MidiEncoder` and write bytes
   - Bluetooth connections write CSV, unless the user opted into json
   - everything else writes json

`Connection` exposes both `sendData(String)` and `sendData(ByteArray)`. The byte
overload defaults to the string one, so only byte oriented protocols override
it.

Incoming data flows the other way through `Connection.receivedData`, which the
play screen parses to drive SWITCH, SLIDER, LED, GAUGE and LOG. Byte oriented
protocols use `receivedBytes` instead, carrying whole messages the connection
has already framed. MIDI decodes those into the same json the string path
carries, so both ends up in one handler rather than two.

## adding a connection type

1. add a value to `ConnectionType`
2. add a serializable config under `data/connectionconfig`
3. add a `Connection` subclass and any states it reports to `ConnectionState`
4. build it in `ConnectionFactoryImpl`
5. load, edit and save it in `ConnectionConfigScreen` and its view model
6. map its connecting and connected states in `ControlPadPlayScreenViewModel`,
   and give it a `hostAddress`
7. strip anything device specific from it in `QrCodeGeneratorScreenViewModel`

## MIDI

`ConnectionType.MIDI` writes MIDI 1.0 channel voice messages to an input port of
a device the platform exposes, which covers a USB instrument attached in host
mode, the phone's own peripheral port when it is plugged into a computer in MIDI
mode, and virtual ports published by other apps.

- `MidiUtil` wraps `android.media.midi`: enumerating destinations, opening a
  port and listening on the device's output port. `MidiConnection` only knows
  about that interface, so it is covered by jvm tests with a fake.
- `MidiEncoder` is pure: given the mapping table and the pad's items it turns a
  `ControlPadEvent` into MIDI bytes. Continuous controls are scaled from the
  range configured on the item, which is why the encoder needs the items and is
  built in the play screen view model rather than in `ConnectionFactory`.
- `MidiDecoder` is its mirror, and reads the same mapping table: whatever
  number drives a control outward is the number that drives it inward. It emits
  the json a script would have sent, so incoming MIDI reuses the existing update
  path instead of a second one.
- `MidiStreamParser` does the framing, because a port hands over arbitrary
  chunks and a sender may use running status. Keeping it in the connection
  leaves the decoder pure and the framing separately testable.
- `midiTargetsOf()` names the independently mappable controls of an item. The
  mapping editor, the encoder and the decoder all key off it, so a DPAD
  direction or a joystick axis cannot be addressed differently across them.
  LED and GAUGE are targets despite never sending, because a mapping is what
  lets incoming MIDI find them.
- `midiMappingsFor()` is the one place a number is chosen. New targets take the
  lowest free number rather than a positional one, so adding a control cannot
  land it on top of a control that was renumbered by hand.

Mappings live on the connection config rather than on item properties, so a
control pad stays usable over every connection type and no per item editor
knows about MIDI.

The wire format is covered twice: `MidiEncoderTest` asserts the bytes directly,
and `make test-e2e` re-decodes the exported vectors with an independent parser
in `e2e/test_midi_wire_format.py`.

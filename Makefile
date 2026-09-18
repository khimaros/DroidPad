# the wrapper is checked in without the executable bit, so always go through sh
GRADLE := sh gradlew --console=plain
VECTORS := app/build/e2e/midi-vectors.json

.PHONY: build test test-e2e precommit lint clean

build:
	$(GRADLE) :app:assembleDebug

test:
	$(GRADLE) :app:testDebugUnitTest

# the exporter test writes the encoder's output for a fixed set of control pad
# events; the python suite decodes those bytes with an independent MIDI parser
test-e2e:
	$(GRADLE) :app:testDebugUnitTest --tests '*MidiVectorExporterTest'
	uv run --quiet e2e/test_midi_wire_format.py $(VECTORS)

lint:
	$(GRADLE) :app:lintDebug

precommit: lint test

clean:
	$(GRADLE) clean

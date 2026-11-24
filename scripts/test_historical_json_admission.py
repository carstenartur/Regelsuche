import json
from pathlib import Path
import unittest

from frozen_graalpy_json_admission import instrument_loads, instrument_decoder


class HistoricalJsonAdmissionTest(unittest.TestCase):
    def test_exercises_native_bom_then_original_short_circuit(self):
        events = []
        with open(json.__file__, encoding='utf-8') as file:
            source = file.read()
        loads = instrument_loads(source, json.loads, events.append)
        self.assertEqual({'x': 1}, loads('{"x":1}'))
        self.assertEqual(['json.loads.isinstance', 'json.loads.before-bom', 'json.loads.after-bom',
                          'json.loads.cls.is-none', 'json.loads.object_hook.is-none', 'json.loads.parse_int.is-none',
                          'json.loads.parse_float.is-none', 'json.loads.parse_constant.is-none',
                          'json.loads.object_pairs_hook.is-none', 'json.loads.not-kw', 'json.loads.before-decode'], events)

    def test_real_bom_refusal_and_first_guard_abort(self):
        events = []
        with open(json.__file__, encoding='utf-8') as file:
            source = file.read()
        loads = instrument_loads(source, json.loads, events.append)
        with self.assertRaises(json.JSONDecodeError):
            loads('\ufeff{}')
        self.assertEqual(['json.loads.isinstance','json.loads.before-bom','json.loads.after-bom'], events)
        def deny(event):
            raise ValueError(event)
        with self.assertRaisesRegex(ValueError, 'json.loads.isinstance'):
            instrument_loads(source, json.loads, deny)('{}')

    def test_decoder_preserves_bound_pattern_and_only_marks_the_leading_match(self):
        recorder = []
        source = Path(json.decoder.__file__).read_text(encoding='utf-8')
        original = json.decoder.JSONDecoder.decode
        instrumented = instrument_decoder(source, original, recorder.append)
        self.assertIs(original.__defaults__, instrumented.__defaults__)
        self.assertEqual([1], instrumented(json.JSONDecoder(), '  [1]  '))
        self.assertEqual(['json.decode.leading-match'], recorder)
        recorder.clear()
        with self.assertRaises(json.JSONDecodeError):
            instrumented(json.JSONDecoder(), '  [')
        self.assertEqual(['json.decode.leading-match'], recorder)

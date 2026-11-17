import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/features/checkout/layouts.g.dart';

import '../tool/gen_layouts.dart';

void main() {
  final json = jsonDecode(File(layoutsPath).readAsStringSync()) as Map<String, Object?>;

  test('layouts.g.dart matches docs/venue-layouts.json (else run `dart run tool/gen_layouts.dart`)', () {
    expect(File(outputPath).readAsStringSync(), generateLayouts(json));
  });

  test('every layout and slot of the JSON is built in, in the JSON order', () {
    final layouts = json['layouts']! as Map<String, Object?>;
    expect(VenueLayouts.all.keys, layouts.keys);
    for (final MapEntry(:key, :value) in layouts.entries) {
      final slots = (value! as Map<String, Object?>)['slots']! as List<Object?>;
      expect(
        [for (final (id, _) in VenueLayouts.all[key]!.slots) id],
        [for (final s in slots.cast<Map<String, Object?>>()) s['id']],
      );
    }
  });
}

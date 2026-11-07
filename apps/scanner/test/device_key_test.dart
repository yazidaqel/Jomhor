import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_scanner/core/device_key.dart';

/// The Dart half of the Keystore channel: method names and argument types the Kotlin DeviceKey.kt expects.
/// The Kotlin half runs only on a device (Keystore has no JVM test double).
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('generate, publicKey and sign go over ma.jomhor.scanner/device_key with raw bytes', () async {
    final calls = <MethodCall>[];
    final spki = Uint8List.fromList([0x30, 0x59, 1, 2]);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      KeystoreDeviceKey.channel,
      (call) async {
        calls.add(call);
        return switch (call.method) {
          'generate' || 'publicKey' => spki,
          'sign' => Uint8List.fromList([0x30, 0x44]),
          _ => null,
        };
      },
    );
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        KeystoreDeviceKey.channel,
        null,
      ),
    );

    const key = KeystoreDeviceKey();
    expect(await key.generate(), spki);
    expect(await key.publicKey(), spki);
    expect(await key.sign(Uint8List.fromList([1, 2, 3])), [0x30, 0x44]);
    expect(calls.map((c) => c.method), ['generate', 'publicKey', 'sign']);
    expect(calls.last.arguments, isA<Uint8List>().having((b) => b.toList(), 'bytes', [1, 2, 3]));
  });
}

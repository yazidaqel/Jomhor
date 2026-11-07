import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The device key of docs/04-api.md §Gate auth: ECDSA P-256, non-exportable, in Android Keystore.
abstract interface class DeviceKey {
  /// Creates a new key (replacing any previous one, for re-enrolment) and returns its SPKI DER public key.
  Future<Uint8List> generate();

  /// SPKI DER of the current key, or null before [generate].
  Future<Uint8List?> publicKey();

  /// DER ECDSA P-256 / SHA-256 signature of [data].
  Future<Uint8List> sign(Uint8List data);
}

/// [DeviceKey] over the Kotlin channel in android/app/src/main/kotlin/ma/jomhor/scanner/DeviceKey.kt.
class KeystoreDeviceKey implements DeviceKey {
  const new();

  static const channel = MethodChannel('ma.jomhor.scanner/device_key');

  @override
  Future<Uint8List> generate() async => (await channel.invokeMethod<Uint8List>('generate'))!;

  @override
  Future<Uint8List?> publicKey() => channel.invokeMethod<Uint8List>('publicKey');

  @override
  Future<Uint8List> sign(Uint8List data) async => (await channel.invokeMethod<Uint8List>('sign', data))!;
}

/// Overridden in tests with a fake.
final deviceKeyProvider = Provider<DeviceKey>((ref) => const KeystoreDeviceKey());

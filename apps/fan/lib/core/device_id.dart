import 'dart:convert';
import 'dart:math';

import 'package:shared_preferences/shared_preferences.dart';

const _key = 'device_id';

/// This install's random id (lot-5 Q146): 32 chars of `[A-Za-z0-9_-]`, made once and kept in the app preferences,
/// so it survives restarts and logouts but not a reinstall. Sent only as `device_id` on `POST /fan/queue`, where the
/// server keeps an HMAC of it for the per-device limit; nothing about the phone is in it.
Future<String> deviceId() async {
  final prefs = SharedPreferencesAsync();
  try {
    if (await prefs.getString(_key) case final id? when _valid.hasMatch(id)) return id;
  } on Object {
    // Unreadable: a new id below.
  }
  final random = Random.secure();
  final id = base64Url.encode([for (var i = 0; i < 24; i++) random.nextInt(256)]);
  try {
    await prefs.setString(_key, id);
  } on Object {
    // Not saved: this run joins with it; the next one makes another (the server limit is soft anyway).
  }
  return id;
}

final _valid = RegExp(r'^[A-Za-z0-9_-]{16,64}$');

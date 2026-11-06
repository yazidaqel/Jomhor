import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/core/api/api_client.dart';

/// `user` of `/fan/auth/verify` and `GET /fan/me`. Hand-written like the event DTOs.
class FanUser {
  new({required this.id, required this.phone, this.displayName, this.locale});

  factory fromJson(Map<String, Object?> j) => FanUser(
    id: j['id']! as String,
    phone: j['phone']! as String,
    displayName: j['display_name'] as String?,
    locale: j['locale'] as String?,
  );

  final String id;
  final String phone;
  final String? displayName;
  final String? locale;
}

/// `POST /fan/auth/verify` response.
class FanSession {
  new({required this.token, required this.expiresAt, required this.user});

  factory fromJson(Map<String, Object?> j) => FanSession(
    token: j['token']! as String,
    expiresAt: DateTime.parse(j['expires_at']! as String),
    user: FanUser.fromJson(j['user']! as Map<String, Object?>),
  );

  final String token;
  final DateTime expiresAt;
  final FanUser user;
}

class AuthRepository {
  new(this._api);

  final ApiClient _api;

  /// 202: accepted, not necessarily delivered.
  Future<void> requestCode(String e164) => _api.postPublic('/fan/auth/otp', body: {'phone': e164});

  Future<FanSession> verify(String e164, String code) async => FanSession.fromJson(
    (await _api.postPublic('/fan/auth/verify', body: {'phone': e164, 'code': code}))! as Map<String, Object?>,
  );

  Future<FanUser> me() async => FanUser.fromJson((await _api.get('/fan/me'))! as Map<String, Object?>);

  /// `PATCH /fan/me`: the server normalises the name (§Holder names) and answers the stored user.
  Future<FanUser> setDisplayName(String name) async =>
      FanUser.fromJson((await _api.patch('/fan/me', body: {'display_name': name}))! as Map<String, Object?>);
}

final authRepositoryProvider = Provider<AuthRepository>((ref) => AuthRepository(ref.watch(apiClientProvider)));

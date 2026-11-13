import 'dart:async';
import 'dart:io';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/config.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// A push as the app sees it (docs/04-api.md §Notifications, push wire): `notification {title, body}` and
/// `data {path}`, nothing else.
class PushMessage {
  const new({this.title, this.body, this.data = const {}});

  final String? title;
  final String? body;
  final Map<String, Object?> data;
}

/// The OS notification permission, as Profil shows it (lot-4 Q123).
enum PushPermission { granted, denied, notAsked }

/// The push provider seen by the app; [FirebasePushMessaging] on a phone, a fake in tests.
abstract interface class PushMessaging {
  Future<String?> token();
  Stream<String> get onTokenRefresh;

  /// Received while the app is in the foreground (the OS shows nothing then).
  Stream<PushMessage> get onForeground;

  /// The fan tapped a notification while the app was in the background.
  Stream<PushMessage> get onOpened;

  /// The notification that launched the app from terminated, once.
  Future<PushMessage?> initialMessage();
  Future<PushPermission> permission();
  Future<PushPermission> requestPermission();
  Future<void> deleteToken();
}

class FirebasePushMessaging implements PushMessaging {
  FirebaseMessaging get _fcm => FirebaseMessaging.instance;

  static PushMessage _of(RemoteMessage m) =>
      PushMessage(title: m.notification?.title, body: m.notification?.body, data: m.data);

  // ponytail: FCM registration tokens, deprecated in firebase_messaging 17 for FID direct-send (register() /
  // onRegistered). The backend sends to registration tokens (docs/04-api.md `PUT /fan/me/push-token`); move both
  // sides together.
  @override
  // Registration tokens until the backend moves to FID direct-send (above).
  // ignore: deprecated_member_use
  Future<String?> token() => _fcm.getToken();

  @override
  // Registration tokens until the backend moves to FID direct-send (above).
  // ignore: deprecated_member_use
  Stream<String> get onTokenRefresh => _fcm.onTokenRefresh;

  @override
  Stream<PushMessage> get onForeground => FirebaseMessaging.onMessage.map(_of);

  @override
  Stream<PushMessage> get onOpened => FirebaseMessaging.onMessageOpenedApp.map(_of);

  @override
  Future<PushMessage?> initialMessage() async => switch (await _fcm.getInitialMessage()) {
    final m? => _of(m),
    null => null,
  };

  @override
  Future<PushPermission> permission() async {
    final status = (await _fcm.getNotificationSettings()).authorizationStatus;
    return permissionOf(status, ios: Platform.isIOS, refused: await _refused());
  }

  @override
  Future<PushPermission> requestPermission() async {
    final status = (await _fcm.requestPermission()).authorizationStatus;
    final granted = status == AuthorizationStatus.authorized || status == AuthorizationStatus.provisional;
    if (!granted) {
      try {
        await SharedPreferencesAsync().setBool(pushRefusedKey, true);
      } on Object {
        // Not saved: "Activer" shows once more after a restart.
      }
    }
    return permissionOf(status, ios: Platform.isIOS, refused: !granted || await _refused());
  }

  static Future<bool> _refused() async {
    try {
      return await SharedPreferencesAsync().getBool(pushRefusedKey) ?? false;
    } on Object {
      return false;
    }
  }

  @override
  // Registration tokens until the backend moves to FID direct-send (above).
  // ignore: deprecated_member_use
  Future<void> deleteToken() => _fcm.deleteToken();
}

/// A request on this phone came back refused (kept per install, like [pushAskedKey]).
const pushRefusedKey = 'push.refused';

/// Profil's reading of firebase_messaging's status (lot-4 Q123). Android says `denied` both on 13+ while it can still
/// prompt and below 13 when notifications are off in the settings (no prompt exists there): once a request came back
/// refused on this phone ([refused]), only the settings can help. iOS `denied` and Android `deniedPermanently` are
/// for good.
PushPermission permissionOf(AuthorizationStatus status, {required bool ios, required bool refused}) =>
    switch (status) {
      AuthorizationStatus.authorized || AuthorizationStatus.provisional => PushPermission.granted,
      AuthorizationStatus.deniedPermanently => PushPermission.denied,
      AuthorizationStatus.denied => ios || refused ? PushPermission.denied : PushPermission.notAsked,
      AuthorizationStatus.notDetermined => PushPermission.notAsked,
    };

/// Firebase from the Q119 dart-defines; no google-services.json or plist in the repo.
Future<void> initFirebase() => Firebase.initializeApp(
  options: FirebaseOptions(
    apiKey: AppConfig.firebaseApiKey,
    appId: AppConfig.firebaseAppId,
    messagingSenderId: AppConfig.firebaseMessagingSenderId,
    projectId: AppConfig.firebaseProjectId,
    iosBundleId: AppConfig.firebaseIosBundleId.isEmpty ? null : AppConfig.firebaseIosBundleId,
  ),
);

/// Called once in `main`. Push is on only with `PUSH_ENABLED` and a Firebase that starts: any failure means push
/// off (no token, no permission, no banner), never a crash. The inbox works either way.
Future<PushMessaging?> startPush({bool enabled = AppConfig.pushEnabled, Future<void> Function() init = initFirebase}) async {
  if (!enabled) return null;
  try {
    await init();
    return FirebasePushMessaging();
  } on Object {
    return null;
  }
}

/// Null when push is off. `main` overrides it with [startPush]'s result.
final pushMessagingProvider = Provider<PushMessaging?>((ref) => null);

const pushSentKey = 'push.sent';
const pushAskedKey = 'push.asked';

/// `PUT /fan/me/push-token` while signed in: the current token at sign-in and at launch, then every refresh. A token
/// already sent from this session (`push.sent`, wiped on sign-out) is not sent again. Follows play no part
/// (multi-club Q73). Watched by the app for its whole life.
final pushRegistrarProvider = Provider<void>((ref) {
  final push = ref.watch(pushMessagingProvider);
  if (push == null || !ref.watch(sessionProvider)) return;
  final prefs = SharedPreferencesAsync();

  Future<void> send(String? token) async {
    if (token == null || token.isEmpty) return;
    try {
      if (await prefs.getString(pushSentKey) == token || !ref.mounted) return;
      await ref
          .read(apiClientProvider)
          .put('/fan/me/push-token', body: {'token': token, 'platform': Platform.isIOS ? 'ios' : 'android'});
      // Signed out meanwhile: signOut wiped the key, and this token belongs to the old session.
      if (ref.mounted) await prefs.setString(pushSentKey, token);
    } on Object {
      // Offline or refused: sent again at the next launch, sign-in or refresh.
    }
  }

  final sub = push.onTokenRefresh.listen(send);
  ref.onDispose(sub.cancel);
  unawaited(push.token().then(send, onError: (Object _) {}));
});

/// The app paths a push may open (docs/04-api.md §Notifications, push wire); anything else is ignored.
final _pushPath = RegExp(
  r'^/(tickets|orders)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$|^/events/\d+$|^/$',
);

String? pushPathOf(Map<String, Object?> data) => switch (data['path']) {
  final String p when _pushPath.hasMatch(p) => p,
  _ => null,
};

/// The permission prompt, never at launch (lot-4 Q126): after the first PAID order screen or a join with push on,
/// once per install (`push.asked`, kept on sign-out). Never throws.
Future<void> maybeAskPermission(PushMessaging? push) async {
  if (push == null) return;
  try {
    final prefs = SharedPreferencesAsync();
    if (await prefs.getBool(pushAskedKey) ?? false) return;
    await prefs.setBool(pushAskedKey, true);
    await push.requestPermission();
  } on Object {
    // No prompt this time.
  }
}

/// Profil's Notifications row; null when push is off.
final pushPermissionProvider = FutureProvider<PushPermission?>((ref) => ref.watch(pushMessagingProvider)?.permission());

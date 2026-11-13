import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/launch_splash.dart';
import 'package:jomhor_fan/app/locale.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/core/push/push.dart';
import 'package:jomhor_fan/core/push/push_banner.dart';
import 'package:jomhor_fan/features/checkout/checkout_repository.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/features/inbox/inbox.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/app_theme.dart';

class JomhorApp extends ConsumerStatefulWidget {
  const new({super.key});

  @override
  ConsumerState<JomhorApp> createState() => _JomhorAppState();
}

class _JomhorAppState extends ConsumerState<JomhorApp> {
  late final AppLifecycleListener _lifecycle;
  StreamSubscription<PushMessage>? _opened;

  /// Matchday mode was checked for this launch or resume.
  var _matchdayChecked = false;

  @override
  void initState() {
    super.initState();
    // Back from the background (e.g. the fan waited for the opening in another app): re-read sale states, and
    // the tickets (saved ones first, which also re-anchors the server clock) and the pending cash code, then check
    // matchday mode again.
    _lifecycle = AppLifecycleListener(
      onResume: () {
        _matchdayChecked = false;
        ref
          ..invalidate(upcomingEventsProvider)
          ..invalidate(eventDetailProvider)
          ..invalidate(ticketsProvider)
          ..invalidate(pendingCashProvider)
          ..invalidate(inboxProvider)
          ..invalidate(pushPermissionProvider);
      },
    );
    // A tapped notification (docs/04-api.md §Notifications, push wire): only the whitelisted paths open.
    final push = ref.read(pushMessagingProvider);
    if (push != null) {
      _opened = push.onOpened.listen(_openPush);
      // Cold start from a notification: after the first frame, once the router has its first location.
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        try {
          if (await push.initialMessage() case final m? when mounted) _openPush(m);
        } on Object {
          // No launch message.
        }
      });
    }
  }

  void _openPush(PushMessage m) {
    if (pushPathOf(m.data) case final path?) openPushPath(ref, path);
  }

  /// Matchday mode (docs/02 §Fan app design direction): from T0 − 4 h the app opens straight on the next
  /// ticket, from the saved tickets (airplane mode included). Only from a tab root, never over another screen.
  void _openMatchday(TicketsSnapshot? snapshot) {
    if (_matchdayChecked || snapshot == null) return;
    _matchdayChecked = true;
    final ticket = matchdayTicket(snapshot.tickets, snapshot.clock.now());
    if (ticket == null) return;
    final router = ref.read(routerProvider);
    final path = router.state.uri.path;
    if (path == '/' || path == '/tickets') unawaited(router.push('/tickets/${ticket.id}'));
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    unawaited(_opened?.cancel());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(pushRegistrarProvider);
    // Only settled data: a reload (resume) first emits the previous snapshot, whose clock may have stopped
    // during deep sleep; checking it would latch the mode before the re-anchored snapshot arrives.
    ref.listen(ticketsProvider, (_, next) {
      if (!next.isLoading) _openMatchday(next.value?.snapshot);
    });
    return MaterialApp.router(
      debugShowCheckedModeBanner: false,
      onGenerateTitle: (context) => AppLocalizations.of(context).appTitle,
      theme: buildAppTheme(),
      // The Profil choice, else the device's; FR first, so an unsupported device locale resolves to FR.
      locale: ref.watch(appLocaleProvider),
      supportedLocales: AppLocalizations.supportedLocales,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      routerConfig: ref.watch(routerProvider),
      builder: (context, child) =>
          PushBannerHost(child: ref.watch(launchSplashProvider) ? LaunchSplash(child: child!) : child!),
    );
  }
}

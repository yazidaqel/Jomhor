import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/core/event_key.dart';
import 'package:jomhor_scanner/features/enrol/enrol_screen.dart';
import 'package:jomhor_scanner/features/event/event_controller.dart';
import 'package:jomhor_scanner/features/event/event_screen.dart';
import 'package:jomhor_scanner/features/home/device_status.dart';
import 'package:jomhor_scanner/features/scan/scan_screen.dart';
import 'package:jomhor_scanner/features/staff/unlock_screen.dart';

/// The device's single route: enrolment, event mode, the staff lock or the scan screen, from `config`
/// ([gateScreenOf]). A revocation wipes `config`, so the device lands back on enrolment.
class HomeScreen extends ConsumerWidget {
  const new({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final config = ref.watch(configProvider).value;
    if (config == null) return const Scaffold();
    final screen = gateScreenOf(config, keyLoaded: ref.watch(eventKeyProvider) != null);
    // Once ACTIVE the event refresh (K_e restore, 5-min manifest refresh) runs under every screen.
    if (screen != GateScreen.enrol) ref.watch(eventControllerProvider.select((_) => null));
    return switch (screen) {
      GateScreen.enrol => EnrolScreen(pending: config.deviceId != null),
      GateScreen.event => EventScreen(config: config),
      GateScreen.unlock => UnlockScreen(timeZone: config.eventTimezone),
      GateScreen.scan => const ScanScreen(),
    };
  }
}

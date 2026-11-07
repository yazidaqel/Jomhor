import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/features/home/device_status.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';

/// Start screen: the device's enrolment state. Enrolment, event mode and scanning come in FL-11 to FL-14.
class HomeScreen extends ConsumerWidget {
  const new({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final status = ref.watch(deviceStatusProvider).value;
    return Scaffold(
      appBar: AppBar(title: Text(l10n.appTitle)),
      body: Padding(
        padding: const EdgeInsets.all(JomhorSpace.xxl),
        child: switch (status) {
          null => const SizedBox.shrink(),
          DeviceStatus.notEnrolled => Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(l10n.deviceNotEnrolled, style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: JomhorSpace.s),
              Text(l10n.deviceNotEnrolledHint),
            ],
          ),
          DeviceStatus.pending => Text(l10n.devicePending, style: Theme.of(context).textTheme.titleLarge),
          DeviceStatus.active => Text(l10n.deviceActive, style: Theme.of(context).textTheme.titleLarge),
        },
      ),
    );
  }
}

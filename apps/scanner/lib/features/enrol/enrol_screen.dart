import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/features/enrol/enrol_controller.dart';
import 'package:jomhor_scanner/features/scan/scan_screen.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';

/// Copy for a gate call failure (codes of docs/04 and of the controllers).
String gateErrorText(AppLocalizations l, String code) => switch (code) {
  GateApiError.network => l.errorNetwork,
  'UNAUTHORIZED' => l.errorEnrolCode,
  EnrolController.codeInvalid => l.enrolCodeInvalid,
  EnrolController.labelInvalid => l.enrolLabelInvalid,
  _ => l.errorGeneric,
};

/// Enrôlement (DESIGN Lot 2 adjustments): scan the enrol QR, type a label, then the fingerprint at 28 px and
/// "En attente d'approbation" while the admin approves the device in B6.
class EnrolScreen extends ConsumerStatefulWidget {
  const new({required this.pending, super.key, this.camera});

  /// Enrolled, waiting for approval.
  final bool pending;
  final CameraBuilder? camera;

  @override
  ConsumerState<EnrolScreen> createState() => _EnrolScreenState();
}

class _EnrolScreenState extends ConsumerState<EnrolScreen> {
  final _label = TextEditingController();

  @override
  void dispose() {
    _label.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final state = ref.watch(enrolControllerProvider);
    final controller = ref.read(enrolControllerProvider.notifier);
    final theme = Theme.of(context);
    final List<Widget> body;
    if (widget.pending) {
      ref.watch(pendingPollProvider);
      body = [
        Text(l.devicePending, style: theme.textTheme.titleLarge),
        const SizedBox(height: JomhorSpace.xxl),
        Text(l.enrolFingerprint, style: JomhorType.label.copyWith(color: JomhorColors.textMuted)),
        const SizedBox(height: JomhorSpace.s),
        Text(state.fingerprint ?? '', style: JomhorType.h1.copyWith(letterSpacing: 2)),
        const SizedBox(height: JomhorSpace.l),
        Text(l.enrolPendingHint),
      ];
    } else {
      body = [
        Text(l.deviceNotEnrolled, style: theme.textTheme.titleLarge),
        const SizedBox(height: JomhorSpace.s),
        Text(l.deviceNotEnrolledHint),
        const SizedBox(height: JomhorSpace.xxl),
        if (state.code != null) ...[
          Text(l.enrolCodeRead, style: JomhorType.label.copyWith(color: JomhorColors.successText)),
          const SizedBox(height: JomhorSpace.l),
          TextField(
            controller: _label,
            maxLength: 40,
            decoration: InputDecoration(labelText: l.enrolLabel, hintText: l.enrolLabelHint),
          ),
          const SizedBox(height: JomhorSpace.l),
          FilledButton(onPressed: state.busy ? null : () => controller.submit(_label.text), child: Text(l.enrolSubmit)),
        ] else if (state.scanning)
          SizedBox(
            height: 320,
            child: ClipRRect(
              borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.card)),
              child: ColoredBox(
                color: JomhorColors.surface,
                child: (widget.camera ?? (onCode) => QrCamera(onCode: onCode))(controller.onCode),
              ),
            ),
          )
        else
          FilledButton(onPressed: controller.openCamera, child: Text(l.enrolScan)),
        if (state.error case final error?) ...[
          const SizedBox(height: JomhorSpace.l),
          Text(gateErrorText(l, error), style: JomhorType.body.copyWith(color: JomhorColors.danger)),
        ],
      ];
    }
    return Scaffold(
      appBar: AppBar(title: Text(l.enrolTitle)),
      body: SafeArea(
        child: ListView(padding: const EdgeInsets.all(JomhorSpace.xxl), children: body),
      ),
    );
  }
}

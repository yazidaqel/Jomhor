import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/core/event_time.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/enrol/enrol_screen.dart' show gateErrorText;
import 'package:jomhor_scanner/features/event/event_controller.dart';
import 'package:jomhor_scanner/features/event/event_service.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';

/// Événement (DESIGN Lot 2 adjustments): the assignment, manifest download progress, drift check result.
class EventScreen extends ConsumerWidget {
  const new({required this.config, super.key});

  final ConfigData config;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context).toLanguageTag();
    final state = ref.watch(eventControllerProvider);
    final controller = ref.read(eventControllerProvider.notifier);
    final assignments = state.assignments;
    final current = assignments?.where((a) => a.id == config.eventId).firstOrNull;
    final muted = JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted);
    final seconds = (config.serverTimeOffsetMs.abs() / 1000).round();
    final children = <Widget>[
      Text(
        config.lastServerTimeAt == null
            ? l.eventDriftUnknown
            : driftOk(config)
            ? l.eventDriftOk(seconds)
            : l.eventDriftRefused(seconds),
        style: JomhorType.body.copyWith(color: driftOk(config) ? JomhorColors.successText : JomhorColors.danger),
      ),
      const SizedBox(height: JomhorSpace.xl),
      if (config.eventId != null) ...[
        _EventCard(
          title: config.eventTitle ?? '',
          when: config.startsAt == null ? '' : eventDateTime(config.startsAt!, config.eventTimezone, locale),
          assignment: current,
        ),
        const SizedBox(height: JomhorSpace.l),
        if (state.stage case final stage?) ...[
          LinearProgressIndicator(value: (stage.index + 1) / ManifestStage.values.length),
          const SizedBox(height: JomhorSpace.s),
          Text(switch (stage) {
            ManifestStage.downloading => l.eventStageDownloading,
            ManifestStage.verifying => l.eventStageVerifying,
            ManifestStage.saving => l.eventStageSaving,
          }, style: muted),
        ] else
          FilledButton(
            onPressed: () => controller.refresh(force: true),
            child: Text(config.manifestVersion == 0 ? l.eventLoad : l.eventRetry),
          ),
      ] else if (assignments == null)
        if (state.error == null) const Center(child: CircularProgressIndicator()) else const SizedBox.shrink()
      else if (assignments.isEmpty) ...[
        Text(l.eventNone, style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: JomhorSpace.s),
        Text(l.eventNoneHint),
      ] else
        for (final a in assignments) ...[
          _EventCard(
            title: a.title,
            when: eventDateTime(a.startsAt, a.timezone, locale),
            assignment: a,
            onTap: state.stage == null ? () => controller.select(a) : null,
          ),
          const SizedBox(height: JomhorSpace.m),
        ],
      if (state.loaded case (final version, final rows)) ...[
        const SizedBox(height: JomhorSpace.l),
        Text(l.eventLoaded(version, rows), style: muted),
      ],
      if (state.error case final error?) ...[
        const SizedBox(height: JomhorSpace.l),
        Text(switch (error) {
          EventController.rejected => l.eventRejected,
          EventController.drift => l.eventDriftRefused(seconds),
          GateApiError.manifestNotReady =>
            state.availableAt == null
                ? l.eventNotReadyNoTime
                : l.eventNotReady(shortTime(state.availableAt!, config.eventTimezone)),
          'EVENT_CLOSED' => l.eventClosed,
          _ => gateErrorText(l, error),
        }, style: JomhorType.body.copyWith(color: JomhorColors.danger)),
        if (config.eventId == null) ...[
          const SizedBox(height: JomhorSpace.l),
          FilledButton(onPressed: () => controller.refresh(force: true), child: Text(l.eventRetry)),
        ],
      ],
    ];
    return Scaffold(
      appBar: AppBar(title: Text(l.eventTitle)),
      body: SafeArea(
        child: ListView(padding: const EdgeInsets.all(JomhorSpace.xxl), children: children),
      ),
    );
  }
}

class _EventCard extends StatelessWidget {
  const new({required this.title, required this.when, required this.assignment, this.onTap});

  final String title;
  final String when;
  final Assignment? assignment;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final a = assignment;
    final muted = JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted);
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(JomhorSpace.l),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: JomhorType.h2),
              const SizedBox(height: JomhorSpace.xs),
              Text(a == null ? when : '$when · ${a.venueName}', style: muted),
              if (a != null) ...[
                const SizedBox(height: JomhorSpace.s),
                Text(l.eventGate(a.gateCode, a.gateName), style: JomhorType.label.copyWith(color: JomhorColors.accent)),
                const SizedBox(height: JomhorSpace.xs),
                Text(l.eventManifestAt(shortTime(a.manifestAvailableAt, a.timezone)), style: muted),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

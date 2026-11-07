import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/features/sync/sync_controller.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';

/// Right side of the screen 6 top line: online/offline (icon + word, never colour only) and the unsynced count.
class SyncLine extends ConsumerWidget {
  const new({required this.unsynced, required this.color, super.key});

  final int unsynced;
  final Color color;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final online = ref.watch(syncControllerProvider.select((s) => s.online)) ?? false;
    final style = JomhorType.label.copyWith(color: color);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(online ? Icons.cloud_done_outlined : Icons.sync_disabled_rounded, size: 14, color: color),
        const SizedBox(width: JomhorSpace.xs),
        Flexible(
          child: Text(
            online ? l.scanSyncOnline(unsynced) : l.scanSyncOffline(unsynced),
            style: style,
            overflow: TextOverflow.ellipsis,
          ),
        ),
      ],
    );
  }
}

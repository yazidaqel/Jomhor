import 'package:flutter/material.dart';
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

/// A failed load: fan copy for the error code and a retry button.
class ErrorPanel extends StatelessWidget {
  const new({required this.error, required this.onRetry, super.key});

  final Object error;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final code = error is ApiError ? (error as ApiError).code : ApiError.unexpectedResponse;
    return Padding(
      padding: const EdgeInsets.all(JomhorSpace.xl),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(errorText(l, code), textAlign: TextAlign.center),
          const SizedBox(height: JomhorSpace.l),
          OutlinedButton(onPressed: onRetry, child: Text(l.retry)),
        ],
      ),
    );
  }
}

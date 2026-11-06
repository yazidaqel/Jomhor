import 'package:flutter/material.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

/// Stands in for a tab until its feature lands (FL-4 profile/auth, FL-5 home, FL-7 tickets).
class PlaceholderScreen extends StatelessWidget {
  const new({required this.title, super.key});

  final String title;

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(JomhorSpace.xl),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.headlineMedium),
            const SizedBox(height: JomhorSpace.m),
            Text(AppLocalizations.of(context).placeholderComingSoon),
          ],
        ),
      ),
    ),
  );
}

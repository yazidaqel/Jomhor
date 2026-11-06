import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';

class AppShell extends StatelessWidget {
  const new({required this.shell, super.key});

  final StatefulNavigationShell shell;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Scaffold(
      body: shell,
      bottomNavigationBar: NavigationBar(
        selectedIndex: shell.currentIndex,
        // Re-tapping the current tab returns to its root.
        onDestinationSelected: (i) => shell.goBranch(i, initialLocation: i == shell.currentIndex),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.home_outlined), label: l.navHome),
          NavigationDestination(icon: const Icon(Icons.confirmation_number_outlined), label: l.navTickets),
          NavigationDestination(icon: const Icon(Icons.person_outline), label: l.navProfile),
        ],
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/core/event_time.dart';
import 'package:jomhor_scanner/core/server_clock.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/home/device_status.dart';
import 'package:jomhor_scanner/features/scan/scan_controller.dart';
import 'package:jomhor_scanner/features/staff/pin_pad.dart';
import 'package:jomhor_scanner/features/staff/staff_session.dart';
import 'package:jomhor_scanner/features/supervisor/supervisor_service.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';

enum _Step { pick, pin, actions, reason, ticket, gate, lookup }

String reasonLabel(AppLocalizations l, OverrideReason r) => switch (r) {
  OverrideReason.idChecked => l.reasonID_CHECKED,
  OverrideReason.deviceFault => l.reasonDEVICE_FAULT,
  OverrideReason.damagedTicket => l.reasonDAMAGED_TICKET,
  OverrideReason.other => l.reasonOTHER,
};

String statusLabel(AppLocalizations l, String status) => switch (status) {
  'VALID' => l.statusVALID,
  'CANCELLED' => l.statusCANCELLED,
  'REFUNDED' => l.statusREFUNDED,
  'BLOCKED' => l.statusBLOCKED,
  // A status this build does not know: shown as sent, never dressed up as another one.
  _ => status,
};

/// DESIGN screen 6, Lot 2 adjustments: pick a SUPERVISOR → their PIN → Dérogation · Changer de porte · Recherche
/// billet, with the override count "n / 20". Drawn inside the scan screen (not a route), so the idle lock that
/// swaps the scan screen for Déverrouillage takes the sheet with it. Offline throughout.
class SupervisorSheet extends ConsumerStatefulWidget {
  const new({required this.camera, required this.onClose, this.ticketNo, super.key});

  /// The camera for "Scanner le billet"; the scan screen's own camera is not mounted meanwhile.
  final Widget Function(ValueChanged<String> onCode) camera;
  final VoidCallback onClose;

  /// The ticket number of the result on screen, if any: the override starts from it, no rescan.
  final String? ticketNo;

  @override
  ConsumerState<SupervisorSheet> createState() => _SupervisorSheetState();
}

class _SupervisorSheetState extends ConsumerState<SupervisorSheet> {
  _Step _step = _Step.pick;
  late StaffData _supervisor;
  SupervisorGrant? _grant;
  late OverrideReason _reason;
  int? _count;
  List<TicketMatch>? _matches;
  List<Gate>? _gates;
  String? _message;
  var _scanning = false;
  var _busy = false;
  final _input = TextEditingController();

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  SupervisorService get _service => ref.read(supervisorServiceProvider);

  void _go(_Step step) => setState(() {
    _step = step;
    _matches = null;
    _message = null;
    _scanning = false;
  });

  /// Back to the supervisor list without the grant: the next action asks for the PIN again.
  void _relock([String? message]) {
    _go(_Step.pick);
    setState(() {
      _grant = null;
      _count = null;
      _message = message;
    });
  }

  /// An action button: only while the PIN is recent (SupervisorGrant.ttl); the service checks again.
  void _act(VoidCallback action) {
    if (_grant!.validAt(ref.read(serverClockProvider).nowMs())) {
      action();
    } else {
      _relock(AppLocalizations.of(context).supervisorExpired);
    }
  }

  Future<String?> _verify(String pin) async {
    final l = AppLocalizations.of(context);
    final (outcome, grant) = await ref.read(staffSessionProvider).verifySupervisor(_supervisor.staffId!, pin);
    if (grant == null) {
      return outcome.lockedUntilMs == null
          ? l.unlockWrong(outcome.attemptsLeft)
          : l.unlockLocked(shortTime(outcome.lockedUntilMs!, ref.read(configProvider).value?.eventTimezone));
    }
    final count = await _service.overrideCount();
    if (mounted) {
      setState(() {
        _grant = grant;
        _count = count;
      });
      _go(_Step.actions);
    }
    return null;
  }

  Future<void> _search(String raw) async {
    if (raw.trim().isEmpty || _busy) return;
    setState(() {
      _busy = true;
      _scanning = false;
    });
    final l = AppLocalizations.of(context);
    try {
      final matches = await _service.find(_grant!, raw);
      if (mounted) {
        setState(() {
          _matches = matches;
          _message = matches.isEmpty ? l.ticketNoMatch : null;
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _override(TicketMatch ticket) async {
    if (_busy) return;
    setState(() => _busy = true);
    final l = AppLocalizations.of(context);
    String? message;
    try {
      await ref.read(scanControllerProvider.notifier).admitOverride(_grant!, _reason, ticket.ticketId);
      widget.onClose();
      return;
    } on SupervisorGrantInvalid {
      if (mounted) _relock(l.supervisorExpired);
      return;
    } on OverrideCapReached {
      message = l.supervisorCapReached(SupervisorService.overrideCap);
    } on TicketNotInManifest {
      message = l.ticketNotInManifest;
    } on Object {
      message = l.errorGeneric;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    if (mounted) setState(() => _message = message);
  }

  Future<void> _openGates() async {
    final l = AppLocalizations.of(context);
    try {
      final gates = await _service.gates();
      if (!mounted) return;
      _go(_Step.gate);
      setState(() => _gates = gates);
    } on Object {
      if (mounted) setState(() => _message = l.errorGeneric);
    }
  }

  Future<void> _changeGate(String gateId) async {
    final l = AppLocalizations.of(context);
    try {
      await _service.changeGate(_grant!, gateId);
    } on SupervisorGrantInvalid {
      if (mounted) _relock(l.supervisorExpired);
      return;
    } on Object {
      if (mounted) setState(() => _message = l.errorGeneric);
      return;
    }
    await ref.read(scanControllerProvider.notifier).refresh();
    widget.onClose();
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final grant = _grant;
    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        title: Text(grant == null ? l.supervisorTitle : _supervisor.label),
        actions: [
          if (_count case final count? when grant != null)
            Center(
              child: Text(
                l.supervisorOverrideCount(count, SupervisorService.overrideCap),
                style: JomhorType.label.copyWith(
                  color: count >= SupervisorService.overrideCap ? JomhorColors.danger : JomhorColors.textMuted,
                ),
              ),
            ),
          IconButton(tooltip: l.supervisorClose, icon: const Icon(Icons.close_rounded), onPressed: widget.onClose),
        ],
      ),
      body: SafeArea(child: _body(l)),
    );
  }

  Widget _body(AppLocalizations l) => switch (_step) {
    _Step.pick => _pick(l),
    _Step.pin => PinPad(
      key: ValueKey(_supervisor.staffId),
      title: l.unlockPin(_supervisor.label),
      onPin: _verify,
      backLabel: l.unlockChangeName,
      onBack: () => _go(_Step.pick),
    ),
    _Step.actions => _actions(l),
    _Step.reason => _list([
      _heading(l.overrideReasonTitle),
      for (final r in OverrideReason.values)
        _tile(reasonLabel(l, r), () {
          _reason = r;
          _input.text = widget.ticketNo ?? '';
          _go(_Step.ticket);
        }),
      _back(l, _Step.actions),
    ]),
    _Step.ticket => _ticket(l, override: true),
    _Step.lookup => _ticket(l, override: false),
    _Step.gate => _gatesList(l),
  };

  Widget _list(List<Widget> children) => ListView(padding: const EdgeInsets.all(JomhorSpace.xxl), children: children);

  Widget _heading(String text) => Padding(
    padding: const EdgeInsets.only(bottom: JomhorSpace.l),
    child: Text(text, style: Theme.of(context).textTheme.titleLarge),
  );

  Widget _tile(String title, VoidCallback? onTap, {String? subtitle, Widget? trailing}) => ListTile(
    minTileHeight: JomhorSize.ctaHeight,
    title: Text(title, style: JomhorType.h2),
    subtitle: subtitle == null ? null : Text(subtitle),
    trailing: trailing ?? (onTap == null ? null : const Icon(Icons.chevron_right_rounded)),
    onTap: onTap,
  );

  Widget _back(AppLocalizations l, _Step to) => Padding(
    padding: const EdgeInsets.only(top: JomhorSpace.l),
    child: TextButton(onPressed: to == _Step.pick ? _relock : () => _go(to), child: Text(l.supervisorBack)),
  );

  /// The step's error or notice, if any.
  Widget _note() => switch (_message) {
    final message? => Padding(
      padding: const EdgeInsets.only(top: JomhorSpace.m),
      child: Text(message, style: JomhorType.body.copyWith(color: JomhorColors.danger)),
    ),
    null => const SizedBox.shrink(),
  };

  Widget _pick(AppLocalizations l) {
    final supervisors = [
      for (final s in ref.watch(staffListProvider).value ?? const <StaffData>[])
        if (s.role == 'SUPERVISOR') s,
    ];
    return _list([
      _heading(supervisors.isEmpty ? l.supervisorNone : l.supervisorPickName),
      for (final s in supervisors)
        _tile(s.label, () {
          _supervisor = s;
          _go(_Step.pin);
        }),
      _note(),
    ]);
  }

  Widget _actions(AppLocalizations l) {
    final capped = (_count ?? 0) >= SupervisorService.overrideCap;
    Widget action(String label, IconData icon, VoidCallback? onPressed) => Padding(
      padding: const EdgeInsets.only(bottom: JomhorSpace.m),
      child: OutlinedButton.icon(
        style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(JomhorSize.ctaHeight)),
        onPressed: onPressed,
        icon: Icon(icon),
        label: Text(label),
      ),
    );
    return _list([
      action(l.actionOverride, Icons.verified_user_outlined, capped ? null : () => _act(() => _go(_Step.reason))),
      if (capped)
        Padding(
          padding: const EdgeInsets.only(bottom: JomhorSpace.m),
          child: Text(
            l.supervisorCapReached(SupervisorService.overrideCap),
            style: JomhorType.body.copyWith(color: JomhorColors.danger),
          ),
        ),
      action(l.actionGate, Icons.alt_route_rounded, () => _act(_openGates)),
      action(
        l.actionLookup,
        Icons.search_rounded,
        () => _act(() {
          _input.clear();
          _go(_Step.lookup);
        }),
      ),
      _note(),
    ]);
  }

  Widget _ticket(AppLocalizations l, {required bool override}) {
    final matches = _matches;
    return _list([
      _heading(override ? reasonLabel(l, _reason) : l.actionLookup),
      TextField(
        controller: _input,
        autofocus: true,
        textCapitalization: TextCapitalization.characters,
        decoration: InputDecoration(labelText: l.ticketNoLabel),
        onSubmitted: _search,
      ),
      const SizedBox(height: JomhorSpace.m),
      Row(
        children: [
          Expanded(
            child: FilledButton(onPressed: _busy ? null : () => _search(_input.text), child: Text(l.ticketSearch)),
          ),
          const SizedBox(width: JomhorSpace.m),
          Expanded(
            child: OutlinedButton.icon(
              onPressed: () => setState(() => _scanning = !_scanning),
              icon: const Icon(Icons.qr_code_scanner_rounded),
              label: Text(l.ticketScan),
            ),
          ),
        ],
      ),
      if (_scanning) ...[
        const SizedBox(height: JomhorSpace.m),
        AspectRatio(
          aspectRatio: 1,
          child: ClipRRect(
            borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.card)),
            child: widget.camera(_search),
          ),
        ),
      ],
      if (_busy)
        const Padding(
          padding: EdgeInsets.only(top: JomhorSpace.m),
          child: LinearProgressIndicator(),
        ),
      _note(),
      if (matches != null && matches.isNotEmpty) ...[
        const SizedBox(height: JomhorSpace.l),
        if (override) Text(l.ticketPick(matches.length), style: JomhorType.label),
        // Zone, status and initials only (docs/02 §Manual lookup): never a name, never the ticket id.
        for (final m in matches)
          _tile(
            m.zoneName ?? '${m.zoneId}',
            override && !_busy ? () => _override(m) : null,
            subtitle: m.initials == null || m.initials!.isEmpty
                ? statusLabel(l, m.status)
                : l.ticketDetail(statusLabel(l, m.status), m.initials!),
          ),
      ],
      // Ending a lookup ends the supervisor's session: the device goes back to the gate agent.
      _back(l, override ? _Step.reason : _Step.pick),
    ]);
  }

  Widget _gatesList(AppLocalizations l) {
    final current = ref.watch(configProvider).value?.gateId;
    return _list([
      _heading(l.gatePickTitle),
      for (final g in _gates ?? const <Gate>[])
        _tile(
          l.eventGate(g.code, g.name),
          g.gateId == current ? null : () => _changeGate(g.gateId!),
          trailing: g.gateId == current ? Text(l.gateCurrent, style: JomhorType.label) : null,
        ),
      _note(),
      _back(l, _Step.actions),
    ]);
  }
}

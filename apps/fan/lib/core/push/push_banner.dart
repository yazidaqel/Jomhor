import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/core/push/push.dart';
import 'package:jomhor_fan/features/inbox/inbox.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// Opens a push's app path ([pushPathOf] already checked it): a ticket is read again first (it may have just been
/// received), `/` is the Accueil tab, anything else opens over the current screen. Signed out, the router sends the
/// fan to the login with `from`.
void openPushPath(WidgetRef ref, String path) {
  final router = ref.read(routerProvider);
  if (path == '/') return router.go('/');
  if (path.startsWith('/tickets/')) ref.invalidate(ticketsProvider);
  unawaited(router.push(path));
}

final _ticketPath = RegExp(r'^/tickets/[^/]+$');

/// A push received in the foreground (DESIGN.md Lot 4, "Push in foreground"): a top banner, title then body, tap →
/// its path, swipe up or 4 s → gone. Never over screen 5 (nothing over the QR), and it goes when screen 5 opens.
/// The inbox is read again either way. Mounted above the navigator, in `MaterialApp.router`'s builder.
class PushBannerHost extends ConsumerStatefulWidget {
  const new({required this.child, super.key});

  final Widget child;

  static const shownFor = Duration(seconds: 4);

  @override
  ConsumerState<PushBannerHost> createState() => _PushBannerHostState();
}

class _PushBannerHostState extends ConsumerState<PushBannerHost> {
  StreamSubscription<PushMessage>? _sub;
  late final GoRouter _router;
  PushMessage? _shown;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _router = ref.read(routerProvider)..routerDelegate.addListener(_onRoute);
    _sub = ref.read(pushMessagingProvider)?.onForeground.listen(_show);
  }

  bool get _overTicket => _ticketPath.hasMatch(_router.state.uri.path);

  void _show(PushMessage m) {
    ref.invalidate(inboxProvider);
    if (_overTicket || (m.title == null && m.body == null)) return;
    _timer?.cancel();
    _timer = Timer(PushBannerHost.shownFor, _dismiss);
    setState(() => _shown = m);
  }

  void _onRoute() {
    if (_shown != null && _overTicket) _dismiss();
  }

  void _dismiss() {
    _timer?.cancel();
    if (mounted && _shown != null) setState(() => _shown = null);
  }

  void _open(PushMessage m) {
    _dismiss();
    if (pushPathOf(m.data) case final path?) openPushPath(ref, path);
  }

  @override
  void dispose() {
    _timer?.cancel();
    unawaited(_sub?.cancel());
    _router.routerDelegate.removeListener(_onRoute);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final m = _shown;
    return Stack(
      children: [
        widget.child,
        if (m != null)
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: SafeArea(
              bottom: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(JomhorSpace.l, JomhorSpace.s, JomhorSpace.l, 0),
                child: TweenAnimationBuilder<Offset>(
                  key: ObjectKey(m),
                  tween: Tween(begin: const Offset(0, -1.3), end: Offset.zero),
                  duration: motionOf(context, JomhorMotion.base),
                  curve: Curves.easeOutCubic,
                  builder: (context, offset, child) =>
                      FractionalTranslation(key: const Key('push.banner.slide'), translation: offset, child: child),
                  child: Dismissible(
                    key: ObjectKey(m),
                    direction: DismissDirection.up,
                    onDismissed: (_) => _dismiss(),
                    child: _Banner(message: m, onTap: () => _open(m)),
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class _Banner extends StatelessWidget {
  const new({required this.message, required this.onTap});

  final PushMessage message;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    container: true,
    child: Material(
      key: const Key('push.banner'),
      color: JomhorColors.surface2,
      elevation: 8,
      shape: const RoundedRectangleBorder(
        side: BorderSide(color: JomhorColors.border),
        borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (message.title case final title?)
                Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: JomhorType.body.copyWith(fontWeight: FontWeight.w600),
                ),
              if (message.body case final body?) ...[
                const SizedBox(height: 2),
                Text(
                  body,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary),
                ),
              ],
            ],
          ),
        ),
      ),
    ),
  );
}

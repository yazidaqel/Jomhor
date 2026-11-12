import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/app/launch_splash.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/brand.dart';

import 'helpers.dart';

void main() {
  Widget host(Widget brand, {bool reduce = false}) => MediaQuery(
    data: MediaQueryData(disableAnimations: reduce),
    child: Directionality(
      textDirection: TextDirection.ltr,
      child: Center(child: brand),
    ),
  );

  /// The radius of each dot as painted now, in the 120 viewBox, in the J's order.
  List<double> radii(WidgetTester tester) {
    final radii = <double>[];
    expect(
      find.byType(CustomPaint),
      paints..everything((method, arguments) {
        if (method == #drawCircle) radii.add(arguments[1] as double);
        return true;
      }),
    );
    return radii;
  }

  final still = [for (final (_, _, r) in JomhorBrand.dots) r];
  Duration ms(int milliseconds) => Duration(milliseconds: milliseconds);

  testWidgets('a still mark paints the logo as drawn and schedules no frame', (tester) async {
    await tester.pumpWidget(host(const JomhorMark()));
    expect(radii(tester), still);
    expect(tester.binding.hasScheduledFrame, isFalse);
  });

  testWidgets('entrance: the dots assemble along the J, the amber one last with an overshoot, then it rests', (
    tester,
  ) async {
    await tester.pumpWidget(host(const JomhorLockup(height: 56, entrance: true)));
    expect(radii(tester), everyElement(0));

    await tester.pump(ms(200));
    final assembling = radii(tester);
    expect(assembling.first, still.first, reason: 'the first dot is in place');
    expect(assembling[5], inExclusiveRange(0, still[5]), reason: 'the sixth is on its way');
    expect(assembling.last, 0, reason: 'the amber dot comes last');

    await tester.pump(ms(180));
    expect(radii(tester).last, greaterThan(still.last), reason: 'overshoot');
    expect(tester.binding.hasScheduledFrame, isTrue);

    await tester.pump(ms(60));
    expect(radii(tester), still);
    expect(tester.binding.hasScheduledFrame, isFalse);
  });

  testWidgets('busy: the wave runs along the eleven to the amber dot, loops, and ends its pass when busy ends', (
    tester,
  ) async {
    await tester.pumpWidget(host(const JomhorMark(busy: true)));
    expect(radii(tester), still, reason: 'the wave starts from the still mark');

    // 0.3 of the 1100 ms period: the bump is on the sixth dot.
    await tester.pump(ms(330));
    expect(radii(tester)[5], closeTo(still[5] * 1.2, 1e-9));
    expect(radii(tester).last, still.last);

    // 0.65: the bump has left the eleven, the amber dot is at the top of its swell.
    await tester.pump(ms(385));
    expect(radii(tester).sublist(0, 11), still.sublist(0, 11));
    expect(radii(tester).last, closeTo(still.last * 1.18, 1e-9));
    final (x11, y11, eleventh) = JomhorBrand.dots[10];
    final (x12, y12, _) = JomhorBrand.dots[11];
    expect(
      radii(tester).last + eleventh,
      lessThan((Offset(x12, y12) - Offset(x11, y11)).distance),
      reason: 'the swollen amber dot does not touch the eleventh',
    );

    await tester.pump(const Duration(seconds: 5));
    expect(tester.binding.hasScheduledFrame, isTrue, reason: 'it loops while busy');

    // Busy ends mid-pass (0.195 of the period): the pass goes on to its end, it does not snap to the still mark.
    await tester.pumpWidget(host(const JomhorMark()));
    expect(radii(tester), isNot(still));
    expect(tester.binding.hasScheduledFrame, isTrue);
    await tester.pump(ms(100));
    expect(radii(tester), isNot(still));
    expect(tester.binding.hasScheduledFrame, isTrue);

    await tester.pump(ms(1100));
    expect(radii(tester), still);
    expect(tester.binding.hasScheduledFrame, isFalse);
  });

  testWidgets('reduced motion: no entrance, no wave, no frame', (tester) async {
    await tester.pumpWidget(host(const JomhorLockup(entrance: true, busy: true), reduce: true));
    expect(radii(tester), still);
    expect(tester.binding.hasScheduledFrame, isFalse);
  });

  testWidgets('the entrance is for the first paint: turning reduced motion off later never plays it', (tester) async {
    const lockup = JomhorLockup(height: 56, entrance: true);
    // On at first paint.
    await tester.pumpWidget(host(lockup, reduce: true));
    await tester.pumpWidget(host(lockup));
    expect(radii(tester), still);
    expect(tester.binding.hasScheduledFrame, isFalse);

    // Turned on while the dots assemble, then off.
    await tester.pumpWidget(const SizedBox());
    await tester.pumpWidget(host(lockup));
    await tester.pump(ms(200));
    expect(radii(tester), isNot(still));
    await tester.pumpWidget(host(lockup, reduce: true));
    expect(radii(tester), still);
    await tester.pumpWidget(host(lockup));
    expect(radii(tester), still);
    expect(tester.binding.hasScheduledFrame, isFalse);
  });

  group('launch splash', () {
    var taps = 0;
    var deactivations = 0;
    setUp(() => taps = deactivations = 0);

    Widget app({bool reduce = false}) => MaterialApp(
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(disableAnimations: reduce),
        child: LaunchSplash(child: child!),
      ),
      home: Scaffold(
        body: _Probe(
          onDeactivate: () => deactivations++,
          child: Center(
            child: TextButton(onPressed: () => taps++, child: const Text('app')),
          ),
        ),
      ),
    );

    test('the app plays it at launch (only test harnesses turn it off)', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      expect(container.read(launchSplashProvider), isTrue);
    });

    for (final splash in [true, false]) {
      testWidgets('the app ${splash ? 'opens on' : 'skips'} the splash when launchSplashProvider is $splash', (
        tester,
      ) async {
        await pumpJomhor(tester, backend: (_) async => json(<Object?>[]), splash: splash, settle: false);
        expect(find.byType(LaunchSplash), splash ? findsOneWidget : findsNothing);
        final mark = find.byWidgetPredicate((w) => w is JomhorMark && w.size == 160);
        expect(mark, splash ? findsOneWidget : findsNothing, reason: 'the native splash, exactly');
        await tester.pumpAndSettle();
        expect(mark, findsNothing);
      });
    }

    testWidgets('covers the app built under it, lets taps through as it fades, then leaves the tree for good', (
      tester,
    ) async {
      await tester.pumpWidget(app());
      final navigator = tester.state(find.byType(Navigator));
      expect(tester.widget<JomhorMark>(find.byType(JomhorMark)).size, 160, reason: 'the native splash, exactly');
      expect(tester.getCenter(find.byType(JomhorMark)), tester.getCenter(find.byType(MaterialApp)));
      expect(find.text('app'), findsOneWidget, reason: 'the app is built under it');

      await tester.pump(ms(700));
      expect(tester.widget<Opacity>(find.byType(Opacity)).opacity, 1, reason: 'held until the amber dot is at its top');
      await tester.pump(ms(50));
      expect(tester.widget<Opacity>(find.byType(Opacity)).opacity, lessThan(1));
      await tester.tap(find.text('app'), warnIfMissed: false);
      expect(taps, 1, reason: 'fading: the app is reachable');

      await tester.pumpAndSettle();
      expect(find.byType(JomhorMark), findsNothing);
      expect(tester.state(find.byType(Navigator)), same(navigator), reason: 'the app was not rebuilt');
      expect(deactivations, 0, reason: 'the app kept its place in the tree');
      await tester.tap(find.text('app'));
      expect(taps, 2);

      // A rebuild of the app (a locale change) does not play it again.
      await tester.pumpWidget(app());
      expect(find.byType(JomhorMark), findsNothing);
    });

    testWidgets('a tap during the hold goes to the splash, which starts its fade', (tester) async {
      await tester.pumpWidget(app());
      await tester.pump(ms(100));
      await tester.tap(find.text('app'), warnIfMissed: false);
      expect(taps, 0, reason: 'the splash takes the taps until it fades');

      await tester.pump();
      await tester.pump(ms(100));
      expect(tester.widget<Opacity>(find.byType(Opacity)).opacity, lessThan(1));
      await tester.tap(find.text('app'), warnIfMissed: false);
      expect(taps, 1, reason: 'fading: the app is reachable');

      // Gone 450 ms after launch, not 935.
      await tester.pump(ms(250));
      await tester.pump();
      expect(find.byType(JomhorMark), findsNothing);
      expect(deactivations, 0);
    });

    testWidgets('reduced motion: the app, directly', (tester) async {
      await tester.pumpWidget(app(reduce: true));
      expect(find.byType(JomhorMark), findsNothing);
      await tester.tap(find.text('app'));
      expect(taps, 1);
    });
  });
}

/// Counts the times the app under the splash is taken out of the tree.
class _Probe extends StatefulWidget {
  const new({required this.onDeactivate, required this.child});

  final VoidCallback onDeactivate;
  final Widget child;

  @override
  State<_Probe> createState() => _ProbeState();
}

class _ProbeState extends State<_Probe> {
  @override
  void deactivate() {
    widget.onDeactivate();
    super.deactivate();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/events/event.dart';

import 'helpers.dart';

void main() {
  final now = DateTime.utc(2027, 2, 20, 12);

  /// An event read at [fetchedAt] (default: now).
  FanEvent read(Map<String, Object?> json, {DateTime? fetchedAt}) =>
      FanEvent.fromJson(json, fetchedAt: fetchedAt ?? now);

  group('sale state', () {
    test('on sale', () => expect(read(eventJson()).saleState(now), SaleState.onSale));

    test('on sale but every category at 0 → sold out', () {
      expect(read(eventJson(soldOut: true, fromPriceMad: null)).saleState(now), SaleState.soldOut);
    });

    test('opening in the future → not started', () {
      expect(read(eventJson(onSale: false, saleStartsAt: '2027-02-21T10:00:00Z')).saleState(now), SaleState.notStarted);
    });

    test('a pre-opening read once the clock passes the opening → opening, never closed', () {
      final e = read(
        eventJson(onSale: false, saleStartsAt: '2027-02-20T12:00:00Z'),
        fetchedAt: now.subtract(const Duration(minutes: 5)),
      );
      expect(e.saleState(now.subtract(const Duration(seconds: 1))), SaleState.notStarted);
      expect(e.saleState(now), SaleState.opening);
      expect(e.saleState(now.add(const Duration(hours: 2))), SaleState.opening);
    });

    test('not on sale after the opening and before the cutoff → opening (server clock behind), never closed', () {
      expect(read(eventJson(onSale: false)).saleState(now), SaleState.opening);
    });

    test('closed: past the cutoff, or no opening time and not on sale', () {
      expect(read(eventJson(onSale: false, salesCloseAt: '2027-02-20T11:00:00Z')).saleState(now), SaleState.closed);
      expect(read(eventJson(onSale: false, saleStartsAt: null)).saleState(now), SaleState.closed);
    });

    test('on sale only while now < sales_close_at, whatever the cached read says', () {
      final e = read(
        eventJson(salesCloseAt: '2027-02-20T12:00:00Z'),
        fetchedAt: now.subtract(const Duration(minutes: 1)),
      );
      expect(e.saleState(now.subtract(const Duration(seconds: 1))), SaleState.onSale);
      expect(e.saleState(now), SaleState.closed);
    });

    test('status GATES_OPEN / CLOSED (when the backend sends it) → closed; absent → ignored', () {
      for (final status in ['GATES_OPEN', 'CLOSED']) {
        expect(read({...eventJson(), 'status': status}).saleState(now), SaleState.closed);
      }
      expect(read({...eventJson(), 'status': 'ON_SALE'}).saleState(now), SaleState.onSale);
      expect(read(eventJson()).status, isNull);
    });

    test('closed and sold out → closed', () {
      final e = read(eventJson(onSale: false, soldOut: true, salesCloseAt: '2027-02-20T11:00:00Z'));
      expect(e.saleState(now), SaleState.closed);
    });
  });

  group('re-read delay (server time)', () {
    const opens = '2027-02-20T12:00:00Z';
    final at = DateTime.utc(2027, 2, 20, 12);
    Duration? delay(Duration sinceOpening, {bool onSale = false, String? status}) => read({
      ...eventJson(onSale: onSale, saleStartsAt: opens),
      'status': ?status,
    }, fetchedAt: at.add(sinceOpening)).reopenDelay();

    test('before the opening: the time left on the server clock', () {
      expect(delay(const Duration(seconds: -90)), const Duration(seconds: 90));
    });

    test('after it and still not on sale: back off with the time waited, 2 s to 30 s', () {
      expect(
        [
          for (final s in [0, 1, 2, 4, 8, 16, 32, 600]) delay(Duration(seconds: s))!.inSeconds,
        ],
        [2, 2, 2, 4, 8, 16, 30, 30],
      );
    });

    test('no re-read once on sale, closed by status, or past the cutoff', () {
      expect(delay(Duration.zero, onSale: true), isNull);
      expect(delay(Duration.zero, status: 'GATES_OPEN'), isNull);
      expect(delay(const Duration(days: 30)), isNull); // past sales_close_at (2027-03-13)
    });
  });

  group('metadata by event_type', () {
    test('FOOTBALL_MATCH reads opponent, competition, matchday', () {
      final f = read(eventJson()).facts;
      expect(f, isA<FootballFacts>());
      f as FootballFacts;
      expect((f.opponent, f.competition, f.matchday), ('Rapid Oued', 'Championnat', 11));
    });

    test('a known key with the wrong type is ignored, unknown keys too', () {
      final f = EventFacts.of('FOOTBALL_MATCH', {'opponent': 7, 'matchday': '11', 'derby': true}) as FootballFacts;
      expect((f.opponent, f.competition, f.matchday), (null, null, null));
    });

    test('CONCERT and FESTIVAL_DAY read headliner; football keys mean nothing there', () {
      for (final type in ['CONCERT', 'FESTIVAL_DAY']) {
        final f = EventFacts.of(type, {'headliner': 'Hoba Hoba Spirit', 'opponent': 'x'});
        expect(f, isA<HeadlinerFacts>().having((h) => h.headliner, 'headliner', 'Hoba Hoba Spirit'));
      }
    });

    test('other types have no known keys', () {
      expect(EventFacts.of('CONFERENCE', {'opponent': 'x'}), isA<OtherFacts>());
    });
  });

  group('zones', () {
    Zone zone(List<(int, int)> prices) => Zone.fromJson(zoneJson(1, 'Tribune Nord', prices));

    test('remaining sums the categories; last seats at <= 50; sold out at 0', () {
      expect(zone([(5000, 30), (3000, 20)]), isA<Zone>().having((z) => z.lastSeats, 'lastSeats', isTrue));
      expect(zone([(5000, 51)]).lastSeats, isFalse);
      expect(
        zone([(5000, 0)]),
        isA<Zone>().having((z) => z.soldOut, 'soldOut', isTrue).having((z) => z.lastSeats, 'last', isFalse),
      );
    });

    test('from price is the cheapest category still available', () {
      expect(zone([(3000, 0), (5000, 10), (12000, 3)]).fromPriceMad, 5000);
      expect(zone([(3000, 0)]).fromPriceMad, isNull);
    });
  });

  test('countdown in whole days, hours, minutes; zero once passed', () {
    final start = DateTime.utc(2027, 3, 1, 20);
    expect(countdown(DateTime.utc(2027, 2, 21, 5, 38), start), (days: 8, hours: 14, minutes: 22));
    expect(countdown(start.add(const Duration(minutes: 1)), start), (days: 0, hours: 0, minutes: 0));
  });
}

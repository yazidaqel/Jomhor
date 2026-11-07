import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/ui/bidi.dart';

/// Hero CTA label per sale state (DESIGN.md screen 1 + Lot 1 adjustments). "Liste d'attente" is Lot 5.
String ctaLabel(AppLocalizations l, FanEvent e, DateTime now) => switch (e.saleState(now)) {
  SaleState.onSale => e.fromPriceMad == null ? l.ctaBuy : l.ctaBuyFrom(l.dh(e.fromPriceMad!)),
  SaleState.notStarted => l.saleOpensOn(
    venueDate(e.saleStartsAt!, e.timezone, l.localeName),
    venueClock(e.saleStartsAt!, e.timezone, l.localeName),
  ),
  SaleState.opening => l.saleOpening,
  SaleState.soldOut => l.soldOut,
  SaleState.closed => l.saleClosed,
};

/// Calendar row chip.
String chipLabel(AppLocalizations l, FanEvent e, DateTime now) => switch (e.saleState(now)) {
  SaleState.onSale => e.fromPriceMad == null ? l.ctaBuy : l.chipFrom(l.dh(e.fromPriceMad!)),
  SaleState.notStarted || SaleState.opening => l.chipSoon,
  SaleState.soldOut => l.soldOut,
  SaleState.closed => l.saleClosed,
};

/// Screen 2 rules banner.
String rulesLabel(AppLocalizations l, FanEvent e, DateTime now) => switch (e.saleState(now)) {
  SaleState.onSale => l.rulesOpen(e.perAccountLimit),
  SaleState.notStarted => l.rulesNotStarted(
    venueDate(e.saleStartsAt!, e.timezone, l.localeName),
    venueClock(e.saleStartsAt!, e.timezone, l.localeName),
    e.perAccountLimit,
  ),
  SaleState.opening => l.saleOpening,
  SaleState.soldOut => l.rulesSoldOut,
  SaleState.closed => l.rulesClosed,
};

/// "Championnat · Journée 11" for a match, the headliner for a concert, null otherwise.
String? factsLine(AppLocalizations l, EventFacts f) => switch (f) {
  FootballFacts(:final competition?, :final matchday?) => l.dotPair(competition, l.matchday(matchday)),
  FootballFacts(:final competition?) => competition,
  FootballFacts(:final matchday?) => l.matchday(matchday),
  FootballFacts() => null,
  HeadlinerFacts(:final headliner) => headliner,
  OtherFacts() => null,
};

/// Calendar row title: the opponent of a match, the headliner of a concert, else the event title.
String rowTitle(FanEvent e) => switch (e.facts) {
  FootballFacts(:final opponent?) => opponent,
  HeadlinerFacts(:final headliner?) => headliner,
  _ => e.title,
};

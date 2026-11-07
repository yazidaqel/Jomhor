// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'Jomhor Porte';

  @override
  String get deviceNotEnrolled => 'Device not enrolled';

  @override
  String get deviceNotEnrolledHint => 'Scannez le QR d\'enrôlement affiché dans le back-office, onglet Appareils.';

  @override
  String get devicePending => 'Waiting for approval';

  @override
  String get deviceActive => 'Device active';

  @override
  String get scanInactive => 'Event mode inactive';

  @override
  String get scanInactiveHint => 'Load the event manifest and unlock the device with your PIN.';

  @override
  String get scanModeIn => 'ENTRY';

  @override
  String scanTopLine(String code, String name, String mode) {
    return 'GATE $code · $name · $mode';
  }

  @override
  String scanUnsynced(int count) {
    return '$count to sync';
  }

  @override
  String get scanHint => 'Show the ticket QR';

  @override
  String get scanCameraError => 'Camera unavailable. Use the terminal trigger.';

  @override
  String get scanEntered => 'ENTERED HERE';

  @override
  String get scanRefusedCount => 'REFUSED';

  @override
  String get scanRate => 'RATE';

  @override
  String scanRateValue(int count) {
    return '$count / min';
  }

  @override
  String get scanNext => 'Scan next';

  @override
  String get scanFailed => 'ERROR';

  @override
  String get scanFailedReason => 'Scan not saved. Scan the ticket again.';

  @override
  String scanCheckedOk(int ms, String time) {
    return 'Validated offline in $ms ms · $time';
  }

  @override
  String scanCheckedRefused(int ms, String time) {
    return 'Checked offline in $ms ms · $time';
  }

  @override
  String get factZone => 'ZONE';

  @override
  String get factHolder => 'HOLDER';

  @override
  String get factTicketNo => 'TICKET NO';

  @override
  String get factType => 'TYPE';

  @override
  String get tokenRotating => 'App · rotating QR';

  @override
  String get tokenStatic => 'PDF · static QR';

  @override
  String get resultOK => 'VALID';

  @override
  String get resultDUPLICATE => 'ALREADY USED';

  @override
  String get resultBAD_SIG => 'NOT VALID';

  @override
  String get resultBAD_FORMAT => 'UNREADABLE';

  @override
  String get resultUNKNOWN => 'UNKNOWN';

  @override
  String get resultCANCELLED => 'CANCELLED';

  @override
  String get resultBLOCKED => 'BLOCKED';

  @override
  String get resultWRONG_ZONE => 'WRONG GATE';

  @override
  String get resultWRONG_EVENT => 'OTHER EVENT';

  @override
  String get resultEXPIRED_SLOT => 'CODE EXPIRED';

  @override
  String get resultOVERRIDE => 'OVERRIDE';

  @override
  String reasonDuplicate(String time, String gate) {
    return 'Entered at $time · gate $gate';
  }

  @override
  String get reasonBadSig => 'Invalid signature: forged or altered ticket.';

  @override
  String get reasonBadFormat => 'This code is not a Jomhor ticket.';

  @override
  String get reasonUnknown => 'Ticket not in this device\'s manifest.';

  @override
  String get reasonCancelled => 'Ticket cancelled or refunded.';

  @override
  String get reasonBlocked => 'Ticket blocked by the organizer.';

  @override
  String reasonWrongZone(String gates) {
    return 'Gates for this zone: $gates';
  }

  @override
  String get reasonWrongZoneNone => 'No gate serves this zone.';

  @override
  String get reasonWrongEvent => 'Ticket for another event.';

  @override
  String get reasonExpiredSlot => 'QR expired: ask the fan to reopen the ticket in the app.';

  @override
  String get reasonOverride => 'Entry granted by a supervisor.';

  @override
  String reasonDuplicateNoGate(String time) {
    return 'Entered at $time';
  }

  @override
  String get scanCountersStale => 'Counters unavailable';

  @override
  String get enrolTitle => 'Enrolment';

  @override
  String get enrolScan => 'Scan the enrolment QR';

  @override
  String get enrolCodeRead => 'Enrolment QR read';

  @override
  String get enrolCodeInvalid => 'This is not a Jomhor enrolment QR.';

  @override
  String get enrolLabel => 'Device name';

  @override
  String get enrolLabelHint => 'e.g. Tablet Gate 3';

  @override
  String get enrolLabelInvalid => 'Between 1 and 40 characters.';

  @override
  String get enrolSubmit => 'Enrol device';

  @override
  String get enrolFingerprint => 'FINGERPRINT';

  @override
  String get enrolPendingHint => 'Check the back-office shows the same fingerprint, then approve the device.';

  @override
  String get errorNetwork => 'No connection to the server. Try again.';

  @override
  String get errorEnrolCode => 'Enrolment QR invalid or expired. Show a new one in the back-office.';

  @override
  String get errorGeneric => 'Server error. Try again.';

  @override
  String get eventTitle => 'Event';

  @override
  String get eventNone => 'No event assigned to this device.';

  @override
  String get eventNoneHint => 'Assign the device to a gate in the back-office (event, Devices tab).';

  @override
  String eventGate(String code, String name) {
    return 'Gate $code · $name';
  }

  @override
  String eventManifestAt(String time) {
    return 'Manifest available at $time';
  }

  @override
  String get eventLoad => 'Load the manifest';

  @override
  String get eventRetry => 'Try again';

  @override
  String get eventStageDownloading => 'Downloading the manifest…';

  @override
  String get eventStageVerifying => 'Checking the signature…';

  @override
  String get eventStageSaving => 'Saving…';

  @override
  String eventLoaded(int version, int rows) {
    return 'Manifest v$version · $rows tickets';
  }

  @override
  String eventDriftOk(int seconds) {
    return 'Clock checked · $seconds s off';
  }

  @override
  String eventDriftRefused(int seconds) {
    return 'Clock $seconds s off (max 20 s). Set the device time, then try again.';
  }

  @override
  String get eventDriftUnknown => 'Clock not checked: connect the device.';

  @override
  String get eventRejected => 'Manifest refused: invalid signature or content. The last good manifest is kept.';

  @override
  String eventNotReady(String time) {
    return 'Manifest not available yet (at $time).';
  }

  @override
  String get eventNotReadyNoTime => 'Manifest not available yet.';

  @override
  String get eventClosed => 'Event closed.';

  @override
  String get unlockTitle => 'Unlock';

  @override
  String get unlockPickName => 'Who are you?';

  @override
  String get unlockNoStaff => 'No gate staff yet. Connect the device to load the list.';

  @override
  String unlockPin(String name) {
    return 'PIN for $name';
  }

  @override
  String unlockWrong(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Wrong PIN · $count tries left',
      one: 'Wrong PIN · 1 try left',
    );
    return '$_temp0';
  }

  @override
  String unlockLocked(String time) {
    return 'Too many tries. Try again at $time.';
  }

  @override
  String get unlockChangeName => 'Change name';

  @override
  String get unlockDelete => 'Delete';

  @override
  String get roleSupervisor => 'Supervisor';

  @override
  String get roleGate => 'Gate staff';
}

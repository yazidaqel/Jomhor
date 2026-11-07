import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';

/// Where the device is in enrolment (docs/05 `config.device_id`, `device_status`).
enum DeviceStatus { notEnrolled, pending, active }

DeviceStatus deviceStatusOf(ConfigData config) => switch ((config.deviceId, config.deviceStatus)) {
  (null, _) => DeviceStatus.notEnrolled,
  (_, 'ACTIVE') => DeviceStatus.active,
  _ => DeviceStatus.pending,
};

final deviceStatusProvider = StreamProvider<DeviceStatus>(
  (ref) => ref.watch(databaseProvider).watchConfig().map(deviceStatusOf),
);

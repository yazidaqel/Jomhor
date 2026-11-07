// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'scanner_database.dart';

// ignore_for_file: type=lint
class Config extends Table with TableInfo<Config, ConfigData> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  Config(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _idMeta = const VerificationMeta('id');
  late final GeneratedColumn<int> id = GeneratedColumn<int>(
    'id',
    aliasedName,
    true,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
    $customConstraints: 'PRIMARY KEY CHECK (id = 1)',
  );
  static const VerificationMeta _deviceIdMeta = const VerificationMeta('deviceId');
  late final GeneratedColumn<String> deviceId = GeneratedColumn<String>(
    'device_id',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  static const VerificationMeta _deviceStatusMeta = const VerificationMeta('deviceStatus');
  late final GeneratedColumn<String> deviceStatus = GeneratedColumn<String>(
    'device_status',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  static const VerificationMeta _manifestPublicKeyMeta = const VerificationMeta('manifestPublicKey');
  late final GeneratedColumn<String> manifestPublicKey = GeneratedColumn<String>(
    'manifest_public_key',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  static const VerificationMeta _serverTimeOffsetMsMeta = const VerificationMeta('serverTimeOffsetMs');
  late final GeneratedColumn<int> serverTimeOffsetMs = GeneratedColumn<int>(
    'server_time_offset_ms',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
    $customConstraints: 'NOT NULL DEFAULT 0',
    defaultValue: const CustomExpression('0'),
  );
  static const VerificationMeta _lastServerTimeAtMeta = const VerificationMeta('lastServerTimeAt');
  late final GeneratedColumn<int> lastServerTimeAt = GeneratedColumn<int>(
    'last_server_time_at',
    aliasedName,
    true,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  static const VerificationMeta _eventIdMeta = const VerificationMeta('eventId');
  late final GeneratedColumn<int> eventId = GeneratedColumn<int>(
    'event_id',
    aliasedName,
    true,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  static const VerificationMeta _eventTitleMeta = const VerificationMeta('eventTitle');
  late final GeneratedColumn<String> eventTitle = GeneratedColumn<String>(
    'event_title',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  static const VerificationMeta _startsAtMeta = const VerificationMeta('startsAt');
  late final GeneratedColumn<int> startsAt = GeneratedColumn<int>(
    'starts_at',
    aliasedName,
    true,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  static const VerificationMeta _eventStatusMeta = const VerificationMeta('eventStatus');
  late final GeneratedColumn<String> eventStatus = GeneratedColumn<String>(
    'event_status',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  static const VerificationMeta _gateIdMeta = const VerificationMeta('gateId');
  late final GeneratedColumn<String> gateId = GeneratedColumn<String>(
    'gate_id',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  static const VerificationMeta _manifestVersionMeta = const VerificationMeta('manifestVersion');
  late final GeneratedColumn<int> manifestVersion = GeneratedColumn<int>(
    'manifest_version',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
    $customConstraints: 'NOT NULL DEFAULT 0',
    defaultValue: const CustomExpression('0'),
  );
  static const VerificationMeta _reentryPolicyMeta = const VerificationMeta('reentryPolicy');
  late final GeneratedColumn<String> reentryPolicy = GeneratedColumn<String>(
    'reentry_policy',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: 'NOT NULL DEFAULT \'NONE\'',
    defaultValue: const CustomExpression('\'NONE\''),
  );
  static const VerificationMeta _syncCursorMeta = const VerificationMeta('syncCursor');
  late final GeneratedColumn<int> syncCursor = GeneratedColumn<int>(
    'sync_cursor',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
    $customConstraints: 'NOT NULL DEFAULT 0',
    defaultValue: const CustomExpression('0'),
  );
  static const VerificationMeta _staffIdMeta = const VerificationMeta('staffId');
  late final GeneratedColumn<String> staffId = GeneratedColumn<String>(
    'staff_id',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  static const VerificationMeta _modeMeta = const VerificationMeta('mode');
  late final GeneratedColumn<String> mode = GeneratedColumn<String>(
    'mode',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: 'NOT NULL DEFAULT \'IN\'',
    defaultValue: const CustomExpression('\'IN\''),
  );
  @override
  List<GeneratedColumn> get $columns => [
    id,
    deviceId,
    deviceStatus,
    manifestPublicKey,
    serverTimeOffsetMs,
    lastServerTimeAt,
    eventId,
    eventTitle,
    startsAt,
    eventStatus,
    gateId,
    manifestVersion,
    reentryPolicy,
    syncCursor,
    staffId,
    mode,
  ];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'config';
  @override
  VerificationContext validateIntegrity(Insertable<ConfigData> instance, {bool isInserting = false}) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('id')) {
      context.handle(_idMeta, id.isAcceptableOrUnknown(data['id']!, _idMeta));
    }
    if (data.containsKey('device_id')) {
      context.handle(_deviceIdMeta, deviceId.isAcceptableOrUnknown(data['device_id']!, _deviceIdMeta));
    }
    if (data.containsKey('device_status')) {
      context.handle(_deviceStatusMeta, deviceStatus.isAcceptableOrUnknown(data['device_status']!, _deviceStatusMeta));
    }
    if (data.containsKey('manifest_public_key')) {
      context.handle(
        _manifestPublicKeyMeta,
        manifestPublicKey.isAcceptableOrUnknown(data['manifest_public_key']!, _manifestPublicKeyMeta),
      );
    }
    if (data.containsKey('server_time_offset_ms')) {
      context.handle(
        _serverTimeOffsetMsMeta,
        serverTimeOffsetMs.isAcceptableOrUnknown(data['server_time_offset_ms']!, _serverTimeOffsetMsMeta),
      );
    }
    if (data.containsKey('last_server_time_at')) {
      context.handle(
        _lastServerTimeAtMeta,
        lastServerTimeAt.isAcceptableOrUnknown(data['last_server_time_at']!, _lastServerTimeAtMeta),
      );
    }
    if (data.containsKey('event_id')) {
      context.handle(_eventIdMeta, eventId.isAcceptableOrUnknown(data['event_id']!, _eventIdMeta));
    }
    if (data.containsKey('event_title')) {
      context.handle(_eventTitleMeta, eventTitle.isAcceptableOrUnknown(data['event_title']!, _eventTitleMeta));
    }
    if (data.containsKey('starts_at')) {
      context.handle(_startsAtMeta, startsAt.isAcceptableOrUnknown(data['starts_at']!, _startsAtMeta));
    }
    if (data.containsKey('event_status')) {
      context.handle(_eventStatusMeta, eventStatus.isAcceptableOrUnknown(data['event_status']!, _eventStatusMeta));
    }
    if (data.containsKey('gate_id')) {
      context.handle(_gateIdMeta, gateId.isAcceptableOrUnknown(data['gate_id']!, _gateIdMeta));
    }
    if (data.containsKey('manifest_version')) {
      context.handle(
        _manifestVersionMeta,
        manifestVersion.isAcceptableOrUnknown(data['manifest_version']!, _manifestVersionMeta),
      );
    }
    if (data.containsKey('reentry_policy')) {
      context.handle(
        _reentryPolicyMeta,
        reentryPolicy.isAcceptableOrUnknown(data['reentry_policy']!, _reentryPolicyMeta),
      );
    }
    if (data.containsKey('sync_cursor')) {
      context.handle(_syncCursorMeta, syncCursor.isAcceptableOrUnknown(data['sync_cursor']!, _syncCursorMeta));
    }
    if (data.containsKey('staff_id')) {
      context.handle(_staffIdMeta, staffId.isAcceptableOrUnknown(data['staff_id']!, _staffIdMeta));
    }
    if (data.containsKey('mode')) {
      context.handle(_modeMeta, mode.isAcceptableOrUnknown(data['mode']!, _modeMeta));
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {id};
  @override
  ConfigData map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return ConfigData(
      id: attachedDatabase.typeMapping.read(DriftSqlType.int, data['${effectivePrefix}id']),
      deviceId: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}device_id']),
      deviceStatus: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}device_status']),
      manifestPublicKey: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}manifest_public_key'],
      ),
      serverTimeOffsetMs: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}server_time_offset_ms'],
      )!,
      lastServerTimeAt: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}last_server_time_at'],
      ),
      eventId: attachedDatabase.typeMapping.read(DriftSqlType.int, data['${effectivePrefix}event_id']),
      eventTitle: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}event_title']),
      startsAt: attachedDatabase.typeMapping.read(DriftSqlType.int, data['${effectivePrefix}starts_at']),
      eventStatus: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}event_status']),
      gateId: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}gate_id']),
      manifestVersion: attachedDatabase.typeMapping.read(DriftSqlType.int, data['${effectivePrefix}manifest_version'])!,
      reentryPolicy: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}reentry_policy'])!,
      syncCursor: attachedDatabase.typeMapping.read(DriftSqlType.int, data['${effectivePrefix}sync_cursor'])!,
      staffId: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}staff_id']),
      mode: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}mode'])!,
    );
  }

  @override
  Config createAlias(String alias) {
    return Config(attachedDatabase, alias);
  }

  @override
  bool get dontWriteConstraints => true;
}

class ConfigData extends DataClass implements Insertable<ConfigData> {
  final int? id;
  final String? deviceId;

  /// null until enrolled
  final String? deviceStatus;

  /// PENDING | ACTIVE
  final String? manifestPublicKey;

  /// server Ed25519 key, pinned at enrolment
  final int serverTimeOffsetMs;

  /// server - device wall clock, from every response
  final int? lastServerTimeAt;

  /// epoch ms (device) of the last offset update
  final int? eventId;
  final String? eventTitle;
  final int? startsAt;

  /// epoch ms
  final String? eventStatus;
  final String? gateId;

  /// current gate: assignment, or a supervisor gate change
  final int manifestVersion;
  final String reentryPolicy;
  final int syncCursor;
  final String? staffId;

  /// unlocked staff; null = locked
  final String mode;
  const ConfigData({
    this.id,
    this.deviceId,
    this.deviceStatus,
    this.manifestPublicKey,
    required this.serverTimeOffsetMs,
    this.lastServerTimeAt,
    this.eventId,
    this.eventTitle,
    this.startsAt,
    this.eventStatus,
    this.gateId,
    required this.manifestVersion,
    required this.reentryPolicy,
    required this.syncCursor,
    this.staffId,
    required this.mode,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (!nullToAbsent || id != null) {
      map['id'] = Variable<int>(id);
    }
    if (!nullToAbsent || deviceId != null) {
      map['device_id'] = Variable<String>(deviceId);
    }
    if (!nullToAbsent || deviceStatus != null) {
      map['device_status'] = Variable<String>(deviceStatus);
    }
    if (!nullToAbsent || manifestPublicKey != null) {
      map['manifest_public_key'] = Variable<String>(manifestPublicKey);
    }
    map['server_time_offset_ms'] = Variable<int>(serverTimeOffsetMs);
    if (!nullToAbsent || lastServerTimeAt != null) {
      map['last_server_time_at'] = Variable<int>(lastServerTimeAt);
    }
    if (!nullToAbsent || eventId != null) {
      map['event_id'] = Variable<int>(eventId);
    }
    if (!nullToAbsent || eventTitle != null) {
      map['event_title'] = Variable<String>(eventTitle);
    }
    if (!nullToAbsent || startsAt != null) {
      map['starts_at'] = Variable<int>(startsAt);
    }
    if (!nullToAbsent || eventStatus != null) {
      map['event_status'] = Variable<String>(eventStatus);
    }
    if (!nullToAbsent || gateId != null) {
      map['gate_id'] = Variable<String>(gateId);
    }
    map['manifest_version'] = Variable<int>(manifestVersion);
    map['reentry_policy'] = Variable<String>(reentryPolicy);
    map['sync_cursor'] = Variable<int>(syncCursor);
    if (!nullToAbsent || staffId != null) {
      map['staff_id'] = Variable<String>(staffId);
    }
    map['mode'] = Variable<String>(mode);
    return map;
  }

  ConfigCompanion toCompanion(bool nullToAbsent) {
    return ConfigCompanion(
      id: id == null && nullToAbsent ? const Value.absent() : Value(id),
      deviceId: deviceId == null && nullToAbsent ? const Value.absent() : Value(deviceId),
      deviceStatus: deviceStatus == null && nullToAbsent ? const Value.absent() : Value(deviceStatus),
      manifestPublicKey: manifestPublicKey == null && nullToAbsent ? const Value.absent() : Value(manifestPublicKey),
      serverTimeOffsetMs: Value(serverTimeOffsetMs),
      lastServerTimeAt: lastServerTimeAt == null && nullToAbsent ? const Value.absent() : Value(lastServerTimeAt),
      eventId: eventId == null && nullToAbsent ? const Value.absent() : Value(eventId),
      eventTitle: eventTitle == null && nullToAbsent ? const Value.absent() : Value(eventTitle),
      startsAt: startsAt == null && nullToAbsent ? const Value.absent() : Value(startsAt),
      eventStatus: eventStatus == null && nullToAbsent ? const Value.absent() : Value(eventStatus),
      gateId: gateId == null && nullToAbsent ? const Value.absent() : Value(gateId),
      manifestVersion: Value(manifestVersion),
      reentryPolicy: Value(reentryPolicy),
      syncCursor: Value(syncCursor),
      staffId: staffId == null && nullToAbsent ? const Value.absent() : Value(staffId),
      mode: Value(mode),
    );
  }

  factory ConfigData.fromJson(Map<String, dynamic> json, {ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return ConfigData(
      id: serializer.fromJson<int?>(json['id']),
      deviceId: serializer.fromJson<String?>(json['device_id']),
      deviceStatus: serializer.fromJson<String?>(json['device_status']),
      manifestPublicKey: serializer.fromJson<String?>(json['manifest_public_key']),
      serverTimeOffsetMs: serializer.fromJson<int>(json['server_time_offset_ms']),
      lastServerTimeAt: serializer.fromJson<int?>(json['last_server_time_at']),
      eventId: serializer.fromJson<int?>(json['event_id']),
      eventTitle: serializer.fromJson<String?>(json['event_title']),
      startsAt: serializer.fromJson<int?>(json['starts_at']),
      eventStatus: serializer.fromJson<String?>(json['event_status']),
      gateId: serializer.fromJson<String?>(json['gate_id']),
      manifestVersion: serializer.fromJson<int>(json['manifest_version']),
      reentryPolicy: serializer.fromJson<String>(json['reentry_policy']),
      syncCursor: serializer.fromJson<int>(json['sync_cursor']),
      staffId: serializer.fromJson<String?>(json['staff_id']),
      mode: serializer.fromJson<String>(json['mode']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'id': serializer.toJson<int?>(id),
      'device_id': serializer.toJson<String?>(deviceId),
      'device_status': serializer.toJson<String?>(deviceStatus),
      'manifest_public_key': serializer.toJson<String?>(manifestPublicKey),
      'server_time_offset_ms': serializer.toJson<int>(serverTimeOffsetMs),
      'last_server_time_at': serializer.toJson<int?>(lastServerTimeAt),
      'event_id': serializer.toJson<int?>(eventId),
      'event_title': serializer.toJson<String?>(eventTitle),
      'starts_at': serializer.toJson<int?>(startsAt),
      'event_status': serializer.toJson<String?>(eventStatus),
      'gate_id': serializer.toJson<String?>(gateId),
      'manifest_version': serializer.toJson<int>(manifestVersion),
      'reentry_policy': serializer.toJson<String>(reentryPolicy),
      'sync_cursor': serializer.toJson<int>(syncCursor),
      'staff_id': serializer.toJson<String?>(staffId),
      'mode': serializer.toJson<String>(mode),
    };
  }

  ConfigData copyWith({
    Value<int?> id = const Value.absent(),
    Value<String?> deviceId = const Value.absent(),
    Value<String?> deviceStatus = const Value.absent(),
    Value<String?> manifestPublicKey = const Value.absent(),
    int? serverTimeOffsetMs,
    Value<int?> lastServerTimeAt = const Value.absent(),
    Value<int?> eventId = const Value.absent(),
    Value<String?> eventTitle = const Value.absent(),
    Value<int?> startsAt = const Value.absent(),
    Value<String?> eventStatus = const Value.absent(),
    Value<String?> gateId = const Value.absent(),
    int? manifestVersion,
    String? reentryPolicy,
    int? syncCursor,
    Value<String?> staffId = const Value.absent(),
    String? mode,
  }) => ConfigData(
    id: id.present ? id.value : this.id,
    deviceId: deviceId.present ? deviceId.value : this.deviceId,
    deviceStatus: deviceStatus.present ? deviceStatus.value : this.deviceStatus,
    manifestPublicKey: manifestPublicKey.present ? manifestPublicKey.value : this.manifestPublicKey,
    serverTimeOffsetMs: serverTimeOffsetMs ?? this.serverTimeOffsetMs,
    lastServerTimeAt: lastServerTimeAt.present ? lastServerTimeAt.value : this.lastServerTimeAt,
    eventId: eventId.present ? eventId.value : this.eventId,
    eventTitle: eventTitle.present ? eventTitle.value : this.eventTitle,
    startsAt: startsAt.present ? startsAt.value : this.startsAt,
    eventStatus: eventStatus.present ? eventStatus.value : this.eventStatus,
    gateId: gateId.present ? gateId.value : this.gateId,
    manifestVersion: manifestVersion ?? this.manifestVersion,
    reentryPolicy: reentryPolicy ?? this.reentryPolicy,
    syncCursor: syncCursor ?? this.syncCursor,
    staffId: staffId.present ? staffId.value : this.staffId,
    mode: mode ?? this.mode,
  );
  ConfigData copyWithCompanion(ConfigCompanion data) {
    return ConfigData(
      id: data.id.present ? data.id.value : this.id,
      deviceId: data.deviceId.present ? data.deviceId.value : this.deviceId,
      deviceStatus: data.deviceStatus.present ? data.deviceStatus.value : this.deviceStatus,
      manifestPublicKey: data.manifestPublicKey.present ? data.manifestPublicKey.value : this.manifestPublicKey,
      serverTimeOffsetMs: data.serverTimeOffsetMs.present ? data.serverTimeOffsetMs.value : this.serverTimeOffsetMs,
      lastServerTimeAt: data.lastServerTimeAt.present ? data.lastServerTimeAt.value : this.lastServerTimeAt,
      eventId: data.eventId.present ? data.eventId.value : this.eventId,
      eventTitle: data.eventTitle.present ? data.eventTitle.value : this.eventTitle,
      startsAt: data.startsAt.present ? data.startsAt.value : this.startsAt,
      eventStatus: data.eventStatus.present ? data.eventStatus.value : this.eventStatus,
      gateId: data.gateId.present ? data.gateId.value : this.gateId,
      manifestVersion: data.manifestVersion.present ? data.manifestVersion.value : this.manifestVersion,
      reentryPolicy: data.reentryPolicy.present ? data.reentryPolicy.value : this.reentryPolicy,
      syncCursor: data.syncCursor.present ? data.syncCursor.value : this.syncCursor,
      staffId: data.staffId.present ? data.staffId.value : this.staffId,
      mode: data.mode.present ? data.mode.value : this.mode,
    );
  }

  @override
  String toString() {
    return (StringBuffer('ConfigData(')
          ..write('id: $id, ')
          ..write('deviceId: $deviceId, ')
          ..write('deviceStatus: $deviceStatus, ')
          ..write('manifestPublicKey: $manifestPublicKey, ')
          ..write('serverTimeOffsetMs: $serverTimeOffsetMs, ')
          ..write('lastServerTimeAt: $lastServerTimeAt, ')
          ..write('eventId: $eventId, ')
          ..write('eventTitle: $eventTitle, ')
          ..write('startsAt: $startsAt, ')
          ..write('eventStatus: $eventStatus, ')
          ..write('gateId: $gateId, ')
          ..write('manifestVersion: $manifestVersion, ')
          ..write('reentryPolicy: $reentryPolicy, ')
          ..write('syncCursor: $syncCursor, ')
          ..write('staffId: $staffId, ')
          ..write('mode: $mode')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(
    id,
    deviceId,
    deviceStatus,
    manifestPublicKey,
    serverTimeOffsetMs,
    lastServerTimeAt,
    eventId,
    eventTitle,
    startsAt,
    eventStatus,
    gateId,
    manifestVersion,
    reentryPolicy,
    syncCursor,
    staffId,
    mode,
  );
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is ConfigData &&
          other.id == this.id &&
          other.deviceId == this.deviceId &&
          other.deviceStatus == this.deviceStatus &&
          other.manifestPublicKey == this.manifestPublicKey &&
          other.serverTimeOffsetMs == this.serverTimeOffsetMs &&
          other.lastServerTimeAt == this.lastServerTimeAt &&
          other.eventId == this.eventId &&
          other.eventTitle == this.eventTitle &&
          other.startsAt == this.startsAt &&
          other.eventStatus == this.eventStatus &&
          other.gateId == this.gateId &&
          other.manifestVersion == this.manifestVersion &&
          other.reentryPolicy == this.reentryPolicy &&
          other.syncCursor == this.syncCursor &&
          other.staffId == this.staffId &&
          other.mode == this.mode);
}

class ConfigCompanion extends UpdateCompanion<ConfigData> {
  final Value<int?> id;
  final Value<String?> deviceId;
  final Value<String?> deviceStatus;
  final Value<String?> manifestPublicKey;
  final Value<int> serverTimeOffsetMs;
  final Value<int?> lastServerTimeAt;
  final Value<int?> eventId;
  final Value<String?> eventTitle;
  final Value<int?> startsAt;
  final Value<String?> eventStatus;
  final Value<String?> gateId;
  final Value<int> manifestVersion;
  final Value<String> reentryPolicy;
  final Value<int> syncCursor;
  final Value<String?> staffId;
  final Value<String> mode;
  const ConfigCompanion({
    this.id = const Value.absent(),
    this.deviceId = const Value.absent(),
    this.deviceStatus = const Value.absent(),
    this.manifestPublicKey = const Value.absent(),
    this.serverTimeOffsetMs = const Value.absent(),
    this.lastServerTimeAt = const Value.absent(),
    this.eventId = const Value.absent(),
    this.eventTitle = const Value.absent(),
    this.startsAt = const Value.absent(),
    this.eventStatus = const Value.absent(),
    this.gateId = const Value.absent(),
    this.manifestVersion = const Value.absent(),
    this.reentryPolicy = const Value.absent(),
    this.syncCursor = const Value.absent(),
    this.staffId = const Value.absent(),
    this.mode = const Value.absent(),
  });
  ConfigCompanion.insert({
    this.id = const Value.absent(),
    this.deviceId = const Value.absent(),
    this.deviceStatus = const Value.absent(),
    this.manifestPublicKey = const Value.absent(),
    this.serverTimeOffsetMs = const Value.absent(),
    this.lastServerTimeAt = const Value.absent(),
    this.eventId = const Value.absent(),
    this.eventTitle = const Value.absent(),
    this.startsAt = const Value.absent(),
    this.eventStatus = const Value.absent(),
    this.gateId = const Value.absent(),
    this.manifestVersion = const Value.absent(),
    this.reentryPolicy = const Value.absent(),
    this.syncCursor = const Value.absent(),
    this.staffId = const Value.absent(),
    this.mode = const Value.absent(),
  });
  static Insertable<ConfigData> custom({
    Expression<int>? id,
    Expression<String>? deviceId,
    Expression<String>? deviceStatus,
    Expression<String>? manifestPublicKey,
    Expression<int>? serverTimeOffsetMs,
    Expression<int>? lastServerTimeAt,
    Expression<int>? eventId,
    Expression<String>? eventTitle,
    Expression<int>? startsAt,
    Expression<String>? eventStatus,
    Expression<String>? gateId,
    Expression<int>? manifestVersion,
    Expression<String>? reentryPolicy,
    Expression<int>? syncCursor,
    Expression<String>? staffId,
    Expression<String>? mode,
  }) {
    return RawValuesInsertable({
      if (id != null) 'id': id,
      if (deviceId != null) 'device_id': deviceId,
      if (deviceStatus != null) 'device_status': deviceStatus,
      if (manifestPublicKey != null) 'manifest_public_key': manifestPublicKey,
      if (serverTimeOffsetMs != null) 'server_time_offset_ms': serverTimeOffsetMs,
      if (lastServerTimeAt != null) 'last_server_time_at': lastServerTimeAt,
      if (eventId != null) 'event_id': eventId,
      if (eventTitle != null) 'event_title': eventTitle,
      if (startsAt != null) 'starts_at': startsAt,
      if (eventStatus != null) 'event_status': eventStatus,
      if (gateId != null) 'gate_id': gateId,
      if (manifestVersion != null) 'manifest_version': manifestVersion,
      if (reentryPolicy != null) 'reentry_policy': reentryPolicy,
      if (syncCursor != null) 'sync_cursor': syncCursor,
      if (staffId != null) 'staff_id': staffId,
      if (mode != null) 'mode': mode,
    });
  }

  ConfigCompanion copyWith({
    Value<int?>? id,
    Value<String?>? deviceId,
    Value<String?>? deviceStatus,
    Value<String?>? manifestPublicKey,
    Value<int>? serverTimeOffsetMs,
    Value<int?>? lastServerTimeAt,
    Value<int?>? eventId,
    Value<String?>? eventTitle,
    Value<int?>? startsAt,
    Value<String?>? eventStatus,
    Value<String?>? gateId,
    Value<int>? manifestVersion,
    Value<String>? reentryPolicy,
    Value<int>? syncCursor,
    Value<String?>? staffId,
    Value<String>? mode,
  }) {
    return ConfigCompanion(
      id: id ?? this.id,
      deviceId: deviceId ?? this.deviceId,
      deviceStatus: deviceStatus ?? this.deviceStatus,
      manifestPublicKey: manifestPublicKey ?? this.manifestPublicKey,
      serverTimeOffsetMs: serverTimeOffsetMs ?? this.serverTimeOffsetMs,
      lastServerTimeAt: lastServerTimeAt ?? this.lastServerTimeAt,
      eventId: eventId ?? this.eventId,
      eventTitle: eventTitle ?? this.eventTitle,
      startsAt: startsAt ?? this.startsAt,
      eventStatus: eventStatus ?? this.eventStatus,
      gateId: gateId ?? this.gateId,
      manifestVersion: manifestVersion ?? this.manifestVersion,
      reentryPolicy: reentryPolicy ?? this.reentryPolicy,
      syncCursor: syncCursor ?? this.syncCursor,
      staffId: staffId ?? this.staffId,
      mode: mode ?? this.mode,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (id.present) {
      map['id'] = Variable<int>(id.value);
    }
    if (deviceId.present) {
      map['device_id'] = Variable<String>(deviceId.value);
    }
    if (deviceStatus.present) {
      map['device_status'] = Variable<String>(deviceStatus.value);
    }
    if (manifestPublicKey.present) {
      map['manifest_public_key'] = Variable<String>(manifestPublicKey.value);
    }
    if (serverTimeOffsetMs.present) {
      map['server_time_offset_ms'] = Variable<int>(serverTimeOffsetMs.value);
    }
    if (lastServerTimeAt.present) {
      map['last_server_time_at'] = Variable<int>(lastServerTimeAt.value);
    }
    if (eventId.present) {
      map['event_id'] = Variable<int>(eventId.value);
    }
    if (eventTitle.present) {
      map['event_title'] = Variable<String>(eventTitle.value);
    }
    if (startsAt.present) {
      map['starts_at'] = Variable<int>(startsAt.value);
    }
    if (eventStatus.present) {
      map['event_status'] = Variable<String>(eventStatus.value);
    }
    if (gateId.present) {
      map['gate_id'] = Variable<String>(gateId.value);
    }
    if (manifestVersion.present) {
      map['manifest_version'] = Variable<int>(manifestVersion.value);
    }
    if (reentryPolicy.present) {
      map['reentry_policy'] = Variable<String>(reentryPolicy.value);
    }
    if (syncCursor.present) {
      map['sync_cursor'] = Variable<int>(syncCursor.value);
    }
    if (staffId.present) {
      map['staff_id'] = Variable<String>(staffId.value);
    }
    if (mode.present) {
      map['mode'] = Variable<String>(mode.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('ConfigCompanion(')
          ..write('id: $id, ')
          ..write('deviceId: $deviceId, ')
          ..write('deviceStatus: $deviceStatus, ')
          ..write('manifestPublicKey: $manifestPublicKey, ')
          ..write('serverTimeOffsetMs: $serverTimeOffsetMs, ')
          ..write('lastServerTimeAt: $lastServerTimeAt, ')
          ..write('eventId: $eventId, ')
          ..write('eventTitle: $eventTitle, ')
          ..write('startsAt: $startsAt, ')
          ..write('eventStatus: $eventStatus, ')
          ..write('gateId: $gateId, ')
          ..write('manifestVersion: $manifestVersion, ')
          ..write('reentryPolicy: $reentryPolicy, ')
          ..write('syncCursor: $syncCursor, ')
          ..write('staffId: $staffId, ')
          ..write('mode: $mode')
          ..write(')'))
        .toString();
  }
}

class Zones extends Table with TableInfo<Zones, Zone> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  Zones(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _zoneIdMeta = const VerificationMeta('zoneId');
  late final GeneratedColumn<int> zoneId = GeneratedColumn<int>(
    'zone_id',
    aliasedName,
    true,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
    $customConstraints: 'PRIMARY KEY',
  );
  static const VerificationMeta _codeMeta = const VerificationMeta('code');
  late final GeneratedColumn<String> code = GeneratedColumn<String>(
    'code',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _nameMeta = const VerificationMeta('name');
  late final GeneratedColumn<String> name = GeneratedColumn<String>(
    'name',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  @override
  List<GeneratedColumn> get $columns => [zoneId, code, name];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'zones';
  @override
  VerificationContext validateIntegrity(Insertable<Zone> instance, {bool isInserting = false}) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('zone_id')) {
      context.handle(_zoneIdMeta, zoneId.isAcceptableOrUnknown(data['zone_id']!, _zoneIdMeta));
    }
    if (data.containsKey('code')) {
      context.handle(_codeMeta, code.isAcceptableOrUnknown(data['code']!, _codeMeta));
    } else if (isInserting) {
      context.missing(_codeMeta);
    }
    if (data.containsKey('name')) {
      context.handle(_nameMeta, name.isAcceptableOrUnknown(data['name']!, _nameMeta));
    } else if (isInserting) {
      context.missing(_nameMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {zoneId};
  @override
  Zone map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return Zone(
      zoneId: attachedDatabase.typeMapping.read(DriftSqlType.int, data['${effectivePrefix}zone_id']),
      code: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}code'])!,
      name: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}name'])!,
    );
  }

  @override
  Zones createAlias(String alias) {
    return Zones(attachedDatabase, alias);
  }

  @override
  bool get dontWriteConstraints => true;
}

class Zone extends DataClass implements Insertable<Zone> {
  final int? zoneId;
  final String code;
  final String name;
  const Zone({this.zoneId, required this.code, required this.name});
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (!nullToAbsent || zoneId != null) {
      map['zone_id'] = Variable<int>(zoneId);
    }
    map['code'] = Variable<String>(code);
    map['name'] = Variable<String>(name);
    return map;
  }

  ZonesCompanion toCompanion(bool nullToAbsent) {
    return ZonesCompanion(
      zoneId: zoneId == null && nullToAbsent ? const Value.absent() : Value(zoneId),
      code: Value(code),
      name: Value(name),
    );
  }

  factory Zone.fromJson(Map<String, dynamic> json, {ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return Zone(
      zoneId: serializer.fromJson<int?>(json['zone_id']),
      code: serializer.fromJson<String>(json['code']),
      name: serializer.fromJson<String>(json['name']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'zone_id': serializer.toJson<int?>(zoneId),
      'code': serializer.toJson<String>(code),
      'name': serializer.toJson<String>(name),
    };
  }

  Zone copyWith({Value<int?> zoneId = const Value.absent(), String? code, String? name}) =>
      Zone(zoneId: zoneId.present ? zoneId.value : this.zoneId, code: code ?? this.code, name: name ?? this.name);
  Zone copyWithCompanion(ZonesCompanion data) {
    return Zone(
      zoneId: data.zoneId.present ? data.zoneId.value : this.zoneId,
      code: data.code.present ? data.code.value : this.code,
      name: data.name.present ? data.name.value : this.name,
    );
  }

  @override
  String toString() {
    return (StringBuffer('Zone(')
          ..write('zoneId: $zoneId, ')
          ..write('code: $code, ')
          ..write('name: $name')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(zoneId, code, name);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is Zone && other.zoneId == this.zoneId && other.code == this.code && other.name == this.name);
}

class ZonesCompanion extends UpdateCompanion<Zone> {
  final Value<int?> zoneId;
  final Value<String> code;
  final Value<String> name;
  const ZonesCompanion({
    this.zoneId = const Value.absent(),
    this.code = const Value.absent(),
    this.name = const Value.absent(),
  });
  ZonesCompanion.insert({this.zoneId = const Value.absent(), required String code, required String name})
    : code = Value(code),
      name = Value(name);
  static Insertable<Zone> custom({Expression<int>? zoneId, Expression<String>? code, Expression<String>? name}) {
    return RawValuesInsertable({
      if (zoneId != null) 'zone_id': zoneId,
      if (code != null) 'code': code,
      if (name != null) 'name': name,
    });
  }

  ZonesCompanion copyWith({Value<int?>? zoneId, Value<String>? code, Value<String>? name}) {
    return ZonesCompanion(zoneId: zoneId ?? this.zoneId, code: code ?? this.code, name: name ?? this.name);
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (zoneId.present) {
      map['zone_id'] = Variable<int>(zoneId.value);
    }
    if (code.present) {
      map['code'] = Variable<String>(code.value);
    }
    if (name.present) {
      map['name'] = Variable<String>(name.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('ZonesCompanion(')
          ..write('zoneId: $zoneId, ')
          ..write('code: $code, ')
          ..write('name: $name')
          ..write(')'))
        .toString();
  }
}

class Gates extends Table with TableInfo<Gates, Gate> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  Gates(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _gateIdMeta = const VerificationMeta('gateId');
  late final GeneratedColumn<String> gateId = GeneratedColumn<String>(
    'gate_id',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: 'PRIMARY KEY',
  );
  static const VerificationMeta _codeMeta = const VerificationMeta('code');
  late final GeneratedColumn<String> code = GeneratedColumn<String>(
    'code',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _nameMeta = const VerificationMeta('name');
  late final GeneratedColumn<String> name = GeneratedColumn<String>(
    'name',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _zoneIdsMeta = const VerificationMeta('zoneIds');
  late final GeneratedColumn<String> zoneIds = GeneratedColumn<String>(
    'zone_ids',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  @override
  List<GeneratedColumn> get $columns => [gateId, code, name, zoneIds];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'gates';
  @override
  VerificationContext validateIntegrity(Insertable<Gate> instance, {bool isInserting = false}) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('gate_id')) {
      context.handle(_gateIdMeta, gateId.isAcceptableOrUnknown(data['gate_id']!, _gateIdMeta));
    }
    if (data.containsKey('code')) {
      context.handle(_codeMeta, code.isAcceptableOrUnknown(data['code']!, _codeMeta));
    } else if (isInserting) {
      context.missing(_codeMeta);
    }
    if (data.containsKey('name')) {
      context.handle(_nameMeta, name.isAcceptableOrUnknown(data['name']!, _nameMeta));
    } else if (isInserting) {
      context.missing(_nameMeta);
    }
    if (data.containsKey('zone_ids')) {
      context.handle(_zoneIdsMeta, zoneIds.isAcceptableOrUnknown(data['zone_ids']!, _zoneIdsMeta));
    } else if (isInserting) {
      context.missing(_zoneIdsMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {gateId};
  @override
  Gate map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return Gate(
      gateId: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}gate_id']),
      code: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}code'])!,
      name: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}name'])!,
      zoneIds: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}zone_ids'])!,
    );
  }

  @override
  Gates createAlias(String alias) {
    return Gates(attachedDatabase, alias);
  }

  @override
  bool get dontWriteConstraints => true;
}

class Gate extends DataClass implements Insertable<Gate> {
  final String? gateId;
  final String code;
  final String name;
  final String zoneIds;
  const Gate({this.gateId, required this.code, required this.name, required this.zoneIds});
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (!nullToAbsent || gateId != null) {
      map['gate_id'] = Variable<String>(gateId);
    }
    map['code'] = Variable<String>(code);
    map['name'] = Variable<String>(name);
    map['zone_ids'] = Variable<String>(zoneIds);
    return map;
  }

  GatesCompanion toCompanion(bool nullToAbsent) {
    return GatesCompanion(
      gateId: gateId == null && nullToAbsent ? const Value.absent() : Value(gateId),
      code: Value(code),
      name: Value(name),
      zoneIds: Value(zoneIds),
    );
  }

  factory Gate.fromJson(Map<String, dynamic> json, {ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return Gate(
      gateId: serializer.fromJson<String?>(json['gate_id']),
      code: serializer.fromJson<String>(json['code']),
      name: serializer.fromJson<String>(json['name']),
      zoneIds: serializer.fromJson<String>(json['zone_ids']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'gate_id': serializer.toJson<String?>(gateId),
      'code': serializer.toJson<String>(code),
      'name': serializer.toJson<String>(name),
      'zone_ids': serializer.toJson<String>(zoneIds),
    };
  }

  Gate copyWith({Value<String?> gateId = const Value.absent(), String? code, String? name, String? zoneIds}) => Gate(
    gateId: gateId.present ? gateId.value : this.gateId,
    code: code ?? this.code,
    name: name ?? this.name,
    zoneIds: zoneIds ?? this.zoneIds,
  );
  Gate copyWithCompanion(GatesCompanion data) {
    return Gate(
      gateId: data.gateId.present ? data.gateId.value : this.gateId,
      code: data.code.present ? data.code.value : this.code,
      name: data.name.present ? data.name.value : this.name,
      zoneIds: data.zoneIds.present ? data.zoneIds.value : this.zoneIds,
    );
  }

  @override
  String toString() {
    return (StringBuffer('Gate(')
          ..write('gateId: $gateId, ')
          ..write('code: $code, ')
          ..write('name: $name, ')
          ..write('zoneIds: $zoneIds')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(gateId, code, name, zoneIds);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is Gate &&
          other.gateId == this.gateId &&
          other.code == this.code &&
          other.name == this.name &&
          other.zoneIds == this.zoneIds);
}

class GatesCompanion extends UpdateCompanion<Gate> {
  final Value<String?> gateId;
  final Value<String> code;
  final Value<String> name;
  final Value<String> zoneIds;
  final Value<int> rowid;
  const GatesCompanion({
    this.gateId = const Value.absent(),
    this.code = const Value.absent(),
    this.name = const Value.absent(),
    this.zoneIds = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  GatesCompanion.insert({
    this.gateId = const Value.absent(),
    required String code,
    required String name,
    required String zoneIds,
    this.rowid = const Value.absent(),
  }) : code = Value(code),
       name = Value(name),
       zoneIds = Value(zoneIds);
  static Insertable<Gate> custom({
    Expression<String>? gateId,
    Expression<String>? code,
    Expression<String>? name,
    Expression<String>? zoneIds,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (gateId != null) 'gate_id': gateId,
      if (code != null) 'code': code,
      if (name != null) 'name': name,
      if (zoneIds != null) 'zone_ids': zoneIds,
      if (rowid != null) 'rowid': rowid,
    });
  }

  GatesCompanion copyWith({
    Value<String?>? gateId,
    Value<String>? code,
    Value<String>? name,
    Value<String>? zoneIds,
    Value<int>? rowid,
  }) {
    return GatesCompanion(
      gateId: gateId ?? this.gateId,
      code: code ?? this.code,
      name: name ?? this.name,
      zoneIds: zoneIds ?? this.zoneIds,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (gateId.present) {
      map['gate_id'] = Variable<String>(gateId.value);
    }
    if (code.present) {
      map['code'] = Variable<String>(code.value);
    }
    if (name.present) {
      map['name'] = Variable<String>(name.value);
    }
    if (zoneIds.present) {
      map['zone_ids'] = Variable<String>(zoneIds.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('GatesCompanion(')
          ..write('gateId: $gateId, ')
          ..write('code: $code, ')
          ..write('name: $name, ')
          ..write('zoneIds: $zoneIds, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class Staff extends Table with TableInfo<Staff, StaffData> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  Staff(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _staffIdMeta = const VerificationMeta('staffId');
  late final GeneratedColumn<String> staffId = GeneratedColumn<String>(
    'staff_id',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: 'PRIMARY KEY',
  );
  static const VerificationMeta _labelMeta = const VerificationMeta('label');
  late final GeneratedColumn<String> label = GeneratedColumn<String>(
    'label',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _roleMeta = const VerificationMeta('role');
  late final GeneratedColumn<String> role = GeneratedColumn<String>(
    'role',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _pinVerifierMeta = const VerificationMeta('pinVerifier');
  late final GeneratedColumn<String> pinVerifier = GeneratedColumn<String>(
    'pin_verifier',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  @override
  List<GeneratedColumn> get $columns => [staffId, label, role, pinVerifier];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'staff';
  @override
  VerificationContext validateIntegrity(Insertable<StaffData> instance, {bool isInserting = false}) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('staff_id')) {
      context.handle(_staffIdMeta, staffId.isAcceptableOrUnknown(data['staff_id']!, _staffIdMeta));
    }
    if (data.containsKey('label')) {
      context.handle(_labelMeta, label.isAcceptableOrUnknown(data['label']!, _labelMeta));
    } else if (isInserting) {
      context.missing(_labelMeta);
    }
    if (data.containsKey('role')) {
      context.handle(_roleMeta, role.isAcceptableOrUnknown(data['role']!, _roleMeta));
    } else if (isInserting) {
      context.missing(_roleMeta);
    }
    if (data.containsKey('pin_verifier')) {
      context.handle(_pinVerifierMeta, pinVerifier.isAcceptableOrUnknown(data['pin_verifier']!, _pinVerifierMeta));
    } else if (isInserting) {
      context.missing(_pinVerifierMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {staffId};
  @override
  StaffData map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return StaffData(
      staffId: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}staff_id']),
      label: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}label'])!,
      role: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}role'])!,
      pinVerifier: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}pin_verifier'])!,
    );
  }

  @override
  Staff createAlias(String alias) {
    return Staff(attachedDatabase, alias);
  }

  @override
  bool get dontWriteConstraints => true;
}

class StaffData extends DataClass implements Insertable<StaffData> {
  final String? staffId;
  final String label;
  final String role;
  final String pinVerifier;
  const StaffData({this.staffId, required this.label, required this.role, required this.pinVerifier});
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (!nullToAbsent || staffId != null) {
      map['staff_id'] = Variable<String>(staffId);
    }
    map['label'] = Variable<String>(label);
    map['role'] = Variable<String>(role);
    map['pin_verifier'] = Variable<String>(pinVerifier);
    return map;
  }

  StaffCompanion toCompanion(bool nullToAbsent) {
    return StaffCompanion(
      staffId: staffId == null && nullToAbsent ? const Value.absent() : Value(staffId),
      label: Value(label),
      role: Value(role),
      pinVerifier: Value(pinVerifier),
    );
  }

  factory StaffData.fromJson(Map<String, dynamic> json, {ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return StaffData(
      staffId: serializer.fromJson<String?>(json['staff_id']),
      label: serializer.fromJson<String>(json['label']),
      role: serializer.fromJson<String>(json['role']),
      pinVerifier: serializer.fromJson<String>(json['pin_verifier']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'staff_id': serializer.toJson<String?>(staffId),
      'label': serializer.toJson<String>(label),
      'role': serializer.toJson<String>(role),
      'pin_verifier': serializer.toJson<String>(pinVerifier),
    };
  }

  StaffData copyWith({
    Value<String?> staffId = const Value.absent(),
    String? label,
    String? role,
    String? pinVerifier,
  }) => StaffData(
    staffId: staffId.present ? staffId.value : this.staffId,
    label: label ?? this.label,
    role: role ?? this.role,
    pinVerifier: pinVerifier ?? this.pinVerifier,
  );
  StaffData copyWithCompanion(StaffCompanion data) {
    return StaffData(
      staffId: data.staffId.present ? data.staffId.value : this.staffId,
      label: data.label.present ? data.label.value : this.label,
      role: data.role.present ? data.role.value : this.role,
      pinVerifier: data.pinVerifier.present ? data.pinVerifier.value : this.pinVerifier,
    );
  }

  @override
  String toString() {
    return (StringBuffer('StaffData(')
          ..write('staffId: $staffId, ')
          ..write('label: $label, ')
          ..write('role: $role, ')
          ..write('pinVerifier: $pinVerifier')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(staffId, label, role, pinVerifier);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is StaffData &&
          other.staffId == this.staffId &&
          other.label == this.label &&
          other.role == this.role &&
          other.pinVerifier == this.pinVerifier);
}

class StaffCompanion extends UpdateCompanion<StaffData> {
  final Value<String?> staffId;
  final Value<String> label;
  final Value<String> role;
  final Value<String> pinVerifier;
  final Value<int> rowid;
  const StaffCompanion({
    this.staffId = const Value.absent(),
    this.label = const Value.absent(),
    this.role = const Value.absent(),
    this.pinVerifier = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  StaffCompanion.insert({
    this.staffId = const Value.absent(),
    required String label,
    required String role,
    required String pinVerifier,
    this.rowid = const Value.absent(),
  }) : label = Value(label),
       role = Value(role),
       pinVerifier = Value(pinVerifier);
  static Insertable<StaffData> custom({
    Expression<String>? staffId,
    Expression<String>? label,
    Expression<String>? role,
    Expression<String>? pinVerifier,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (staffId != null) 'staff_id': staffId,
      if (label != null) 'label': label,
      if (role != null) 'role': role,
      if (pinVerifier != null) 'pin_verifier': pinVerifier,
      if (rowid != null) 'rowid': rowid,
    });
  }

  StaffCompanion copyWith({
    Value<String?>? staffId,
    Value<String>? label,
    Value<String>? role,
    Value<String>? pinVerifier,
    Value<int>? rowid,
  }) {
    return StaffCompanion(
      staffId: staffId ?? this.staffId,
      label: label ?? this.label,
      role: role ?? this.role,
      pinVerifier: pinVerifier ?? this.pinVerifier,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (staffId.present) {
      map['staff_id'] = Variable<String>(staffId.value);
    }
    if (label.present) {
      map['label'] = Variable<String>(label.value);
    }
    if (role.present) {
      map['role'] = Variable<String>(role.value);
    }
    if (pinVerifier.present) {
      map['pin_verifier'] = Variable<String>(pinVerifier.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('StaffCompanion(')
          ..write('staffId: $staffId, ')
          ..write('label: $label, ')
          ..write('role: $role, ')
          ..write('pinVerifier: $pinVerifier, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class ManifestTickets extends Table with TableInfo<ManifestTickets, ManifestTicket> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  ManifestTickets(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _ticketIdMeta = const VerificationMeta('ticketId');
  late final GeneratedColumn<String> ticketId = GeneratedColumn<String>(
    'ticket_id',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: 'PRIMARY KEY',
  );
  static const VerificationMeta _ticketNoMeta = const VerificationMeta('ticketNo');
  late final GeneratedColumn<String> ticketNo = GeneratedColumn<String>(
    'ticket_no',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _zoneIdMeta = const VerificationMeta('zoneId');
  late final GeneratedColumn<int> zoneId = GeneratedColumn<int>(
    'zone_id',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _statusMeta = const VerificationMeta('status');
  late final GeneratedColumn<String> status = GeneratedColumn<String>(
    'status',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _holderInitialsMeta = const VerificationMeta('holderInitials');
  late final GeneratedColumn<String> holderInitials = GeneratedColumn<String>(
    'holder_initials',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  @override
  List<GeneratedColumn> get $columns => [ticketId, ticketNo, zoneId, status, holderInitials];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'manifest_tickets';
  @override
  VerificationContext validateIntegrity(Insertable<ManifestTicket> instance, {bool isInserting = false}) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('ticket_id')) {
      context.handle(_ticketIdMeta, ticketId.isAcceptableOrUnknown(data['ticket_id']!, _ticketIdMeta));
    }
    if (data.containsKey('ticket_no')) {
      context.handle(_ticketNoMeta, ticketNo.isAcceptableOrUnknown(data['ticket_no']!, _ticketNoMeta));
    } else if (isInserting) {
      context.missing(_ticketNoMeta);
    }
    if (data.containsKey('zone_id')) {
      context.handle(_zoneIdMeta, zoneId.isAcceptableOrUnknown(data['zone_id']!, _zoneIdMeta));
    } else if (isInserting) {
      context.missing(_zoneIdMeta);
    }
    if (data.containsKey('status')) {
      context.handle(_statusMeta, status.isAcceptableOrUnknown(data['status']!, _statusMeta));
    } else if (isInserting) {
      context.missing(_statusMeta);
    }
    if (data.containsKey('holder_initials')) {
      context.handle(
        _holderInitialsMeta,
        holderInitials.isAcceptableOrUnknown(data['holder_initials']!, _holderInitialsMeta),
      );
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {ticketId};
  @override
  ManifestTicket map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return ManifestTicket(
      ticketId: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}ticket_id']),
      ticketNo: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}ticket_no'])!,
      zoneId: attachedDatabase.typeMapping.read(DriftSqlType.int, data['${effectivePrefix}zone_id'])!,
      status: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}status'])!,
      holderInitials: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}holder_initials']),
    );
  }

  @override
  ManifestTickets createAlias(String alias) {
    return ManifestTickets(attachedDatabase, alias);
  }

  @override
  bool get dontWriteConstraints => true;
}

class ManifestTicket extends DataClass implements Insertable<ManifestTicket> {
  final String? ticketId;

  /// UUID string
  final String ticketNo;

  /// last 8 hex, uppercase, no space (manual lookup)
  final int zoneId;
  final String status;

  /// VALID | CANCELLED | REFUNDED | BLOCKED
  final String? holderInitials;
  const ManifestTicket({
    this.ticketId,
    required this.ticketNo,
    required this.zoneId,
    required this.status,
    this.holderInitials,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (!nullToAbsent || ticketId != null) {
      map['ticket_id'] = Variable<String>(ticketId);
    }
    map['ticket_no'] = Variable<String>(ticketNo);
    map['zone_id'] = Variable<int>(zoneId);
    map['status'] = Variable<String>(status);
    if (!nullToAbsent || holderInitials != null) {
      map['holder_initials'] = Variable<String>(holderInitials);
    }
    return map;
  }

  ManifestTicketsCompanion toCompanion(bool nullToAbsent) {
    return ManifestTicketsCompanion(
      ticketId: ticketId == null && nullToAbsent ? const Value.absent() : Value(ticketId),
      ticketNo: Value(ticketNo),
      zoneId: Value(zoneId),
      status: Value(status),
      holderInitials: holderInitials == null && nullToAbsent ? const Value.absent() : Value(holderInitials),
    );
  }

  factory ManifestTicket.fromJson(Map<String, dynamic> json, {ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return ManifestTicket(
      ticketId: serializer.fromJson<String?>(json['ticket_id']),
      ticketNo: serializer.fromJson<String>(json['ticket_no']),
      zoneId: serializer.fromJson<int>(json['zone_id']),
      status: serializer.fromJson<String>(json['status']),
      holderInitials: serializer.fromJson<String?>(json['holder_initials']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'ticket_id': serializer.toJson<String?>(ticketId),
      'ticket_no': serializer.toJson<String>(ticketNo),
      'zone_id': serializer.toJson<int>(zoneId),
      'status': serializer.toJson<String>(status),
      'holder_initials': serializer.toJson<String?>(holderInitials),
    };
  }

  ManifestTicket copyWith({
    Value<String?> ticketId = const Value.absent(),
    String? ticketNo,
    int? zoneId,
    String? status,
    Value<String?> holderInitials = const Value.absent(),
  }) => ManifestTicket(
    ticketId: ticketId.present ? ticketId.value : this.ticketId,
    ticketNo: ticketNo ?? this.ticketNo,
    zoneId: zoneId ?? this.zoneId,
    status: status ?? this.status,
    holderInitials: holderInitials.present ? holderInitials.value : this.holderInitials,
  );
  ManifestTicket copyWithCompanion(ManifestTicketsCompanion data) {
    return ManifestTicket(
      ticketId: data.ticketId.present ? data.ticketId.value : this.ticketId,
      ticketNo: data.ticketNo.present ? data.ticketNo.value : this.ticketNo,
      zoneId: data.zoneId.present ? data.zoneId.value : this.zoneId,
      status: data.status.present ? data.status.value : this.status,
      holderInitials: data.holderInitials.present ? data.holderInitials.value : this.holderInitials,
    );
  }

  @override
  String toString() {
    return (StringBuffer('ManifestTicket(')
          ..write('ticketId: $ticketId, ')
          ..write('ticketNo: $ticketNo, ')
          ..write('zoneId: $zoneId, ')
          ..write('status: $status, ')
          ..write('holderInitials: $holderInitials')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(ticketId, ticketNo, zoneId, status, holderInitials);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is ManifestTicket &&
          other.ticketId == this.ticketId &&
          other.ticketNo == this.ticketNo &&
          other.zoneId == this.zoneId &&
          other.status == this.status &&
          other.holderInitials == this.holderInitials);
}

class ManifestTicketsCompanion extends UpdateCompanion<ManifestTicket> {
  final Value<String?> ticketId;
  final Value<String> ticketNo;
  final Value<int> zoneId;
  final Value<String> status;
  final Value<String?> holderInitials;
  final Value<int> rowid;
  const ManifestTicketsCompanion({
    this.ticketId = const Value.absent(),
    this.ticketNo = const Value.absent(),
    this.zoneId = const Value.absent(),
    this.status = const Value.absent(),
    this.holderInitials = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  ManifestTicketsCompanion.insert({
    this.ticketId = const Value.absent(),
    required String ticketNo,
    required int zoneId,
    required String status,
    this.holderInitials = const Value.absent(),
    this.rowid = const Value.absent(),
  }) : ticketNo = Value(ticketNo),
       zoneId = Value(zoneId),
       status = Value(status);
  static Insertable<ManifestTicket> custom({
    Expression<String>? ticketId,
    Expression<String>? ticketNo,
    Expression<int>? zoneId,
    Expression<String>? status,
    Expression<String>? holderInitials,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (ticketId != null) 'ticket_id': ticketId,
      if (ticketNo != null) 'ticket_no': ticketNo,
      if (zoneId != null) 'zone_id': zoneId,
      if (status != null) 'status': status,
      if (holderInitials != null) 'holder_initials': holderInitials,
      if (rowid != null) 'rowid': rowid,
    });
  }

  ManifestTicketsCompanion copyWith({
    Value<String?>? ticketId,
    Value<String>? ticketNo,
    Value<int>? zoneId,
    Value<String>? status,
    Value<String?>? holderInitials,
    Value<int>? rowid,
  }) {
    return ManifestTicketsCompanion(
      ticketId: ticketId ?? this.ticketId,
      ticketNo: ticketNo ?? this.ticketNo,
      zoneId: zoneId ?? this.zoneId,
      status: status ?? this.status,
      holderInitials: holderInitials ?? this.holderInitials,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (ticketId.present) {
      map['ticket_id'] = Variable<String>(ticketId.value);
    }
    if (ticketNo.present) {
      map['ticket_no'] = Variable<String>(ticketNo.value);
    }
    if (zoneId.present) {
      map['zone_id'] = Variable<int>(zoneId.value);
    }
    if (status.present) {
      map['status'] = Variable<String>(status.value);
    }
    if (holderInitials.present) {
      map['holder_initials'] = Variable<String>(holderInitials.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('ManifestTicketsCompanion(')
          ..write('ticketId: $ticketId, ')
          ..write('ticketNo: $ticketNo, ')
          ..write('zoneId: $zoneId, ')
          ..write('status: $status, ')
          ..write('holderInitials: $holderInitials, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class Used extends Table with TableInfo<Used, UsedData> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  Used(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _ticketIdMeta = const VerificationMeta('ticketId');
  late final GeneratedColumn<String> ticketId = GeneratedColumn<String>(
    'ticket_id',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _directionMeta = const VerificationMeta('direction');
  late final GeneratedColumn<String> direction = GeneratedColumn<String>(
    'direction',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _firstAtMeta = const VerificationMeta('firstAt');
  late final GeneratedColumn<int> firstAt = GeneratedColumn<int>(
    'first_at',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _gateCodeMeta = const VerificationMeta('gateCode');
  late final GeneratedColumn<String> gateCode = GeneratedColumn<String>(
    'gate_code',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  static const VerificationMeta _sourceMeta = const VerificationMeta('source');
  late final GeneratedColumn<String> source = GeneratedColumn<String>(
    'source',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  @override
  List<GeneratedColumn> get $columns => [ticketId, direction, firstAt, gateCode, source];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'used';
  @override
  VerificationContext validateIntegrity(Insertable<UsedData> instance, {bool isInserting = false}) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('ticket_id')) {
      context.handle(_ticketIdMeta, ticketId.isAcceptableOrUnknown(data['ticket_id']!, _ticketIdMeta));
    } else if (isInserting) {
      context.missing(_ticketIdMeta);
    }
    if (data.containsKey('direction')) {
      context.handle(_directionMeta, direction.isAcceptableOrUnknown(data['direction']!, _directionMeta));
    } else if (isInserting) {
      context.missing(_directionMeta);
    }
    if (data.containsKey('first_at')) {
      context.handle(_firstAtMeta, firstAt.isAcceptableOrUnknown(data['first_at']!, _firstAtMeta));
    } else if (isInserting) {
      context.missing(_firstAtMeta);
    }
    if (data.containsKey('gate_code')) {
      context.handle(_gateCodeMeta, gateCode.isAcceptableOrUnknown(data['gate_code']!, _gateCodeMeta));
    }
    if (data.containsKey('source')) {
      context.handle(_sourceMeta, source.isAcceptableOrUnknown(data['source']!, _sourceMeta));
    } else if (isInserting) {
      context.missing(_sourceMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {ticketId, direction};
  @override
  UsedData map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return UsedData(
      ticketId: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}ticket_id'])!,
      direction: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}direction'])!,
      firstAt: attachedDatabase.typeMapping.read(DriftSqlType.int, data['${effectivePrefix}first_at'])!,
      gateCode: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}gate_code']),
      source: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}source'])!,
    );
  }

  @override
  Used createAlias(String alias) {
    return Used(attachedDatabase, alias);
  }

  @override
  List<String> get customConstraints => const ['PRIMARY KEY(ticket_id, direction)'];
  @override
  bool get dontWriteConstraints => true;
}

class UsedData extends DataClass implements Insertable<UsedData> {
  final String ticketId;
  final String direction;
  final int firstAt;

  /// epoch ms, corrected device time
  final String? gateCode;
  final String source;
  const UsedData({
    required this.ticketId,
    required this.direction,
    required this.firstAt,
    this.gateCode,
    required this.source,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['ticket_id'] = Variable<String>(ticketId);
    map['direction'] = Variable<String>(direction);
    map['first_at'] = Variable<int>(firstAt);
    if (!nullToAbsent || gateCode != null) {
      map['gate_code'] = Variable<String>(gateCode);
    }
    map['source'] = Variable<String>(source);
    return map;
  }

  UsedCompanion toCompanion(bool nullToAbsent) {
    return UsedCompanion(
      ticketId: Value(ticketId),
      direction: Value(direction),
      firstAt: Value(firstAt),
      gateCode: gateCode == null && nullToAbsent ? const Value.absent() : Value(gateCode),
      source: Value(source),
    );
  }

  factory UsedData.fromJson(Map<String, dynamic> json, {ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return UsedData(
      ticketId: serializer.fromJson<String>(json['ticket_id']),
      direction: serializer.fromJson<String>(json['direction']),
      firstAt: serializer.fromJson<int>(json['first_at']),
      gateCode: serializer.fromJson<String?>(json['gate_code']),
      source: serializer.fromJson<String>(json['source']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'ticket_id': serializer.toJson<String>(ticketId),
      'direction': serializer.toJson<String>(direction),
      'first_at': serializer.toJson<int>(firstAt),
      'gate_code': serializer.toJson<String?>(gateCode),
      'source': serializer.toJson<String>(source),
    };
  }

  UsedData copyWith({
    String? ticketId,
    String? direction,
    int? firstAt,
    Value<String?> gateCode = const Value.absent(),
    String? source,
  }) => UsedData(
    ticketId: ticketId ?? this.ticketId,
    direction: direction ?? this.direction,
    firstAt: firstAt ?? this.firstAt,
    gateCode: gateCode.present ? gateCode.value : this.gateCode,
    source: source ?? this.source,
  );
  UsedData copyWithCompanion(UsedCompanion data) {
    return UsedData(
      ticketId: data.ticketId.present ? data.ticketId.value : this.ticketId,
      direction: data.direction.present ? data.direction.value : this.direction,
      firstAt: data.firstAt.present ? data.firstAt.value : this.firstAt,
      gateCode: data.gateCode.present ? data.gateCode.value : this.gateCode,
      source: data.source.present ? data.source.value : this.source,
    );
  }

  @override
  String toString() {
    return (StringBuffer('UsedData(')
          ..write('ticketId: $ticketId, ')
          ..write('direction: $direction, ')
          ..write('firstAt: $firstAt, ')
          ..write('gateCode: $gateCode, ')
          ..write('source: $source')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(ticketId, direction, firstAt, gateCode, source);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is UsedData &&
          other.ticketId == this.ticketId &&
          other.direction == this.direction &&
          other.firstAt == this.firstAt &&
          other.gateCode == this.gateCode &&
          other.source == this.source);
}

class UsedCompanion extends UpdateCompanion<UsedData> {
  final Value<String> ticketId;
  final Value<String> direction;
  final Value<int> firstAt;
  final Value<String?> gateCode;
  final Value<String> source;
  final Value<int> rowid;
  const UsedCompanion({
    this.ticketId = const Value.absent(),
    this.direction = const Value.absent(),
    this.firstAt = const Value.absent(),
    this.gateCode = const Value.absent(),
    this.source = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  UsedCompanion.insert({
    required String ticketId,
    required String direction,
    required int firstAt,
    this.gateCode = const Value.absent(),
    required String source,
    this.rowid = const Value.absent(),
  }) : ticketId = Value(ticketId),
       direction = Value(direction),
       firstAt = Value(firstAt),
       source = Value(source);
  static Insertable<UsedData> custom({
    Expression<String>? ticketId,
    Expression<String>? direction,
    Expression<int>? firstAt,
    Expression<String>? gateCode,
    Expression<String>? source,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (ticketId != null) 'ticket_id': ticketId,
      if (direction != null) 'direction': direction,
      if (firstAt != null) 'first_at': firstAt,
      if (gateCode != null) 'gate_code': gateCode,
      if (source != null) 'source': source,
      if (rowid != null) 'rowid': rowid,
    });
  }

  UsedCompanion copyWith({
    Value<String>? ticketId,
    Value<String>? direction,
    Value<int>? firstAt,
    Value<String?>? gateCode,
    Value<String>? source,
    Value<int>? rowid,
  }) {
    return UsedCompanion(
      ticketId: ticketId ?? this.ticketId,
      direction: direction ?? this.direction,
      firstAt: firstAt ?? this.firstAt,
      gateCode: gateCode ?? this.gateCode,
      source: source ?? this.source,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (ticketId.present) {
      map['ticket_id'] = Variable<String>(ticketId.value);
    }
    if (direction.present) {
      map['direction'] = Variable<String>(direction.value);
    }
    if (firstAt.present) {
      map['first_at'] = Variable<int>(firstAt.value);
    }
    if (gateCode.present) {
      map['gate_code'] = Variable<String>(gateCode.value);
    }
    if (source.present) {
      map['source'] = Variable<String>(source.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('UsedCompanion(')
          ..write('ticketId: $ticketId, ')
          ..write('direction: $direction, ')
          ..write('firstAt: $firstAt, ')
          ..write('gateCode: $gateCode, ')
          ..write('source: $source, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class Scans extends Table with TableInfo<Scans, Scan> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  Scans(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _scanIdMeta = const VerificationMeta('scanId');
  late final GeneratedColumn<String> scanId = GeneratedColumn<String>(
    'scan_id',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: 'PRIMARY KEY',
  );
  static const VerificationMeta _ticketIdMeta = const VerificationMeta('ticketId');
  late final GeneratedColumn<String> ticketId = GeneratedColumn<String>(
    'ticket_id',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  static const VerificationMeta _gateIdMeta = const VerificationMeta('gateId');
  late final GeneratedColumn<String> gateId = GeneratedColumn<String>(
    'gate_id',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _staffIdMeta = const VerificationMeta('staffId');
  late final GeneratedColumn<String> staffId = GeneratedColumn<String>(
    'staff_id',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _directionMeta = const VerificationMeta('direction');
  late final GeneratedColumn<String> direction = GeneratedColumn<String>(
    'direction',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _resultMeta = const VerificationMeta('result');
  late final GeneratedColumn<String> result = GeneratedColumn<String>(
    'result',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _tokenVersionMeta = const VerificationMeta('tokenVersion');
  late final GeneratedColumn<int> tokenVersion = GeneratedColumn<int>(
    'token_version',
    aliasedName,
    true,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  static const VerificationMeta _deviceTimeMeta = const VerificationMeta('deviceTime');
  late final GeneratedColumn<int> deviceTime = GeneratedColumn<int>(
    'device_time',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: true,
    $customConstraints: 'NOT NULL',
  );
  static const VerificationMeta _syncedMeta = const VerificationMeta('synced');
  late final GeneratedColumn<int> synced = GeneratedColumn<int>(
    'synced',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
    $customConstraints: 'NOT NULL DEFAULT 0',
    defaultValue: const CustomExpression('0'),
  );
  static const VerificationMeta _overrideReasonMeta = const VerificationMeta('overrideReason');
  late final GeneratedColumn<String> overrideReason = GeneratedColumn<String>(
    'override_reason',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  static const VerificationMeta _supervisorIdMeta = const VerificationMeta('supervisorId');
  late final GeneratedColumn<String> supervisorId = GeneratedColumn<String>(
    'supervisor_id',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
    $customConstraints: '',
  );
  @override
  List<GeneratedColumn> get $columns => [
    scanId,
    ticketId,
    gateId,
    staffId,
    direction,
    result,
    tokenVersion,
    deviceTime,
    synced,
    overrideReason,
    supervisorId,
  ];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'scans';
  @override
  VerificationContext validateIntegrity(Insertable<Scan> instance, {bool isInserting = false}) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('scan_id')) {
      context.handle(_scanIdMeta, scanId.isAcceptableOrUnknown(data['scan_id']!, _scanIdMeta));
    }
    if (data.containsKey('ticket_id')) {
      context.handle(_ticketIdMeta, ticketId.isAcceptableOrUnknown(data['ticket_id']!, _ticketIdMeta));
    }
    if (data.containsKey('gate_id')) {
      context.handle(_gateIdMeta, gateId.isAcceptableOrUnknown(data['gate_id']!, _gateIdMeta));
    } else if (isInserting) {
      context.missing(_gateIdMeta);
    }
    if (data.containsKey('staff_id')) {
      context.handle(_staffIdMeta, staffId.isAcceptableOrUnknown(data['staff_id']!, _staffIdMeta));
    } else if (isInserting) {
      context.missing(_staffIdMeta);
    }
    if (data.containsKey('direction')) {
      context.handle(_directionMeta, direction.isAcceptableOrUnknown(data['direction']!, _directionMeta));
    } else if (isInserting) {
      context.missing(_directionMeta);
    }
    if (data.containsKey('result')) {
      context.handle(_resultMeta, result.isAcceptableOrUnknown(data['result']!, _resultMeta));
    } else if (isInserting) {
      context.missing(_resultMeta);
    }
    if (data.containsKey('token_version')) {
      context.handle(_tokenVersionMeta, tokenVersion.isAcceptableOrUnknown(data['token_version']!, _tokenVersionMeta));
    }
    if (data.containsKey('device_time')) {
      context.handle(_deviceTimeMeta, deviceTime.isAcceptableOrUnknown(data['device_time']!, _deviceTimeMeta));
    } else if (isInserting) {
      context.missing(_deviceTimeMeta);
    }
    if (data.containsKey('synced')) {
      context.handle(_syncedMeta, synced.isAcceptableOrUnknown(data['synced']!, _syncedMeta));
    }
    if (data.containsKey('override_reason')) {
      context.handle(
        _overrideReasonMeta,
        overrideReason.isAcceptableOrUnknown(data['override_reason']!, _overrideReasonMeta),
      );
    }
    if (data.containsKey('supervisor_id')) {
      context.handle(_supervisorIdMeta, supervisorId.isAcceptableOrUnknown(data['supervisor_id']!, _supervisorIdMeta));
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {scanId};
  @override
  Scan map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return Scan(
      scanId: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}scan_id']),
      ticketId: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}ticket_id']),
      gateId: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}gate_id'])!,
      staffId: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}staff_id'])!,
      direction: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}direction'])!,
      result: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}result'])!,
      tokenVersion: attachedDatabase.typeMapping.read(DriftSqlType.int, data['${effectivePrefix}token_version']),
      deviceTime: attachedDatabase.typeMapping.read(DriftSqlType.int, data['${effectivePrefix}device_time'])!,
      synced: attachedDatabase.typeMapping.read(DriftSqlType.int, data['${effectivePrefix}synced'])!,
      overrideReason: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}override_reason']),
      supervisorId: attachedDatabase.typeMapping.read(DriftSqlType.string, data['${effectivePrefix}supervisor_id']),
    );
  }

  @override
  Scans createAlias(String alias) {
    return Scans(attachedDatabase, alias);
  }

  @override
  bool get dontWriteConstraints => true;
}

class Scan extends DataClass implements Insertable<Scan> {
  final String? scanId;

  /// UUIDv7
  final String? ticketId;

  /// null for BAD_FORMAT, WRONG_EVENT, EXPIRED_SLOT, BAD_SIG
  final String gateId;
  final String staffId;
  final String direction;
  final String result;
  final int? tokenVersion;
  final int deviceTime;

  /// epoch ms, corrected
  final int synced;
  final String? overrideReason;
  final String? supervisorId;
  const Scan({
    this.scanId,
    this.ticketId,
    required this.gateId,
    required this.staffId,
    required this.direction,
    required this.result,
    this.tokenVersion,
    required this.deviceTime,
    required this.synced,
    this.overrideReason,
    this.supervisorId,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (!nullToAbsent || scanId != null) {
      map['scan_id'] = Variable<String>(scanId);
    }
    if (!nullToAbsent || ticketId != null) {
      map['ticket_id'] = Variable<String>(ticketId);
    }
    map['gate_id'] = Variable<String>(gateId);
    map['staff_id'] = Variable<String>(staffId);
    map['direction'] = Variable<String>(direction);
    map['result'] = Variable<String>(result);
    if (!nullToAbsent || tokenVersion != null) {
      map['token_version'] = Variable<int>(tokenVersion);
    }
    map['device_time'] = Variable<int>(deviceTime);
    map['synced'] = Variable<int>(synced);
    if (!nullToAbsent || overrideReason != null) {
      map['override_reason'] = Variable<String>(overrideReason);
    }
    if (!nullToAbsent || supervisorId != null) {
      map['supervisor_id'] = Variable<String>(supervisorId);
    }
    return map;
  }

  ScansCompanion toCompanion(bool nullToAbsent) {
    return ScansCompanion(
      scanId: scanId == null && nullToAbsent ? const Value.absent() : Value(scanId),
      ticketId: ticketId == null && nullToAbsent ? const Value.absent() : Value(ticketId),
      gateId: Value(gateId),
      staffId: Value(staffId),
      direction: Value(direction),
      result: Value(result),
      tokenVersion: tokenVersion == null && nullToAbsent ? const Value.absent() : Value(tokenVersion),
      deviceTime: Value(deviceTime),
      synced: Value(synced),
      overrideReason: overrideReason == null && nullToAbsent ? const Value.absent() : Value(overrideReason),
      supervisorId: supervisorId == null && nullToAbsent ? const Value.absent() : Value(supervisorId),
    );
  }

  factory Scan.fromJson(Map<String, dynamic> json, {ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return Scan(
      scanId: serializer.fromJson<String?>(json['scan_id']),
      ticketId: serializer.fromJson<String?>(json['ticket_id']),
      gateId: serializer.fromJson<String>(json['gate_id']),
      staffId: serializer.fromJson<String>(json['staff_id']),
      direction: serializer.fromJson<String>(json['direction']),
      result: serializer.fromJson<String>(json['result']),
      tokenVersion: serializer.fromJson<int?>(json['token_version']),
      deviceTime: serializer.fromJson<int>(json['device_time']),
      synced: serializer.fromJson<int>(json['synced']),
      overrideReason: serializer.fromJson<String?>(json['override_reason']),
      supervisorId: serializer.fromJson<String?>(json['supervisor_id']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'scan_id': serializer.toJson<String?>(scanId),
      'ticket_id': serializer.toJson<String?>(ticketId),
      'gate_id': serializer.toJson<String>(gateId),
      'staff_id': serializer.toJson<String>(staffId),
      'direction': serializer.toJson<String>(direction),
      'result': serializer.toJson<String>(result),
      'token_version': serializer.toJson<int?>(tokenVersion),
      'device_time': serializer.toJson<int>(deviceTime),
      'synced': serializer.toJson<int>(synced),
      'override_reason': serializer.toJson<String?>(overrideReason),
      'supervisor_id': serializer.toJson<String?>(supervisorId),
    };
  }

  Scan copyWith({
    Value<String?> scanId = const Value.absent(),
    Value<String?> ticketId = const Value.absent(),
    String? gateId,
    String? staffId,
    String? direction,
    String? result,
    Value<int?> tokenVersion = const Value.absent(),
    int? deviceTime,
    int? synced,
    Value<String?> overrideReason = const Value.absent(),
    Value<String?> supervisorId = const Value.absent(),
  }) => Scan(
    scanId: scanId.present ? scanId.value : this.scanId,
    ticketId: ticketId.present ? ticketId.value : this.ticketId,
    gateId: gateId ?? this.gateId,
    staffId: staffId ?? this.staffId,
    direction: direction ?? this.direction,
    result: result ?? this.result,
    tokenVersion: tokenVersion.present ? tokenVersion.value : this.tokenVersion,
    deviceTime: deviceTime ?? this.deviceTime,
    synced: synced ?? this.synced,
    overrideReason: overrideReason.present ? overrideReason.value : this.overrideReason,
    supervisorId: supervisorId.present ? supervisorId.value : this.supervisorId,
  );
  Scan copyWithCompanion(ScansCompanion data) {
    return Scan(
      scanId: data.scanId.present ? data.scanId.value : this.scanId,
      ticketId: data.ticketId.present ? data.ticketId.value : this.ticketId,
      gateId: data.gateId.present ? data.gateId.value : this.gateId,
      staffId: data.staffId.present ? data.staffId.value : this.staffId,
      direction: data.direction.present ? data.direction.value : this.direction,
      result: data.result.present ? data.result.value : this.result,
      tokenVersion: data.tokenVersion.present ? data.tokenVersion.value : this.tokenVersion,
      deviceTime: data.deviceTime.present ? data.deviceTime.value : this.deviceTime,
      synced: data.synced.present ? data.synced.value : this.synced,
      overrideReason: data.overrideReason.present ? data.overrideReason.value : this.overrideReason,
      supervisorId: data.supervisorId.present ? data.supervisorId.value : this.supervisorId,
    );
  }

  @override
  String toString() {
    return (StringBuffer('Scan(')
          ..write('scanId: $scanId, ')
          ..write('ticketId: $ticketId, ')
          ..write('gateId: $gateId, ')
          ..write('staffId: $staffId, ')
          ..write('direction: $direction, ')
          ..write('result: $result, ')
          ..write('tokenVersion: $tokenVersion, ')
          ..write('deviceTime: $deviceTime, ')
          ..write('synced: $synced, ')
          ..write('overrideReason: $overrideReason, ')
          ..write('supervisorId: $supervisorId')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(
    scanId,
    ticketId,
    gateId,
    staffId,
    direction,
    result,
    tokenVersion,
    deviceTime,
    synced,
    overrideReason,
    supervisorId,
  );
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is Scan &&
          other.scanId == this.scanId &&
          other.ticketId == this.ticketId &&
          other.gateId == this.gateId &&
          other.staffId == this.staffId &&
          other.direction == this.direction &&
          other.result == this.result &&
          other.tokenVersion == this.tokenVersion &&
          other.deviceTime == this.deviceTime &&
          other.synced == this.synced &&
          other.overrideReason == this.overrideReason &&
          other.supervisorId == this.supervisorId);
}

class ScansCompanion extends UpdateCompanion<Scan> {
  final Value<String?> scanId;
  final Value<String?> ticketId;
  final Value<String> gateId;
  final Value<String> staffId;
  final Value<String> direction;
  final Value<String> result;
  final Value<int?> tokenVersion;
  final Value<int> deviceTime;
  final Value<int> synced;
  final Value<String?> overrideReason;
  final Value<String?> supervisorId;
  final Value<int> rowid;
  const ScansCompanion({
    this.scanId = const Value.absent(),
    this.ticketId = const Value.absent(),
    this.gateId = const Value.absent(),
    this.staffId = const Value.absent(),
    this.direction = const Value.absent(),
    this.result = const Value.absent(),
    this.tokenVersion = const Value.absent(),
    this.deviceTime = const Value.absent(),
    this.synced = const Value.absent(),
    this.overrideReason = const Value.absent(),
    this.supervisorId = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  ScansCompanion.insert({
    this.scanId = const Value.absent(),
    this.ticketId = const Value.absent(),
    required String gateId,
    required String staffId,
    required String direction,
    required String result,
    this.tokenVersion = const Value.absent(),
    required int deviceTime,
    this.synced = const Value.absent(),
    this.overrideReason = const Value.absent(),
    this.supervisorId = const Value.absent(),
    this.rowid = const Value.absent(),
  }) : gateId = Value(gateId),
       staffId = Value(staffId),
       direction = Value(direction),
       result = Value(result),
       deviceTime = Value(deviceTime);
  static Insertable<Scan> custom({
    Expression<String>? scanId,
    Expression<String>? ticketId,
    Expression<String>? gateId,
    Expression<String>? staffId,
    Expression<String>? direction,
    Expression<String>? result,
    Expression<int>? tokenVersion,
    Expression<int>? deviceTime,
    Expression<int>? synced,
    Expression<String>? overrideReason,
    Expression<String>? supervisorId,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (scanId != null) 'scan_id': scanId,
      if (ticketId != null) 'ticket_id': ticketId,
      if (gateId != null) 'gate_id': gateId,
      if (staffId != null) 'staff_id': staffId,
      if (direction != null) 'direction': direction,
      if (result != null) 'result': result,
      if (tokenVersion != null) 'token_version': tokenVersion,
      if (deviceTime != null) 'device_time': deviceTime,
      if (synced != null) 'synced': synced,
      if (overrideReason != null) 'override_reason': overrideReason,
      if (supervisorId != null) 'supervisor_id': supervisorId,
      if (rowid != null) 'rowid': rowid,
    });
  }

  ScansCompanion copyWith({
    Value<String?>? scanId,
    Value<String?>? ticketId,
    Value<String>? gateId,
    Value<String>? staffId,
    Value<String>? direction,
    Value<String>? result,
    Value<int?>? tokenVersion,
    Value<int>? deviceTime,
    Value<int>? synced,
    Value<String?>? overrideReason,
    Value<String?>? supervisorId,
    Value<int>? rowid,
  }) {
    return ScansCompanion(
      scanId: scanId ?? this.scanId,
      ticketId: ticketId ?? this.ticketId,
      gateId: gateId ?? this.gateId,
      staffId: staffId ?? this.staffId,
      direction: direction ?? this.direction,
      result: result ?? this.result,
      tokenVersion: tokenVersion ?? this.tokenVersion,
      deviceTime: deviceTime ?? this.deviceTime,
      synced: synced ?? this.synced,
      overrideReason: overrideReason ?? this.overrideReason,
      supervisorId: supervisorId ?? this.supervisorId,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (scanId.present) {
      map['scan_id'] = Variable<String>(scanId.value);
    }
    if (ticketId.present) {
      map['ticket_id'] = Variable<String>(ticketId.value);
    }
    if (gateId.present) {
      map['gate_id'] = Variable<String>(gateId.value);
    }
    if (staffId.present) {
      map['staff_id'] = Variable<String>(staffId.value);
    }
    if (direction.present) {
      map['direction'] = Variable<String>(direction.value);
    }
    if (result.present) {
      map['result'] = Variable<String>(result.value);
    }
    if (tokenVersion.present) {
      map['token_version'] = Variable<int>(tokenVersion.value);
    }
    if (deviceTime.present) {
      map['device_time'] = Variable<int>(deviceTime.value);
    }
    if (synced.present) {
      map['synced'] = Variable<int>(synced.value);
    }
    if (overrideReason.present) {
      map['override_reason'] = Variable<String>(overrideReason.value);
    }
    if (supervisorId.present) {
      map['supervisor_id'] = Variable<String>(supervisorId.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('ScansCompanion(')
          ..write('scanId: $scanId, ')
          ..write('ticketId: $ticketId, ')
          ..write('gateId: $gateId, ')
          ..write('staffId: $staffId, ')
          ..write('direction: $direction, ')
          ..write('result: $result, ')
          ..write('tokenVersion: $tokenVersion, ')
          ..write('deviceTime: $deviceTime, ')
          ..write('synced: $synced, ')
          ..write('overrideReason: $overrideReason, ')
          ..write('supervisorId: $supervisorId, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

abstract class _$ScannerDatabase extends GeneratedDatabase {
  _$ScannerDatabase(QueryExecutor e) : super(e);
  $ScannerDatabaseManager get managers => $ScannerDatabaseManager(this);
  late final Config config = Config(this);
  late final Zones zones = Zones(this);
  late final Gates gates = Gates(this);
  late final Staff staff = Staff(this);
  late final ManifestTickets manifestTickets = ManifestTickets(this);
  late final Index idxManifestTicketNo = Index(
    'idx_manifest_ticket_no',
    'CREATE INDEX idx_manifest_ticket_no ON manifest_tickets (ticket_no)',
  );
  late final Used used = Used(this);
  late final Scans scans = Scans(this);
  late final Index idxScansUnsynced = Index(
    'idx_scans_unsynced',
    'CREATE INDEX idx_scans_unsynced ON scans (synced) WHERE synced = 0',
  );
  @override
  Iterable<TableInfo<Table, Object?>> get allTables => allSchemaEntities.whereType<TableInfo<Table, Object?>>();
  @override
  List<DatabaseSchemaEntity> get allSchemaEntities => [
    config,
    zones,
    gates,
    staff,
    manifestTickets,
    idxManifestTicketNo,
    used,
    scans,
    idxScansUnsynced,
    OnCreateQuery('INSERT INTO config (id) VALUES (1)'),
  ];
}

typedef $ConfigCreateCompanionBuilder = ConfigCompanion Function({
  Value<int?> id,
  Value<String?> deviceId,
  Value<String?> deviceStatus,
  Value<String?> manifestPublicKey,
  Value<int> serverTimeOffsetMs,
  Value<int?> lastServerTimeAt,
  Value<int?> eventId,
  Value<String?> eventTitle,
  Value<int?> startsAt,
  Value<String?> eventStatus,
  Value<String?> gateId,
  Value<int> manifestVersion,
  Value<String> reentryPolicy,
  Value<int> syncCursor,
  Value<String?> staffId,
  Value<String> mode,
});
typedef $ConfigUpdateCompanionBuilder = ConfigCompanion Function({
  Value<int?> id,
  Value<String?> deviceId,
  Value<String?> deviceStatus,
  Value<String?> manifestPublicKey,
  Value<int> serverTimeOffsetMs,
  Value<int?> lastServerTimeAt,
  Value<int?> eventId,
  Value<String?> eventTitle,
  Value<int?> startsAt,
  Value<String?> eventStatus,
  Value<String?> gateId,
  Value<int> manifestVersion,
  Value<String> reentryPolicy,
  Value<int> syncCursor,
  Value<String?> staffId,
  Value<String> mode,
});

class $ConfigFilterComposer extends Composer<_$ScannerDatabase, Config> {
  $ConfigFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<int> get id => $composableBuilder(column: $table.id, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get deviceId =>
      $composableBuilder(column: $table.deviceId, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get deviceStatus =>
      $composableBuilder(column: $table.deviceStatus, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get manifestPublicKey =>
      $composableBuilder(column: $table.manifestPublicKey, builder: (column) => ColumnFilters(column));

  ColumnFilters<int> get serverTimeOffsetMs =>
      $composableBuilder(column: $table.serverTimeOffsetMs, builder: (column) => ColumnFilters(column));

  ColumnFilters<int> get lastServerTimeAt =>
      $composableBuilder(column: $table.lastServerTimeAt, builder: (column) => ColumnFilters(column));

  ColumnFilters<int> get eventId =>
      $composableBuilder(column: $table.eventId, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get eventTitle =>
      $composableBuilder(column: $table.eventTitle, builder: (column) => ColumnFilters(column));

  ColumnFilters<int> get startsAt =>
      $composableBuilder(column: $table.startsAt, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get eventStatus =>
      $composableBuilder(column: $table.eventStatus, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get gateId =>
      $composableBuilder(column: $table.gateId, builder: (column) => ColumnFilters(column));

  ColumnFilters<int> get manifestVersion =>
      $composableBuilder(column: $table.manifestVersion, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get reentryPolicy =>
      $composableBuilder(column: $table.reentryPolicy, builder: (column) => ColumnFilters(column));

  ColumnFilters<int> get syncCursor =>
      $composableBuilder(column: $table.syncCursor, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get staffId =>
      $composableBuilder(column: $table.staffId, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get mode => $composableBuilder(column: $table.mode, builder: (column) => ColumnFilters(column));
}

class $ConfigOrderingComposer extends Composer<_$ScannerDatabase, Config> {
  $ConfigOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<int> get id => $composableBuilder(column: $table.id, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get deviceId =>
      $composableBuilder(column: $table.deviceId, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get deviceStatus =>
      $composableBuilder(column: $table.deviceStatus, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get manifestPublicKey =>
      $composableBuilder(column: $table.manifestPublicKey, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<int> get serverTimeOffsetMs =>
      $composableBuilder(column: $table.serverTimeOffsetMs, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<int> get lastServerTimeAt =>
      $composableBuilder(column: $table.lastServerTimeAt, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<int> get eventId =>
      $composableBuilder(column: $table.eventId, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get eventTitle =>
      $composableBuilder(column: $table.eventTitle, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<int> get startsAt =>
      $composableBuilder(column: $table.startsAt, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get eventStatus =>
      $composableBuilder(column: $table.eventStatus, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get gateId =>
      $composableBuilder(column: $table.gateId, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<int> get manifestVersion =>
      $composableBuilder(column: $table.manifestVersion, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get reentryPolicy =>
      $composableBuilder(column: $table.reentryPolicy, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<int> get syncCursor =>
      $composableBuilder(column: $table.syncCursor, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get staffId =>
      $composableBuilder(column: $table.staffId, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get mode =>
      $composableBuilder(column: $table.mode, builder: (column) => ColumnOrderings(column));
}

class $ConfigAnnotationComposer extends Composer<_$ScannerDatabase, Config> {
  $ConfigAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<int> get id => $composableBuilder(column: $table.id, builder: (column) => column);

  GeneratedColumn<String> get deviceId => $composableBuilder(column: $table.deviceId, builder: (column) => column);

  GeneratedColumn<String> get deviceStatus =>
      $composableBuilder(column: $table.deviceStatus, builder: (column) => column);

  GeneratedColumn<String> get manifestPublicKey =>
      $composableBuilder(column: $table.manifestPublicKey, builder: (column) => column);

  GeneratedColumn<int> get serverTimeOffsetMs =>
      $composableBuilder(column: $table.serverTimeOffsetMs, builder: (column) => column);

  GeneratedColumn<int> get lastServerTimeAt =>
      $composableBuilder(column: $table.lastServerTimeAt, builder: (column) => column);

  GeneratedColumn<int> get eventId => $composableBuilder(column: $table.eventId, builder: (column) => column);

  GeneratedColumn<String> get eventTitle => $composableBuilder(column: $table.eventTitle, builder: (column) => column);

  GeneratedColumn<int> get startsAt => $composableBuilder(column: $table.startsAt, builder: (column) => column);

  GeneratedColumn<String> get eventStatus =>
      $composableBuilder(column: $table.eventStatus, builder: (column) => column);

  GeneratedColumn<String> get gateId => $composableBuilder(column: $table.gateId, builder: (column) => column);

  GeneratedColumn<int> get manifestVersion =>
      $composableBuilder(column: $table.manifestVersion, builder: (column) => column);

  GeneratedColumn<String> get reentryPolicy =>
      $composableBuilder(column: $table.reentryPolicy, builder: (column) => column);

  GeneratedColumn<int> get syncCursor => $composableBuilder(column: $table.syncCursor, builder: (column) => column);

  GeneratedColumn<String> get staffId => $composableBuilder(column: $table.staffId, builder: (column) => column);

  GeneratedColumn<String> get mode => $composableBuilder(column: $table.mode, builder: (column) => column);
}

class $ConfigTableManager
    extends
        RootTableManager<
          _$ScannerDatabase,
          Config,
          ConfigData,
          $ConfigFilterComposer,
          $ConfigOrderingComposer,
          $ConfigAnnotationComposer,
          $ConfigCreateCompanionBuilder,
          $ConfigUpdateCompanionBuilder,
          (ConfigData, BaseReferences<_$ScannerDatabase, Config, ConfigData>),
          ConfigData,
          PrefetchHooks Function()
        > {
  $ConfigTableManager(_$ScannerDatabase db, Config table)
    : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () => $ConfigFilterComposer($db: db, $table: table),
          createOrderingComposer: () => $ConfigOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () => $ConfigAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<int?> id = const Value.absent(),
                Value<String?> deviceId = const Value.absent(),
                Value<String?> deviceStatus = const Value.absent(),
                Value<String?> manifestPublicKey = const Value.absent(),
                Value<int> serverTimeOffsetMs = const Value.absent(),
                Value<int?> lastServerTimeAt = const Value.absent(),
                Value<int?> eventId = const Value.absent(),
                Value<String?> eventTitle = const Value.absent(),
                Value<int?> startsAt = const Value.absent(),
                Value<String?> eventStatus = const Value.absent(),
                Value<String?> gateId = const Value.absent(),
                Value<int> manifestVersion = const Value.absent(),
                Value<String> reentryPolicy = const Value.absent(),
                Value<int> syncCursor = const Value.absent(),
                Value<String?> staffId = const Value.absent(),
                Value<String> mode = const Value.absent(),
              }) => ConfigCompanion(
                id: id,
                deviceId: deviceId,
                deviceStatus: deviceStatus,
                manifestPublicKey: manifestPublicKey,
                serverTimeOffsetMs: serverTimeOffsetMs,
                lastServerTimeAt: lastServerTimeAt,
                eventId: eventId,
                eventTitle: eventTitle,
                startsAt: startsAt,
                eventStatus: eventStatus,
                gateId: gateId,
                manifestVersion: manifestVersion,
                reentryPolicy: reentryPolicy,
                syncCursor: syncCursor,
                staffId: staffId,
                mode: mode,
              ),
          createCompanionCallback:
              ({
                Value<int?> id = const Value.absent(),
                Value<String?> deviceId = const Value.absent(),
                Value<String?> deviceStatus = const Value.absent(),
                Value<String?> manifestPublicKey = const Value.absent(),
                Value<int> serverTimeOffsetMs = const Value.absent(),
                Value<int?> lastServerTimeAt = const Value.absent(),
                Value<int?> eventId = const Value.absent(),
                Value<String?> eventTitle = const Value.absent(),
                Value<int?> startsAt = const Value.absent(),
                Value<String?> eventStatus = const Value.absent(),
                Value<String?> gateId = const Value.absent(),
                Value<int> manifestVersion = const Value.absent(),
                Value<String> reentryPolicy = const Value.absent(),
                Value<int> syncCursor = const Value.absent(),
                Value<String?> staffId = const Value.absent(),
                Value<String> mode = const Value.absent(),
              }) => ConfigCompanion.insert(
                id: id,
                deviceId: deviceId,
                deviceStatus: deviceStatus,
                manifestPublicKey: manifestPublicKey,
                serverTimeOffsetMs: serverTimeOffsetMs,
                lastServerTimeAt: lastServerTimeAt,
                eventId: eventId,
                eventTitle: eventTitle,
                startsAt: startsAt,
                eventStatus: eventStatus,
                gateId: gateId,
                manifestVersion: manifestVersion,
                reentryPolicy: reentryPolicy,
                syncCursor: syncCursor,
                staffId: staffId,
                mode: mode,
              ),
          withReferenceMapper: (p0) => p0
              .map(
                (e) => (
                  e.readTable<Config, ConfigData>(table),
                  BaseReferences<_$ScannerDatabase, Config, ConfigData>(db, table, e),
                ),
              )
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $ConfigProcessedTableManager =
    ProcessedTableManager<
      _$ScannerDatabase,
      Config,
      ConfigData,
      $ConfigFilterComposer,
      $ConfigOrderingComposer,
      $ConfigAnnotationComposer,
      $ConfigCreateCompanionBuilder,
      $ConfigUpdateCompanionBuilder,
      (ConfigData, BaseReferences<_$ScannerDatabase, Config, ConfigData>),
      ConfigData,
      PrefetchHooks Function()
    >;
typedef $ZonesCreateCompanionBuilder = ZonesCompanion Function({
  Value<int?> zoneId,
  required String code,
  required String name,
});
typedef $ZonesUpdateCompanionBuilder = ZonesCompanion Function({
  Value<int?> zoneId,
  Value<String> code,
  Value<String> name,
});

class $ZonesFilterComposer extends Composer<_$ScannerDatabase, Zones> {
  $ZonesFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<int> get zoneId =>
      $composableBuilder(column: $table.zoneId, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get code => $composableBuilder(column: $table.code, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get name => $composableBuilder(column: $table.name, builder: (column) => ColumnFilters(column));
}

class $ZonesOrderingComposer extends Composer<_$ScannerDatabase, Zones> {
  $ZonesOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<int> get zoneId =>
      $composableBuilder(column: $table.zoneId, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get code =>
      $composableBuilder(column: $table.code, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get name =>
      $composableBuilder(column: $table.name, builder: (column) => ColumnOrderings(column));
}

class $ZonesAnnotationComposer extends Composer<_$ScannerDatabase, Zones> {
  $ZonesAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<int> get zoneId => $composableBuilder(column: $table.zoneId, builder: (column) => column);

  GeneratedColumn<String> get code => $composableBuilder(column: $table.code, builder: (column) => column);

  GeneratedColumn<String> get name => $composableBuilder(column: $table.name, builder: (column) => column);
}

class $ZonesTableManager
    extends
        RootTableManager<
          _$ScannerDatabase,
          Zones,
          Zone,
          $ZonesFilterComposer,
          $ZonesOrderingComposer,
          $ZonesAnnotationComposer,
          $ZonesCreateCompanionBuilder,
          $ZonesUpdateCompanionBuilder,
          (Zone, BaseReferences<_$ScannerDatabase, Zones, Zone>),
          Zone,
          PrefetchHooks Function()
        > {
  $ZonesTableManager(_$ScannerDatabase db, Zones table)
    : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () => $ZonesFilterComposer($db: db, $table: table),
          createOrderingComposer: () => $ZonesOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () => $ZonesAnnotationComposer($db: db, $table: table),
          updateCompanionCallback: ({
            Value<int?> zoneId = const Value.absent(),
            Value<String> code = const Value.absent(),
            Value<String> name = const Value.absent(),
          }) => ZonesCompanion(zoneId: zoneId, code: code, name: name),
          createCompanionCallback: ({
            Value<int?> zoneId = const Value.absent(),
            required String code,
            required String name,
          }) => ZonesCompanion.insert(zoneId: zoneId, code: code, name: name),
          withReferenceMapper: (p0) => p0
              .map(
                (e) => (e.readTable<Zones, Zone>(table), BaseReferences<_$ScannerDatabase, Zones, Zone>(db, table, e)),
              )
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $ZonesProcessedTableManager =
    ProcessedTableManager<
      _$ScannerDatabase,
      Zones,
      Zone,
      $ZonesFilterComposer,
      $ZonesOrderingComposer,
      $ZonesAnnotationComposer,
      $ZonesCreateCompanionBuilder,
      $ZonesUpdateCompanionBuilder,
      (Zone, BaseReferences<_$ScannerDatabase, Zones, Zone>),
      Zone,
      PrefetchHooks Function()
    >;
typedef $GatesCreateCompanionBuilder = GatesCompanion Function({
  Value<String?> gateId,
  required String code,
  required String name,
  required String zoneIds,
  Value<int> rowid,
});
typedef $GatesUpdateCompanionBuilder = GatesCompanion Function({
  Value<String?> gateId,
  Value<String> code,
  Value<String> name,
  Value<String> zoneIds,
  Value<int> rowid,
});

class $GatesFilterComposer extends Composer<_$ScannerDatabase, Gates> {
  $GatesFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get gateId =>
      $composableBuilder(column: $table.gateId, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get code => $composableBuilder(column: $table.code, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get name => $composableBuilder(column: $table.name, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get zoneIds =>
      $composableBuilder(column: $table.zoneIds, builder: (column) => ColumnFilters(column));
}

class $GatesOrderingComposer extends Composer<_$ScannerDatabase, Gates> {
  $GatesOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get gateId =>
      $composableBuilder(column: $table.gateId, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get code =>
      $composableBuilder(column: $table.code, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get name =>
      $composableBuilder(column: $table.name, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get zoneIds =>
      $composableBuilder(column: $table.zoneIds, builder: (column) => ColumnOrderings(column));
}

class $GatesAnnotationComposer extends Composer<_$ScannerDatabase, Gates> {
  $GatesAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get gateId => $composableBuilder(column: $table.gateId, builder: (column) => column);

  GeneratedColumn<String> get code => $composableBuilder(column: $table.code, builder: (column) => column);

  GeneratedColumn<String> get name => $composableBuilder(column: $table.name, builder: (column) => column);

  GeneratedColumn<String> get zoneIds => $composableBuilder(column: $table.zoneIds, builder: (column) => column);
}

class $GatesTableManager
    extends
        RootTableManager<
          _$ScannerDatabase,
          Gates,
          Gate,
          $GatesFilterComposer,
          $GatesOrderingComposer,
          $GatesAnnotationComposer,
          $GatesCreateCompanionBuilder,
          $GatesUpdateCompanionBuilder,
          (Gate, BaseReferences<_$ScannerDatabase, Gates, Gate>),
          Gate,
          PrefetchHooks Function()
        > {
  $GatesTableManager(_$ScannerDatabase db, Gates table)
    : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () => $GatesFilterComposer($db: db, $table: table),
          createOrderingComposer: () => $GatesOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () => $GatesAnnotationComposer($db: db, $table: table),
          updateCompanionCallback: ({
            Value<String?> gateId = const Value.absent(),
            Value<String> code = const Value.absent(),
            Value<String> name = const Value.absent(),
            Value<String> zoneIds = const Value.absent(),
            Value<int> rowid = const Value.absent(),
          }) => GatesCompanion(gateId: gateId, code: code, name: name, zoneIds: zoneIds, rowid: rowid),
          createCompanionCallback: ({
            Value<String?> gateId = const Value.absent(),
            required String code,
            required String name,
            required String zoneIds,
            Value<int> rowid = const Value.absent(),
          }) => GatesCompanion.insert(gateId: gateId, code: code, name: name, zoneIds: zoneIds, rowid: rowid),
          withReferenceMapper: (p0) => p0
              .map(
                (e) => (e.readTable<Gates, Gate>(table), BaseReferences<_$ScannerDatabase, Gates, Gate>(db, table, e)),
              )
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $GatesProcessedTableManager =
    ProcessedTableManager<
      _$ScannerDatabase,
      Gates,
      Gate,
      $GatesFilterComposer,
      $GatesOrderingComposer,
      $GatesAnnotationComposer,
      $GatesCreateCompanionBuilder,
      $GatesUpdateCompanionBuilder,
      (Gate, BaseReferences<_$ScannerDatabase, Gates, Gate>),
      Gate,
      PrefetchHooks Function()
    >;
typedef $StaffCreateCompanionBuilder = StaffCompanion Function({
  Value<String?> staffId,
  required String label,
  required String role,
  required String pinVerifier,
  Value<int> rowid,
});
typedef $StaffUpdateCompanionBuilder = StaffCompanion Function({
  Value<String?> staffId,
  Value<String> label,
  Value<String> role,
  Value<String> pinVerifier,
  Value<int> rowid,
});

class $StaffFilterComposer extends Composer<_$ScannerDatabase, Staff> {
  $StaffFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get staffId =>
      $composableBuilder(column: $table.staffId, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get label =>
      $composableBuilder(column: $table.label, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get role => $composableBuilder(column: $table.role, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get pinVerifier =>
      $composableBuilder(column: $table.pinVerifier, builder: (column) => ColumnFilters(column));
}

class $StaffOrderingComposer extends Composer<_$ScannerDatabase, Staff> {
  $StaffOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get staffId =>
      $composableBuilder(column: $table.staffId, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get label =>
      $composableBuilder(column: $table.label, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get role =>
      $composableBuilder(column: $table.role, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get pinVerifier =>
      $composableBuilder(column: $table.pinVerifier, builder: (column) => ColumnOrderings(column));
}

class $StaffAnnotationComposer extends Composer<_$ScannerDatabase, Staff> {
  $StaffAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get staffId => $composableBuilder(column: $table.staffId, builder: (column) => column);

  GeneratedColumn<String> get label => $composableBuilder(column: $table.label, builder: (column) => column);

  GeneratedColumn<String> get role => $composableBuilder(column: $table.role, builder: (column) => column);

  GeneratedColumn<String> get pinVerifier =>
      $composableBuilder(column: $table.pinVerifier, builder: (column) => column);
}

class $StaffTableManager
    extends
        RootTableManager<
          _$ScannerDatabase,
          Staff,
          StaffData,
          $StaffFilterComposer,
          $StaffOrderingComposer,
          $StaffAnnotationComposer,
          $StaffCreateCompanionBuilder,
          $StaffUpdateCompanionBuilder,
          (StaffData, BaseReferences<_$ScannerDatabase, Staff, StaffData>),
          StaffData,
          PrefetchHooks Function()
        > {
  $StaffTableManager(_$ScannerDatabase db, Staff table)
    : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () => $StaffFilterComposer($db: db, $table: table),
          createOrderingComposer: () => $StaffOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () => $StaffAnnotationComposer($db: db, $table: table),
          updateCompanionCallback: ({
            Value<String?> staffId = const Value.absent(),
            Value<String> label = const Value.absent(),
            Value<String> role = const Value.absent(),
            Value<String> pinVerifier = const Value.absent(),
            Value<int> rowid = const Value.absent(),
          }) => StaffCompanion(staffId: staffId, label: label, role: role, pinVerifier: pinVerifier, rowid: rowid),
          createCompanionCallback:
              ({
                Value<String?> staffId = const Value.absent(),
                required String label,
                required String role,
                required String pinVerifier,
                Value<int> rowid = const Value.absent(),
              }) => StaffCompanion.insert(
                staffId: staffId,
                label: label,
                role: role,
                pinVerifier: pinVerifier,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map(
                (e) => (
                  e.readTable<Staff, StaffData>(table),
                  BaseReferences<_$ScannerDatabase, Staff, StaffData>(db, table, e),
                ),
              )
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $StaffProcessedTableManager =
    ProcessedTableManager<
      _$ScannerDatabase,
      Staff,
      StaffData,
      $StaffFilterComposer,
      $StaffOrderingComposer,
      $StaffAnnotationComposer,
      $StaffCreateCompanionBuilder,
      $StaffUpdateCompanionBuilder,
      (StaffData, BaseReferences<_$ScannerDatabase, Staff, StaffData>),
      StaffData,
      PrefetchHooks Function()
    >;
typedef $ManifestTicketsCreateCompanionBuilder = ManifestTicketsCompanion Function({
  Value<String?> ticketId,
  required String ticketNo,
  required int zoneId,
  required String status,
  Value<String?> holderInitials,
  Value<int> rowid,
});
typedef $ManifestTicketsUpdateCompanionBuilder = ManifestTicketsCompanion Function({
  Value<String?> ticketId,
  Value<String> ticketNo,
  Value<int> zoneId,
  Value<String> status,
  Value<String?> holderInitials,
  Value<int> rowid,
});

class $ManifestTicketsFilterComposer extends Composer<_$ScannerDatabase, ManifestTickets> {
  $ManifestTicketsFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get ticketId =>
      $composableBuilder(column: $table.ticketId, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get ticketNo =>
      $composableBuilder(column: $table.ticketNo, builder: (column) => ColumnFilters(column));

  ColumnFilters<int> get zoneId =>
      $composableBuilder(column: $table.zoneId, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get status =>
      $composableBuilder(column: $table.status, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get holderInitials =>
      $composableBuilder(column: $table.holderInitials, builder: (column) => ColumnFilters(column));
}

class $ManifestTicketsOrderingComposer extends Composer<_$ScannerDatabase, ManifestTickets> {
  $ManifestTicketsOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get ticketId =>
      $composableBuilder(column: $table.ticketId, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get ticketNo =>
      $composableBuilder(column: $table.ticketNo, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<int> get zoneId =>
      $composableBuilder(column: $table.zoneId, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get status =>
      $composableBuilder(column: $table.status, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get holderInitials =>
      $composableBuilder(column: $table.holderInitials, builder: (column) => ColumnOrderings(column));
}

class $ManifestTicketsAnnotationComposer extends Composer<_$ScannerDatabase, ManifestTickets> {
  $ManifestTicketsAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get ticketId => $composableBuilder(column: $table.ticketId, builder: (column) => column);

  GeneratedColumn<String> get ticketNo => $composableBuilder(column: $table.ticketNo, builder: (column) => column);

  GeneratedColumn<int> get zoneId => $composableBuilder(column: $table.zoneId, builder: (column) => column);

  GeneratedColumn<String> get status => $composableBuilder(column: $table.status, builder: (column) => column);

  GeneratedColumn<String> get holderInitials =>
      $composableBuilder(column: $table.holderInitials, builder: (column) => column);
}

class $ManifestTicketsTableManager
    extends
        RootTableManager<
          _$ScannerDatabase,
          ManifestTickets,
          ManifestTicket,
          $ManifestTicketsFilterComposer,
          $ManifestTicketsOrderingComposer,
          $ManifestTicketsAnnotationComposer,
          $ManifestTicketsCreateCompanionBuilder,
          $ManifestTicketsUpdateCompanionBuilder,
          (ManifestTicket, BaseReferences<_$ScannerDatabase, ManifestTickets, ManifestTicket>),
          ManifestTicket,
          PrefetchHooks Function()
        > {
  $ManifestTicketsTableManager(_$ScannerDatabase db, ManifestTickets table)
    : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () => $ManifestTicketsFilterComposer($db: db, $table: table),
          createOrderingComposer: () => $ManifestTicketsOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () => $ManifestTicketsAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<String?> ticketId = const Value.absent(),
                Value<String> ticketNo = const Value.absent(),
                Value<int> zoneId = const Value.absent(),
                Value<String> status = const Value.absent(),
                Value<String?> holderInitials = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => ManifestTicketsCompanion(
                ticketId: ticketId,
                ticketNo: ticketNo,
                zoneId: zoneId,
                status: status,
                holderInitials: holderInitials,
                rowid: rowid,
              ),
          createCompanionCallback:
              ({
                Value<String?> ticketId = const Value.absent(),
                required String ticketNo,
                required int zoneId,
                required String status,
                Value<String?> holderInitials = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => ManifestTicketsCompanion.insert(
                ticketId: ticketId,
                ticketNo: ticketNo,
                zoneId: zoneId,
                status: status,
                holderInitials: holderInitials,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map(
                (e) => (
                  e.readTable<ManifestTickets, ManifestTicket>(table),
                  BaseReferences<_$ScannerDatabase, ManifestTickets, ManifestTicket>(db, table, e),
                ),
              )
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $ManifestTicketsProcessedTableManager =
    ProcessedTableManager<
      _$ScannerDatabase,
      ManifestTickets,
      ManifestTicket,
      $ManifestTicketsFilterComposer,
      $ManifestTicketsOrderingComposer,
      $ManifestTicketsAnnotationComposer,
      $ManifestTicketsCreateCompanionBuilder,
      $ManifestTicketsUpdateCompanionBuilder,
      (ManifestTicket, BaseReferences<_$ScannerDatabase, ManifestTickets, ManifestTicket>),
      ManifestTicket,
      PrefetchHooks Function()
    >;
typedef $UsedCreateCompanionBuilder = UsedCompanion Function({
  required String ticketId,
  required String direction,
  required int firstAt,
  Value<String?> gateCode,
  required String source,
  Value<int> rowid,
});
typedef $UsedUpdateCompanionBuilder = UsedCompanion Function({
  Value<String> ticketId,
  Value<String> direction,
  Value<int> firstAt,
  Value<String?> gateCode,
  Value<String> source,
  Value<int> rowid,
});

class $UsedFilterComposer extends Composer<_$ScannerDatabase, Used> {
  $UsedFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get ticketId =>
      $composableBuilder(column: $table.ticketId, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get direction =>
      $composableBuilder(column: $table.direction, builder: (column) => ColumnFilters(column));

  ColumnFilters<int> get firstAt =>
      $composableBuilder(column: $table.firstAt, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get gateCode =>
      $composableBuilder(column: $table.gateCode, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get source =>
      $composableBuilder(column: $table.source, builder: (column) => ColumnFilters(column));
}

class $UsedOrderingComposer extends Composer<_$ScannerDatabase, Used> {
  $UsedOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get ticketId =>
      $composableBuilder(column: $table.ticketId, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get direction =>
      $composableBuilder(column: $table.direction, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<int> get firstAt =>
      $composableBuilder(column: $table.firstAt, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get gateCode =>
      $composableBuilder(column: $table.gateCode, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get source =>
      $composableBuilder(column: $table.source, builder: (column) => ColumnOrderings(column));
}

class $UsedAnnotationComposer extends Composer<_$ScannerDatabase, Used> {
  $UsedAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get ticketId => $composableBuilder(column: $table.ticketId, builder: (column) => column);

  GeneratedColumn<String> get direction => $composableBuilder(column: $table.direction, builder: (column) => column);

  GeneratedColumn<int> get firstAt => $composableBuilder(column: $table.firstAt, builder: (column) => column);

  GeneratedColumn<String> get gateCode => $composableBuilder(column: $table.gateCode, builder: (column) => column);

  GeneratedColumn<String> get source => $composableBuilder(column: $table.source, builder: (column) => column);
}

class $UsedTableManager
    extends
        RootTableManager<
          _$ScannerDatabase,
          Used,
          UsedData,
          $UsedFilterComposer,
          $UsedOrderingComposer,
          $UsedAnnotationComposer,
          $UsedCreateCompanionBuilder,
          $UsedUpdateCompanionBuilder,
          (UsedData, BaseReferences<_$ScannerDatabase, Used, UsedData>),
          UsedData,
          PrefetchHooks Function()
        > {
  $UsedTableManager(_$ScannerDatabase db, Used table)
    : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () => $UsedFilterComposer($db: db, $table: table),
          createOrderingComposer: () => $UsedOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () => $UsedAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<String> ticketId = const Value.absent(),
                Value<String> direction = const Value.absent(),
                Value<int> firstAt = const Value.absent(),
                Value<String?> gateCode = const Value.absent(),
                Value<String> source = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => UsedCompanion(
                ticketId: ticketId,
                direction: direction,
                firstAt: firstAt,
                gateCode: gateCode,
                source: source,
                rowid: rowid,
              ),
          createCompanionCallback:
              ({
                required String ticketId,
                required String direction,
                required int firstAt,
                Value<String?> gateCode = const Value.absent(),
                required String source,
                Value<int> rowid = const Value.absent(),
              }) => UsedCompanion.insert(
                ticketId: ticketId,
                direction: direction,
                firstAt: firstAt,
                gateCode: gateCode,
                source: source,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map(
                (e) => (
                  e.readTable<Used, UsedData>(table),
                  BaseReferences<_$ScannerDatabase, Used, UsedData>(db, table, e),
                ),
              )
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $UsedProcessedTableManager =
    ProcessedTableManager<
      _$ScannerDatabase,
      Used,
      UsedData,
      $UsedFilterComposer,
      $UsedOrderingComposer,
      $UsedAnnotationComposer,
      $UsedCreateCompanionBuilder,
      $UsedUpdateCompanionBuilder,
      (UsedData, BaseReferences<_$ScannerDatabase, Used, UsedData>),
      UsedData,
      PrefetchHooks Function()
    >;
typedef $ScansCreateCompanionBuilder = ScansCompanion Function({
  Value<String?> scanId,
  Value<String?> ticketId,
  required String gateId,
  required String staffId,
  required String direction,
  required String result,
  Value<int?> tokenVersion,
  required int deviceTime,
  Value<int> synced,
  Value<String?> overrideReason,
  Value<String?> supervisorId,
  Value<int> rowid,
});
typedef $ScansUpdateCompanionBuilder = ScansCompanion Function({
  Value<String?> scanId,
  Value<String?> ticketId,
  Value<String> gateId,
  Value<String> staffId,
  Value<String> direction,
  Value<String> result,
  Value<int?> tokenVersion,
  Value<int> deviceTime,
  Value<int> synced,
  Value<String?> overrideReason,
  Value<String?> supervisorId,
  Value<int> rowid,
});

class $ScansFilterComposer extends Composer<_$ScannerDatabase, Scans> {
  $ScansFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get scanId =>
      $composableBuilder(column: $table.scanId, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get ticketId =>
      $composableBuilder(column: $table.ticketId, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get gateId =>
      $composableBuilder(column: $table.gateId, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get staffId =>
      $composableBuilder(column: $table.staffId, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get direction =>
      $composableBuilder(column: $table.direction, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get result =>
      $composableBuilder(column: $table.result, builder: (column) => ColumnFilters(column));

  ColumnFilters<int> get tokenVersion =>
      $composableBuilder(column: $table.tokenVersion, builder: (column) => ColumnFilters(column));

  ColumnFilters<int> get deviceTime =>
      $composableBuilder(column: $table.deviceTime, builder: (column) => ColumnFilters(column));

  ColumnFilters<int> get synced =>
      $composableBuilder(column: $table.synced, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get overrideReason =>
      $composableBuilder(column: $table.overrideReason, builder: (column) => ColumnFilters(column));

  ColumnFilters<String> get supervisorId =>
      $composableBuilder(column: $table.supervisorId, builder: (column) => ColumnFilters(column));
}

class $ScansOrderingComposer extends Composer<_$ScannerDatabase, Scans> {
  $ScansOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get scanId =>
      $composableBuilder(column: $table.scanId, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get ticketId =>
      $composableBuilder(column: $table.ticketId, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get gateId =>
      $composableBuilder(column: $table.gateId, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get staffId =>
      $composableBuilder(column: $table.staffId, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get direction =>
      $composableBuilder(column: $table.direction, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get result =>
      $composableBuilder(column: $table.result, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<int> get tokenVersion =>
      $composableBuilder(column: $table.tokenVersion, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<int> get deviceTime =>
      $composableBuilder(column: $table.deviceTime, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<int> get synced =>
      $composableBuilder(column: $table.synced, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get overrideReason =>
      $composableBuilder(column: $table.overrideReason, builder: (column) => ColumnOrderings(column));

  ColumnOrderings<String> get supervisorId =>
      $composableBuilder(column: $table.supervisorId, builder: (column) => ColumnOrderings(column));
}

class $ScansAnnotationComposer extends Composer<_$ScannerDatabase, Scans> {
  $ScansAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get scanId => $composableBuilder(column: $table.scanId, builder: (column) => column);

  GeneratedColumn<String> get ticketId => $composableBuilder(column: $table.ticketId, builder: (column) => column);

  GeneratedColumn<String> get gateId => $composableBuilder(column: $table.gateId, builder: (column) => column);

  GeneratedColumn<String> get staffId => $composableBuilder(column: $table.staffId, builder: (column) => column);

  GeneratedColumn<String> get direction => $composableBuilder(column: $table.direction, builder: (column) => column);

  GeneratedColumn<String> get result => $composableBuilder(column: $table.result, builder: (column) => column);

  GeneratedColumn<int> get tokenVersion => $composableBuilder(column: $table.tokenVersion, builder: (column) => column);

  GeneratedColumn<int> get deviceTime => $composableBuilder(column: $table.deviceTime, builder: (column) => column);

  GeneratedColumn<int> get synced => $composableBuilder(column: $table.synced, builder: (column) => column);

  GeneratedColumn<String> get overrideReason =>
      $composableBuilder(column: $table.overrideReason, builder: (column) => column);

  GeneratedColumn<String> get supervisorId =>
      $composableBuilder(column: $table.supervisorId, builder: (column) => column);
}

class $ScansTableManager
    extends
        RootTableManager<
          _$ScannerDatabase,
          Scans,
          Scan,
          $ScansFilterComposer,
          $ScansOrderingComposer,
          $ScansAnnotationComposer,
          $ScansCreateCompanionBuilder,
          $ScansUpdateCompanionBuilder,
          (Scan, BaseReferences<_$ScannerDatabase, Scans, Scan>),
          Scan,
          PrefetchHooks Function()
        > {
  $ScansTableManager(_$ScannerDatabase db, Scans table)
    : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () => $ScansFilterComposer($db: db, $table: table),
          createOrderingComposer: () => $ScansOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () => $ScansAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<String?> scanId = const Value.absent(),
                Value<String?> ticketId = const Value.absent(),
                Value<String> gateId = const Value.absent(),
                Value<String> staffId = const Value.absent(),
                Value<String> direction = const Value.absent(),
                Value<String> result = const Value.absent(),
                Value<int?> tokenVersion = const Value.absent(),
                Value<int> deviceTime = const Value.absent(),
                Value<int> synced = const Value.absent(),
                Value<String?> overrideReason = const Value.absent(),
                Value<String?> supervisorId = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => ScansCompanion(
                scanId: scanId,
                ticketId: ticketId,
                gateId: gateId,
                staffId: staffId,
                direction: direction,
                result: result,
                tokenVersion: tokenVersion,
                deviceTime: deviceTime,
                synced: synced,
                overrideReason: overrideReason,
                supervisorId: supervisorId,
                rowid: rowid,
              ),
          createCompanionCallback:
              ({
                Value<String?> scanId = const Value.absent(),
                Value<String?> ticketId = const Value.absent(),
                required String gateId,
                required String staffId,
                required String direction,
                required String result,
                Value<int?> tokenVersion = const Value.absent(),
                required int deviceTime,
                Value<int> synced = const Value.absent(),
                Value<String?> overrideReason = const Value.absent(),
                Value<String?> supervisorId = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => ScansCompanion.insert(
                scanId: scanId,
                ticketId: ticketId,
                gateId: gateId,
                staffId: staffId,
                direction: direction,
                result: result,
                tokenVersion: tokenVersion,
                deviceTime: deviceTime,
                synced: synced,
                overrideReason: overrideReason,
                supervisorId: supervisorId,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map(
                (e) => (e.readTable<Scans, Scan>(table), BaseReferences<_$ScannerDatabase, Scans, Scan>(db, table, e)),
              )
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $ScansProcessedTableManager =
    ProcessedTableManager<
      _$ScannerDatabase,
      Scans,
      Scan,
      $ScansFilterComposer,
      $ScansOrderingComposer,
      $ScansAnnotationComposer,
      $ScansCreateCompanionBuilder,
      $ScansUpdateCompanionBuilder,
      (Scan, BaseReferences<_$ScannerDatabase, Scans, Scan>),
      Scan,
      PrefetchHooks Function()
    >;

class $ScannerDatabaseManager {
  final _$ScannerDatabase _db;
  $ScannerDatabaseManager(this._db);
  $ConfigTableManager get config => $ConfigTableManager(_db, _db.config);
  $ZonesTableManager get zones => $ZonesTableManager(_db, _db.zones);
  $GatesTableManager get gates => $GatesTableManager(_db, _db.gates);
  $StaffTableManager get staff => $StaffTableManager(_db, _db.staff);
  $ManifestTicketsTableManager get manifestTickets => $ManifestTicketsTableManager(_db, _db.manifestTickets);
  $UsedTableManager get used => $UsedTableManager(_db, _db.used);
  $ScansTableManager get scans => $ScansTableManager(_db, _db.scans);
}

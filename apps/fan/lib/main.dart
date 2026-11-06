import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/app.dart';
import 'package:jomhor_fan/app/config.dart';

void main() {
  AppConfig.checkRelease();
  runApp(const ProviderScope(child: JomhorApp()));
}

// Generates lib/theme/tokens.g.dart from design/tokens.json with the fan app's generator (one generator, no
// copy; its paths are relative to the working directory). Run from apps/scanner:
//   dart run tool/gen_tokens.dart
// test/theme_test.dart fails when the committed file is stale.
import '../../fan/tool/gen_tokens.dart' as fan;

void main() => fan.main();

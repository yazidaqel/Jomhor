import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/config.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

/// The app's URL scheme (Android intent filter, iOS CFBundleURLTypes). The API's `/return` sends APP
/// orders to `jomhor-fan://orders/{order_id}` (docs/04-api.md §Payments).
const appScheme = 'jomhor-fan';

/// An order id as the API writes it: a lowercase UUID. Anything else from a deep link is refused.
final _orderId = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

bool isOrderId(String id) => _orderId.hasMatch(id);

/// The order id of a `jomhor-fan://orders/{id}` return URL, else null. Read from the raw path (no
/// percent-decoding), so `..%2F` or `%252F` can never become a path of their own.
String? returnedOrderId(String url) {
  final uri = Uri.tryParse(url);
  if (uri == null || uri.scheme != appScheme || uri.host != 'orders' || uri.hasQuery || uri.hasFragment) return null;
  final id = uri.path.startsWith('/') ? uri.path.substring(1) : '';
  return isOrderId(id) ? id : null;
}

/// Pages the card web view may load: https, and http only outside a release build (the dev backend).
bool webSchemeAllowed(String? scheme, {bool release = kReleaseMode}) =>
    scheme == 'https' || (!release && scheme == 'http');

/// Only our own API starts a payment: `redirect_url` is `{API}/payments/card/{id}/redirect`.
bool isPaymentPage(String url, {String apiBaseUrl = AppConfig.apiBaseUrl, bool release = kReleaseMode}) {
  final uri = Uri.tryParse(url);
  if (uri == null || uri.host.isEmpty || !webSchemeAllowed(uri.scheme, release: release)) return false;
  return uri.origin == Uri.parse(apiBaseUrl).origin;
}

/// Opens the card page and completes with true once the gateway lands on our return URL for [orderId],
/// false if the fan closes it. Tests replace it ([cardPaymentLauncherProvider]): no web view in tests.
typedef CardPaymentLauncher = Future<bool> Function(BuildContext context, String redirectUrl, String orderId);

final cardPaymentLauncherProvider = Provider<CardPaymentLauncher>((ref) => _openInWebView);

Future<bool> _openInWebView(BuildContext context, String redirectUrl, String orderId) async {
  if (!isPaymentPage(redirectUrl)) return false; // Callers check first; never open another origin.
  final paid = await Navigator.of(context).push<bool>(
    MaterialPageRoute(
      fullscreenDialog: true,
      builder: (_) => _CardPaymentPage(url: redirectUrl, orderId: orderId),
    ),
  );
  return paid ?? false;
}

/// Payzone 3DS (or the simulated gateway) in a web view, Q27. The return URL never loads: it is caught.
class _CardPaymentPage extends StatefulWidget {
  const new({required this.url, required this.orderId});

  final String url;
  final String orderId;

  @override
  State<_CardPaymentPage> createState() => _CardPaymentPageState();
}

class _CardPaymentPageState extends State<_CardPaymentPage> {
  late final WebViewController _web;

  @override
  void initState() {
    super.initState();
    _web = WebViewController();
    unawaited(_web.setJavaScriptMode(JavaScriptMode.unrestricted)); // 3DS pages need it.
    unawaited(
      _web.setNavigationDelegate(
        NavigationDelegate(
          onNavigationRequest: (request) {
            final id = returnedOrderId(request.url);
            if (id != null) {
              if (mounted) Navigator.of(context).pop(id == widget.orderId);
              return NavigationDecision.prevent;
            }
            // Gateway and bank pages only: no intent:, tel:, file: or other schemes from inside the card flow.
            return webSchemeAllowed(Uri.tryParse(request.url)?.scheme)
                ? NavigationDecision.navigate
                : NavigationDecision.prevent;
          },
        ),
      ),
    );
    // Before the first load: no file:// access from the card flow.
    if (_web.platform case final AndroidWebViewController android) unawaited(android.setAllowFileAccess(false));
    unawaited(_web.loadRequest(Uri.parse(widget.url)));
  }

  @override
  void dispose() {
    // Nothing of the card flow (gateway cookies, cached 3DS pages, storage) is left for the next page.
    unawaited(WebViewCookieManager().clearCookies());
    unawaited(_web.clearCache());
    unawaited(_web.clearLocalStorage());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      leading: CloseButton(onPressed: () => Navigator.of(context).pop(false)),
      title: Text(AppLocalizations.of(context).cardPageTitle),
    ),
    body: WebViewWidget(controller: _web),
  );
}

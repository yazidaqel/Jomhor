package ma.jomhor.notifications.application;

/**
 * Sends a push notification to one device token of the Jomhor fan app (docs/04-api.md §Notifications push wire:
 * {@code notification: {title, body}} and {@code data: {path}} only). Adapters: log (dev/staging, refused under prod),
 * FCM with BE-36. No adapter configured → no bean, and PUSH messages fail NOT_CONFIGURED.
 */
public interface PushSender {

    /**
     * Throws {@link Unregistered} when the provider no longer knows the token, {@link Failed} (or anything else)
     * otherwise.
     */
    void send(Push push);

    /** {@code path}: the app route: /tickets/{uuid}, /orders/{uuid}, /events/{id} or / (04 push wire). */
    record Push(String token, String title, String body, String path) {
        /** Without the token. */
        @Override
        public String toString() {
            return "Push[" + path + "]";
        }
    }

    /** The token is dead (app uninstalled, token rotated): the caller deletes it. */
    class Unregistered extends RuntimeException {
        public Unregistered() {
            super("unregistered", null, false, false);
        }
    }

    /**
     * A provider refusal. {@code code} is the provider's error code only ("Failed" when it gives none, so a
     * refusal is never mistaken for a success): no token, no message body.
     */
    class Failed extends RuntimeException {
        private final String code;

        public Failed(String code) {
            super(code, null, false, false);
            this.code = code == null || code.isBlank() ? "Failed" : code;
        }

        public String code() {
            return code;
        }
    }
}

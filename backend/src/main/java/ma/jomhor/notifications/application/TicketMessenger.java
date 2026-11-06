package ma.jomhor.notifications.application;

/**
 * Sends a ticket PDF to a phone on WhatsApp. The real provider adapter comes with BE-15 (plan Q2); until then only
 * dev and staging have one (a log adapter), so prod refuses to start.
 */
public interface TicketMessenger {

    /** Throws {@link Failed} (or anything else) when the provider refuses or can't be reached. */
    void send(Message message);

    /** The ticket {@code ticketNo} as {@code pdf}, to {@code phoneE164}. Not for logs. */
    record Message(String phoneE164, String ticketNo, byte[] pdf) {
        @Override
        public String toString() {
            return "Message[" + ticketNo + "]";
        }
    }

    /** A provider refusal. {@code code} is the provider's error code only: no phone, no message body. */
    class Failed extends RuntimeException {
        private final String code;

        public Failed(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

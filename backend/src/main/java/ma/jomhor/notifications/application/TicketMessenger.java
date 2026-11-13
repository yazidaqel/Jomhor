package ma.jomhor.notifications.application;

import java.util.List;
import java.util.UUID;

/**
 * Sends a ticket PDF, or a template message (docs/04-api.md §Notifications), to a phone on WhatsApp. The real
 * provider adapter comes with BE-15 (plan Q2); until then only
 * dev and staging have one (a log adapter), so prod refuses to start.
 */
public interface TicketMessenger {

    /** Throws {@link Failed} (or anything else) when the provider refuses or can't be reached. */
    void send(Message message);

    /** The same, for a template message. Throws {@link Failed} (or anything else) likewise. */
    void send(Template template);

    /** The ticket {@code ticketNo} as {@code pdf}, to {@code phoneE164}. Not for logs. */
    record Message(String phoneE164, String ticketNo, byte[] pdf) {
        @Override
        public String toString() {
            return "Message[" + ticketNo + "]";
        }
    }

    /**
     * Template {@code name} in {@code locale} (fr, ar, en) with {@code params} in order, to {@code phoneE164}, the
     * phone of user {@code userId}. Not for logs: toString shows the name and the user id only.
     */
    record Template(UUID userId, String phoneE164, String locale, String name, List<String> params) {
        @Override
        public String toString() {
            return "Template[" + name + ", user " + userId + "]";
        }
    }

    /**
     * A provider refusal. {@code code} is the provider's error code only ("Failed" when it gives none, so a
     * refusal is never mistaken for a success): no phone, no message body.
     */
    class Failed extends RuntimeException {
        private final String code;

        public Failed(String code) {
            super(code);
            this.code = code == null || code.isBlank() ? "Failed" : code;
        }

        public String code() {
            return code;
        }
    }
}

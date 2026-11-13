package ma.jomhor.notifications.adapters.log;

import ma.jomhor.notifications.application.TicketMessenger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/staging messenger: logs the ticket number, the masked phone and the PDF size; never the PDF, a token or a
 * key. Exists only under dev or staging; anywhere else (prod, or no profile) there is no TicketMessenger until
 * BE-15, so the app refuses to start.
 */
@Component
@Profile({"dev", "staging"})
class LogTicketMessenger implements TicketMessenger {

    private static final Logger log = LoggerFactory.getLogger(LogTicketMessenger.class);

    @Override
    public void send(Message message) {
        log.info("[dev WhatsApp] ticket {} -> {} ({} bytes PDF)", message.ticketNo(), mask(message.phoneE164()),
                message.pdf().length);
    }

    /** The template name and the recipient's user id only: never the phone or the parameters (plan lot-4 Q63). */
    @Override
    public void send(Template template) {
        log.info("[dev WhatsApp] template {} -> user {}", template.name(), template.userId());
    }

    /** +212612345678 → +212*******78: the country code and the last two digits. */
    static String mask(String phone) {
        return phone.length() < 7 ? "***" : phone.substring(0, 4) + "*".repeat(phone.length() - 6)
                + phone.substring(phone.length() - 2);
    }
}

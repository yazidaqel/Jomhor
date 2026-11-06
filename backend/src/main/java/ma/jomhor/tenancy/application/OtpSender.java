package ma.jomhor.tenancy.application;

/** Delivers a one-time code (SMS / WhatsApp). The real provider is chosen before Lot 1 (Q2). */
public interface OtpSender {

    void send(String phoneE164, String code);
}

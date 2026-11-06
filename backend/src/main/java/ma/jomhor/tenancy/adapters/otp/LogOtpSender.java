package ma.jomhor.tenancy.adapters.otp;

import ma.jomhor.tenancy.application.OtpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/staging sender: writes the code to the log. The only place a code is ever logged.
 * Exists only under the dev or staging profile; anywhere else (prod, or no profile) there is no
 * OtpSender yet, so the app refuses to start.
 */
@Component
@Profile({"dev", "staging"})
class LogOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LogOtpSender.class);

    @Override
    public void send(String phoneE164, String code) {
        log.warn("[dev OTP] {} -> {}", phoneE164, code);
    }
}

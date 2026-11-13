package ma.jomhor.notifications.adapters.log;

import ma.jomhor.notifications.application.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Dev/staging push sender (notifications.push.provider=log): logs the app path and the token's last 4 characters,
 * never the title, the body or the whole token. Refuses to start under prod.
 */
@Component
@ConditionalOnProperty(name = "notifications.push.provider", havingValue = "log")
class LogPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LogPushSender.class);

    LogPushSender(Environment env) {
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("The log push sender is refused under the prod profile");
        }
    }

    @Override
    public void send(Push push) {
        String token = push.token();
        String last4 = token.length() < 4 ? "" : token.substring(token.length() - 4);
        log.info("[dev push] {} -> token ...{}", push.path(), last4);
    }
}

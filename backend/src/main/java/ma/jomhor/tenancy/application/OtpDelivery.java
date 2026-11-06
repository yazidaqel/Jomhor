package ma.jomhor.tenancy.application;

import ma.jomhor.tenancy.application.OtpService.OtpIssued;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends a code only once its challenge has committed (a rollback sends nothing), on the bounded
 * {@code otpSendExecutor}, never on the request thread: a slow provider cannot hold a request or a lock.
 */
@Component
class OtpDelivery {

    static final String EXECUTOR = "otpSendExecutor";

    private final OtpSender sender;

    OtpDelivery(OtpSender sender) {
        this.sender = sender;
    }

    @Async(EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void send(OtpIssued issued) {
        sender.send(issued.phone(), issued.code());
    }
}

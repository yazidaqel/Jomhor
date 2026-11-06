package ma.jomhor.tenancy;

import static ma.jomhor.tenancy.ClientIpTest.fanOtp;
import static ma.jomhor.tenancy.ClientIpTest.randomIp;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import ma.jomhor.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The shop-shaped peer (plan §7 "Client IP trust chain"): loopback is a trusted proxy here, it forwards one
 * X-Forwarded-For entry, and that entry is the client the per-IP throttle counts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {IntegrationTest.JWT_SECRET, IntegrationTest.OTP_SECRET, IntegrationTest.EVENT_KEY_MASTER,
                ClientIpTest.FAN_10M, ClientIpTest.FAN_24H, ClientIpTest.ORG_10M, ClientIpTest.ORG_24H,
                "TRUSTED_PROXIES_REGEX=127\\.0\\.0\\.1"})
class TrustedProxyIpTest extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Test
    void aTrustedPeerForwardingOneEntryIsBucketedByThatEntry() {
        String client = randomIp();
        List<String> phones = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            phones.add(randomPhone());
            assertThat(fanOtp(port, phones.getLast(), client)).isEqualTo(202);
        }
        assertThat(fanOtp(port, randomPhone(), client)).isEqualTo(429);
        assertThat(fanOtp(port, randomPhone(), randomIp())).isEqualTo(202); // another fan behind the same proxy
        assertThat(jdbc.sql("select distinct host(ip) from otp_challenges where phone_e164 in (:phones)")
                .param("phones", phones).query(String.class).list()).containsExactly(client);
    }
}

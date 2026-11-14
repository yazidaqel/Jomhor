package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import ma.jomhor.IntegrationTest;
import ma.jomhor.tenancy.application.OtpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;

/**
 * Per-IP OTP throttle with the real limits (fan 60 / 10 min, org 5 / 10 min) over real HTTP, so Tomcat's
 * RemoteIpValve runs. No proxy is trusted here (test default): the TCP peer is the client, whatever
 * X-Forwarded-For says. {@link TrustedProxyIpTest} covers the trusted side.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {IntegrationTest.JWT_SECRET, IntegrationTest.OTP_SECRET, IntegrationTest.EVENT_KEY_MASTER,
                ClientIpTest.FAN_10M, ClientIpTest.FAN_24H, ClientIpTest.ORG_10M, ClientIpTest.ORG_24H})
class ClientIpTest extends IntegrationTest {

    /** application.yml default (04 POST /fan/auth/otp: 60 per 10 min, 600 per 24 h). */
    static final int FAN_LIMIT = 60;
    static final String FAN_10M = "otp.per-ip.fan.sends-10m=" + FAN_LIMIT;
    static final String FAN_24H = "otp.per-ip.fan.sends-24h=600";
    static final String ORG_10M = "otp.per-ip.org.sends-10m=5";
    static final String ORG_24H = "otp.per-ip.org.sends-24h=20";

    @Value("${local.server.port}")
    int port;

    @Autowired
    OtpService otpService;

    @Test
    void sixtyFirstFanSendFromOneIpIs429AndASpoofedForwardedForDoesNotBypassIt() {
        // Earlier test classes' MockMvc calls also come from 127.0.0.1 (with the limits off): start clean.
        jdbc.sql("delete from otp_challenges where ip = '127.0.0.1'").update();
        List<String> phones = new ArrayList<>();
        for (int i = 0; i < FAN_LIMIT; i++) {
            phones.add(randomPhone());
            assertThat(fanOtp(port, phones.getLast(), randomIp())).isEqualTo(202);
        }
        assertThat(fanOtp(port, randomPhone(), randomIp())).isEqualTo(429);
        assertThat(fanOtp(port, randomPhone(), null)).isEqualTo(429);
        assertThat(jdbc.sql("select distinct host(ip) from otp_challenges where phone_e164 in (:phones)")
                .param("phones", phones).query(String.class).list()).containsExactly("127.0.0.1");
    }

    @Test
    void orgOverTheIpLimitIs202AndSendsNothing() throws Exception {
        String ip = randomIp();
        UUID org = organizer("IP FC");
        for (int i = 0; i < 5; i++) {
            String phone = randomPhone();
            staff(org, phone, "ADMIN", true);
            orgOtp(phone, ip).andExpect(status().isAccepted());
            assertThat(lastCode(phone)).matches("[0-9]{6}");
        }
        String sixth = randomPhone();
        staff(org, sixth, "ADMIN", true);
        orgOtp(sixth, ip).andExpect(status().isAccepted());
        awaitSends();
        verify(otpSender, never()).send(eq(sixth), anyString());
        // Another IP is its own bucket.
        orgOtp(sixth, randomIp()).andExpect(status().isAccepted());
        assertThat(lastCode(sixth)).matches("[0-9]{6}");
    }

    @Test
    void ipv6ClientsShareOneBucketPerSlash64() throws Exception {
        var r = ThreadLocalRandom.current();
        String net = "2001:db8:%x:%x:".formatted(r.nextInt(0x10000), r.nextInt(0x10000));
        List<String> phones = new ArrayList<>();
        for (int i = 0; i < FAN_LIMIT; i++) {
            phones.add(randomPhone());
            otp("/fan/auth/otp", phones.getLast(), net + randomHost()).andExpect(status().isAccepted());
        }
        otp("/fan/auth/otp", randomPhone(), net + randomHost()).andExpect(status().isTooManyRequests());
        otp("/fan/auth/otp", randomPhone(), "2001:db8:ffff:" + Integer.toHexString(r.nextInt(0x10000)) + ":" + randomHost())
                .andExpect(status().isAccepted()); // another /64
        assertThat(jdbc.sql("select distinct host(ip) from otp_challenges where phone_e164 in (:phones)")
                .param("phones", phones).query(String.class).list()).containsExactly(net + ":");
    }

    @Test
    void parallelSendsFromOneIpAtLimitMinusOneAcceptExactlyOne() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < FAN_LIMIT - 1; i++) {
            jdbc.sql("""
                    insert into otp_challenges (phone_e164, audience, ip, code_hash, expires_at)
                    values (?, 'fan', cast(? as inet), ?, now() + interval '5 minutes')
                    """).params(randomPhone(), ip, "00".repeat(32)).update();
        }
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> sends = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                sends.add(pool.submit(() -> {
                    start.await();
                    return otpService.issue(randomPhone(), "fan", ip, false);
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var s : sends) {
                accepted += s.get() ? 1 : 0;
            }
            assertThat(accepted).isEqualTo(1);
        }
    }

    private ResultActions orgOtp(String phone, String ip) throws Exception {
        return otp("/org/auth/otp", phone, ip);
    }

    private ResultActions otp(String path, String phone, String ip) throws Exception {
        return mvc.perform(post(path).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content("{\"phone\": \"%s\"}".formatted(phone)));
    }

    /** POST /v1/fan/auth/otp over real HTTP from 127.0.0.1, with this X-Forwarded-For (null = none). */
    static int fanOtp(int port, String phone, String forwardedFor) {
        var req = RestClient.create("http://127.0.0.1:" + port).post().uri("/v1/fan/auth/otp")
                .contentType(MediaType.APPLICATION_JSON).body("{\"phone\": \"%s\"}".formatted(phone));
        if (forwardedFor != null) {
            req.header("X-Forwarded-For", forwardedFor);
        }
        return req.exchange((rq, rs) -> rs.getStatusCode().value());
    }

    private static String randomHost() {
        var r = ThreadLocalRandom.current();
        return "%x:%x:%x:%x".formatted(1 + r.nextInt(0xffff), r.nextInt(0x10000), r.nextInt(0x10000), 1 + r.nextInt(0xffff));
    }

    static String randomIp() {
        var r = ThreadLocalRandom.current();
        return "10.%d.%d.%d".formatted(r.nextInt(256), r.nextInt(256), 1 + r.nextInt(254));
    }
}

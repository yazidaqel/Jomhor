package ma.jomhor;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import ma.jomhor.tenancy.application.OtpSender;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Full app on one shared Postgres 18 container, with test secrets and a mock OtpSender. */
@SpringBootTest(properties = {IntegrationTest.JWT_SECRET, IntegrationTest.OTP_SECRET, IntegrationTest.EVENT_KEY_MASTER})
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    /** For subclasses that redeclare @SpringBootTest (e.g. RANDOM_PORT). */
    public static final String JWT_SECRET = "jomhor.jwt-secret=test-only-jwt-secret-0123456789abcdef";
    public static final String OTP_SECRET = "jomhor.otp-secret=test-only-otp-secret-0123456789abcdef";
    /** base64 of the 32 ASCII bytes "test-only-event-key-master-01234". */
    public static final String EVENT_KEY_MASTER =
            "jomhor.event-key-master=dGVzdC1vbmx5LWV2ZW50LWtleS1tYXN0ZXItMDEyMzQ=";

    @ServiceConnection
    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start(); // stopped by Ryuk when the JVM exits; shared by every subclass
    }

    @MockitoBean
    protected OtpSender otpSender;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient jdbc;

    /** A fresh Moroccan mobile number, so tests never share a rate-limit window. */
    protected static String randomPhone() {
        return "+2126" + "%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
    }

    protected ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions requestOtp(String path, String phone) throws Exception {
        return postJson(path, "{\"phone\": \"%s\"}".formatted(phone));
    }

    protected ResultActions verifyOtp(String path, String phone, String code) throws Exception {
        return postJson(path, "{\"phone\": \"%s\", \"code\": \"%s\"}".formatted(phone, code));
    }

    /** The last code the mock sender got for this phone. */
    protected String lastCode(String phone) {
        var code = ArgumentCaptor.forClass(String.class);
        verify(otpSender, atLeastOnce()).send(eq(phone), code.capture());
        return code.getValue();
    }

    protected String fanToken(String phone) throws Exception {
        requestOtp("/fan/auth/otp", phone).andExpect(status().isAccepted());
        return token(verifyOtp("/fan/auth/verify", phone, lastCode(phone)));
    }

    protected String orgToken(String phone) throws Exception {
        requestOtp("/org/auth/otp", phone).andExpect(status().isAccepted());
        return token(verifyOtp("/org/auth/verify", phone, lastCode(phone)));
    }

    protected UUID organizer(String name) {
        return jdbc.sql("insert into organizers (name) values (?) returning id").param(name).query(UUID.class).single();
    }

    /** Creates (or reuses) the user for this phone and gives it a staff row; returns the user id. */
    protected UUID staff(UUID organizerId, String phone, String role, boolean active) {
        jdbc.sql("insert into users (phone_e164) values (?) on conflict do nothing").param(phone).update();
        UUID userId = jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single();
        jdbc.sql("insert into staff (organizer_id, user_id, name, role, active) values (?, ?, 'Test', ?::staff_role, ?)")
                .params(organizerId, userId, role, active).update();
        return userId;
    }

    private static String token(ResultActions verify) throws Exception {
        String body = verify.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }
}

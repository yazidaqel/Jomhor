package ma.jomhor.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import ma.jomhor.catalog.OrgApiTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Fixtures for card payments on the simulated gateway: a sale, orders, pay, signed callbacks, DB probes. */
public abstract class PaymentTest extends OrgApiTest {

    /** payments.fake.secret in src/test/resources/application.properties. */
    static final String FAKE_SECRET = "test-only-payments-fake-secret-0123456789";
    static final String BASE = "http://localhost/v1";

    /** A published event with one category of {@code quota} at 50 DH. */
    public record Sale(Caller org, int zoneId, int eventId, String priceId) {}

    protected Sale onSale(int quota) throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int zoneId = zone(a, venueId, "NORD");
        int eventId = event(a, venueId);
        String priceId = price(a, eventId, zoneId, "Plein tarif", quota);
        call(a, post("/org/events/" + eventId + "/publish")).andExpect(status().isOk());
        return new Sale(a, zoneId, eventId, priceId);
    }

    protected ResultActions fan(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }

    protected ResultActions fan(String token, MockHttpServletRequestBuilder req, String body) throws Exception {
        return fan(token, req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected String order(String token, Sale s, int qty) throws Exception {
        return JsonPath.read(body(fan(token, post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": %d, "holders": %s}]}
                """.formatted(s.eventId(), s.priceId(), qty, holders(qty))).andExpect(status().isCreated())), "$.id");
    }

    protected ResultActions pay(String token, String orderId) throws Exception {
        return fan(token, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CARD\"}");
    }

    protected String payment(String token, String orderId) throws Exception {
        return JsonPath.read(body(pay(token, orderId).andExpect(status().isOk())), "$.payment_id");
    }

    static String sign(String paymentId, String outcome, String amountMad, String providerRef) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(FAKE_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(String.join("|", paymentId, outcome, amountMad, providerRef)
                .getBytes(StandardCharsets.UTF_8)));
    }

    /** The callback fields the simulated gateway posts, signed with the test secret. */
    protected static Map<String, String> signed(String paymentId, String outcome, long amountMad, String providerRef) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("payment_id", paymentId);
        m.put("outcome", outcome);
        m.put("amount_mad", Long.toString(amountMad));
        m.put("provider_ref", providerRef);
        m.put("signature", sign(paymentId, outcome, Long.toString(amountMad), providerRef));
        return m;
    }

    protected static Map<String, String> approve(String paymentId, long amountMad) throws Exception {
        return signed(paymentId, "APPROVED", amountMad, "FAKE-" + UUID.randomUUID());
    }

    protected ResultActions callback(Map<String, String> fields) throws Exception {
        var req = post("/payments/fake/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        fields.forEach(req::param);
        return mvc.perform(req);
    }

    /** The hidden fields of the form whose button reads {@code label} on the simulated gateway page. */
    static Map<String, String> formFields(String html, String label) {
        var form = Pattern.compile("<form[^>]*action=\"callback\">(.*?)<button type=\"submit\">" + label + "</button>",
                Pattern.DOTALL).matcher(html);
        if (!form.find()) {
            throw new AssertionError("no form '" + label + "' in " + html);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        var input = Pattern.compile("name=\"([^\"]+)\" value=\"([^\"]*)\"").matcher(form.group(1));
        while (input.find()) {
            fields.put(input.group(1), input.group(2));
        }
        return fields;
    }

    /** GET of an absolute URL on this API (strips the public base). */
    ResultActions browse(String absoluteUrl) throws Exception {
        return mvc.perform(get(absoluteUrl.substring(BASE.length())));
    }

    // ---------- probes ----------

    String paymentStatus(String paymentId) {
        return jdbc.sql("select status::text from payments where id = ?::uuid").param(paymentId).query(String.class).single();
    }

    String orderStatus(String orderId) {
        return jdbc.sql("select status::text from orders where id = ?::uuid").param(orderId).query(String.class).single();
    }

    int sold(String priceId) {
        return jdbc.sql("select sold from price_categories where id = ?::uuid").param(priceId).query(Integer.class).single();
    }

    int tickets(String orderId) {
        return jdbc.sql("select count(*) from tickets where order_id = ?::uuid").param(orderId).query(Integer.class).single();
    }

    protected long total(String orderId) {
        return jdbc.sql("select total_mad from orders where id = ?::uuid").param(orderId).query(Long.class).single();
    }

    int refundsRequired(String paymentId) {
        return jdbc.sql("select count(*) from audit_log where action = 'payment.refund_required' and target = ?")
                .param(paymentId).query(Integer.class).single();
    }
}

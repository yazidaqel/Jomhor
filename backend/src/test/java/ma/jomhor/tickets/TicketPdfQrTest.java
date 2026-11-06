package ma.jomhor.tickets;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import ma.jomhor.tickets.adapters.pdf.TicketPdf;
import ma.jomhor.tickets.domain.TicketToken;
import org.junit.jupiter.api.Test;

/**
 * The PDF QR decodes for any token, not just a lucky one: 200 random keys and ticket ids (no Spring, no DB). A failure
 * names its seed: put it in place of System.nanoTime() to replay the same tickets.
 */
class TicketPdfQrTest {

    @Test
    void twoHundredRandomTicketsAllDecode() throws Exception {
        long seed = System.nanoTime();
        Random random = new Random(seed);
        for (int i = 0; i < 200; i++) {
            byte[] key = new byte[32];
            random.nextBytes(key);
            UUID ticketId = new UUID(random.nextLong(), random.nextLong());
            String token = TicketToken.encodeStatic(key, random.nextInt(1, Integer.MAX_VALUE), ticketId,
                    random.nextInt(1, Short.MAX_VALUE));
            byte[] pdf = TicketPdf.render(new TicketPdf.Content(token, TicketToken.ticketNo(ticketId),
                    "Atlas FC - Raja CA", Instant.parse("2026-12-19T19:00:00Z"), "Africa/Casablanca",
                    "Grand Stade, Casablanca", "NORD", List.of("P1", "P2"), "Yazid A."));
            assertThat(TicketPdfApiTest.qr(pdf).getText()).as("seed %d, ticket #%d %s", seed, i, ticketId).isEqualTo(token);
        }
    }
}

package ma.jomhor.crm.adapters.web;

import java.util.List;
import java.util.UUID;
import ma.jomhor.crm.application.CampaignService;
import ma.jomhor.crm.application.Campaigns;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Fan GET /fan/me/inbox: {messages:[{id, organizer:{id, slug, name, crest_url}, title, body,
 * event_id, sent_at}]}, no-store. The rows are Campaigns.InboxMessage as they are.
 */
@RestController
class FanInboxController {

    record Inbox(List<Campaigns.InboxMessage> messages) {}

    private final CampaignService campaigns;

    FanInboxController(CampaignService campaigns) {
        this.campaigns = campaigns;
    }

    @GetMapping("/fan/me/inbox")
    ResponseEntity<Inbox> inbox(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Inbox(campaigns.inbox(UUID.fromString(jwt.getSubject()))));
    }
}

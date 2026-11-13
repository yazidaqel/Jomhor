package ma.jomhor.crm.adapters.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.crm.application.CampaignService;
import ma.jomhor.crm.application.MembershipService;
import ma.jomhor.crm.domain.Campaign;
import ma.jomhor.notifications.domain.FanMessage.Channel;
import ma.jomhor.tenancy.domain.OrgContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Organizer /org/campaigns and /org/members/summary. Roles come from OrgContextInterceptor: ADMIN and
 * OPS read and write, FINANCE reads (its POST/PATCH are 403 there), CASHIER, GATE and SUPERVISOR get 403.
 */
@RestController
class OrgCampaignController {

    record Body(Channel channel, Campaign.Audience audience, Integer eventId, String title, String body) {
        CampaignService.Input input() {
            return new CampaignService.Input(channel, audience, eventId, title, body);
        }
    }

    record SendBody(Instant scheduledAt) {}

    record StaffRef(UUID id, String name) {}

    record Row(UUID id, Channel channel, Campaign.Audience audience, Integer eventId, String title,
               Campaign.Status status, Instant scheduledAt, Instant startedAt, Instant finishedAt, StaffRef createdBy,
               long recipients, long sent, long failed) {
        static Row of(Campaign c) {
            return new Row(c.id(), c.channel(), c.audience(), c.eventId(), c.title(), c.status(), c.scheduledAt(),
                    c.startedAt(), c.finishedAt(), new StaffRef(c.createdBy(), c.createdByName()), c.recipients(),
                    c.sent(), c.failed());
        }
    }

    /** The list row + body + audience_estimate. */
    record Detail(UUID id, Channel channel, Campaign.Audience audience, Integer eventId, String title,
                  Campaign.Status status, Instant scheduledAt, Instant startedAt, Instant finishedAt,
                  StaffRef createdBy, long recipients, long sent, long failed, String body, long audienceEstimate) {
        static Detail of(CampaignService.Detail d) {
            Campaign c = d.campaign();
            return new Detail(c.id(), c.channel(), c.audience(), c.eventId(), c.title(), c.status(), c.scheduledAt(),
                    c.startedAt(), c.finishedAt(), new StaffRef(c.createdBy(), c.createdByName()), c.recipients(),
                    c.sent(), c.failed(), c.body(), d.audienceEstimate());
        }
    }

    private final CampaignService campaigns;
    private final MembershipService memberships;

    OrgCampaignController(CampaignService campaigns, MembershipService memberships) {
        this.campaigns = campaigns;
        this.memberships = memberships;
    }

    @GetMapping("/org/members/summary")
    MembershipService.Summary summary(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org) {
        return memberships.summary(org.organizerId());
    }

    @GetMapping("/org/campaigns")
    List<Row> list(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org) {
        return campaigns.list(org.organizerId()).stream().map(Row::of).toList();
    }

    @PostMapping("/org/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    Detail create(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @RequestBody Body body) {
        return Detail.of(campaigns.create(org.organizerId(), org.userId(), body.input()));
    }

    @GetMapping("/org/campaigns/{id}")
    Detail get(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id) {
        return Detail.of(campaigns.get(org.organizerId(), id));
    }

    @PatchMapping("/org/campaigns/{id}")
    Detail update(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                  @RequestBody Body body) {
        return Detail.of(campaigns.update(org.organizerId(), id, body.input()));
    }

    /** Body optional: none or {} = now. */
    @PostMapping("/org/campaigns/{id}/send")
    Detail send(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id,
                @RequestBody(required = false) SendBody body) {
        return Detail.of(campaigns.send(org.organizerId(), org.userId(), id, body == null ? null : body.scheduledAt()));
    }

    @PostMapping("/org/campaigns/{id}/cancel")
    Detail cancel(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id) {
        return Detail.of(campaigns.cancel(org.organizerId(), org.userId(), id));
    }
}

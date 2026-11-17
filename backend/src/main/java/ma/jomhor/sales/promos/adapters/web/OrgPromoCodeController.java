package ma.jomhor.sales.promos.adapters.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.sales.promos.application.PromoCodeService;
import ma.jomhor.sales.promos.domain.PromoCode;
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
 * docs/04-api.md §Organizer /org/events/{id}/promo-codes*. Roles from OrgContextInterceptor: ADMIN and OPS read and
 * write, FINANCE reads, CASHIER, GATE and SUPERVISOR get 403. POST and PATCH return the list row. Money in centimes.
 */
@RestController
class OrgPromoCodeController {

    record Body(String code, PromoCode.Kind kind, Integer percent, Long amountMad, UUID priceCategoryId,
                Integer maxUses, Integer perAccountMax, Boolean active) {
        PromoCodeService.Input input() {
            return new PromoCodeService.Input(code, kind, percent, amountMad, priceCategoryId, maxUses, perAccountMax,
                    active);
        }
    }

    record ZoneRef(String code, String name) {}

    record CategoryRef(UUID id, String name, ZoneRef zone) {}

    record Uses(long held, long paid) {}

    record PromoDto(UUID id, String code, PromoCode.Kind kind, Integer percent, Long amountMad,
                    CategoryRef priceCategory, Integer maxUses, int perAccountMax, boolean active, Instant createdAt,
                    Uses uses, long discountMad) {
        static PromoDto of(PromoCodeService.View v) {
            PromoCode p = v.listed().promo();
            Event.PriceCategory c = v.category();
            return new PromoDto(p.id(), p.code(), p.kind(), p.percent(), p.amountMad(),
                    c == null ? null : new CategoryRef(c.id(), c.name(), new ZoneRef(c.zoneCode(), c.zoneName())),
                    p.maxUses(), p.perAccountMax(), p.active(), p.createdAt(),
                    new Uses(v.listed().held(), v.listed().paid()), v.listed().discountMad());
        }
    }

    private final PromoCodeService promos;

    OrgPromoCodeController(PromoCodeService promos) {
        this.promos = promos;
    }

    @GetMapping("/org/events/{id}/promo-codes")
    List<PromoDto> list(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        return promos.list(org.organizerId(), id).stream().map(PromoDto::of).toList();
    }

    @PostMapping("/org/events/{id}/promo-codes")
    @ResponseStatus(HttpStatus.CREATED)
    PromoDto create(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id,
                    @RequestBody Body body) {
        return PromoDto.of(promos.create(org.organizerId(), org.userId(), id, body.input()));
    }

    @PatchMapping("/org/events/{id}/promo-codes/{promoId}")
    PromoDto update(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id,
                    @PathVariable UUID promoId, @RequestBody Body body) {
        return PromoDto.of(promos.update(org.organizerId(), org.userId(), id, promoId, body.input()));
    }
}

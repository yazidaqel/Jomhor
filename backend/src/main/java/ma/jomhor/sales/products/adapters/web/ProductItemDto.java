package ma.jomhor.sales.products.adapters.web;

import java.util.List;
import java.util.UUID;
import ma.jomhor.common.HolderNames;
import ma.jomhor.sales.application.OrderService;

/**
 * A product order's item as POST /fan/product-orders and GET /fan/orders/{id} show it (docs/04-api.md §Fan):
 * {@code price_category_id} null; {@code zone_id}, {@code zone_name} the pass's zone, null for MEMBERSHIP; {@code name}
 * the product's. Holder labels only.
 */
public record ProductItemDto(UUID productId, UUID priceCategoryId, Integer zoneId, String zoneName, String name,
                             int qty, long unitPriceMad, List<HolderDto> holders) {

    public record HolderDto(String label) {}

    public static List<ProductItemDto> of(OrderService.View v) {
        var pass = v.product().pass();
        return v.order().items().stream().map(i -> new ProductItemDto(i.productId(), null,
                pass == null ? null : pass.zone().id(), pass == null ? null : pass.zone().name(),
                v.product().product().name(), i.qty(), i.unitPriceMad(),
                i.holders().stream().map(h -> new HolderDto(HolderNames.label(h.name()))).toList())).toList();
    }
}

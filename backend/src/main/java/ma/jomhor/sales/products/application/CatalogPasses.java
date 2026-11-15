package ma.jomhor.sales.products.application;

import java.util.UUID;
import ma.jomhor.catalog.application.EventPasses;
import org.springframework.stereotype.Component;

/**
 * Catalog's view of season passes. Kept apart from ProductService, which depends on catalog's EventService, so the two
 * modules' beans don't form a cycle.
 */
@Component
class CatalogPasses implements EventPasses {

    private final Products products;

    CatalogPasses(Products products) {
        this.products = products;
    }

    @Override
    public boolean hasPassTickets(UUID org, int eventId) {
        return products.hasPassTickets(org, eventId);
    }
}

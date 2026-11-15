package ma.jomhor.sales.products.application;

import java.util.UUID;
import ma.jomhor.crm.application.MembershipProducts;
import ma.jomhor.sales.products.domain.Product;
import org.springframework.stereotype.Component;

/**
 * crm's view of membership products. Kept apart from ProductOrderService, which depends on crm's MembershipService, so
 * the two modules' beans don't form a cycle.
 */
@Component
class CrmMembershipProducts implements MembershipProducts {

    private final Products products;

    CrmMembershipProducts(Products products) {
        this.products = products;
    }

    /** ponytail: reads the organizer's MEMBERSHIP rows (a handful); an exists query if a club ever has many. */
    @Override
    public boolean any(UUID org) {
        return !products.list(org, Product.Kind.MEMBERSHIP).isEmpty();
    }
}

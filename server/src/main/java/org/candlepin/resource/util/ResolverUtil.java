/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.resource.util;

import org.candlepin.common.exceptions.BadRequestException;
import org.candlepin.common.exceptions.NotFoundException;
import org.candlepin.model.Owner;
import org.candlepin.model.OwnerCurator;
import org.candlepin.model.Pool;
import org.candlepin.model.Product;
import org.candlepin.model.ProductCurator;
import org.candlepin.model.ProvidedProduct;
import org.candlepin.model.dto.Subscription;

import com.google.inject.Inject;

import org.xnap.commons.i18n.I18n;

import java.util.HashSet;

/**
 * Convinience util for resolving owners, products, pools & subscriptions
 */
public class ResolverUtil {

    private I18n i18n;
    private OwnerCurator ownerCurator;
    private ProductCurator productCurator;

    @Inject
    public ResolverUtil(I18n i18n, OwnerCurator ownerCurator, ProductCurator productCurator) {
        this.i18n = i18n;
        this.ownerCurator = ownerCurator;
        this.productCurator = productCurator;
    }

    public Owner resolveOwner(Owner owner) {
        if (owner == null || (owner.getKey() == null && owner.getId() == null)) {
            throw new BadRequestException(
                    i18n.tr("No owner specified, or owner lacks identifying information"));
        }

        if (owner.getKey() != null) {
            String key = owner.getKey();
            owner = ownerCurator.lookupByKey(owner.getKey());

            if (owner == null) {
                throw new NotFoundException(i18n.tr("Unable to find an owner with the key \"{0}\"", key));
            }
        }
        else {
            String id = owner.getId();
            owner = ownerCurator.find(owner.getId());

            if (owner == null) {
                throw new NotFoundException(i18n.tr("Unable to find an owner with the ID \"{0}\"", id));
            }
        }

        return owner;
    }

    public Product resolveProduct(Owner owner, Product product) {
        String id = null;
        if (product != null) {
            id = product.getId();
        }
        return resolveProduct(owner, id);
    }

    public Product resolveProduct(Owner owner, String productId) {
        if (productId == null) {
            throw new BadRequestException(
                    i18n.tr("No product specified, or product lacks identifying information"));
        }

        // TODO: Maybe add UUID resolution as well?
        return findProduct(owner, productId);
    }

    public Product findProduct(Owner owner, String productId) {
        Product product = productCurator.lookupById(owner, productId);

        if (product == null) {
            throw new NotFoundException(i18n.tr(
                "Unable to find a product with the ID \"{0}\" for owner \"{1}\"",
                productId, owner.getKey()
            ));
        }

        return product;
    }

    public Pool resolvePool(Pool pool) {
        // Impl note:
        // We don't check that the subscription exists here, because it's
        // entirely possible that it
        // doesn't (i.e. during creation). We just need to make sure it's not
        // null.
        if (pool == null) {
            throw new BadRequestException(i18n.tr("No subscription specified"));
        }

        // Ensure the owner is set and is valid
        Owner owner = this.resolveOwner(pool.getOwner());
        pool.setOwner(owner);

        // Ensure the specified product(s) exists for the given owner
        pool.setProduct(this.resolveProduct(owner, pool.getProduct()));

        if (pool.getDerivedProduct() != null) {
            pool.setDerivedProduct(this.resolveProduct(owner, pool.getDerivedProduct()));
        }

        HashSet<Product> presolved = new HashSet<Product>();

        for (ProvidedProduct product : pool.getProvidedProductDtos()) {
            // TODO: Maybe add UUID resolution as well?
            presolved.add(resolveProduct(owner, product.getProductId()));
        }

        pool.setProvidedProducts(presolved);
        presolved.clear();

        for (ProvidedProduct product : pool.getDerivedProvidedProductDtos()) {
            presolved.add(this.resolveProduct(owner, product.getProductId()));
        }

        pool.setDerivedProvidedProducts(presolved);

        // TODO: Do we need to resolve Branding objects?

        return pool;
    }

    public Subscription resolveSubscription(Subscription subscription) {
        // Impl note:
        // We don't check that the subscription exists here, because it's
        // entirely possible that it
        // doesn't (i.e. during creation). We just need to make sure it's not
        // null.
        if (subscription == null) {
            throw new BadRequestException(i18n.tr("No subscription specified"));
        }

        // Ensure the owner is set and is valid
        Owner owner = this.resolveOwner(subscription.getOwner());
        subscription.setOwner(owner);

        // Ensure the specified product(s) exists for the given owner
        subscription.setProduct(this.resolveProduct(owner, subscription.getProduct()));

        if (subscription.getDerivedProduct() != null) {
            subscription.setDerivedProduct(this.resolveProduct(owner, subscription.getDerivedProduct()));
        }

        HashSet<Product> presolved = new HashSet<Product>();

        for (Product product : subscription.getProvidedProducts()) {
            presolved.add(this.resolveProduct(owner, product));
        }

        subscription.setProvidedProducts(presolved);
        presolved.clear();

        for (Product product : subscription.getDerivedProvidedProducts()) {
            presolved.add(this.resolveProduct(owner, product));
        }

        subscription.setDerivedProvidedProducts(presolved);

        // TODO: Do we need to resolve Branding objects?

        return subscription;
    }
}

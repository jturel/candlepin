/**
 * Copyright (c) 2009 Red Hat, Inc.
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
package org.fedoraproject.candlepin.model;

import java.util.Date;
import java.util.Set;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.ManyToMany;
import javax.persistence.OneToMany;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Transient;
import javax.persistence.UniqueConstraint;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import org.hibernate.annotations.ForeignKey;

/**
 * Represents a pool of products eligible to be consumed (entitled).
 * For every Product there will be a corresponding Pool.
 */
@XmlRootElement
@XmlAccessorType(XmlAccessType.PROPERTY)
@Entity
@Table(name = "cp_entitlement_pool")
@SequenceGenerator(name="seq_entitlement_pool", sequenceName="seq_entitlement_pool", allocationSize=1)
public class EntitlementPool implements Persisted {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator="seq_entitlement_pool")
    private Long id;
    
    @ManyToOne
    @ForeignKey(name = "fk_entitlement_pool_owner")
    @JoinColumn(nullable = false)
    private Owner owner;
    
    @ManyToOne
    @ForeignKey(name = "fk_entitlement_pool_product")
    @JoinColumn(nullable = false)
    private Product product;
    
    @Column(nullable = false)
    private Long maxMembers;

    @Column(nullable = false)
    private Long currentMembers;

    @Column(nullable = false)
    private Date startDate;
    
    @Column(nullable = false)
    private Date endDate;

    @OneToMany
//    @ForeignKey(name = "fk_entitlement_pool_attribute")
    @JoinColumn(name = "attribute_id")
    private Set<Attribute> attributes;

    public EntitlementPool() {
    }

    public EntitlementPool(Owner ownerIn, Product productIn, Long maxMembersIn, 
            Date startDateIn, Date endDateIn) {
        this.owner = ownerIn;
        this.product = productIn;
        this.maxMembers = maxMembersIn;
        this.startDate = startDateIn;
        this.endDate = endDateIn;
        
        // Always assume no current members if creating a new pool.
        this.currentMembers = new Long(0);
    }

    /**
     * @return the id
     */
    public Long getId() {
        return id;
    }

    /**
     * @param id the id to set
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * @return the product
     */
    public Product getProduct() {
        return product;
    }

    /**
     * @param product the product to set
     */
    public void setProduct(Product product) {
        this.product = product;
    }

    /**
     * @return the startDate
     */
    public Date getStartDate() {
        return startDate;
    }

    /**
     * @param startDate the startDate to set
     */
    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }

    /**
     * @return the endDate
     */
    public Date getEndDate() {
        return endDate;
    }

    /**
     * @param endDate the endDate to set
     */
    public void setEndDate(Date endDate) {
        this.endDate = endDate;
    }

    /**
     * @return the maxMembers
     */
    public Long getMaxMembers() {
        return maxMembers;
    }

    /**
     * @param maxMembers the maxMembers to set
     */
    public void setMaxMembers(Long maxMembers) {
        this.maxMembers = maxMembers;
    }

    /**
     * @return the currentMembers
     */
    public Long getCurrentMembers() {
        return currentMembers;
    }

    /**
     * @param currentMembers the currentMembers to set
     */
    public void setCurrentMembers(long currentMembers) {
        this.currentMembers = currentMembers;
    }
    
    public boolean hasAvailableEntitlements() {
        if (currentMembers >= maxMembers) {
            return false;
        }
        return true;
    }

    /**
     * @return the owner
     */
    public Owner getOwner() {
        return owner;
    }

    /**
     * @param owner the owner to set
     */
    @XmlTransient
    public void setOwner(Owner owner) {
        this.owner = owner;
    }

    /**
     * Add 1 to the current members.
     */
    public void bumpCurrentMembers() {
        this.currentMembers = this.currentMembers + 1;
    }

	public Set<Attribute> getAttributes() {
		return attributes;
	}

	public void setAttributes(Set<Attribute> attributes) {
		this.attributes = attributes;
	}

}

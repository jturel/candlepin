package org.fedoraproject.candlepin.model.test;

import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;

import org.fedoraproject.candlepin.model.Owner;
import org.fedoraproject.candlepin.model.Product;
import org.fedoraproject.candlepin.util.EntityManagerUtil;
import org.junit.After;
import org.junit.Before;

public class ModelTestFixture {
    
    protected EntityManager em;
    
    @Before
    public void setUp() {
        em = EntityManagerUtil.createEntityManager();
    }
    
    /*
     * Cleans out everything in the database. Currently we test against an 
     * in-memory hsqldb, so this should be ok.
     * 
     *  WARNING: Don't flip the persistence unit to point to an actual live 
     *  DB or you'll lose everything.
     *  
     *  WARNING: If you're creating objects in tables that have static 
     *  pre-populated content, we may not want to wipe those tables here.
     *  This situation hasn't arisen yet.
     */
    @After
    public void cleanDb() {
        if (!em.getTransaction().isActive()) {
            em.getTransaction().begin();
        }
        
        // TODO: Would rather be doing this, but such a bulk delete does not seem to respect
        // the cascade to child products and thus fails.
        // em.createQuery("delete from Product").executeUpdate();
        List<Product> products = em.createQuery("from Product p").getResultList();
        for (Product p : products) {
            em.remove(p);
        }
        
        List<Owner> owners = em.createQuery("from Owner o").getResultList();
        for (Owner o : owners) {
            em.remove(o);
        }
        em.getTransaction().commit();
        em.close();
    }
    
    /**
     * Begin a transaction, persist the given entity, and commit.
     * 
     * @param storeMe Entity to persist.
     */
    protected void persistAndCommit(Object storeMe) {
        EntityTransaction tx = null;
        
        try {
            tx = em.getTransaction();
            tx.begin();
            em.persist(storeMe);
            tx.commit();
        }
        catch (RuntimeException e) {
            if (tx != null && tx.isActive()) {
                tx.rollback();
            }
            throw e; // or display error message
        }
    }


}

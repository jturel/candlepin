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
package org.candlepin.model;

import org.candlepin.auth.Principal;
import org.candlepin.auth.permissions.Permission;
import org.candlepin.common.exceptions.ConcurrentModificationException;
import org.candlepin.common.paging.Page;
import org.candlepin.common.paging.PageRequest;
import org.candlepin.guice.PrincipalProvider;

import com.google.common.collect.Iterables;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.persist.Transactional;

import org.apache.commons.collections.CollectionUtils;
import org.hibernate.Criteria;
import org.hibernate.SQLQuery;
import org.hibernate.Session;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Projection;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.hibernate.internal.CriteriaImpl;
import org.hibernate.transform.ResultTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnap.commons.i18n.I18n;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.OptimisticLockException;
import javax.persistence.Query;



/**
 * AbstractHibernateCurator base class for all Candlepin curators. Curators are
 * the applications database layer, typically following the pattern of one
 * curator for each model type. This class contains methods common to all
 * curators.
 * @param <E> Entity specific curator.
 */
public abstract class AbstractHibernateCurator<E extends Persisted> {
    // oracle has a limit of 1000
    public static final int IN_OPERATOR_BLOCK_SIZE = 999;

    @Inject protected Provider<EntityManager> entityManager;
    @Inject protected I18n i18n;
    private final Class<E> entityType;
    protected int batchSize = 500;

    @Inject private PrincipalProvider principalProvider;
    private static Logger log = LoggerFactory.getLogger(AbstractHibernateCurator.class);

    public AbstractHibernateCurator(Class<E> entityType) {
        //entityType = (Class<E>) ((ParameterizedType)
        //getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        this.entityType = entityType;
    }

    public Class<E> entityType() {
        return entityType;
    }

    public void enableFilter(String filterName, String parameterName, Object value) {
        currentSession().enableFilter(filterName).setParameter(parameterName, value);
    }

    public void enableFilterList(String filterName, String parameterName,
        Collection value) {
        currentSession().enableFilter(filterName).setParameterList(parameterName, value);
    }

    /**
     * @param id db id of entity to be found.
     * @return entity matching given id, or null otherwise.
     */
    @Transactional
    public E find(Serializable id) {
        return id == null ? null : get(entityType, id);
    }

    /**
     * Same as {@link find} but allows permissions on the current principal to inject
     * filters into the query before it is run. Primarily useful in authentication when
     * we want to verify access to an entity specified in the URL, but not reveal if
     * the entity exists or not if you don't have permissions to see it at all.
     *
     * @param id db id of entity to be found.
     * @return entity matching given id, or null otherwise.
     */
    @Transactional
    public E secureFind(Serializable id) {
        return id == null ? null : secureGet(entityType, id);
    }

    /**
     * @param entity to be created.
     * @return newly created entity
     */
    @Transactional
    public E create(E entity) {
        save(entity);
        return entity;
    }

    /**
     * @return all entities for a particular type.
     */
    public List<E> listAll() {
        return listByCriteria(createSecureCriteria());
    }

    public List<E> listAllByIds(Collection<? extends Serializable> ids) {
        return listByCriteria(
            createSecureCriteria().add(unboundedInCriterion("id", ids)));
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public Page<List<E>> listAll(PageRequest pageRequest, boolean postFilter) {
        Page<List<E>> resultsPage;
        if (postFilter) {
            // Create a copy of the page request with just the order and sort by values.
            // Since we are filtering after the results are returned, we don't want
            // to send the page or page size values in.
            PageRequest orderAndSortByPageRequest = null;
            if (pageRequest != null) {
                orderAndSortByPageRequest = new PageRequest();
                orderAndSortByPageRequest.setOrder(pageRequest.getOrder());
                orderAndSortByPageRequest.setSortBy(pageRequest.getSortBy());
            }

            resultsPage = listAll(orderAndSortByPageRequest);

            // Set the pageRequest to the correct object here.
            resultsPage.setPageRequest(pageRequest);
        }
        else {
            resultsPage = listAll(pageRequest);
        }
        return resultsPage;
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public Page<List<E>> listAll(PageRequest pageRequest) {
        Page<List<E>> page = new Page<List<E>>();

        if (pageRequest != null) {
            Criteria count = createSecureCriteria();
            page.setMaxRecords(findRowCount(count));

            Criteria c = createSecureCriteria();
            page.setPageData(loadPageData(c, pageRequest));
            page.setPageRequest(pageRequest);
        }
        else {
            List<E> pageData = listAll();
            page.setMaxRecords(pageData.size());
            page.setPageData(pageData);
        }

        return page;
    }

    @SuppressWarnings("unchecked")
    private List<E> loadPageData(Criteria c, PageRequest pageRequest) {
        c.addOrder(createPagingOrder(pageRequest));
        if (pageRequest.isPaging()) {
            c.setFirstResult((pageRequest.getPage() - 1) * pageRequest.getPerPage());
            c.setMaxResults(pageRequest.getPerPage());
        }
        return c.list();
    }

    private Order createPagingOrder(PageRequest p) {
        String sortBy = (p.getSortBy() == null) ?
            AbstractHibernateObject.DEFAULT_SORT_FIELD : p.getSortBy();
        PageRequest.Order order = (p.getOrder() == null) ?
            PageRequest.DEFAULT_ORDER : p.getOrder();

        switch (order) {
            case ASCENDING:
                return Order.asc(sortBy);
            //DESCENDING
            default:
                return Order.desc(sortBy);
        }
    }

    private Integer findRowCount(Criteria c) {
        c.setProjection(Projections.rowCount());
        return ((Long) c.uniqueResult()).intValue();
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public List<E> listByCriteria(Criteria query) {
        return query.list();
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public Page<List<E>> listByCriteria(Criteria query,
        PageRequest pageRequest, boolean postFilter) {
        Page<List<E>> resultsPage;
        if (postFilter) {
            // Create a copy of the page request with just the order and sort by values.
            // Since we are filtering after the results are returned, we don't want
            // to send the page or page size values in.
            PageRequest orderAndSortByPageRequest = null;
            if (pageRequest != null) {
                orderAndSortByPageRequest = new PageRequest();
                orderAndSortByPageRequest.setOrder(pageRequest.getOrder());
                orderAndSortByPageRequest.setSortBy(pageRequest.getSortBy());
            }

            resultsPage = listByCriteria(query, orderAndSortByPageRequest);

            // Set the pageRequest to the correct object here.
            resultsPage.setPageRequest(pageRequest);
        }
        else {
            resultsPage = listByCriteria(query, pageRequest);
        }
        return resultsPage;
    }

    /**
     * Gives the permissions a chance to add aliases and then restrictions to the query.
     * Uses an "or" so a principal could carry permissions for multiple owners
     * (for example), but still have their results filtered without one of the perms
     * hiding the results from the other.
     *
     * @return Criteria Final criteria query with all filters applied.
     */
    protected Criteria createSecureCriteria() {
        return this.createSecureCriteria(null);
    }


    /**
     * Gives the permissions a chance to add aliases and then restrictions to the query.
     * Uses an "or" so a principal could carry permissions for multiple owners
     * (for example), but still have their results filtered without one of the perms
     * hiding the results from the other.
     *
     * @param alias
     *  The alias to assign to the entity managed by the resultant Criteria.
     *
     * @return Criteria Final criteria query with all filters applied.
     */
    protected Criteria createSecureCriteria(String alias) {
        Principal principal = principalProvider.get();
        Criteria query = (alias != null && !alias.equals("")) ?
            currentSession().createCriteria(entityType, alias) :
            currentSession().createCriteria(entityType);

        /*
         * There are situations where consumer queries are run before there is a principal,
         * i.e. during authentication when we're looking up the consumer itself.
         */
        if (principal == null) {
            return query;
        }


        // Admins do not need query filtering enabled.
        if (principal.hasFullAccess()) {
            return query;
        }

        Criterion finalCriterion = null;
        for (Permission perm : principal.getPermissions()) {

            Criterion crit = perm.getCriteriaRestrictions(entityType);
            if (crit != null) {
                log.debug("Got criteria restrictions from permissions {} for {}: {}",
                    new Object [] {perm, entityType, crit});
                if (finalCriterion == null) {
                    finalCriterion = crit;
                }
                else {
                    finalCriterion = Restrictions.or(finalCriterion, crit);
                }
            }
        }

        if (finalCriterion != null) {
            query.add(finalCriterion);
        }

        return query;
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public Page<List<E>> listByCriteria(Criteria c,
        PageRequest pageRequest) {
        Page<List<E>> page = new Page<List<E>>();

        if (pageRequest != null) {
            // see https://forum.hibernate.org/viewtopic.php?t=974802

            // Save original Projection and ResultTransformer
            CriteriaImpl cImpl = (CriteriaImpl) c;
            Projection origProjection = cImpl.getProjection();
            ResultTransformer origRt = cImpl.getResultTransformer();

            // Get total number of records by setting a rowCount projection
            page.setMaxRecords(findRowCount(c));

            // Restore original Projection and ResultTransformer
            c.setProjection(origProjection);
            c.setResultTransformer(origRt);

            page.setPageData(loadPageData(c, pageRequest));
            page.setPageRequest(pageRequest);
        }
        else {
            List<E> pageData = listByCriteria(c);
            page.setMaxRecords(pageData.size());
            page.setPageData(pageData);
        }

        return page;
    }

    /**
     * @param entity to be deleted.
     */
    @Transactional
    public void delete(E entity) {
        E toDelete = find(entity.getId());
        currentSession().delete(toDelete);
    }

    public void bulkDelete(List<E> entities) {
        for (E entity : entities) {
            delete(entity);
        }
    }

    /**
     * @param entity entity to be merged.
     * @return merged entity.
     */
    @Transactional
    public E merge(E entity) {
        return getEntityManager().merge(entity);
    }

    @Transactional
    protected final <T> T secureGet(Class<T> clazz, Serializable id) {
        return clazz.cast(createSecureCriteria().
            add(Restrictions.idEq(id)).uniqueResult());
    }

    @Transactional
    protected <T> T get(Class<T> clazz, Serializable id) {
        return clazz.cast(currentSession().get(clazz, id));
    }

    @Transactional
    protected void save(E anObject) {
        getEntityManager().persist(anObject);
        flush();
    }

    public void flush() {
        try {
            getEntityManager().flush();
        }
        catch (OptimisticLockException e) {
            throw new ConcurrentModificationException(getConcurrentModificationMessage(), e);
        }
    }

    public void clear() {
        try {
            getEntityManager().clear();
        }
        catch (OptimisticLockException e) {
            throw new ConcurrentModificationException(getConcurrentModificationMessage(), e);
        }
    }

    protected Session currentSession() {
        Session sess = (Session) entityManager.get().getDelegate();
        return sess;
    }

    protected EntityManager getEntityManager() {
        return entityManager.get();
    }

    public Collection<E> saveOrUpdateAll(Collection<E> entries, boolean flush) {
        if (CollectionUtils.isNotEmpty(entries)) {
            try {
                Session session = currentSession();
                int i = 0;
                Iterator<E> iter = entries.iterator();
                while (iter.hasNext()) {
                    session.saveOrUpdate(iter.next());
                    if (i % batchSize == 0 && flush) {
                        session.flush();
                        session.clear();
                    }
                    i++;
                }
                if (flush) {
                    session.flush();
                    session.clear();
                }
            }
            catch (OptimisticLockException e) {
                throw new ConcurrentModificationException(getConcurrentModificationMessage(), e);
            }
        }
        return entries;
    }

    public Collection<E> mergeAll(Collection<E> entries, boolean flush) {
        if (CollectionUtils.isNotEmpty(entries)) {
            try {
                Session session = currentSession();
                int i = 0;
                Iterator<E> iter = entries.iterator();
                while (iter.hasNext()) {
                    session.merge(iter.next());
                    if (i % batchSize == 0 && flush) {
                        session.flush();
                        session.clear();
                    }
                    i++;
                }
                if (flush) {
                    session.flush();
                    session.clear();
                }
            }
            catch (OptimisticLockException e) {
                throw new ConcurrentModificationException(getConcurrentModificationMessage(), e);
            }
        }
        return entries;
    }

    public void refresh(E object) {
        getEntityManager().refresh(object);
    }

    public void lock(E object, LockModeType lmt) {
        this.getEntityManager().lock(object, lmt);
    }

    public E evict(E object) {
        currentSession().evict(object);
        return object;
    }

    public List<E> takeSubList(PageRequest pageRequest, List<E> results) {
        int fromIndex = (pageRequest.getPage() - 1) * pageRequest.getPerPage();
        if (fromIndex >= results.size()) {
            return new ArrayList<E>();
        }

        int toIndex = fromIndex + pageRequest.getPerPage();
        if (toIndex > results.size()) {
            toIndex = results.size();
        }
        // sublist returns a portion of the list between the specified fromIndex,
        // inclusive, and toIndex, exclusive.
        return results.subList(fromIndex, toIndex);
    }

    private String getConcurrentModificationMessage() {
        return i18n.tr("Request failed due to concurrent modification, please re-try.");
    }

    /**
     * Performs a direct SQL update or delete operation with a collection by breaking the collection
     * into chunks and repeatedly performing the update.
     * <p></p>
     * The parameter receiving the collection chunks must be the last parameter in the query and the
     * provided collection must support the subList operation.
     *
     * @param sql
     *  The SQL statement to execute; must be an UPDATE or DELETE operation
     *
     * @param collection
     *  The collection to be broken up into chunks
     *
     * @return
     *  the number of rows updated as a result of this query
     */
    protected int safeSQLUpdateWithCollection(String sql, List<?> collection, Object... params) {
        int count = 0;

        Session session = this.currentSession();
        SQLQuery query = session.createSQLQuery(sql);

        for (List<?> block : Iterables.partition(collection, IN_OPERATOR_BLOCK_SIZE)) {
            int index = 1;

            if (params != null) {
                for (; index <= params.length; ++index) {
                    query.setParameter(String.valueOf(index), params[index - 1]);
                }
            }
            query.setParameterList(String.valueOf(index), block);

            count += query.executeUpdate();
        }

        return count;
    }

    /**
     * While hibernate does not have limits over how many values can be used in an in clause,
     * the underlying databases sometimes do. This method builds an unbounded in clause
     * by building logical or expressions out of batches of in-clauses.
     *
     * @param expression the string expression against which we are searching values
     * @param values the values being searched for the expression
     * @return the unbounded in criterion as described above
     */
    public <T extends Object> Criterion unboundedInCriterion(String expression, Iterable<T> values) {
        Criterion criterion = null;

        if (values == null || !values.iterator().hasNext()) {
            throw new IllegalArgumentException("values is null or empty");
        }

        for (List<T> block : Iterables.partition(values, IN_OPERATOR_BLOCK_SIZE)) {
            criterion = (criterion == null) ?
                Restrictions.in(expression, block) :
                Restrictions.or(criterion, Restrictions.in(expression, block));
        }

        return criterion;
    }

    public List<E> lockAndLoadBatch(Iterable<String> ids, String entityName, String keyName) {
        List<E> result = new LinkedList<E>();

        if (ids != null && ids.iterator().hasNext()) {
            StringBuilder hql = new StringBuilder("SELECT obj FROM ")
                .append(entityName)
                .append(" obj WHERE ")
                .append(keyName)
                .append(" IN (:ids)");

            Query query = this.getEntityManager()
                .createQuery(hql.toString())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE);

            for (List<String> block : Iterables.partition(ids, IN_OPERATOR_BLOCK_SIZE)) {
                query.setParameter("ids", block);
                result.addAll((List<E>) query.getResultList());
            }
            //In some situations, even after locking the entity we
            //got stale in the entity e.g. Pool.consumed
            //This refresh reloads the entity after the lock has
            //been issued.
            for (E e : result) {
                getEntityManager().refresh(e);
            }
        }

        return result;
    }

}

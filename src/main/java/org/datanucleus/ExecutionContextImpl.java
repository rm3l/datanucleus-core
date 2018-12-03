/**********************************************************************
Copyright (c) 2006 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
2007 Xuan Baldauf - added the use of srm.findObject() to cater for different object lifecycle management policies (in RDBMS and DB4O databases)
2007 Xuan Baldauf - changes to allow the disabling of clearing of fields when transitioning from PERSISTENT_NEW to TRANSIENT.
2008 Marco Schulze - added reference counting functionality for get/acquireThreadContextInfo()
     ...
 **********************************************************************/
package org.datanucleus;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.datanucleus.api.ApiAdapter;
import org.datanucleus.cache.CacheUniqueKey;
import org.datanucleus.cache.CachedPC;
import org.datanucleus.cache.L2CachePopulateFieldManager;
import org.datanucleus.cache.Level1Cache;
import org.datanucleus.cache.Level2Cache;
import org.datanucleus.cache.SoftRefCache;
import org.datanucleus.cache.StrongRefCache;
import org.datanucleus.cache.WeakRefCache;
import org.datanucleus.enhancement.Persistable;
import org.datanucleus.exceptions.ClassNotDetachableException;
import org.datanucleus.exceptions.ClassNotPersistableException;
import org.datanucleus.exceptions.ClassNotResolvedException;
import org.datanucleus.exceptions.CommitStateTransitionException;
import org.datanucleus.exceptions.NoPersistenceInformationException;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusFatalUserException;
import org.datanucleus.exceptions.NucleusObjectNotFoundException;
import org.datanucleus.exceptions.NucleusOptimisticException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.exceptions.ObjectDetachedException;
import org.datanucleus.exceptions.RollbackStateTransitionException;
import org.datanucleus.exceptions.TransactionActiveOnCloseException;
import org.datanucleus.exceptions.TransactionNotActiveException;
import org.datanucleus.flush.FlushMode;
import org.datanucleus.flush.FlushProcess;
import org.datanucleus.flush.Operation;
import org.datanucleus.flush.OperationQueue;
import org.datanucleus.identity.DatastoreUniqueLongId;
import org.datanucleus.identity.IdentityKeyTranslator;
import org.datanucleus.identity.IdentityReference;
import org.datanucleus.identity.IdentityStringTranslator;
import org.datanucleus.identity.IdentityUtils;
import org.datanucleus.identity.DatastoreId;
import org.datanucleus.identity.SCOID;
import org.datanucleus.management.ManagerStatistics;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.IdentityType;
import org.datanucleus.metadata.TransactionType;
import org.datanucleus.metadata.UniqueMetaData;
import org.datanucleus.metadata.VersionMetaData;
import org.datanucleus.properties.BasePropertyStore;
import org.datanucleus.state.CallbackHandler;
import org.datanucleus.state.LockManager;
import org.datanucleus.state.LockManagerImpl;
import org.datanucleus.state.LockMode;
import org.datanucleus.state.NullCallbackHandler;
import org.datanucleus.state.ObjectProvider;
import org.datanucleus.state.RelationshipManager;
import org.datanucleus.store.Extent;
import org.datanucleus.store.FieldValues;
import org.datanucleus.store.StorePersistenceHandler.PersistenceBatchType;
import org.datanucleus.store.types.scostore.Store;
import org.datanucleus.util.ConcurrentReferenceHashMap;
import org.datanucleus.util.ConcurrentReferenceHashMap.ReferenceType;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;
import org.datanucleus.util.StringUtils;
import org.datanucleus.util.TypeConversionHelper;

/**
 * Manager for persistence/retrieval of objects within an execution context, equating to the work required by JDO PersistenceManager and JPA EntityManager.
 * <h3>Caching</h3>
 * <p>
 * An ExecutionContext has its own Level 1 cache. This stores objects against their identity. 
 * The Level 1 cache is typically a weak referenced map and so cached objects can be garbage collected. 
 * Objects are placed in the Level 1 cache during the transaction. 
 * The NucleusContext also has a Level 2 cache. This is used to allow cross-communication between ExecutionContexts. 
 * Objects are placed in the Level 2 cache during commit() of a transaction. If an object is deleted during a transaction then it will be removed 
 * from the Level 2 cache at commit(). If an object is no longer enlisted in the transaction at commit then it will be removed from the Level 2 cache 
 * (so we remove the chance of handing out old data).
 * </p>
 * <h3>Transactions</h3>
 * <p>
 * An ExecutionContext has a single transaction (the "current" transaction). The transaction can be "active" (if begin() has been called on it) or "inactive".
 * </p>
 * <h3>Persisted Objects</h3>
 * <p>
 * When an object involved in the current transaction it is <i>enlisted</i> (calling enlistInTransaction()).
 * Its identity is saved (in "txEnlistedIds") for use later in any "persistenceByReachability" process run at commit.
 * Any object that is passed via makePersistent() will be stored (as an identity) in "txKnownPersistedIds" and objects 
 * persisted due to reachability from these objects will also have their identity stored (in "txFlushedNewIds").
 * All of this information is used in the "persistence-by-reachability-at-commit" process which detects if some objects
 * originally persisted are no longer reachable and hence should not be persistent after all.
 * </p>
 * <h3>ObjectProvider-based storage</h3>
 * <p>
 * You may note that we have various fields here storing ObjectProvider-related information such as which ObjectProvider is embedded into which ObjectProvider etc, 
 * or the managed relations for an ObjectProvider. These are stored here to avoid adding a reference to the storage of each and every ObjectProvider, since
 * we could potentially have a very large number of ObjectProviders (and they may not use that field in the majority, but it still needs the reference). 
 * The same should be followed as a general rule when considering storing something in the ObjectProvider.
 * </p>
 * <p>
 * This class is NOT thread-safe. Use ExecutionContextThreadedImpl if you want to *attempt* to have multithreaded PM/EMs.
 * </p>
 */
public class ExecutionContextImpl implements ExecutionContext, TransactionEventListener
{
    /** Context for the persistence process. */
    PersistenceNucleusContext nucCtx;

    /** The owning PersistenceManager/EntityManager object. */
    private Object owner;

    private boolean closing = false;

    /** State variable for whether the context is closed. */
    private boolean closed;

    /** Current FetchPlan for the context. */
    private FetchPlan fetchPlan;

    /** The ClassLoader resolver to use for class loading issues. */
    private ClassLoaderResolver clr = null;

    /** Callback handler for this context. */
    private CallbackHandler callbackHandler;

    /** Level 1 Cache, essentially a Map of ObjectProvider keyed by the id. */
    protected Level1Cache cache;

    /** Properties controlling runtime behaviour (detach on commit, multithreaded, etc). */
    private final BasePropertyStore properties = new BasePropertyStore();

    /** Current transaction */
    private Transaction tx;

    /** The current flush mode, if it is defined. */
    private FlushMode flushMode = null;

    /** Cache of ObjectProviders enlisted in the current transaction, keyed by the object id. */
    private final Map<Object, ObjectProvider> enlistedOPCache = new ConcurrentReferenceHashMap<>(1, ReferenceType.STRONG, ReferenceType.WEAK);

    /** List of ObjectProviders for all current dirty objects managed by this context. */
    private final Set<ObjectProvider> dirtyOPs = new LinkedHashSet<>();

    /** List of ObjectProviders for all current dirty objects made dirty by reachability. */
    private final Set<ObjectProvider> indirectDirtyOPs = new LinkedHashSet<>();

    private OperationQueue operationQueue = null;

    private Set<ObjectProvider> nontxProcessedOPs = null;

    private boolean l2CacheEnabled = false;

    /** Map of fields of object to update in L2 cache (when attached), keyed by the id. */
    private Map<Object, BitSet> l2CacheTxFieldsToUpdateById = null;

    /** Set of ids to be Level2 cached at commit (if using L2 cache). */
    private Set l2CacheTxIds = null;

    /** Objects that were updated in L2 cache before commit, which should be evicted if rollback happens */
    private List<Object> l2CacheObjectsToEvictUponRollback = null;

    /** State variable for whether the context is currently flushing its operations. */
    private int flushing = 0;

    /** Manager for dynamic fetch groups. */
    private FetchGroupManager fetchGrpMgr;

    /** Lock manager for object-based locking. */
    private LockManager lockMgr = null;

    /** Lookup map of attached-detached objects when attaching/detaching. */
    private Map<ObjectProvider, Object> opAttachDetachObjectReferenceMap = null;

    /** Map of embedded ObjectProvider relations, keyed by owner ObjectProvider. */
    private Map<ObjectProvider, List<EmbeddedOwnerRelation>> opEmbeddedInfoByOwner = null;

    /** Map of embedded ObjectProvider relations, keyed by embedded ObjectProvider. */
    private Map<ObjectProvider, List<EmbeddedOwnerRelation>> opEmbeddedInfoByEmbedded = null;

    /**
     * Map of associated values for the ObjectProvider. This can contain anything really and is down to the StoreManager to define. 
     * For example RDBMS datastores typically put external FK info in here keyed by the mapping of the field to which it pertains.
     */
    protected Map<ObjectProvider, Map<?,?>> opAssociatedValuesMapByOP = null;

    /** Handler for "managed relations" at flush/commit. */
    private ManagedRelationsHandler managedRelationsHandler = null;

    /** Handler for "persistence-by-reachability" at commit. */
    private ReachabilityAtCommitHandler pbrAtCommitHandler = null;

    /** State variable for whether we are currently running detachAllOnCommit/detachAllOnRollback. */
    private boolean runningDetachAllOnTxnEnd = false;

    /**
     * Temporary array of ObjectProviders to detach at commit (to prevent garbage collection). 
     * Set up in preCommit() and used in postCommit().
     */
    private ObjectProvider[] detachAllOnTxnEndOPs = null;

    /** Statistics gatherer for this context. */
    private ManagerStatistics statistics = null;

    /** Set of listeners who need to know when this ExecutionContext is closing, so they can clean up. */
    private Set<ExecutionContextListener> ecListeners = null;

    /**
     * Thread-specific state information (instances of {@link ThreadContextInfo}) used where we don't want
     * to pass information down through a large number of method calls.
     */
    private ThreadLocal contextInfoThreadLocal;

    /**
     * Constructor.
     * TODO userName/password aren't currently used and we always use the PMF/EMF userName/password.
     * @param ctx NucleusContext
     * @param owner Owning object (for bytecode enhancement contract, PersistenceManager)
     * @param options Any options affecting startup
     * @throws NucleusUserException if an error occurs allocating the necessary requested components
     */
    public ExecutionContextImpl(PersistenceNucleusContext ctx, Object owner, Map<String, Object> options)
    {
        this.nucCtx = ctx;

        initialise(owner, options);

        // Set up the Level 1 Cache
        initialiseLevel1Cache();
    }

    public void initialise(Object owner, Map<String, Object> options)
    {
        // TODO Make use of the username+password (for JDO). How? need new StoreManager maybe?
        /*String userName = (String)options.get(ExecutionContext.OPTION_USERNAME);
        String password = (String)options.get(ExecutionContext.OPTION_PASSWORD);*/
        this.owner = owner;
        this.closed = false;

        // Set up class loading
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        clr = nucCtx.getClassLoaderResolver(contextLoader);
        try
        {
            ImplementationCreator ic = nucCtx.getImplementationCreator();
            if (ic != null)
            {
                clr.setRuntimeClassLoader(ic.getClassLoader());
            }
        }
        catch (Exception ex)
        {
            // do nothing
        }

        Configuration conf = nucCtx.getConfiguration();

        // copy default configuration from factory for overrideable properties
        Iterator<Map.Entry<String, Object>> propIter = conf.getManagerOverrideableProperties().entrySet().iterator();
        while (propIter.hasNext())
        {
            Map.Entry<String, Object> entry = propIter.next();
            properties.setProperty(entry.getKey().toLowerCase(Locale.ENGLISH), entry.getValue());
        }
        properties.getFrequentProperties().setDefaults(conf.getFrequentProperties());

        // Set up FetchPlan
        fetchPlan = new FetchPlan(this, clr).setMaxFetchDepth(conf.getIntProperty(PropertyNames.PROPERTY_MAX_FETCH_DEPTH));

        // Set up the transaction based on the environment
        if (TransactionType.JTA.toString().equalsIgnoreCase(conf.getStringProperty(PropertyNames.PROPERTY_TRANSACTION_TYPE)))
        {
            if (getNucleusContext().isJcaMode())
            {
                // JTA transaction under JCA
                tx = new JTAJCATransactionImpl(this, properties);
            }
            else
            {
                // JTA transaction
                boolean autoJoin = true;
                if (options != null && options.containsKey(OPTION_JTA_AUTOJOIN))
                {
                    autoJoin = Boolean.valueOf((String)options.get(OPTION_JTA_AUTOJOIN));
                }
                tx = new JTATransactionImpl(this, autoJoin, properties);
            }
        }
        else
        {
            // Local transaction
            tx = new TransactionImpl(this, properties);
        }
        if (NucleusLogger.PERSISTENCE.isDebugEnabled())
        {
            NucleusLogger.PERSISTENCE.debug(Localiser.msg("010000", this, nucCtx.getStoreManager(), tx));
        }

        if (nucCtx.statisticsEnabled())
        {
            statistics = new ManagerStatistics(nucCtx.getJMXManager(), nucCtx.getStatistics());
        }

        contextInfoThreadLocal = new ThreadLocal()
        {
            protected Object initialValue()
            {
                return new ThreadContextInfo();
            }
        };

        if (properties.getBooleanProperty(PropertyNames.PROPERTY_MANAGE_RELATIONSHIPS))
        {
            managedRelationsHandler = new ManagedRelationsHandler(properties.getBooleanProperty(PropertyNames.PROPERTY_MANAGE_RELATIONSHIPS_CHECKS));
        }

        if (properties.getFrequentProperties().getReachabilityAtCommit())
        {
            pbrAtCommitHandler = new ReachabilityAtCommitHandler(this);
        }

        lockMgr = new LockManagerImpl(this);

        l2CacheObjectsToEvictUponRollback = null;

        setLevel2Cache(true);
    }

    /**
     * Method to close the context.
     */
    public void close()
    {
        if (closed)
        {
            throw new NucleusUserException(Localiser.msg("010002"));
        }

        if (tx.getIsActive())
        {
            String closeActionTxAction = nucCtx.getConfiguration().getStringProperty(PropertyNames.PROPERTY_EXECUTION_CONTEXT_CLOSE_ACTIVE_TX_ACTION);
            if (closeActionTxAction != null)
            {
                if (closeActionTxAction.equalsIgnoreCase("exception"))
                {
                    throw new TransactionActiveOnCloseException(this);
                }
                else if (closeActionTxAction.equalsIgnoreCase("rollback"))
                {
                    NucleusLogger.GENERAL.warn("ExecutionContext closed with active transaction, so rolling back the active transaction");
                    tx.rollback();
                }
            }
        }

        // Commit any outstanding non-tx updates
        if (!dirtyOPs.isEmpty() && tx.getNontransactionalWrite())
        {
            if (isNonTxAtomic())
            {
                // TODO Remove this when all mutator operations handle it atomically
                // Process as nontransactional update
                processNontransactionalUpdate();
            }
            else
            {
                // Process within a transaction
                try
                {
                    tx.begin();
                    tx.commit();
                }
                finally
                {
                    if (tx.isActive())
                    {
                        tx.rollback();
                    }
                }
            }
        }

        if (properties.getFrequentProperties().getDetachOnClose() && cache != null && !cache.isEmpty())
        {
            // "Detach-on-Close", detaching all currently cached objects
            // TODO This will remove objects from the L1 cache one-by-one. Is there a possibility for optimisation? See also AttachDetachTest.testDetachOnClose
            NucleusLogger.PERSISTENCE.debug(Localiser.msg("010011"));
            List<ObjectProvider> toDetach = new ArrayList<>(cache.values());

            try
            {
                if (!tx.getNontransactionalRead())
                {
                    tx.begin();
                }

                for (ObjectProvider op : toDetach)
                {
                    if (op != null && op.getObject() != null && !op.getExecutionContext().getApiAdapter().isDeleted(op.getObject()) && op.getExternalObjectId() != null)
                    {
                        // If the object is deleted then no point detaching. An object can be in L1 cache if transient and passed in to a query as a param for example
                        try
                        {
                            op.detach(new DetachState(getApiAdapter()));
                        }
                        catch (NucleusObjectNotFoundException onfe)
                        {
                            // Catch exceptions for any objects that are deleted in other managers whilst having this open
                        }
                    }
                }

                if (!tx.getNontransactionalRead())
                {
                    tx.commit();
                }
            }
            finally
            {
                if (!tx.getNontransactionalRead())
                {
                    if (tx.isActive())
                    {
                        tx.rollback();
                    }
                }
            }
            NucleusLogger.PERSISTENCE.debug(Localiser.msg("010012"));
        }

        // Call all listeners to do their clean up TODO Why is this here and not after "disconnect remaining resources" or before "detachOnClose"?
        ExecutionContext.LifecycleListener[] listener = nucCtx.getExecutionContextListeners();
        for (int i=0; i<listener.length; i++)
        {
            listener[i].preClose(this);
        }

        closing = true;

        // Disconnect remaining resources
        if (cache != null && !cache.isEmpty())
        {
            // Clear out the cache (use separate list since sm.disconnect will remove the object from "cache" so we avoid any ConcurrentModification issues)
            Collection<ObjectProvider> cachedOPsClone = new HashSet<>(cache.values());
            for (ObjectProvider op : cachedOPsClone)
            {
                if (op != null)
                {
                    // Remove it from any transaction
                    op.disconnect();
                }
                else
                {
                    NucleusLogger.CACHE.error(">> EC.close L1Cache op IS NULL!");
                }
            }

            cache.clear();
            if (NucleusLogger.CACHE.isDebugEnabled())
            {
                NucleusLogger.CACHE.debug(Localiser.msg("003011"));
            }
        }
        else
        {
            // TODO If there is no cache we need a way for ObjectProviders to be disconnected; have ObjectProvider as listener for EC close? (ecListeners below)
        }

        // Clear out lifecycle listeners that were registered
        closeCallbackHandler();

        if (ecListeners != null)
        {
            // Inform all interested parties that we are about to close
            Set<ExecutionContextListener> listeners = new HashSet<>(ecListeners);
            for (ExecutionContextListener lstr : listeners)
            {
                lstr.executionContextClosing(this);
            }
            ecListeners.clear();
            ecListeners = null;
        }

        // Reset the Fetch Plan to its DFG setting
        fetchPlan.clearGroups().addGroup(FetchPlan.DEFAULT);

        if (statistics != null)
        {
            statistics.close();
            statistics = null;
        }

        enlistedOPCache.clear();
        dirtyOPs.clear();
        indirectDirtyOPs.clear();

        if (nontxProcessedOPs != null)
        {
            nontxProcessedOPs.clear();
            nontxProcessedOPs = null;
        }
        if (managedRelationsHandler != null)
        {
            managedRelationsHandler.clear();
        }
        if (l2CacheTxIds != null)
        {
            l2CacheTxIds.clear();
        }
        if (l2CacheTxFieldsToUpdateById != null)
        {
            l2CacheTxFieldsToUpdateById.clear();
        }
        if (pbrAtCommitHandler != null)
        {
            pbrAtCommitHandler.clear();
        }
        if (opEmbeddedInfoByOwner != null)
        {
            opEmbeddedInfoByOwner.clear();
            opEmbeddedInfoByOwner = null;
        }
        if (opEmbeddedInfoByEmbedded != null)
        {
            opEmbeddedInfoByEmbedded.clear();
            opEmbeddedInfoByEmbedded = null;
        }
        if (opAssociatedValuesMapByOP != null)
        {
            opAssociatedValuesMapByOP.clear();
            opAssociatedValuesMapByOP = null;
        }

        l2CacheObjectsToEvictUponRollback = null;

        closing = false;
        closed = true;
        tx.close(); // Close the transaction
        tx = null;
        owner = null;

        if (NucleusLogger.PERSISTENCE.isDebugEnabled())
        {
            NucleusLogger.PERSISTENCE.debug(Localiser.msg("010001", this));
        }

        // Hand back to the pool for reuse
        nucCtx.getExecutionContextPool().checkIn(this);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#registerExecutionContextListener(org.datanucleus.ExecutionContextListener)
     */
    @Override
    public void registerExecutionContextListener(ExecutionContextListener listener)
    {
        if (ecListeners == null)
        {
            ecListeners = new HashSet<>();
        }
        ecListeners.add(listener);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#deregisterExecutionContextListener(org.datanucleus.ExecutionContextListener)
     */
    @Override
    public void deregisterExecutionContextListener(ExecutionContextListener listener)
    {
        if (ecListeners != null)
        {
            ecListeners.remove(listener);
        }
    }

    /**
     * Method to set whether we are supporting the Level2 Cache with this ExecutionContext
     * Note that if the NucleusContext has no Level2 Cache enabled then you cannot turn it on here.
     * @param flag Whether to enable/disable it
     */
    protected void setLevel2Cache(boolean flag)
    {
        if (flag && nucCtx.hasLevel2Cache() && !l2CacheEnabled)
        {
            // Create temporary storage to handle objects needing L2 caching after txn
            l2CacheTxIds = new HashSet();
            l2CacheTxFieldsToUpdateById = new HashMap<>();
            l2CacheEnabled = true;
        }
        else if (!flag && l2CacheEnabled)
        {
            // Remove temporary storage for L2 caching
            if (NucleusLogger.CACHE.isDebugEnabled())
            {
                NucleusLogger.CACHE.debug("Disabling L2 caching for " + this);
            }
            l2CacheTxIds.clear();
            l2CacheTxIds = null;
            l2CacheTxFieldsToUpdateById.clear();
            l2CacheTxFieldsToUpdateById = null;
            l2CacheEnabled = false;
        }
    }

    /**
     * Accessor for whether this context is closed.
     * @return Whether this manager is closed.
     */
    public boolean isClosed()
    {
        return closed;
    }

    /**
     * Context info for a particular thread. Can be used for storing state information for the current
     * thread where we don't want to pass it through large numbers of method calls (e.g persistence by
     * reachability) where such argument passing would damage the structure of DataNucleus.
     */
    static class ThreadContextInfo
    {
        int referenceCounter = 0;

        /** Map of the owner of an attached object, keyed by the object. Present when performing attachment. */
        Map<Object, ObjectProvider> attachedOwnerByObject = null;

        /** Map of attached PC object keyed by the id. Present when performing a attachment. */
        Map attachedPCById = null;

        boolean merging = false;

        boolean nontxPersistDelete = false;
    }

    /**
     * Accessor for the thread context information, for the current thread.
     * If the current thread is not present, will add an info context for it.
     * <p>
     * You must call {@link #releaseThreadContextInfo()} when you don't need it anymore,
     * since we use reference counting. Use a try...finally-block for this purpose.
     * </p>
     *
     * @return The thread context information
     * @see #getThreadContextInfo()
     */
    protected ThreadContextInfo acquireThreadContextInfo()
    {
        ThreadContextInfo threadInfo = (ThreadContextInfo) contextInfoThreadLocal.get();
        ++threadInfo.referenceCounter;
        return threadInfo;
    }

    /**
     * Get the current ThreadContextInfo assigned to the current thread without changing the
     * reference counter.
     * @return the thread context information
     * @see #acquireThreadContextInfo()
     */
    protected ThreadContextInfo getThreadContextInfo()
    {
        return (ThreadContextInfo) contextInfoThreadLocal.get();
    }

    /**
     * Method to remove the current thread context info for the current thread, after
     * the reference counter reached 0. This method decrements a reference counter (per thread), that
     * is incremented by {@link #acquireThreadContextInfo()}.
     *
     * @see #acquireThreadContextInfo()
     */
    protected void releaseThreadContextInfo()
    {
        ThreadContextInfo threadInfo = (ThreadContextInfo) contextInfoThreadLocal.get();
        if (--threadInfo.referenceCounter <= 0) // might be -1, if acquireThreadContextInfo was not called. shall we throw an exception in this case?
        {
            threadInfo.referenceCounter = 0; // just to be 100% sure, we never have a negative reference counter.

            if (threadInfo.attachedOwnerByObject != null)
                threadInfo.attachedOwnerByObject.clear();
            threadInfo.attachedOwnerByObject = null;

            if (threadInfo.attachedPCById != null)
                threadInfo.attachedPCById.clear();
            threadInfo.attachedPCById = null;

            contextInfoThreadLocal.remove();
        }
    }

    public void transactionStarted()
    {
        getStoreManager().transactionStarted(this);
        postBegin();
    }
    public void transactionPreFlush() {}
    public void transactionFlushed() {}
    public void transactionPreCommit()
    {
        preCommit();
    }
    public void transactionCommitted()
    {
        getStoreManager().transactionCommitted(this);
        postCommit();
    }
    public void transactionPreRollBack()
    {
        preRollback();
    }
    public void transactionRolledBack()
    {
        getStoreManager().transactionRolledBack(this);
        postRollback();
    }
    public void transactionEnded() {}
    public void transactionSetSavepoint(String name) {}
    public void transactionReleaseSavepoint(String name) {}
    public void transactionRollbackToSavepoint(String name) {}

    /* (non-Javadoc)
     * @see org.datanucleus.store.ExecutionContext#getStatistics()
     */
    public ManagerStatistics getStatistics()
    {
        return statistics;
    }

    /**
     * Method to initialise the L1 cache.
     * @throws NucleusUserException if an error occurs setting up the L1 cache
     */
    protected void initialiseLevel1Cache()
    {
        String level1Type = nucCtx.getConfiguration().getStringProperty(PropertyNames.PROPERTY_CACHE_L1_TYPE);
        if (Level1Cache.NONE_NAME.equalsIgnoreCase(level1Type))
        {
            return;
        }
        else if (SoftRefCache.NAME.equalsIgnoreCase(level1Type))
        {
            cache = new SoftRefCache();
        }
        else if (WeakRefCache.NAME.equalsIgnoreCase(level1Type))
        {
            cache = new WeakRefCache();
        }
        else if (StrongRefCache.NAME.equalsIgnoreCase(level1Type))
        {
            cache = new StrongRefCache();
        }
        else
        {
            // Find the L1 cache class name from its plugin name
            String level1ClassName = getNucleusContext().getPluginManager().getAttributeValueForExtension("org.datanucleus.cache_level1", "name", level1Type, "class-name");
            if (level1ClassName == null)
            {
                // Plugin of this name not found
                throw new NucleusUserException(Localiser.msg("003001", level1Type)).setFatal();
            }

            try
            {
                // Create an instance of the L1 Cache
                cache = (Level1Cache)getNucleusContext().getPluginManager().createExecutableExtension(
                    "org.datanucleus.cache_level1", "name", level1Type, "class-name", null, null);
                if (NucleusLogger.CACHE.isDebugEnabled())
                {
                    NucleusLogger.CACHE.debug(Localiser.msg("003003", level1Type));
                }
            }
            catch (Exception e)
            {
                // Class name for this L1 cache plugin is not found!
                throw new NucleusUserException(Localiser.msg("003002", level1Type, level1ClassName),e).setFatal();
            }
        }
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#getLevel1Cache()
     */
    @Override
    public Level1Cache getLevel1Cache()
    {
        return cache;
    }

    public ClassLoaderResolver getClassLoaderResolver()
    {
        return clr;
    }

    public LockManager getLockManager()
    {
        return lockMgr;
    }

    public FetchPlan getFetchPlan()
    {
        assertIsOpen();
        return fetchPlan;
    }

    public PersistenceNucleusContext getNucleusContext()
    {
        return nucCtx;
    }

    /**
     * Accessor for the owner of this ExecutionContext. This will typically be PersistenceManager (JDO) or EntityManager (JPA).
     * @return The owner
     */
    public Object getOwner()
    {
        return owner;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.ExecutionContext#setProperties(java.util.Map)
     */
    public void setProperties(Map props)
    {
        if (props == null)
        {
            return;
        }
        Iterator<Entry<Object, Object>> entryIter = props.entrySet().iterator();
        while (entryIter.hasNext())
        {
            Map.Entry entry = entryIter.next();
            if (entry.getKey() instanceof String)
            {
                setProperty((String)entry.getKey(), entry.getValue());
            }
        }
    }

    public void setProperty(String name, Object value)
    {
        /*if (tx.isActive())
        {
            // Don't allow change of options during a transaction
            if (NucleusLogger.PERSISTENCE.isDebugEnabled())
            {
                NucleusLogger.PERSISTENCE.debug("Attempt to set property " + name + " during a transaction. Ignored");
            }
            return;
        }*/
        if (name.equalsIgnoreCase(PropertyNames.PROPERTY_MANAGE_RELATIONSHIPS))
        {
            if ("false".equalsIgnoreCase((String)value))
            {
                // Turn off managed relations if enabled
                managedRelationsHandler = null;
            }
            else if ("true".equalsIgnoreCase((String)value))
            {
                managedRelationsHandler = new ManagedRelationsHandler(properties.getBooleanProperty(PropertyNames.PROPERTY_MANAGE_RELATIONSHIPS_CHECKS));
            }
        }
        else if (name.equalsIgnoreCase(PropertyNames.PROPERTY_MANAGE_RELATIONSHIPS_CHECKS))
        {
            if (managedRelationsHandler != null)
            {
                Boolean checks = Boolean.valueOf((String)value);
                if (checks == Boolean.TRUE)
                {
                    managedRelationsHandler.setPerformChecks(true);
                }
                else if (checks == Boolean.FALSE)
                {
                    managedRelationsHandler.setPerformChecks(false);
                }
            }
        }
        else if (name.equalsIgnoreCase(PropertyNames.PROPERTY_PERSISTENCE_BY_REACHABILITY_AT_COMMIT))
        {
            if (pbrAtCommitHandler != null)
            {
                if ("false".equalsIgnoreCase((String)value))
                {
                    // Turn off PBR at commit if enabled
                    pbrAtCommitHandler = null;
                }
                else if ("true".equalsIgnoreCase((String)value))
                {
                    pbrAtCommitHandler = new ReachabilityAtCommitHandler(this);
                }
            }
        }
        else if (name.equalsIgnoreCase(PropertyNames.PROPERTY_FLUSH_MODE))
        {
            flushMode = FlushMode.getFlushModeForString((String) value);
            return;
        }

        if (properties.hasProperty(name.toLowerCase(Locale.ENGLISH)))
        {
            String intName = getNucleusContext().getConfiguration().getInternalNameForProperty(name);
            getNucleusContext().getConfiguration().validatePropertyValue(intName, value);
            properties.setProperty(intName.toLowerCase(Locale.ENGLISH), value);
        }
        else if (name.equalsIgnoreCase(PropertyNames.PROPERTY_CACHE_L2_TYPE))
        {
            // Allow the user to turn off L2 caching with this ExecutionContext
            if ("none".equalsIgnoreCase((String)value))
            {
                // Turn off L2 cache
                setLevel2Cache(false);
            }
        }
        else
        {
            String intName = getNucleusContext().getConfiguration().getInternalNameForProperty(name);
            if (intName != null && !intName.equalsIgnoreCase(name))
            {
                getNucleusContext().getConfiguration().validatePropertyValue(intName, value);
                properties.setProperty(intName.toLowerCase(Locale.ENGLISH), value);
            }
            else
            {
                NucleusLogger.PERSISTENCE.warn("Attempt to set property \"" + name + "\" on PM/EM yet this is not supported. Ignored");
            }
        }
        if (name.equalsIgnoreCase(PropertyNames.PROPERTY_SERIALIZE_READ))
        {
            // Apply value to transaction
            tx.setSerializeRead(getBooleanProperty(PropertyNames.PROPERTY_SERIALIZE_READ));
        }
    }

    public Map<String, Object> getProperties()
    {
        Map<String, Object> props = new HashMap<String, Object>();
        Iterator<Map.Entry<String, Object>> propertiesIter = properties.getProperties().entrySet().iterator();
        while (propertiesIter.hasNext())
        {
            Map.Entry<String, Object> entry = propertiesIter.next();
            props.put(nucCtx.getConfiguration().getCaseSensitiveNameForPropertyName(entry.getKey()), entry.getValue());
        }
        return props;
    }

    public Boolean getBooleanProperty(String name)
    {
        if (properties.hasProperty(name.toLowerCase(Locale.ENGLISH)))
        {
            assertIsOpen();
            return properties.getBooleanProperty(getNucleusContext().getConfiguration().getInternalNameForProperty(name));
        }
        return null;
    }

    public Integer getIntProperty(String name)
    {
        if (properties.hasProperty(name.toLowerCase(Locale.ENGLISH)))
        {
            assertIsOpen();
            return properties.getIntProperty(getNucleusContext().getConfiguration().getInternalNameForProperty(name));
        }
        return null;
    }

    public String getStringProperty(String name)
    {
        if (properties.hasProperty(name.toLowerCase(Locale.ENGLISH)))
        {
            assertIsOpen();
            return properties.getStringProperty(getNucleusContext().getConfiguration().getInternalNameForProperty(name));
        }
        return null;
    }

    public Object getProperty(String name)
    {
        if (properties.hasProperty(name.toLowerCase(Locale.ENGLISH)))
        {
            assertIsOpen();
            return properties.getProperty(getNucleusContext().getConfiguration().getInternalNameForProperty(name).toLowerCase(Locale.ENGLISH));
        }
        return null;
    }

    public Set<String> getSupportedProperties()
    {
        return nucCtx.getConfiguration().getManagedOverrideablePropertyNames();
    }

    /**
     * Accessor for whether the usage is multi-threaded.
     * @return False
     */
    public boolean getMultithreaded()
    {
        return false;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#getFlushMode()
     */
    @Override
    public FlushMode getFlushMode()
    {
        return flushMode;
    }

    /**
     * Whether the datastore operations are delayed until commit/flush. 
     * In optimistic transactions this is automatically enabled. In datastore transactions there is a persistence property to enable it.
     * If we are committing/flushing then will return false since the delay is no longer required.
     * @return true if datastore operations are delayed until commit/flush
     */
    public boolean isDelayDatastoreOperationsEnabled()
    {
        if (!tx.isActive())
        {
            // Non-transactional usage
            if (isFlushing())
            {
                return false;
            }
            return !isNonTxAtomic();
        }

        // Transactional usage
        if (isFlushing() || tx.isCommitting())
        {
            // Already sending to the datastore so return false to not confuse things
            return false;
        }

        if (flushMode == FlushMode.AUTO)
        {
            return false;
        }
        else if (flushMode == FlushMode.MANUAL || flushMode == FlushMode.QUERY)
        {
            return true;
        }

        // Default behaviour - delay ops with optimistic, and don't (currently) with datastore txns
        return tx.getOptimistic();
    }

    /**
     * Tests whether this persistable object is in the process of being inserted.
     * @param pc the object to verify the status
     * @return true if this instance is inserting.
     */
    public boolean isInserting(Object pc)
    {
        ObjectProvider op = findObjectProvider(pc);
        if (op == null)
        {
            return false;
        }
        return op.isInserting();
    }

    /**
     * Accessor for the current transaction.
     * @return The transaction
     */
    public Transaction getTransaction()
    {
        assertIsOpen();
        return tx;
    }

    /**
     * Method to enlist the specified ObjectProvider in the current transaction.
     * @param op The ObjectProvider
     */
    public void enlistInTransaction(ObjectProvider op)
    {
        assertActiveTransaction();

        if (pbrAtCommitHandler != null && tx.isActive())
        {
            if (getApiAdapter().isNew(op.getObject()))
            {
                // Add this object to the list of new objects in this transaction
                pbrAtCommitHandler.addFlushedNewObject(op.getInternalObjectId());
            }
            else if (getApiAdapter().isPersistent(op.getObject()) && !getApiAdapter().isDeleted(op.getObject()))
            {
                // Add this object to the list of known valid persisted objects (unless it is a known new object)
                if (!pbrAtCommitHandler.isObjectFlushedNew(op.getInternalObjectId()))
                {
                    pbrAtCommitHandler.addPersistedObject(op.getInternalObjectId());
                }
            }

            // Add the object to those enlisted
            if (!pbrAtCommitHandler.isExecuting())
            {
                // Keep a note of the id for use by persistence-by-reachability-at-commit
                pbrAtCommitHandler.addEnlistedObject(op.getInternalObjectId());
            }
        }

        if (NucleusLogger.TRANSACTION.isDebugEnabled())
        {
            NucleusLogger.TRANSACTION.debug(Localiser.msg("015017", StringUtils.toJVMIDString(op.getObject()), op.getInternalObjectId().toString()));
        }
        enlistedOPCache.put(op.getInternalObjectId(), op);
    }

    /**
     * Method to evict the specified ObjectProvider from the current transaction.
     * @param op The ObjectProvider
     */
    public void evictFromTransaction(ObjectProvider op)
    {
        if (enlistedOPCache.remove(op.getInternalObjectId()) != null)
        {
            if (NucleusLogger.TRANSACTION.isDebugEnabled())
            {
                NucleusLogger.TRANSACTION.debug(Localiser.msg("015019", StringUtils.toJVMIDString(op.getObject()), 
                    IdentityUtils.getPersistableIdentityForId(op.getInternalObjectId())));
            }
        }
    }

    /**
     * Method to return if an object is enlisted in the current transaction.
     * This is only of use when running "persistence-by-reachability" at commit.
     * @param id Identity for the object
     * @return Whether it is enlisted in the current transaction
     */
    public boolean isEnlistedInTransaction(Object id)
    {
        if (pbrAtCommitHandler == null || !tx.isActive())
        {
            return false;
        }

        if (id == null)
        {
            return false;
        }
        return pbrAtCommitHandler.isObjectEnlisted(id);
    }

    /**
     * Convenience method to return the attached object for the specified id if one exists.
     * Returns null if there is no currently enlisted/cached object with the specified id.
     * @param id The id
     * @return The attached object
     */
    public Object getAttachedObjectForId(Object id)
    {
        ObjectProvider op = enlistedOPCache.get(id);
        if (op != null)
        {
            return op.getObject();
        }
        if (cache != null)
        {
            op = cache.get(id);
            if (op != null)
            {
                return op.getObject();
            }
        }
        return null;
    }

    /**
     * Method to add the object managed by the specified ObjectProvider to the (L1) cache.
     * @param op The ObjectProvider
     */
    public void addObjectProviderToCache(ObjectProvider op)
    {
        // Add to the Level 1 Cache
        putObjectIntoLevel1Cache(op);
    }

    /**
     * Method to remove the object managed by the specified ObjectProvider from the cache.
     * @param op The ObjectProvider
     */
    public void removeObjectProviderFromCache(ObjectProvider op)
    {
        if (closing)
        {
            // All state variables will be reset in bulk in close()
            return;
        }

        // Remove from the Level 1 Cache
        removeObjectFromLevel1Cache(op.getInternalObjectId());

        // Remove it from any transaction
        enlistedOPCache.remove(op.getInternalObjectId());

        if (opEmbeddedInfoByEmbedded != null)
        {
            // Remove any owner-embedded relations for this
            List<EmbeddedOwnerRelation> embRels = opEmbeddedInfoByEmbedded.get(op);
            if (embRels != null)
            {
                for (EmbeddedOwnerRelation rel : embRels)
                {
                    // Remove from owner lookup too
                    opEmbeddedInfoByOwner.remove(rel.getOwnerOP());
                }
                opEmbeddedInfoByEmbedded.remove(op);
            }
        }
        if (opEmbeddedInfoByOwner != null)
        {
            // Remove any owner-embedded relations for this
            List<EmbeddedOwnerRelation> embRels = opEmbeddedInfoByOwner.get(op);
            if (embRels != null)
            {
                for (EmbeddedOwnerRelation rel : embRels)
                {
                    // Remove from embedded lookup too
                    opEmbeddedInfoByEmbedded.remove(rel.getEmbeddedOP());
                }
                opEmbeddedInfoByOwner.remove(op);
            }
        }
        if (opAssociatedValuesMapByOP != null)
        {
            opAssociatedValuesMapByOP.remove(op);
        }
        setAttachDetachReferencedObject(op, null);
    }

    /**
     * Method to return the ObjectProvider for an object (if managed).
     * @param pc The object we are checking
     * @return The ObjectProvider, null if not found.
     * @throws NucleusUserException if the persistable object is managed by a different ExecutionContext
     */
    public ObjectProvider findObjectProvider(Object pc)
    {
        ObjectProvider op = (ObjectProvider) getApiAdapter().getStateManager(pc);
        if (op != null)
        {
            ExecutionContext ec = op.getExecutionContext();
            if (ec != null && this != ec)
            {
                throw new NucleusUserException(Localiser.msg("010007", getApiAdapter().getIdForObject(pc)));
            }
        }
        return op;
    }

    /**
     * Find the ObjectProvider for the specified object, persisting it if required.
     * @param pc The persistable object
     * @param persist persists the object if not yet persisted. 
     * @return The ObjectProvider
     */
    public ObjectProvider findObjectProvider(Object pc, boolean persist)
    {
        ObjectProvider op = findObjectProvider(pc);
        if (op == null && persist)
        {
            int objectType = ObjectProvider.PC;
            Object object2 = persistObjectInternal(pc, null, null, -1, objectType);
            op = findObjectProvider(object2);
        }
        else if (op == null)
        {
            return null;
        }
        return op;
    }

    public ObjectProvider findObjectProviderForEmbedded(Object value, ObjectProvider owner, AbstractMemberMetaData mmd)
    {
        ObjectProvider embeddedOP = findObjectProvider(value);
        if (embeddedOP == null)
        {
            // Assign an ObjectProvider to manage our embedded object
            embeddedOP = nucCtx.getObjectProviderFactory().newForEmbedded(this, value, false, owner,
                owner.getClassMetaData().getMetaDataForMember(mmd.getName()).getAbsoluteFieldNumber());
        }
        ObjectProvider[] embOwnerOPs = getOwnersForEmbeddedObjectProvider(embeddedOP);
        if (embOwnerOPs == null || embOwnerOPs.length == 0)
        {
            int absoluteFieldNumber = owner.getClassMetaData().getMetaDataForMember(mmd.getName()).getAbsoluteFieldNumber();
            registerEmbeddedRelation(owner, absoluteFieldNumber, embeddedOP);
            embeddedOP.setPcObjectType(ObjectProvider.EMBEDDED_PC);
        }
        return embeddedOP;
    }

    public ObjectProvider findObjectProviderOfOwnerForAttachingObject(Object pc)
    {
        ThreadContextInfo threadInfo = acquireThreadContextInfo();
        try
        {
            if (threadInfo.attachedOwnerByObject == null)
            {
                return null;
            }
            return threadInfo.attachedOwnerByObject.get(pc);
        }
        finally
        {
            releaseThreadContextInfo();
        }
    }

    /**
     * Convenience method for whether any non-tx operations are considered "atomic" (i.e auto-commit).
     * @return Whether atomic non-tx behaviour
     */
    private boolean isNonTxAtomic()
    {
        return getNucleusContext().getConfiguration().getBooleanProperty(PropertyNames.PROPERTY_TRANSACTION_NONTX_ATOMIC);
    }

    /**
     * Method called when a non-tx update has been performed (via setter call on the persistable object, or via
     * use of mutator methods of a field). Only hands the update across to be "committed" if not part of an owning 
     * persist/delete call.
     */
    public void processNontransactionalUpdate()
    {
        if (tx.isActive() || !tx.getNontransactionalWrite() || !tx.getNontransactionalWriteAutoCommit())
        {
            return;
        }

        ThreadContextInfo threadInfo = acquireThreadContextInfo();
        try
        {
            if (threadInfo.nontxPersistDelete)
            {
                // Invoked from persist/delete so ignore as we are handling it
                return;
            }

            processNontransactionalAtomicChanges();
        }
        finally
        {
            releaseThreadContextInfo();
        }
    }

    /**
     * Handler for all outstanding changes to be "committed" atomically.
     * If a transaction is active, non-tx writes are disabled, or atomic updates not enabled then will do nothing.
     * Otherwise will flush any updates that are outstanding (updates to an object), will perform detachAllOnCommit
     * if enabled (so user always has detached objects), update objects in any L2 cache, and migrates any 
     * objects through lifecycle changes.
     * Is similar in content to "flush"+"preCommit"+"postCommit"
     * Note that this handling for updates is not part of standard JDO which expects non-tx updates to migrate an 
     * object to P_NONTRANS_DIRTY rather than committing it directly.
     * TODO If any update fails we should throw the appropriate exception for the API
     */
    protected void processNontransactionalAtomicChanges()
    {
        if (tx.isActive() || !tx.getNontransactionalWrite() || !tx.getNontransactionalWriteAutoCommit())
        {
            return;
        }

        if (!dirtyOPs.isEmpty())
        {
            // Make sure all non-tx dirty objects are enlisted so they get lifecycle changes
            for (ObjectProvider op : dirtyOPs)
            {
                if (NucleusLogger.TRANSACTION.isDebugEnabled())
                {
                    NucleusLogger.TRANSACTION.debug(Localiser.msg("015017", StringUtils.toJVMIDString(op.getObject()), op.getInternalObjectId().toString()));
                }
                enlistedOPCache.put(op.getInternalObjectId(), op);
            }

            // Flush any outstanding changes to the datastore
            flushInternal(true);

            if (l2CacheEnabled)
            {
                // L2 caching of enlisted objects
                performLevel2CacheUpdateAtCommit();
            }

            if (properties.getFrequentProperties().getDetachAllOnCommit())
            {
                // "detach-on-commit"
                performDetachAllOnTxnEndPreparation();
                performDetachAllOnTxnEnd();
            }

            // Make sure lifecycle changes take place to all "enlisted" objects
            List failures = null;
            try
            {
                // "commit" all enlisted ObjectProviders
                ApiAdapter api = getApiAdapter();
                ObjectProvider[] ops = enlistedOPCache.values().toArray(new ObjectProvider[enlistedOPCache.size()]);
                for (int i = 0; i < ops.length; ++i)
                {
                    try
                    {
                        // Run through "postCommit" to migrate the lifecycle state
                        if (ops[i] != null && ops[i].getObject() != null && api.isPersistent(ops[i].getObject()) && api.isDirty(ops[i].getObject()))
                        {
                            ops[i].postCommit(getTransaction());
                        }
                        else
                        {
                            NucleusLogger.PERSISTENCE.debug(">> Atomic nontransactional processing : Not performing postCommit on " + ops[i]);
                        }
                    }
                    catch (RuntimeException e)
                    {
                        if (failures == null)
                        {
                            failures = new ArrayList();
                        }
                        failures.add(e);
                    }
                }
            }
            finally
            {
                resetTransactionalVariables();
            }
            if (failures != null && !failures.isEmpty())
            {
                throw new CommitStateTransitionException((Exception[]) failures.toArray(new Exception[failures.size()]));
            }
        }

        if (nontxProcessedOPs != null && !nontxProcessedOPs.isEmpty())
        {
            for (ObjectProvider op : nontxProcessedOPs)
            {
                if (op != null && op.getLifecycleState() != null && op.getLifecycleState().isDeleted())
                {
                    removeObjectFromLevel1Cache(op.getInternalObjectId());
                    removeObjectFromLevel2Cache(op.getInternalObjectId());
                }
            }
            nontxProcessedOPs.clear();
        }
    }

    // ----------------------------- Lifecycle Methods ------------------------------------

    /**
     * Internal method to evict an object from L1 cache.
     * @param obj The object
     * @throws NucleusException if an error occurs evicting the object
     */
    public void evictObject(Object obj)
    {
        if (obj == null)
        {
            return;
        }

        try
        {
            clr.setPrimary(obj.getClass().getClassLoader());
            assertClassPersistable(obj.getClass());
            assertNotDetached(obj);

            // we do not directly remove from cache level 1 here. The cache level 1 will be evicted 
            // automatically on garbage collection, if the object can be evicted. it means not all
            // jdo states allows the object to be evicted.
            ObjectProvider op = findObjectProvider(obj);
            if (op == null)
            {
                throw new NucleusUserException(Localiser.msg("010048", StringUtils.toJVMIDString(obj), getApiAdapter().getIdForObject(obj), "evict"));
            }
            op.evict();
        }
        finally
        {
            clr.unsetPrimary();
        }
    }

    /**
     * Method to evict all objects of the specified type (and optionaly its subclasses) that are present in the L1 cache.
     * @param cls Type of persistable object
     * @param subclasses Whether to include subclasses
     */
    public void evictObjects(Class cls, boolean subclasses)
    {
        if (cache != null)
        {
            Set<ObjectProvider> opsToEvict = new HashSet<>(cache.values());
            for (ObjectProvider op : opsToEvict)
            {
                Object pc = op.getObject();
                boolean evict = false;
                if (!subclasses && pc.getClass() == cls)
                {
                    evict = true;
                }
                else if (subclasses && cls.isAssignableFrom(pc.getClass()))
                {
                    evict = true;
                }

                if (evict)
                {
                    op.evict();
                    removeObjectFromLevel1Cache(getApiAdapter().getIdForObject(pc));
                }
            }
        }
    }

    /**
     * Method to evict all current objects from L1 cache.
     */
    public void evictAllObjects()
    {
        if (cache != null && !cache.isEmpty())
        {
            // TODO All persistent non-transactional instances should be evicted here, but not yet supported

            // Evict ObjectProviders and remove objects from cache. Performed in separate loop to avoid ConcurrentModificationException
            Set<ObjectProvider> opsToEvict = new HashSet(cache.values());
            for (ObjectProvider op : opsToEvict)
            {
                if (op != null)
                {
                    op.evict();
                }
                else
                {
                    NucleusLogger.CACHE.error(">> EC.evictAllObjects L1Cache op IS NULL!");
                }
            }

            cache.clear();
            if (NucleusLogger.CACHE.isDebugEnabled())
            {
                NucleusLogger.CACHE.debug(Localiser.msg("003011"));
            }
        }
    }

    /**
     * Method to do a refresh of an object, updating it from its datastore representation. 
     * Also updates the object in the L1/L2 caches.
     * @param obj The Object
     */
    public void refreshObject(Object obj)
    {
        if (obj == null)
        {
            return;
        }

        try
        {
            clr.setPrimary(obj.getClass().getClassLoader());
            assertClassPersistable(obj.getClass());
            assertNotDetached(obj);

            ObjectProvider op = findObjectProvider(obj);
            if (op == null)
            {
                throw new NucleusUserException(Localiser.msg("010048", StringUtils.toJVMIDString(obj), getApiAdapter().getIdForObject(obj), "refresh"));
            }

            if (getApiAdapter().isPersistent(obj) && op.isWaitingToBeFlushedToDatastore())
            {
                // Persistent but not yet flushed so nothing to "refresh" from!
                return;
            }

            op.refresh();
        }
        finally
        {
            clr.unsetPrimary();
        }
    }

    /**
     * Method to do a refresh of all objects.
     * @throws NucleusUserException thrown if instances could not be refreshed.
     */
    public void refreshAllObjects()
    {
        Set<ObjectProvider> toRefresh = new HashSet<>();
        toRefresh.addAll(enlistedOPCache.values());
        toRefresh.addAll(dirtyOPs);
        toRefresh.addAll(indirectDirtyOPs);
        if (!tx.isActive() && cache != null && !cache.isEmpty())
        {
            toRefresh.addAll(cache.values());
        }

        List failures = null;
        for (ObjectProvider op : toRefresh)
        {
            try
            {
                op.refresh();
            }
            catch (RuntimeException e)
            {
                if (failures == null)
                {
                    failures = new ArrayList();
                }
                failures.add(e);
            }
        }

        if (failures != null && !failures.isEmpty())
        {
            throw new NucleusUserException(Localiser.msg("010037"), (Exception[]) failures.toArray(new Exception[failures.size()]));
        }
    }

    /**
     * Method to retrieve an object.
     * @param obj The object
     * @param fgOnly Whether to retrieve the current fetch group fields only
     */
    public void retrieveObject(Object obj, boolean fgOnly)
    {
        if (obj == null)
        {
            return;
        }

        try
        {
            clr.setPrimary(obj.getClass().getClassLoader());
            assertClassPersistable(obj.getClass());
            assertNotDetached(obj);

            ObjectProvider op = findObjectProvider(obj);
            if (op == null)
            {
                throw new NucleusUserException(Localiser.msg("010048", StringUtils.toJVMIDString(obj), getApiAdapter().getIdForObject(obj), "retrieve"));
            }
            op.retrieve(fgOnly);
        }
        finally
        {
            clr.unsetPrimary();
        }
    }

    /**
     * Method to make an object persistent.
     * NOT to be called by internal DataNucleus methods. Only callable by external APIs (JDO/JPA).
     * @param obj The object
     * @param merging Whether this object (and dependents) is being merged
     * @return The persisted object
     * @throws NucleusUserException if the object is managed by a different manager
     */
    public Object persistObject(Object obj, boolean merging)
    {
        if (obj == null)
        {
            return null;
        }

        // Allocate thread-local persistence info
        ThreadContextInfo threadInfo = acquireThreadContextInfo();
        try
        {
            boolean allowMergeOfTransient = nucCtx.getConfiguration().getBooleanProperty(PropertyNames.PROPERTY_ALLOW_ATTACH_OF_TRANSIENT, false);
            if (getBooleanProperty(PropertyNames.PROPERTY_ALLOW_ATTACH_OF_TRANSIENT) != null)
            {
                allowMergeOfTransient = getBooleanProperty(PropertyNames.PROPERTY_ALLOW_ATTACH_OF_TRANSIENT);
            }
            if (merging && allowMergeOfTransient)
            {
                threadInfo.merging = true;
            }
            if (threadInfo.attachedOwnerByObject == null)
            {
                threadInfo.attachedOwnerByObject = new HashMap();
            }
            if (threadInfo.attachedPCById == null)
            {
                threadInfo.attachedPCById = new HashMap();
            }

            if (tx.isActive())
            {
                return persistObjectWork(obj);
            }

            threadInfo.nontxPersistDelete = true;
            boolean success = true;
            Set cachedIds = (cache != null && !cache.isEmpty()) ? new HashSet(cache.keySet()) : null;
            try
            {
                return persistObjectWork(obj);
            }
            catch (RuntimeException re)
            {
                success = false;
                if (cache != null)
                {
                    // Make sure we evict any objects that have been put in the L1 cache during this step
                    // TODO Also ought to disconnect any state manager
                    Iterator cacheIter = cache.keySet().iterator();
                    while (cacheIter.hasNext())
                    {
                        Object id = cacheIter.next();
                        if (cachedIds == null || !cachedIds.contains(id))
                        {
                            // Remove from L1 cache
                            cacheIter.remove();
                        }
                    }
                }
                throw re;
            }
            finally
            {
                threadInfo.nontxPersistDelete = false;
                if (success)
                {
                    // Commit any non-tx changes
                    processNontransactionalAtomicChanges();
                }
            }
        }
        finally
        {
            // Deallocate thread-local persistence info
            releaseThreadContextInfo();
        }
    }

    /**
     * Method to persist an array of objects to the datastore.
     * @param objs The objects to persist
     * @return The persisted objects
     * @throws NucleusUserException Thrown if an error occurs during the persist process.
     *     Any exception could have several nested exceptions for each failed object persist
     */
    public Object[] persistObjects(Object... objs)
    {
        if (objs == null)
        {
            return null;
        }

        Object[] persistedObjs = new Object[objs.length];

        // Allocate thread-local persistence info
        ThreadContextInfo threadInfo = acquireThreadContextInfo();
        try
        {
            if (threadInfo.attachedOwnerByObject == null)
            {
                threadInfo.attachedOwnerByObject = new HashMap();
            }
            if (threadInfo.attachedPCById == null)
            {
                threadInfo.attachedPCById = new HashMap();
            }
            if (!tx.isActive())
            {
                threadInfo.nontxPersistDelete = true;
            }

            try
            {
                getStoreManager().getPersistenceHandler().batchStart(this, PersistenceBatchType.PERSIST);
                List<RuntimeException> failures = null;
                for (int i=0;i<objs.length;i++)
                {
                    try
                    {
                        if (objs[i] != null)
                        {
                            persistedObjs[i] = persistObjectWork(objs[i]);
                        }
                    }
                    catch (RuntimeException e)
                    {
                        if (failures == null)
                        {
                            failures = new ArrayList();
                        }
                        failures.add(e);
                    }
                }
                if (failures != null && !failures.isEmpty())
                {
                    RuntimeException e = failures.get(0);
                    if (e instanceof NucleusException && ((NucleusException)e).isFatal())
                    {
                        // Should really check all and see if any are fatal not just first one
                        throw new NucleusFatalUserException(Localiser.msg("010039"), failures.toArray(new Exception[failures.size()]));
                    }
                    throw new NucleusUserException(Localiser.msg("010039"), failures.toArray(new Exception[failures.size()]));
                }
            }
            finally
            {
                getStoreManager().getPersistenceHandler().batchEnd(this, PersistenceBatchType.PERSIST);

                if (!tx.isActive())
                {
                    // Commit any non-tx changes
                    threadInfo.nontxPersistDelete = false;
                    processNontransactionalAtomicChanges();
                }
            }
        }
        finally
        {
            // Deallocate thread-local persistence info
            releaseThreadContextInfo();
        }
        return persistedObjs;
    }

    /**
     * Method to make an object persistent.
     * NOT to be called by internal DataNucleus methods.
     * @param obj The object
     * @return The persisted object
     * @throws NucleusUserException if the object is managed by a different manager
     */
    private Object persistObjectWork(Object obj)
    {
        boolean detached = getApiAdapter().isDetached(obj);

        // Persist the object
        Object persistedPc = persistObjectInternal(obj, null, null, -1, ObjectProvider.PC);

        // If using reachability at commit and appropriate save it for reachability checks when we commit
        ObjectProvider op = findObjectProvider(persistedPc);
        if (op != null)
        {
            // TODO If attaching (detached=true), we maybe ought not add the ObjectProvider to dirtyOPs/indirectDirtyOPs
            if (indirectDirtyOPs.contains(op))
            {
                dirtyOPs.add(op);
                indirectDirtyOPs.remove(op);
            }
            else if (!dirtyOPs.contains(op))
            {
                dirtyOPs.add(op);
                if (l2CacheTxIds != null && nucCtx.isClassCacheable(op.getClassMetaData()))
                {
                    l2CacheTxIds.add(op.getInternalObjectId());
                }
            }

            if (pbrAtCommitHandler != null && tx.isActive())
            {
                if (detached || getApiAdapter().isNew(persistedPc))
                {
                    pbrAtCommitHandler.addPersistedObject(op.getInternalObjectId());
                }
            }
        }

        return persistedPc;
    }

    /**
     * Method to make an object persistent which should be called from internal calls only.
     * All PM/EM calls should go via persistObject(Object obj).
     * @param obj The object
     * @param preInsertChanges Any changes to make before inserting
     * @param ownerOP ObjectProvider of the owner when embedded
     * @param ownerFieldNum Field number in the owner where this is embedded (or -1 if not embedded)
     * @param objectType Type of object (see org.datanucleus.ObjectProvider, e.g ObjectProvider.PC)
     * @return The persisted object
     * @throws NucleusUserException if the object is managed by a different manager
     */
    public <T> T persistObjectInternal(T obj, FieldValues preInsertChanges, ObjectProvider ownerOP, int ownerFieldNum, int objectType)
    {
        if (obj == null)
        {
            return null;
        }

        // TODO Support embeddedOwner/objectType, so we can add ObjectProvider for embedded objects here
        ApiAdapter api = getApiAdapter();
        Object id = null; // Id of the object that was persisted during this process (if any)
        try
        {
            clr.setPrimary(obj.getClass().getClassLoader());
            assertClassPersistable(obj.getClass());
            ExecutionContext ec = api.getExecutionContext(obj);
            if (ec != null && ec != this)
            {
                // Object managed by a different manager
                throw new NucleusUserException(Localiser.msg("010007", obj));
            }

            boolean cacheable = false;
            T persistedPc = obj; // Persisted object is the passed in pc (unless being attached as a copy)
            if (api.isDetached(obj))
            {
                // Detached : attach it
                assertDetachable(obj);
                if (getBooleanProperty(PropertyNames.PROPERTY_COPY_ON_ATTACH))
                {
                    // Attach a copy and return the copy
                    persistedPc = attachObjectCopy(ownerOP, obj, api.getIdForObject(obj) == null);
                }
                else
                {
                    // Attach the object
                    attachObject(ownerOP, obj, api.getIdForObject(obj) == null);
                    persistedPc = obj;
                }
            }
            else if (api.isTransactional(obj) && !api.isPersistent(obj))
            {
                // TransientTransactional : persist it
                if (NucleusLogger.PERSISTENCE.isDebugEnabled())
                {
                    NucleusLogger.PERSISTENCE.debug(Localiser.msg("010015", StringUtils.toJVMIDString(obj)));
                }
                ObjectProvider op = findObjectProvider(obj);
                if (op == null)
                {
                    throw new NucleusUserException(Localiser.msg("010007", getApiAdapter().getIdForObject(obj)));
                }
                op.makePersistentTransactionalTransient();
            }
            else if (!api.isPersistent(obj))
            {
                // Transient : persist it
                if (NucleusLogger.PERSISTENCE.isDebugEnabled())
                {
                    NucleusLogger.PERSISTENCE.debug(Localiser.msg("010015", StringUtils.toJVMIDString(obj)));
                }
                boolean merged = false;
                ThreadContextInfo threadInfo = acquireThreadContextInfo();
                try
                {
                    if (threadInfo.merging)
                    {
                        AbstractClassMetaData cmd = getMetaDataManager().getMetaDataForClass(obj.getClass(), clr);
                        if (cmd.getIdentityType() == IdentityType.APPLICATION)
                        {
                            Object transientId = nucCtx.getIdentityManager().getApplicationId(obj, cmd);
                            if (transientId != null)
                            {
                                // User has set id field(s) so find the datastore object (if exists)
                                T existingObj = (T)findObject(transientId, true, true, cmd.getFullClassName());
                                ObjectProvider existingOP = findObjectProvider(existingObj);
                                existingOP.attach(obj);
                                id = transientId;
                                merged = true;
                                persistedPc = existingObj;
                            }
                        }
                        cacheable = nucCtx.isClassCacheable(cmd);
                    }
                }
                catch (NucleusObjectNotFoundException onfe)
                {
                    // Object with this id doesn't exist, so just persist the transient (below)
                }
                finally
                {
                    releaseThreadContextInfo();
                }

                if (!merged)
                {
                    ObjectProvider<T> op = findObjectProvider(obj);
                    if (op == null)
                    {
                        if ((objectType == ObjectProvider.EMBEDDED_COLLECTION_ELEMENT_PC || 
                             objectType == ObjectProvider.EMBEDDED_MAP_KEY_PC ||
                             objectType == ObjectProvider.EMBEDDED_MAP_VALUE_PC ||
                             objectType == ObjectProvider.EMBEDDED_PC) && ownerOP != null)
                        {
                            // SCO object
                            op = nucCtx.getObjectProviderFactory().newForEmbedded(this, obj, false, ownerOP, ownerFieldNum);
                            op.setPcObjectType((short) objectType);
                            op.makePersistent();
                            id = op.getInternalObjectId();
                        }
                        else
                        {
                            // FCO object
                            op = nucCtx.getObjectProviderFactory().newForPersistentNew(this, obj, preInsertChanges);
                            op.makePersistent();
                            id = op.getInternalObjectId();
                        }
                    }
                    else
                    {
                        if (op.getReferencedPC() == null)
                        {
                            // Persist it
                            op.makePersistent();
                            id = op.getInternalObjectId();
                        }
                        else
                        {
                            // Being attached, so use the attached object
                            persistedPc = op.getReferencedPC();
                        }
                    }
                    if (op != null)
                    {
                        cacheable = nucCtx.isClassCacheable(op.getClassMetaData());
                    }
                }
            }
            else if (api.isPersistent(obj) && api.getIdForObject(obj) == null)
            {
                // Embedded/Serialised : have ObjectProvider but no identity, allow persist in own right
                // Should we be making a copy of the object here ?
                if (NucleusLogger.PERSISTENCE.isDebugEnabled())
                {
                    NucleusLogger.PERSISTENCE.debug(Localiser.msg("010015", StringUtils.toJVMIDString(obj)));
                }
                ObjectProvider op = findObjectProvider(obj);
                op.makePersistent();
                id = op.getInternalObjectId();
                cacheable = nucCtx.isClassCacheable(op.getClassMetaData());
            }
            else if (api.isDeleted(obj))
            {
                // Deleted : (re)-persist it (permitted in JPA, but not JDO - see ObjectProvider)
                if (NucleusLogger.PERSISTENCE.isDebugEnabled())
                {
                    NucleusLogger.PERSISTENCE.debug(Localiser.msg("010015", StringUtils.toJVMIDString(obj)));
                }
                ObjectProvider op = findObjectProvider(obj);
                op.makePersistent();
                id = op.getInternalObjectId();
                cacheable = nucCtx.isClassCacheable(op.getClassMetaData());
            }
            else
            {
                if (api.isPersistent(obj) && api.isTransactional(obj) && api.isDirty(obj) && isDelayDatastoreOperationsEnabled())
                {
                    // Object provisionally persistent (but not in datastore) so re-run reachability maybe
                    if (NucleusLogger.PERSISTENCE.isDebugEnabled())
                    {
                        NucleusLogger.PERSISTENCE.debug(Localiser.msg("010015", StringUtils.toJVMIDString(obj)));
                    }
                    ObjectProvider op = findObjectProvider(obj);
                    op.makePersistent();
                    id = op.getInternalObjectId();
                    cacheable = nucCtx.isClassCacheable(op.getClassMetaData());
                }
            }

            if (id != null && l2CacheTxIds != null && cacheable)
            {
                l2CacheTxIds.add(id);
            }
            return persistedPc;
        }
        finally
        {
            clr.unsetPrimary();
        }
    }

    /**
     * Method to persist the passed object (internally).
     * @param pc The object
     * @param ownerOP ObjectProvider of the owner when embedded
     * @param ownerFieldNum Field number in the owner where this is embedded (or -1 if not embedded)
     * @param objectType Type of object (see org.datanucleus.ObjectProvider, e.g ObjectProvider.PC)
     * @return The persisted object
     */
    public <T> T persistObjectInternal(T pc, ObjectProvider ownerOP, int ownerFieldNum, int objectType)
    {
        if (ownerOP != null)
        {
            ObjectProvider op = findObjectProvider(ownerOP.getObject());
            return persistObjectInternal(pc, null, op, ownerFieldNum, objectType);
        }
        return persistObjectInternal(pc, null, null, ownerFieldNum, objectType);
    }

    /**
     * Method to delete an array of objects from the datastore.
     * @param objs The objects
     * @throws NucleusUserException Thrown if an error occurs during the deletion process. Any exception could have several nested exceptions for each failed object deletion
     */
    public void deleteObjects(Object... objs)
    {
        if (objs == null)
        {
            return;
        }

        ThreadContextInfo threadInfo = acquireThreadContextInfo();
        try
        {
            if (!tx.isActive())
            {
                threadInfo.nontxPersistDelete = true;
            }

            getStoreManager().getPersistenceHandler().batchStart(this, PersistenceBatchType.DELETE);

            List<RuntimeException> failures = null;
            for (int i=0;i<objs.length;i++)
            {
                try
                {
                    if (objs[i] != null)
                    {
                        deleteObjectWork(objs[i]);
                    }
                }
                catch (RuntimeException e)
                {
                    if (failures == null)
                    {
                        failures = new ArrayList();
                    }
                    failures.add(e);
                }
            }
            if (failures != null && !failures.isEmpty())
            {
                RuntimeException e = failures.get(0);
                if (e instanceof NucleusException && ((NucleusException)e).isFatal())
                {
                    // Should really check all and see if any are fatal not just first one
                    throw new NucleusFatalUserException(Localiser.msg("010040"), failures.toArray(new Exception[failures.size()]));
                }
                throw new NucleusUserException(Localiser.msg("010040"), failures.toArray(new Exception[failures.size()]));
            }
        }
        finally
        {
            getStoreManager().getPersistenceHandler().batchEnd(this, PersistenceBatchType.DELETE);

            if (!tx.isActive())
            {
                threadInfo.nontxPersistDelete = false;
                // Commit any non-tx changes
                processNontransactionalAtomicChanges();
            }

            // Deallocate thread-local persistence info
            releaseThreadContextInfo();
        }
    }

    /**
     * Method to delete an object from the datastore.
     * NOT to be called by internal methods. Only callable by external APIs (JDO/JPA).
     * @param obj The object
     */
    public void deleteObject(Object obj)
    {
        if (obj == null)
        {
            return;
        }

        ThreadContextInfo threadInfo = acquireThreadContextInfo();
        try
        {
            if (!tx.isActive())
            {
                threadInfo.nontxPersistDelete = true;
            }

            deleteObjectWork(obj);
        }
        finally
        {
            if (!tx.isActive())
            {
                threadInfo.nontxPersistDelete = false;
                // Commit any non-tx changes
                processNontransactionalAtomicChanges();
            }

            releaseThreadContextInfo();
        }
    }

    /**
     * Method to delete an object from the datastore.
     * NOT to be called by internal methods. Only callable by external APIs (JDO/JPA).
     * @param obj The object
     */
    void deleteObjectWork(Object obj)
    {
        ObjectProvider op = findObjectProvider(obj);
        if (op == null && getApiAdapter().isDetached(obj))
        {
            // Delete of detached, so find a managed attached version and delete that
            Object attachedObj = findObject(getApiAdapter().getIdForObject(obj), true, false, obj.getClass().getName());
            op = findObjectProvider(attachedObj);
        }
        if (op != null)
        {
            // Add the object to the relevant list of dirty ObjectProviders
            if (indirectDirtyOPs.contains(op))
            {
                // Object is dirty indirectly, but now user-requested so move to direct list of dirty objects
                indirectDirtyOPs.remove(op);
                dirtyOPs.add(op);
            }
            else if (!dirtyOPs.contains(op))
            {
                dirtyOPs.add(op);
                if (l2CacheTxIds != null && nucCtx.isClassCacheable(op.getClassMetaData()))
                {
                    l2CacheTxIds.add(op.getInternalObjectId());
                }
            }
        }

        // Delete the object
        deleteObjectInternal(obj);

        if (pbrAtCommitHandler != null && tx.isActive())
        {
            if (op != null)
            {
                if (getApiAdapter().isDeleted(obj))
                {
                    pbrAtCommitHandler.addDeletedObject(op.getInternalObjectId());
                }
            }
        }
    }

    /**
     * Method to delete an object from persistence which should be called from internal calls only.
     * All PM/EM calls should go via deleteObject(Object obj).
     * @param obj Object to delete
     */
    public void deleteObjectInternal(Object obj)
    {
        if (obj == null)
        {
            return;
        }

        try
        {
            clr.setPrimary(obj.getClass().getClassLoader());
            assertClassPersistable(obj.getClass());

            Object pc = obj;
            if (getApiAdapter().isDetached(obj))
            {
                // Load up the attached instance with this identity
                pc = findObject(getApiAdapter().getIdForObject(obj), true, true, null);
            }

            if (NucleusLogger.PERSISTENCE.isDebugEnabled())
            {
                NucleusLogger.PERSISTENCE.debug(Localiser.msg("010019", StringUtils.toJVMIDString(pc)));
            }

            // Check that the object is valid for deleting
            if (getApiAdapter().getName().equals("JDO"))
            {
                // JDO doesn't allow deletion of transient
                if (!getApiAdapter().isPersistent(pc) && !getApiAdapter().isTransactional(pc))
                {
                    throw new NucleusUserException(Localiser.msg("010020"));
                }
                else if (!getApiAdapter().isPersistent(pc) && getApiAdapter().isTransactional(pc))
                {
                    throw new NucleusUserException(Localiser.msg("010021", getApiAdapter().getIdForObject(obj)));
                }
            }

            // Delete it
            ObjectProvider op = findObjectProvider(pc);
            if (op == null)
            {
                if (!getApiAdapter().allowDeleteOfNonPersistentObject())
                {
                    // Not permitted by the API
                    throw new NucleusUserException(Localiser.msg("010007", getApiAdapter().getIdForObject(pc)));
                }

                // Put ObjectProvider around object so it is P_NEW (unpersisted), then P_NEW_DELETED soon after
                op = nucCtx.getObjectProviderFactory().newForPNewToBeDeleted(this, pc);
            }

            if (l2CacheTxIds != null && nucCtx.isClassCacheable(op.getClassMetaData()))
            {
                // Mark for L2 cache update
                l2CacheTxIds.add(op.getInternalObjectId());
            }

            // Move to deleted state
            op.deletePersistent();
        }
        finally
        {
            clr.unsetPrimary();
        }
    }

    /**
     * Method to migrate an object to transient state.
     * @param obj The object
     * @param state Object containing the state of the fetch plan process (if any)
     * @throws NucleusException When an error occurs in making the object transient
     */
    public void makeObjectTransient(Object obj, FetchPlanState state)
    {
        if (obj == null)
        {
            return;
        }

        try
        {
            clr.setPrimary(obj.getClass().getClassLoader());
            assertClassPersistable(obj.getClass());
            assertNotDetached(obj);

            if (NucleusLogger.PERSISTENCE.isDebugEnabled())
            {
                NucleusLogger.PERSISTENCE.debug(Localiser.msg("010022", StringUtils.toJVMIDString(obj)));
            }

            if (getApiAdapter().isPersistent(obj))
            {
                ObjectProvider op = findObjectProvider(obj);
                op.makeTransient(state);
            }
        }
        finally
        {
            clr.unsetPrimary();
        }
    }

    /**
     * Method to make an object transactional.
     * @param obj The object
     * @throws NucleusException Thrown when an error occurs
     */
    public void makeObjectTransactional(Object obj)
    {
        if (obj == null)
        {
            return;
        }

        try
        {
            clr.setPrimary(obj.getClass().getClassLoader());
            assertClassPersistable(obj.getClass());
            assertNotDetached(obj);
            if (getApiAdapter().isPersistent(obj))
            {
                assertActiveTransaction();
            }

            ObjectProvider op = findObjectProvider(obj);
            if (op == null)
            {
                op = nucCtx.getObjectProviderFactory().newForTransactionalTransient(this, obj);
            }
            op.makeTransactional();
        }
        finally
        {
            clr.unsetPrimary();
        }
    }

    /**
     * Method to make an object nontransactional.
     * @param obj The object
     */
    public void makeObjectNontransactional(Object obj)
    {
        if (obj == null)
        {
            return;
        }

        try
        {
            clr.setPrimary(obj.getClass().getClassLoader());
            assertClassPersistable(obj.getClass());
            if (!getApiAdapter().isPersistent(obj) && getApiAdapter().isTransactional(obj) && getApiAdapter().isDirty(obj))
            {
                throw new NucleusUserException(Localiser.msg("010024"));
            }

            ObjectProvider op = findObjectProvider(obj);
            op.makeNontransactional();
        }
        finally
        {
            clr.unsetPrimary();
        }
    }

    /**
     * Method to attach a persistent detached object.
     * If a different object with the same identity as this object exists in the L1 cache then an exception
     * will be thrown.
     * @param ownerOP ObjectProvider of the owner object that has this in a field that causes this attach
     * @param pc The persistable object
     * @param sco Whether the PC object is stored without an identity (embedded/serialised)
     */
    public void attachObject(ObjectProvider ownerOP, Object pc, boolean sco)
    {
        assertClassPersistable(pc.getClass());

        // Store the owner for this persistable object being attached
        Map attachedOwnerByObject = getThreadContextInfo().attachedOwnerByObject; // For the current thread
        if (attachedOwnerByObject != null)
        {
            attachedOwnerByObject.put(pc, ownerOP);
        }

        ApiAdapter api = getApiAdapter();
        Object id = api.getIdForObject(pc);
        if (id != null && isInserting(pc))
        {
            // Object is being inserted in this transaction so just return
            return;
        }
        else if (id == null && !sco)
        {
            // Transient object so needs persisting
            persistObjectInternal(pc, null, null, -1, ObjectProvider.PC);
            return;
        }

        if (api.isDetached(pc))
        {
            // Detached, so migrate to attached
            if (cache != null)
            {
                ObjectProvider l1CachedOP = cache.get(id);
                if (l1CachedOP != null && l1CachedOP.getObject() != pc)
                {
                    // attached object with the same id already present in the L1 cache so cannot attach in-situ
                    throw new NucleusUserException(Localiser.msg("010017", StringUtils.toJVMIDString(pc)));
                }
            }

            if (NucleusLogger.PERSISTENCE.isDebugEnabled())
            {
                NucleusLogger.PERSISTENCE.debug(Localiser.msg("010016", StringUtils.toJVMIDString(pc)));
            }
            ObjectProvider op = nucCtx.getObjectProviderFactory().newForDetached(this, pc, id, api.getVersionForObject(pc));
            op.attach(sco);
        }
        else
        {
            // Not detached so can't attach it. Just return
            return;
        }
    }

    /**
     * Method to attach a persistent detached object returning an attached copy of the object.
     * If the object is of class that is not detachable, a ClassNotDetachableException will be thrown.
     * @param ownerOP ObjectProvider of the owner object that has this in a field that causes this attach
     * @param pc The object
     * @param sco Whether it has no identity (second-class object)
     * @return The attached object
     */
    public <T> T attachObjectCopy(ObjectProvider ownerOP, T pc, boolean sco)
    {
        assertClassPersistable(pc.getClass());
        assertDetachable(pc);

        // Store the owner for this persistable object being attached
        Map attachedOwnerByObject = getThreadContextInfo().attachedOwnerByObject; // For the current thread
        if (attachedOwnerByObject != null)
        {
            attachedOwnerByObject.put(pc, ownerOP);
        }

        ApiAdapter api = getApiAdapter();
        Object id = api.getIdForObject(pc);
        if (id != null && isInserting(pc))
        {
            // Object is being inserted in this transaction
            return pc;
        }
        else if (id == null && !sco)
        {
            // Object was not persisted before so persist it
            return persistObjectInternal(pc, null, null, -1, ObjectProvider.PC);
        }
        else if (api.isPersistent(pc))
        {
            // Already persistent hence can't be attached
            return pc;
        }

        // Object should exist in this datastore now
        T pcTarget = null;
        if (sco)
        {
            // SCO PC (embedded/serialised)
            boolean detached = getApiAdapter().isDetached(pc);
            ObjectProvider<T> targetOP = nucCtx.getObjectProviderFactory().newForEmbedded(this, pc, true, null, -1);
            pcTarget = targetOP.getObject();
            if (detached)
            {
                // If the object is detached, re-attach it
                if (NucleusLogger.PERSISTENCE.isDebugEnabled())
                {
                    NucleusLogger.PERSISTENCE.debug(Localiser.msg("010018", StringUtils.toJVMIDString(pc), StringUtils.toJVMIDString(pcTarget)));
                }
                targetOP.attachCopy(pc, sco);
            }
        }
        else
        {
            // FCO PC
            boolean detached = getApiAdapter().isDetached(pc);
            pcTarget = (T)findObject(id, false, false, pc.getClass().getName());
            if (detached)
            {
                T obj = null;
                Map attachedPCById = getThreadContextInfo().attachedPCById; // For the current thread
                if (attachedPCById != null) // Only used by persistObject process
                {
                    obj = (T) attachedPCById.get(getApiAdapter().getIdForObject(pc));
                }
                if (obj != null)
                {
                    pcTarget = obj;
                }
                else
                {
                    // If the object is detached, re-attach it
                    if (NucleusLogger.PERSISTENCE.isDebugEnabled())
                    {
                        NucleusLogger.PERSISTENCE.debug(Localiser.msg("010018", 
                            StringUtils.toJVMIDString(pc), StringUtils.toJVMIDString(pcTarget)));
                    }
                    pcTarget = (T) findObjectProvider(pcTarget).attachCopy(pc, sco);

                    // Save the detached-attached PCs for later reference
                    if (attachedPCById != null) // Only used by persistObject process
                    {
                        attachedPCById.put(getApiAdapter().getIdForObject(pc), pcTarget);
                    }
                }
            }
        }

        return pcTarget;
    }

    /**
     * Method to detach a persistent object without making a copy. Note that 
     * also all the objects which are refered to from this object are detached.
     * If the object is of class that is not detachable a ClassNotDetachableException
     * will be thrown. If the object is not persistent a NucleusUserException is thrown.
     * @param state State for the detachment process
     * @param obj The object
     */
    public void detachObject(FetchPlanState state, Object obj)
    {
        if (getApiAdapter().isDetached(obj))
        {
            // Already detached
            return;
        }
        if (!getApiAdapter().isPersistent(obj))
        {
            if (runningDetachAllOnTxnEnd && !getMetaDataManager().getMetaDataForClass(obj.getClass(), clr).isDetachable())
            {
                // Object is not detachable, and is now transient (and after commit) so just return
                return;
            }

            // Transient object passed so persist it before thinking about detaching
            if (tx.isActive())
            {
                persistObjectInternal(obj, null, null, -1, ObjectProvider.PC);
            }
        }

        ObjectProvider op = findObjectProvider(obj);
        if (op == null)
        {
            throw new NucleusUserException(Localiser.msg("010007", getApiAdapter().getIdForObject(obj)));
        }
        op.detach(state);

        // Clear any changes from this since it is now detached
        if (dirtyOPs.contains(op) || indirectDirtyOPs.contains(op))
        {
            NucleusLogger.GENERAL.info(Localiser.msg("010047", StringUtils.toJVMIDString(obj)));
            clearDirty(op);
        }
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#detachObjects(org.datanucleus.FetchPlanState, java.lang.Object[])
     */
    @Override
    public void detachObjects(FetchPlanState state, Object... pcs)
    {
        if (pcs == null || pcs.length == 0)
        {
            return;
        }

        Collection<ObjectProvider> opsToDetach = new HashSet<>();
        for (Object pc : pcs)
        {
            if (getApiAdapter().isDetached(pc))
            {
                // Already detached
                continue;
            }
            else if (!getApiAdapter().isPersistent(pc))
            {
                if (runningDetachAllOnTxnEnd && !getMetaDataManager().getMetaDataForClass(pc.getClass(), clr).isDetachable())
                {
                    // Object is not detachable, and is now transient (and after commit) so just return
                    continue;
                }

                // Transient object passed so persist it before thinking about detaching
                if (tx.isActive())
                {
                    persistObjectInternal(pc, null, null, -1, ObjectProvider.PC);
                }
            }

            ObjectProvider op = findObjectProvider(pc);
            if (op != null)
            {
                opsToDetach.add(op);
            }
        }

        for (ObjectProvider op : opsToDetach)
        {
            op.detach(state);

            // Clear any changes from this since it is now detached
            if (dirtyOPs.contains(op) || indirectDirtyOPs.contains(op))
            {
                NucleusLogger.GENERAL.info(Localiser.msg("010047", StringUtils.toJVMIDString(op.getObject())));
                clearDirty(op);
            }
        }

    }

    /**
     * Detach a copy of the passed persistent object using the provided detach state.
     * If the object is of class that is not detachable it will be detached as transient.
     * If it is not yet persistent it will be first persisted.
     * @param state State for the detachment process
     * @param pc The object
     * @return The detached object
     */
    public <T> T detachObjectCopy(FetchPlanState state, T pc)
    {
        T thePC = pc;
        try
        {
            clr.setPrimary(pc.getClass().getClassLoader());
            if (!getApiAdapter().isPersistent(pc) && !getApiAdapter().isDetached(pc))
            {
                // Transient object passed so persist it before thinking about detaching
                if (tx.isActive())
                {
                    thePC = persistObjectInternal(pc, null, null, -1, ObjectProvider.PC);
                }
                else
                {
                    // JDO [12.6.8] "If a detachCopy method is called outside an active transaction, the reachability 
                    // algorithm will not be run; if any transient instances are reachable via persistent fields, a 
                    // XXXUserException is thrown for each persistent instance containing such fields.
                    throw new NucleusUserException(Localiser.msg("010014"));
                }
            }

            if (getApiAdapter().isDetached(thePC))
            {
                // Passing in a detached (dirty or clean) instance, so get a persistent copy to detach
                thePC = (T)findObject(getApiAdapter().getIdForObject(thePC), false, true, null);
            }

            ObjectProvider<T> op = findObjectProvider(thePC);
            if (op == null)
            {
                throw new NucleusUserException(Localiser.msg("010007", getApiAdapter().getIdForObject(thePC)));
            }

            return op.detachCopy(state);
        }
        finally
        {
            clr.unsetPrimary();
        }
    }

    /**
     * Method to detach all objects in the context.
     * Detaches all objects enlisted as well as all objects in the L1 cache.
     * Of particular use with JPA when doing a clear of the persistence context.
     */
    public void detachAll()
    {
        Collection<ObjectProvider> opsToDetach = new HashSet<>(this.enlistedOPCache.values());
        if (cache != null && !cache.isEmpty())
        {
            opsToDetach.addAll(cache.values());
        }

        FetchPlanState fps = new FetchPlanState();
        for (ObjectProvider op : opsToDetach)
        {
            op.detach(fps);
        }
    }

    public Object getAttachDetachReferencedObject(ObjectProvider op)
    {
        if (opAttachDetachObjectReferenceMap == null)
        {
            return null;
        }
        return opAttachDetachObjectReferenceMap.get(op);
    }

    public void setAttachDetachReferencedObject(ObjectProvider op, Object obj)
    {
        if (obj != null)
        {
            if (opAttachDetachObjectReferenceMap == null)
            {
                opAttachDetachObjectReferenceMap = new HashMap<ObjectProvider, Object>();
            }
            opAttachDetachObjectReferenceMap.put(op,  obj);
        }
        else
        {
            if (opAttachDetachObjectReferenceMap != null)
            {
                opAttachDetachObjectReferenceMap.remove(op);
            }
        }
    }

    // ----------------------------- New Instances ----------------------------------

    /**
     * Method to generate an instance of an interface, abstract class, or concrete PC class.
     * @param cls The class of the interface or abstract class, or concrete class defined in MetaData
     * @return The instance of this type
     * @throws NucleusUserException if an ImplementationCreator instance does not exist and one is needed (i.e not a concrete class passed in)
     */
    public <T> T newInstance(Class<T> cls)
    {
        if (getApiAdapter().isPersistable(cls) && !Modifier.isAbstract(cls.getModifiers()))
        {
            // Concrete PC class so instantiate here
            try
            {
                return cls.newInstance();
            }
            catch (IllegalAccessException|InstantiationException e)
            {
                throw new NucleusUserException(e.toString(), e);
            }
        }

        // Use ImplementationCreator
        if (getNucleusContext().getImplementationCreator() == null)
        {
            throw new NucleusUserException(Localiser.msg("010035"));
        }
        return getNucleusContext().getImplementationCreator().newInstance(cls, clr);
    }

    // ----------------------------- Object Retrieval by Id ----------------------------------

    /**
     * Method to return if the specified object exists in the datastore.
     * @param obj The (persistable) object
     * @return Whether it exists
     */
    public boolean exists(Object obj)
    {
        if (obj == null)
        {
            return false;
        }

        Object id = getApiAdapter().getIdForObject(obj);
        if (id == null)
        {
            return false;
        }

        try
        {
            findObject(id, true, false, obj.getClass().getName());
        }
        catch (NucleusObjectNotFoundException onfe)
        {
            return false;
        }

        return true;
    }

    /**
     * Accessor for the currently managed objects for the current transaction.
     * If the transaction is not active this returns null.
     * @return Collection of managed objects enlisted in the current transaction
     */
    public Set getManagedObjects()
    {
        if (!tx.isActive())
        {
            return null;
        }

        Set objs = new HashSet();
        for (ObjectProvider op : enlistedOPCache.values())
        {
            objs.add(op.getObject());
        }
        return objs;
    }

    /**
     * Accessor for the currently managed objects for the current transaction.
     * If the transaction is not active this returns null.
     * @param classes Classes that we want the enlisted objects for
     * @return Collection of managed objects enlisted in the current transaction
     */
    public Set getManagedObjects(Class[] classes)
    {
        if (!tx.isActive())
        {
            return null;
        }

        Set objs = new HashSet();
        for (ObjectProvider op : enlistedOPCache.values())
        {
            for (int i=0;i<classes.length;i++)
            {
                if (classes[i] == op.getObject().getClass())
                {
                    objs.add(op.getObject());
                    break;
                }
            }
        }
        return objs;
    }

    /**
     * Accessor for the currently managed objects for the current transaction.
     * If the transaction is not active this returns null.
     * @param states States that we want the enlisted objects for
     * @return Collection of managed objects enlisted in the current transaction
     */
    public Set getManagedObjects(String[] states)
    {
        if (!tx.isActive())
        {
            return null;
        }

        Set objs = new HashSet();
        for (ObjectProvider op : enlistedOPCache.values())
        {
            for (int i=0;i<states.length;i++)
            {
                if (getApiAdapter().getObjectState(op.getObject()).equals(states[i]))
                {
                    objs.add(op.getObject());
                    break;
                }
            }
        }
        return objs;
    }

    /**
     * Accessor for the currently managed objects for the current transaction.
     * If the transaction is not active this returns null.
     * @param states States that we want the enlisted objects for
     * @param classes Classes that we want the enlisted objects for
     * @return Collection of managed objects enlisted in the current transaction
     */
    public Set getManagedObjects(String[] states, Class[] classes)
    {
        if (!tx.isActive())
        {
            return null;
        }

        Set objs = new HashSet();
        for (ObjectProvider op : enlistedOPCache.values())
        {
            boolean matches = false;
            for (int i=0;i<states.length;i++)
            {
                if (getApiAdapter().getObjectState(op.getObject()).equals(states[i]))
                {
                    for (int j=0;j<classes.length;j++)
                    {
                        if (classes[j] == op.getObject().getClass())
                        {
                            matches = true;
                            objs.add(op.getObject());
                            break;
                        }
                    }
                }
                if (matches)
                {
                    break;
                }
            }
        }
        return objs;
    }

    /**
     * Accessor for an object of the specified type with the provided id "key".
     * With datastore id or single-field id the "key" is the key of the id, and with composite ids the "key" is the toString() of the id.
     * @param cls Class of the persistable
     * @param key Value of the key field for SingleFieldIdentity, or the string value of the key otherwise
     * @return The object for this id.
     * @param <T> Type of the persistable
     */
    public <T> T findObject(Class<T> cls, Object key)
    {
        if (cls == null || key == null)
        {
            throw new NucleusUserException(Localiser.msg("010051", cls, key));
        }

        AbstractClassMetaData cmd = getMetaDataManager().getMetaDataForClass(cls, clr);
        if (cmd == null)
        {
            throw new NucleusUserException(Localiser.msg("010052", cls.getName()));
        }

        // Get the identity
        Object id = key;
        if (cmd.getIdentityType() == IdentityType.DATASTORE)
        {
            if (!IdentityUtils.isDatastoreIdentity(key))
            {
                // Create an OID
                id = nucCtx.getIdentityManager().getDatastoreId(cmd.getFullClassName(), key);
            }
        }
        else if (!cmd.getObjectidClass().equals(key.getClass().getName()))
        {
            // primaryKey is just the key (when using single-field identity), so create a PK object
            try
            {
                id = newObjectId(cls, key);
            }
            catch (NucleusException ne)
            {
                throw new IllegalArgumentException(ne);
            }
        }

        return (T) findObject(id, true, true, null);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#findObjects(java.lang.Class, java.util.List)
     */
    @Override
    public <T> List<T> findObjects(Class<T> cls, List keys)
    {
        if (cls == null || keys == null)
        {
            throw new NucleusUserException(Localiser.msg("010051", cls, keys));
        }

        AbstractClassMetaData cmd = getMetaDataManager().getMetaDataForClass(cls, clr);
        if (cmd == null)
        {
            throw new NucleusUserException(Localiser.msg("010052", cls.getName()));
        }

        // Get the identities TODO Batch this process
        List<T> objs = new ArrayList<>();
        for (Object key : keys)
        {
            Object id = key;
            if (cmd.getIdentityType() == IdentityType.DATASTORE)
            {
                if (!IdentityUtils.isDatastoreIdentity(key))
                {
                    // Create an OID
                    id = nucCtx.getIdentityManager().getDatastoreId(cmd.getFullClassName(), key);
                }
            }
            else if (!cmd.getObjectidClass().equals(key.getClass().getName()))
            {
                // primaryKey is just the key (when using single-field identity), so create a PK object
                try
                {
                    id = newObjectId(cls, key);
                }
                catch (NucleusException ne)
                {
                    throw new IllegalArgumentException(ne);
                }
            }

            objs.add((T) findObject(id, true, true, null));
        }

        return objs;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#findObjectByUnique(java.lang.Class, java.lang.String[], java.lang.Object[])
     */
    @Override
    public <T> T findObjectByUnique(Class<T> cls, String[] memberNames, Object[] memberValues)
    {
        if (cls == null || memberNames == null || memberNames.length == 0 || memberValues == null || memberValues.length == 0)
        {
            throw new NucleusUserException(Localiser.msg("010053", cls, StringUtils.objectArrayToString(memberNames), StringUtils.objectArrayToString(memberValues)));
        }

        // Check class and member existence
        AbstractClassMetaData cmd = getMetaDataManager().getMetaDataForClass(cls, clr);
        if (cmd == null)
        {
            throw new NucleusUserException(Localiser.msg("010052", cls.getName()));
        }
        for (String memberName : memberNames)
        {
            AbstractMemberMetaData mmd = cmd.getMetaDataForMember(memberName);
            if (mmd == null)
            {
                throw new NucleusUserException("Attempt to find object using unique key of class " + cmd.getFullClassName() + " but field " + memberName + " doesnt exist!");
            }
        }

        // Check whether this is cached against the unique key
        CacheUniqueKey uniKey = new CacheUniqueKey(cls.getName(), memberNames, memberValues);
        ObjectProvider op = cache.getUnique(uniKey);
        if (op == null && l2CacheEnabled)
        {
            if (NucleusLogger.CACHE.isDebugEnabled())
            {
                NucleusLogger.CACHE.debug(Localiser.msg("003007", uniKey));
            }

            // Try L2 cache
            Object pc = getObjectFromLevel2CacheForUnique(uniKey);
            if (pc != null)
            {
                op = findObjectProvider(pc);
            }
        }
        if (op != null)
        {
            return (T) op.getObject();
        }

        return (T) getStoreManager().getPersistenceHandler().findObjectForUnique(this, cmd, memberNames, memberValues);
    }

    public Object findObject(Object id, boolean validate)
    {
        return findObject(id, validate, validate, null);
    }

    /**
     * Accessor for an object given the object id and a set of field values to apply to it.
     * This is intended for use where we have done a query and have the id from the results, and we want to
     * create the object, preferably using the cache, and then apply any field values to it.
     * @param id Id of the object.
     * @param fv Field values for the object (to copy in)
     * @param cls the type which the object is (optional). Used to instantiate the object
     * @param ignoreCache true if it must ignore the cache
     * @param checkInheritance Whether to check the inheritance on the id of the object
     * @return The Object
     */
    public Object findObject(Object id, FieldValues fv, Class cls, boolean ignoreCache, boolean checkInheritance)
    {
        assertIsOpen();

        Object pc = null;
        ObjectProvider op = null;

        if (!ignoreCache)
        {
            // Check if an object exists in the L1/L2 caches for this id
            pc = getObjectFromCache(id);
        }

        if (pc == null)
        {
            // Find direct from the datastore if supported
            pc = getStoreManager().getPersistenceHandler().findObject(this, id);
        }

        boolean createdHollow = false;
        if (pc == null)
        {
            // Determine the class details for this "id" if not provided, including checking of inheritance level
            String className = cls != null ? cls.getName() : null;
            if (!(id instanceof SCOID))
            {
                ClassDetailsForId details = getClassDetailsForId(id, className, checkInheritance);
                if (details.className != null && cls != null && !cls.getName().equals(details.className))
                {
                    cls = clr.classForName(details.className);
                }
                className = details.className;
                id = details.id;
                if (details.pc != null)
                {
                    // Found following inheritance check via the cache
                    pc = details.pc;
                    op = findObjectProvider(pc);
                }
            }

            if (pc == null)
            {
                // Still not found so create a Hollow instance with the supplied field values
                if (cls == null)
                {
                    try
                    {
                        cls = clr.classForName(className, id.getClass().getClassLoader());
                    }
                    catch (ClassNotResolvedException e)
                    {
                        String msg = Localiser.msg("010027", IdentityUtils.getPersistableIdentityForId(id));
                        NucleusLogger.PERSISTENCE.warn(msg);
                        throw new NucleusUserException(msg, e);
                    }
                }

                createdHollow = true;
                op = nucCtx.getObjectProviderFactory().newForHollow(this, cls, id, fv); // Will put object in L1 cache
                pc = op.getObject();
                putObjectIntoLevel2Cache(op, false);
            }
        }

        if (pc != null && fv != null && !createdHollow)
        {
            // Object found in the cache so load the requested fields
            if (op == null)
            {
                op = findObjectProvider(pc);
            }
            if (op != null)
            {
                // Load the requested fields
                fv.fetchNonLoadedFields(op);
            }
        }

        return pc;
    }

    /**
     * Accessor for objects with the specified identities.
     * @param identities Ids of the object(s).
     * @param validate Whether to validate the object state
     * @return The Objects with these ids (same order)
     * @throws NucleusObjectNotFoundException if an object doesn't exist in the datastore
     */
    public Object[] findObjectsById(Object[] identities, boolean validate)
    {
        if (identities == null)
        {
            return null;
        }
        else if (identities.length == 1)
        {
            return new Object[] {findObject(identities[0], validate, validate, null)};
        }
        for (int i=0;i<identities.length;i++)
        {
            if (identities[i] == null)
            {
                throw new NucleusUserException(Localiser.msg("010044"));
            }
        }

        // Set the identities array
        Object[] ids = new Object[identities.length];
        for (int i=0;i<identities.length;i++)
        {
            // Translate the identity if required
            if (identities[i] instanceof String)
            {
                IdentityStringTranslator idStringTranslator = getNucleusContext().getIdentityManager().getIdentityStringTranslator();
                if (idStringTranslator != null)
                {
                    // DataNucleus extension to translate input identities into valid persistent identities.
                    ids[i] = idStringTranslator.getIdentity(this, (String)identities[i]);
                    continue;
                }
            }

            ids[i] = identities[i];
        }

        Map pcById = new HashMap(identities.length);
        List idsToFind = new ArrayList();
        ApiAdapter api = getApiAdapter();

        // Check the L1 cache
        for (int i=0;i<ids.length;i++)
        {
            Object pc = getObjectFromLevel1Cache(ids[i]);
            if (pc != null)
            {
                if (ids[i] instanceof SCOID)
                {
                    if (api.isPersistent(pc) && !api.isNew(pc) && !api.isDeleted(pc) && !api.isTransactional(pc))
                    {
                        // JDO [5.4.4] Can't return HOLLOW nondurable objects
                        throw new NucleusUserException(Localiser.msg("010005"));
                    }
                }

                pcById.put(ids[i], pc);
            }
            else
            {
                idsToFind.add(ids[i]);
            }
        }

        if (!idsToFind.isEmpty() && l2CacheEnabled)
        {
            // Check the L2 cache for those not found
            Map pcsById = getObjectsFromLevel2Cache(idsToFind);
            if (!pcsById.isEmpty())
            {
                // Found some so add to the values, and remove from the "toFind" list
                Iterator<Map.Entry> entryIter = pcsById.entrySet().iterator();
                while (entryIter.hasNext())
                {
                    Map.Entry entry = entryIter.next();
                    pcById.put(entry.getKey(), entry.getValue());
                    idsToFind.remove(entry.getKey());
                }
            }
        }

        boolean performValidationWhenCached = nucCtx.getConfiguration().getBooleanProperty(PropertyNames.PROPERTY_FIND_OBJECT_VALIDATE_WHEN_CACHED);
        List<ObjectProvider> opsToValidate = new ArrayList<>();
        if (validate)
        {
            if (performValidationWhenCached)
            {
                // Mark all ObjectProviders for validation (performed at end)
                Collection pcValues = pcById.values();
                for (Object pc : pcValues)
                {
                    if (api.isTransactional(pc))
                    {
                        // This object is transactional, so no need to validate
                        continue;
                    }

                    // Mark this object for validation
                    ObjectProvider op = findObjectProvider(pc);
                    opsToValidate.add(op);
                }
            }
        }

        Object[] foundPcs = null;
        if (!idsToFind.isEmpty())
        {
            // Try to find unresolved objects direct from the datastore if supported by the datastore (e.g ODBMS)
            foundPcs = getStoreManager().getPersistenceHandler().findObjects(this, idsToFind.toArray());
        }

        int foundPcIdx = 0;
        for (Object id : idsToFind)
        {
            Object idOrig = id; // Id target class could change due to inheritance level
            Object pc = foundPcs != null ? foundPcs[foundPcIdx++] : null;
            ObjectProvider op = null;
            if (pc != null)
            {
                // Object created by store plugin
                op = findObjectProvider(pc);
                putObjectIntoLevel1Cache(op);
            }
            else
            {
                // Object not found yet, so maybe class name is not correct inheritance level
                ClassDetailsForId details = getClassDetailsForId(id, null, validate);
                String className = details.className;
                id = details.id;
                if (details.pc != null)
                {
                    // Found in cache from updated id
                    pc = details.pc;
                    op = findObjectProvider(pc);
                    if (performValidationWhenCached && validate)
                    {
                        if (!api.isTransactional(pc))
                        {
                            // Mark this object for validation
                            opsToValidate.add(op);
                        }
                    }
                }
                else
                {
                    // Still not found so create a Hollow instance with the supplied field values
                    try
                    {
                        Class pcClass = clr.classForName(className, (id instanceof DatastoreId) ? null : id.getClass().getClassLoader());
                        if (Modifier.isAbstract(pcClass.getModifiers()))
                        {
                            // This class is abstract so impossible to have an instance of this type
                            throw new NucleusObjectNotFoundException(Localiser.msg("010027", IdentityUtils.getPersistableIdentityForId(id), className));
                        }

                        op = nucCtx.getObjectProviderFactory().newForHollow(this, pcClass, id);
                        pc = op.getObject();
                        if (!validate)
                        {
                            // Mark the ObjectProvider as needing to validate this object before loading fields
                            op.markForInheritanceValidation();
                        }

                        putObjectIntoLevel1Cache(op); // Cache it in case we have bidir relations
                    }
                    catch (ClassNotResolvedException e)
                    {
                        NucleusLogger.PERSISTENCE.warn(Localiser.msg("010027", IdentityUtils.getPersistableIdentityForId(id)));
                        throw new NucleusUserException(Localiser.msg("010027", IdentityUtils.getPersistableIdentityForId(id)), e);
                    }

                    if (validate)
                    {
                        // Mark this object for validation
                        opsToValidate.add(op);
                    }
                }
            }

            // Put in map under input id, so we find it later
            pcById.put(idOrig, pc);
        }

        if (!opsToValidate.isEmpty())
        {
            // Validate the objects that need it
            try
            {
                getStoreManager().getPersistenceHandler().locateObjects(opsToValidate.toArray(new ObjectProvider[opsToValidate.size()]));
            }
            catch (NucleusObjectNotFoundException nonfe)
            {
                NucleusObjectNotFoundException[] nonfes = (NucleusObjectNotFoundException[]) nonfe.getNestedExceptions();
                if (nonfes != null)
                {
                    for (int i=0;i<nonfes.length;i++)
                    {
                        Object missingId = nonfes[i].getFailedObject();
                        removeObjectFromLevel1Cache(missingId);
                    }
                }
                throw nonfe;
            }
        }

        Object[] objs = new Object[ids.length];
        for (int i=0;i<ids.length;i++)
        {
            Object id = ids[i];
            objs[i] = pcById.get(id);
        }

        return objs;
    }

    /**
     * Accessor for an object given the object id. If validate is false, we return the object
     * if found in the cache, or otherwise a Hollow object with that id. If validate is true
     * we check with the datastore and return an object with the FetchPlan fields loaded.
     * TODO Would be nice, when using checkInheritance, to be able to specify the "id" is an instance of class X or subclass. See IdentityUtils where we have the min class
     * @param id Id of the object.
     * @param validate Whether to validate the object state
     * @param checkInheritance Whether look to the database to determine which class this object is.
     * @param objectClassName Class name for the object with this id (if known, optional)
     * @return The Object with this id
     * @throws NucleusObjectNotFoundException if the object doesn't exist in the datastore
     */
    public Object findObject(Object id, boolean validate, boolean checkInheritance, String objectClassName)
    {
        if (id == null)
        {
            throw new NucleusUserException(Localiser.msg("010044"));
        }

        IdentityStringTranslator translator = getNucleusContext().getIdentityManager().getIdentityStringTranslator();
        if (translator != null && id instanceof String)
        {
            // DataNucleus extension to translate input identities into valid persistent identities.
            id = translator.getIdentity(this, (String)id);
        }
        ApiAdapter api = getApiAdapter();
        boolean fromCache = false;

        // try to find object in cache(s)
        Object pc = getObjectFromCache(id);
        ObjectProvider op = null;
        if (pc != null)
        {
            // Found in L1/L2 cache
            fromCache = true;
            if (id instanceof SCOID)
            {
                if (api.isPersistent(pc) && !api.isNew(pc) && !api.isDeleted(pc) && !api.isTransactional(pc))
                {
                    // JDO [5.4.4] Cant return HOLLOW nondurable objects
                    throw new NucleusUserException(Localiser.msg("010005"));
                }
            }
            if (api.isTransactional(pc))
            {
                // JDO [12.6.5] If there's already an object with the same id and it's transactional, return it
                return pc;
            }
            op = findObjectProvider(pc);
        }
        else
        {
            // Find it direct from the store if the store supports that
            pc = getStoreManager().getPersistenceHandler().findObject(this, id);
            if (pc != null)
            {
                op = findObjectProvider(pc);
                putObjectIntoLevel1Cache(op);
                putObjectIntoLevel2Cache(op, false);
            }
            else
            {
                // Object not found yet, so maybe class name is not correct inheritance level
                ClassDetailsForId details = getClassDetailsForId(id, objectClassName, checkInheritance);
                String className = details.className;
                id = details.id;
                if (details.pc != null)
                {
                    // Found during inheritance check via the cache
                    pc = details.pc;
                    op = findObjectProvider(pc);
                    fromCache = true;
                }
                else
                {
                    // Still not found, so create a Hollow instance with supplied PK values if possible
                    try
                    {
                        Class pcClass = clr.classForName(className, (id instanceof DatastoreId) ? null : id.getClass().getClassLoader());
                        if (Modifier.isAbstract(pcClass.getModifiers()))
                        {
                            // This class is abstract so impossible to have an instance of this type
                            throw new NucleusObjectNotFoundException(Localiser.msg("010027", IdentityUtils.getPersistableIdentityForId(id), className));
                        }

                        op = nucCtx.getObjectProviderFactory().newForHollow(this, pcClass, id);
                        pc = op.getObject();
                        if (!checkInheritance && !validate)
                        {
                            // Mark the ObjectProvider as needing to validate this object before loading fields
                            op.markForInheritanceValidation();
                        }

                        // Cache the object in case we have bidirectional relations that would need to find this
                        putObjectIntoLevel1Cache(op);
                    }
                    catch (ClassNotResolvedException e)
                    {
                        NucleusLogger.PERSISTENCE.warn(Localiser.msg("010027", IdentityUtils.getPersistableIdentityForId(id)));
                        throw new NucleusUserException(Localiser.msg("010027", IdentityUtils.getPersistableIdentityForId(id)), e);
                    }
                }
            }
        }

        boolean performValidationWhenCached = nucCtx.getConfiguration().getBooleanProperty(PropertyNames.PROPERTY_FIND_OBJECT_VALIDATE_WHEN_CACHED);
        if (validate && (!fromCache || performValidationWhenCached))
        {
            // User requests validation of the instance so go to the datastore to validate it
            // loading any fetchplan fields that are needed in the process.
            if (!fromCache)
            {
                // Cache the object in case we have bidirectional relations that would need to find this
                putObjectIntoLevel1Cache(op);
            }

            try
            {
                op.validate();

                if (op.getObject() != pc)
                {
                    // Underlying object was changed in the validation process. This can happen when the datastore
                    // is responsible for managing object references and it no longer recognises the cached value.
                    fromCache = false;
                    pc = op.getObject();
                    putObjectIntoLevel1Cache(op);
                }
            }
            catch (NucleusObjectNotFoundException onfe)
            {
                // Object doesn't exist, so remove from L1 cache
                removeObjectFromLevel1Cache(op.getInternalObjectId());
                throw onfe;
            }
        }

        if (!fromCache)
        {
            // Cache the object (update it if already present)
            putObjectIntoLevel2Cache(op, false);
        }

        return pc;
    }

    private static class ClassDetailsForId
    {
        Object id;
        String className;
        Object pc;
        public ClassDetailsForId(Object id, String className, Object pc)
        {
            this.id = id;
            this.className = className;
            this.pc = pc;
        }
    }

    /**
     * Convenience method that takes an id, an optional class name for the object it represents, and whether
     * to check for inheritance, and returns class details of the object being represented.
     * Used by the findObject process.
     * @param id The identity
     * @param objectClassName Class name for the object (if known, otherwise is derived)
     * @param checkInheritance Whether to check the inheritance level for this id
     * @return The details for the class
     */
    private ClassDetailsForId getClassDetailsForId(Object id, String objectClassName, boolean checkInheritance)
    {
        // Object not found yet, so work out class name
        String className = null;
        String originalClassName = null;
        boolean checkedClassName = false;
        if (id instanceof SCOID)
        {
            throw new NucleusUserException(Localiser.msg("010006"));
        }
        else if (id instanceof DatastoreUniqueLongId)
        {
            // Should have been found using "persistenceHandler.findObject()"
            throw new NucleusObjectNotFoundException(Localiser.msg("010026"), id);
        }
        else if (objectClassName != null)
        {
            // Object class name specified so use that directly
            originalClassName = objectClassName;
        }
        else
        {
            originalClassName = getStoreManager().manageClassForIdentity(id, clr);
        }

        if (originalClassName == null)
        {
            // We dont know the object class so try to deduce it from what is known by the StoreManager
            originalClassName = getClassNameForObjectId(id);
            checkedClassName = true;
        }

        Object pc = null;
        if (checkInheritance)
        {
            // Validate the inheritance level
            className = checkedClassName ? originalClassName : getClassNameForObjectId(id);
            if (className == null)
            {
                throw new NucleusObjectNotFoundException(Localiser.msg("010026"), id);
            }

            if (!checkedClassName)
            {
                // Check if this id for any known subclasses is in the cache to save searching
                if (IdentityUtils.isDatastoreIdentity(id) || IdentityUtils.isSingleFieldIdentity(id))
                {
                    String[] subclasses = getMetaDataManager().getSubclassesForClass(className, true);
                    if (subclasses != null)
                    {
                        for (int i=0;i<subclasses.length;i++)
                        {
                            Object oid = null;
                            if (IdentityUtils.isDatastoreIdentity(id))
                            {
                                oid = nucCtx.getIdentityManager().getDatastoreId(subclasses[i], IdentityUtils.getTargetKeyForDatastoreIdentity(id));
                            }
                            else if (IdentityUtils.isSingleFieldIdentity(id))
                            {
                                oid = nucCtx.getIdentityManager().getSingleFieldId(id.getClass(), getClassLoaderResolver().classForName(subclasses[i]), 
                                    IdentityUtils.getTargetKeyForSingleFieldIdentity(id));
                            }
                            pc = getObjectFromCache(oid);
                            if (pc != null)
                            {
                                className = subclasses[i];
                                break;
                            }
                        }
                    }
                }
            }

            if (pc == null && originalClassName != null && !originalClassName.equals(className))
            {
                // Inheritance check implies different inheritance level, so retry
                if (IdentityUtils.isDatastoreIdentity(id))
                {
                    // Create new OID using correct target class, and recheck cache
                    id = nucCtx.getIdentityManager().getDatastoreId(className, ((DatastoreId)id).getKeyAsObject());
                    pc = getObjectFromCache(id);
                }
                else if (IdentityUtils.isSingleFieldIdentity(id))
                {
                    // Create new SingleFieldIdentity using correct targetClass, and recheck cache
                    id = nucCtx.getIdentityManager().getSingleFieldId(id.getClass(), clr.classForName(className), IdentityUtils.getTargetKeyForSingleFieldIdentity(id));
                    pc = getObjectFromCache(id);
                }
            }
        }
        else
        {
            className = originalClassName;
        }
        return new ClassDetailsForId(id, className, pc);
    }

    private String getClassNameForObjectId(Object id)
    {
        String className = getStoreManager().manageClassForIdentity(id, clr);
        if (className == null) // Datastore id and single-field identity will have had targetClassName set, so must be user-provided application identity
        {
            Collection<AbstractClassMetaData> cmds = getMetaDataManager().getClassMetaDataWithApplicationId(id.getClass().getName());
            if (cmds != null && cmds.size() == 1)
            {
                // Only 1 possible class using this id type, so return it
                return cmds.iterator().next().getFullClassName();
            }
        }

        if (className != null)
        {
            String[] subclasses = getMetaDataManager().getConcreteSubclassesForClass(className);
            int numConcrete = 0;
            String concreteClassName = null;
            if (subclasses != null && subclasses.length > 0)
            {
                numConcrete = subclasses.length;
                concreteClassName = subclasses[0];
            }
            Class rootCls = clr.classForName(className);
            if (!Modifier.isAbstract(rootCls.getModifiers()))
            {
                concreteClassName = className;
                numConcrete++;
            }
            if (numConcrete == 1)
            {
                // Single possible concrete class, so return it
                return concreteClassName;
            }
        }

        // If we have multiple possible classes then refer to the datastore to resolve it
        return getStoreManager().getClassNameForObjectID(id, clr, this);
    }

    /**
     * This method returns an object id instance corresponding to the pcClass and key arguments.
     * Operates in 2 modes :-
     * <ul>
     * <li>The class uses SingleFieldIdentity and the key is the value of the key field</li>
     * <li>In all other cases the key is the String form of the object id instance</li>
     * </ul>
     * @param pcClass Class of the persistable object to create the identity for
     * @param key Value of the key for SingleFieldIdentity (or the toString value)
     * @return The new object-id instance
     */
    public Object newObjectId(Class pcClass, Object key)
    {
        if (pcClass == null)
        {
            throw new NucleusUserException(Localiser.msg("010028"));
        }
        assertClassPersistable(pcClass);

        AbstractClassMetaData cmd = getMetaDataManager().getMetaDataForClass(pcClass, clr);
        if (cmd == null)
        {
            throw new NoPersistenceInformationException(pcClass.getName());
        }

        // If the class is not yet managed, manage it
        if (!getStoreManager().managesClass(cmd.getFullClassName()))
        {
            getStoreManager().manageClasses(clr, cmd.getFullClassName());
        }

        IdentityKeyTranslator translator = getNucleusContext().getIdentityManager().getIdentityKeyTranslator();
        if (translator != null)
        {
            // Use the provided translator to convert it
            key = translator.getKey(this, pcClass, key);
        }

        Object id = null;
        if (cmd.usesSingleFieldIdentityClass())
        {
            // Single Field Identity
            if (getBooleanProperty(PropertyNames.PROPERTY_FIND_OBJECT_TYPE_CONVERSION) && translator == null && !key.getClass().getName().equals(cmd.getObjectidClass()))
            {
                // key provided is intended to be the type of the PK member, so provide convenience type conversion to the actual type required
                AbstractMemberMetaData mmd = cmd.getMetaDataForMember(cmd.getPrimaryKeyMemberNames()[0]);
                if (!mmd.getType().isAssignableFrom(key.getClass()))
                {
                    Object convKey = TypeConversionHelper.convertTo(key, mmd.getType());
                    if (convKey != null)
                    {
                        key = convKey;
                    }
                }
            }

            id = nucCtx.getIdentityManager().getSingleFieldId(clr.classForName(cmd.getObjectidClass()), pcClass, key);
        }
        else if (key instanceof java.lang.String)
        {
            // String-based PK (datastore identity or application identity)
            if (cmd.getIdentityType() == IdentityType.APPLICATION)
            {
                if (Modifier.isAbstract(pcClass.getModifiers()) && cmd.getObjectidClass() != null) 
                {
                    try
                    {
                        Constructor c = clr.classForName(cmd.getObjectidClass()).getDeclaredConstructor(new Class[] {java.lang.String.class});
                        id = c.newInstance(new Object[] {(String)key});
                    }
                    catch(Exception e) 
                    {
                        String msg = Localiser.msg("010030", cmd.getObjectidClass(), cmd.getFullClassName());
                        NucleusLogger.PERSISTENCE.error(msg, e);
                        throw new NucleusUserException(msg);
                    }
                }
                else
                {
                    clr.classForName(pcClass.getName(), true);
                    id = nucCtx.getIdentityManager().getApplicationId(pcClass, key);
                }
            }
            else
            {
                id = nucCtx.getIdentityManager().getDatastoreId((String)key);
            }
        }
        else
        {
            // Key is not a string, and is not SingleFieldIdentity
            throw new NucleusUserException(Localiser.msg("010029", pcClass.getName(), key.getClass().getName()));
        }

        return id;
    }

    /**
     * This method returns an object id instance corresponding to the class name, and the passed
     * object (when using app identity).
     * @param className Name of the class of the object.
     * @param pc The persistable object. Used for application-identity
     * @return A new object ID.
     */
    public Object newObjectId(String className, Object pc)
    {
        AbstractClassMetaData cmd = getMetaDataManager().getMetaDataForClass(className, clr); 
        if (cmd.getIdentityType() == IdentityType.DATASTORE)
        {
            // Populate any strategy value for the "datastore-identity" element
            return nucCtx.getIdentityManager().getDatastoreId(cmd.getFullClassName(), getStoreManager().getValueGenerationStrategyValue(this, cmd, -1));
        }
        else if (cmd.getIdentityType() == IdentityType.APPLICATION)
        {
            return nucCtx.getIdentityManager().getApplicationId(pc, cmd); // All values will have been populated before arriving here
        }
        else
        {
            // All "nondurable" cases (e.g views) will come through here
            return new SCOID(className);
        }
    }

    /**
     * Method to clear an object from the list of dirty objects.
     * @param op The ObjectProvider
     */
    public void clearDirty(ObjectProvider op)
    {
        dirtyOPs.remove(op);
        indirectDirtyOPs.remove(op);
    }

    /**
     * Method to clear all objects marked as dirty (whether directly or indirectly).
     */
    public void clearDirty()
    {
        dirtyOPs.clear();
        indirectDirtyOPs.clear();
    }

    /**
     * Method to mark an object (ObjectProvider) as dirty.
     * @param op ObjectProvider
     * @param directUpdate Whether the object has had a direct update made on it (if known)
     */
    public void markDirty(ObjectProvider op, boolean directUpdate)
    {
        if (tx.isCommitting() && !tx.isActive())
        {
            //post commit cannot change objects (sanity check - avoid changing avoids on detach)
            throw new NucleusException("Cannot change objects when transaction is no longer active.");
        }

        boolean isInDirty = dirtyOPs.contains(op);
        boolean isInIndirectDirty = indirectDirtyOPs.contains(op);
        if (!isDelayDatastoreOperationsEnabled() && !isInDirty && !isInIndirectDirty && 
            dirtyOPs.size() >= getNucleusContext().getConfiguration().getIntProperty(PropertyNames.PROPERTY_FLUSH_AUTO_OBJECT_LIMIT))
        {
            // Reached flush limit so flush
            flushInternal(false);
        }

        if (directUpdate)
        {
            if (isInIndirectDirty)
            {
                indirectDirtyOPs.remove(op);
                dirtyOPs.add(op);
            }
            else if (!isInDirty)
            {
                dirtyOPs.add(op);
                if (l2CacheTxIds != null && nucCtx.isClassCacheable(op.getClassMetaData()))
                {
                    l2CacheTxIds.add(op.getInternalObjectId());
                }
            }
        }
        else
        {
            if (!isInDirty && !isInIndirectDirty)
            {
                // Register as an indirect dirty
                indirectDirtyOPs.add(op);
                if (l2CacheTxIds != null && nucCtx.isClassCacheable(op.getClassMetaData()))
                {
                    l2CacheTxIds.add(op.getInternalObjectId());
                }
            }
        }
    }

    /**
     * Accessor for whether to manage relationships at flush/commit.
     * @return Whether to manage relationships at flush/commit.
     */
    public boolean getManageRelations()
    {
        return managedRelationsHandler != null;
    }

    /**
     * Method to return the RelationshipManager for the ObjectProvider.
     * If we are currently managing relations and the ObjectProvider has no RelationshipManager allocated then one is created.
     * @param op ObjectProvider
     * @return The RelationshipManager
     */
    public RelationshipManager getRelationshipManager(ObjectProvider op)
    {
        return managedRelationsHandler != null ? managedRelationsHandler.getRelationshipManagerForObjectProvider(op) : null;
    }

    /**
     * Returns whether this context is currently performing the manage relationships task.
     * @return Whether in the process of managing relations
     */
    public boolean isManagingRelations()
    {
        return (managedRelationsHandler != null) ? managedRelationsHandler.isExecuting() : false;
    }

    /**
     * Convenience method to inspect the list of objects with outstanding changes to flush.
     * @return ObjectProviders for the objects to be flushed.
     */
    public List<ObjectProvider> getObjectsToBeFlushed()
    {
        List<ObjectProvider> ops = new ArrayList<>();
        ops.addAll(dirtyOPs);
        ops.addAll(indirectDirtyOPs);
        return ops;
    }

    /**
     * Returns whether the context is in the process of flushing.
     * @return true if the context is flushing
     */
    public boolean isFlushing()
    {
        return flushing > 0;
    }

    /**
     * Method callable from external APIs for user-management of flushing.
     * Called by JDO PM.flush, or JPA EM.flush().
     * Performs management of relations, prior to performing internal flush of all dirty/new/deleted instances to the datastore.
     */
    public void flush()
    {
        if (tx.isActive())
        {
            // Managed Relationships
            if (managedRelationsHandler != null)
            {
                managedRelationsHandler.execute();
            }

            // Perform internal flush
            flushInternal(true);

            if (!dirtyOPs.isEmpty() || !indirectDirtyOPs.isEmpty())
            {
                // If the flush caused the attach of an object it can get registered as dirty, so do second pass
                NucleusLogger.PERSISTENCE.debug("Flush pass 1 resulted in " + (dirtyOPs.size() + indirectDirtyOPs.size()) + " additional objects being made dirty. Performing flush pass 2");
                flushInternal(true);
            }

            if (operationQueue != null && !operationQueue.getOperations().isEmpty())
            {
                NucleusLogger.PERSISTENCE.warn("Queue of operations after flush() is not empty! Generate a testcase and report this. See below (debug) for full details of unflushed ops");
                operationQueue.log();
            }
        }
    }

    /**
     * This method flushes all dirty, new, and deleted instances to the datastore.
     * @param flushToDatastore Whether to ensure any changes reach the datastore
     *     Otherwise they will be flushed to the datastore manager and leave it to
     *     decide the opportune moment to actually flush them to the datastore
     * @throws NucleusOptimisticException when optimistic locking error(s) occur
     */
    public void flushInternal(boolean flushToDatastore)
    {
        if (!flushToDatastore && dirtyOPs.isEmpty() && indirectDirtyOPs.isEmpty())
        {
            // Nothing to flush so abort now
            return;
        }

        if (!tx.isActive())
        {
            // Non transactional flush, so store the ids for later
            if (nontxProcessedOPs == null)
            {
                nontxProcessedOPs = new HashSet<>();
            }
            nontxProcessedOPs.addAll(dirtyOPs);
            nontxProcessedOPs.addAll(indirectDirtyOPs);
        }

        flushing++;
        try
        {
            if (flushToDatastore)
            {
                // Make sure flushes its changes to the datastore
                tx.preFlush();
            }

            // Retrieve the appropriate flush process, and execute it
            FlushProcess flusher = getStoreManager().getFlushProcess();
            List<NucleusOptimisticException> optimisticFailures = flusher.execute(
                    this, new ArrayList<>(dirtyOPs), new ArrayList<>(indirectDirtyOPs), operationQueue);

            if (flushToDatastore)
            {
                // Make sure flushes its changes to the datastore
                tx.flush();
            }

            if (optimisticFailures != null)
            {
                // Throw a single NucleusOptimisticException containing all optimistic failures
                throw new NucleusOptimisticException(Localiser.msg("010031"), optimisticFailures.toArray(new Throwable[optimisticFailures.size()]));
            }
        }
        finally
        {
            if (NucleusLogger.PERSISTENCE.isDebugEnabled())
            {
                NucleusLogger.PERSISTENCE.debug(Localiser.msg("010004"));
            }
            flushing--;
        }
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#getOperationQueue()
     */
    public OperationQueue getOperationQueue()
    {
        return operationQueue;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#addOperationToQueue(org.datanucleus.flush.Operation)
     */
    public void addOperationToQueue(Operation oper)
    {
        if (operationQueue == null)
        {
            operationQueue = new OperationQueue();
        }
        operationQueue.enqueue(oper);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#flushSCOOperationsForBackingStore(org.datanucleus.store.scostore.Store, org.datanucleus.state.ObjectProvider)
     */
    public void flushOperationsForBackingStore(Store backingStore, ObjectProvider op)
    {
        if (operationQueue != null)
        {
            // TODO Remove this when NUCCORE-904 is implemented, and process operationQueue in flush()
            operationQueue.performAll(backingStore, op);
        }
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#operationQueueIsActive()
     */
    @Override
    public boolean operationQueueIsActive()
    {
        return isDelayDatastoreOperationsEnabled() && !isFlushing() && getTransaction().isActive();
    }

    /**
     * Method to perform any post-begin checks.
     */
    public void postBegin()
    {
        ObjectProvider[] ops = dirtyOPs.toArray(new ObjectProvider[dirtyOPs.size()]);
        for (int i=0; i<ops.length; i++)
        {
            ops[i].preBegin(tx);
        }
        ops = indirectDirtyOPs.toArray(new ObjectProvider[indirectDirtyOPs.size()]);
        for (int i=0; i<ops.length; i++)
        {
            ops[i].preBegin(tx);
        }
    }

    /**
     * Method to perform any pre-commit checks.
     */
    public void preCommit()
    {
        if (cache != null && !cache.isEmpty())
        {
            // Check for objects that are managed but not dirty, yet require a version update
            Collection<ObjectProvider> cachedOPs = new HashSet<>(cache.values());
            for (ObjectProvider cachedOP : cachedOPs)
            {
                LockMode lockMode = getLockManager().getLockMode(cachedOP);
                if (cachedOP != null && cachedOP.isFlushedToDatastore() && cachedOP.getClassMetaData().isVersioned() && 
                        (lockMode == LockMode.LOCK_OPTIMISTIC_WRITE || lockMode == LockMode.LOCK_PESSIMISTIC_WRITE))
                {
                    // Not dirty, but locking requires a version update, so force it
                    VersionMetaData vermd = cachedOP.getClassMetaData().getVersionMetaDataForClass();
                    if (vermd != null)
                    {
                        if (vermd.getFieldName() != null)
                        {
                            cachedOP.makeDirty((Persistable) cachedOP.getObject(), vermd.getFieldName());
                            dirtyOPs.add(cachedOP);
                        }
                        else
                        {
                            // TODO Cater for surrogate version
                            NucleusLogger.PERSISTENCE.warn("We do not support forced version update with surrogate version columns : " + cachedOP);
                        }
                    }
                }
                else if (cachedOP == null)
                {
                    NucleusLogger.CACHE.error(">> EC.preCommit L1Cache op IS NULL!");
                }
            }
        }

        // Make sure all is flushed before we start
        flush();

        if (pbrAtCommitHandler != null)
        {
            // Persistence-by-reachability at commit
            try
            {
                pbrAtCommitHandler.execute();
            }
            catch (Throwable t)
            {
                NucleusLogger.PERSISTENCE.error(t);
                if (t instanceof NucleusException)
                {
                    throw (NucleusException) t;
                }
                throw new NucleusException("Unexpected error during precommit",t);
            }
        }

        if (l2CacheEnabled)
        {
            // L2 caching of enlisted objects
            performLevel2CacheUpdateAtCommit();
        }

        if (properties.getFrequentProperties().getDetachAllOnCommit())
        {
            // "detach-on-commit"
            performDetachAllOnTxnEndPreparation();
        }
    }

    /**
     * Accessor for whether the object with this identity is modified in the current transaction.
     * Only returns true when using the L2 cache and the object has been modified during the txn.
     * @param id The identity
     * @return Whether it is modified/new/deleted in this transaction
     */
    public boolean isObjectModifiedInTransaction(Object id)
    {
        if (l2CacheTxIds != null)
        {
            return l2CacheTxIds.contains(id);
        }
        return false;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#markFieldsForUpdateInLevel2Cache(java.lang.Object, boolean[])
     */
    public void markFieldsForUpdateInLevel2Cache(Object id, boolean[] fields)
    {
        if (l2CacheTxFieldsToUpdateById == null)
        {
            return;
        }

        BitSet bits = l2CacheTxFieldsToUpdateById.get(id);
        if (bits == null)
        {
            bits = new BitSet();
            l2CacheTxFieldsToUpdateById.put(id, bits);
        }
        for (int i=0;i<fields.length;i++)
        {
            if (fields[i])
            {
                bits.set(i);
            }
        }
    }

    /**
     * Method invoked during commit() to perform updates to the L2 cache.
     * <ul>
     * <li>Any objects modified during the current transaction will be added/updated in the L2 cache.</li>
     * <li>Any objects that aren't modified but have been enlisted will be added to the L2 cache.</li>
     * <li>Any objects that are modified but no longer enlisted (due to garbage collection) will be
     * removed from the L2 cache (to avoid giving out old data).</li>
     * </ul>
     */
    private void performLevel2CacheUpdateAtCommit()
    {
        if (l2CacheTxIds == null)
        {
            return;
        }
        String cacheStoreMode = getLevel2CacheStoreMode();
        if ("bypass".equalsIgnoreCase(cacheStoreMode))
        {
            // L2 cache storage turned off right now
            return;
        }

        // Process all modified objects adding/updating/removing from L2 cache as appropriate
        Set<ObjectProvider> opsToCache = null;
        Set<Object> idsToRemove = null;
        for (Object id : l2CacheTxIds)
        {
            ObjectProvider op = enlistedOPCache.get(id);
            if (op == null)
            {
                // Modified object either no longer enlisted (GCed) OR is an embedded object without own identity. Remove from L2 if present
                if (NucleusLogger.CACHE.isDebugEnabled())
                {
                    if (nucCtx.getLevel2Cache().containsOid(id))
                    {
                        NucleusLogger.CACHE.debug(Localiser.msg("004014", id));
                    }
                }
                if (idsToRemove == null)
                {
                    idsToRemove = new HashSet<Object>();
                }
                idsToRemove.add(id);
            }
            else
            {
                // Modified object still enlisted so cacheable
                Object obj = op.getObject();
                Object objID = getApiAdapter().getIdForObject(obj);
                if (objID == null || objID instanceof IdentityReference)
                {
                    // Must be embedded
                }
                else if (getApiAdapter().isDeleted(obj))
                {
                    // Object has been deleted so remove from L2 cache
                    if (NucleusLogger.CACHE.isDebugEnabled())
                    {
                        NucleusLogger.CACHE.debug(Localiser.msg("004007", StringUtils.toJVMIDString(obj), op.getInternalObjectId()));
                    }
                    if (idsToRemove == null)
                    {
                        idsToRemove = new HashSet<Object>();
                    }
                    idsToRemove.add(objID);
                }
                else if (!getApiAdapter().isDetached(obj))
                {
                    // Object has been added/modified so update in L2 cache
                    if (opsToCache == null)
                    {
                        opsToCache = new HashSet<>();
                    }
                    opsToCache.add(op);
                    if (l2CacheObjectsToEvictUponRollback == null) 
                    {
                        l2CacheObjectsToEvictUponRollback = new LinkedList<Object>();
                    } 
                    l2CacheObjectsToEvictUponRollback.add(id);
                }
            }
        }

        if (idsToRemove != null && !idsToRemove.isEmpty())
        {
            // Bulk evict from L2 cache
            nucCtx.getLevel2Cache().evictAll(idsToRemove);
        }
        if (opsToCache != null && !opsToCache.isEmpty())
        {
            // Bulk put into L2 cache of required ObjectProviders
            putObjectsIntoLevel2Cache(opsToCache);
        }

        l2CacheTxIds.clear();
        l2CacheTxFieldsToUpdateById.clear();
    }

    /**
     * Method to perform all necessary preparation for detach-all-on-commit/detach-all-on-rollback.
     * Identifies all objects affected and makes sure that all fetch plan fields are loaded.
     */
    private void performDetachAllOnTxnEndPreparation()
    {
        // JDO spec 12.7.3 "Root instances"
        // "Root instances are parameter instances for retrieve, detachCopy, and refresh; result
        // instances for queries. Root instances for DetachAllOnCommit are defined explicitly by
        // the user via the FetchPlan property DetachmentRoots or DetachmentRootClasses. 
        // If not set explicitly, the detachment roots consist of the union of all root instances of
        // methods executed since the last commit or rollback."
        Collection<ObjectProvider> ops = new ArrayList<>();
        Collection roots = fetchPlan.getDetachmentRoots();
        Class[] rootClasses = fetchPlan.getDetachmentRootClasses();
        if (roots != null && !roots.isEmpty())
        {
            // Detachment roots specified
            for (Object root : roots)
            {
                ops.add(findObjectProvider(root));
            }
        }
        else if (rootClasses != null && rootClasses.length > 0)
        {
            // Detachment root classes specified
            ObjectProvider[] txOPs = enlistedOPCache.values().toArray(new ObjectProvider[enlistedOPCache.size()]);
            for (int i=0;i<txOPs.length;i++)
            {
                for (int j=0;j<rootClasses.length;j++)
                {
                    // Check if object is of this root type
                    if (txOPs[i].getObject().getClass() == rootClasses[j])
                    {
                        // This ObjectProvider is for a valid root object
                        ops.add(txOPs[i]);
                        break;
                    }
                }
            }
        }
        else if (cache != null && !cache.isEmpty())
        {
            // Detach all objects in the L1 cache
            ops.addAll(cache.values());
        }

        // Make sure that all FetchPlan fields are loaded
        Iterator<ObjectProvider> opsIter = ops.iterator();
        while (opsIter.hasNext())
        {
            ObjectProvider op = opsIter.next();
            Object pc = op.getObject();
            if (pc != null && !getApiAdapter().isDetached(pc) && !getApiAdapter().isDeleted(pc))
            {
                // Load all fields (and sub-objects) in the FetchPlan
                FetchPlanState state = new FetchPlanState();
                try
                {
                    op.loadFieldsInFetchPlan(state);
                }
                catch (NucleusObjectNotFoundException onfe)
                {
                    // This object doesnt exist in the datastore at this point.
                    // Either the user has some other process that has deleted it or they have
                    // defined datastore based cascade delete and it has been deleted that way
                    NucleusLogger.PERSISTENCE.warn(Localiser.msg("010013", StringUtils.toJVMIDString(pc), op.getInternalObjectId()));
                    opsIter.remove();
                    // TODO Move the object state to P_DELETED for consistency
                }
            }
        }
        detachAllOnTxnEndOPs = ops.toArray(new ObjectProvider[ops.size()]);
    }

    /**
     * Method to perform detach-all-on-commit, using the data identified by
     * performDetachAllOnCommitPreparation().
     */
    private void performDetachAllOnTxnEnd()
    {
        try
        {
            runningDetachAllOnTxnEnd = true;
            if (detachAllOnTxnEndOPs != null)
            {
                // Detach all detachment root objects (causes recursion through the fetch plan)
                ObjectProvider[] opsToDetach = detachAllOnTxnEndOPs;
                DetachState state = new DetachState(getApiAdapter());
                for (int i=0;i<opsToDetach.length;i++)
                {
                    Object pc = opsToDetach[i].getObject();
                    if (pc != null)
                    {
                        opsToDetach[i].detach(state);
                    }
                }
            }
        }
        finally
        {
            detachAllOnTxnEndOPs = null;
            runningDetachAllOnTxnEnd = false;
        }
    }

    /**
     * Accessor for whether this context is currently running detachAllOnCommit.
     * @return Whether running detachAllOnCommit
     */
    public boolean isRunningDetachAllOnCommit()
    {
        return runningDetachAllOnTxnEnd;
    }

    /**
     * Commit any changes made to objects managed by the object manager to the database.
     */
    public void postCommit()
    {
        if (properties.getFrequentProperties().getDetachAllOnCommit())
        {
            // Detach-all-on-commit
            performDetachAllOnTxnEnd();
        }

        List failures = null;
        try
        {
            // Commit all enlisted ObjectProviders
            ApiAdapter api = getApiAdapter();
            ObjectProvider[] ops = enlistedOPCache.values().toArray(new ObjectProvider[enlistedOPCache.size()]);
            for (int i = 0; i < ops.length; ++i)
            {
                try
                {
                    // Perform any operations required after committing
                    // TODO this if is due to sms that can have lc == null, why?, should not be here then
                    if (ops[i] != null && ops[i].getObject() != null &&
                            (api.isPersistent(ops[i].getObject()) || api.isTransactional(ops[i].getObject())))
                    {
                        ops[i].postCommit(getTransaction());

                        // TODO Change this check so that we remove all objects that are no longer suitable for caching
                        if (properties.getFrequentProperties().getDetachAllOnCommit() && api.isDetachable(ops[i].getObject()))
                        {
                            // "DetachAllOnCommit" - Remove the object from the L1 cache since it is now detached
                            removeObjectProviderFromCache(ops[i]);
                        }
                    }
                }
                catch (RuntimeException e)
                {
                    if (failures == null)
                    {
                        failures = new ArrayList();
                    }
                    failures.add(e);
                }
            }
        }
        finally
        {
            resetTransactionalVariables();
        }
        if (failures != null && !failures.isEmpty())
        {
            throw new CommitStateTransitionException((Exception[]) failures.toArray(new Exception[failures.size()]));
        }
    }

    /**
     * Rollback any changes made to objects managed by the object manager to the database.
     */
    public void preRollback()
    {
        List<Exception> failures = null;
        try
        {
            Collection<ObjectProvider> ops = enlistedOPCache.values();
            Iterator<ObjectProvider> opsIter = ops.iterator();
            while (opsIter.hasNext())
            {
                ObjectProvider op = opsIter.next();
                try
                {
                    op.preRollback(getTransaction());
                }
                catch (RuntimeException e)
                {
                    if (failures == null)
                    {
                        failures = new ArrayList();
                    }
                    failures.add(e);
                }
            }
            clearDirty();
        }
        finally
        {
            resetTransactionalVariables();
        }

        if (failures != null && !failures.isEmpty())
        {
            throw new RollbackStateTransitionException(failures.toArray(new Exception[failures.size()]));
        }

        if (getBooleanProperty(PropertyNames.PROPERTY_DETACH_ALL_ON_ROLLBACK))
        {
            // "detach-on-rollback"
            performDetachAllOnTxnEndPreparation();
        }
    }

    /**
     * Callback invoked after the actual datastore rollback.
     */
    public void postRollback()
    {
        if (getBooleanProperty(PropertyNames.PROPERTY_DETACH_ALL_ON_ROLLBACK))
        {
            // "detach-on-rollback"
            performDetachAllOnTxnEnd();
        }

        if (l2CacheObjectsToEvictUponRollback != null)
        {
            nucCtx.getLevel2Cache().evictAll(l2CacheObjectsToEvictUponRollback);
            l2CacheObjectsToEvictUponRollback = null;
        }
    }

    /**
     * Convenience method to reset all state variables for the transaction, performed at commit/rollback.
     */
    private void resetTransactionalVariables()
    {
        if (pbrAtCommitHandler != null)
        {
            pbrAtCommitHandler.clear();
        }

        enlistedOPCache.clear();
        dirtyOPs.clear();
        indirectDirtyOPs.clear();
        fetchPlan.resetDetachmentRoots();
        if (managedRelationsHandler != null)
        {
            managedRelationsHandler.clear();
        }

        // L2 cache processing
        if (l2CacheTxIds != null)
        {
            l2CacheTxIds.clear();
        }
        if (l2CacheTxFieldsToUpdateById != null)
        {
            l2CacheTxFieldsToUpdateById.clear();
        }

        if (operationQueue != null)
        {
            operationQueue.clear();
        }
        opAttachDetachObjectReferenceMap = null;

        // Clear any locking requirements
        lockMgr.clear();
    }

    // -------------------------------------- Cache Management ---------------------------------------

    protected String getLevel2CacheRetrieveMode()
    {
        return properties.getFrequentProperties().getLevel2CacheRetrieveMode();
    }

    protected String getLevel2CacheStoreMode()
    {
        return properties.getFrequentProperties().getLevel2CacheStoreMode();
    }

    /**
     * Convenience method to add an object to the L1 cache.
     * @param op The ObjectProvider
     */
    public void putObjectIntoLevel1Cache(ObjectProvider op)
    {
        if (cache != null)
        {
            Object id = op.getInternalObjectId();
            if (id == null || op.getObject() == null)
            {
                NucleusLogger.CACHE.warn(Localiser.msg("003006"));
                return;
            }

            // TODO This creates problem in JPA1 TCK entityTest.oneXmany.test3 Investigate it
/*            if (cache.containsKey(id))
            {
                // Id already has an object in the L1 cache, so no need to update
                // Note : can only have a single object with a particular id for an ExecutionContext
                return;
            }*/

            if (op.getClassMetaData().getUniqueMetaData() != null)
            {
                // Check for any unique keys on this object, and cache against the unique key also
                List<UniqueMetaData> unimds = op.getClassMetaData().getUniqueMetaData();
                if (unimds != null && !unimds.isEmpty())
                {
                    for (UniqueMetaData unimd : unimds)
                    {
                        CacheUniqueKey uniKey = getCacheUniqueKeyForObjectProvider(op, unimd);
                        if (uniKey != null)
                        {
                            cache.putUnique(uniKey, op);
                        }
                    }
                }
            }

            // Put into Level 1 Cache
            Object oldOP = cache.put(id, op);
            if (NucleusLogger.CACHE.isDebugEnabled())
            {
                if (oldOP == null)
                {
                    NucleusLogger.CACHE.debug(Localiser.msg("003004", StringUtils.toJVMIDString(op.getObject()), 
                        IdentityUtils.getPersistableIdentityForId(id), StringUtils.booleanArrayToString(op.getLoadedFields())));
                }
            }
        }
    }

    /**
     * Method to return a CacheUniqueKey to use when caching the object managed by the supplied ObjectProvider for the specified unique key.
     * @param op The ObjectProvider
     * @param unimd The unique key that this key will relate to
     * @return The CacheUniqueKey, or null if any member of the unique key is null, or if the unique key is not defined on members
     */
    private CacheUniqueKey getCacheUniqueKeyForObjectProvider(ObjectProvider op, UniqueMetaData unimd)
    {
        boolean nonNullMembers = true;
        if (unimd.getNumberOfMembers() > 0)
        {
            Object[] fieldVals = new Object[unimd.getNumberOfMembers()];
            for (int i=0;i<fieldVals.length;i++)
            {
                AbstractMemberMetaData mmd = op.getClassMetaData().getMetaDataForMember(unimd.getMemberNames()[i]);
                fieldVals[i] = op.provideField(mmd.getAbsoluteFieldNumber());
                if (fieldVals[i] == null)
                {
                    // One of the unique key fields is null so we don't cache
                    nonNullMembers = false;
                    break;
                }
            }
            if (nonNullMembers)
            {
                return new CacheUniqueKey(op.getClassMetaData().getFullClassName(), unimd.getMemberNames(), fieldVals);
            }
        }
        return null;
    }

    /**
     * Method to add/update the managed object into the L2 cache as long as it isn't modified in the current transaction.
     * @param op ObjectProvider for the object
     * @param updateIfPresent Whether to update it in the L2 cache if already present
     */
    protected void putObjectIntoLevel2Cache(ObjectProvider op, boolean updateIfPresent)
    {
        if (op.getInternalObjectId() == null || !nucCtx.isClassCacheable(op.getClassMetaData()))
        {
            // Cannot cache something with no identity
            return;
        }

        String storeMode = getLevel2CacheStoreMode();
        if (storeMode.equalsIgnoreCase("bypass"))
        {
            return;
        }

        if (l2CacheTxIds != null && !l2CacheTxIds.contains(op.getInternalObjectId()))
        {
            // Object hasn't been modified in this transaction so put in the L2 cache
            putObjectIntoLevel2CacheInternal(op, updateIfPresent);
        }
    }

    /**
     * Convenience method to convert the object managed by the ObjectProvider into a form suitable for caching in an L2 cache.
     * @param op ObjectProvider for the object
     * @param currentCachedPC Current L2 cached object (if any) to use for updating
     * @return The cacheable form of the object
     */
    protected CachedPC getL2CacheableObject(ObjectProvider op, CachedPC currentCachedPC)
    {
        CachedPC cachedPC = null;
        int[] fieldsToUpdate = null;
        if (currentCachedPC != null)
        {
            // Object already L2 cached, create copy of cached object and just update the fields changed here
            cachedPC = currentCachedPC.getCopy();
            cachedPC.setVersion(op.getTransactionalVersion());
            VersionMetaData vermd = op.getClassMetaData().getVersionMetaDataForClass();
            int versionFieldNum = -1;
            if (vermd != null && vermd.getFieldName() != null)
            {
                versionFieldNum = op.getClassMetaData().getMetaDataForMember(vermd.getFieldName()).getAbsoluteFieldNumber();
            }

            BitSet fieldsToUpdateBitSet = l2CacheTxFieldsToUpdateById.get(op.getInternalObjectId());
            if (fieldsToUpdateBitSet != null)
            {
                if (versionFieldNum >= 0 && !fieldsToUpdateBitSet.get(versionFieldNum))
                {
                    // Version is stored in a field, so make sure the field is also updated
                    fieldsToUpdateBitSet.set(versionFieldNum);
                }

                int num = 0;
                for (int i=0;i<fieldsToUpdateBitSet.length();i++)
                {
                    if (fieldsToUpdateBitSet.get(i))
                    {
                        num++;
                    }
                }
                fieldsToUpdate = new int[num];
                int j = 0;
                for (int i=0;i<fieldsToUpdateBitSet.length();i++)
                {
                    if (fieldsToUpdateBitSet.get(i))
                    {
                        fieldsToUpdate[j++] = i;
                    }
                }
            }
            if (fieldsToUpdate == null || fieldsToUpdate.length == 0)
            {
                return null;
            }
            if (NucleusLogger.CACHE.isDebugEnabled())
            {
                int[] loadedFieldNums = cachedPC.getLoadedFieldNumbers();
                String fieldNames = (loadedFieldNums == null || loadedFieldNums.length == 0) ? "" : StringUtils.intArrayToString(loadedFieldNums);
                NucleusLogger.CACHE.debug(Localiser.msg("004015", StringUtils.toJVMIDString(op.getObject()), op.getInternalObjectId(), 
                    fieldNames, cachedPC.getVersion(), StringUtils.intArrayToString(fieldsToUpdate)));
            }
        }
        else
        {
            // Not yet cached
            int[] loadedFieldNumbers = op.getLoadedFieldNumbers();
            if (loadedFieldNumbers == null || loadedFieldNumbers.length == 0)
            {
                // No point caching an object with no loaded fields!
                return null;
            }

            cachedPC = new CachedPC(op.getObject().getClass(), op.getLoadedFields(), op.getTransactionalVersion(), op.getInternalObjectId());
            fieldsToUpdate = loadedFieldNumbers;
            if (NucleusLogger.CACHE.isDebugEnabled())
            {
                int[] loadedFieldNums = cachedPC.getLoadedFieldNumbers();
                String fieldNames = (loadedFieldNums == null || loadedFieldNums.length == 0) ? "" : StringUtils.intArrayToString(loadedFieldNums);
                NucleusLogger.CACHE.debug(Localiser.msg("004003", StringUtils.toJVMIDString(op.getObject()), op.getInternalObjectId(), fieldNames, cachedPC.getVersion()));
            }
        }

        // Set the fields in the CachedPC
        op.provideFields(fieldsToUpdate, new L2CachePopulateFieldManager(op, cachedPC));

        return cachedPC;
    }

    /**
     * Method to put the passed objects into the L2 cache.
     * Performs the "put" in batches
     * @param ops The ObjectProviders whose objects are to be cached
     */
    protected void putObjectsIntoLevel2Cache(Set<ObjectProvider> ops)
    {
        int batchSize = nucCtx.getConfiguration().getIntProperty(PropertyNames.PROPERTY_CACHE_L2_BATCHSIZE);
        Level2Cache l2Cache = nucCtx.getLevel2Cache();
        Map<Object, CachedPC> dataToUpdate = new HashMap<>();
        Map<CacheUniqueKey, CachedPC> dataUniqueToUpdate = new HashMap<>();
        for (ObjectProvider op : ops)
        {
            Object id = op.getInternalObjectId();
            if (id == null || !nucCtx.isClassCacheable(op.getClassMetaData()))
            {
                continue;
            }

            CachedPC currentCachedPC = l2Cache.get(id);
            CachedPC cachedPC = getL2CacheableObject(op, currentCachedPC);
            if (cachedPC != null && !(id instanceof IdentityReference))
            {
                // Only cache if something to be cached and has identity
                dataToUpdate.put(id, cachedPC);
                if (dataToUpdate.size() == batchSize)
                {
                    // Put this batch of objects in the L2 cache
                    l2Cache.putAll(dataToUpdate);
                    dataToUpdate.clear();
                }
            }

            if (op.getClassMetaData().getUniqueMetaData() != null)
            {
                // Check for any unique keys on this object, and cache against the unique key also
                List<UniqueMetaData> unimds = op.getClassMetaData().getUniqueMetaData();
                if (unimds != null && !unimds.isEmpty())
                {
                    for (UniqueMetaData unimd : unimds)
                    {
                        CacheUniqueKey uniKey = getCacheUniqueKeyForObjectProvider(op, unimd);
                        if (uniKey != null)
                        {
                            dataUniqueToUpdate.put(uniKey, cachedPC);
                            if (dataUniqueToUpdate.size() == batchSize)
                            {
                                // Put this batch of unique keyed objects in the L2 cache
                                l2Cache.putUniqueAll(dataUniqueToUpdate);
                                dataUniqueToUpdate.clear();
                            }
                        }
                    }
                }
            }
        }

        // Put all remaining objects
        if (!dataToUpdate.isEmpty())
        {
            l2Cache.putAll(dataToUpdate);
            dataToUpdate.clear();
        }
        if (!dataUniqueToUpdate.isEmpty())
        {
            l2Cache.putUniqueAll(dataUniqueToUpdate);
            dataUniqueToUpdate.clear();
        }
    }

    /**
     * Convenience method to add/update an object in the L2 cache.
     * @param op ObjectProvider of the object to add.
     * @param updateIfPresent Whether to update the L2 cache if it is present
     */
    protected void putObjectIntoLevel2CacheInternal(ObjectProvider op, boolean updateIfPresent)
    {
        Object id = op.getInternalObjectId();
        if (id == null || id instanceof IdentityReference)
        {
            return;
        }

        Level2Cache l2Cache = nucCtx.getLevel2Cache();
        if (!updateIfPresent && l2Cache.containsOid(id))
        {
            // Already present and not wanting to update
            return;
        }

        CachedPC currentCachedPC = l2Cache.get(id);
        CachedPC cachedPC = getL2CacheableObject(op, currentCachedPC);
        if (cachedPC != null)
        {
            l2Cache.put(id, cachedPC);
        }

        if (op.getClassMetaData().getUniqueMetaData() != null)
        {
            // Cache against any unique keys defined for this object
            List<UniqueMetaData> unimds = op.getClassMetaData().getUniqueMetaData();
            if (unimds != null && !unimds.isEmpty())
            {
                for (UniqueMetaData unimd : unimds)
                {
                    CacheUniqueKey uniKey = getCacheUniqueKeyForObjectProvider(op, unimd);
                    if (uniKey != null)
                    {
                        l2Cache.putUnique(uniKey, cachedPC);
                    }
                }
            }
        }
    }

    /**
     * Convenience method to evict an object from the L1 cache.
     * @param id The Persistable object id
     */
    public void removeObjectFromLevel1Cache(Object id)
    {
        if (id != null && cache != null)
        {
            Object pcRemoved = cache.remove(id);
            if (pcRemoved != null && NucleusLogger.CACHE.isDebugEnabled())
            {
                NucleusLogger.CACHE.debug(Localiser.msg("003009", IdentityUtils.getPersistableIdentityForId(id)));
            }
        }
    }

    /**
     * Convenience method to remove the object with the specified identity from the L2 cache.
     * @param id Identity of the object
     */
    public void removeObjectFromLevel2Cache(Object id)
    {
        if (id != null && !(id instanceof IdentityReference))
        {
            Level2Cache l2Cache = nucCtx.getLevel2Cache();
            if (l2Cache.containsOid(id))
            {
                if (NucleusLogger.CACHE.isDebugEnabled())
                {
                    NucleusLogger.CACHE.debug(Localiser.msg("004016", id));
                }
                l2Cache.evict(id);
            }
        }
    }

    /**
     * Whether the specified identity is cached currently. Looks in L1 cache and L2 cache.
     * @param id The identity
     * @return Whether an object exists in the cache(s) with this identity
     */
    public boolean hasIdentityInCache(Object id)
    {
        // Try Level 1 first
        if (cache != null && cache.containsKey(id))
        {
            return true;
        }

        // Try Level 2 since not in Level 1
        if (l2CacheEnabled)
        {
            Level2Cache l2Cache = nucCtx.getLevel2Cache();
            if (l2Cache.containsOid(id))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Convenience method to access an object in the cache.
     * Firstly looks in the L1 cache for this context, and if not found looks in the L2 cache.
     * @param id Id of the object
     * @return Persistable object (with connected ObjectProvider).
     */
    public Object getObjectFromCache(Object id)
    {
        // Try Level 1 first
        Object pc = getObjectFromLevel1Cache(id);
        if (pc != null)
        {
            return pc;
        }

        if (id instanceof SCOID)
        {
            return null;
        }

        // Try Level 2 since not in Level 1
        return getObjectFromLevel2Cache(id);
    }

    /**
     * Convenience method to access objects in the cache.
     * Firstly looks in the L1 cache for this context, and if not found looks in the L2 cache.
     * @param ids Ids of the objects
     * @return Persistable objects (with connected ObjectProviders), or null.
     */
    public Object[] getObjectsFromCache(Object[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return null;
        }
        Object[] objs = new Object[ids.length];

        // Try L1 cache
        Collection idsNotFound = new HashSet();
        for (int i=0;i<ids.length;i++)
        {
            objs[i] = getObjectFromLevel1Cache(ids[i]);
            if (objs[i] == null)
            {
                idsNotFound.add(ids[i]);
            }
        }

        // Try L2 cache
        if (!idsNotFound.isEmpty())
        {
            Map l2ObjsById = getObjectsFromLevel2Cache(idsNotFound);
            for (int i=0;i<ids.length;i++)
            {
                if (objs[i] == null)
                {
                    objs[i] = l2ObjsById.get(ids[i]);
                }
            }
        }

        return objs;
    }

    /**
     * Convenience method to access an object in the Level 1 cache.
     * @param id Id of the object
     * @return Persistable object (with connected ObjectProvider).
     */
    public Object getObjectFromLevel1Cache(Object id)
    {
        Object pc = null;
        ObjectProvider op = null;

        if (cache != null)
        {
            op = cache.get(id);
            if (op != null)
            {
                pc = op.getObject();
                if (NucleusLogger.CACHE.isDebugEnabled())
                {
                    NucleusLogger.CACHE.debug(Localiser.msg("003008", StringUtils.toJVMIDString(pc), IdentityUtils.getPersistableIdentityForId(id), 
                        StringUtils.booleanArrayToString(op.getLoadedFields())));
                }

                // Wipe the detach state that may have been added if the object has been serialised in the meantime
                op.resetDetachState();

                return pc;
            }

            if (NucleusLogger.CACHE.isDebugEnabled())
            {
                NucleusLogger.CACHE.debug(Localiser.msg("003007", IdentityUtils.getPersistableIdentityForId(id)));
            }
        }
        return null;
    }

    /**
     * Convenience method to access an object in the Level 2 cache.
     * @param id Id of the object
     * @return Persistable object (with connected ObjectProvider).
     */
    protected Object getObjectFromLevel2Cache(Object id)
    {
        Object pc = null;

        if (l2CacheEnabled)
        {
            if (!nucCtx.isClassWithIdentityCacheable(id))
            {
                return null;
            }

            String cacheRetrieveMode = getLevel2CacheRetrieveMode();
            if ("bypass".equalsIgnoreCase(cacheRetrieveMode))
            {
                // Cache retrieval currently turned off
                return null;
            }

            Level2Cache l2Cache = nucCtx.getLevel2Cache();
            CachedPC cachedPC = l2Cache.get(id);

            if (cachedPC != null)
            {
                // Create active version of cached object with ObjectProvider connected and same id
                ObjectProvider op = nucCtx.getObjectProviderFactory().newForCachedPC(this, id, cachedPC);
                pc = op.getObject(); // Object in P_CLEAN state
                if (NucleusLogger.CACHE.isDebugEnabled())
                {
                    NucleusLogger.CACHE.debug(Localiser.msg("004006", IdentityUtils.getPersistableIdentityForId(id),
                        StringUtils.intArrayToString(cachedPC.getLoadedFieldNumbers()), cachedPC.getVersion(), StringUtils.toJVMIDString(pc)));
                }

                if (tx.isActive() && tx.getOptimistic())
                {
                    // Optimistic txns, so return as P_NONTRANS (as per JDO spec)
                    op.makeNontransactional();
                }
                else if (!tx.isActive() && getApiAdapter().isTransactional(pc))
                {
                    // Non-tx context, so return as P_NONTRANS (as per JDO spec)
                    op.makeNontransactional();
                }

                return pc;
            }

            if (NucleusLogger.CACHE.isDebugEnabled())
            {
                NucleusLogger.CACHE.debug(Localiser.msg("004005", IdentityUtils.getPersistableIdentityForId(id)));
            }
        }

        return null;
    }

    /**
     * Convenience method to access the identity that corresponds to a unique key, in the Level 2 cache.
     * @param uniKey The CacheUniqueKey to use in lookups
     * @return Identity of the associated object
     */
    protected Object getObjectFromLevel2CacheForUnique(CacheUniqueKey uniKey)
    {
        Object pc = null;

        if (l2CacheEnabled)
        {
            String cacheRetrieveMode = getLevel2CacheRetrieveMode();
            if ("bypass".equalsIgnoreCase(cacheRetrieveMode))
            {
                // Cache retrieval currently turned off
                return null;
            }

            Level2Cache l2Cache = nucCtx.getLevel2Cache();
            CachedPC cachedPC = l2Cache.getUnique(uniKey);

            if (cachedPC != null)
            {
                Object id = cachedPC.getId();

                // Create active version of cached object with ObjectProvider connected and same id
                ObjectProvider op = nucCtx.getObjectProviderFactory().newForCachedPC(this, id, cachedPC);
                pc = op.getObject(); // Object in P_CLEAN state
                if (NucleusLogger.CACHE.isDebugEnabled())
                {
                    NucleusLogger.CACHE.debug(Localiser.msg("004006", IdentityUtils.getPersistableIdentityForId(id),
                        StringUtils.intArrayToString(cachedPC.getLoadedFieldNumbers()), cachedPC.getVersion(), StringUtils.toJVMIDString(pc)));
                }

                if (tx.isActive() && tx.getOptimistic())
                {
                    // Optimistic txns, so return as P_NONTRANS (as per JDO spec)
                    op.makeNontransactional();
                }
                else if (!tx.isActive() && getApiAdapter().isTransactional(pc))
                {
                    // Non-tx context, so return as P_NONTRANS (as per JDO spec)
                    op.makeNontransactional();
                }

                return pc;
            }

            if (NucleusLogger.CACHE.isDebugEnabled())
            {
                NucleusLogger.CACHE.debug(Localiser.msg("004005", uniKey));
            }
        }

        return null;
    }

    /**
     * Convenience method to access a collection of objects from the Level 2 cache.
     * @param ids Collection of ids to retrieve
     * @return Map of persistable objects (with connected ObjectProvider) keyed by their id if found in the L2 cache
     */
    protected Map getObjectsFromLevel2Cache(Collection ids)
    {
        if (l2CacheEnabled)
        {
            // TODO Restrict to only those ids that are cacheable
            Level2Cache l2Cache = nucCtx.getLevel2Cache();
            Map<Object, CachedPC> cachedPCs = l2Cache.getAll(ids);
            Map pcsById = new HashMap(cachedPCs.size());

            for (Map.Entry<Object, CachedPC> entry : cachedPCs.entrySet())
            {
                Object id = entry.getKey();
                CachedPC cachedPC = entry.getValue();
                if (cachedPC != null)
                {
                    // Create active version of cached object with ObjectProvider connected and same id
                    ObjectProvider op = nucCtx.getObjectProviderFactory().newForCachedPC(this, id, cachedPC);
                    Object pc = op.getObject(); // Object in P_CLEAN state
                    if (NucleusLogger.CACHE.isDebugEnabled())
                    {
                        NucleusLogger.CACHE.debug(Localiser.msg("004006", IdentityUtils.getPersistableIdentityForId(id),
                            StringUtils.intArrayToString(cachedPC.getLoadedFieldNumbers()), cachedPC.getVersion(), StringUtils.toJVMIDString(pc)));
                    }

                    if (tx.isActive() && tx.getOptimistic())
                    {
                        // Optimistic txns, so return as P_NONTRANS (as per JDO spec)
                        op.makeNontransactional();
                    }
                    else if (!tx.isActive() && getApiAdapter().isTransactional(pc))
                    {
                        // Non-tx context, so return as P_NONTRANS (as per JDO spec)
                        op.makeNontransactional();
                    }

                    pcsById.put(id, pc);
                }
                else
                {
                    if (NucleusLogger.CACHE.isDebugEnabled())
                    {
                        NucleusLogger.CACHE.debug(Localiser.msg("004005",
                            IdentityUtils.getPersistableIdentityForId(id)));
                    }
                }
            }
            return pcsById;
        }

        return null;
    }

    /**
     * Replace the previous object id for a persistable object with a new one.
     * This is used where we have already added the object to the cache(s) and/or enlisted it in the txn before its real identity was fixed (attributed in the datastore).
     * @param pc The Persistable object
     * @param oldID the old id it was known by
     * @param newID the new id
     */
    public void replaceObjectId(Object pc, Object oldID, Object newID)
    {
        if (pc == null || newID == null)
        {
            NucleusLogger.CACHE.warn(Localiser.msg("003006"));
            return;
        }

        ObjectProvider op = findObjectProvider(pc);

        // Update L1 cache
        if (cache != null)
        {
            if (oldID != null)
            {
                Object o = cache.get(oldID); //use get() because a cache.remove operation returns a weakReference instance
                if (o != null)
                {
                    // Remove the old variant
                    if (NucleusLogger.CACHE.isDebugEnabled())
                    {
                        NucleusLogger.CACHE.debug(Localiser.msg("003012", StringUtils.toJVMIDString(pc), 
                            IdentityUtils.getPersistableIdentityForId(oldID), IdentityUtils.getPersistableIdentityForId(newID)));
                    }
                    cache.remove(oldID);
                }
            }
            if (op != null)
            {
                putObjectIntoLevel1Cache(op);
            }
        }

        if (oldID != null && enlistedOPCache.get(oldID) != null)
        {
            // Swap the enlisted object identity
            if (op != null)
            {
                enlistedOPCache.remove(oldID);
                enlistedOPCache.put(newID, op);
                if (NucleusLogger.TRANSACTION.isDebugEnabled())
                {
                    NucleusLogger.TRANSACTION.debug(Localiser.msg("015018", StringUtils.toJVMIDString(pc),
                        IdentityUtils.getPersistableIdentityForId(oldID), IdentityUtils.getPersistableIdentityForId(newID)));
                }
            }
        }

        if (oldID != null && l2CacheTxIds != null)
        {
            if (l2CacheTxIds.contains(oldID))
            {
                l2CacheTxIds.remove(oldID);
                l2CacheTxIds.add(newID);
            }
        }

        if (oldID != null && pbrAtCommitHandler != null && tx.isActive())
        {
            pbrAtCommitHandler.swapObjectId(oldID, newID);
        }
    }

    /**
     * Convenience method to return the setting for serialize read for the current transaction for
     * the specified class name. Returns the setting for the transaction (if set), otherwise falls back to
     * the setting for the class, otherwise returns false.
     * @param className Name of the class
     * @return Setting for serialize read
     */
    public boolean getSerializeReadForClass(String className)
    {
        if (tx.isActive() && tx.getSerializeRead() != null)
        {
            // Within a transaction, and serializeRead set for txn
            return tx.getSerializeRead();
        }
        else if (getProperty(PropertyNames.PROPERTY_SERIALIZE_READ) != null)
        {
            // Set for the context as a property
            return properties.getBooleanProperty(PropertyNames.PROPERTY_SERIALIZE_READ);
        }
        else if (className != null)
        {
            // Set for the class
            AbstractClassMetaData cmd = getMetaDataManager().getMetaDataForClass(className, clr);
            if (cmd != null)
            {
                return cmd.isSerializeRead();
            }
        }
        return false;
    }

    // ------------------------------------- Queries/Extents --------------------------------------

    /**
     * Extents are collections of datastore objects managed by the datastore,
     * not by explicit user operations on collections. Extent capability is a
     * boolean property of classes that are persistence capable. If an instance
     * of a class that has a managed extent is made persistent via reachability,
     * the instance is put into the extent implicitly.
     * @param pcClass The class to query
     * @param subclasses Whether to include subclasses in the query.
     * @return returns an Extent that contains all of the instances in the
     * parameter class, and if the subclasses flag is true, all of the instances
     * of the parameter class and its subclasses.
     */
    public <T> Extent<T> getExtent(Class<T> pcClass, boolean subclasses)
    {
        try
        {
            clr.setPrimary(pcClass.getClassLoader());
            assertClassPersistable(pcClass);

            return getStoreManager().getExtent(this, pcClass, subclasses);
        }
        finally
        {
            clr.unsetPrimary();
        }
    }

    // ------------------------------------- Callback Listeners --------------------------------------

    /**
     * Retrieve the callback handler for this context.
     * If the callback handler hasn't yet been created, this will create it.
     * @return the callback handler
     */
    public CallbackHandler getCallbackHandler()
    {
        if (callbackHandler != null)
        {
            return callbackHandler;
        }

        if (getNucleusContext().getConfiguration().getBooleanProperty(PropertyNames.PROPERTY_ALLOW_CALLBACKS))
        {
            String callbackHandlerClassName = getNucleusContext().getPluginManager().getAttributeValueForExtension(
                "org.datanucleus.callbackhandler", "name", getNucleusContext().getApiName(), "class-name");
            if (callbackHandlerClassName != null)
            {
                try
                {
                    callbackHandler = (CallbackHandler) getNucleusContext().getPluginManager().createExecutableExtension(
                        "org.datanucleus.callbackhandler", "name", getNucleusContext().getApiName(), "class-name",
                        new Class[] {ClassConstants.PERSISTENCE_NUCLEUS_CONTEXT}, new Object[] {getNucleusContext()});
                }
                catch (Exception e)
                {
                    NucleusLogger.PERSISTENCE.error(Localiser.msg("025000", callbackHandlerClassName, e));
                }
            }
        }
        else
        {
            callbackHandler = new NullCallbackHandler();
        }
        return callbackHandler;
    }

    /**
     * Close the callback handler and disconnect any registered listeners.
     */
    public void closeCallbackHandler()
    {
        if (callbackHandler != null)
        {
            // Clear out lifecycle listeners that were registered
            callbackHandler.close();
        }
    }

    // ------------------------------- Assert Utilities ---------------------------------

    /**
     * Method to assert if this context is open. 
     * Throws a NucleusUserException if the context is closed.
     */
    protected void assertIsOpen()
    {
        if (isClosed())
        {
            throw new NucleusUserException(Localiser.msg("010002")).setFatal();
        }
    }

    /**
     * Method to assert if the specified class is Persistence Capable.
     * @param cls The class to check
     * @throws ClassNotPersistableException if class is not persistable
     * @throws NoPersistenceInformationException if no metadata/annotations are found for class
     */
    public void assertClassPersistable(Class cls)
    {
        if (cls == null)
        {
            return;
        }
        if (!getNucleusContext().getApiAdapter().isPersistable(cls) && !cls.isInterface())
        {
            throw new ClassNotPersistableException(cls.getName());
        }
        if (!hasPersistenceInformationForClass(cls))
        {
            throw new NoPersistenceInformationException(cls.getName());
        }
    }

    /**
     * Method to assert if the specified object is Detachable. 
     * Throws a ClassNotDetachableException if not capable
     * @param object The object to check
     */
    protected void assertDetachable(Object object)
    {
        if (object != null && !getApiAdapter().isDetachable(object))
        {
            throw new ClassNotDetachableException(object.getClass().getName());
        }
    }

    /**
     * Method to assert if the specified object is detached.
     * Throws a ObjectDetachedException if it is detached.
     * @param object The object to check
     */
    protected void assertNotDetached(Object object)
    {
        if (object != null && getApiAdapter().isDetached(object))
        {
            throw new ObjectDetachedException(object.getClass().getName());
        }
    }

    /**
     * Method to assert if the current transaction is active. Throws a
     * TransactionNotActiveException if not active
     */
    protected void assertActiveTransaction()
    {
        if (!tx.isActive())
        {
            throw new TransactionNotActiveException();
        }
    }

    /**
     * Utility method to check if the specified class has reachable metadata or annotations.
     * @param cls The class to check
     * @return Whether the class has reachable metadata or annotations
     */
    public boolean hasPersistenceInformationForClass(Class cls)
    {
        if (cls == null)
        {
            return false;
        }
        
        if (getMetaDataManager().getMetaDataForClass(cls, clr) != null)
        {
            return true;
        }

        if (cls.isInterface())
        {
            // JDO "persistent-interface"
            // Try to create an implementation of the interface at runtime. 
            // It will register the MetaData and make an implementation available
            try
            {
                newInstance(cls);
            }
            catch (RuntimeException ex)
            {
                NucleusLogger.PERSISTENCE.warn(ex);
            }
            return getMetaDataManager().getMetaDataForClass(cls, clr) != null;
        }
        return false;
    }

    // --------------------------- Fetch Groups ---------------------------------

    /** 
     * Convenience accessor for the FetchGroupManager.
     * Creates it if not yet existing.
     * @return The FetchGroupManager
     */
    protected FetchGroupManager getFetchGroupManager()
    {
        if (fetchGrpMgr == null)
        {
            fetchGrpMgr = new FetchGroupManager(getNucleusContext());
        }
        return fetchGrpMgr;
    }

    /**
     * Method to add a dynamic FetchGroup.
     * @param grp The group
     */
    public void addInternalFetchGroup(FetchGroup grp)
    {
        getFetchGroupManager().addFetchGroup(grp);
    }

    /**
     * Method to remove a dynamic FetchGroup.
     * @param grp The group
     */
    protected void removeInternalFetchGroup(FetchGroup grp)
    {
        if (fetchGrpMgr == null)
        {
            return;
        }
        getFetchGroupManager().removeFetchGroup(grp);
    }

    /**
     * Accessor for an internal fetch group for the specified class.
     * @param cls The class
     * @param name Name of the group
     * @return The FetchGroup
     * @throws NucleusUserException if the class is not persistable
     */
    public FetchGroup getInternalFetchGroup(Class cls, String name)
    {
        if (!cls.isInterface() && !getNucleusContext().getApiAdapter().isPersistable(cls))
        {
            // Class but not persistable!
            throw new NucleusUserException("Cannot create FetchGroup for " + cls + " since it is not persistable");
        }
        else if (cls.isInterface() && !getNucleusContext().getMetaDataManager().isPersistentInterface(cls.getName()))
        {
            // Interface but not persistent
            throw new NucleusUserException("Cannot create FetchGroup for " + cls + " since it is not persistable");
        }

        if (fetchGrpMgr == null)
        {
            return null;
        }
        return getFetchGroupManager().getFetchGroup(cls, name, true);
    }

    /**
     * Accessor for the fetch groups for the specified name.
     * @param name Name of the group
     * @return The FetchGroup
     */
    public Set<FetchGroup> getFetchGroupsWithName(String name)
    {
        if (fetchGrpMgr == null)
        {
            return null;
        }
        return getFetchGroupManager().getFetchGroupsWithName(name);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#registerEmbeddedRelation(org.datanucleus.state.ObjectProvider, int, org.datanucleus.state.ObjectProvider)
     */
    public EmbeddedOwnerRelation registerEmbeddedRelation(ObjectProvider ownerOP, int ownerFieldNum, ObjectProvider embOP)
    {
        EmbeddedOwnerRelation relation = new EmbeddedOwnerRelation(ownerOP, ownerFieldNum, embOP);

        if (opEmbeddedInfoByEmbedded == null)
        {
            opEmbeddedInfoByEmbedded = new HashMap<ObjectProvider, List<EmbeddedOwnerRelation>>();
        }
        List<EmbeddedOwnerRelation> relations = opEmbeddedInfoByEmbedded.get(embOP);
        if (relations == null)
        {
            relations = new ArrayList<>();
        }
        relations.add(relation);
        opEmbeddedInfoByEmbedded.put(embOP, relations);

        if (opEmbeddedInfoByOwner == null)
        {
            opEmbeddedInfoByOwner = new HashMap<ObjectProvider, List<EmbeddedOwnerRelation>>();
        }
        relations = opEmbeddedInfoByOwner.get(ownerOP);
        if (relations == null)
        {
            relations = new ArrayList<>();
        }
        relations.add(relation);
        opEmbeddedInfoByOwner.put(ownerOP, relations);

        return relation;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#deregisterEmbeddedRelation(org.datanucleus.ExecutionContext.EmbeddedOwnerRelation)
     */
    public void deregisterEmbeddedRelation(EmbeddedOwnerRelation rel)
    {
        if (opEmbeddedInfoByEmbedded != null)
        {
            List<EmbeddedOwnerRelation> ownerRels = opEmbeddedInfoByEmbedded.get(rel.getEmbeddedOP());
            ownerRels.remove(rel);
            if (ownerRels.isEmpty())
            {
                opEmbeddedInfoByEmbedded.remove(rel.getEmbeddedOP());
                if (opEmbeddedInfoByEmbedded.isEmpty())
                {
                    opEmbeddedInfoByEmbedded = null;
                }
            }
        }
        if (opEmbeddedInfoByOwner != null)
        {
            List<EmbeddedOwnerRelation> embRels = opEmbeddedInfoByOwner.get(rel.getOwnerOP());
            embRels.remove(rel);
            if (embRels.isEmpty())
            {
                opEmbeddedInfoByOwner.remove(rel.getOwnerOP());
                if (opEmbeddedInfoByOwner.isEmpty())
                {
                    opEmbeddedInfoByOwner = null;
                }
            }
        }
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#removeEmbeddedOwnerRelation(org.datanucleus.state.ObjectProvider, int, org.datanucleus.state.ObjectProvider)
     */
    public void removeEmbeddedOwnerRelation(ObjectProvider ownerOP, int ownerFieldNum, ObjectProvider embOP)
    {
        if (opEmbeddedInfoByOwner != null)
        {
            List<EmbeddedOwnerRelation> ownerRels = opEmbeddedInfoByOwner.get(ownerOP);
            EmbeddedOwnerRelation rel = null;
            for (EmbeddedOwnerRelation ownerRel : ownerRels)
            {
                if (ownerRel.getEmbeddedOP() == embOP && ownerRel.getOwnerFieldNum() == ownerFieldNum)
                {
                    rel = ownerRel;
                    break;
                }
            }
            if (rel != null)
            {
                deregisterEmbeddedRelation(rel);
            }
        }
    }

    /**
     * Accessor for the owning ObjectProviders for the managed object when stored embedded.
     * Should really only have a single owner but users could, in principle, assign it to multiple.
     * @param embOP The ObjectProvider that is embedded that we are looking for the owners for
     * @return ObjectProviders owning this embedded object.
     */
    public ObjectProvider[] getOwnersForEmbeddedObjectProvider(ObjectProvider embOP)
    {
        if (opEmbeddedInfoByEmbedded == null || !opEmbeddedInfoByEmbedded.containsKey(embOP))
        {
            return null;
        }
        List<EmbeddedOwnerRelation> ownerRels = opEmbeddedInfoByEmbedded.get(embOP);
        ObjectProvider[] owners = new ObjectProvider[ownerRels.size()];
        int i = 0;
        for (EmbeddedOwnerRelation rel : ownerRels)
        {
            owners[i++] = rel.getOwnerOP();
        }
        return owners;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#getOwnerInformationForEmbedded(org.datanucleus.state.ObjectProvider)
     */
    public List<EmbeddedOwnerRelation> getOwnerInformationForEmbedded(ObjectProvider embOP)
    {
        // TODO Drop this method
        if (opEmbeddedInfoByEmbedded == null)
        {
            return null;
        }
        return opEmbeddedInfoByEmbedded.get(embOP);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#getEmbeddedInformationForOwner(org.datanucleus.state.ObjectProvider)
     */
    public List<EmbeddedOwnerRelation> getEmbeddedInformationForOwner(ObjectProvider ownerOP)
    {
        if (opEmbeddedInfoByOwner == null)
        {
            return null;
        }
        return opEmbeddedInfoByOwner.get(ownerOP);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#setObjectProviderAssociatedValue(org.datanucleus.state.ObjectProvider, java.lang.Object, java.lang.Object)
     */
    public void setObjectProviderAssociatedValue(ObjectProvider op, Object key, Object value)
    {
        Map opMap = null;
        if (opAssociatedValuesMapByOP == null)
        {
            opAssociatedValuesMapByOP = new HashMap<ObjectProvider, Map<?,?>>();
            opMap = new HashMap();
            opAssociatedValuesMapByOP.put(op, opMap);
        }
        else
        {
            opMap = opAssociatedValuesMapByOP.get(op);
            if (opMap == null)
            {
                opMap = new HashMap();
                opAssociatedValuesMapByOP.put(op, opMap);
            }
        }
        opMap.put(key, value);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#getObjectProviderAssociatedValue(org.datanucleus.state.ObjectProvider, java.lang.Object)
     */
    public Object getObjectProviderAssociatedValue(ObjectProvider op, Object key)
    {
        if (opAssociatedValuesMapByOP == null)
        {
            return null;
        }
        Map opMap = opAssociatedValuesMapByOP.get(op);
        return opMap == null ? null : opMap.get(key);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#removeObjectProviderAssociatedValue(org.datanucleus.state.ObjectProvider, java.lang.Object)
     */
    public void removeObjectProviderAssociatedValue(ObjectProvider op, Object key)
    {
        if (opAssociatedValuesMapByOP != null)
        {
            Map opMap = opAssociatedValuesMapByOP.get(op);
            if (opMap != null)
            {
                opMap.remove(key);
            }
        }
    }

    /* (non-Javadoc)
     * @see org.datanucleus.ExecutionContext#containsObjectProviderAssociatedValue(org.datanucleus.state.ObjectProvider, java.lang.Object)
     */
    public boolean containsObjectProviderAssociatedValue(ObjectProvider op, Object key)
    {
        if (opAssociatedValuesMapByOP != null && opAssociatedValuesMapByOP.containsKey(op))
        {
            return opAssociatedValuesMapByOP.get(op).containsKey(key);
        }
        return false;
    }
}
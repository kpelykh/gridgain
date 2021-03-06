/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.affinity.*;
import org.gridgain.grid.cache.affinity.consistenthash.*;
import org.gridgain.grid.cache.eviction.*;
import org.gridgain.grid.cache.eviction.ggfs.*;
import org.gridgain.grid.cache.store.*;
import org.gridgain.grid.dr.cache.receiver.*;
import org.gridgain.grid.dr.cache.sender.*;
import org.gridgain.grid.dr.hub.sender.*;
import org.gridgain.grid.ggfs.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.processors.*;
import org.gridgain.grid.kernal.processors.cache.datastructures.*;
import org.gridgain.grid.kernal.processors.cache.distributed.dht.*;
import org.gridgain.grid.kernal.processors.cache.distributed.dht.atomic.*;
import org.gridgain.grid.kernal.processors.cache.distributed.dht.colocated.*;
import org.gridgain.grid.kernal.processors.cache.distributed.near.*;
import org.gridgain.grid.kernal.processors.cache.local.*;
import org.gridgain.grid.kernal.processors.cache.query.*;
import org.gridgain.grid.kernal.processors.cache.query.continuous.*;
import org.gridgain.grid.kernal.processors.cache.dr.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.future.*;
import org.jetbrains.annotations.*;

import javax.management.*;
import java.lang.reflect.*;
import java.util.*;

import static org.gridgain.grid.GridDeploymentMode.*;
import static org.gridgain.grid.cache.GridCacheAtomicityMode.*;
import static org.gridgain.grid.cache.GridCacheConfiguration.*;
import static org.gridgain.grid.cache.GridCacheMode.*;
import static org.gridgain.grid.cache.GridCacheDistributionMode.*;
import static org.gridgain.grid.cache.GridCachePreloadMode.*;
import static org.gridgain.grid.cache.GridCacheTxIsolation.*;
import static org.gridgain.grid.cache.GridCacheWriteSynchronizationMode.*;
import static org.gridgain.grid.dr.cache.receiver.GridDrReceiverCacheConflictResolverMode.*;
import static org.gridgain.grid.kernal.GridNodeAttributes.*;
import static org.gridgain.grid.kernal.processors.cache.GridCacheUtils.*;

/**
 * Cache processor.
 */
public class GridCacheProcessor extends GridProcessorAdapter {
    /** */
    private final Map<String, GridCacheAdapter<?, ?>> caches;

    /** Map of proxies. */
    private final Map<String, GridCache<?, ?>> proxies;

    /** Map of public proxies, i.e. proxies which could be returned to the user. */
    private final Map<String, GridCache<?, ?>> publicProxies;

    /** Map of preload finish futures grouped by preload order. */
    private final NavigableMap<Integer, GridFuture<?>> preloadFuts;

    /** Maximum detected preload order. */
    private int maxPreloadOrder;

    /** System cache names. */
    private final Set<String> sysCaches;

    /** Caches stop sequence. */
    private final Deque<GridCacheAdapter<?, ?>> stopSeq;

    /** MBean server. */
    private final MBeanServer mBeanSrv;

    /** Cache MBeans. */
    private final Collection<ObjectName> cacheMBeans = new LinkedList<>();

    /**
     * @param ctx Kernal context.
     */
    public GridCacheProcessor(GridKernalContext ctx) {
        super(ctx);

        caches = new LinkedHashMap<>();
        proxies = new HashMap<>();
        publicProxies = new HashMap<>();
        preloadFuts = new TreeMap<>();

        sysCaches = new HashSet<>();
        stopSeq = new LinkedList<>();

        mBeanSrv = ctx.config().getMBeanServer();
    }

    /**
     * @param cfg Initializes cache configuration with proper defaults.
     */
    @SuppressWarnings("unchecked")
    private void initialize(GridCacheConfiguration cfg) {
        if (cfg.getCacheMode() == null)
            cfg.setCacheMode(DFLT_CACHE_MODE);

        if (cfg.getDefaultTxConcurrency() == null)
            cfg.setDefaultTxConcurrency(DFLT_TX_CONCURRENCY);

        if (cfg.getDefaultTxIsolation() == null)
            cfg.setDefaultTxIsolation(DFLT_TX_ISOLATION);

        if (cfg.getMemoryMode() == null)
            cfg.setMemoryMode(DFLT_MEMORY_MODE);

        if (cfg.getAffinity() == null) {
            if (cfg.getCacheMode() == PARTITIONED) {
                GridCacheConsistentHashAffinityFunction aff = new GridCacheConsistentHashAffinityFunction();

                aff.setHashIdResolver(new GridCacheAffinityNodeAddressHashResolver());

                cfg.setAffinity(aff);
            }
            else if (cfg.getCacheMode() == REPLICATED) {
                GridCacheConsistentHashAffinityFunction aff = new GridCacheConsistentHashAffinityFunction(false, 512);

                aff.setHashIdResolver(new GridCacheAffinityNodeAddressHashResolver());

                cfg.setAffinity(aff);

                cfg.setBackups(Integer.MAX_VALUE);
            }
            else
                cfg.setAffinity(new LocalAffinityFunction());
        }
        else {
            if (cfg.getCacheMode() == PARTITIONED) {
                if (cfg.getAffinity() instanceof GridCacheConsistentHashAffinityFunction) {
                    GridCacheConsistentHashAffinityFunction aff = (GridCacheConsistentHashAffinityFunction)cfg.getAffinity();

                    if (aff.getHashIdResolver() == null)
                        aff.setHashIdResolver(new GridCacheAffinityNodeAddressHashResolver());
                }
            }
        }

        if (cfg.getAffinityMapper() == null)
            cfg.setAffinityMapper(new GridCacheDefaultAffinityKeyMapper());

        GridCacheEvictionPolicy evictPlc = cfg.getEvictionPolicy();

        if (evictPlc instanceof GridCacheGgfsPerBlockLruEvictionPolicy && cfg.getEvictionFilter() == null)
            cfg.setEvictionFilter(new GridCacheGgfsEvictionFilter());

        if (cfg.getPreloadMode() == null)
            cfg.setPreloadMode(ASYNC);

        if (cfg.getCacheMode() == PARTITIONED || cfg.getCacheMode() == REPLICATED) {
            if (cfg.getAtomicityMode() == null)
                cfg.setAtomicityMode(ATOMIC);

            if (cfg.getDistributionMode() == null)
                cfg.setDistributionMode(PARTITIONED_ONLY);

            if (cfg.getDistributionMode() == PARTITIONED_ONLY ||
                cfg.getDistributionMode() == CLIENT_ONLY) {
                if (cfg.getNearEvictionPolicy() != null)
                    U.quietAndWarn(log, "Ignoring near eviction policy since near cache is disabled.");
            }

            assert cfg.getDistributionMode() != null;
        }
        else {
            assert cfg.getCacheMode() == LOCAL;

            if (cfg.getAtomicityMode() == null)
                cfg.setAtomicityMode(TRANSACTIONAL);

            if (cfg.getAtomicityMode() == ATOMIC) {
                U.quietAndWarn(log, "Cache atomicity mode ATOMIC is not supported for LOCAL cache (ignoring) " +
                    "[cacheName=" + cfg.getName() + ", cacheMode=" + cfg.getCacheMode() + ']');

                cfg.setAtomicityMode(TRANSACTIONAL);
            }
        }

        if (cfg.getWriteSynchronizationMode() == null)
            cfg.setWriteSynchronizationMode(cfg.getCacheMode() == PARTITIONED ? PRIMARY_SYNC : FULL_ASYNC);

        assert cfg.getWriteSynchronizationMode() != null;
    }

    /**
     * @param gridCfg Grid configuration.
     * @param cfg Configuration to check for possible performance issues.
     */
    private void suggestOptimizations(GridConfiguration gridCfg, GridCacheConfiguration cfg) {
        // Skip over GGFS caches.
        if (gridCfg.getGgfsConfiguration() != null) {
            for (GridGgfsConfiguration c : gridCfg.getGgfsConfiguration()) {
                if (F.eq(c.getMetaCacheName(), cfg.getName()) || F.eq(c.getDataCacheName(), cfg.getName()))
                    return;
            }
        }

        GridPerformanceSuggestions perf = ctx.performance();

        String msg = "Disable eviction policy (remove from configuration)";

        if (cfg.getEvictionPolicy() != null) {
            perf.add(msg, false);

            perf.add("Disable synchronized evictions (set 'evictSynchronized' to false)", !cfg.isEvictSynchronized());
        }
        else
            perf.add(msg, true);

        if (cfg.getCacheMode() == PARTITIONED) {
            perf.add("Disable near cache (set 'partitionDistributionMode' to PARTITIONED_ONLY or CLIENT_ONLY)",
                cfg.getDistributionMode() != NEAR_PARTITIONED &&
                    cfg.getDistributionMode() != NEAR_ONLY);

            if (cfg.getAffinity() != null)
                perf.add("Decrease number of backups (set 'keyBackups' to 0)", cfg.getBackups() == 0);
        }

        // Suppress warning if at least one ATOMIC cache found.
        perf.add("Enable ATOMIC mode if not using transactions (set 'atomicityMode' to ATOMIC)",
            cfg.getAtomicityMode() == ATOMIC);

        // Suppress warning if at least one non-FULL_SYNC mode found.
        perf.add("Disable fully synchronous writes (set 'writeSynchronizationMode' to PRIMARY_SYNC or FULL_ASYNC)",
            cfg.getWriteSynchronizationMode() != FULL_SYNC);

        // Suppress warning if at least one swap is disabled.
        perf.add("Disable swap store (set 'swapEnabled' to false)", !cfg.isSwapEnabled());

        if (cfg.getStore() != null)
            perf.add("Enable write-behind to persistent store (set 'writeBehindEnabled' to true)",
                cfg.isWriteBehindEnabled());

        perf.add("Disable query index (set 'queryIndexEnabled' to false)", !cfg.isQueryIndexEnabled());

        perf.add("Disable serializable transactions (set 'txSerializableEnabled' to false)",
            !cfg.isTxSerializableEnabled());
    }

    /**
     * @param c Grid configuration.
     * @param cc Configuration to validate.
     * @throws GridException If failed.
     */
    private void validate(GridConfiguration c, GridCacheConfiguration cc) throws GridException {
        if (cc.getCacheMode() == REPLICATED) {
            if (!(cc.getAffinity() instanceof GridCacheConsistentHashAffinityFunction))
                throw new GridException("REPLICATED cache can be started only with GridCacheConsistentHashAffinityFunction" +
                    " [cacheName=" + cc.getName() + ", affinity=" + cc.getAffinity().getClass().getName() + ']');

            GridCacheConsistentHashAffinityFunction aff = (GridCacheConsistentHashAffinityFunction)cc.getAffinity();

            if (cc.getBackups() != Integer.MAX_VALUE)
                throw new GridException("For REPLICATED cache number of backups in GridCacheCconfiguration must " +
                    "be set to Integer.MAX_VALUE [cacheName=" + cc.getName() + ", backups=" + cc.getBackups() + ']');

            if (aff.isExcludeNeighbors())
                throw new GridException("For REPLICATED cache flag 'excludeNeighbors' in GridCacheConsistentHashAffinityFunction " +
                    "cannot be set [cacheName=" + cc.getName() + ']');

            if (cc.getWriteSynchronizationMode() == PRIMARY_SYNC)
                throw new GridException("Cannot start REPLICATED cache with PRIMARY_SYNC write synchronization mode " +
                    "(switch to FULL_SYNC or FULL_ASYNC mode instead) [cacheName=" + cc.getName() + ']');

            if (cc.getDistributionMode() == NEAR_PARTITIONED) {
                U.warn(log, "NEAR_PARTITIONED distribution mode cannot be used with REPLICATED cache, " +
                    "will be changed to PARTITIONED_ONLY [cacheName=" + cc.getName() + ']');

                cc.setDistributionMode(PARTITIONED_ONLY);
            }
        }

        if (cc.getCacheMode() == LOCAL && !cc.getAffinity().getClass().equals(LocalAffinityFunction.class))
            U.warn(log, "GridCacheAffinityFunction configuration parameter will be ignored for local cache [cacheName=" +
                cc.getName() + ']');

        if (cc.getPreloadMode() != GridCachePreloadMode.NONE) {
            assertParameter(cc.getPreloadThreadPoolSize() > 0, "preloadThreadPoolSize > 0");
            assertParameter(cc.getPreloadBatchSize() > 0, "preloadBatchSize > 0");
        }

        if (cc.getCacheMode() == PARTITIONED || cc.getCacheMode() == REPLICATED) {
            if (isNearEnabled(cc) && cc.getAtomicityMode() == ATOMIC)
                throw new GridException("Cannot start cache with ATOMIC atomicity mode and near-enabled" +
                    " partition distribution mode [cacheName=" + cc.getName() + ']');

            if (cc.getAtomicityMode() == ATOMIC && cc.getWriteSynchronizationMode() == FULL_ASYNC)
                U.warn(log, "Cache write synchronization mode is set to FULL_ASYNC. All single-key 'put' and " +
                    "'remove' operations will return 'null', all 'putx' and 'removex' operations will return" +
                    " 'true'.");

            if (cc.getAtomicityMode() == ATOMIC && cc.getWriteSynchronizationMode() != FULL_SYNC &&
                cc.getAtomicWriteOrderMode() == GridCacheAtomicWriteOrderMode.CLOCK) {
                cc.setAtomicWriteOrderMode(GridCacheAtomicWriteOrderMode.PRIMARY);

                U.warn(log, "Automatically set write order mode to PRIMARY for write synchronization mode " +
                    "[writeSynchronizationMode=" + cc.getWriteSynchronizationMode() + ", " +
                    "cacheName=" + cc.getName() + ']');
            }
        }
        else if (cc.getCacheMode() == LOCAL)
            assert cc.getAtomicityMode() != ATOMIC : "Invalid configuration: " + cc;

        if (ctx.config().getDeploymentMode() == PRIVATE || ctx.config().getDeploymentMode() == ISOLATED)
            throw new GridException("Cannot start cache in PRIVATE or ISOLATED deployment mode: " +
                ctx.config().getDeploymentMode());

        if (!cc.isTxSerializableEnabled() && cc.getDefaultTxIsolation() == SERIALIZABLE)
            U.warn(log,
                "Serializable transactions are disabled while default transaction isolation is SERIALIZABLE " +
                    "(most likely misconfiguration - either update 'isTxSerializableEnabled' or " +
                    "'defaultTxIsolationLevel' properties) for cache: " + cc.getName(),
                "Serializable transactions are disabled while default transaction isolation is SERIALIZABLE " +
                    "for cache: " + cc.getName());

        if (cc.isWriteBehindEnabled()) {
            if (cc.getStore() == null)
                throw new GridException("Cannot enable write-behind cache (cache store is not provided) for cache: " +
                    cc.getName());

            assertParameter(cc.getWriteBehindBatchSize() > 0, "writeBehindBatchSize > 0");
            assertParameter(cc.getWriteBehindFlushSize() >= 0, "writeBehindFlushSize >= 0");
            assertParameter(cc.getWriteBehindFlushFrequency() >= 0, "writeBehindFlushFrequency >= 0");
            assertParameter(cc.getWriteBehindFlushThreadCount() > 0, "writeBehindFlushThreadCount > 0");

            if (cc.getWriteBehindFlushSize() == 0 && cc.getWriteBehindFlushFrequency() == 0)
                throw new GridException("Cannot set both 'writeBehindFlushFrequency' and " +
                    "'writeBehindFlushSize' parameters to 0 for cache: " + cc.getName());
        }

        long delay = cc.getPreloadPartitionedDelay();

        if (delay != 0) {
            if (cc.getCacheMode() != PARTITIONED)
                U.warn(log, "Preload delay is supported only for partitioned caches (will ignore): " + cc.getName(),
                    "Will ignore preload delay for cache: " + cc.getName());
            else if (cc.getPreloadMode() == SYNC) {
                if (delay < 0) {
                    U.warn(log, "Ignoring SYNC preload mode with manual preload start (node will not wait for " +
                        "preloading to be finished): " + cc.getName(),
                        "Node will not wait for preload in SYNC mode: " + cc.getName());
                }
                else {
                    U.warn(log,
                        "Using SYNC preload mode with preload delay (node will wait until preloading is " +
                            "initiated for " + delay + "ms) for cache: " + cc.getName(),
                        "Node will wait until preloading is initiated for " + delay + "ms for cache: " + cc.getName());
                }
            }
        }

        if (!cc.isQueryIndexEnabled())
            U.warn(log, "Query indexing is disabled (queries will not work) for cache: '" + cc.getName() + "'. " +
                "To enable change GridCacheConfiguration.isQueryIndexEnabled() property.",
                "Query indexing is disabled (queries will not work) for cache: " + cc.getName());

        GridCacheEvictionPolicy evictPlc =  cc.getEvictionPolicy();

        if (evictPlc != null && evictPlc instanceof GridCacheGgfsPerBlockLruEvictionPolicy) {
            GridCacheEvictionFilter evictFilter = cc.getEvictionFilter();

            if (evictFilter != null && !(evictFilter instanceof GridCacheGgfsEvictionFilter))
                throw new GridException("Eviction filter cannot be set explicitly when using " +
                    "GridCacheGgfsPerBlockLruEvictionPolicy:" + cc.getName());
        }

        switch (cc.getMemoryMode()) {
            case OFFHEAP_VALUES: {
                if (cc.getOffHeapMaxMemory() < 0)
                    cc.setOffHeapMaxMemory(0); // Set to unlimited.

                break;
            }

            case OFFHEAP_TIERED: {
                if (cc.getOffHeapMaxMemory() < 0)
                    cc.setOffHeapMaxMemory(0); // Set to unlimited.

                break;
            }

            case ONHEAP_TIERED: break;

            default:
                throw new IllegalStateException("Unknown memory mode: " + cc.getMemoryMode());
        }

        if (cc.getMemoryMode() == GridCacheMemoryMode.OFFHEAP_VALUES) {
            if (cc.isQueryIndexEnabled())
                throw new GridException("Cannot have query indexing enabled while values are stored off-heap. " +
                    "You must either disable query indexing or disable off-heap values only flag for cache: " +
                    cc.getName());
        }

        assertParameter(cc.getContinuousQueryQueueSize() > 0, "cfg.getContinuousQueryQueueSize() > 0");

        // Validate DR send configuration.
        boolean ggfsCache = CU.isGgfsCache(c, cc.getName());
        boolean mongoCache = false; // CU.isMongoCache(c, cc.getName());

        GridDrSenderCacheConfiguration drSndCfg = cc.getDrSenderConfiguration();

        if (drSndCfg != null) {
            if (ggfsCache)
                throw new GridException("GGFS cache cannot be data center replication sender cache: " + cc.getName());

            if (mongoCache)
                throw new GridException("Mongo cache cannot be data center replication sender cache: " + cc.getName());

            assertParameter(drSndCfg.getMode() != null, "cfg.getDrSenderConfiguration().getMode() != null");

            if (cc.getCacheMode() == LOCAL)
                throw new GridException("Data center replication is not supported for LOCAL cache");

            assertParameter(drSndCfg.getBatchSendSize() > 0, "cfg.getDrSenderConfiguration().getBatchSendSize() > 0");

            if (drSndCfg.getBatchSendFrequency() < 0)
                drSndCfg.setBatchSendFrequency(0);

            assertParameter(drSndCfg.getMaxBatches() > 0, "cfg.getDrSenderConfiguration().getMaxBatches() > 0");

            assertParameter(drSndCfg.getSenderHubLoadBalancingMode() != null,
                "cfg.getDrSendConfiguration().getSenderHubLoadBalancingMode() != null");

            assertParameter(drSndCfg.getStateTransferThreadsCount() > 0,
                "cfg.getDrSenderConfiguration().getStateTransferThreadsCount() > 0");

            assertParameter(drSndCfg.getStateTransferThrottle() >= 0,
                "cfg.getDrSenderConfiguration().getStateTransferThrottle >= 0");
        }

        // Validate DR receive configuration.
        GridDrReceiverCacheConfiguration drRcvCfg = cc.getDrReceiverConfiguration();

        if (drRcvCfg != null) {
            if (ggfsCache)
                throw new GridException("GGFS cache cannot be data center replication receiver cache: " +
                    cc.getName());

            if (mongoCache)
                throw new GridException("Mongo cache cannot be data center replication receiver cache: " +
                    cc.getName());

            GridDrReceiverCacheConflictResolverMode rslvrMode = drRcvCfg.getConflictResolverMode();

            assertParameter(rslvrMode != null, "cfg.getDrReceiverConfiguration().getConflictResolverPolicy() != null");

            if (rslvrMode != DR_AUTO && drRcvCfg.getConflictResolver() == null)
                throw new GridException("Conflict resolver must be not null with " + rslvrMode + " resolving policy");
        }
    }

    /**
     * @param ctx Context.
     * @return DHT managers.
     */
    private List<GridCacheManager> dhtManagers(GridCacheContext ctx) {
        return F.asList(ctx.store(), ctx.mvcc(), ctx.events(), ctx.tm(), ctx.swap(), ctx.dgc(), ctx.evicts(),
            ctx.queries(), ctx.continuousQueries(), ctx.dr());
    }

    /**
     * @param ctx Context.
     * @return Managers present in both, DHT and Near caches.
     */
    @SuppressWarnings("IfMayBeConditional")
    private Collection<GridCacheManager> dhtExcludes(GridCacheContext ctx) {
        if (ctx.config().getCacheMode() == LOCAL || !isNearEnabled(ctx))
            return Collections.emptyList();
        else
            return F.asList((GridCacheManager)ctx.dgc(), ctx.queries(), ctx.continuousQueries());
    }

    /**
     * @param cfg Configuration.
     * @throws GridException If failed to inject.
     */
    private void prepare(GridCacheConfiguration cfg) throws GridException {
        prepare(cfg, cfg.getEvictionPolicy(), false);
        prepare(cfg, cfg.getNearEvictionPolicy(), true);
        prepare(cfg, cfg.getAffinity(), false);
        prepare(cfg, cfg.getAffinityMapper(), false);
        prepare(cfg, cfg.getTransactionManagerLookup(), false);
        prepare(cfg, cfg.getCloner(), false);
        prepare(cfg, cfg.getStore(), false);
        prepare(cfg, cfg.getEvictionFilter(), false);

        GridDrSenderCacheConfiguration drSndCfg = cfg.getDrSenderConfiguration();

        if (drSndCfg != null)
            prepare(cfg, drSndCfg.getEntryFilter(), false);

        GridDrReceiverCacheConfiguration drRcvCfg = cfg.getDrReceiverConfiguration();

        if (drRcvCfg != null)
            prepare(cfg, drRcvCfg.getConflictResolver(), false);
    }

    /**
     * @param cfg Cache configuration.
     * @param rsrc Resource.
     * @param near Near flag.
     * @throws GridException If failed.
     */
    private void prepare(GridCacheConfiguration cfg, @Nullable Object rsrc, boolean near) throws GridException {
        if (rsrc != null) {
            ctx.resource().injectGeneric(rsrc);

            ctx.resource().injectCacheName(rsrc, cfg.getName());

            registerMbean(rsrc, cfg.getName(), near);
        }
    }

    /**
     * @param cctx Cache context.
     */
    private void cleanup(GridCacheContext cctx) {
        GridCacheConfiguration cfg = cctx.config();

        cleanup(cfg, cfg.getEvictionPolicy(), false);
        cleanup(cfg, cfg.getNearEvictionPolicy(), true);
        cleanup(cfg, cfg.getAffinity(), false);
        cleanup(cfg, cfg.getAffinityMapper(), false);
        cleanup(cfg, cfg.getTransactionManagerLookup(), false);
        cleanup(cfg, cfg.getCloner(), false);
        cleanup(cfg, cfg.getStore(), false);

        cctx.cleanup();
    }

    /**
     * @param cfg Cache configuration.
     * @param rsrc Resource.
     * @param near Near flag.
     */
    private void cleanup(GridCacheConfiguration cfg, @Nullable Object rsrc, boolean near) {
        if (rsrc != null) {
            unregisterMbean(rsrc, cfg.getName(), near);

            try {
                ctx.resource().cleanupGeneric(rsrc);
            }
            catch (GridException e) {
                U.error(log, "Failed to cleanup resource: " + rsrc, e);
            }
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings( {"unchecked"})
    @Override public void start() throws GridException {
        if (ctx.config().isDaemon())
            return;

        GridDeploymentMode depMode = ctx.config().getDeploymentMode();

        if (!F.isEmpty(ctx.config().getCacheConfiguration())) {
            if (depMode != CONTINUOUS && depMode != SHARED)
                U.warn(log, "Deployment mode for cache is not CONTINUOUS or SHARED " +
                    "(it is recommended that you change deployment mode and restart): " + depMode,
                    "Deployment mode for cache is not CONTINUOUS or SHARED.");
        }

        maxPreloadOrder = validatePreloadOrder(ctx.config().getCacheConfiguration());

        // Internal caches which should not be returned to user.
        GridGgfsConfiguration[] ggfsCfgs = ctx.grid().configuration().getGgfsConfiguration();

        if (ggfsCfgs != null) {
            for (GridGgfsConfiguration ggfsCfg : ggfsCfgs) {
                sysCaches.add(ggfsCfg.getMetaCacheName());
                sysCaches.add(ggfsCfg.getDataCacheName());
            }
        }

        for (GridCacheConfiguration ccfg : ctx.grid().configuration().getCacheConfiguration()) {
            if (ccfg.getDrSenderConfiguration() != null)
                sysCaches.add(CU.cacheNameForReplicationSystemCache(ccfg.getName()));
        }

        GridDrSenderHubConfiguration sndHubCfg = ctx.grid().configuration().getDrSenderHubConfiguration();

        if (sndHubCfg != null && sndHubCfg.getCacheNames() != null) {
            for (String cacheName : sndHubCfg.getCacheNames())
                sysCaches.add(CU.cacheNameForReplicationSystemCache(cacheName));
        }

        GridCacheConfiguration[] cfgs = ctx.config().getCacheConfiguration();

        for (int i = 0; i < cfgs.length; i++) {
            GridCacheConfiguration cfg = new GridCacheConfiguration(cfgs[i]);

            // Initialize defaults.
            initialize(cfg);

            suggestOptimizations(ctx.config(), cfg);

            validate(ctx.config(), cfg);

            prepare(cfg);

            U.startLifecycleAware(lifecycleAwares(cfg));

            cfgs[i] = cfg; // Replace original configuration value.

            GridCacheMvccManager mvccMgr = new GridCacheMvccManager();
            GridCacheTxManager tm = new GridCacheTxManager();
            GridCacheAffinityManager affMgr = new GridCacheAffinityManager();
            GridCacheVersionManager verMgr = new GridCacheVersionManager();
            GridCacheEventManager evtMgr = new GridCacheEventManager();
            GridCacheSwapManager swapMgr = new GridCacheSwapManager(cfg.getCacheMode() == LOCAL || !isNearEnabled(cfg));
            GridCacheDgcManager dgcMgr = new GridCacheDgcManager();
            GridCacheDeploymentManager depMgr = new GridCacheDeploymentManager();
            GridCacheEvictionManager evictMgr = new GridCacheEvictionManager();
            GridCacheQueryManager qryMgr = queryManager(cfg);
            GridCacheContinuousQueryManager contQryMgr = new GridCacheContinuousQueryManager();
            GridCacheIoManager ioMgr = new GridCacheIoManager();
            GridCacheDataStructuresManager dataStructuresMgr = new GridCacheEnterpriseDataStructuresManager();
            GridCacheTtlManager ttlMgr = new GridCacheTtlManager();
            GridCacheDrManager drMgr = createComponent(GridCacheDrManager.class);

            GridCacheStore nearStore = cacheStore(ctx.gridName(), cfg, isNearEnabled(cfg));
            GridCacheStoreManager storeMgr = new GridCacheStoreManager(nearStore);

            GridCacheContext<?, ?> cacheCtx = new GridCacheContext(
                ctx,
                cfg,

                /*
                 * Managers in starting order!
                 * ===========================
                 */
                mvccMgr,
                verMgr,
                evtMgr,
                swapMgr,
                storeMgr,
                depMgr,
                evictMgr,
                ioMgr,
                qryMgr,
                contQryMgr,
                dgcMgr,
                affMgr,
                tm,
                dataStructuresMgr,
                ttlMgr,
                drMgr);

            GridCacheAdapter cache = null;

            switch (cfg.getCacheMode()) {
                case LOCAL: {
                    cache = new GridLocalCache(cacheCtx);

                    break;
                }
                case PARTITIONED:
                case REPLICATED: {
                    if (isNearEnabled(cfg))
                        cache = new GridNearCache(cacheCtx);
                    else {
                        switch (cfg.getAtomicityMode()) {
                            case TRANSACTIONAL: {
                                cache = isAffinityNode(cfg) ? new GridDhtColocatedCache(cacheCtx) :
                                    new GridDhtColocatedCache(cacheCtx, new GridNoStorageCacheMap(cacheCtx));

                                break;
                            }
                            case ATOMIC: {
                                cache = isAffinityNode(cfg) ? new GridDhtAtomicCache(cacheCtx) :
                                    new GridDhtAtomicCache(cacheCtx, new GridNoStorageCacheMap(cacheCtx));

                                break;
                            }

                            default: {
                                assert false : "Invalid cache atomicity mode: " + cfg.getAtomicityMode();
                            }
                        }
                    }

                    break;
                }

                default: {
                    assert false : "Invalid cache mode: " + cfg.getCacheMode();
                }
            }

            cacheCtx.cache(cache);

            if (caches.containsKey(cfg.getName())) {
                String cacheName = cfg.getName();

                if (cacheName != null)
                    throw new GridException("Duplicate cache name found (check configuration and " +
                        "assign unique name to each cache): " + cacheName);
                else
                    throw new GridException("Default cache has already been configured (check configuration and " +
                        "assign unique name to each cache).");
            }

            caches.put(cfg.getName(), cache);

            if (sysCaches.contains(cfg.getName()))
                stopSeq.addLast(cache);
            else
                stopSeq.addFirst(cache);

            // Start managers.
            for (GridCacheManager mgr : F.view(cacheCtx.managers(), F.notContains(dhtExcludes(cacheCtx))))
                mgr.start(cacheCtx);

            /*
             * Start DHT cache.
             * ================
             */
            if (cfg.getCacheMode() != LOCAL && isNearEnabled(cfg)) {
                /*
                 * Specifically don't create the following managers
                 * here and reuse the one from Near cache:
                 * 1. GridCacheVersionManager
                 * 2. GridCacheIoManager
                 * 3. GridCacheDeploymentManager
                 * 4. GridCacheQueryManager (note, that we start it for DHT cache though).
                 * 5. GridCacheContinuousQueryManager (note, that we start it for DHT cache though).
                 * 6. GridCacheDgcManager
                 * 7. GridCacheTtlManager.
                 * ===============================================
                 */
                mvccMgr = new GridCacheMvccManager();
                tm = new GridCacheTxManager();
                swapMgr = new GridCacheSwapManager(true);
                evictMgr = new GridCacheEvictionManager();
                evtMgr = new GridCacheEventManager();
                drMgr = createComponent(GridCacheDrManager.class);

                GridCacheStore dhtStore = cacheStore(ctx.gridName(), cfg, false);

                storeMgr = new GridCacheStoreManager(dhtStore);

                cacheCtx = new GridCacheContext(
                    ctx,
                    cfg,

                    /*
                     * Managers in starting order!
                     * ===========================
                     */
                    mvccMgr,
                    verMgr,
                    evtMgr,
                    swapMgr,
                    storeMgr,
                    depMgr,
                    evictMgr,
                    ioMgr,
                    qryMgr,
                    contQryMgr,
                    dgcMgr,
                    affMgr,
                    tm,
                    dataStructuresMgr,
                    ttlMgr,
                    drMgr);

                assert cache instanceof GridNearCache;

                GridNearCache near = (GridNearCache)cache;
                GridDhtCache dht = !isAffinityNode(cfg) ?
                    new GridDhtCache(cacheCtx, new GridNoStorageCacheMap(cacheCtx)) :
                    new GridDhtCache(cacheCtx);

                cacheCtx.cache(dht);

                near.dht(dht);
                dht.near(near);

                // Start managers.
                for (GridCacheManager mgr : dhtManagers(cacheCtx))
                    mgr.start(cacheCtx);

                dht.start();

                if (log.isDebugEnabled())
                    log.debug("Started DHT cache: " + dht.name());
            }

            cache.start();

            if (log.isInfoEnabled())
                log.info("Started cache [name=" + cfg.getName() + ", mode=" + cfg.getCacheMode() + ']');
        }

        for (Map.Entry<String, GridCacheAdapter<?, ?>> e : caches.entrySet()) {
            GridCacheAdapter cache = e.getValue();

            proxies.put(e.getKey(), new GridCacheProxyImpl(cache.context(), cache, null));
        }

        for (GridCacheAdapter<?, ?> cache : caches.values()) {
            try {
                ObjectName mb = U.registerCacheMBean(mBeanSrv, ctx.gridName(), cache.name(), "Cache",
                    new GridCacheMBeanAdapter(cache.context()), GridCacheMBean.class);

                cacheMBeans.add(mb);

                if (log.isDebugEnabled())
                    log.debug("Registered cache MBean: " + mb);
            }
            catch (JMException ex) {
                U.error(log, "Failed to register cache MBean.", ex);
            }
        }

        // Internal caches which should not be returned to user.
        for (Map.Entry<String, GridCacheAdapter<?, ?>> e : caches.entrySet()) {
            GridCacheAdapter cache = e.getValue();

            if (!sysCaches.contains(e.getKey()))
                publicProxies.put(e.getKey(), new GridCacheProxyImpl(cache.context(), cache, null));
        }

        if (log.isDebugEnabled())
            log.debug("Started cache processor.");
    }

    /** {@inheritDoc} */
    @Override public void addAttributes(Map<String, Object> attrs) throws GridException {
        if (ctx.isDaemon() || F.isEmpty(ctx.config().getCacheConfiguration()))
            return;

        GridCacheAttributes[] attrVals = new GridCacheAttributes[ctx.config().getCacheConfiguration().length];

        Collection<String> replicationCaches = new ArrayList<>();

        int i = 0;

        for (GridCacheConfiguration cfg : ctx.config().getCacheConfiguration()) {
            attrVals[i++] = new GridCacheAttributes(cfg);

            if (cfg.getDrSenderConfiguration() != null)
                replicationCaches.add(cfg.getName());
        }

        attrs.put(ATTR_CACHE, attrVals);

        attrs.put(ATTR_REPLICATION_CACHES, replicationCaches);
    }

    /**
     * Checks that preload-order-dependant caches has SYNC or ASYNC preloading mode.
     *
     * @param cfgs Caches.
     * @return Maximum detected preload order.
     * @throws GridException If validation failed.
     */
    private int validatePreloadOrder(GridCacheConfiguration[] cfgs) throws GridException {
        int maxOrder = 0;

        for (GridCacheConfiguration cfg : cfgs) {
            int preloadOrder = cfg.getPreloadOrder();

            if (preloadOrder > 0) {
                if (cfg.getCacheMode() == LOCAL)
                    throw new GridException("Preload order set for local cache (fix configuration and restart the " +
                        "node): " + cfg.getName());

                if (cfg.getPreloadMode() == GridCachePreloadMode.NONE)
                    throw new GridException("Only caches with SYNC or ASYNC preload mode can be set as preload " +
                        "dependency for other caches [cacheName=" + cfg.getName() +
                        ", preloadMode=" + cfg.getPreloadMode() + ", preloadOrder=" + cfg.getPreloadOrder() + ']');

                maxOrder = Math.max(maxOrder, preloadOrder);
            }
            else if (preloadOrder < 0)
                throw new GridException("Preload order cannot be negative for cache (fix configuration and restart " +
                    "the node) [cacheName=" + cfg.getName() + ", preloadOrder=" + preloadOrder + ']');
        }

        return maxOrder;
    }

    /**
     * Checks that remote caches has configuration compatible with the local.
     *
     * @param rmt Node.
     * @throws GridException If check failed.
     */
    private void checkCache(GridNode rmt) throws GridException {
        GridCacheAttributes[] rmtAttrs = U.cacheAttributes(rmt);
        GridCacheAttributes[] locAttrs = U.cacheAttributes(ctx.discovery().localNode());

        // If remote or local node does not have cache configured, do nothing
        if (F.isEmpty(rmtAttrs) || F.isEmpty(locAttrs))
            return;

        GridDeploymentMode locDepMode = ctx.config().getDeploymentMode();
        GridDeploymentMode rmtDepMode = rmt.attribute(GridNodeAttributes.ATTR_DEPLOYMENT_MODE);

        for (GridCacheAttributes rmtAttr : rmtAttrs) {
            for (GridCacheAttributes locAttr : locAttrs) {
                if (F.eq(rmtAttr.cacheName(), locAttr.cacheName())) {
                    CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "cacheMode", "Cache mode",
                        locAttr.cacheMode(), rmtAttr.cacheMode(), true);

                    if (rmtAttr.cacheMode() != LOCAL) {
                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "atomicityMode",
                            "Cache atomicity mode", locAttr.atomicityMode(), rmtAttr.atomicityMode(), true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "cachePreloadMode",
                            "Cache preload mode", locAttr.cachePreloadMode(), rmtAttr.cachePreloadMode(), true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "cacheAffinity", "Cache affinity",
                            locAttr.cacheAffinityClassName(), rmtAttr.cacheAffinityClassName(), true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "cacheAffinityMapper",
                            "Cache affinity mapper", locAttr.cacheAffinityMapperClassName(),
                            rmtAttr.cacheAffinityMapperClassName(), true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "affinityPartitionsCount",
                            "Affinity partitions count", locAttr.affinityPartitionsCount(),
                            rmtAttr.affinityPartitionsCount(), true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "evictionFilter", "Eviction filter",
                            locAttr.evictionFilterClassName(), rmtAttr.evictionFilterClassName(), true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "evictionPolicy", "Eviction policy",
                            locAttr.evictionPolicyClassName(), rmtAttr.evictionPolicyClassName(), true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "store", "Cache store",
                            locAttr.storeClassName(), rmtAttr.storeClassName(), true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "cloner", "Cache cloner",
                            locAttr.clonerClassName(), rmtAttr.clonerClassName(), false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "transactionManagerLookup",
                            "Transaction manager lookup", locAttr.transactionManagerLookupClassName(),
                            rmtAttr.transactionManagerLookupClassName(), false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "atomicSequenceReserveSize",
                            "Atomic sequence reserve size", locAttr.sequenceReserveSize(),
                            rmtAttr.sequenceReserveSize(), false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "batchUpdateOnCommit",
                            "Batch update on commit", locAttr.txBatchUpdate(), rmtAttr.txBatchUpdate(), false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "defaultLockTimeout",
                            "Default lock timeout", locAttr.defaultLockTimeout(), rmtAttr.defaultLockTimeout(), false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "defaultQueryTimeout",
                            "Default query timeout", locAttr.defaultQueryTimeout(), rmtAttr.defaultQueryTimeout(),
                            false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "dgcFrequency",
                            "Distributed garbage collector frequency", locAttr.dgcFrequency(), rmtAttr.dgcFrequency(),
                            false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "defaultTimeToLive",
                            "Default time to live", locAttr.defaultTimeToLive(), rmtAttr.defaultTimeToLive(), false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "defaultTxConcurrency",
                            "Default transaction concurrency", locAttr.defaultConcurrency(),
                            rmtAttr.defaultConcurrency(), false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "defaultTxIsolation",
                            "Default transaction isolation", locAttr.defaultIsolation(), rmtAttr.defaultIsolation(),
                            false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "defaultTxTimeout",
                            "Default transaction timeout", locAttr.defaultTxTimeout(), rmtAttr.defaultTxTimeout(),
                            false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "dgcFrequency",
                            "Distributed garbage collector frequency", locAttr.dgcFrequency(), rmtAttr.dgcFrequency(),
                            false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "dgcRemoveLocks",
                            "Distributed garbage collector remove locks", locAttr.dgcRemoveLocks(),
                            rmtAttr.dgcRemoveLocks(), false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "dgcSuspectLockTimeout",
                            "Distributed garbage collector suspect lock timeout", locAttr.dgcSuspectLockTimeout(),
                            rmtAttr.dgcSuspectLockTimeout(), false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "preloadBatchSize",
                            "Preload batch size", locAttr.preloadBatchSize(), rmtAttr.preloadBatchSize(), false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "swapEnabled",
                            "Swap enabled", locAttr.swapEnabled(), rmtAttr.swapEnabled(), false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "writeSynchronizationMode",
                            "Write synchronization mode", locAttr.writeSynchronization(), rmtAttr.writeSynchronization(),
                            true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "writeBehindBatchSize",
                            "Write behind batch size", locAttr.writeBehindBatchSize(), rmtAttr.writeBehindBatchSize(),
                            false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "writeBehindEnabled",
                            "Write behind enabled", locAttr.writeBehindEnabled(), rmtAttr.writeBehindEnabled(), false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "writeBehindFlushFrequency",
                            "Write behind flush frequency", locAttr.writeBehindFlushFrequency(),
                            rmtAttr.writeBehindFlushFrequency(), false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "writeBehindFlushSize",
                            "Write behind flush size", locAttr.writeBehindFlushSize(), rmtAttr.writeBehindFlushSize(),
                            false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "writeBehindFlushThreadCount",
                            "Write behind flush thread count", locAttr.writeBehindFlushThreadCount(),
                            rmtAttr.writeBehindFlushThreadCount(), false);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "evictMaxOverflowRatio",
                            "Eviction max overflow ratio", locAttr.evictMaxOverflowRatio(),
                            rmtAttr.evictMaxOverflowRatio(), true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "indexingSpiName", "IndexingSpiName",
                            locAttr.indexingSpiName(), rmtAttr.indexingSpiName(), true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "queryIndexEnabled",
                            "Query index enabled", locAttr.queryIndexEnabled(), rmtAttr.queryIndexEnabled(), true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "storeEnabled", "Store enabled",
                            locAttr.storeEnabled(), rmtAttr.storeEnabled(), true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "storeValueBytes",
                            "Store value bytes", locAttr.storeValueBytes(), rmtAttr.storeValueBytes(), true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "txSerializableEnabled",
                            "Transaction serializable enabled", locAttr.txSerializableEnabled(),
                            rmtAttr.txSerializableEnabled(), true);

                        CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "queryIndexEnabled",
                            "Query index enabled", locAttr.queryIndexEnabled(), rmtAttr.queryIndexEnabled(), true);

                        if (locAttr.cacheMode() == PARTITIONED) {
                            CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "evictSynchronized",
                                "Eviction synchronized", locAttr.evictSynchronized(), rmtAttr.evictSynchronized(),
                                true);

                            CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "evictNearSynchronized",
                                "Eviction near synchronized", locAttr.evictNearSynchronized(),
                                rmtAttr.evictNearSynchronized(), true);

                            CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "nearEvictionPolicy",
                                "Near eviction policy", locAttr.nearEvictionPolicyClassName(),
                                rmtAttr.nearEvictionPolicyClassName(), false);

                            CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "affinityIncludeNeighbors",
                                "Affinity include neighbors", locAttr.affinityIncludeNeighbors(),
                                rmtAttr.affinityIncludeNeighbors(), true);

                            CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "affinityKeyBackups",
                                "Affinity key backups", locAttr.affinityKeyBackups(),
                                rmtAttr.affinityKeyBackups(), true);

                            CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "affinityReplicas",
                                "Affinity replicas", locAttr.affinityReplicas(),
                                rmtAttr.affinityReplicas(), true);

                            CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "affinityReplicaCountAttrName",
                                "Affinity replica count attribute name", locAttr.affinityReplicaCountAttrName(),
                                rmtAttr.affinityReplicaCountAttrName(), true);

                            CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "cacheAffinity.hashIdResolver",
                                "Partitioned cache affinity hash ID resolver class",
                                locAttr.affinityHashIdResolverClassName(), rmtAttr.affinityHashIdResolverClassName(),
                                true);
                        }

                        // Validate DR send configuration.
                        GridCacheDrSendAttributes locSndAttrs = locAttr.drSendAttributes();

                        GridCacheDrSendAttributes rmtSndAttrs = rmtAttr.drSendAttributes();

                        if (locSndAttrs != null && rmtSndAttrs != null) {
                            CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "replicationMode",
                                "Replication mode", locSndAttrs.mode(), rmtSndAttrs.mode(), true);

                            CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "entryFilterClassName",
                                "Class name for replication cache entry filter", locSndAttrs.entryFilterClassName(),
                                rmtSndAttrs.entryFilterClassName(), true);
                        }
                        else if (!(locSndAttrs == null && rmtSndAttrs == null)) {
                            UUID nullAttrNode = locSndAttrs == null ? ctx.discovery().localNode().id() : rmt.id();

                            throw new GridException("Replication sender cache should be enabled for all nodes or " +
                                "disabled for all of them (configuration is not set for nodeId=" + nullAttrNode + ").");
                        }

                        // Validate DR receive configuration.
                        GridCacheDrReceiveAttributes locRcvAttrs = locAttr.drReceiveAttributes();

                        GridCacheDrReceiveAttributes rmtRcvAttrs = rmtAttr.drReceiveAttributes();

                        if (locRcvAttrs != null && rmtRcvAttrs != null) {
                            CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "conflictResolverPolicy",
                                "Policy for conflict resolver", locRcvAttrs.conflictResolverMode(),
                                rmtRcvAttrs.conflictResolverMode(), true);

                            CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "conflictResolverClassName",
                                "Class name for conflict resolver", locRcvAttrs.conflictResolverClassName(),
                                rmtRcvAttrs.conflictResolverClassName(), true);
                        }
                        else if (!(locRcvAttrs == null && rmtRcvAttrs == null)) {
                            UUID nullAttrNode = locRcvAttrs == null ? ctx.discovery().localNode().id() : rmt.id();

                            throw new GridException("DR receiver cache should be enabled for all nodes or " +
                                "disabled for all of them (configuration is not set for nodeId=" + nullAttrNode + ").");
                        }
                    }
                }

                CU.checkAttributeMismatch(log, rmtAttr.cacheName(), rmt, "deploymentMode", "Deployment mode",
                    locDepMode, rmtDepMode, true);
            }
        }
    }

    /**
     * @param cache Cache.
     * @throws GridException If failed.
     */
    @SuppressWarnings("unchecked")
    private void onKernalStart(GridCacheAdapter<?, ?> cache) throws GridException {
        GridCacheContext<?, ?> ctx = cache.context();

        // Start DHT cache as well.
        if (isNearEnabled(ctx)) {
            GridDhtCache dht = ctx.near().dht();

            GridCacheContext<?, ?> dhtCtx = dht.context();

            for (GridCacheManager mgr : dhtManagers(dhtCtx))
                mgr.onKernalStart();

            dht.onKernalStart();

            if (log.isDebugEnabled())
                log.debug("Executed onKernalStart() callback for DHT cache: " + dht.name());
        }

        for (GridCacheManager mgr : F.view(ctx.managers(), F0.notContains(dhtExcludes(ctx))))
            mgr.onKernalStart();

        cache.onKernalStart();

        if (log.isDebugEnabled())
            log.debug("Executed onKernalStart() callback for cache [name=" + cache.name() + ", mode=" +
                cache.configuration().getCacheMode() + ']');
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public void onKernalStart() throws GridException {
        if (ctx.config().isDaemon())
            return;

        for (GridNode n : ctx.discovery().remoteNodes())
            checkCache(n);

        for (Map.Entry<String, GridCacheAdapter<?, ?>> e : caches.entrySet()) {
            GridCacheAdapter cache = e.getValue();

            if (maxPreloadOrder > 0) {
                GridCacheConfiguration cfg = cache.configuration();

                int order = cfg.getPreloadOrder();

                if (order > 0 && order != maxPreloadOrder && cfg.getCacheMode() != LOCAL) {
                    GridCompoundFuture<Object, Object> fut = (GridCompoundFuture<Object, Object>)preloadFuts
                        .get(order);

                    if (fut == null) {
                        fut = new GridCompoundFuture<>(ctx);

                        preloadFuts.put(order, fut);
                    }

                    fut.add(cache.preloader().syncFuture());
                }
            }
        }

        for (GridFuture<?> fut : preloadFuts.values())
            ((GridCompoundFuture<Object, Object>)fut).markInitialized();

        for (GridCacheAdapter<?, ?> cache : caches.values())
            onKernalStart(cache);

        // Wait for caches in SYNC preload mode.
        for (GridCacheAdapter<?, ?> cache : caches.values()) {
            GridCacheConfiguration cfg = cache.configuration();

            if (cfg.getPreloadMode() == SYNC) {
                if (cfg.getCacheMode() == REPLICATED ||
                    (cfg.getCacheMode() == PARTITIONED && cfg.getPreloadPartitionedDelay() >= 0))
                    cache.preloader().syncFuture().get();
            }
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public void onKernalStop(boolean cancel) {
        if (ctx.config().isDaemon())
            return;

        if (!F.isEmpty(cacheMBeans))
            for (ObjectName mb : cacheMBeans) {
                try {
                    mBeanSrv.unregisterMBean(mb);

                    if (log.isDebugEnabled())
                        log.debug("Unregistered cache MBean: " + mb);
                }
                catch (JMException e) {
                    U.error(log, "Failed to unregister cache MBean: " + mb, e);
                }
            }

        for (GridCacheAdapter<?, ?> cache : stopSeq) {
            GridCacheContext ctx = cache.context();

            if (isNearEnabled(ctx)) {
                GridDhtCache dht = ctx.near().dht();

                if (dht != null) {
                    GridCacheContext<?, ?> dhtCtx = dht.context();

                    for (GridCacheManager mgr : dhtManagers(dhtCtx))
                        mgr.onKernalStop(cancel);

                    dht.onKernalStop();
                }
            }

            List<GridCacheManager> mgrs = ctx.managers();

            Collection<GridCacheManager> excludes = dhtExcludes(ctx);

            // Reverse order.
            for (ListIterator<GridCacheManager> it = mgrs.listIterator(mgrs.size()); it.hasPrevious(); ) {
                GridCacheManager mgr = it.previous();

                if (!excludes.contains(mgr))
                    mgr.onKernalStop(cancel);
            }

            cache.onKernalStop();
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public void stop(boolean cancel) throws GridException {
        if (ctx.config().isDaemon())
            return;

        for (GridCacheAdapter<?, ?> cache : stopSeq) {
            cache.stop();

            GridCacheContext ctx = cache.context();

            if (isNearEnabled(ctx)) {
                GridDhtCache dht = ctx.near().dht();

                // Check whether dht cache has been started.
                if (dht != null) {
                    dht.stop();

                    GridCacheContext<?, ?> dhtCtx = dht.context();

                    List<GridCacheManager> dhtMgrs = dhtManagers(dhtCtx);

                    for (ListIterator<GridCacheManager> it = dhtMgrs.listIterator(dhtMgrs.size()); it.hasPrevious();) {
                        GridCacheManager mgr = it.previous();

                        mgr.stop(cancel);
                    }
                }
            }

            List<GridCacheManager> mgrs = ctx.managers();

            Collection<GridCacheManager> excludes = dhtExcludes(ctx);

            // Reverse order.
            for (ListIterator<GridCacheManager> it = mgrs.listIterator(mgrs.size()); it.hasPrevious();) {
                GridCacheManager mgr = it.previous();

                if (!excludes.contains(mgr))
                    mgr.stop(cancel);
            }

            U.stopLifecycleAware(log, lifecycleAwares(cache.configuration()));

            if (log.isInfoEnabled())
                log.info("Stopped cache: " + cache.name());

            cleanup(ctx);
        }

        if (log.isDebugEnabled())
            log.debug("Stopped cache processor.");
    }

    /**
     * Gets preload finish future for preload-ordered cache with given order. I.e. will get compound preload future
     * with maximum order less than {@code order}.
     *
     * @param order Cache order.
     * @return Compound preload future or {@code null} if order is minimal order found.
     */
    @Nullable public GridFuture<?> orderedPreloadFuture(int order) {
        Map.Entry<Integer, GridFuture<?>> entry = preloadFuts.lowerEntry(order);

        return entry == null ? null : entry.getValue();
    }

    /**
     * @param spaceName Space name.
     * @param keyBytes Key bytes.
     */
    @SuppressWarnings( {"unchecked"})
    public void onEvictFromSwap(String spaceName, byte[] keyBytes) {
        assert spaceName != null;
        assert keyBytes != null;

        /*
         * NOTE: this method should not have any synchronization because
         * it is called from synchronization block within Swap SPI.
         */

        GridCacheAdapter cache = caches.get(CU.cacheNameForSwapSpaceName(spaceName));

        assert cache != null : "Failed to resolve cache name for swap space name: " + spaceName;

        GridCacheContext cctx = cache.configuration().getCacheMode() == PARTITIONED ?
            ((GridNearCache<?, ?>)cache).dht().context() : cache.context();

        if (spaceName.equals(CU.swapSpaceName(cctx))) {
            GridCacheQueryManager qryMgr = cctx.queries();

            if (qryMgr != null) {
                try {
                    Object key = cctx.marshaller().unmarshal(keyBytes, cctx.deploy().globalLoader());

                    qryMgr.remove(key, keyBytes);
                }
                catch (GridException e) {
                    U.error(log, "Failed to unmarshal key evicted from swap [swapSpaceName=" + spaceName + ']', e);
                }
            }
        }
    }

    /**
     * @param cfg Cache configuration.
     * @return Query manager.
     */
    private GridCacheQueryManager queryManager(GridCacheConfiguration cfg) {
        return cfg.getCacheMode() == LOCAL ? new GridCacheLocalQueryManager() : new GridCacheDistributedQueryManager();
    }

    /**
     * @return Last data version.
     */
    public long lastDataVersion() {
        long max = 0;

        for (GridCacheAdapter<?, ?> cache : caches.values()) {
            GridCacheContext<?, ?> ctx = cache.context();

            if (ctx.versions().last().order() > max)
                max = ctx.versions().last().order();

            if (ctx.isNear()) {
                ctx = ctx.near().dht().context();

                if (ctx.versions().last().order() > max)
                    max = ctx.versions().last().order();
            }
        }

        assert caches.isEmpty() || max > 0;

        return max;
    }

    /**
     * @param <K> type of keys.
     * @param <V> type of values.
     * @return Default cache.
     */
    public <K, V> GridCache<K, V> cache() {
        return cache(null);
    }

    /**
     * @param name Cache name.
     * @param <K> type of keys.
     * @param <V> type of values.
     * @return Cache instance for given name.
     */
    @SuppressWarnings("unchecked")
    public <K, V> GridCache<K, V> cache(@Nullable String name) {
        if (log.isDebugEnabled())
            log.debug("Getting cache for name: " + name);

        return (GridCache<K, V>)proxies.get(name);
    }

    /**
     * @return All configured cache instances.
     */
    public Collection<GridCache<?, ?>> caches() {
        return proxies.values();
    }

    /**
     * @param <K> type of keys.
     * @param <V> type of values.
     * @return Default cache.
     */
    public <K, V> GridCache<K, V> publicCache() {
        return publicCache(null);
    }

    /**
     * @param name Cache name.
     * @param <K> type of keys.
     * @param <V> type of values.
     * @return Cache instance for given name.
     */
    public <K, V> GridCache<K, V> publicCache(@Nullable String name) {
        if (log.isDebugEnabled())
            log.debug("Getting public cache for name: " + name);

        if (sysCaches.contains(name))
            throw new IllegalStateException("Failed to get cache because it is system cache: " + name);

        return (GridCache<K, V>)publicProxies.get(name);
    }

    /**
     * @return All configured public cache instances.
     */
    public Collection<GridCache<?, ?>> publicCaches() {
        return publicProxies.values();
    }

    /**
     * @param <K> type of keys.
     * @param <V> type of values.
     * @return Default cache.
     */
    public <K, V> GridCacheAdapter<K, V> internalCache() {
        return internalCache(null);
    }

    /**
     * @param name Cache name.
     * @param <K> type of keys.
     * @param <V> type of values.
     * @return Cache instance for given name.
     */
    @SuppressWarnings("unchecked")
    public <K, V> GridCacheAdapter<K, V> internalCache(@Nullable String name) {
        if (log.isDebugEnabled())
            log.debug("Getting internal cache adapter: " + name);

        return (GridCacheAdapter<K, V>)caches.get(name);
    }

    /**
     * @return All internal cache instances.
     */
    public Collection<GridCacheAdapter<?, ?>> internalCaches() {
        return caches.values();
    }

    /** {@inheritDoc} */
    @Override public void printMemoryStats() {
        X.println(">>> ");

        for (GridCacheAdapter c : caches.values()) {
            X.println(">>> Cache memory stats [grid=" + ctx.gridName() + ", cache=" + c.name() + ']');

            c.context().printMemoryStats();
        }
    }

    /**
     * Callback invoked by deployment manager for whenever a class loader
     * gets undeployed.
     *
     * @param leftNodeId Left node ID.
     * @param ldr Class loader.
     */
    public void onUndeployed(@Nullable UUID leftNodeId, ClassLoader ldr) {
        if (!ctx.isStopping())
            for (GridCacheAdapter<?, ?> cache : caches.values())
                cache.onUndeploy(leftNodeId, ldr);
    }

    /**
     * Registers MBean for cache components.
     *
     * @param o Cache component.
     * @param cacheName Cache name.
     * @param near Near flag.
     * @throws GridException If registration failed.
     */
    private void registerMbean(Object o, @Nullable String cacheName, boolean near)
        throws GridException {
        assert o != null;

        MBeanServer srvr = ctx.config().getMBeanServer();

        assert srvr != null;

        cacheName = U.maskName(cacheName);

        cacheName = near ? cacheName + "-near" : cacheName;

        for (Class<?> itf : o.getClass().getInterfaces()) {
            if (itf.getName().endsWith("MBean")) {
                try {
                    U.registerCacheMBean(srvr, ctx.gridName(), cacheName, o.getClass().getName(), o,
                        (Class<Object>)itf);
                }
                catch (JMException e) {
                    throw new GridException("Failed to register MBean for component: " + o, e);
                }

                break;
            }
        }
    }

    /**
     * Unregisters MBean for cache components.
     *
     * @param o Cache component.
     * @param cacheName Cache name.
     * @param near Near flag.
     */
    private void unregisterMbean(Object o, @Nullable String cacheName, boolean near) {
        assert o != null;

        MBeanServer srvr = ctx.config().getMBeanServer();

        assert srvr != null;

        cacheName = U.maskName(cacheName);

        cacheName = near ? cacheName + "-near" : cacheName;

        for (Class<?> itf : o.getClass().getInterfaces()) {
            if (itf.getName().endsWith("MBean")) {
                try {
                    srvr.unregisterMBean(U.makeCacheMBeanName(ctx.gridName(), cacheName, o.getClass().getName()));
                }
                catch (JMException e) {
                    U.error(log, "Failed to unregister MBean for component: " + o, e);
                }

                break;
            }
        }
    }

    /**
     * Creates a wrapped cache store if write-behind cache is configured.
     *
     * @param gridName Grid name.
     * @param cfg Cache configuration.
     * @param near Whether or not store retrieved for near cache.
     * @return Instance if {@link GridCacheWriteBehindStore} if write-behind store is configured,
     *         or user-defined cache store.
     */
    @SuppressWarnings({"unchecked"})
    private GridCacheStore cacheStore(String gridName, GridCacheConfiguration cfg, boolean near) {
        if (cfg.getStore() == null || !cfg.isWriteBehindEnabled())
            return cfg.getStore();

        // Write-behind store is used in DHT cache only.
        if (!near) {
            GridCacheWriteBehindStore store = new GridCacheWriteBehindStore(gridName, cfg.getName(), log,
                cfg.getStore());

            store.setFlushSize(cfg.getWriteBehindFlushSize());
            store.setFlushThreadCount(cfg.getWriteBehindFlushThreadCount());
            store.setFlushFrequency(cfg.getWriteBehindFlushFrequency());
            store.setBatchSize(cfg.getWriteBehindBatchSize());

            return store;
        }
        else
            return cfg.getStore();
    }

    /**
     * @param ccfg Cache configuration.
     * @return Components provided in cache configuration which can implement {@link GridLifecycleAware} interface.
     */
    private Iterable<Object> lifecycleAwares(GridCacheConfiguration ccfg) {
        return F.asList(ccfg.getAffinity(), ccfg.getAffinityMapper(), ccfg.getCloner(),
            ccfg.getEvictionFilter(), ccfg.getEvictionPolicy(), ccfg.getNearEvictionPolicy(),
            ccfg.getTransactionManagerLookup());
    }

    /**
     * Creates cache component which has different implementations for enterprise and open source versions.
     * For such components following convention is used:
     * <ul>
     *     <li>component has an interface (org.gridgain.xxx.GridXXXComponent)</li>
     *     <li>there are two component implementations in the subpackages 'ent' and 'os' where
     *     component implementations are named correspondingly GridEntXXXComponent and GridOSXXXComponent</li>
     *     <li>component implementation has public no-arg constructor </li>
     * </ul>
     * This method first tries to find component implementation from 'ent' package, if it is not found it
     * uses implementation from 'os' subpackage.
     *
     * @param cls Component interface.
     * @return Created component.
     * @throws GridException If failed to create component.
     */
    private static <T> T createComponent(Class<T> cls) throws GridException {
        assert cls.isInterface() : cls;
        assert cls.getSimpleName().startsWith("Grid") : cls;

        Class<T> implCls = null;

        try {
            implCls = (Class<T>)Class.forName(enterpriseClassName(cls));
        }
        catch (ClassNotFoundException ignore) {
            // No-op.
        }

        if (implCls == null) {
            try {
                implCls = (Class<T>)Class.forName(openSourceClassName(cls));
            }
            catch (ClassNotFoundException ignore) {
                // No-op.
            }
        }

        if (implCls == null)
            throw new GridException("Failed to find component implementation: " + cls.getName());

        if (!cls.isAssignableFrom(implCls))
            throw new GridException("Component implementation does not implement component interface " +
                "[component=" + cls.getName() + ", implementation=" + implCls.getName() + ']');

        Constructor<T> constructor;

        try {
            constructor = implCls.getConstructor();
        }
        catch (NoSuchMethodException e) {
            throw new GridException("Component does not have expected constructor: " + implCls.getName(), e);
        }

        try {
            return constructor.newInstance();
        }
        catch (ReflectiveOperationException e) {
            throw new GridException("Failed to create component [component=" + cls.getName() +
                ", implementation=" + implCls.getName() + ']', e);
        }
    }

    /**
     * @param cls Component interface.
     * @return Name of component implementation class for enterprise edition.
     */
    private static String enterpriseClassName(Class<?> cls) {
        return cls.getPackage().getName() + ".ent." + cls.getSimpleName().replace("Grid", "GridEnt");
    }

    /**
     * @param cls Component interface.
     * @return Name of component implementation class for open source edition.
     */
    private static String openSourceClassName(Class<?> cls) {
        return cls.getPackage().getName() + ".os." + cls.getSimpleName().replace("Grid", "GridOs");
    }

    /**
     *
     */
    private static class LocalAffinityFunction implements GridCacheAffinityFunction {
        @Override public List<List<GridNode>> assignPartitions(GridCacheAffinityFunctionContext affCtx) {
            GridNode locNode = null;

            for (GridNode n : affCtx.currentTopologySnapshot()) {
                if (n.isLocal()) {
                    locNode = n;

                    break;
                }
            }

            if (locNode == null)
                throw new GridRuntimeException("Local node is not included into affinity nodes for 'LOCAL' cache");

            List<List<GridNode>> res = new ArrayList<>(partitions());

            for (int part = 0; part < partitions(); part++)
                res.add(Collections.singletonList(locNode));

            return Collections.unmodifiableList(res);
        }

        /** {@inheritDoc} */
        @Override public void reset() {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override public int partitions() {
            return 1;
        }

        /** {@inheritDoc} */
        @Override public int partition(Object key) {
            return 0;
        }

        /** {@inheritDoc} */
        @Override public void removeNode(UUID nodeId) {
            // No-op.
        }
    }
}


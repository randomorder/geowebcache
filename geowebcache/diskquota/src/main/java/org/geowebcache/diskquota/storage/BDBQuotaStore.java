package org.geowebcache.diskquota.storage;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.storage.DefaultStorageFinder;
import org.geowebcache.storage.StorageException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.util.Assert;

import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.Transaction;
import com.sleepycat.persist.EntityCursor;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;
import com.sleepycat.persist.SecondaryIndex;

public class BDBQuotaStore implements QuotaStore, InitializingBean, DisposableBean {

    private static final Log log = LogFactory.getLog(BDBQuotaStore.class);

    private static final String GLOBAL_QUOTA_NAME = "___GLOBAL_QUOTA___";

    private EntityStore entityStore;

    private final String cacheRootDir;

    private final TilePageCalculator tilePageCalculator;

    private static ExecutorService transactionRunner;

    private PrimaryIndex<String, TileSet> tileSetById;

    private PrimaryIndex<Long, TilePage> pageById;

    private PrimaryIndex<Long, PageStats> pageStatsById;

    private PrimaryIndex<Integer, Quota> usedQuotaById;

    private SecondaryIndex<String, String, TileSet> tileSetsByLayer;

    private SecondaryIndex<String, Long, TilePage> pageByKey;

    private SecondaryIndex<Long, Long, PageStats> pageStatsByPageId;

    private SecondaryIndex<Float, Long, PageStats> pageStatsByLRU;

    private SecondaryIndex<Float, Long, PageStats> pageStatsByLFU;

    private SecondaryIndex<String, Integer, Quota> usedQuotaByTileSetId;

    private volatile boolean open;

    public BDBQuotaStore(final DefaultStorageFinder cacheDirFinder,
            TilePageCalculator tilePageCalculator) throws StorageException {

        Assert.notNull(cacheDirFinder, "cacheDirFinder can't be null");
        Assert.notNull(tilePageCalculator, "tilePageCalculator can't be null");

        this.tilePageCalculator = tilePageCalculator;
        this.cacheRootDir = cacheDirFinder.getDefaultPath();
    }

    /**
     * Initialization method called by Spring, actually loads an applies the page store
     * configuration
     * 
     * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
     */
    public void afterPropertiesSet() throws Exception {
        startUp();
    }

    public void startUp() throws InterruptedException {
        open = true;
        File storeDirectory = new File(cacheRootDir, "diskquota_page_store");
        storeDirectory.mkdirs();
        CustomizableThreadFactory tf = new CustomizableThreadFactory("GWC DiskQuota Store Writer-");
        transactionRunner = Executors.newFixedThreadPool(1, tf);
        try {
            configure(storeDirectory);

            deleteStaleLayersAndCreateMissingTileSets();

            log.info("Berkeley DB JE Disk Quota page store configured at "
                    + storeDirectory.getAbsolutePath());
        } catch (RuntimeException e) {
            transactionRunner.shutdownNow();
            throw e;
        }
        log.info("Quota Store initialized. Global quota: " + getGloballyUsedQuota().toNiceString());
    }

    /**
     * 
     * @see org.springframework.beans.factory.DisposableBean#destroy()
     */
    public void destroy() throws Exception {
        open = false;
        log.info("Requesting to close quota store...");
        transactionRunner.shutdown();
        try {
            transactionRunner.awaitTermination(30 * 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            log.error("Time out shutting down quota store write thread, trying to "
                    + "close the entity store as is.", ie);
        } finally {
            entityStore.close();
        }
        log.info("Quota store closed.");
    }

    private void configure(final File storeDirectory) throws InterruptedException {
        // todo: make config persistent? or just rely on je.properties (I guess so)
        PageStoreConfig config = new PageStoreConfig();
        EntityStoreBuilder builder = new EntityStoreBuilder(config);
        EntityStore entityStore = builder.buildEntityStore(storeDirectory, null);
        this.entityStore = entityStore;

        tileSetById = entityStore.getPrimaryIndex(String.class, TileSet.class);
        pageById = entityStore.getPrimaryIndex(Long.class, TilePage.class);
        pageStatsById = entityStore.getPrimaryIndex(Long.class, PageStats.class);
        usedQuotaById = entityStore.getPrimaryIndex(Integer.class, Quota.class);

        pageByKey = entityStore.getSecondaryIndex(pageById, String.class, "page_key");
        tileSetsByLayer = entityStore.getSecondaryIndex(tileSetById, String.class, "layer");
        pageStatsByLRU = entityStore.getSecondaryIndex(pageStatsById, Float.class, "LRU");
        pageStatsByLFU = entityStore.getSecondaryIndex(pageStatsById, Float.class, "LFU");
        usedQuotaByTileSetId = entityStore.getSecondaryIndex(usedQuotaById, String.class,
                "tileset_id");
        pageStatsByPageId = entityStore.getSecondaryIndex(pageStatsById, Long.class,
                "page_stats_by_page_id");

    }

    private class StartUpInitializer implements Callable<Void> {
        public Void call() throws Exception {
            final Transaction transaction = entityStore.getEnvironment().beginTransaction(null,
                    null);
            try {
                if (null == usedQuotaByTileSetId.get(transaction, GLOBAL_QUOTA_NAME,
                        LockMode.DEFAULT)) {
                    log.debug("First time run: creating global quota object");
                    // need a global TileSet cause the Quota->TileSet relationship is enforced
                    TileSet globalTileSet = new TileSet(GLOBAL_QUOTA_NAME);
                    tileSetById.put(transaction, globalTileSet);

                    Quota globalQuota = new Quota();
                    globalQuota.setTileSetId(GLOBAL_QUOTA_NAME);
                    usedQuotaById.put(transaction, globalQuota);
                    log.debug("created Global Quota");
                }

                final Set<String> layerNames = tilePageCalculator.getLayerNames();
                final Set<String> existingLayers = new GetLayerNames().call();

                final Set<String> layersToDelete = new HashSet<String>(existingLayers);
                layersToDelete.removeAll(layerNames);

                for (String layerName : layersToDelete) {
                    log.info("Deleting disk quota information for layer '" + layerName
                            + "' as it does not exist anymore...");
                    // do not call issue since we're already running on the transaction thread here
                    try {
                        new DeleteLayer(layerName).call(transaction);
                    } catch (Exception e) {
                        log.warn("Error deleting disk quota information for layer '" + layerName
                                + "'", e);
                    }
                }

                // add any missing tileset
                for (String layerName : layerNames) {
                    Set<TileSet> layerTileSets = tilePageCalculator.getTileSetsFor(layerName);
                    for (TileSet tset : layerTileSets) {
                        String id = tset.getId();
                        if (null == tileSetById.get(transaction, id, LockMode.DEFAULT)) {
                            log.debug("Creating TileSet for quota tracking: " + tset);
                            tileSetById.put(transaction, tset);
                            Quota tileSetUsedQuota = new Quota();
                            tileSetUsedQuota.setTileSetId(tset.getId());
                            usedQuotaById.put(transaction, tileSetUsedQuota);
                        }
                    }
                }
                transaction.commit();
            } catch (RuntimeException e) {
                transaction.abort();
                throw e;
            }
            return null;
        }
    }

    /**
     * Asynchronously issues the given {@code command} to the working transactional thread
     */
    private <E> Future<E> issue(final Callable<E> command) {
        if (!open) {
            throw new IllegalStateException("QuotaStore is closed.");
        }
        Future<E> future = transactionRunner.submit(command);
        return future;
    }

    /**
     * Synchronously issues the given {@code command} to the working transactional thread
     * 
     * @throws InterruptedException
     *             in case the calling thread was interrupted while waiting for the command to
     *             complete
     */
    private <E> E issueSync(final Callable<E> command) throws InterruptedException {
        Future<E> result = issue(command);
        try {
            return result.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            log.debug("Caught InterruptedException while waiting for command "
                    + command.getClass().getSimpleName());
            throw e;
        } catch (ExecutionException e) {
            log.warn(e);
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    private void deleteStaleLayersAndCreateMissingTileSets() throws InterruptedException {
        issueSync(new StartUpInitializer());
    }

    private class GetLayerNames implements Callable<Set<String>> {

        public Set<String> call() throws Exception {
            EntityCursor<String> layerNameCursor = tileSetsByLayer.keys(null, CursorConfig.DEFAULT);
            Set<String> names = new HashSet<String>();
            try {
                String name;
                while ((name = layerNameCursor.nextNoDup()) != null) {
                    if (!GLOBAL_QUOTA_NAME.equals(name)) {
                        names.add(name);
                    }
                }
            } finally {
                layerNameCursor.close();
            }
            return names;
        }

    }

    public Quota getGloballyUsedQuota() throws InterruptedException {
        return getUsedQuotaByTileSetId(GLOBAL_QUOTA_NAME);
    }

    public Quota getUsedQuotaByTileSetId(final String tileSetId) throws InterruptedException {
        Quota usedQuota = issueSync(new UsedQuotaByTileSetId(tileSetId));
        return usedQuota;
    }

    private final class UsedQuotaByTileSetId implements Callable<Quota> {
        private final String tileSetId;

        private UsedQuotaByTileSetId(String tileSetId) {
            this.tileSetId = tileSetId;
        }

        public Quota call() throws Exception {
            Quota quota = usedQuotaByTileSetId.get(null, tileSetId, LockMode.READ_COMMITTED);
            if (quota == null) {
                throw new IllegalArgumentException("Used quota for tileSet '" + tileSetId
                        + "' does not exist");
            }
            return quota;
        }
    }

    private class DeleteLayer implements Callable<Void> {

        private final String layerName;

        public DeleteLayer(String layerName) {
            this.layerName = layerName;
        }

        public Void call() throws Exception {
            Transaction transaction = entityStore.getEnvironment().beginTransaction(null, null);
            try {
                call(transaction);
                transaction.commit();
            } catch (RuntimeException e) {
                transaction.abort();
                throw e;
            }
            return null;
        }

        public void call(Transaction transaction) {

            EntityCursor<TileSet> tileSets = tileSetsByLayer.entities(transaction, layerName, true,
                    layerName, true, null);
            try {
                TileSet tileSet;
                Quota freed;
                Quota global;
                while (null != (tileSet = tileSets.next())) {
                    freed = usedQuotaByTileSetId
                            .get(transaction, tileSet.getId(), LockMode.DEFAULT);
                    global = usedQuotaByTileSetId.get(transaction, GLOBAL_QUOTA_NAME,
                            LockMode.DEFAULT);

                    tileSets.delete();
                    global.subtract(freed.getBytes());
                    usedQuotaById.put(transaction, global);
                }
            } finally {
                tileSets.close();
            }
        }

    }

    public void deleteLayer(final String layerName) {
        issue(new DeleteLayer(layerName));
    }

    /**
     * 
     * @param layerName
     * @return the used quota for the given layer, may need to create a new one before returning if
     *         no quota usage information for that layer already exists
     * @throws InterruptedException
     */
    public Quota getUsedQuotaByLayerName(final String layerName) throws InterruptedException {
        return issueSync(new UsedQuotaByLayerName(layerName));
    }

    private final class UsedQuotaByLayerName implements Callable<Quota> {
        private final String layerName;

        public UsedQuotaByLayerName(final String layerName) {
            this.layerName = layerName;
        }

        public Quota call() throws Exception {
            Quota aggregated = null;

            EntityCursor<TileSet> layerTileSetsIds;
            layerTileSetsIds = tileSetsByLayer.entities(null, layerName, true, layerName, true,
                    CursorConfig.DEFAULT);
            TileSet tileSet;
            try {
                Quota tileSetUsedQuota;
                while (null != (tileSet = layerTileSetsIds.next())) {
                    if (aggregated == null) {
                        aggregated = new Quota();
                    }
                    tileSetUsedQuota = new UsedQuotaByTileSetId(tileSet.getId()).call();
                    aggregated.add(tileSetUsedQuota);
                }
            } finally {
                layerTileSetsIds.close();
            }
            if (aggregated == null) {
                throw new IllegalArgumentException("No such layer: '" + layerName + "'");
            }

            return aggregated;
        }

    }

    public long[][] getTilesForPage(TilePage page) throws InterruptedException {
        TileSet tileSet = getTileSetById(page.getTileSetId());
        long[][] gridCoverage = tilePageCalculator.toGridCoverage(tileSet, page);
        return gridCoverage;
    }

    public Set<TileSet> getTileSets() {
        Map<String, TileSet> map = new HashMap<String, TileSet>(tileSetById.map());
        map.remove(GLOBAL_QUOTA_NAME);
        HashSet<TileSet> hashSet = new HashSet<TileSet>(map.values());
        return hashSet;
    }

    public TileSet getTileSetById(final String tileSetId) throws InterruptedException {
        return issueSync(new Callable<TileSet>() {

            public TileSet call() throws Exception {
                TileSet tileSet = tileSetById.get(tileSetId);
                if (tileSet == null) {
                    throw new IllegalArgumentException("TileSet does not exist: " + tileSetId);
                }
                return tileSet;
            }
        });
    }

    public TilePageCalculator getTilePageCalculator() {
        return tilePageCalculator;
    }

    /**
     * Adds the {@link TilePage#getNumPresentTilesInPage() number of tiles} present in each of the
     * argument pages
     * 
     * @param quotaDiff
     * 
     * @param tileCountDiffs
     * @throws InterruptedException
     */
    public void addToQuotaAndTileCounts(final TileSet tileSet, final Quota quotaDiff,
            final Collection<PageStatsPayload> tileCountDiffs) throws InterruptedException {
        issueSync(new AddToQuotaAndTileCounts(tileSet, quotaDiff, tileCountDiffs));
    }

    private class AddToQuotaAndTileCounts implements Callable<Void> {

        private final TileSet tileSet;

        private final Collection<PageStatsPayload> tileCountDiffs;

        private final Quota quotaDiff;

        public AddToQuotaAndTileCounts(final TileSet tileSet, Quota quotaDiff,
                final Collection<PageStatsPayload> tileCountDiffs) {
            this.tileSet = tileSet;
            this.quotaDiff = quotaDiff;
            this.tileCountDiffs = tileCountDiffs;
        }

        public Void call() throws Exception {
            final Transaction tx = entityStore.getEnvironment().beginTransaction(null, null);
            try {
                TileSet storedTileset = tileSetById.get(tx, tileSet.getId(), LockMode.DEFAULT);
                if (null == storedTileset) {
                    log.info("Can't add to tileset used quota. TileSet does not exist. Was it deleted? "
                            + tileSet);
                    tx.abort();
                    return null;
                }
                // increase the tileset used quota
                addToUsedQuota(tx, tileSet, quotaDiff);

                // and each page's fillFactor for lru/lfu expiration
                if (tileCountDiffs.size() > 0) {
                    TilePage page;
                    String pageKey;
                    for (PageStatsPayload payload : tileCountDiffs) {
                        page = payload.getPage();
                        pageKey = page.getKey();
                        PageStats pageStats;

                        TilePage storedPage = pageByKey.get(tx, pageKey, LockMode.DEFAULT);
                        if (null == storedPage) {
                            pageById.put(tx, page);
                            storedPage = page;
                            pageStats = new PageStats(storedPage.getId());
                            // pageStatsById.put(tx, pageStats);
                        } else {
                            pageStats = pageStatsByPageId.get(tx, storedPage.getId(), null);
                        }

                        final byte level = page.getZoomLevel();
                        final BigInteger tilesPerPage = tilePageCalculator.getTilesPerPage(tileSet,
                                level);
                        final int tilesAdded = payload.getNumTiles();

                        pageStats.addTiles(tilesAdded, tilesPerPage);
                        pageStatsById.putNoReturn(tx, pageStats);
                    }
                }
                tx.commit();
                return null;
            } catch (RuntimeException e) {
                e.printStackTrace();
                tx.abort();
                throw e;
            }
        }

        private void addToUsedQuota(final Transaction tx, final TileSet tileSet,
                final Quota quotaDiff) {
            Quota usedQuota = usedQuotaByTileSetId.get(tx, tileSet.getId(), LockMode.DEFAULT);
            Quota globalQuota = usedQuotaByTileSetId.get(tx, GLOBAL_QUOTA_NAME, LockMode.DEFAULT);

            usedQuota.add(quotaDiff);
            globalQuota.add(quotaDiff);

            usedQuotaById.putNoReturn(tx, usedQuota);
            usedQuotaById.putNoReturn(tx, globalQuota);
        }

    }

    /**
     * Asynchronously updates (or set if not exists) the
     * {@link PageStats#getFrequencyOfUsePerMinute()} and
     * {@link PageStats#getLastAccessTimeMinutes()} values for the stored versions of the page
     * statistics using {@link PageStats#addHits(long)}; these values are influenced by the
     * {@code PageStats}' {@link PageStats#getFillFactor() fillFactor}.
     * 
     * @param statsUpdates
     * @return
     */
    public Future<List<PageStats>> addHitsAndSetAccesTime(
            final Collection<PageStatsPayload> statsUpdates) {

        Assert.notNull(statsUpdates);

        return issue(new AddHitsAndSetAccesTime(statsUpdates));
    }

    /**
     * 
     * 
     */
    private class AddHitsAndSetAccesTime implements Callable<List<PageStats>> {

        private final Collection<PageStatsPayload> statsUpdates;

        public AddHitsAndSetAccesTime(Collection<PageStatsPayload> statsUpdates) {
            this.statsUpdates = statsUpdates;
        }

        public List<PageStats> call() throws Exception {
            List<PageStats> allStats = new ArrayList<PageStats>(statsUpdates.size());
            PageStats pageStats = null;
            final Transaction tx = entityStore.getEnvironment().beginTransaction(null, null);
            try {
                for (PageStatsPayload payload : statsUpdates) {
                    TilePage page = payload.getPage();
                    TileSet storedTileset = tileSetById.get(tx, page.getTileSetId(),
                            LockMode.DEFAULT);
                    if (null == storedTileset) {
                        log.info("Can't add usage stats. TileSet does not exist. Was it deleted? "
                                + page.getTileSetId());
                        continue;
                    }

                    TilePage storedPage = pageByKey.get(tx, page.getKey(), null);

                    if (storedPage == null) {
                        pageById.put(tx, page);
                        storedPage = page;
                        pageStats = new PageStats(storedPage.getId());
                    } else {
                        pageStats = pageStatsByPageId.get(tx, storedPage.getId(), null);
                    }

                    final int addedHits = payload.getNumHits();
                    final int lastAccessTimeMinutes = (int) (payload.getLastAccessTime() / 1000 / 60);
                    final int creationTimeMinutes = storedPage.getCreationTimeMinutes();
                    pageStats.addHitsAndAccessTime(addedHits, lastAccessTimeMinutes,
                            creationTimeMinutes);
                    pageStatsById.putNoReturn(tx, pageStats);
                    allStats.add(pageStats);
                }
                tx.commit();
                return allStats;
            } catch (RuntimeException e) {
                tx.abort();
                throw e;
            }
        }
    }

    /**
     * @param layerNames
     * @return
     * @throws InterruptedException
     */
    public TilePage getLeastFrequentlyUsedPage(final Set<String> layerNames)
            throws InterruptedException {

        SecondaryIndex<Float, Long, PageStats> expirationPolicyIndex = pageStatsByLFU;
        TilePage nextToExpire = issueSync(new FindPageToExpireByLayer(expirationPolicyIndex,
                layerNames));

        return nextToExpire;
    }

    /**
     * @param layerNames
     * @return
     * @throws InterruptedException
     */
    public TilePage getLeastRecentlyUsedPage(final Set<String> layerNames)
            throws InterruptedException {
        SecondaryIndex<Float, Long, PageStats> expirationPolicyIndex = pageStatsByLRU;
        TilePage nextToExpire = issueSync(new FindPageToExpireByLayer(expirationPolicyIndex,
                layerNames));

        return nextToExpire;
    }

    /**
     * @param expirationPolicyIndex
     * @param layerNames
     * @return
     */
    private class FindPageToExpireByLayer implements Callable<TilePage> {
        private final SecondaryIndex<Float, Long, PageStats> expirationPolicyIndex;

        private final Set<String> layerNames;

        public FindPageToExpireByLayer(
                SecondaryIndex<Float, Long, PageStats> expirationPolicyIndex, Set<String> layerNames) {
            this.expirationPolicyIndex = expirationPolicyIndex;
            this.layerNames = layerNames;
        }

        public TilePage call() throws Exception {

            // find out the tilesets for the requested layers
            final Set<String> tileSetIds = new HashSet<String>();
            for (String layerName : layerNames) {
                EntityCursor<TileSet> keys = tileSetsByLayer.entities(layerName, true, layerName,
                        true);
                try {
                    TileSet tileSet;
                    while ((tileSet = keys.next()) != null) {
                        tileSetIds.add(tileSet.getId());
                    }
                } finally {
                    keys.close();
                }
            }

            TilePage nextToExpire = null;
            // find out the LRU page that matches a requested tileset
            final EntityCursor<PageStats> pageStatsCursor = expirationPolicyIndex.entities();

            try {
                String tileSetId;
                long pageId;
                PageStats pageStats;
                while ((pageStats = pageStatsCursor.next()) != null) {
                    if (pageStats.getFillFactor() > 0) {
                        pageId = pageStats.getPageId();
                        TilePage tilePage = pageById.get(pageId);
                        tileSetId = tilePage.getTileSetId();
                        if (tileSetIds.contains(tileSetId)) {
                            nextToExpire = tilePage;
                            break;
                        }
                    }
                }
            } finally {
                pageStatsCursor.close();
            }

            return nextToExpire;
        }
    }

    public PageStats setTruncated(final TilePage tilePage) throws InterruptedException {
        return issueSync(new TruncatePage(tilePage));
    }

    private class TruncatePage implements Callable<PageStats> {
        private final TilePage tilePage;

        public TruncatePage(TilePage tilePage) {
            this.tilePage = tilePage;
        }

        public PageStats call() throws Exception {
            Transaction tx = entityStore.getEnvironment().beginTransaction(null, null);
            try {
                PageStats pageStats = pageStatsByPageId.get(tx, tilePage.getId(), null);
                if (pageStats != null) {
                    pageStats.setFillFactor(0f);
                    pageStatsById.putNoReturn(tx, pageStats);
                }
                tx.commit();
                return pageStats;
            } catch (Exception e) {
                tx.abort();
                throw e;
            }
        }
    }
}

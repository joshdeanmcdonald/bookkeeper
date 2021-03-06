/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.bookkeeper.bookie;

import static com.google.common.base.Charsets.UTF_8;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.bookkeeper.bookie.CheckpointProgress.CheckPoint;
import org.apache.bookkeeper.bookie.Journal.JournalScanner;
import org.apache.bookkeeper.bookie.LedgerDirsManager.LedgerDirsListener;
import org.apache.bookkeeper.bookie.LedgerDirsManager.NoWritableLedgerDirException;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.jmx.BKMBeanInfo;
import org.apache.bookkeeper.jmx.BKMBeanRegistry;
import org.apache.bookkeeper.meta.ActiveLedgerManager;
import org.apache.bookkeeper.meta.LedgerManagerFactory;
import org.apache.bookkeeper.net.BookieSocketAddress;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.WriteCallback;
import org.apache.bookkeeper.stats.BookkeeperServerStatsLogger;
import org.apache.bookkeeper.stats.BookkeeperServerStatsLogger.BookkeeperServerGauge;
import org.apache.bookkeeper.stats.Gauge;
import org.apache.bookkeeper.stats.ServerStatsProvider;
import org.apache.bookkeeper.stats.Stats;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.bookkeeper.util.DiskChecker;
import org.apache.bookkeeper.util.IOUtils;
import org.apache.bookkeeper.util.MathUtils;
import org.apache.bookkeeper.zookeeper.BoundExponentialBackoffRetryPolicy;
import org.apache.bookkeeper.zookeeper.ZooKeeperClient;
import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.SettableFuture;

import static org.apache.bookkeeper.util.BookKeeperConstants.*;

/**
 * Implements a bookie.
 *
 */
public class Bookie extends BookieCriticalThread {

    static Logger LOG = LoggerFactory.getLogger(Bookie.class);

    final File journalDirectory;
    final ServerConfiguration conf;

    SyncThread syncThread;
    LedgerManagerFactory activeLedgerManagerFactory;
    ActiveLedgerManager activeLedgerManager;
    LedgerStorage ledgerStorage;
    Journal journal;

    HandleFactory handles;

    static final long METAENTRY_ID_LEDGER_KEY = -0x1000;
    static final long METAENTRY_ID_FENCE_KEY  = -0x2000;

    // ZK registration path for this bookie
    private final String bookieRegistrationPath;
    private final String bookieReadonlyRegistrationPath;

    private final LedgerDirsManager ledgerDirsManager;
    private LedgerDirsManager indexDirsManager;

    // ZooKeeper client instance for the Bookie
    ZooKeeper zk;

    // Running flag
    private volatile boolean running = false;
    // Flag identify whether it is in shutting down progress
    private volatile boolean shuttingdown = false;

    private int exitCode = ExitCode.OK;

    // jmx related beans
    BookieBean jmxBookieBean;
    BKMBeanInfo jmxLedgerStorageBean;

    ConcurrentMap<Long, byte[]> masterKeyCache = new ConcurrentHashMap<Long, byte[]>();

    private String zkBookieRegPath;
    private String zkBookieReadOnlyPath;

    final private AtomicBoolean zkRegistered = new AtomicBoolean(false);
    final protected AtomicBoolean readOnly = new AtomicBoolean(false);
    // executor to manage the state changes for a bookie.
    final ExecutorService stateService = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat("BookieStateService-%d").build());

    public static class NoLedgerException extends IOException {
        private static final long serialVersionUID = 1L;
        private final long ledgerId;
        public NoLedgerException(long ledgerId) {
            super("Ledger " + ledgerId + " not found");
            this.ledgerId = ledgerId;
        }
        public long getLedgerId() {
            return ledgerId;
        }
    }
    public static class NoEntryException extends IOException {
        private static final long serialVersionUID = 1L;
        private final long ledgerId;
        private final long entryId;
        public NoEntryException(long ledgerId, long entryId) {
            this("Entry " + entryId + " not found in " + ledgerId, ledgerId, entryId);
        }

        public NoEntryException(String msg, long ledgerId, long entryId) {
            super(msg);
            this.ledgerId = ledgerId;
            this.entryId = entryId;
        }

        public long getLedger() {
            return ledgerId;
        }
        public long getEntry() {
            return entryId;
        }
    }

    // Write Callback do nothing
    static class NopWriteCallback implements WriteCallback {
        @Override
        public void writeComplete(int rc, long ledgerId, long entryId,
                                  BookieSocketAddress addr, Object ctx) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Finished writing entry {} @ ledger {} for {} : {}",
                          new Object[] { entryId, ledgerId, addr, rc });
            }
        }
    }

    static class FutureWriteCallback implements WriteCallback {

        SettableFuture<Boolean> result = SettableFuture.create();

        @Override
        public void writeComplete(int rc, long ledgerId, long entryId,
                                  BookieSocketAddress addr, Object ctx) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Finished writing entry {} @ ledger {} for {} : {}",
                          new Object[] { entryId, ledgerId, addr, rc });
            }

            result.set(0 == rc);
        }

        public SettableFuture<Boolean> getResult() {
            return result;
        }
    }

    /**
     * Sync Action Representing a CheckPoint or a Flush.
     */
    class SyncAction implements Callable<Boolean> {
        // sync ledger storage up to this checkpoint
        final CheckPoint checkpoint;
        // is the sync request a fully flush request
        final boolean isFlush;
        boolean completed = false;

        SyncAction(CheckPoint cp, boolean isFlush) {
            this.checkpoint = cp;
            this.isFlush = isFlush;
        }

        @Override
        public Boolean call() {
            boolean flushFailed = false;
            try {
                if (!isFlush) {
                    ledgerStorage.checkpoint(checkpoint);
                } else {
                    ledgerStorage.flush();
                }
            } catch (NoWritableLedgerDirException e) {
                LOG.error("No writeable ledger directories when flushing ledger storage : ", e);
                flushFailed = true;
                transitionToReadOnlyMode();
            } catch (IOException e) {
                LOG.error("Exception flushing ledger storage : ", e);
                flushFailed = true;
            }

            // if flush failed, we should not roll last mark, otherwise we would
            // have some ledgers are not flushed and their journal entries were lost
            if (!flushFailed) {
                try {
                    checkpoint.checkpointComplete(true);
                    completed = true;
                } catch (IOException e) {
                    LOG.error("Exception on completing checkpoint " + checkpoint + " : ", e);
                    transitionToReadOnlyMode();
                }
            }
            return completed;
        }
    }

    /**
     * Properly I should change SyncThread to an executor, which is a more natural way for
     * callback on sync requests.
     */
    static class SyncRequest extends FutureTask<Boolean> {

        final SyncAction action;

        public SyncRequest(SyncAction action) {
            super(action);
            this.action = action;
        }
    }

    @VisibleForTesting
    public SyncThread getSyncThread() {
        return syncThread;
    }

    /**
     * SyncThread is a background thread which flushes ledger index pages periodically.
     * Also it takes responsibility of garbage collecting journal files.
     *
     * <p>
     * Before flushing, SyncThread first records a log marker {journalId, journalPos} in memory,
     * which indicates entries before this log marker would be persisted to ledger files.
     * Then sync thread begins flushing ledger index pages to ledger index files, flush entry
     * logger to ensure all entries persisted to entry loggers for future reads.
     * </p>
     * <p>
     * After all data has been persisted to ledger index files and entry loggers, it is safe
     * to persist the log marker to disk. If bookie failed after persist log mark,
     * bookie is able to relay journal entries started from last log mark without losing
     * any entries.
     * </p>
     * <p>
     * Those journal files whose id are less than the log id in last log mark, could be
     * removed safely after persisting last log mark. We provide a setting to let user keeping
     * number of old journal files which may be used for manual recovery in critical disaster.
     * </p>
     */
    @VisibleForTesting
    public class SyncThread extends BookieCriticalThread implements CheckpointProgress {
        volatile boolean running = true;
        // flag to ensure sync thread will not be interrupted during flush
        final AtomicBoolean flushing = new AtomicBoolean(false);

        final LinkedBlockingQueue<SyncRequest> syncRequests =
                new LinkedBlockingQueue<SyncRequest>();

        private final Object suspensionLock = new Object();
        private boolean suspended = false;

        public SyncThread() {
            super("SyncThread");
        }

        private Future<Boolean> offerSyncRequest(SyncAction action) {
            SyncRequest request = new SyncRequest(action);
            syncRequests.offer(request);
            return request;
        }

        /**
         * flush data up to given logMark and roll log if success
         * @param checkpoint
         */
        @VisibleForTesting
        public void checkPoint(final CheckPoint checkpoint) {
            new SyncAction(checkpoint, !running).call();
        }

        /**
         * flush on current state.
         */
        Future<Boolean> flush() {
            CheckPoint cp = requestCheckpoint();
            LOG.info("Flush ledger storage at checkpoint {}.", cp);
            return offerSyncRequest(new SyncAction(cp, true));
        }

        /**
         * Suspend sync thread. (for testing)
         */
        @VisibleForTesting
        public void suspendSync() {
            synchronized(suspensionLock) {
                suspended = true;
            }
        }

        /**
         * Resume sync thread. (for testing)
         */
        @VisibleForTesting
        public void resumeSync() {
            synchronized(suspensionLock) {
                suspended = false;
                suspensionLock.notify();
            }
        }

        @Override
        public void run() {
            while(running) {
                SyncRequest request;
                try {
                    request = syncRequests.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    continue;
                }

                synchronized (suspensionLock) {
                    while (suspended) {
                        try {
                            suspensionLock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            continue;
                        }
                    }
                }

                // try to mark flushing flag to check if interrupted
                if (!flushing.compareAndSet(false, true)) {
                    // set flushing flag failed, means flushing is true now
                    // indicates another thread wants to interrupt sync thread to exit
                    break;
                }

                // run the sync request.
                request.run();

                flushing.set(false);
            }
        }

        // shutdown sync thread
        void shutdown() throws InterruptedException {
            LOG.info("Shutting down SyncThread.");
            // Wake up and finish sync thread
            running = false;
            flushing.compareAndSet(false, true);
            CheckPoint cp = requestCheckpoint();
            startCheckpoint(cp);
            this.join();

            LOG.info("Finished shutting down SyncThread.");
        }

        @Override
        public CheckPoint requestCheckpoint() {
            return journal.requestCheckpoint();
        }

        @Override
        public void startCheckpoint(CheckPoint checkpoint) {
            offerSyncRequest(new SyncAction(checkpoint, !running));
        }

    }

    public static void checkDirectoryStructure(File dir) throws IOException {
        if (!dir.exists()) {
            File parent = dir.getParentFile();
            File preV3versionFile = new File(dir.getParent(), VERSION_FILENAME);

            final AtomicBoolean oldDataExists = new AtomicBoolean(false);
            parent.list(new FilenameFilter() {
                    @Override
                    public boolean accept(File dir, String name) {
                        if (name.endsWith(".txn") || name.endsWith(".idx") || name.endsWith(".log")) {
                            oldDataExists.set(true);
                        }
                        return true;
                    }
                });
            if (preV3versionFile.exists() || oldDataExists.get()) {
                String err = "Directory layout version is less than 3, upgrade needed";
                LOG.error(err);
                throw new IOException(err);
            }
            if (!dir.mkdirs()) {
                String err = "Unable to create directory " + dir;
                LOG.error(err);
                throw new IOException(err);
            }
        }
    }

    /**
     * Check that the environment for the bookie is correct.
     * This means that the configuration has stayed the same as the
     * first run and the filesystem structure is up to date.
     */
    private void checkEnvironment(ZooKeeper zk) throws BookieException, IOException {
        List<File> allLedgerDirs = new ArrayList<File>(ledgerDirsManager.getAllLedgerDirs().size()
                                                     + indexDirsManager.getAllLedgerDirs().size());
        allLedgerDirs.addAll(ledgerDirsManager.getAllLedgerDirs());
        if (indexDirsManager != ledgerDirsManager) {
            allLedgerDirs.addAll(indexDirsManager.getAllLedgerDirs());
        }
        if (zk == null) { // exists only for testing, just make sure directories are correct
            checkDirectoryStructure(journalDirectory);
            for (File dir : allLedgerDirs) {
                    checkDirectoryStructure(dir);
            }
            return;
        }
        try {
            String instanceId = getInstanceId(zk);
            boolean newEnv = false;
            Cookie masterCookie = Cookie.generateCookie(conf);
            if (null != instanceId) {
                masterCookie.setInstanceId(instanceId);
            }
            try {
                Cookie zkCookie = Cookie.readFromZooKeeper(zk, conf);
                masterCookie.verify(zkCookie);
            } catch (KeeperException.NoNodeException nne) {
                newEnv = true;
            }
            List<File> missedCookieDirs = new ArrayList<File>();
            checkDirectoryStructure(journalDirectory);

            // try to read cookie from journal directory
            try {
                Cookie journalCookie = Cookie.readFromDirectory(journalDirectory);
                journalCookie.verify(masterCookie);
            } catch (FileNotFoundException fnf) {
                missedCookieDirs.add(journalDirectory);
            }
            for (File dir : allLedgerDirs) {
                checkDirectoryStructure(dir);
                try {
                    Cookie c = Cookie.readFromDirectory(dir);
                    c.verify(masterCookie);
                } catch (FileNotFoundException fnf) {
                    missedCookieDirs.add(dir);
                }
            }

            if (!newEnv && missedCookieDirs.size() > 0){
                LOG.error("Cookie exists in zookeeper, but not in all local directories. "
                        + " Directories missing cookie file are " + missedCookieDirs);
                throw new BookieException.InvalidCookieException();
            }
            if (newEnv) {
                if (missedCookieDirs.size() > 0) {
                    LOG.debug("Directories missing cookie file are {}", missedCookieDirs);
                    masterCookie.writeToDirectory(journalDirectory);
                    for (File dir : allLedgerDirs) {
                        masterCookie.writeToDirectory(dir);
                    }
                }
                masterCookie.writeToZooKeeper(zk, conf);
            }
        } catch (KeeperException ke) {
            LOG.error("Couldn't access cookie in zookeeper", ke);
            throw new BookieException.InvalidCookieException(ke);
        } catch (UnknownHostException uhe) {
            LOG.error("Couldn't check cookies, networking is broken", uhe);
            throw new BookieException.InvalidCookieException(uhe);
        } catch (IOException ioe) {
            LOG.error("Error accessing cookie on disks", ioe);
            throw new BookieException.InvalidCookieException(ioe);
        } catch (InterruptedException ie) {
            LOG.error("Thread interrupted while checking cookies, exiting", ie);
            throw new BookieException.InvalidCookieException(ie);
        }
    }

    /**
     * Return the configured address of the bookie.
     */
    public static BookieSocketAddress getBookieAddress(ServerConfiguration conf)
            throws UnknownHostException {
        return new BookieSocketAddress(InetAddress.getLocalHost()
                .getHostAddress(), conf.getBookiePort());
    }

    private String getInstanceId(ZooKeeper zk) throws KeeperException,
            InterruptedException {
        String instanceId = null;
        try {
            byte[] data = zk.getData(conf.getZkLedgersRootPath() + "/"
                    + INSTANCEID, false, null);
            instanceId = new String(data, UTF_8);
        } catch (KeeperException.NoNodeException e) {
            LOG.warn("INSTANCEID not exists in zookeeper. Not considering it for data verification");
        }
        return instanceId;
    }

    public LedgerDirsManager getLedgerDirsManager() {
        return ledgerDirsManager;
    }

    LedgerDirsManager getIndexDirsManager() {
        return indexDirsManager;
    }

    public static File getCurrentDirectory(File dir) {
        return new File(dir, CURRENT_DIR);
    }

    public static File[] getCurrentDirectories(File[] dirs) {
        File[] currentDirs = new File[dirs.length];
        for (int i = 0; i < dirs.length; i++) {
            currentDirs[i] = getCurrentDirectory(dirs[i]);
        }
        return currentDirs;
    }

    public Bookie(ServerConfiguration conf)
            throws IOException, KeeperException, InterruptedException, BookieException {
        super("Bookie-" + conf.getBookiePort());
        StatsLogger statsLogger = Stats.get().getStatsLogger("bookie");
        this.bookieRegistrationPath = conf.getZkAvailableBookiesPath() + "/";
        this.bookieReadonlyRegistrationPath =
            this.bookieRegistrationPath + READONLY;
        this.conf = conf;
        this.journalDirectory = getCurrentDirectory(conf.getJournalDir());
        this.ledgerDirsManager = new LedgerDirsManager(conf, conf.getLedgerDirs(),
                statsLogger.scope("ledger"));
        File[] idxDirs = conf.getIndexDirs();
        if (null == idxDirs) {
            this.indexDirsManager = this.ledgerDirsManager;
        } else {
            this.indexDirsManager = new LedgerDirsManager(conf, idxDirs,
                    statsLogger.scope("index"));
        }
        // 1 : up, 0 : readonly, -1 : unregistered
        ServerStatsProvider.getStatsLoggerInstance().registerGauge(BookkeeperServerGauge.SERVER_STATUS,
                new Gauge<Number>() {
                    @Override
                    public Number getDefaultValue() {
                        return 0;
                    }

                    @Override
                    public Number getSample() {
                        return zkRegistered.get() ? (readOnly.get() ? 0 : 1) : -1;
                    }
                });
    }

    private void checkDiskSpace() throws NoWritableLedgerDirException,
            DiskChecker.DiskErrorException {
        ledgerDirsManager.checkAllDirs();
        if (indexDirsManager != ledgerDirsManager) {
            indexDirsManager.checkAllDirs();
        }
    }

    private void reclaimDiskSpace() throws IOException {
        LOG.info("Reclaiming disk space from ledger storage.");
        ledgerStorage.reclaimDiskSpace();
        LOG.info("Reclaimed disk space from ledger storage.");
    }

    public int getExitCode() {
        return this.exitCode;
    }

    private String getMyId() throws UnknownHostException {
        return InetAddress.getLocalHost().getHostAddress() + ":"
                + conf.getBookiePort();
    }

    void readJournal() throws IOException, BookieException {
        long startTs = MathUtils.now();
        journal.replay(new JournalScanner() {
            @Override
            public void process(int journalVersion, long offset, ByteBuffer recBuff) throws IOException {
                long ledgerId = recBuff.getLong();
                long entryId = recBuff.getLong();
                try {
                    LOG.debug("Replay journal - ledger id : {}, entry id : {}.", ledgerId, entryId);
                    if (entryId == METAENTRY_ID_LEDGER_KEY) {
                        if (journalVersion >= JournalChannel.V3) {
                            int masterKeyLen = recBuff.getInt();
                            byte[] masterKey = new byte[masterKeyLen];

                            recBuff.get(masterKey);
                            masterKeyCache.put(ledgerId, masterKey);
                        } else {
                            throw new IOException("Invalid journal. Contains journalKey "
                                    + " but layout version (" + journalVersion
                                    + ") is too old to hold this");
                        }
                    } else if (entryId == METAENTRY_ID_FENCE_KEY) {
                        if (journalVersion >= JournalChannel.V4) {
                            byte[] key = masterKeyCache.get(ledgerId);
                            if (key == null) {
                                key = ledgerStorage.readMasterKey(ledgerId);
                            }
                            LedgerDescriptor handle = handles.getHandle(ledgerId, key);
                            handle.setFenced();
                        } else {
                            throw new IOException("Invalid journal. Contains fenceKey "
                                    + " but layout version (" + journalVersion
                                    + ") is too old to hold this");
                        }
                    } else {
                        byte[] key = masterKeyCache.get(ledgerId);
                        if (key == null) {
                            key = ledgerStorage.readMasterKey(ledgerId);
                        }
                        LedgerDescriptor handle = handles.getHandle(ledgerId, key);

                        recBuff.rewind();
                        handle.addEntry(recBuff);
                    }
                } catch (NoLedgerException nsle) {
                    LOG.debug("Skip replaying entries of ledger {} since it was deleted.", ledgerId);
                } catch (BookieException be) {
                    throw new IOException(be);
                }
            }
        });
        long elapsedTs = MathUtils.now() - startTs;
        LOG.info("Finished replaying journal in {} ms.", elapsedTs);
    }

    public void initialize() throws IOException, KeeperException, InterruptedException, BookieException {
        // instantiate zookeeper client to initialize ledger manager
        this.zk = instantiateZookeeperClient(conf);
        checkEnvironment(this.zk);

        activeLedgerManagerFactory = LedgerManagerFactory.newLedgerManagerFactory(conf, this.zk);
        activeLedgerManager = activeLedgerManagerFactory.newActiveLedgerManager();

        // instantiate the journal
        journal = new Journal(conf, ledgerDirsManager);

        // start sync thread after journal
        syncThread = new SyncThread();

        // Check the type of storage.
        if (conf.getSortedLedgerStorageEnabled()) {
            ledgerStorage = new SortedLedgerStorage(conf, activeLedgerManager,
                ledgerDirsManager, indexDirsManager, syncThread);
        } else {
            ledgerStorage = new InterleavedLedgerStorage(conf, activeLedgerManager,
                ledgerDirsManager, indexDirsManager, syncThread);
        }
        handles = new HandleFactoryImpl(ledgerStorage);

        // Initialise ledgerDirManager. This would look through all the
        // configured directories. When disk errors or all the ledger
        // directories are full, would throws exception and fail bookie startup.
        try {
            checkDiskSpace();
        } catch (NoWritableLedgerDirException nwlde) {
            LOG.info("Ledger storage is already full : ", nwlde);
            // if there is no writable ledger dir, we should try reclaimSpace
            reclaimDiskSpace();
            // check disk again if we still can't have enough room for replaying journal, fail it
            checkDiskSpace();
        }

        // ZK ephemeral node for this Bookie.
        String myID = getMyId();
        zkBookieRegPath = this.bookieRegistrationPath + myID;
        zkBookieReadOnlyPath = this.bookieReadonlyRegistrationPath + "/" + myID;
    }

    @Override
    synchronized public void start() {
        setDaemon(true);
        LOG.info("I'm starting a bookie with journal directory {}", journalDirectory.getName());
        //Start DiskChecker thread
        ledgerDirsManager.start();
        if (indexDirsManager != ledgerDirsManager) {
            indexDirsManager.start();
        }

        // start sync thread first, so during replaying journals, we could do checkpoint
        // which reduce the chance that we need to replay journals again if bookie restarted
        // again before finished journal replays.
        syncThread.start();

        // replay journals
        try {
            readJournal();
        } catch (IOException ioe) {
            LOG.error("Exception while replaying journals, shutting down", ioe);
            shutdown(ExitCode.BOOKIE_EXCEPTION);
            return;
        } catch (BookieException be) {
            LOG.error("Exception while replaying journals, shutting down", be);
            shutdown(ExitCode.BOOKIE_EXCEPTION);
            return;
        }

        // Do a fully flush after journal replay
        try {
            syncThread.flush().get();
        } catch (InterruptedException e) {
            LOG.warn("Interrupting the fully flush after replaying journals : ", e);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOG.error("Error on executing a fully flush after replaying journals.");
            shutdown(ExitCode.BOOKIE_EXCEPTION);
        }

        // start bookie thread
        super.start();

        // After successful bookie startup, register listener for disk
        // error/full notifications.
        ledgerDirsManager.addLedgerDirsListener(getLedgerDirsListener());
        if (indexDirsManager != ledgerDirsManager) {
            indexDirsManager.addLedgerDirsListener(getLedgerDirsListener());
        }

        ledgerStorage.start();

        // set running here.
        // since bookie server use running as a flag to tell bookie server whether it is alive
        // if setting it in bookie thread, the watcher might run before bookie thread.
        running = true;
        try {
            registerBookie(true).get();
        } catch (Exception ie) {
            LOG.error("Couldn't register bookie with zookeeper, shutting down : ", ie);
            shutdown(ExitCode.ZK_REG_FAIL);
        }
    }

    /*
     * Get the DiskFailure listener for the bookie
     */
    private LedgerDirsListener getLedgerDirsListener() {

        return new LedgerDirsListener() {

            @Override
            public void diskFull(File disk) {
                // Nothing needs to be handled here.
            }

            @Override
            public void diskAlmostFull(File disk) {
                // Nothing needs to be handled here.
            }

            @Override
            public void diskFailed(File disk) {
                // Shutdown the bookie on disk failure.
                triggerBookieShutdown(ExitCode.BOOKIE_EXCEPTION);
            }

            @Override
            public void allDisksFull() {
                // Transition to readOnly mode on all disks full
                transitionToReadOnlyMode();
            }

            @Override
            public void fatalError() {
                LOG.error("Fatal error reported by ledgerDirsManager");
                triggerBookieShutdown(ExitCode.BOOKIE_EXCEPTION);
            }

            @Override
            public void diskWritable(File disk) {
                // Transition to writable mode when a disk becomes writable again.
                transitionToWritableMode();
            }

            @Override
            public void diskJustWritable(File disk) {
                // Transition to writable mode when a disk becomes writable again.
                transitionToWritableMode();
            }
        };
    }

    /**
     * Register jmx with parent
     *
     * @param parent parent bk mbean info
     */
    public void registerJMX(BKMBeanInfo parent) {
        try {
            jmxBookieBean = new BookieBean(this);
            BKMBeanRegistry.getInstance().register(jmxBookieBean, parent);

            try {
                jmxLedgerStorageBean = this.ledgerStorage.getJMXBean();
                BKMBeanRegistry.getInstance().register(jmxLedgerStorageBean, jmxBookieBean);
            } catch (Exception e) {
                LOG.warn("Failed to register with JMX for ledger cache", e);
                jmxLedgerStorageBean = null;
            }
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            jmxBookieBean = null;
        }
    }

    /**
     * Unregister jmx
     */
    public void unregisterJMX() {
        try {
            if (jmxLedgerStorageBean != null) {
                BKMBeanRegistry.getInstance().unregister(jmxLedgerStorageBean);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister with JMX", e);
        }
        try {
            if (jmxBookieBean != null) {
                BKMBeanRegistry.getInstance().unregister(jmxBookieBean);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister with JMX", e);
        }
        jmxBookieBean = null;
        jmxLedgerStorageBean = null;
    }


    /**
     * Instantiate the ZooKeeper client for the Bookie.
     */
    protected ZooKeeper instantiateZookeeperClient(ServerConfiguration conf)
            throws IOException, InterruptedException, KeeperException {
        if (conf.getZkServers() == null) {
            LOG.warn("No ZK servers passed to Bookie constructor so BookKeeper clients won't know about this server!");
            return null;
        }
        // Create the ZooKeeper client instance
        return newZookeeper(conf);
    }

    /**
     * Check existence of <i>regPath</i> and wait it expired if possible
     *
     * @param regPath
     *          reg node path.
     * @return true if regPath exists, otherwise return false
     * @throws IOException if can't create reg path
     */
    protected boolean checkRegNodeAndWaitExpired(String regPath) throws IOException {
        final CountDownLatch prevNodeLatch = new CountDownLatch(1);
        Watcher zkPrevRegNodewatcher = new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                // Check for prev znode deletion. Connection expiration is
                // not handling, since bookie has logic to shutdown.
                if (EventType.NodeDeleted == event.getType()) {
                    prevNodeLatch.countDown();
                }
            }
        };
        try {
            Stat stat = zk.exists(regPath, zkPrevRegNodewatcher);
            if (null != stat) {
                // if the ephemeral owner isn't current zookeeper client
                // wait for it to be expired.
                if (stat.getEphemeralOwner() != zk.getSessionId()) {
                    LOG.info("Previous bookie registration znode: {} exists, so waiting zk sessiontimeout:"
                            + " {} ms for znode deletion", regPath, conf.getZkTimeout());
                    // waiting for the previous bookie reg znode deletion
                    if (!prevNodeLatch.await(conf.getZkTimeout(), TimeUnit.MILLISECONDS)) {
                        throw new NodeExistsException(regPath);
                    } else {
                        return false;
                    }
                }
                return true;
            } else {
                return false;
            }
        } catch (KeeperException ke) {
            LOG.error("ZK exception checking and wait ephemeral znode {} expired : ", regPath, ke);
            throw new IOException("ZK exception checking and wait ephemeral znode "
                    + regPath + " expired", ke);
        } catch (InterruptedException ie) {
            LOG.error("Interrupted checking and wait ephemeral znode {} expired : ", regPath, ie);
            throw new IOException("Interrupted checking and wait ephemeral znode "
                    + regPath + " expired", ie);
        }
    }

    /**
     * Register as an available bookie
     */
    protected Future<Void> registerBookie(final boolean throwException) {
        return stateService.submit(new Callable<Void>() {
            @Override
            public Void call() throws IOException {
                try {
                    doRegisterBookie();
                } catch (IOException ioe) {
                    if (throwException) {
                        throw ioe;
                    } else {
                        LOG.error("Couldn't register bookie with zookeeper, shutting down : ", ioe);
                        triggerBookieShutdown(ExitCode.ZK_REG_FAIL);
                    }
                }
                return (Void)null;
            }
        });
    }

    protected void doRegisterBookie() throws IOException {
        doRegisterBookie(readOnly.get() ? zkBookieReadOnlyPath : zkBookieRegPath);
    }

    private void doRegisterBookie(final String regPath) throws IOException {
        if (null == zk) {
            // zookeeper instance is null, means not register itself to zk
            return;
        }

        zkRegistered.set(false);

        // ZK ephemeral node for this Bookie.
        try{
            if (!checkRegNodeAndWaitExpired(regPath)) {
                // Create the ZK ephemeral node for this Bookie.
                zk.create(regPath, new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.EPHEMERAL);
                LOG.info("Registered myself in ZooKeeper at {}.", regPath);
            }
            zkRegistered.set(true);
        } catch (KeeperException ke) {
            LOG.error("ZK exception registering ephemeral Znode for Bookie!", ke);
            // Throw an IOException back up. This will cause the Bookie
            // constructor to error out. Alternatively, we could do a System
            // exit here as this is a fatal error.
            throw new IOException(ke);
        } catch (InterruptedException ie) {
            LOG.error("ZK exception registering ephemeral Znode for Bookie!", ie);
            // Throw an IOException back up. This will cause the Bookie
            // constructor to error out. Alternatively, we could do a System
            // exit here as this is a fatal error.
            throw new IOException(ie);
        }
    }

    /**
     * Transition the bookie from readOnly mode to writable
     */
    private Future<Void> transitionToWritableMode() {
        return stateService.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                doTransitionToWritableMode();
                return null;
            }
        });
    }

    @VisibleForTesting
    public void doTransitionToWritableMode() {
        if (shuttingdown) {
            return;
        }
        if (!readOnly.compareAndSet(true, false)) {
            return;
        }
        LOG.info("Transitioning Bookie to Writable mode and will serve read/write requests.");
        // change zookeeper state only when using zookeeper
        if (null == zk) {
            return;
        }
        try {
            doRegisterBookie(zkBookieRegPath);
            try {
                // Clear the current readonly node
                zk.delete(zkBookieReadOnlyPath, -1);
            } catch (KeeperException.NoNodeException nne) {
                LOG.warn("No readonly bookie registered node {} when transitioning to writable",
                        zkBookieReadOnlyPath, nne);
            }
        } catch (IOException e) {
            LOG.error("Error in transition to ReadOnly Mode."
                    + " Shutting down", e);
            triggerBookieShutdown(ExitCode.BOOKIE_EXCEPTION);
        } catch (KeeperException e) {
            LOG.error("Error in transition to ReadOnly Mode."
                    + " Shutting down", e);
            triggerBookieShutdown(ExitCode.BOOKIE_EXCEPTION);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted Exception while transitioning to ReadOnly Mode.");
        }
    }

    /**
     * Transition the bookie to readOnly mode
     */
    private Future<Void> transitionToReadOnlyMode() {
        return stateService.submit(new Callable<Void>() {
            @Override
            public Void call() {
                doTransitionToReadOnlyMode();
                return (Void)null;
            }
        });
    }

    @VisibleForTesting
    public void doTransitionToReadOnlyMode() {
        if (shuttingdown) {
            return;
        }
        if (!readOnly.compareAndSet(false, true)) {
            return;
        }
        if (!conf.isReadOnlyModeEnabled()) {
            LOG.warn("ReadOnly mode is not enabled. "
                    + "Can be enabled by configuring "
                    + "'readOnlyModeEnabled=true' in configuration."
                    + "Shutting down bookie");
            triggerBookieShutdown(ExitCode.BOOKIE_EXCEPTION);
            return;
        }
        LOG.info("Transitioning Bookie to ReadOnly mode,"
                + " and will serve only read requests from clients!");
        // change zookeeper state only when using zookeeper
        if (null == zk) {
            return;
        }
        try {
            if (null == zk.exists(this.bookieReadonlyRegistrationPath, false)) {
                try {
                    zk.create(this.bookieReadonlyRegistrationPath, new byte[0],
                              Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                } catch (NodeExistsException e) {
                    // this node is just now created by someone.
                }
            }
            doRegisterBookie(zkBookieReadOnlyPath);
            try {
                // Clear the current registered node
                zk.delete(zkBookieRegPath, -1);
            } catch (KeeperException.NoNodeException nne) {
                LOG.warn("No writable bookie registered node {} when transitioning to readonly",
                        zkBookieRegPath, nne);
            }
        } catch (IOException e) {
            LOG.error("Error in transition to ReadOnly Mode."
                    + " Shutting down", e);
            triggerBookieShutdown(ExitCode.BOOKIE_EXCEPTION);
            return;
        } catch (KeeperException e) {
            LOG.error("Error in transition to ReadOnly Mode."
                    + " Shutting down", e);
            triggerBookieShutdown(ExitCode.BOOKIE_EXCEPTION);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted Exception while transitioning to ReadOnly Mode.");
        }
    }

    /*
     * Check whether Bookie is writable
     */
    public boolean isReadOnly() {
        return readOnly.get();
    }

    /**
     * Create a new zookeeper client to zk cluster.
     *
     * <p>
     * Bookie Server just used zk client when syncing ledgers for garbage collection.
     * So when zk client is expired, it means this bookie server is not available in
     * bookie server list. The bookie client will be notified for its expiration. No
     * more bookie request will be sent to this server. So it's better to exit when zk
     * expired.
     * </p>
     * <p>
     * Since there are lots of bk operations cached in queue, so we wait for all the operations
     * are processed and quit. It is done by calling <b>shutdown</b>.
     * </p>
     *
     * @param conf server configuration
     *
     * @return zk client instance
     */
    private ZooKeeper newZookeeper(final ServerConfiguration conf)
            throws IOException, InterruptedException, KeeperException {
        Set<Watcher> watchers = new HashSet<Watcher>();
        watchers.add(new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (!running) {
                    // do nothing until first registration
                    return;
                }
                // Check for expired connection.
                if (event.getType().equals(EventType.None) &&
                    event.getState().equals(KeeperState.Expired)) {
                    zkRegistered.set(false);
                    // schedule a re-register operation
                    registerBookie(false);
                }
            }
        });
        return ZooKeeperClient.newBuilder()
                .connectString(conf.getZkServers())
                .sessionTimeoutMs(conf.getZkTimeout())
                .watchers(watchers)
                .operationRetryPolicy(new BoundExponentialBackoffRetryPolicy(conf.getZkRetryBackoffStartMs(),
                        conf.getZkRetryBackoffMaxMs(), Integer.MAX_VALUE))
                .requestRateLimit(conf.getZkRequestRateLimit())
                .build();
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void run() {
        // bookie thread wait for journal thread
        try {
            // start journal
            journal.start();
            // wait until journal quits
            journal.join();
            LOG.info("Journal thread quits.");
        } catch (InterruptedException ie) {
            LOG.warn("Interrupted on running journal thread : ", ie);
        }
        // if the journal thread quits due to shutting down, it is ok
        if (!shuttingdown) {
            // some error found in journal thread and it quits
            // following add operations to it would hang unit client timeout
            // so we should let bookie server exists
            LOG.error("Journal manager quits unexpectedly.");
            triggerBookieShutdown(ExitCode.BOOKIE_EXCEPTION);
        }
    }

    // Triggering the Bookie shutdown in its own thread,
    // because shutdown can be called from sync thread which would be
    // interrupted by shutdown call.
    void triggerBookieShutdown(final int exitCode) {
        LOG.info("Triggering shutdown of Bookie-{} with exitCode {}",
                 conf.getBookiePort(), exitCode);
        BookieThread shutdownThread = new BookieThread("BookieShutdownTrigger") {
            @Override
            public void run() {
                Bookie.this.shutdown(exitCode);
            }
        };
        shutdownThread.start();
        try {
            shutdownThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debug("InterruptedException while waiting for shutdown. Not a problem!!");
        }
    }

    // provided a public shutdown method for other caller
    // to shut down bookie gracefully
    public int shutdown() {
        return shutdown(ExitCode.OK);
    }

    // internal shutdown method to let shutdown bookie gracefully
    // when encountering exception
    synchronized int shutdown(int exitCode) {
        try {
            if (running) { // avoid shutdown twice
                // the exitCode only set when first shutdown usually due to exception found
                this.exitCode = exitCode;
                // mark bookie as in shutting down progress
                shuttingdown = true;



                // Shutdown Sync thread
                syncThread.shutdown();

                //Shutdown disk checker
                ledgerDirsManager.shutdown();
                if (indexDirsManager != ledgerDirsManager) {
                    indexDirsManager.shutdown();
                }

                // Shutdown journal
                journal.shutdown();
                this.join();

                // Shutdown the EntryLogger which has the GarbageCollector Thread running
                ledgerStorage.shutdown();

                // close Ledger Manager
                try {
                    activeLedgerManager.close();
                    activeLedgerManagerFactory.uninitialize();
                } catch (IOException ie) {
                    LOG.error("Failed to close active ledger manager : ", ie);
                }

                // Shutdown the ZK client
                if(zk != null) zk.close();

                // Shutdown State Service
                stateService.shutdown();

                // setting running to false here, so watch thread in bookie server know it only after bookie shut down
                running = false;
            }
        } catch (InterruptedException ie) {
            LOG.error("Interrupted during shutting down bookie : ", ie);
        }
        return this.exitCode;
    }

    /**
     * Retrieve the ledger descriptor for the ledger which entry should be added to.
     * The LedgerDescriptor returned from this method should be eventually freed with
     * #putHandle().
     *
     * @throws BookieException if masterKey does not match the master key of the ledger
     */
    private LedgerDescriptor getLedgerForEntry(ByteBuffer entry, byte[] masterKey)
            throws IOException, BookieException {
        long ledgerId = entry.getLong();
        LedgerDescriptor l = handles.getHandle(ledgerId, masterKey);
        if (!masterKeyCache.containsKey(ledgerId)) {
            // new handle, we should add the key to journal ensure we can rebuild
            ByteBuffer bb = ByteBuffer.allocate(8 + 8 + 4 + masterKey.length);
            bb.putLong(ledgerId);
            bb.putLong(METAENTRY_ID_LEDGER_KEY);
            bb.putInt(masterKey.length);
            bb.put(masterKey);
            bb.flip();

            if (null == masterKeyCache.putIfAbsent(ledgerId, masterKey)) {
                journal.logAddEntry(bb, new NopWriteCallback(), null);
            }
        }
        return l;
    }

    protected void addEntryByLedgerId(long ledgerId, ByteBuffer entry)
        throws IOException, BookieException {
        byte[] key = ledgerStorage.readMasterKey(ledgerId);
        LedgerDescriptor handle = handles.getHandle(ledgerId, key);
        handle.addEntry(entry);
    }

    /**
     * Add an entry to a ledger as specified by handle.
     */
    private void addEntryInternal(LedgerDescriptor handle, ByteBuffer entry, WriteCallback cb, Object ctx)
            throws IOException, BookieException {
        long ledgerId = handle.getLedgerId();
        entry.rewind();
        long entryId = handle.addEntry(entry);

        entry.rewind();
        LOG.trace("Adding {}@{}", entryId, ledgerId);
        journal.logAddEntry(entry, cb, ctx);
    }

    /**
     * Add entry to a ledger, even if the ledger has previous been fenced. This should only
     * happen in bookie recovery or ledger recovery cases, where entries are being replicates
     * so that they exist on a quorum of bookies. The corresponding client side call for this
     * is not exposed to users.
     */
    public void recoveryAddEntry(ByteBuffer entry, WriteCallback cb, Object ctx, byte[] masterKey)
            throws IOException, BookieException {
        long requestNanos = MathUtils.nowInNano();
        boolean success = false;
        try {
            LedgerDescriptor handle = getLedgerForEntry(entry, masterKey);
            synchronized (handle) {
                addEntryInternal(handle, entry, cb, ctx);
            }
            success = true;
        } catch (NoWritableLedgerDirException e) {
            transitionToReadOnlyMode();
            throw new IOException(e);
        } finally {
            long elapsedMicros = MathUtils.elapsedMicroSec(requestNanos);
            if (success) {
                ServerStatsProvider.getStatsLoggerInstance().getOpStatsLogger(
                        BookkeeperServerStatsLogger.BookkeeperServerOp.BOOKIE_RECOVERY_ADD_ENTRY)
                        .registerSuccessfulEvent(elapsedMicros);
            } else {
                ServerStatsProvider.getStatsLoggerInstance().getOpStatsLogger(
                        BookkeeperServerStatsLogger.BookkeeperServerOp.BOOKIE_RECOVERY_ADD_ENTRY)
                        .registerFailedEvent(elapsedMicros);
            }
        }
    }

    /**
     * Add entry to a ledger.
     * @throws BookieException.LedgerFencedException if the ledger is fenced
     */
    public void addEntry(ByteBuffer entry, WriteCallback cb, Object ctx, byte[] masterKey)
            throws IOException, BookieException {
        long requestNanos = MathUtils.nowInNano();
        boolean success = false;
        try {
            LedgerDescriptor handle = getLedgerForEntry(entry, masterKey);
            synchronized (handle) {
                if (handle.isFenced()) {
                    throw BookieException
                            .create(BookieException.Code.LedgerFencedException);
                }
                addEntryInternal(handle, entry, cb, ctx);
            }
            success = true;
        } catch (NoWritableLedgerDirException e) {
            transitionToReadOnlyMode();
            throw new IOException(e);
        } finally {
            long elapsedMicros = MathUtils.elapsedMicroSec(requestNanos);
            if (success) {
                ServerStatsProvider.getStatsLoggerInstance().getOpStatsLogger(
                        BookkeeperServerStatsLogger.BookkeeperServerOp.BOOKIE_ADD_ENTRY)
                        .registerSuccessfulEvent(elapsedMicros);
            } else {
                ServerStatsProvider.getStatsLoggerInstance().getOpStatsLogger(
                        BookkeeperServerStatsLogger.BookkeeperServerOp.BOOKIE_ADD_ENTRY)
                        .registerFailedEvent(elapsedMicros);
            }
        }
    }

    /**
     * Fences a ledger. From this point on, clients will be unable to
     * write to this ledger. Only recoveryAddEntry will be
     * able to add entries to the ledger.
     * This method is idempotent. Once a ledger is fenced, it can
     * never be unfenced. Fencing a fenced ledger has no effect.
     */
    public SettableFuture<Boolean> fenceLedger(long ledgerId, byte[] masterKey) throws IOException, BookieException {
        LedgerDescriptor handle = handles.getHandle(ledgerId, masterKey);
        boolean success;
        synchronized (handle) {
            success = handle.setFenced();
        }
        if (success) {
            // fenced first time, we should add the key to journal ensure we can rebuild
            ByteBuffer bb = ByteBuffer.allocate(8 + 8);
            bb.putLong(ledgerId);
            bb.putLong(METAENTRY_ID_FENCE_KEY);
            bb.flip();

            FutureWriteCallback fwc = new FutureWriteCallback();
            LOG.debug("record fenced state for ledger {} in journal.", ledgerId);
            journal.logAddEntry(bb, fwc, null);
            return fwc.getResult();
        } else {
            // already fenced
            SettableFuture<Boolean> successFuture = SettableFuture.create();
            successFuture.set(true);
            return successFuture;
        }
    }

    public ByteBuffer readEntry(long ledgerId, long entryId)
            throws IOException, NoLedgerException {
        long requestNanos = MathUtils.nowInNano();
        boolean success = false;
        try {
            LedgerDescriptor handle = handles.getReadOnlyHandle(ledgerId);
            LOG.trace("Reading {}@{}", entryId, ledgerId);
            ByteBuffer data = handle.readEntry(entryId);
            success = true;
            return data;
        } finally {
            long elapsedMicros = MathUtils.elapsedMicroSec(requestNanos);
            if (success) {
                ServerStatsProvider.getStatsLoggerInstance().getOpStatsLogger(
                        BookkeeperServerStatsLogger.BookkeeperServerOp.BOOKIE_READ_ENTRY)
                        .registerSuccessfulEvent(elapsedMicros);
            } else {
                ServerStatsProvider.getStatsLoggerInstance().getOpStatsLogger(
                        BookkeeperServerStatsLogger.BookkeeperServerOp.BOOKIE_READ_ENTRY)
                        .registerFailedEvent(elapsedMicros);
            }
        }
    }

    public long readLastAddConfirmed(long ledgerId) throws IOException {
        long requestNanos = MathUtils.nowInNano();
        boolean success = false;
        try {
            LedgerDescriptor handle = handles.getReadOnlyHandle(ledgerId);
            long lac = handle.getLastAddConfirmed();
            success = true;
            return lac;
        } finally {
            long elapsedMicros = MathUtils.elapsedMicroSec(requestNanos);
            if (success) {
                ServerStatsProvider.getStatsLoggerInstance().getOpStatsLogger(
                        BookkeeperServerStatsLogger.BookkeeperServerOp.BOOKIE_READ_LAST_CONFIRMED)
                        .registerSuccessfulEvent(elapsedMicros);
            } else {
                ServerStatsProvider.getStatsLoggerInstance().getOpStatsLogger(
                        BookkeeperServerStatsLogger.BookkeeperServerOp.BOOKIE_READ_LAST_CONFIRMED)
                        .registerFailedEvent(elapsedMicros);
            }
        }
    }

    public Observable waitForLastAddConfirmedUpdate(long ledgerId, long previoisLAC, Observer observer) throws IOException {
        LedgerDescriptor handle = handles.getReadOnlyHandle(ledgerId);
        return handle.waitForLastAddConfirmedUpdate(previoisLAC, observer);
    }

    // The rest of the code is test stuff
    static class CounterCallback implements WriteCallback {
        int count;

        @Override
        synchronized public void writeComplete(int rc, long l, long e, BookieSocketAddress addr, Object ctx) {
            count--;
            if (count == 0) {
                notifyAll();
            }
        }

        synchronized public void incCount() {
            count++;
        }

        synchronized public void waitZero() throws InterruptedException {
            while (count > 0) {
                wait();
            }
        }
    }

    /**
     * Format the bookie server data
     *
     * @param conf
     *            ServerConfiguration
     * @param isInteractive
     *            Whether format should ask prompt for confirmation if old data
     *            exists or not.
     * @param force
     *            If non interactive and force is true, then old data will be
     *            removed without confirm prompt.
     * @return Returns true if the format is success else returns false
     */
    public static boolean format(ServerConfiguration conf,
            boolean isInteractive, boolean force) {
        File journalDir = conf.getJournalDir();
        if (journalDir.exists() && journalDir.isDirectory()
                && journalDir.list().length != 0) {
            try {
                boolean confirm = false;
                if (!isInteractive) {
                    // If non interactive and force is set, then delete old
                    // data.
                    if (force) {
                        confirm = true;
                    } else {
                        confirm = false;
                    }
                } else {
                    confirm = IOUtils
                            .confirmPrompt("Are you sure to format Bookie data..?");
                }

                if (!confirm) {
                    LOG.error("Bookie format aborted!!");
                    return false;
                }
            } catch (IOException e) {
                LOG.error("Error during bookie format", e);
                return false;
            }
        }
        if (!cleanDir(journalDir)) {
            LOG.error("Formatting journal directory failed");
            return false;
        }

        File[] ledgerDirs = conf.getLedgerDirs();
        for (File dir : ledgerDirs) {
            if (!cleanDir(dir)) {
                LOG.error("Formatting ledger directory " + dir + " failed");
                return false;
            }
        }

        // Clean up index directories if they are separate from the ledger dirs
        File[] indexDirs = conf.getIndexDirs();
        if (null != indexDirs) {
            for (File dir : indexDirs) {
                if (!cleanDir(dir)) {
                    LOG.error("Formatting ledger directory " + dir + " failed");
                    return false;
                }
            }
        }

        LOG.info("Bookie format completed successfully");
        return true;
    }

    private static boolean cleanDir(File dir) {
        if (dir.exists()) {
            for (File child : dir.listFiles()) {
                boolean delete = FileUtils.deleteQuietly(child);
                if (!delete) {
                    LOG.error("Not able to delete " + child);
                    return false;
                }
            }
        } else if (!dir.mkdirs()) {
            LOG.error("Not able to create the directory " + dir);
            return false;
        }
        return true;
    }

    /**
     * @param args
     * @throws IOException
     * @throws InterruptedException
     */
    public static void main(String[] args)
            throws IOException, InterruptedException, BookieException, KeeperException {
        Bookie b = new Bookie(new ServerConfiguration());
        b.start();
        CounterCallback cb = new CounterCallback();
        long start = MathUtils.now();
        for (int i = 0; i < 100000; i++) {
            ByteBuffer buff = ByteBuffer.allocate(1024);
            buff.putLong(1);
            buff.putLong(i);
            buff.limit(1024);
            buff.position(0);
            cb.incCount();
            b.addEntry(buff, cb, null, new byte[0]);
        }
        cb.waitZero();
        long end = MathUtils.now();
        System.out.println("Took " + (end-start) + "ms");
    }
}

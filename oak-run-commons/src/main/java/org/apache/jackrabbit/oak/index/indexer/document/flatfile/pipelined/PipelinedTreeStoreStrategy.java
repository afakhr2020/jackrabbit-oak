/*
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
 */
package org.apache.jackrabbit.oak.index.indexer.document.flatfile.pipelined;

import com.mongodb.MongoClientURI;
import org.apache.commons.io.FileUtils;
import org.apache.jackrabbit.guava.common.base.Preconditions;
import org.apache.jackrabbit.guava.common.base.Stopwatch;
import org.apache.jackrabbit.guava.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.jackrabbit.oak.commons.Compression;
import org.apache.jackrabbit.oak.commons.IOUtils;
import org.apache.jackrabbit.oak.commons.concurrent.ExecutorCloser;
import org.apache.jackrabbit.oak.index.indexer.document.flatfile.NodeStateEntryWriter;
import org.apache.jackrabbit.oak.index.indexer.document.indexstore.IndexStoreSortStrategyBase;
import org.apache.jackrabbit.oak.index.indexer.document.tree.TreeStore;
import org.apache.jackrabbit.oak.plugins.document.DocumentNodeStore;
import org.apache.jackrabbit.oak.plugins.document.NodeDocument;
import org.apache.jackrabbit.oak.plugins.document.RevisionVector;
import org.apache.jackrabbit.oak.plugins.document.mongo.MongoDocumentStore;
import org.apache.jackrabbit.oak.plugins.index.FormattingUtils;
import org.apache.jackrabbit.oak.plugins.index.MetricsFormatter;
import org.apache.jackrabbit.oak.plugins.index.IndexingReporter;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.filter.PathFilter;
import org.apache.jackrabbit.oak.stats.StatisticsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.apache.jackrabbit.oak.plugins.index.IndexUtils.INDEXING_PHASE_LOGGER;

/**
 * Downloads the contents of the MongoDB repository dividing the tasks in a pipeline with the following stages:
 * <ul>
 * <li>Download - Downloads from Mongo all the documents in the node store.
 * <li>Transform - Converts Mongo documents to node state entries.
 * <li>Sort and save - Sorts the batch of node state entries and saves them to disk
 * <li>Merge sorted files - Merge the intermediate sorted files into a single file (the final FlatFileStore).
 * </ul>
 * <p>
 * <h2>Memory management</h2>
 * <p>
 * For efficiency, the intermediate sorted files should be as large as possible given the memory constraints.
 * This strategy accumulates the entries that will be stored in each of these files in memory until reaching a maximum
 * configurable size, at which point it sorts the data and writes it to a file. The data is accumulated in instances of
 * {@link NodeStateEntryBatch}. This class contains two data structures:
 * <ul>
 * <li>A {@link java.nio.ByteBuffer} for the binary representation of the entry, that is, the byte array that will be written to the file.
 * This buffer contains length-prefixed byte arrays, that is, each entry is {@code <size><data>}, where size is a 4 byte int.
 * <li>An array of {@link SortKey} instances, which contain the paths of each entry and are used to sort the entries. Each element
 * in this array also contains the position in the ByteBuffer of the serialized representation of the entry.
 * </ul>
 * This representation has several advantages:
 * <ul>
 * <li>It is compact, as a String object in the heap requires more memory than a length-prefixed byte array in the ByteBuffer.
 * <li>Predictable memory usage - the memory used by the {@link java.nio.ByteBuffer} is fixed and allocated at startup
 * (more on this later). The memory used by the array of {@link SortKey} is not bounded, but these objects are small,
 * as they contain little more than the path of the entry, and we can easily put limits on the maximum number of entries
 * kept in a buffer.
 * </ul>
 * <p>
 * The instances of {@link NodeStateEntryBatch} are created at launch time. We create {@code #transformThreads+1} buffers.
 * This way, except for some rare situations, each transform thread will have its own buffer where to write the entries
 * and there will be an extra buffer to be used by the Save-and-Sort thread, so that all the transform and sort threads
 * can operate concurrently.
 * <p>
 * These buffers are reused. Once the Save-and-Sort thread finishes processing a buffer, it clears it and sends it back
 * to the transform threads. For this, we use two queues, one with empty buffers, from where the transform threads take
 * their buffers when they need one, and another with full buffers, which are read by the Save-and-Sort thread.
 * <p>
 * Reusing the buffers reduces significantly the pressure on the garbage collector and ensures that we do not run out
 * of memory, as the largest blocks of memory are pre-allocated and reused.
 * <p>
 * The total amount of memory used by the buffers is a configurable parameter (env variable {@link #OAK_INDEXER_PIPELINED_WORKING_MEMORY_MB}).
 * This memory is divided in {@code numberOfBuffers + 1 </code>} regions, each of
 * {@code regionSize = PIPELINED_WORKING_MEMORY_MB/(#numberOfBuffers + 1)} size.
 * Each ByteBuffer is of {@code regionSize} big. The extra region is to account for the memory taken by the {@link SortKey}
 * entries. There is also a maximum limit on the number of entries, which is calculated based on regionSize
 * (we assume each {@link SortKey} entry requires 256 bytes).
 * <p>
 * The transform threads will stop filling a buffer and enqueue it for sorting and saving once either the byte buffer is
 * full or the number of entries in the buffer reaches the limit.
 * <p>
 *
 * <h2>Retrials on broken MongoDB connections</h2>
 */
public class PipelinedTreeStoreStrategy extends IndexStoreSortStrategyBase {
    public static final String OAK_INDEXER_PIPELINED_MONGO_DOC_BATCH_MAX_SIZE_MB = "oak.indexer.pipelined.mongoDocBatchMaxSizeMB";
    public static final int DEFAULT_OAK_INDEXER_PIPELINED_MONGO_DOC_BATCH_MAX_SIZE_MB = 4;
    public static final String OAK_INDEXER_PIPELINED_MONGO_DOC_BATCH_MAX_NUMBER_OF_DOCUMENTS = "oak.indexer.pipelined.mongoDocBatchMaxNumberOfDocuments";
    public static final int DEFAULT_OAK_INDEXER_PIPELINED_MONGO_DOC_BATCH_MAX_NUMBER_OF_DOCUMENTS = 10000;
    public static final String OAK_INDEXER_PIPELINED_MONGO_DOC_QUEUE_RESERVED_MEMORY_MB = "oak.indexer.pipelined.mongoDocQueueReservedMemoryMB";
    public static final int DEFAULT_OAK_INDEXER_PIPELINED_MONGO_DOC_QUEUE_RESERVED_MEMORY_MB = 128;
    public static final String OAK_INDEXER_PIPELINED_TRANSFORM_THREADS = "oak.indexer.pipelined.transformThreads";
    public static final int DEFAULT_OAK_INDEXER_PIPELINED_TRANSFORM_THREADS = 2;
    public static final String OAK_INDEXER_PIPELINED_WORKING_MEMORY_MB = "oak.indexer.pipelined.workingMemoryMB";
    // 0 means autodetect
    public static final int DEFAULT_OAK_INDEXER_PIPELINED_WORKING_MEMORY_MB = 0;
    // Between 1 and 100
    public static final String OAK_INDEXER_PIPELINED_SORT_BUFFER_MEMORY_PERCENTAGE = "oak.indexer.pipelined.sortBufferMemoryPercentage";
    public static final int DEFAULT_OAK_INDEXER_PIPELINED_SORT_BUFFER_MEMORY_PERCENTAGE = 25;

    private static final Logger LOG = LoggerFactory.getLogger(PipelinedTreeStoreStrategy.class);
    // A MongoDB document is at most 16MB, so the buffer that holds node state entries must be at least that big
    private static final int MIN_MONGO_DOC_QUEUE_RESERVED_MEMORY_MB = 16;
    private static final int MIN_AUTODETECT_WORKING_MEMORY_MB = 128;
    private static final int MIN_ENTRY_BATCH_BUFFER_SIZE_MB = 32;
    private static final int MAX_AUTODETECT_WORKING_MEMORY_MB = 4000;

    private static <T> void printStatistics(ArrayBlockingQueue<T[]> mongoDocQueue,
                                            ArrayBlockingQueue<NodeStateEntryBatch> emptyBuffersQueue,
                                            ArrayBlockingQueue<NodeStateEntryBatch> nonEmptyBuffersQueue,
                                            TransformStageStatistics transformStageStatistics,
                                            boolean printHistogramsAtInfo) {

        String queueSizeStats = MetricsFormatter.newBuilder()
                .add("mongoDocQueue", mongoDocQueue.size())
                .add("emptyBuffersQueue", emptyBuffersQueue.size())
                .add("nonEmptyBuffersQueue", nonEmptyBuffersQueue.size())
                .build();

        LOG.info("Queue sizes: {}", queueSizeStats);
        LOG.info("Transform stats: {}", transformStageStatistics.formatStats());
        prettyPrintTransformStatisticsHistograms(transformStageStatistics, printHistogramsAtInfo);
    }

    private static void prettyPrintTransformStatisticsHistograms(TransformStageStatistics transformStageStatistics, boolean printHistogramAtInfo) {
        if (printHistogramAtInfo) {
            LOG.info("Top hidden paths rejected: {}", transformStageStatistics.getHiddenPathsRejectedHistogram().prettyPrint());
            LOG.info("Top paths filtered: {}", transformStageStatistics.getFilteredPathsRejectedHistogram().prettyPrint());
            LOG.info("Top empty node state documents: {}", transformStageStatistics.getEmptyNodeStateHistogram().prettyPrint());
        } else {
            LOG.debug("Top hidden paths rejected: {}", transformStageStatistics.getHiddenPathsRejectedHistogram().prettyPrint());
            LOG.debug("Top paths filtered: {}", transformStageStatistics.getFilteredPathsRejectedHistogram().prettyPrint());
            LOG.debug("Top empty node state documents: {}", transformStageStatistics.getEmptyNodeStateHistogram().prettyPrint());
        }
    }

    private final MongoDocumentStore docStore;
    private final MongoClientURI mongoClientURI;
    private final DocumentNodeStore documentNodeStore;
    private final RevisionVector rootRevision;
    private final BlobStore blobStore;
    private final List<PathFilter> pathFilters;
    private final StatisticsProvider statisticsProvider;
    private final IndexingReporter indexingReporter;
    private final int numberOfTransformThreads;
    private final int mongoDocQueueSize;
    private final int mongoDocBatchMaxSizeMB;
    private final int mongoDocBatchMaxNumberOfDocuments;
    private final int nseBuffersCount;
    private final int nseBuffersSizeBytes;

    private long nodeStateEntriesExtracted;

    /**
     * @param mongoClientURI     URI of the Mongo cluster.
     * @param pathPredicate      Used by the transform stage to test if a node should be kept or discarded.
     * @param pathFilters        If non-empty, the download stage will use these filters to create a query that downloads
     *                           only the matching MongoDB documents.
     * @param statisticsProvider Used to collect statistics about the indexing process.
     * @param indexingReporter   Used to collect diagnostics, metrics and statistics and report them at the end of the indexing process.
     */
    public PipelinedTreeStoreStrategy(MongoClientURI mongoClientURI,
                             MongoDocumentStore documentStore,
                             DocumentNodeStore documentNodeStore,
                             RevisionVector rootRevision,
                             Set<String> preferredPathElements,
                             BlobStore blobStore,
                             File storeDir,
                             Compression algorithm,
                             Predicate<String> pathPredicate,
                             List<PathFilter> pathFilters,
                             String checkpoint,
                             StatisticsProvider statisticsProvider,
                             IndexingReporter indexingReporter) {
        super(storeDir, algorithm, pathPredicate, preferredPathElements, checkpoint);
        this.mongoClientURI = mongoClientURI;
        this.docStore = documentStore;
        this.documentNodeStore = documentNodeStore;
        this.rootRevision = rootRevision;
        this.blobStore = blobStore;
        this.pathFilters = pathFilters;
        this.statisticsProvider = statisticsProvider;
        this.indexingReporter = indexingReporter;
        Preconditions.checkState(documentStore.isReadOnly(), "Traverser can only be used with readOnly store");

        int mongoDocQueueReservedMemoryMB = ConfigHelper.getSystemPropertyAsInt(OAK_INDEXER_PIPELINED_MONGO_DOC_QUEUE_RESERVED_MEMORY_MB, DEFAULT_OAK_INDEXER_PIPELINED_MONGO_DOC_QUEUE_RESERVED_MEMORY_MB);
        Preconditions.checkArgument(mongoDocQueueReservedMemoryMB >= MIN_MONGO_DOC_QUEUE_RESERVED_MEMORY_MB,
                "Invalid value for property " + OAK_INDEXER_PIPELINED_MONGO_DOC_QUEUE_RESERVED_MEMORY_MB + ": " + mongoDocQueueReservedMemoryMB + ". Must be >= " + MIN_MONGO_DOC_QUEUE_RESERVED_MEMORY_MB);
        this.indexingReporter.addConfig(OAK_INDEXER_PIPELINED_MONGO_DOC_QUEUE_RESERVED_MEMORY_MB, String.valueOf(mongoDocQueueReservedMemoryMB));

        this.mongoDocBatchMaxSizeMB = ConfigHelper.getSystemPropertyAsInt(OAK_INDEXER_PIPELINED_MONGO_DOC_BATCH_MAX_SIZE_MB, DEFAULT_OAK_INDEXER_PIPELINED_MONGO_DOC_BATCH_MAX_SIZE_MB);
        Preconditions.checkArgument(mongoDocBatchMaxSizeMB > 0,
                "Invalid value for property " + OAK_INDEXER_PIPELINED_MONGO_DOC_BATCH_MAX_SIZE_MB + ": " + mongoDocBatchMaxSizeMB + ". Must be > 0");
        this.indexingReporter.addConfig(OAK_INDEXER_PIPELINED_MONGO_DOC_BATCH_MAX_SIZE_MB, String.valueOf(mongoDocBatchMaxSizeMB));

        this.mongoDocBatchMaxNumberOfDocuments = ConfigHelper.getSystemPropertyAsInt(OAK_INDEXER_PIPELINED_MONGO_DOC_BATCH_MAX_NUMBER_OF_DOCUMENTS, DEFAULT_OAK_INDEXER_PIPELINED_MONGO_DOC_BATCH_MAX_NUMBER_OF_DOCUMENTS);
        Preconditions.checkArgument(mongoDocBatchMaxNumberOfDocuments > 0,
                "Invalid value for property " + OAK_INDEXER_PIPELINED_MONGO_DOC_BATCH_MAX_NUMBER_OF_DOCUMENTS + ": " + mongoDocBatchMaxNumberOfDocuments + ". Must be > 0");
        this.indexingReporter.addConfig(OAK_INDEXER_PIPELINED_MONGO_DOC_BATCH_MAX_NUMBER_OF_DOCUMENTS, String.valueOf(mongoDocBatchMaxNumberOfDocuments));

        this.numberOfTransformThreads = ConfigHelper.getSystemPropertyAsInt(OAK_INDEXER_PIPELINED_TRANSFORM_THREADS, DEFAULT_OAK_INDEXER_PIPELINED_TRANSFORM_THREADS);
        Preconditions.checkArgument(numberOfTransformThreads > 0,
                "Invalid value for property " + OAK_INDEXER_PIPELINED_TRANSFORM_THREADS + ": " + numberOfTransformThreads + ". Must be > 0");
        this.indexingReporter.addConfig(OAK_INDEXER_PIPELINED_TRANSFORM_THREADS, String.valueOf(numberOfTransformThreads));

        int sortBufferMemoryPercentage = ConfigHelper.getSystemPropertyAsInt(OAK_INDEXER_PIPELINED_SORT_BUFFER_MEMORY_PERCENTAGE, DEFAULT_OAK_INDEXER_PIPELINED_SORT_BUFFER_MEMORY_PERCENTAGE);
        Preconditions.checkArgument(sortBufferMemoryPercentage > 0 && sortBufferMemoryPercentage <= 100,
                "Invalid value for property " + OAK_INDEXER_PIPELINED_SORT_BUFFER_MEMORY_PERCENTAGE + ": " + numberOfTransformThreads + ". Must be between 1 and 100");
        this.indexingReporter.addConfig(OAK_INDEXER_PIPELINED_SORT_BUFFER_MEMORY_PERCENTAGE, String.valueOf(sortBufferMemoryPercentage));

        // mongo-dump  <-> transform threads
        Preconditions.checkArgument(mongoDocQueueReservedMemoryMB >= 8 * mongoDocBatchMaxSizeMB,
                "Invalid values for properties " + OAK_INDEXER_PIPELINED_MONGO_DOC_QUEUE_RESERVED_MEMORY_MB + " and " + OAK_INDEXER_PIPELINED_MONGO_DOC_BATCH_MAX_SIZE_MB +
                        ": " + OAK_INDEXER_PIPELINED_MONGO_DOC_QUEUE_RESERVED_MEMORY_MB + " must be at least 8x " + OAK_INDEXER_PIPELINED_MONGO_DOC_BATCH_MAX_SIZE_MB +
                        ", but are " + mongoDocQueueReservedMemoryMB + " and " + mongoDocBatchMaxSizeMB + ", respectively"
        );
        this.mongoDocQueueSize = mongoDocQueueReservedMemoryMB / mongoDocBatchMaxSizeMB;

        // Derived values for transform <-> sort-save
        int nseWorkingMemoryMB = readNSEBuffersReservedMemory();
        this.nseBuffersCount = 1 + numberOfTransformThreads;
        long nseWorkingMemoryBytes = (long) nseWorkingMemoryMB * FileUtils.ONE_MB;
        // The working memory is divided in the following regions:
        // - #transforThreads   NSE Binary buffers
        // - x1                 Memory reserved for the array created by the sort-batch thread with the keys of the entries
        //                      in the batch that is being sorted
        long memoryReservedForSortKeysArray = estimateMaxSizeOfSortArray(nseWorkingMemoryBytes, nseBuffersCount, sortBufferMemoryPercentage);
        long memoryReservedForBuffers = nseWorkingMemoryBytes - memoryReservedForSortKeysArray;

        // A ByteBuffer can be at most Integer.MAX_VALUE bytes long
        this.nseBuffersSizeBytes = limitToIntegerRange(memoryReservedForBuffers / nseBuffersCount);

        if (nseBuffersSizeBytes < MIN_ENTRY_BATCH_BUFFER_SIZE_MB * FileUtils.ONE_MB) {
            throw new IllegalArgumentException("Entry batch buffer size too small: " + nseBuffersSizeBytes +
                    " bytes. Must be at least " + MIN_ENTRY_BATCH_BUFFER_SIZE_MB + " MB. " +
                    "To increase the size of the buffers, either increase the size of the working memory region " +
                    "(system property " + OAK_INDEXER_PIPELINED_WORKING_MEMORY_MB + ") or decrease the number of transform " +
                    "threads (" + OAK_INDEXER_PIPELINED_TRANSFORM_THREADS + ")");
        }

        LOG.info("MongoDocumentQueue: [ reservedMemory: {} MB, batchMaxSize: {} MB, queueSize: {} (reservedMemory/batchMaxSize) ]",
                mongoDocQueueReservedMemoryMB,
                mongoDocBatchMaxSizeMB,
                mongoDocQueueSize);
        LOG.info("NodeStateEntryBuffers: [ workingMemory: {} MB, numberOfBuffers: {}, bufferSize: {}, sortBufferReservedMemory: {} ]",
                nseWorkingMemoryMB,
                nseBuffersCount,
                IOUtils.humanReadableByteCountBin(nseBuffersSizeBytes),
                IOUtils.humanReadableByteCountBin(memoryReservedForSortKeysArray)
        );
    }

    static long estimateMaxSizeOfSortArray(long nseWorkingMemoryBytes, long nseBuffersCount, int sortBufferMemoryPercentage) {
        // We reserve a percentage of the size of a buffer for sorting. That is, we are assuming that for every line
        // in the sort buffer, the memory needed to store the path section of the line will not be more
        // than sortBufferMemoryPercentage of the total size of the line in average
        // Estimate memory needed by the sort keys array. We assume each entry requires 256 bytes.
        long approxNseBufferSize = limitToIntegerRange(nseWorkingMemoryBytes / nseBuffersCount);
        return approxNseBufferSize * sortBufferMemoryPercentage / 100;
    }

    private int readNSEBuffersReservedMemory() {
        int workingMemoryMB = ConfigHelper.getSystemPropertyAsInt(OAK_INDEXER_PIPELINED_WORKING_MEMORY_MB, DEFAULT_OAK_INDEXER_PIPELINED_WORKING_MEMORY_MB);
        Preconditions.checkArgument(workingMemoryMB >= 0,
                "Invalid value for property " + OAK_INDEXER_PIPELINED_WORKING_MEMORY_MB + ": " + workingMemoryMB + ". Must be >= 0");
        indexingReporter.addConfig(OAK_INDEXER_PIPELINED_WORKING_MEMORY_MB, workingMemoryMB);
        if (workingMemoryMB == 0) {
            return autodetectWorkingMemoryMB();
        } else {
            return workingMemoryMB;
        }
    }

    private int autodetectWorkingMemoryMB() {
        int maxHeapSizeMB = (int) (Runtime.getRuntime().maxMemory() / FileUtils.ONE_MB);
        int workingMemoryMB = maxHeapSizeMB - 2048;
        LOG.info("Auto detecting working memory. Maximum heap size: {} MB, selected working memory: {} MB", maxHeapSizeMB, workingMemoryMB);
        if (workingMemoryMB > MAX_AUTODETECT_WORKING_MEMORY_MB) {
            LOG.warn("Auto-detected value for working memory too high, setting to the maximum allowed for auto-detection: {} MB", MAX_AUTODETECT_WORKING_MEMORY_MB);
            return MAX_AUTODETECT_WORKING_MEMORY_MB;
        }
        if (workingMemoryMB < MIN_AUTODETECT_WORKING_MEMORY_MB) {
            LOG.warn("Auto-detected value for working memory too low, setting to the minimum allowed for auto-detection: {} MB", MIN_AUTODETECT_WORKING_MEMORY_MB);
            return MIN_AUTODETECT_WORKING_MEMORY_MB;
        }
        return workingMemoryMB;
    }

    private static int limitToIntegerRange(long bufferSizeBytes) {
        if (bufferSizeBytes > Integer.MAX_VALUE) {
            // Probably not necessary to subtract 16, just a safeguard to avoid boundary conditions.
            int truncatedBufferSize = Integer.MAX_VALUE - 16;
            LOG.warn("Computed buffer size too big: {}, exceeds Integer.MAX_VALUE. Truncating to: {}", bufferSizeBytes, truncatedBufferSize);
            return truncatedBufferSize;
        } else {
            return (int) bufferSizeBytes;
        }
    }

    @Override
    public File createSortedStoreFile() throws IOException {
        int numberOfThreads = 1 + numberOfTransformThreads + 1; // dump, transform, sort threads
        ExecutorService threadPool = Executors.newFixedThreadPool(numberOfThreads,
                new ThreadFactoryBuilder().setDaemon(true).build()
        );
        // This executor can wait for several tasks at the same time. We use this below to wait at the same time for
        // all the tasks, so that if one of them fails, we can abort the whole pipeline. Otherwise, if we wait on
        // Future instances, we can only wait on one of them, so that if any of the others fail, we have no easy way
        // to detect this failure.
        @SuppressWarnings("rawtypes")
        ExecutorCompletionService ecs = new ExecutorCompletionService<>(threadPool);
        File resultDir = getStoreDir();
        TreeStore treeStore = new TreeStore("dump", resultDir, null, 1);
        treeStore.getSession().init();
        try {
            // download -> transform thread.
            ArrayBlockingQueue<NodeDocument[]> mongoDocQueue = new ArrayBlockingQueue<>(mongoDocQueueSize);

            // transform <-> sort and save threads
            // Queue with empty buffers, used by the transform task
            ArrayBlockingQueue<NodeStateEntryBatch> emptyBatchesQueue = new ArrayBlockingQueue<>(nseBuffersCount);
            // Queue with buffers filled by the transform task, used by the sort and save task. +1 for the SENTINEL
            ArrayBlockingQueue<NodeStateEntryBatch> nonEmptyBatchesQueue = new ArrayBlockingQueue<>(nseBuffersCount + 1);

            TransformStageStatistics transformStageStatistics = new TransformStageStatistics();

            // Create empty buffers
            for (int i = 0; i < nseBuffersCount; i++) {
                // No limits on the number of entries, only on their total size. This might be revised later.
                emptyBatchesQueue.add(NodeStateEntryBatch.createNodeStateEntryBatch(nseBuffersSizeBytes, Integer.MAX_VALUE));
            }

            INDEXING_PHASE_LOGGER.info("[TASK:PIPELINED-DUMP:START] Starting to build TreeStore");
            Stopwatch start = Stopwatch.createStarted();

            @SuppressWarnings("unchecked")
            Future<PipelinedMongoDownloadTask.Result> downloadFuture = ecs.submit(new PipelinedMongoDownloadTask(
                    mongoClientURI,
                    docStore,
                    (int) (mongoDocBatchMaxSizeMB * FileUtils.ONE_MB),
                    mongoDocBatchMaxNumberOfDocuments,
                    mongoDocQueue,
                    pathFilters,
                    statisticsProvider,
                    indexingReporter
            ));

            ArrayList<Future<PipelinedTransformTask.Result>> transformFutures = new ArrayList<>(numberOfTransformThreads);
            for (int i = 0; i < numberOfTransformThreads; i++) {
                NodeStateEntryWriter entryWriter = new NodeStateEntryWriter(blobStore);
                @SuppressWarnings("unchecked")
                Future<PipelinedTransformTask.Result> future = ecs.submit(new PipelinedTransformTask(
                        docStore,
                        documentNodeStore,
                        rootRevision,
                        this.getPathPredicate(),
                        entryWriter,
                        mongoDocQueue,
                        emptyBatchesQueue,
                        nonEmptyBatchesQueue,
                        transformStageStatistics
                ));
                transformFutures.add(future);
            }

            @SuppressWarnings("unchecked")
            Future<PipelinedSortBatchTask.Result> sortBatchFuture = ecs.submit(new PipelinedTreeStoreTask(
                    treeStore,
                    emptyBatchesQueue,
                    nonEmptyBatchesQueue,
                    statisticsProvider,
                    indexingReporter
            ));

            try {
                LOG.info("Waiting for tasks to complete");
                int tasksFinished = 0;
                int transformTasksFinished = 0;
                boolean monitorQueues = true;
                while (tasksFinished < numberOfThreads) {
                    // Wait with a timeout to print statistics periodically
                    Future<?> completedTask = ecs.poll(30, TimeUnit.SECONDS);
                    if (completedTask == null) {
                        // Timeout waiting for a task to complete
                        if (monitorQueues) {
                            try {
                                printStatistics(mongoDocQueue, emptyBatchesQueue, nonEmptyBatchesQueue, transformStageStatistics, false);
                            } catch (Exception e) {
                                LOG.warn("Error while logging queue sizes", e);
                            }
                        }
                    } else {
                        try {
                            Object result = completedTask.get();
                            if (result instanceof PipelinedMongoDownloadTask.Result) {
                                PipelinedMongoDownloadTask.Result downloadResult = (PipelinedMongoDownloadTask.Result) result;
                                LOG.info("Download finished. Documents downloaded: {}", downloadResult.getDocumentsDownloaded());
                                downloadFuture = null;

                            } else if (result instanceof PipelinedTransformTask.Result) {
                                PipelinedTransformTask.Result transformResult = (PipelinedTransformTask.Result) result;
                                transformTasksFinished++;
                                nodeStateEntriesExtracted += transformResult.getEntryCount();
                                LOG.info("Transform task {} finished. Entries processed: {}",
                                        transformResult.getThreadId(), transformResult.getEntryCount());
                                if (transformTasksFinished == numberOfTransformThreads) {
                                    LOG.info("All transform tasks finished. Total entries processed: {}", nodeStateEntriesExtracted);
                                    // No need to keep monitoring the queues, the download and transform threads are done.
                                    monitorQueues = false;
                                    // Terminate the sort thread.
                                    nonEmptyBatchesQueue.put(PipelinedStrategy.SENTINEL_NSE_BUFFER);
                                    transformStageStatistics.publishStatistics(statisticsProvider, indexingReporter);
                                    transformFutures.clear();
                                }

                            } else if (result instanceof PipelinedSortBatchTask.Result) {
                                PipelinedSortBatchTask.Result sortTaskResult = (PipelinedSortBatchTask.Result) result;
                                LOG.info("Sort batch task finished. Entries processed: {}", sortTaskResult.getTotalEntries());
                                // The buffers between transform and merge sort tasks are no longer needed, so remove them
                                // from the queues so they can be garbage collected.
                                // These buffers can be very large, so this is important to avoid running out of memory in
                                // the merge-sort phase
                                if (!nonEmptyBatchesQueue.isEmpty()) {
                                    LOG.warn("emptyBatchesQueue is not empty. Size: {}", emptyBatchesQueue.size());
                                }
                                emptyBatchesQueue.clear();
                                printStatistics(mongoDocQueue, emptyBatchesQueue, nonEmptyBatchesQueue, transformStageStatistics, true);
                                sortBatchFuture = null;

                            } else {
                                throw new RuntimeException("Unknown result type: " + result);
                            }
                            tasksFinished++;
                        } catch (ExecutionException ex) {
                            throw new RuntimeException(ex.getCause());
                        } catch (Throwable ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                }
                long elapsedSeconds = start.elapsed(TimeUnit.SECONDS);
                INDEXING_PHASE_LOGGER.info("[TASK:PIPELINED-DUMP:END] Metrics: {}", MetricsFormatter.newBuilder()
                        .add("duration", FormattingUtils.formatToSeconds(elapsedSeconds))
                        .add("durationSeconds", elapsedSeconds)
                        .add("nodeStateEntriesExtracted", nodeStateEntriesExtracted)
                        .build());
                indexingReporter.addTiming("Build TreeStore (Dump+Merge)", FormattingUtils.formatToSeconds(elapsedSeconds));

                LOG.info("[INDEXING_REPORT:BUILD_TREE_STORE]\n{}", indexingReporter.generateReport());
            } catch (Throwable e) {
                INDEXING_PHASE_LOGGER.info("[TASK:PIPELINED-DUMP:FAIL] Metrics: {}, Error: {}",
                        MetricsFormatter.createMetricsWithDurationOnly(start), e.toString()
                );
                LOG.warn("Error dumping from MongoDB. Cancelling all tasks. Error: {}", e.toString());
                // Cancel in order
                cancelFuture(downloadFuture);
                for (Future<?> transformTask : transformFutures) {
                    cancelFuture(transformTask);
                }
                cancelFuture(sortBatchFuture);
                throw new RuntimeException(e);
            }
            treeStore.close();
            return resultDir;
        } finally {
            LOG.info("Shutting down build FFS thread pool");
            new ExecutorCloser(threadPool).close();
        }
    }

    private void cancelFuture(Future<?> future) {
        if (future != null) {
            LOG.info("Cancelling future: {}", future);
            future.cancel(true);
        }
    }

    @Override
    public long getEntryCount() {
        return nodeStateEntriesExtracted;
    }
}

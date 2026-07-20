/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.evmtool;

import static java.util.Objects.requireNonNull;

import org.hyperledger.besu.config.GenesisConfig;
import org.hyperledger.besu.controller.BesuController;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.ConsensusContext;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.chain.BlockchainStorage;
import org.hyperledger.besu.ethereum.chain.DefaultBlockchain;
import org.hyperledger.besu.ethereum.chain.GenesisState;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.chain.VariablesStorage;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions;
import org.hyperledger.besu.ethereum.mainnet.MainnetProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProvider;
import org.hyperledger.besu.ethereum.trie.forest.ForestWorldStateArchive;
import org.hyperledger.besu.ethereum.trie.forest.storage.ForestWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.forest.worldview.ForestMutableWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.BonsaiArchiveWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.BonsaiWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.BonsaiCachedMerkleTrieLoader;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.CodeCache;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.WorldStateQueryParams;
import org.hyperledger.besu.ethereum.trie.pathbased.common.worldview.PathBasedWorldState;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStoragePrefixedKeyBlockchainStorage;
import org.hyperledger.besu.ethereum.storage.keyvalue.VariablesKeyValueStorage;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier;
import org.hyperledger.besu.ethereum.storage.keyvalue.WorldStatePreimageKeyValueStorage;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.services.kvstore.InMemoryKeyValueStorage;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.TxValues;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.precompile.KZGPointEvalPrecompiledContract;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.services.BlockImportTracerProvider;
import org.hyperledger.besu.plugin.services.BesuConfiguration;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBMetricsFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBKeyValueStorageFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.BaseVersionedStorageFormat;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.DatabaseMetadata;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.RocksDBCLIOptions;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.VersionedStorageFormat;
import org.hyperledger.besu.ethereum.worldstate.ImmutableDataStorageConfiguration;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;
import org.hyperledger.besu.services.BesuConfigurationImpl;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Hash;

import org.hyperledger.besu.ethereum.worldstate.WorldStatePreimageStorage;
import org.hyperledger.besu.ethereum.core.MutableWorldState;

import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Re-executes blocks from a source Besu database into an existing replay Besu database.
 *
 * <p>{@code --data-path} supplies canonical block bodies (read-only). {@code --replay-data-path}
 * must point at an existing Besu data directory whose world state is the starting snapshot; replay
 * begins at that directory's chain head and advances the replay world state as blocks are
 * processed. BONSAI and FOREST replay databases are supported (format is read from database
 * metadata).
 */
public final class ReplayTransactionsFromDb {

  private ReplayTransactionsFromDb() {}

  public static void main(final String[] args) throws IOException {
    final Map<String, String> cli = parseArgs(args);

    // Mainnet blob/KZG precompile and block-body validation require the CKZG4844 native library.
    final Path kzgTrustedSetupFile =
        Optional.ofNullable(cli.get("--kzg-trusted-setup")).map(Path::of).orElse(null);
    if (kzgTrustedSetupFile != null) {
      KZGPointEvalPrecompiledContract.init(kzgTrustedSetupFile);
    } else {
      KZGPointEvalPrecompiledContract.init();
    }

    final Path dataPath =
        Path.of(requireNonNull(cli.get("--data-path"), "--data-path is required")).toAbsolutePath();
    final Path blockCsvDir =
        Path.of(requireNonNull(cli.get("--block-csv-dir"), "--block-csv-dir is required"))
            .toAbsolutePath();
    final Path replayDataPath =
        Path.of(requireNonNull(cli.get("--replay-data-path"), "--replay-data-path is required"))
            .toAbsolutePath();

    final long toBlockOption = parseLongOrDefault(cli.get("--to-block"), -1L);
    final long toBlock;

    final Path sourceStoragePath = dataPath.resolve(BesuController.DATABASE_PATH);
    final Path replayStoragePath = replayDataPath.resolve(BesuController.DATABASE_PATH);
    final Path progressFile = replayDataPath.resolve("replay-progress.properties");
    final boolean resume = Boolean.parseBoolean(cli.getOrDefault("--resume", "true"));

    if (!Files.exists(replayStoragePath)) {
      throw new IllegalStateException(
          "Replay data path must contain an existing Besu database at " + replayStoragePath);
    }

    final DataStorageConfiguration sourceStorageConfiguration =
        dataStorageConfigurationFrom(DatabaseMetadata.lookUpFrom(dataPath));
    final DataStorageConfiguration replayStorageConfiguration =
        dataStorageConfigurationFrom(DatabaseMetadata.lookUpFrom(replayDataPath));

    // 1. Build the protocol schedule (mainnet genesis is used only to anchor the blockchain view).
    final GenesisConfig genesisConfig = GenesisConfig.mainnet();
    final NoOpMetricsSystem metricsSystem = new NoOpMetricsSystem();
    final ProtocolSchedule protocolSchedule =
        MainnetProtocolSchedule.fromConfig(
            genesisConfig.getConfigOptions(),
            /* isRevertReasonEnabled= */ Optional.empty(),
            Optional.of(EvmConfiguration.DEFAULT),
            MiningConfiguration.newDefault(),
            new BadBlockManager(),
            /* isParallelTxProcessingEnabled= */ false,
            BalConfiguration.DEFAULT,
            metricsSystem);

    final CodeCache codeCache = new CodeCache();
    final GenesisState genesisState = GenesisState.fromConfig(genesisConfig, protocolSchedule, codeCache);

    // 2. Open the source DB to read canonical blocks (read-only usage).
    final BesuConfiguration sourceBesuConfiguration =
        new BesuConfigurationImpl().init(dataPath, sourceStoragePath, sourceStorageConfiguration);

    final RocksDBKeyValueStorageFactory sourceRocksDbFactory =
        new RocksDBKeyValueStorageFactory(
            RocksDBCLIOptions.create()::toDomainObject,
            List.of(KeyValueSegmentIdentifier.values()),
            RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS);

    // Separate factory for the replay DB. A single RocksDBKeyValueStorageFactory instance can only
    // open one column-family layout, so source and replay must use different factories.
    final RocksDBKeyValueStorageFactory replayRocksDbFactory =
        new RocksDBKeyValueStorageFactory(
            RocksDBCLIOptions.create()::toDomainObject,
            List.of(KeyValueSegmentIdentifier.values()),
            RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS);

    final KeyValueStorage sourceBlockchainKv;
    final KeyValueStorage sourceVariablesKv;
    KeyValueStorage replayWorldStateKv = null;
    KeyValueStorage replayPreimageKv = null;
    KeyValueStorageProvider replayStorageProvider = null;
    try {
      sourceBlockchainKv =
          sourceRocksDbFactory.create(
              KeyValueSegmentIdentifier.BLOCKCHAIN, sourceBesuConfiguration, metricsSystem);
      sourceVariablesKv =
          sourceRocksDbFactory.create(
              KeyValueSegmentIdentifier.VARIABLES, sourceBesuConfiguration, metricsSystem);
    } catch (final Exception e) {
      throw new RuntimeException("Failed to open source RocksDB storages under: " + sourceStoragePath, e);
    }

    final BesuConfiguration replayBesuConfiguration =
        new BesuConfigurationImpl().init(replayDataPath, replayStoragePath, replayStorageConfiguration);

    try {
      final var sourceVariablesStorage = new VariablesKeyValueStorage(sourceVariablesKv);
      final BlockchainStorage sourceBlockchainStorage =
          new KeyValueStoragePrefixedKeyBlockchainStorage(
              sourceBlockchainKv,
              sourceVariablesStorage,
              new MainnetBlockHeaderFunctions(),
              sourceStorageConfiguration.getReceiptCompactionEnabled());

      final MutableBlockchain sourceBlockchain =
          DefaultBlockchain.createMutable(
              genesisState.getBlock(),
              sourceBlockchainStorage,
              new NoOpMetricsSystem(),
              /* reorgLoggingThreshold= */ 0);

      final long sourceChainHeadBlockNumber = sourceBlockchain.getChainHeadBlockNumber();
      toBlock = toBlockOption < 0 ? sourceChainHeadBlockNumber : toBlockOption;

      final MutableBlockchain replayBlockchain;
      final BlockHeader replayHeadHeader;
      if (replayStorageConfiguration.getDataStorageFormat().isBonsaiFormat()) {
        replayStorageProvider =
            new KeyValueStorageProvider(
                segments ->
                    replayRocksDbFactory.create(segments, replayBesuConfiguration, metricsSystem),
                new InMemoryKeyValueStorage(),
                metricsSystem);
        replayBlockchain =
            openReplayBlockchain(
                replayStorageProvider,
                replayStorageConfiguration,
                protocolSchedule,
                genesisState,
                metricsSystem);
        replayHeadHeader = replayBlockchain.getChainHeadHeader();
      } else {
        replayBlockchain = null;
        replayHeadHeader =
            readReplayChainHeadHeader(
                replayBesuConfiguration, replayRocksDbFactory, genesisState, metricsSystem);
      }
      final long replayHeadBlockNumber = replayHeadHeader.getNumber();
      final Hash replayHeadStateRoot = replayHeadHeader.getStateRoot();

      if (replayHeadBlockNumber > sourceChainHeadBlockNumber) {
        throw new IllegalStateException(
            "Replay chain head ("
                + replayHeadBlockNumber
                + ") is ahead of source chain head ("
                + sourceChainHeadBlockNumber
                + ")");
      }

      final Optional<ReplayProgress> maybeProgress = resume ? readProgress(progressFile) : Optional.empty();

      final long checkpointBlockNumber;
      final long currentFromBlock;
      if (maybeProgress.isPresent()) {
        final ReplayProgress progress = maybeProgress.get();
        checkpointBlockNumber = progress.lastProcessedBlockNumber;
        currentFromBlock = progress.lastProcessedBlockNumber + 1;
      } else {
        checkpointBlockNumber = replayHeadBlockNumber;
        currentFromBlock = replayHeadBlockNumber + 1;
      }

      if (currentFromBlock > toBlock) {
        throw new IllegalStateException(
            "Nothing to replay: next block "
                + currentFromBlock
                + " is after target to-block "
                + toBlock);
      }

      final BlockHeader checkpointHeader =
          sourceBlockchain
              .getBlockByNumber(checkpointBlockNumber)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Missing source block for replay checkpoint " + checkpointBlockNumber))
              .getHeader();
      final Hash initialStateRoot = checkpointHeader.getStateRoot();
      final Hash initialStateBlockHash = checkpointHeader.getHash();

      if (maybeProgress.isPresent()) {
        final Hash progressStateRoot =
            Hash.fromHexString(maybeProgress.get().lastProcessedStateRootHex);
        if (!progressStateRoot.equals(initialStateRoot)) {
          throw new IllegalStateException(
              "Replay progress state root "
                  + progressStateRoot
                  + " does not match source checkpoint state root "
                  + initialStateRoot
                  + " at block "
                  + checkpointBlockNumber);
        }
      }

      final WorldStateArchive worldStateArchive;
      final MutableWorldState mutableWorldState;
      if (replayStorageConfiguration.getDataStorageFormat().isBonsaiFormat()) {
        final WorldStateStorageCoordinator replayWorldStateCoordinator =
            replayStorageProvider.createWorldStateStorageCoordinator(replayStorageConfiguration);
        final BonsaiWorldStateKeyValueStorage bonsaiWorldStateStorage =
            replayWorldStateCoordinator.getStrategy(BonsaiWorldStateKeyValueStorage.class);
        final BonsaiCachedMerkleTrieLoader bonsaiCachedMerkleTrieLoader =
            new BonsaiCachedMerkleTrieLoader(metricsSystem);

        worldStateArchive =
            replayStorageConfiguration.getDataStorageFormat() == DataStorageFormat.X_BONSAI_ARCHIVE
                ? new BonsaiArchiveWorldStateProvider(
                    bonsaiWorldStateStorage,
                    replayBlockchain,
                    replayStorageConfiguration.getPathBasedExtraStorageConfiguration(),
                    bonsaiCachedMerkleTrieLoader,
                    null,
                    EvmConfiguration.DEFAULT,
                    () -> (maybeAccount, location) -> {},
                    codeCache)
                : new BonsaiWorldStateProvider(
                    bonsaiWorldStateStorage,
                    replayBlockchain,
                    replayStorageConfiguration.getPathBasedExtraStorageConfiguration(),
                    bonsaiCachedMerkleTrieLoader,
                    null,
                    EvmConfiguration.DEFAULT,
                    () -> (maybeAccount, location) -> {},
                    codeCache);

        if (!worldStateArchive.isWorldStateAvailable(initialStateRoot, initialStateBlockHash)) {
          throw new IllegalStateException(
              "Replay world state is not available for state root "
                  + initialStateRoot
                  + " at block hash "
                  + initialStateBlockHash);
        }

        mutableWorldState =
            loadBonsaiCheckpointWorldState(
                worldStateArchive,
                checkpointHeader,
                initialStateRoot,
                initialStateBlockHash,
                checkpointBlockNumber,
                replayHeadBlockNumber);
      } else if (replayStorageConfiguration.getDataStorageFormat() == DataStorageFormat.FOREST) {
        replayWorldStateKv =
            replayRocksDbFactory.create(
                KeyValueSegmentIdentifier.WORLD_STATE, replayBesuConfiguration, metricsSystem);
        replayPreimageKv =
            replayRocksDbFactory.create(
                KeyValueSegmentIdentifier.PRUNING_STATE, replayBesuConfiguration, metricsSystem);

        final ForestWorldStateKeyValueStorage replayForestWsStorage =
            new ForestWorldStateKeyValueStorage(replayWorldStateKv);
        final WorldStateStorageCoordinator worldStateStorageCoordinator =
            new WorldStateStorageCoordinator(replayForestWsStorage);
        final WorldStatePreimageStorage preimageStorage =
            new WorldStatePreimageKeyValueStorage(replayPreimageKv);

        worldStateArchive =
            new ForestWorldStateArchive(
                worldStateStorageCoordinator, preimageStorage, EvmConfiguration.DEFAULT);

        if (!worldStateStorageCoordinator.isWorldStateAvailable(
            Bytes32.wrap(initialStateRoot.getBytes()), initialStateBlockHash)) {
          throw new IllegalStateException(
              "Replay world state is not available for state root "
                  + initialStateRoot
                  + " at block hash "
                  + initialStateBlockHash);
        }

        mutableWorldState =
            new ForestMutableWorldState(
                Bytes32.wrap(initialStateRoot.getBytes()),
                replayForestWsStorage,
                preimageStorage,
                EvmConfiguration.DEFAULT);
      } else {
        throw new IllegalStateException(
            "Unsupported replay data storage format: "
                + replayStorageConfiguration.getDataStorageFormat());
      }

      final ConsensusContext consensusContext = new SimpleConsensusContext();

      final var opcodeCollectorProvider = new OpcodeCollectorBlockImportTracerProvider();
      final var serviceManager = new ServiceManager.SimpleServiceManager();
      serviceManager.addService(BlockImportTracerProvider.class, opcodeCollectorProvider);
      final ProtocolContext protocolContext =
          new ProtocolContext.Builder()
              .withBlockchain(sourceBlockchain)
              .withWorldStateArchive(worldStateArchive)
              .withConsensusContext(consensusContext)
              .withServiceManager(serviceManager)
              .build();

      // 4. Sequentially process blocks through the protocol's block processor.
      System.out.println(
          "Re-executing source blocks from "
              + currentFromBlock
              + " to "
              + toBlock
              + " (inclusive). "
              + (maybeProgress.isPresent()
                  ? "Resuming from replay progress."
                  : "Starting from replay chain head "
                      + replayHeadBlockNumber
                      + " (state root "
                      + replayHeadStateRoot
                      + ")."));

      final ReplayStepTimings intervalTimings = new ReplayStepTimings();
      long lastProcessedBlockNumber = currentFromBlock - 1;

      final BlockingQueue<PrefetchedBlock> prefetchQueue = new ArrayBlockingQueue<>(3);
      final AtomicBoolean stopPrefetch = new AtomicBoolean(false);
      final AtomicReference<Throwable> prefetchError = new AtomicReference<>();
      final Thread prefetchThread =
          startBlockPrefetchThread(
              sourceBlockchain, currentFromBlock, toBlock, prefetchQueue, stopPrefetch, prefetchError);

      try {
        while (true) {
          final PrefetchedBlock prefetched;
          try {
            prefetched = prefetchQueue.take();
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for prefetched block", e);
          }

          if (prefetched.isEndOfStream()) {
            break;
          }
          if (prefetched.error() != null) {
            throw new RuntimeException("Block prefetch failed", prefetched.error());
          }

          final long currentBlockNumber = prefetched.blockNumber();
          final Block block = prefetched.block();
          lastProcessedBlockNumber = currentBlockNumber;
          intervalTimings.addReadSourceBlock(prefetched.readNanos());

          final ProtocolSpec protocolSpec = protocolSchedule.getByBlockHeader(block.getHeader());
          final var blockProcessor = protocolSpec.getBlockProcessor();

          final long processBlockStartNs = System.nanoTime();
          final BlockProcessingResult result =
              blockProcessor.processBlock(
                  protocolContext, sourceBlockchain, mutableWorldState, block);
          intervalTimings.addProcessBlock(System.nanoTime() - processBlockStartNs);

          if (!result.isSuccessful()) {
            System.err.println(
                "Block processing failed at blockNumber="
                    + currentBlockNumber
                    + " error="
                    + result.errorMessage.orElse("unknown"));
            if (result.cause.isPresent()) {
              result.cause.get().printStackTrace(System.err);
            }
            break;
          }

          final long writeCsvStartNs = System.nanoTime();
          // Expose per-transaction opcode usage back to this replay tool.
          opcodeCollectorProvider
              .getLastTracer()
              .ifPresent(
                  tracer -> {
                    final List<TransactionReplayOpcodes> txOpcodes = tracer.getTransactionResults();
                    final List<Transaction> blockTxs = block.getBody().getTransactions();
                    if (txOpcodes.size() != blockTxs.size()) {
                      System.err.println(
                          "Opcode tracer mismatch at blockNumber="
                              + currentBlockNumber
                              + " txsInBlock="
                              + blockTxs.size()
                              + " tracedTxs="
                              + txOpcodes.size());
                    }

                    writeBlockCsv(blockCsvDir, block, txOpcodes);
                  });
          intervalTimings.addWriteCsv(System.nanoTime() - writeCsvStartNs);

          final long writeProgressStartNs = System.nanoTime();
          // Persist progress only after successful persistence of this block.
          writeProgress(
              progressFile,
              currentBlockNumber,
              block.getHeader().getStateRoot().getBytes().toHexString());
          intervalTimings.addWriteProgress(System.nanoTime() - writeProgressStartNs);

          intervalTimings.incrementBlocks();

          if (currentBlockNumber % 100 == 0) {
            reportReplayStepTimings(currentBlockNumber, toBlock, intervalTimings);
            intervalTimings.reset();
          }
        }

        final Throwable readerFailure = prefetchError.get();
        if (readerFailure != null) {
          throw new RuntimeException("Block prefetch failed", readerFailure);
        }
      } finally {
        stopPrefetch.set(true);
        prefetchThread.interrupt();
        try {
          prefetchThread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }

      if (intervalTimings.getBlocks() > 0) {
        reportReplayStepTimings(lastProcessedBlockNumber, toBlock, intervalTimings);
      }
    } finally {
      try {
        sourceBlockchainKv.close();
      } catch (final Exception e) {
        System.err.println("Failed to close sourceBlockchainKv: " + e.getMessage());
      }
      try {
        sourceVariablesKv.close();
      } catch (final Exception e) {
        System.err.println("Failed to close sourceVariablesKv: " + e.getMessage());
      }
      // Do not close individual replay segment storages or the replay storage provider: they share
      // one RocksDB instance via replayRocksDbFactory, and closing any adapter closes the database.
      try {
        replayRocksDbFactory.close();
      } catch (final Exception e) {
        System.err.println("Failed to close replayRocksDbFactory: " + e.getMessage());
      }
      try {
        sourceRocksDbFactory.close();
      } catch (final Exception e) {
        System.err.println("Failed to close sourceRocksDbFactory: " + e.getMessage());
      }
    }
  }

  private static Thread startBlockPrefetchThread(
      final MutableBlockchain sourceBlockchain,
      final long fromBlock,
      final long toBlock,
      final BlockingQueue<PrefetchedBlock> prefetchQueue,
      final AtomicBoolean stopPrefetch,
      final AtomicReference<Throwable> prefetchError) {
    final Thread prefetchThread =
        new Thread(
            () -> {
              try {
                for (long blockNumber = fromBlock;
                    blockNumber <= toBlock && !stopPrefetch.get();
                    blockNumber++) {
                  final long currentBlockNumber = blockNumber;
                  final long readSourceStartNs = System.nanoTime();
                  final Block block =
                      sourceBlockchain
                          .getBlockByNumber(currentBlockNumber)
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "Missing block in source DB for blockNumber="
                                          + currentBlockNumber));
                  final long readNanos = System.nanoTime() - readSourceStartNs;

                  while (!stopPrefetch.get()) {
                    try {
                      if (prefetchQueue.offer(
                          new PrefetchedBlock(currentBlockNumber, block, readNanos, null),
                          100,
                          TimeUnit.MILLISECONDS)) {
                        break;
                      }
                    } catch (final InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                  }
                }

                if (!stopPrefetch.get()) {
                  prefetchQueue.put(PrefetchedBlock.endOfStream());
                }
              } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (final Throwable t) {
                prefetchError.set(t);
                try {
                  prefetchQueue.offer(new PrefetchedBlock(-1L, null, 0L, t), 1, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            },
            "block-prefetch");
    prefetchThread.setDaemon(true);
    prefetchThread.start();
    return prefetchThread;
  }

  /**
   * A block read ahead of processing. {@link #isEndOfStream()} marks successful completion of the
   * prefetch range; {@link #error()} carries a prefetch failure.
   */
  private record PrefetchedBlock(
      long blockNumber, Block block, long readNanos, Throwable error) {
    static PrefetchedBlock endOfStream() {
      return new PrefetchedBlock(-1L, null, 0L, null);
    }

    boolean isEndOfStream() {
      return block == null && error == null;
    }
  }

  private static void reportReplayStepTimings(
      final long currentBlockNumber, final long toBlock, final ReplayStepTimings timings) {
    final long blocks = timings.getBlocks();
    if (blocks == 0) {
      return;
    }
    final double readMs = nanosToMillis(timings.getReadSourceBlockNs());
    final double processMs = nanosToMillis(timings.getProcessBlockNs());
    final double csvMs = nanosToMillis(timings.getWriteCsvNs());
    final double progressMs = nanosToMillis(timings.getWriteProgressNs());
    final double totalMs = nanosToMillis(timings.totalNs());
    System.out.printf(
        "Processed block %d/%d | last %d blocks (ms): readSource=%.2f processBlock=%.2f writeCsv=%.2f writeProgress=%.2f total=%.2f%n",
        currentBlockNumber, toBlock, blocks, readMs, processMs, csvMs, progressMs, totalMs);
    System.out.printf(
        "  per-block avg (ms): readSource=%.3f processBlock=%.3f writeCsv=%.3f writeProgress=%.3f total=%.3f%n",
        readMs / blocks,
        processMs / blocks,
        csvMs / blocks,
        progressMs / blocks,
        totalMs / blocks);
  }

  private static double nanosToMillis(final long nanos) {
    return nanos / 1_000_000.0;
  }

  private static final class ReplayStepTimings {
    private long readSourceBlockNs;
    private long processBlockNs;
    private long writeCsvNs;
    private long writeProgressNs;
    private long blocks;

    void addReadSourceBlock(final long nanos) {
      readSourceBlockNs += nanos;
    }

    void addProcessBlock(final long nanos) {
      processBlockNs += nanos;
    }

    void addWriteCsv(final long nanos) {
      writeCsvNs += nanos;
    }

    void addWriteProgress(final long nanos) {
      writeProgressNs += nanos;
    }

    void incrementBlocks() {
      blocks++;
    }

    long getReadSourceBlockNs() {
      return readSourceBlockNs;
    }

    long getProcessBlockNs() {
      return processBlockNs;
    }

    long getWriteCsvNs() {
      return writeCsvNs;
    }

    long getWriteProgressNs() {
      return writeProgressNs;
    }

    long getBlocks() {
      return blocks;
    }

    long totalNs() {
      return readSourceBlockNs + processBlockNs + writeCsvNs + writeProgressNs;
    }

    void reset() {
      readSourceBlockNs = 0;
      processBlockNs = 0;
      writeCsvNs = 0;
      writeProgressNs = 0;
      blocks = 0;
    }
  }

  private static void writeBlockCsv(
      final Path blockCsvDir, final Block block, final List<TransactionReplayOpcodes> txResults) {
    final long blockNumber = block.getHeader().getNumber();
    final String blockHashHex = block.getHeader().getHash().toHexString();
    final Path out = blockCsvDir.resolve(String.format("block.%d.%s.csv", blockNumber, blockHashHex));
    final Path txsOut =
        blockCsvDir.resolve(String.format("txs.%d.%s.csv", blockNumber, blockHashHex));

    final StringBuilder sb = new StringBuilder(128 + txResults.size() * 64);
    sb.append("transactionIndexInBlock,transactionHash,succeeded,gasUsed\n");
    for (final TransactionReplayOpcodes tx : txResults) {
      sb.append(tx.transactionIndexInBlock())
          .append(',')
          .append(tx.transactionHash().toHexString())
          .append(',')
          .append(tx.succeeded())
          .append(',')
          .append(tx.gasUsed())
          .append('\n');
    }

    try {
      Files.createDirectories(out.getParent());
      Files.writeString(out, sb.toString(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

      // Storage-efficient per-call usage vectors: write empty fields for zeros.
      try (BufferedWriter w =
          Files.newBufferedWriter(
              txsOut, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        for (final TransactionReplayOpcodes tx : txResults) {
          final int txIndex = tx.transactionIndexInBlock();
          for (final int[] callOpcodeExecutionCounts : tx.perCallOpcodeExecutionCounts()) {
            w.write(Integer.toString(txIndex));
            final int len = callOpcodeExecutionCounts.length;
            for (int i = 0; i < len; i++) {
              w.write(',');
              final int v = callOpcodeExecutionCounts[i];
              if (v != 0) {
                w.write(Integer.toString(v));
              }
            }
            w.newLine();
          }
        }
      }
    } catch (final IOException e) {
      throw new RuntimeException(
          "Failed to write block csv outputs to: " + out + " and " + txsOut, e);
    }
  }

  private static DataStorageConfiguration dataStorageConfigurationFrom(
      final DatabaseMetadata databaseMetadata) {
    final VersionedStorageFormat versionedStorageFormat =
        databaseMetadata.getVersionedStorageFormat();
    return ImmutableDataStorageConfiguration.builder()
        .dataStorageFormat(versionedStorageFormat.getFormat())
        .receiptCompactionEnabled(receiptCompactionEnabled(versionedStorageFormat))
        .build();
  }

  private static boolean receiptCompactionEnabled(final VersionedStorageFormat format) {
    if (format.getFormat() == DataStorageFormat.X_BONSAI_ARCHIVE) {
      return format.getVersion()
          >= BaseVersionedStorageFormat.BONSAI_ARCHIVE_WITH_RECEIPT_COMPACTION.getVersion();
    }
    return format.getVersion()
        >= BaseVersionedStorageFormat.FOREST_WITH_RECEIPT_COMPACTION.getVersion();
  }

  private static MutableWorldState loadBonsaiCheckpointWorldState(
      final WorldStateArchive worldStateArchive,
      final BlockHeader checkpointHeader,
      final Hash expectedStateRoot,
      final Hash expectedBlockHash,
      final long checkpointBlockNumber,
      final long replayHeadBlockNumber) {
    final MutableWorldState headWorldState = worldStateArchive.getWorldState();
    if (headWorldState instanceof PathBasedWorldState pathBasedHeadWorldState
        && pathBasedHeadWorldState.getWorldStateBlockHash().equals(expectedBlockHash)
        && headWorldState.rootHash().equals(expectedStateRoot)) {
      return headWorldState;
    }

    final MutableWorldState rolledWorldState =
        worldStateArchive
            .getWorldState(
                WorldStateQueryParams.withBlockHeaderAndUpdateNodeHead(checkpointHeader))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Unable to load BONSAI world state at checkpoint block "
                            + checkpointBlockNumber
                            + " (replay chain head is "
                            + replayHeadBlockNumber
                            + ")"));

    if (!rolledWorldState.rootHash().equals(expectedStateRoot)) {
      throw new IllegalStateException(
          "Loaded BONSAI world state root "
              + rolledWorldState.rootHash()
              + " does not match checkpoint state root "
              + expectedStateRoot
              + " at block "
              + checkpointBlockNumber);
    }
    return rolledWorldState;
  }

  private static MutableBlockchain openReplayBlockchain(
      final KeyValueStorageProvider replayStorageProvider,
      final DataStorageConfiguration replayStorageConfiguration,
      final ProtocolSchedule protocolSchedule,
      final GenesisState genesisState,
      final MetricsSystem metricsSystem) {
    try {
      final VariablesStorage variablesStorage = replayStorageProvider.createVariablesStorage();
      final BlockchainStorage blockchainStorage =
          replayStorageProvider.createBlockchainStorage(
              protocolSchedule, variablesStorage, replayStorageConfiguration);

      return DefaultBlockchain.createMutable(
          genesisState.getBlock(),
          blockchainStorage,
          metricsSystem,
          /* reorgLoggingThreshold= */ 0);
    } catch (final Exception e) {
      throw new RuntimeException("Failed to open replay blockchain from replay data path", e);
    }
  }

  private static BlockHeader readReplayChainHeadHeader(
      final BesuConfiguration replayBesuConfiguration,
      final RocksDBKeyValueStorageFactory replayRocksDbFactory,
      final GenesisState genesisState,
      final MetricsSystem metricsSystem) {
    KeyValueStorage replayBlockchainKv = null;
    KeyValueStorage replayVariablesKv = null;
    try {
      replayBlockchainKv =
          replayRocksDbFactory.create(
              KeyValueSegmentIdentifier.BLOCKCHAIN, replayBesuConfiguration, metricsSystem);
      replayVariablesKv =
          replayRocksDbFactory.create(
              KeyValueSegmentIdentifier.VARIABLES, replayBesuConfiguration, metricsSystem);

      final VariablesKeyValueStorage replayVariablesStorage =
          new VariablesKeyValueStorage(replayVariablesKv);
      final BlockchainStorage replayBlockchainStorage =
          new KeyValueStoragePrefixedKeyBlockchainStorage(
              replayBlockchainKv,
              replayVariablesStorage,
              new MainnetBlockHeaderFunctions(),
              replayBesuConfiguration.getDataStorageConfiguration().getReceiptCompactionEnabled());

      final MutableBlockchain replayBlockchain =
          DefaultBlockchain.createMutable(
              genesisState.getBlock(),
              replayBlockchainStorage,
              metricsSystem,
              /* reorgLoggingThreshold= */ 0);

      return replayBlockchain.getChainHeadHeader();
    } catch (final Exception e) {
      throw new RuntimeException("Failed to read replay chain head from replay data path", e);
    }
    // Do not close replayBlockchainKv/replayVariablesKv: they are adapters over the shared
    // RocksDB instance owned by replayRocksDbFactory.
  }

  private static long parseLongOrDefault(final String value, final long defaultValue) {
    return value == null ? defaultValue : Long.parseLong(value);
  }

  private static Map<String, String> parseArgs(final String[] args) {
    final Map<String, String> map = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      final String k = args[i];
      if (!k.startsWith("--")) {
        continue;
      }
      if (i + 1 >= args.length) {
        throw new IllegalArgumentException("Missing value for arg: " + k);
      }
      map.put(k, args[++i]);
    }
    return map;
  }

  private static Optional<ReplayProgress> readProgress(final Path progressFile) {
    if (!Files.exists(progressFile)) {
      return Optional.empty();
    }

    final Properties props = new Properties();
    try (var in = Files.newInputStream(progressFile, StandardOpenOption.READ)) {
      props.load(in);
      final String lastBlock = props.getProperty("lastProcessedBlockNumber");
      final String lastStateRootHex = props.getProperty("lastProcessedStateRootHex");
      if (lastBlock == null || lastStateRootHex == null) {
        return Optional.empty();
      }
      return Optional.of(
          new ReplayProgress(Long.parseLong(lastBlock.trim()), lastStateRootHex.trim()));
    } catch (final Exception e) {
      System.err.println("Failed to read replay progress; starting fresh. " + e.getMessage());
      return Optional.empty();
    }
  }

  private static void writeProgress(
      final Path progressFile, final long lastProcessedBlockNumber, final String lastStateRootHex) {
    try {
      Files.createDirectories(progressFile.getParent());
      final Properties props = new Properties();
      props.setProperty("lastProcessedBlockNumber", Long.toString(lastProcessedBlockNumber));
      props.setProperty("lastProcessedStateRootHex", lastStateRootHex);

      final Path tmp = progressFile.resolveSibling(progressFile.getFileName() + ".tmp");
      try (OutputStream out =
          Files.newOutputStream(
              tmp,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        props.store(out, "Besu replay progress");
      }
      Files.move(tmp, progressFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (final IOException e) {
      throw new RuntimeException("Failed to write replay progress to: " + progressFile, e);
    }
  }

  private static final class ReplayProgress {
    private final long lastProcessedBlockNumber;
    private final String lastProcessedStateRootHex;

    private ReplayProgress(final long lastProcessedBlockNumber, final String lastProcessedStateRootHex) {
      this.lastProcessedBlockNumber = lastProcessedBlockNumber;
      this.lastProcessedStateRootHex = lastProcessedStateRootHex;
    }
  }

  /**
   * Minimal {@link ConsensusContext} implementation for replaying EVM execution.
   *
   * <p>Block processing with an in-memory forest world state does not require merge/consensus
   * behaviour, so this context is intentionally minimal.
   */
  private static final class SimpleConsensusContext implements ConsensusContext {
    @Override
    public <C extends ConsensusContext> C as(final Class<C> klass) {
      return klass.cast(this);
    }
  }

  /**
   * Provides a {@link BlockAwareOperationTracer} for block processing that collects, per
   * transaction, the per-frame opcode histograms stored in {@link TxValues#perFrameOpcodeUsage()}.
   *
   * <p>Implemented via the plugin service mechanism because {@link
   * org.hyperledger.besu.ethereum.mainnet.BlockProcessor} does not expose an operation tracer
   * parameter.
   */
  private static final class OpcodeCollectorBlockImportTracerProvider
      implements BlockImportTracerProvider {
    private volatile BlockOpcodeCollectorTracer lastTracer;

    @Override
    public BlockAwareOperationTracer getBlockImportTracer(
        final org.hyperledger.besu.plugin.data.BlockHeader blockHeader) {
      lastTracer = new BlockOpcodeCollectorTracer();
      return lastTracer;
    }

    public Optional<BlockOpcodeCollectorTracer> getLastTracer() {
      return Optional.ofNullable(lastTracer);
    }
  }

  /**
   * Collects opcode usage per transaction, where each transaction contains the per-call arrays
   * allocated for each {@link MessageFrame} created during execution.
   */
  private static final class BlockOpcodeCollectorTracer implements BlockAwareOperationTracer {
    private Hash currentTxHash;
    private int txIndexInBlock;
    private final Map<Hash, TxValues> txValuesByTxHash = new HashMap<>();
    private final List<TransactionReplayOpcodes> transactionResults = new ArrayList<>();

    @Override
    public void traceStartTransaction(
        final org.hyperledger.besu.evm.worldstate.WorldView worldView,
        final org.hyperledger.besu.datatypes.Transaction transaction) {
      currentTxHash = transaction.getHash();
    }

    @Override
    public void traceContextEnter(final MessageFrame frame) {
      // The root frame is the only one with stack size 1 at the point it's first processed.
      if (frame.getMessageFrameStack().size() == 1 && currentTxHash != null) {
        txValuesByTxHash.put(currentTxHash, frame.getTxValues());
      }
    }

    @Override
    public void traceEndTransaction(
        final org.hyperledger.besu.evm.worldstate.WorldView worldView,
        final org.hyperledger.besu.datatypes.Transaction tx,
        final boolean status,
        final org.apache.tuweni.bytes.Bytes output,
        final List<org.hyperledger.besu.datatypes.Log> logs,
        final long gasUsed,
        final java.util.Set<org.hyperledger.besu.datatypes.Address> selfDestructs,
        final long timeNs) {
      final Hash txHash = tx.getHash();
      final TxValues txValues = txValuesByTxHash.get(txHash);

      final List<int[]> perCallOpcodeExecutionCounts =
          txValues == null ? List.of() : List.copyOf(txValues.perCallOpcodeUsage());

      transactionResults.add(
          new TransactionReplayOpcodes(
              txHash, txIndexInBlock++, status, gasUsed, perCallOpcodeExecutionCounts));
    }

    public List<TransactionReplayOpcodes> getTransactionResults() {
      return Collections.unmodifiableList(transactionResults);
    }
  }

  /**
   * Per-transaction output available to {@link ReplayTransactionsFromDb} after a block is
   * processed.
   */
  private record TransactionReplayOpcodes(
      Hash transactionHash,
      int transactionIndexInBlock,
      boolean succeeded,
      long gasUsed,
      List<int[]> perCallOpcodeExecutionCounts) {}
}


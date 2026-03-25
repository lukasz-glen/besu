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
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions;
import org.hyperledger.besu.ethereum.mainnet.MainnetProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.trie.forest.ForestWorldStateArchive;
import org.hyperledger.besu.ethereum.trie.forest.storage.ForestWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.forest.worldview.ForestMutableWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.CodeCache;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStoragePrefixedKeyBlockchainStorage;
import org.hyperledger.besu.ethereum.storage.keyvalue.VariablesKeyValueStorage;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier;
import org.hyperledger.besu.ethereum.storage.keyvalue.WorldStatePreimageKeyValueStorage;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.services.BesuConfiguration;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBMetricsFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBKeyValueStorageFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.RocksDBCLIOptions;
import org.hyperledger.besu.services.BesuConfigurationImpl;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Hash;

import org.hyperledger.besu.ethereum.worldstate.WorldStatePreimageStorage;
import org.hyperledger.besu.ethereum.core.MutableWorldState;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Standalone tool that re-executes blocks sequentially from genesis using the on-disk Besu
 * database as a transaction/block source, while keeping the executed world state in a
 * separate replay database on disk.
 *
 * <p>This is intended for experimentation; validating all Mainnet blocks can take a very long
 * time.
 */
public final class ReplayTransactionsFromDb {

  private ReplayTransactionsFromDb() {}

  public static void main(final String[] args) {
    final Map<String, String> cli = parseArgs(args);
    final Path dataPath =
        Path.of(requireNonNull(cli.get("--data-path"), "--data-path is required")).toAbsolutePath();

    final long toBlockOption = parseLongOrDefault(cli.get("--to-block"), -1L);
    final long toBlock;

    final Path storagePath = dataPath.resolve(BesuController.DATABASE_PATH);
    final Path replayDataPath =
        Path.of(cli.getOrDefault("--replay-data-path", dataPath.resolve("replay-worldstate").toString()))
            .toAbsolutePath();
    final Path replayStoragePath = replayDataPath.resolve(BesuController.DATABASE_PATH);
    final Path progressFile = replayDataPath.resolve("replay-progress.properties");
    final boolean resume = Boolean.parseBoolean(cli.getOrDefault("--resume", "true"));

    // 1. Build the protocol schedule + genesis world state (for initializing our in-memory state).
    final GenesisConfig genesisConfig = GenesisConfig.mainnet();
    final MetricsSystem metricsSystem = new NoOpMetricsSystem();
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

    final GenesisState genesisState = GenesisState.fromConfig(genesisConfig, protocolSchedule, new CodeCache());

    // 2. Open the existing DB to read canonical blocks.
    final BesuConfiguration besuConfiguration =
        new BesuConfigurationImpl().init(
            dataPath, storagePath, DataStorageConfiguration.DEFAULT_BONSAI_CONFIG);

    final RocksDBKeyValueStorageFactory rocksDbFactory =
        new RocksDBKeyValueStorageFactory(
            RocksDBCLIOptions.create()::toDomainObject,
            List.of(KeyValueSegmentIdentifier.values()),
            RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS);

    // Replay DB: separate RocksDB that we can write to (without touching the node DB).
    final BesuConfiguration replayBesuConfiguration =
        new BesuConfigurationImpl()
            .init(replayDataPath, replayStoragePath, DataStorageConfiguration.DEFAULT_FOREST_CONFIG);

    final KeyValueStorage blockchainKv;
    final KeyValueStorage variablesKv;
    KeyValueStorage replayWorldStateKv = null;
    KeyValueStorage replayPreimageKv = null;
    try {
      blockchainKv =
          rocksDbFactory.create(
              KeyValueSegmentIdentifier.BLOCKCHAIN, besuConfiguration, metricsSystem);
      variablesKv =
          rocksDbFactory.create(KeyValueSegmentIdentifier.VARIABLES, besuConfiguration, metricsSystem);
    } catch (final Exception e) {
      throw new RuntimeException("Failed to open RocksDB storages under: " + storagePath, e);
    }

    try {
      final var variablesStorage = new VariablesKeyValueStorage(variablesKv);
      final BlockchainStorage blockchainStorage =
          new KeyValueStoragePrefixedKeyBlockchainStorage(
              blockchainKv,
              variablesStorage,
              new MainnetBlockHeaderFunctions(),
              /* receiptCompaction= */ true);

      final MutableBlockchain blockchain =
          DefaultBlockchain.createMutable(
              genesisState.getBlock(),
              blockchainStorage,
              new NoOpMetricsSystem(),
              /* reorgLoggingThreshold= */ 0);

      final long chainHeadBlockNumber = blockchain.getChainHeadBlockNumber();
      toBlock = toBlockOption < 0 ? chainHeadBlockNumber : toBlockOption;

      // 3. Open a disk-backed world state (replay DB) and ProtocolContext for block processing.
      replayWorldStateKv =
          rocksDbFactory.create(
              KeyValueSegmentIdentifier.WORLD_STATE, replayBesuConfiguration, metricsSystem);
      replayPreimageKv =
          rocksDbFactory.create(
              KeyValueSegmentIdentifier.PRUNING_STATE, replayBesuConfiguration, metricsSystem);

      final ForestWorldStateKeyValueStorage replayForestWsStorage =
          new ForestWorldStateKeyValueStorage(replayWorldStateKv);
      final WorldStateStorageCoordinator worldStateStorageCoordinator =
          new WorldStateStorageCoordinator(replayForestWsStorage);
      final WorldStatePreimageStorage preimageStorage =
          new WorldStatePreimageKeyValueStorage(replayPreimageKv);

      final ForestWorldStateArchive worldStateArchive =
          new ForestWorldStateArchive(
              worldStateStorageCoordinator, preimageStorage, EvmConfiguration.DEFAULT);

      final boolean replayDbExists = Files.exists(replayStoragePath);
      final boolean progressExists = Files.exists(progressFile);

      final Optional<ReplayProgress> maybeProgress;
      if (!resume) {
        maybeProgress = Optional.empty();
      } else if (replayDbExists && progressExists) {
        maybeProgress = readProgress(progressFile);
      } else if (replayDbExists && !progressExists) {
        throw new IllegalStateException(
            "Replay database exists but progress file is missing. "
                + "Refusing to run because the replay state root to resume from is unknown. "
                + "Either restore "
                + progressFile
                + " or delete the replay database at "
                + replayDataPath);
      } else if (!replayDbExists && progressExists) {
        System.err.println(
            "Replay progress file exists but replay database directory is missing. "
                + "Ignoring progress and starting from genesis.");
        maybeProgress = Optional.empty();
      } else {
        maybeProgress = Optional.empty();
      }

      long currentFromBlock = 1L;
      final MutableWorldState mutableWorldState;

      if (maybeProgress.isPresent()) {
        final ReplayProgress progress = maybeProgress.get();
        currentFromBlock = progress.lastProcessedBlockNumber + 1;

        final Hash stateRoot = Hash.fromHexString(progress.lastProcessedStateRootHex);
        mutableWorldState =
            new ForestMutableWorldState(
                Bytes32.wrap(stateRoot.getBytes()),
                replayForestWsStorage,
                preimageStorage,
                EvmConfiguration.DEFAULT);
      } else {
        mutableWorldState =
            new ForestMutableWorldState(
                replayForestWsStorage, preimageStorage, EvmConfiguration.DEFAULT);
        // Initialize world state to genesis allocations.
        genesisState.writeStateTo(mutableWorldState);
      }

      // Sanity-check: genesis header stateRoot in the DB should match our computed root.
      final Block dbGenesis = blockchain.getGenesisBlock();
      final BlockHeader dbGenesisHeader = dbGenesis.getHeader();
      final var computedGenesisRoot = mutableWorldState.rootHash();
      if (!dbGenesisHeader.getStateRoot().equals(computedGenesisRoot) && maybeProgress.isEmpty()) {
        throw new IllegalStateException(
            "Genesis stateRoot mismatch (fresh replay): DB="
                + dbGenesisHeader.getStateRoot()
                + " computed="
                + computedGenesisRoot);
      }

      final ConsensusContext consensusContext = new SimpleConsensusContext();
      final ProtocolContext protocolContext =
          new ProtocolContext.Builder()
              .withBlockchain(blockchain)
              .withWorldStateArchive(worldStateArchive)
              .withConsensusContext(consensusContext)
              .withServiceManager(new ServiceManager.SimpleServiceManager())
              .build();

      // 4. Sequentially process blocks through the protocol's block processor.
      System.out.println(
          "Re-executing canonical blocks from "
              + currentFromBlock
              + " to "
              + toBlock
              + " (inclusive). "
              + (maybeProgress.isPresent() ? "Resuming." : "Fresh start."));

      for (long blockNumber = currentFromBlock; blockNumber <= toBlock; blockNumber++) {
        final long currentBlockNumber = blockNumber;
        final Block block =
            blockchain
                .getBlockByNumber(currentBlockNumber)
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Missing block in DB for blockNumber=" + currentBlockNumber));

        final ProtocolSpec protocolSpec =
            protocolSchedule.getByBlockHeader(block.getHeader());
        final var blockProcessor = protocolSpec.getBlockProcessor();

        final BlockProcessingResult result =
            blockProcessor.processBlock(protocolContext, blockchain, mutableWorldState, block);

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

        // Persist progress only after successful persistence of this block.
        writeProgress(
            progressFile, currentBlockNumber, block.getHeader().getStateRoot().getBytes().toHexString());

        if (currentBlockNumber % 100 == 0) {
          System.out.println("Processed block " + currentBlockNumber + "/" + toBlock);
        }
      }
    } finally {
      try {
        blockchainKv.close();
      } catch (final Exception e) {
        System.err.println("Failed to close blockchainKv: " + e.getMessage());
      }
      try {
        variablesKv.close();
      } catch (final Exception e) {
        System.err.println("Failed to close variablesKv: " + e.getMessage());
      }
      try {
        if (replayWorldStateKv != null) {
          replayWorldStateKv.close();
        }
      } catch (final Exception e) {
        System.err.println("Failed to close replayWorldStateKv: " + e.getMessage());
      }
      try {
        if (replayPreimageKv != null) {
          replayPreimageKv.close();
        }
      } catch (final Exception e) {
        System.err.println("Failed to close replayPreimageKv: " + e.getMessage());
      }
    }
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
}


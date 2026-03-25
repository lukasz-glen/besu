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
import org.hyperledger.besu.services.kvstore.InMemoryKeyValueStorage;

import org.hyperledger.besu.ethereum.worldstate.WorldStatePreimageStorage;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorageFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Standalone tool that re-executes blocks sequentially from genesis using the on-disk Besu
 * database as a transaction/block source, while keeping the executed world state in memory.
 *
 * <p>This is intended for experimentation; validating all Mainnet blocks can take a very long
 * time and also consumes significant memory because trie nodes are stored in the in-memory
 * forest backend.
 */
public final class ReplayTransactionsFromDbMemoryState {

  private ReplayTransactionsFromDbMemoryState() {}

  public static void main(final String[] args) {
    final Map<String, String> cli = parseArgs(args);
    final Path dataPath =
        Path.of(requireNonNull(cli.get("--data-path"), "--data-path is required")).toAbsolutePath();

    final long fromBlock = parseLong(cli.getOrDefault("--from-block", "1"));
    final long toBlockOption = parseLongOrDefault(cli.get("--to-block"), -1L);
    final long toBlock;

    final Path storagePath = dataPath.resolve(BesuController.DATABASE_PATH);

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

    final KeyValueStorage blockchainKv;
    final KeyValueStorage variablesKv;
    try {
      blockchainKv = rocksDbFactory.create(KeyValueSegmentIdentifier.BLOCKCHAIN, besuConfiguration, metricsSystem);
      variablesKv = rocksDbFactory.create(KeyValueSegmentIdentifier.VARIABLES, besuConfiguration, metricsSystem);
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
      if (fromBlock < 0) {
        throw new IllegalArgumentException("--from-block must be >= 0");
      }
      if (fromBlock > toBlock) {
        throw new IllegalArgumentException("--from-block must be <= --to-block");
      }

      // 3. Create an in-memory world state and ProtocolContext for block processing.
      final InMemoryKeyValueStorage inMemoryWorldStateKv = new InMemoryKeyValueStorage();
      final InMemoryKeyValueStorage inMemoryWorldStatePreimageKv = new InMemoryKeyValueStorage();

      final ForestWorldStateKeyValueStorage inMemoryForestWsStorage =
          new ForestWorldStateKeyValueStorage(inMemoryWorldStateKv);
      final WorldStateStorageCoordinator worldStateStorageCoordinator =
          new WorldStateStorageCoordinator(inMemoryForestWsStorage);
      final WorldStatePreimageStorage preimageStorage =
          new WorldStatePreimageKeyValueStorage(inMemoryWorldStatePreimageKv);

      final ForestWorldStateArchive worldStateArchive =
          new ForestWorldStateArchive(
              worldStateStorageCoordinator,
              preimageStorage,
              EvmConfiguration.DEFAULT);

      final MutableWorldState mutableWorldState =
          new ForestMutableWorldState(
              inMemoryForestWsStorage, preimageStorage, EvmConfiguration.DEFAULT);

      // Initialize world state to genesis allocations.
      genesisState.writeStateTo(mutableWorldState);

      // Sanity-check: genesis header stateRoot in the DB should match our computed root.
      final Block dbGenesis = blockchain.getGenesisBlock();
      final BlockHeader dbGenesisHeader = dbGenesis.getHeader();
      final var computedGenesisRoot = mutableWorldState.rootHash();
      if (!dbGenesisHeader.getStateRoot().equals(computedGenesisRoot)) {
        throw new IllegalStateException(
            "Genesis stateRoot mismatch: DB="
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
          "Re-executing canonical blocks from " + fromBlock + " to " + toBlock + " (inclusive).");

      for (long blockNumber = fromBlock; blockNumber <= toBlock; blockNumber++) {
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
    }
  }

  private static long parseLong(final String value) {
    if (value == null) {
      throw new IllegalArgumentException("Missing long value");
    }
    return Long.parseLong(value);
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


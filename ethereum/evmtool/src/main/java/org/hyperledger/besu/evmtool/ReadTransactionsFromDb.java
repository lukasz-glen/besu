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
import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.chain.BlockchainStorage;
import org.hyperledger.besu.ethereum.chain.DefaultBlockchain;
import org.hyperledger.besu.ethereum.chain.GenesisState;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions;
import org.hyperledger.besu.ethereum.mainnet.MainnetProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStoragePrefixedKeyBlockchainStorage;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProvider;
import org.hyperledger.besu.ethereum.storage.keyvalue.VariablesKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.forest.storage.ForestWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.CodeCache;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.ImmutableDataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.BesuConfiguration;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBKeyValueStorageFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBMetricsFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.BaseVersionedStorageFormat;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.DatabaseMetadata;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.RocksDBCLIOptions;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.VersionedStorageFormat;
import org.hyperledger.besu.services.BesuConfigurationImpl;
import org.hyperledger.besu.services.kvstore.InMemoryKeyValueStorage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Read-only inspector for an on-disk Besu RocksDB data directory.
 *
 * <p>Opens the database without writing, prints chain head information, checks whether the chain
 * head world state is present, and optionally lists transaction hashes for a block number.
 */
public final class ReadTransactionsFromDb {

  private ReadTransactionsFromDb() {}

  public static void main(final String[] args) throws IOException {
    final Map<String, String> cli = parseArgs(args);
    final Path dataPath =
        Path.of(requireNonNull(cli.get("--data-path"), "--data-path is required")).toAbsolutePath();
    final Optional<Long> blockNumber = Optional.ofNullable(cli.get("--block-number")).map(Long::parseLong);

    final Path storagePath = dataPath.resolve(BesuController.DATABASE_PATH);
    final DatabaseMetadata databaseMetadata = DatabaseMetadata.lookUpFrom(dataPath);
    final VersionedStorageFormat versionedStorageFormat =
        databaseMetadata.getVersionedStorageFormat();
    final DataStorageConfiguration dataStorageConfiguration =
        ImmutableDataStorageConfiguration.builder()
            .dataStorageFormat(versionedStorageFormat.getFormat())
            .receiptCompactionEnabled(receiptCompactionEnabled(versionedStorageFormat))
            .build();

    final GenesisConfig genesisConfig = GenesisConfig.mainnet();
    final MetricsSystem metricsSystem = new NoOpMetricsSystem();
    final ProtocolSchedule protocolSchedule =
        MainnetProtocolSchedule.fromConfig(
            genesisConfig.getConfigOptions(),
            Optional.empty(),
            Optional.of(EvmConfiguration.DEFAULT),
            MiningConfiguration.newDefault(),
            new BadBlockManager(),
            false,
            BalConfiguration.DEFAULT,
            metricsSystem);
    final GenesisState genesisState =
        GenesisState.fromConfig(genesisConfig, protocolSchedule, new CodeCache());

    final BesuConfiguration besuConfiguration =
        new BesuConfigurationImpl().init(dataPath, storagePath, dataStorageConfiguration);

    final RocksDBKeyValueStorageFactory rocksDbFactory =
        new RocksDBKeyValueStorageFactory(
            RocksDBCLIOptions.create()::toDomainObject,
            List.of(KeyValueSegmentIdentifier.values()),
            RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS);

    KeyValueStorage blockchainKv = null;
    KeyValueStorage variablesKv = null;
    KeyValueStorageProvider worldStateProvider = null;
    try {
      blockchainKv =
          rocksDbFactory.create(
              KeyValueSegmentIdentifier.BLOCKCHAIN, besuConfiguration, metricsSystem);
      variablesKv =
          rocksDbFactory.create(
              KeyValueSegmentIdentifier.VARIABLES, besuConfiguration, metricsSystem);

      final VariablesKeyValueStorage variablesStorage = new VariablesKeyValueStorage(variablesKv);
      final BlockchainStorage blockchainStorage =
          new KeyValueStoragePrefixedKeyBlockchainStorage(
              blockchainKv,
              variablesStorage,
              new MainnetBlockHeaderFunctions(),
              dataStorageConfiguration.getReceiptCompactionEnabled());

      final MutableBlockchain blockchain =
          DefaultBlockchain.createMutable(
              genesisState.getBlock(),
              blockchainStorage,
              metricsSystem,
              /* reorgLoggingThreshold= */ 0);

      final BlockHeader chainHead = blockchain.getChainHeadHeader();
      System.out.println("Database format: " + versionedStorageFormat);
      System.out.println("Chain head block number: " + blockchain.getChainHeadBlockNumber());
      System.out.println("Chain head hash: " + chainHead.getHash().toHexString());
      System.out.println("Chain head state root: " + chainHead.getStateRoot().toHexString());

      worldStateProvider =
          createWorldStateProvider(rocksDbFactory, besuConfiguration, dataStorageConfiguration, metricsSystem);
      final WorldStateStorageCoordinator worldStateStorageCoordinator =
          worldStateProvider.createWorldStateStorageCoordinator(dataStorageConfiguration);

      final boolean worldStateAvailable =
          worldStateStorageCoordinator.isWorldStateAvailable(
              Bytes32.wrap(chainHead.getStateRoot().getBytes()), chainHead.getHash());
      System.out.println("World state available at chain head: " + worldStateAvailable);

      if (blockNumber.isPresent()) {
        printBlockTransactions(blockchain, blockNumber.get());
      }
    } finally {
      closeQuietly(worldStateProvider);
      closeQuietly(variablesKv);
      closeQuietly(blockchainKv);
      closeQuietly(rocksDbFactory);
    }
  }

  private static void printBlockTransactions(
      final MutableBlockchain blockchain, final long blockNumber) {
    final Optional<Block> maybeBlock = blockchain.getBlockByNumber(blockNumber);
    if (maybeBlock.isEmpty()) {
      System.out.println("Block " + blockNumber + ": not available");
      return;
    }

    final Block block = maybeBlock.get();
    final List<Transaction> transactions = block.getBody().getTransactions();
    System.out.println("Block " + blockNumber + ": available");
    System.out.println("Block hash: " + block.getHash().toHexString());
    System.out.println("Transaction count: " + transactions.size());
    System.out.println("Transaction hashes:");
    for (int i = 0; i < transactions.size(); i++) {
      System.out.println("  [" + i + "] " + transactions.get(i).getHash().toHexString());
    }
  }

  private static KeyValueStorageProvider createWorldStateProvider(
      final RocksDBKeyValueStorageFactory rocksDbFactory,
      final BesuConfiguration besuConfiguration,
      final DataStorageConfiguration dataStorageConfiguration,
      final MetricsSystem metricsSystem) {
    final KeyValueStorage preimageStorage =
        dataStorageConfiguration.getDataStorageFormat() == DataStorageFormat.FOREST
            ? rocksDbFactory.create(
                KeyValueSegmentIdentifier.PRUNING_STATE, besuConfiguration, metricsSystem)
            : new InMemoryKeyValueStorage();

    return new KeyValueStorageProvider(
        segments -> rocksDbFactory.create(segments, besuConfiguration, metricsSystem),
        preimageStorage,
        new NoOpMetricsSystem());
  }

  private static boolean receiptCompactionEnabled(final VersionedStorageFormat format) {
    if (format.getFormat() == DataStorageFormat.X_BONSAI_ARCHIVE) {
      return format.getVersion() >= BaseVersionedStorageFormat.BONSAI_ARCHIVE_WITH_RECEIPT_COMPACTION.getVersion();
    }
    return format.getVersion() >= BaseVersionedStorageFormat.FOREST_WITH_RECEIPT_COMPACTION.getVersion();
  }

  private static void closeQuietly(final AutoCloseable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (final Exception e) {
      System.err.println("Failed to close resource: " + e.getMessage());
    }
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
}

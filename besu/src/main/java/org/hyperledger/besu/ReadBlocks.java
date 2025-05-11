package org.hyperledger.besu;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import org.hyperledger.besu.cli.DefaultCommandValues;
import org.hyperledger.besu.config.GenesisConfig;
import org.hyperledger.besu.consensus.merge.TransitionProtocolSchedule;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.ConsensusContext;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.chain.BlockchainStorage;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.chain.VariablesStorage;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;
import org.hyperledger.besu.ethereum.mainnet.MainnetProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProvider;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProviderBuilder;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.StorageService;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBKeyValueStorageFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBMetricsFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.RocksDBCLIOptions;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.RocksDBFactoryConfiguration;
import org.hyperledger.besu.services.BesuConfigurationImpl;
import org.hyperledger.besu.services.StorageServiceImpl;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.hyperledger.besu.cli.config.NetworkName.MAINNET;
import static org.hyperledger.besu.controller.BesuController.DATABASE_PATH;

public class ReadBlocks {

    public static final String DATA_PATH_1 = "/home/lukaszglen/others/besu-database";
    public static final String DATA_PATH_2 = "/home/lukaszglen/others/besu-database-2";

    public static void main(final String[] args) throws IOException {
        final ReadBlocks readBlocks = new ReadBlocks().init(DATA_PATH_2);
//        System.out.println(readBlocks.getBlockHash(0));
//        System.out.println(readBlocks.getBlock(0));
//        System.out.println(readBlocks.getChainHeadNumber());
        for (int bn = 22363924 ; bn <= 22420894 ; bn ++ ) {
            Block block = readBlocks.getBlock(bn);
            System.out.println(block.getHeader().getNumber() + "," + block.getHeader().getGasUsed());
        }
        readBlocks.close();
    }

    private BlockchainStorage blockchainStorage;
    private KeyValueStorageProvider storageProvider;
    private ProtocolSchedule protocolSchedule;

    ReadBlocks init(final String dataPath) throws IOException {
        final StorageService storageService = new StorageServiceImpl();
        final RocksDBCLIOptions options = RocksDBCLIOptions.create();
        final Supplier<RocksDBFactoryConfiguration> configuration =
                Suppliers.memoize(options::toDomainObject);
        final List<SegmentIdentifier> segments = storageService.getAllSegmentIdentifiers();
        final List<SegmentIdentifier> ignorableSegments = new ArrayList<>();
        RocksDBKeyValueStorageFactory rocksDBFactory =
                new RocksDBKeyValueStorageFactory(
                        configuration,
                        segments,
                        ignorableSegments,
                        RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS);
        storageService.registerKeyValueStorage(rocksDBFactory);

        final MetricsSystem metricsSystem = new NoOpMetricsSystem();
        final DataStorageConfiguration dataStorageConfiguration = DataStorageConfiguration.DEFAULT_BONSAI_CONFIG;
        final GenesisConfig genesisConfig = GenesisConfig.fromResource(MAINNET.getGenesisFile());
        final boolean isParallelTxProcessingEnabled = false;
        final BadBlockManager badBlockManager = new BadBlockManager();
        final ProtocolSchedule protocolSchedule = MainnetProtocolSchedule.fromConfig(
                genesisConfig.getConfigOptions(),
                MiningConfiguration.MINING_DISABLED,
                badBlockManager,
                isParallelTxProcessingEnabled,
                metricsSystem);
        final Path dataDir = Paths.get(dataPath).toAbsolutePath();
        final BesuConfigurationImpl pluginCommonConfiguration = new BesuConfigurationImpl();
        pluginCommonConfiguration
                .init(dataDir, dataDir.resolve(DATABASE_PATH), dataStorageConfiguration);

        final KeyValueStorageProvider storageProvider = new KeyValueStorageProviderBuilder()
                .withStorageFactory(storageService.getByName(DefaultCommandValues.DEFAULT_KEY_VALUE_STORAGE_NAME).orElseThrow())
                .withCommonConfiguration(pluginCommonConfiguration)
                .withMetricsSystem(metricsSystem)
                .build();
        final VariablesStorage variablesStorage = storageProvider.createVariablesStorage();
        final BlockchainStorage blockchainStorage = storageProvider.createBlockchainStorage(protocolSchedule, variablesStorage, dataStorageConfiguration);

        this.storageProvider = storageProvider;
        this.protocolSchedule = protocolSchedule;
        this.blockchainStorage = blockchainStorage;
        return this;
    }

    ProtocolSchedule getProtocolSchedule() {
        return this.protocolSchedule;
    }

    void close() throws IOException {
        storageProvider.close();
    }

    Hash getBlockHash(final long blockNumber) {
        return this.blockchainStorage.getBlockHash(blockNumber).orElseThrow();
    }

    long getChainHeadNumber() {
        Hash chainHead = blockchainStorage.getChainHead().orElseThrow();
        return blockchainStorage.getBlockHeader(chainHead).orElseThrow().getNumber();
    }

    Block getBlock(final long blockNumber) {
        Hash blockHash = this.blockchainStorage.getBlockHash(blockNumber).orElseThrow();
        BlockHeader header = this.blockchainStorage.getBlockHeader(blockHash).orElseThrow();
        BlockBody body = this.blockchainStorage.getBlockBody(blockHash).orElseThrow();
        return new Block(header, body);
    }

//    void saveBlock(Block block) {
//        var optResult =
//                this.protocolSchedule.getByBlockHeader(block.getHeader()).getBlockValidator()
//                        .validateAndProcessBlock(
//                                this.protocolContext,
//                                block,
//                                HeaderValidationMode.FULL,
//                                HeaderValidationMode.NONE);
//        if (optResult.isSuccessful()) {
//            this.protocolContext
//                    .getBlockchain()
//                    .appendBlock(block, optResult.getYield().get().getReceipts());
//            // possiblyMoveHead(block);
//            if (! this.protocolContext.getBlockchain().getChainHead().getHash().equals(block.getHash())) {
//                this.protocolContext.getBlockchain().rewindToBlock(block.getHash());
//            }
//        } else {
//            throw new RuntimeException("block validation failed " + block.getHeader().getNumber());
//        }
//    }

}

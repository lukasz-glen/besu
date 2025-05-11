package org.hyperledger.besu;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import org.hyperledger.besu.cli.DefaultCommandValues;
import org.hyperledger.besu.cli.options.EvmOptions;
import org.hyperledger.besu.components.BesuComponent;
import org.hyperledger.besu.config.GenesisConfig;
import org.hyperledger.besu.config.MergeConfiguration;
import org.hyperledger.besu.consensus.merge.MergeProtocolSchedule;
import org.hyperledger.besu.consensus.merge.PostMergeContext;
import org.hyperledger.besu.consensus.merge.TransitionContext;
import org.hyperledger.besu.consensus.merge.TransitionProtocolSchedule;
import org.hyperledger.besu.datatypes.Hash;
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
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;
import org.hyperledger.besu.ethereum.mainnet.MainnetProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProvider;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProviderBuilder;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.BonsaiWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.cache.BonsaiCachedMerkleTrieLoader;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.metrics.ObservableMetricsSystem;
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
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.hyperledger.besu.cli.config.NetworkName.MAINNET;
import static org.hyperledger.besu.controller.BesuController.DATABASE_PATH;

public class ProcessBlocks {

    private BlockchainStorage blockchainStorage;
    private ProtocolSchedule protocolSchedule;
    private ProtocolContext protocolContext;

    public static void main(final String[] args) throws IOException {
        final ProcessBlocks proc1 = new ProcessBlocks().init(ReadBlocks.DATA_PATH_1);
        final ProcessBlocks proc2 = new ProcessBlocks().init(ReadBlocks.DATA_PATH_2);
        long head = proc2.protocolContext.getBlockchain().getChainHead().getBlockHeader().getNumber();
        System.out.println("HEAD: " + head);
        for (long i = head + 1 ; i < 1_150_000 ; i ++) {
            Block block = proc1.getBlock(i);
            proc2.saveBlock(block);
            if (i % 10_000 == 0) {
                System.out.println("block " + i);
            }
        }
    }

    ProcessBlocks init(final String dataPath) {
        final Path dataDir = Paths.get(dataPath).toAbsolutePath();

        final BadBlockManager badBlockManager = new BadBlockManager();
        final ObservableMetricsSystem metricsSystem = new NoOpMetricsSystem();
        final var worldStateHealerSupplier = new AtomicReference<WorldStateArchive.WorldStateHealer>();

        final GenesisConfig genesisConfig = GenesisConfig.fromResource(MAINNET.getGenesisFile());
        final DataStorageConfiguration dataStorageConfiguration = DataStorageConfiguration.DEFAULT_BONSAI_CONFIG;
        final boolean isParallelTxProcessingEnabled = false;
        final boolean isRevertReasonEnabled = false;
        final boolean isPostMergeAtGenesis = false;
        final BesuConfigurationImpl pluginCommonConfiguration =
                new BesuConfigurationImpl()
                        .init(dataDir, dataDir.resolve(DATABASE_PATH), dataStorageConfiguration)
                        .withMiningParameters(MiningConfiguration.MINING_DISABLED);
                        // .withJsonRpcHttpOptions(jsonRpcHttpOptions);
        final EvmOptions unstableEvmOptions = EvmOptions.create();
        final EvmConfiguration evmConfiguration = unstableEvmOptions.toDomainObject();

        MergeConfiguration.setMergeEnabled(true);

        final ProtocolSchedule mainnetProtocolSchedule = MainnetProtocolSchedule.fromConfig(
                genesisConfig.getConfigOptions(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                MiningConfiguration.MINING_DISABLED,
                badBlockManager,
                isParallelTxProcessingEnabled,
                metricsSystem);
        final ProtocolSchedule mergeProtocolSchedule = MergeProtocolSchedule.create(
                genesisConfig.getConfigOptions(),
                PrivacyParameters.DEFAULT,  // may need richer configuration
                isRevertReasonEnabled,
                MiningConfiguration.MINING_DISABLED,
                badBlockManager,
                isParallelTxProcessingEnabled,
                metricsSystem);
        final PostMergeContext postMergeContext = new PostMergeContext();  // this is mergeContext
        postMergeContext
                .setSyncState(null)  // syncState.get() may need initialization
                .setTerminalTotalDifficulty(
                        genesisConfig.getConfigOptions()
                                .getTerminalTotalDifficulty()
                                .map(Difficulty::of)
                                .orElse(Difficulty.ZERO))
                .setPostMergeAtGenesis(isPostMergeAtGenesis);
        final TransitionProtocolSchedule protocolSchedule =
                new TransitionProtocolSchedule(
                        mainnetProtocolSchedule,
                        mergeProtocolSchedule,
                        postMergeContext);

        // to be checked
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
        final KeyValueStorageProvider storageProvider = new KeyValueStorageProviderBuilder()
                .withStorageFactory(storageService.getByName(DefaultCommandValues.DEFAULT_KEY_VALUE_STORAGE_NAME).orElseThrow())
                .withCommonConfiguration(pluginCommonConfiguration)
                .withMetricsSystem(metricsSystem)
                .build();

        final VariablesStorage variablesStorage = storageProvider.createVariablesStorage();
        final BlockchainStorage blockchainStorage =
                storageProvider.createBlockchainStorage(
                        protocolSchedule, variablesStorage, dataStorageConfiguration);

        final GenesisState genesisState = GenesisState.fromConfig(dataStorageConfiguration, genesisConfig, protocolSchedule);
        final MutableBlockchain blockchain =
                DefaultBlockchain.createMutable(
                        genesisState.getBlock(),
                        blockchainStorage,
                        metricsSystem,
                        6,
                        dataPath,
                        0);

        // a part of createConsensusContext()
        final OptionalLong terminalBlockNumber = genesisConfig.getConfigOptions().getTerminalBlockNumber();
        final Optional<Hash> terminalBlockHash = genesisConfig.getConfigOptions().getTerminalBlockHash();
        blockchain
                .getFinalized()
                .flatMap(blockchain::getBlockHeader)
                .ifPresent(postMergeContext::setFinalized);
        blockchain
                .getSafeBlock()
                .flatMap(blockchain::getBlockHeader)
                .ifPresent(postMergeContext::setSafeBlock);
        if (terminalBlockNumber.isPresent() && terminalBlockHash.isPresent()) {
            Optional<BlockHeader> termBlock = blockchain.getBlockHeader(terminalBlockNumber.getAsLong());
            postMergeContext.setTerminalPoWBlock(termBlock);
        }
        blockchain.observeBlockAdded(
                blockAddedEvent ->
                        blockchain
                                .getTotalDifficultyByHash(blockAddedEvent.getBlock().getHeader().getHash())
                                .ifPresent(postMergeContext::setIsPostMerge));

        final WorldStateStorageCoordinator worldStateStorageCoordinator =
                storageProvider.createWorldStateStorageCoordinator(dataStorageConfiguration);
        final BonsaiCachedMerkleTrieLoader bonsaiCachedMerkleTrieLoader = new BonsaiCachedMerkleTrieLoader(metricsSystem);
        final BonsaiWorldStateKeyValueStorage worldStateKeyValueStorage =
                worldStateStorageCoordinator.getStrategy(BonsaiWorldStateKeyValueStorage.class);
        final WorldStateArchive worldStateArchive = new BonsaiWorldStateProvider(
                worldStateKeyValueStorage,
                blockchain,
                Optional.of(
                        dataStorageConfiguration
                                .getDiffBasedSubStorageConfiguration()
                                .getMaxLayersToLoad()),
                bonsaiCachedMerkleTrieLoader,
                null, // may need besuComponent.map(BesuComponent::getBesuPluginContext).orElse(null),
                evmConfiguration,
                worldStateHealerSupplier::get);
        final var maybeStoredGenesisBlockHash = blockchainStorage.getBlockHash(0L);
        if (maybeStoredGenesisBlockHash.isEmpty()) {
            genesisState.writeStateTo(worldStateArchive.getWorldState());
        }
        final ConsensusContext preMergeConsensusContext = null;
        final ConsensusContext consensusContext = new TransitionContext(
                preMergeConsensusContext, postMergeContext);
        final ProtocolContext protocolContext =
                new ProtocolContext(blockchain, worldStateArchive, consensusContext, badBlockManager);
        protocolSchedule.setProtocolContext(protocolContext);

        this.blockchainStorage = blockchainStorage;
        this.protocolSchedule = protocolSchedule;
        this.protocolContext = protocolContext;

        return this;
    }

    Block getBlock(final long blockNumber) {
        Hash blockHash = this.blockchainStorage.getBlockHash(blockNumber).orElseThrow();
        BlockHeader header = this.blockchainStorage.getBlockHeader(blockHash).orElseThrow();
        BlockBody body = this.blockchainStorage.getBlockBody(blockHash).orElseThrow();
        return new Block(header, body);
    }

    void saveBlock(final Block block) {
        final BlockProcessingResult optResult =
                this.protocolSchedule.getByBlockHeader(block.getHeader()).getBlockValidator()
                        .validateAndProcessBlock(
                                this.protocolContext,
                                block,
                                HeaderValidationMode.FULL,
                                HeaderValidationMode.NONE);
        if (optResult.isSuccessful()) {
            this.protocolContext
                    .getBlockchain()
                    .appendBlock(block, optResult.getYield().get().getReceipts());
            // possiblyMoveHead(block);
            if (! this.protocolContext.getBlockchain().getChainHead().getHash().equals(block.getHash())) {
                this.protocolContext.getBlockchain().rewindToBlock(block.getHash());
            }
        } else {
            System.out.println(optResult);
            System.out.println(optResult.cause);
            System.out.println(optResult.errorMessage);
            System.out.println(optResult.getYield());
            System.out.println(optResult.isPartial());
            throw new RuntimeException("block validation failed " + block.getHeader().getNumber());
        }
    }
}

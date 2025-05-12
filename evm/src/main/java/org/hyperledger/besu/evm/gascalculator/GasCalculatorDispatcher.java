package org.hyperledger.besu.evm.gascalculator;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.AccessListEntry;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.frame.MessageFrame;

import java.util.List;
import java.util.function.Supplier;

public class GasCalculatorDispatcher implements GasCalculator {
    public static class BoolHolder {
        public boolean value = false;
    }

    private final GasCalculator gasCalculator;
    private final GasCalculator gasCalculatorSimulation;
    private final BoolHolder isSimulationHolder;

    public GasCalculatorDispatcher(final GasCalculator gasCalculator, final GasCalculator gasCalculatorSimulation, final BoolHolder isSimulationHolder) {
        this.gasCalculator = gasCalculator;
        this.gasCalculatorSimulation = gasCalculatorSimulation;
        this.isSimulationHolder = isSimulationHolder;
    }

    @Override
    public boolean isSimulationEnabled() {
        return true;
    }

    @Override
    public boolean isSimulation() {
        return isSimulationHolder.value;
    }

    @Override
    public void setSimulation(final boolean value) {
        isSimulationHolder.value = value;
    }

    @Override
    public long idPrecompiledContractGasCost(final Bytes input) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.idPrecompiledContractGasCost(input);
        } else {
            return gasCalculator.idPrecompiledContractGasCost(input);
        }
    }

    @Override
    public long getEcrecPrecompiledContractGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getEcrecPrecompiledContractGasCost();
        } else {
            return gasCalculator.getEcrecPrecompiledContractGasCost();
        }
    }

    @Override
    public long sha256PrecompiledContractGasCost(final Bytes input) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.sha256PrecompiledContractGasCost(input);
        } else {
            return gasCalculator.sha256PrecompiledContractGasCost(input);
        }
    }

    @Override
    public long ripemd160PrecompiledContractGasCost(final Bytes input) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.ripemd160PrecompiledContractGasCost(input);
        } else {
            return gasCalculator.ripemd160PrecompiledContractGasCost(input);
        }
    }

    @Override
    public long getZeroTierGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getZeroTierGasCost();
        } else {
            return gasCalculator.getZeroTierGasCost();
        }
    }

    @Override
    public long getVeryLowTierGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getVeryLowTierGasCost();
        } else {
            return gasCalculator.getVeryLowTierGasCost();
        }
    }

    @Override
    public long getLowTierGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getLowTierGasCost();
        } else {
            return gasCalculator.getLowTierGasCost();
        }
    }

    @Override
    public long getBaseTierGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getBaseTierGasCost();
        } else {
            return gasCalculator.getBaseTierGasCost();
        }
    }

    @Override
    public long getMidTierGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getMidTierGasCost();
        } else {
            return gasCalculator.getMidTierGasCost();
        }
    }

    @Override
    public long getHighTierGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getHighTierGasCost();
        } else {
            return gasCalculator.getHighTierGasCost();
        }
    }

    @Override
    public long callOperationBaseGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.callOperationBaseGasCost();
        } else {
            return gasCalculator.callOperationBaseGasCost();
        }
    }

    @Override
    public long callValueTransferGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.callValueTransferGasCost();
        } else {
            return gasCalculator.callValueTransferGasCost();
        }
    }

    @Override
    public long newAccountGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.newAccountGasCost();
        } else {
            return gasCalculator.newAccountGasCost();
        }
    }

    @SuppressWarnings("removal")
    @Deprecated(since = "24.2.0", forRemoval = true)
    @Override
    public long callOperationGasCost(final MessageFrame frame, final long stipend, final long inputDataOffset, final long inputDataLength, final long outputDataOffset, final long outputDataLength, final Wei transferValue, final Account recipient, final Address contract) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.callOperationGasCost(frame, stipend, inputDataOffset, inputDataLength, outputDataOffset, outputDataLength, transferValue, recipient, contract);
        } else {
            return gasCalculator.callOperationGasCost(frame, stipend, inputDataOffset, inputDataLength, outputDataOffset, outputDataLength, transferValue, recipient, contract);
        }
    }

    @Override
    public long callOperationGasCost(final MessageFrame frame, final long stipend, final long inputDataOffset, final long inputDataLength, final long outputDataOffset, final long outputDataLength, final Wei transferValue, final Account recipient, final Address contract, final boolean accountIsWarm) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.callOperationGasCost(frame, stipend, inputDataOffset, inputDataLength, outputDataOffset, outputDataLength, transferValue, recipient, contract, accountIsWarm);
        } else {
            return gasCalculator.callOperationGasCost(frame, stipend, inputDataOffset, inputDataLength, outputDataOffset, outputDataLength, transferValue, recipient, contract, accountIsWarm);
        }
    }

    @Override
    public long getAdditionalCallStipend() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getAdditionalCallStipend();
        } else {
            return gasCalculator.getAdditionalCallStipend();
        }
    }

    @Override
    public long gasAvailableForChildCall(final MessageFrame frame, final long stipend, final boolean transfersValue) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.gasAvailableForChildCall(frame, stipend, transfersValue);
        } else {
            return gasCalculator.gasAvailableForChildCall(frame, stipend, transfersValue);
        }
    }

    @Override
    public long getMinRetainedGas() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getMinRetainedGas();
        } else {
            return gasCalculator.getMinRetainedGas();
        }
    }

    @Override
    public long getMinCalleeGas() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getMinCalleeGas();
        } else {
            return gasCalculator.getMinCalleeGas();
        }
    }

    @SuppressWarnings("removal")
    @Deprecated(since = "24.4.1", forRemoval = true)
    @Override
    public long createOperationGasCost(final MessageFrame frame) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.createOperationGasCost(frame);
        } else {
            return gasCalculator.createOperationGasCost(frame);
        }
    }

    @SuppressWarnings("removal")
    @Deprecated(since = "24.4.1", forRemoval = true)
    @Override
    public long create2OperationGasCost(final MessageFrame frame) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.create2OperationGasCost(frame);
        } else {
            return gasCalculator.create2OperationGasCost(frame);
        }
    }

    @Override
    public long txCreateCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.txCreateCost();
        } else {
            return gasCalculator.txCreateCost();
        }
    }

    @Override
    public long createKeccakCost(final int initCodeLength) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.createKeccakCost(initCodeLength);
        } else {
            return gasCalculator.createKeccakCost(initCodeLength);
        }
    }

    @Override
    public long initcodeCost(final int initCodeLength) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.initcodeCost(initCodeLength);
        } else {
            return gasCalculator.initcodeCost(initCodeLength);
        }
    }

    @Override
    public long gasAvailableForChildCreate(final long stipend) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.gasAvailableForChildCreate(stipend);
        } else {
            return gasCalculator.gasAvailableForChildCreate(stipend);
        }
    }

    @Override
    public long dataCopyOperationGasCost(final MessageFrame frame, final long offset, final long length) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.dataCopyOperationGasCost(frame, offset, length);
        } else {
            return gasCalculator.dataCopyOperationGasCost(frame, offset, length);
        }
    }

    @Override
    public long memoryExpansionGasCost(final MessageFrame frame, final long offset, final long length) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.memoryExpansionGasCost(frame, offset, length);
        } else {
            return gasCalculator.memoryExpansionGasCost(frame, offset, length);
        }
    }

    @Override
    public long getBalanceOperationGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getBalanceOperationGasCost();
        } else {
            return gasCalculator.getBalanceOperationGasCost();
        }
    }

    @Override
    public long getBlockHashOperationGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getBlockHashOperationGasCost();
        } else {
            return gasCalculator.getBlockHashOperationGasCost();
        }
    }

    @Override
    public long expOperationGasCost(final int numBytes) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.expOperationGasCost(numBytes);
        } else {
            return gasCalculator.expOperationGasCost(numBytes);
        }
    }

    @Override
    public long extCodeCopyOperationGasCost(final MessageFrame frame, final long offset, final long length) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.extCodeCopyOperationGasCost(frame, offset, length);
        } else {
            return gasCalculator.extCodeCopyOperationGasCost(frame, offset, length);
        }
    }

    @Override
    public long extCodeHashOperationGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.extCodeHashOperationGasCost();
        } else {
            return gasCalculator.extCodeHashOperationGasCost();
        }
    }

    @Override
    public long getExtCodeSizeOperationGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getExtCodeSizeOperationGasCost();
        } else {
            return gasCalculator.getExtCodeSizeOperationGasCost();
        }
    }

    @Override
    public long getJumpDestOperationGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getJumpDestOperationGasCost();
        } else {
            return gasCalculator.getJumpDestOperationGasCost();
        }
    }

    @Override
    public long logOperationGasCost(final MessageFrame frame, final long dataOffset, final long dataLength, final int numTopics) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.logOperationGasCost(frame, dataOffset, dataLength, numTopics);
        } else {
            return gasCalculator.logOperationGasCost(frame, dataOffset, dataLength, numTopics);
        }
    }

    @Override
    public long mLoadOperationGasCost(final MessageFrame frame, final long offset) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.mLoadOperationGasCost(frame, offset);
        } else {
            return gasCalculator.mLoadOperationGasCost(frame, offset);
        }
    }

    @Override
    public long mStoreOperationGasCost(final MessageFrame frame, final long offset) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.mStoreOperationGasCost(frame, offset);
        } else {
            return gasCalculator.mStoreOperationGasCost(frame, offset);
        }
    }

    @Override
    public long mStore8OperationGasCost(final MessageFrame frame, final long offset) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.mStore8OperationGasCost(frame, offset);
        } else {
            return gasCalculator.mStore8OperationGasCost(frame, offset);
        }
    }

    @Override
    public long selfDestructOperationGasCost(final Account recipient, final Wei inheritance) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.selfDestructOperationGasCost(recipient, inheritance);
        } else {
            return gasCalculator.selfDestructOperationGasCost(recipient, inheritance);
        }
    }

    @Override
    public long keccak256OperationGasCost(final MessageFrame frame, final long offset, final long length) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.keccak256OperationGasCost(frame, offset, length);
        } else {
            return gasCalculator.keccak256OperationGasCost(frame, offset, length);
        }
    }

    @Override
    public long getSloadOperationGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getSloadOperationGasCost();
        } else {
            return gasCalculator.getSloadOperationGasCost();
        }
    }

    @Override
    public long calculateStorageCost(final UInt256 newValue, final Supplier<UInt256> currentValue, final Supplier<UInt256> originalValue) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.calculateStorageCost(newValue, currentValue, originalValue);
        } else {
            return gasCalculator.calculateStorageCost(newValue, currentValue, originalValue);
        }
    }

    @Override
    public long calculateStorageRefundAmount(final UInt256 newValue, final Supplier<UInt256> currentValue, final Supplier<UInt256> originalValue) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.calculateStorageRefundAmount(newValue, currentValue, originalValue);
        } else {
            return gasCalculator.calculateStorageRefundAmount(newValue, currentValue, originalValue);
        }
    }

    @Override
    public long getSelfDestructRefundAmount() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getSelfDestructRefundAmount();
        } else {
            return gasCalculator.getSelfDestructRefundAmount();
        }
    }

    @Override
    public long getColdSloadCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getColdSloadCost();
        } else {
            return gasCalculator.getColdSloadCost();
        }
    }

    @Override
    public long getColdAccountAccessCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getColdAccountAccessCost();
        } else {
            return gasCalculator.getColdAccountAccessCost();
        }
    }

    @Override
    public long getWarmStorageReadCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getWarmStorageReadCost();
        } else {
            return gasCalculator.getWarmStorageReadCost();
        }
    }

    @Override
    public boolean isPrecompile(final Address address) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.isPrecompile(address);
        } else {
            return gasCalculator.isPrecompile(address);
        }
    }

    @Override
    public long modExpGasCost(final Bytes input) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.modExpGasCost(input);
        } else {
            return gasCalculator.modExpGasCost(input);
        }
    }

    @Override
    public long codeDepositGasCost(final int codeSize) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.codeDepositGasCost(codeSize);
        } else {
            return gasCalculator.codeDepositGasCost(codeSize);
        }
    }

    @Override
    public long transactionIntrinsicGasCost(final Bytes transactionPayload, final boolean isContractCreation, final long baselineGas) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.transactionIntrinsicGasCost(transactionPayload, isContractCreation, baselineGas);
        } else {
            return gasCalculator.transactionIntrinsicGasCost(transactionPayload, isContractCreation, baselineGas);
        }
    }

    @Override
    public long transactionFloorCost(final Bytes transactionPayload) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.transactionFloorCost(transactionPayload);
        } else {
            return gasCalculator.transactionFloorCost(transactionPayload);
        }
    }

    @Override
    public long accessListGasCost(final List<AccessListEntry> accessListEntries) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.accessListGasCost(accessListEntries);
        } else {
            return gasCalculator.accessListGasCost(accessListEntries);
        }
    }

    @Override
    public long accessListGasCost(final int addresses, final int storageSlots) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.accessListGasCost(addresses, storageSlots);
        } else {
            return gasCalculator.accessListGasCost(addresses, storageSlots);
        }
    }

    @Override
    public long getMaxRefundQuotient() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getMaxRefundQuotient();
        } else {
            return gasCalculator.getMaxRefundQuotient();
        }
    }

    @Override
    public long getMinimumTransactionCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getMinimumTransactionCost();
        } else {
            return gasCalculator.getMinimumTransactionCost();
        }
    }

    @Override
    public long getTransientLoadOperationGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getTransientLoadOperationGasCost();
        } else {
            return gasCalculator.getTransientLoadOperationGasCost();
        }
    }

    @Override
    public long getTransientStoreOperationGasCost() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getTransientStoreOperationGasCost();
        } else {
            return gasCalculator.getTransientStoreOperationGasCost();
        }
    }

    @Override
    public long getBlobGasPerBlob() {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.getBlobGasPerBlob();
        } else {
            return gasCalculator.getBlobGasPerBlob();
        }
    }

    @Override
    public long blobGasCost(final long blobCount) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.blobGasCost(blobCount);
        } else {
            return gasCalculator.blobGasCost(blobCount);
        }
    }

    @Override
    public long computeExcessBlobGas(final long parentExcessBlobGas, final long blobGasUsed) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.computeExcessBlobGas(parentExcessBlobGas, blobGasUsed);
        } else {
            return gasCalculator.computeExcessBlobGas(parentExcessBlobGas, blobGasUsed);
        }
    }

    @Override
    public long delegateCodeGasCost(final int delegateCodeListLength) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.delegateCodeGasCost(delegateCodeListLength);
        } else {
            return gasCalculator.delegateCodeGasCost(delegateCodeListLength);
        }
    }

    @Override
    public long calculateDelegateCodeGasRefund(final long alreadyExistingAccountSize) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.calculateDelegateCodeGasRefund(alreadyExistingAccountSize);
        } else {
            return gasCalculator.calculateDelegateCodeGasRefund(alreadyExistingAccountSize);
        }
    }

    @Override
    public long calculateGasRefund(final Transaction transaction, final MessageFrame initialFrame, final long codeDelegationRefund) {
        if (isSimulationHolder.value) {
            return gasCalculatorSimulation.calculateGasRefund(transaction, initialFrame, codeDelegationRefund);
        } else {
            return gasCalculator.calculateGasRefund(transaction, initialFrame, codeDelegationRefund);
        }
    }
}

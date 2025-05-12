package org.hyperledger.besu.evm.gascalculator;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;

import static org.hyperledger.besu.evm.internal.Words.clampedAdd;
import static org.hyperledger.besu.evm.internal.Words.clampedMultiply;

public class Eip7904GasCalculator extends CancunGasCalculator {
    public Eip7904GasCalculator() {
    }

    public Eip7904GasCalculator(final int targetBlobsPerBlock) {
        super(targetBlobsPerBlock);
    }

    public Eip7904GasCalculator(final int maxPrecompile, final int targetBlobsPerBlock) {
        super(maxPrecompile, targetBlobsPerBlock);
    }

    /**
     * Memory cost.
     *
     * @param length the length
     * @return the cost
     */
    static long memoryCost(final long length) {
        final long lengthSquare = clampedMultiply(length, length);
        final long base =
                (lengthSquare == Long.MAX_VALUE)
                        ? clampedMultiply(length / 512, length)
                        : lengthSquare / 512;

        return base;
    }

    @Override
    public long memoryExpansionGasCost(final MessageFrame frame, final long offset, final long length) {
        final long pre = memoryCost(frame.memoryWordSize());
        final long post = memoryCost(frame.calculateMemoryExpansion(offset, length));
        if (post == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return post - pre;
    }

    @Override
    public long expOperationGasCost(final int numBytes) {
        return 4L * numBytes + 2L;
    }

    @Override
    public long getWarmStorageReadCost() {
        return 5L;
    }

    @Override
    public long getTransientLoadOperationGasCost() {
        return 5L;
    }

    @Override
    public long getTransientStoreOperationGasCost() {
        return 5L;
    }

    @Override
    public long mLoadOperationGasCost(final MessageFrame frame, final long offset) {
        return clampedAdd(1L, memoryExpansionGasCost(frame, offset, 32));
    }

    @Override
    public long mStoreOperationGasCost(final MessageFrame frame, final long offset) {
        return clampedAdd(1L, memoryExpansionGasCost(frame, offset, 32));
    }

    @Override
    public long mStore8OperationGasCost(final MessageFrame frame, final long offset) {
        return clampedAdd(1L, memoryExpansionGasCost(frame, offset, 1));
    }

    @Override
    public long dataCopyOperationGasCost(
            final MessageFrame frame, final long offset, final long length) {
        return copyWordsToMemoryGasCost(
                frame, 1L, 1L, offset, length);
    }

    @Override
    public long extCodeCopyOperationGasCost(
            final MessageFrame frame, final long offset, final long length) {
        return copyWordsToMemoryGasCost(frame, 0L, 1L, offset, length);
    }

}

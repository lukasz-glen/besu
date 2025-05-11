package org.hyperledger.besu.evm;

import org.hyperledger.besu.evm.frame.MessageFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GasUsageCoefficients {
    public enum COMPARISON_OUTCOME {
        GOOD,  // success - success, revert - revert
        BAD, // success - revert
        UNKNOWN, // revert - success
        UNKNOWN_CHILDREN_MISMATCH, // it was good but there are extra subcalls
        INVALID // something went wrong
    }
    public static final int TOO_MANY_STACK_ITEMS = 0x0101;
    public static final int INSUFFICIENT_STACK_ITEMS = 0x0102;
    public static final int EXP_OPERATION_BYTE_GAS_COST = 0x0103;
    public static final int MEMORY_WORD_GAS_COST = 0x0104;
    public static final int KECCAK256_OPERATION_WORD_GAS_COST = 0x0105;
    public static final int LOG_OPERATION_DATA_BYTE_GAS_COST = 0x0106;
    public static final int COLD_ACCOUNT_ACCESS_COST = 0x0107;
    public static final int WARM_ACCOUNT_ACCESS_COST = 0x0108;
    public static final int COPY_WORD_GAS_COST = 0x0109;
    public static final int COLD_STORAGE_ACCESS_COST = 0x010a;
    public static final int WARM_STORAGE_ACCESS_COST = 0x010b;
    public static final int INVALID_RETURN_DATA_BUFFER_ACCESS = 0x0110;  // RETURNDATACOPY
    public static final int OUT_OF_BOUNDS = 0x0111;  // RETURNDATACOPY
    public static final int INVALID_OPERATION = 0x0112;  // CREATE with EOF wrong version
    public static final int CALL_VALUE_TRANSFER_GAS_COST = 0x0113;
    public static final int NON_ZERO_TO_EMPTY_ACCOUNT_GAS_COST = 0x0114; // NEW_ACCOUNT_GAS_COST without transfer eth, see EIP161
    public static final int NON_ZERO_TO_NON_EXISTENT_ACCOUNT_GAS_COST = 0x0115; // NEW_ACCOUNT_GAS_COST without transfer eth, see EIP161
    public static final int NON_ZERO_TO_EXISTENT_ACCOUNT_GAS_COST = 0x0116; // no charge, without transfer eth, see EIP161
    public static final int ZERO_TO_EMPTY_ACCOUNT_GAS_COST = 0x0117; // NEW_ACCOUNT_GAS_COST with transfer eth, see EIP161
    public static final int ZERO_TO_NON_EXISTENT_ACCOUNT_GAS_COST = 0x0118; // NEW_ACCOUNT_GAS_COST with transfer eth, see EIP161
    public static final int ZERO_TO_EXISTENT_ACCOUNT_GAS_COST = 0x0119; // no charge, with transfer eth, see EIP161
    public static final int TX_TOTAL_GAS = 0x011a; // Total gas cost without refund
    public static final int TX_REFUND_GAS = 0x011b; // Total gas cost without refund
    public static final int TX_INITIAL_GAS = 0x011c; // TODO this is raw, it includes access lists, calldata, also contract creation tx should be handled
    public static final int NON_ZERO_CALLDATA_BYTE = 0x011d;
    public static final int ZERO_CALLDATA_BYTE = 0x011e;
    public static final int ACCESS_LIST_ADDRESS_COST = 0x011f;
    public static final int ACCESS_LIST_STORAGE_COST = 0x0120;

    public static final int PRECOMPILED_OTHER = 0x0130; // raw gas cost of a general precompile
    public static final int PRECOMPILED_ECREC = 0x0131;
    public static final int PRECOMPILED_SHA256_BASE_GAS_COST = 0x0132;
    public static final int PRECOMPILED_SHA256_WORD_GAS_COST = 0x0133;
    public static final int PRECOMPILED_RIPEMD160_BASE_GAS_COST = 0x0134;
    public static final int PRECOMPILED_RIPEMD160_WORD_GAS_COST = 0x0135;
    public static final int PRECOMPILED_ID_BASE_GAS_COST = 0x0136;
    public static final int PRECOMPILED_ID_WORD_GAS_COST = 0x0137;
    public static final int PRECOMPILED_BLAKE2BF_ROUNDS = 0x0138;
    public static final int PRECOMPILED_MOD_EXP = 0x0139; // raw gas cost of a MODEXP precompile
    public static final int PRECOMPILED_EC_ADD = 0x013a;
    public static final int PRECOMPILED_EC_MUL = 0x013b;
    public static final int PRECOMPILED_EC_PAIRING_BASE = 0x013c;
    public static final int PRECOMPILED_EC_PAIRING_PARAMETERS = 0x013d;
    public static final int PRECOMPILED_KZG_POINT_EVAL = 0x013e;

    private static final int SIZE = 0x013e + 1;

    private final int[] gasUsageCoefficients = new int[SIZE];
    private final long blockNumber;
    private final String transactionHash;
    private final int id;
    private final List<GasUsageCoefficients> children = new ArrayList<>();
    private final GasUsageCoefficients root;
    private int seq = 1;
    private MessageFrame.State state = MessageFrame.State.NOT_STARTED;
    private COMPARISON_OUTCOME comparisonToSimulation = COMPARISON_OUTCOME.INVALID;

    public GasUsageCoefficients(final long blockNumber, final String transactionHash) {
        this.blockNumber = blockNumber;
        this.transactionHash = transactionHash;
        this.id = 1;
        this.root = this;
    }

    private GasUsageCoefficients(final long blockNumber, final String transactionHash, final int id, final GasUsageCoefficients root) {
        this.blockNumber = blockNumber;
        this.transactionHash = transactionHash;
        this.id = id;
        this.root = root;
    }

    public GasUsageCoefficients spawnChild() {
        final int id = ++this.root.seq;
        final GasUsageCoefficients gasUsageCoefficients = new GasUsageCoefficients(this.blockNumber, this.transactionHash, id, this.root);
        this.children.add(gasUsageCoefficients);
        return gasUsageCoefficients;
    }

    public void addGasUsage(final int[][] newGasUsageCoefficients) {
        for (int[] newGasUsageCoefficient : newGasUsageCoefficients) {
            gasUsageCoefficients[newGasUsageCoefficient[0]] += newGasUsageCoefficient[1];
        }
    }

    public List<String> toStringsAll() {
        List<String> ret = new ArrayList<>();
        for (int i = 0 ; i < SIZE ; i ++) {
            if (gasUsageCoefficients[i] != 0) {
                String entry = String.format("%d,'%s',%d,%d,%d", blockNumber, transactionHash, id, i, gasUsageCoefficients[i]);
                ret.add(entry);
            }
        }
        for (GasUsageCoefficients child : children) {
            ret.addAll(child.toStringsAll());
        }
        return ret;
    }

    public List<String> toStringsFlat() {
        List<String> ret = new ArrayList<>();
        int[] coefficients;
        List<GasUsageCoefficients> allChildren;
        if (this.children.isEmpty()) {
            coefficients = this.gasUsageCoefficients;
            allChildren = Collections.singletonList(this);
        } else {
            coefficients = new int[SIZE];
            allChildren = new ArrayList<>();
            this.collectChildren(allChildren);
            for (GasUsageCoefficients child : allChildren) {
                for (int i = 0 ; i < SIZE ; i ++) {
                    coefficients[i] += child.gasUsageCoefficients[i];
                }
            }
        }
        for (int i = 0 ; i < SIZE ; i ++) {
            if (coefficients[i] != 0 && i != MEMORY_WORD_GAS_COST) {
                String entry = String.format("%d,'%s',%d, %d", blockNumber, transactionHash, i, coefficients[i]);
                ret.add(entry);
            }
        }
        for (GasUsageCoefficients child : allChildren) {
            if (child.gasUsageCoefficients[MEMORY_WORD_GAS_COST] != 0) {
                String entry = String.format("%d,'%s',%d, %d", blockNumber, transactionHash, MEMORY_WORD_GAS_COST, child.gasUsageCoefficients[MEMORY_WORD_GAS_COST]);
                ret.add(entry);
            }
        }
        return ret;
    }

    public void collectChildren(final List<GasUsageCoefficients> childrenList) {
        childrenList.add(this);
        for (GasUsageCoefficients child : children) {
            child.collectChildren(childrenList);
        }
    }

    public COMPARISON_OUTCOME compareState(final GasUsageCoefficients simulation) {
        for (int i = 0 ; i < this.children.size() && i < simulation.children.size() ; i ++) {
            final COMPARISON_OUTCOME result = this.children.get(i).compareState(simulation.children.get(i));
            if (result != COMPARISON_OUTCOME.GOOD) {
                return result;
            }
        }
        if (this.children.size() != simulation.children.size()) {
            return COMPARISON_OUTCOME.UNKNOWN_CHILDREN_MISMATCH;
        }
        if (this.state == MessageFrame.State.NOT_STARTED || simulation.state == MessageFrame.State.NOT_STARTED) {
            return COMPARISON_OUTCOME.INVALID;
        }
        if (this.state == MessageFrame.State.COMPLETED_SUCCESS && simulation.state == MessageFrame.State.COMPLETED_SUCCESS) {
            return COMPARISON_OUTCOME.GOOD;
        }
        if (this.state == MessageFrame.State.COMPLETED_FAILED && simulation.state == MessageFrame.State.COMPLETED_FAILED) {
            return COMPARISON_OUTCOME.GOOD;
        }
        if (this.state == MessageFrame.State.COMPLETED_FAILED && simulation.state == MessageFrame.State.COMPLETED_SUCCESS) {
            return COMPARISON_OUTCOME.UNKNOWN;
        }
        if (this.state == MessageFrame.State.COMPLETED_SUCCESS && simulation.state == MessageFrame.State.COMPLETED_FAILED) {
            return COMPARISON_OUTCOME.BAD;
        }
        return COMPARISON_OUTCOME.INVALID;
    }

    public int[] getGasUsageCoefficients() {
        return gasUsageCoefficients;
    }

    public String getTransactionHash() {
        return transactionHash;
    }

    public MessageFrame.State getState() {
        return state;
    }

    public void setState(final MessageFrame.State state) {
        this.state = state;
    }

    public COMPARISON_OUTCOME getComparisonToSimulation() {
        return comparisonToSimulation;
    }

    public void setComparisonToSimulation(final COMPARISON_OUTCOME comparisonToSimulation) {
        this.comparisonToSimulation = comparisonToSimulation;
    }

    public static int[][] aggregateGasUsageCoefficients(final List<GasUsageCoefficients> gasUsageCoefficientsList) {
        int[] coefficients = new int[SIZE];
        int[] memoryWordGasCost = new int[gasUsageCoefficientsList.size()];
        int j = 0;
        for (GasUsageCoefficients gasUsageCoefficients : gasUsageCoefficientsList) {
            for (int i = 0 ; i < SIZE ; i ++) {
                coefficients[i] += gasUsageCoefficients.gasUsageCoefficients[i];
            }
            memoryWordGasCost[j] = gasUsageCoefficients.gasUsageCoefficients[MEMORY_WORD_GAS_COST];
            j ++;
        }
        coefficients[MEMORY_WORD_GAS_COST] = 0;
        return new int[][]{coefficients, memoryWordGasCost};
    }
}

#!/usr/bin/env bash
#
# Load replay CSV exports into PostgreSQL (schema: gcr.create.sql).
#
# Usage: gcr_copy_data.sh <csv-directory>
#
# Required environment variables:
#   DB_NAME, DB_USER, DB_PASS, DB_PORT
#
# Optional:
#   DB_HOST (default: localhost)
#
set -euo pipefail

DATA_DIR="${1:?Usage: $0 <csv-directory>}"

[ ! -f .env ] || export $(grep -v '^#' .env | xargs)

: "${DB_NAME:?DB_NAME is required}"
: "${DB_USER:?DB_USER is required}"
: "${DB_PASS:?DB_PASS is required}"
: "${DB_PORT:?DB_PORT is required}"

DB_HOST="${DB_HOST:-localhost}"
export PGPASSWORD="$DB_PASS"

PSQL=(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1)

if [[ ! -d "$DATA_DIR" ]]; then
  echo "Error: directory does not exist: $DATA_DIR" >&2
  exit 1
fi

build_call_opcode_copy_columns() {
  local cols="block_id,transaction_index_in_block"
  local i hex
  for ((i = 0; i < 256; i++)); do
    printf -v hex '%02x' "$i"
    cols+=",opcode_${hex}"
  done
  cols+=",call_ordinal,call_gas_used,call_success,call_memory_word_size,exp_operation_bytes"
  cols+=",call_precompile_01,call_precompile_02,call_precompile_03,call_precompile_04,call_precompile_05"
  cols+=",call_precompile_06,call_precompile_07,call_precompile_08,call_precompile_09,call_precompile_0a"
  cols+=",call_precompile_others"
  cols+=",call_precompile_sha256_words_processed,call_precompile_ripemd160_words_processed"
  cols+=",call_precompile_id_words_processed,call_precompile_ec_pairing_params_processed"
  cols+=",call_precompile_blake2bf_rounds_processed"
  cols+=",access_address_cold_count,access_address_warm_count"
  cols+=",access_storage_cold_count,access_storage_warm_count"
  cols+=",keccak256_words_processed"
  cols+=",parent_call_ordinal"
  cols+=",call_type"
  cols+=",call_target_address"
  printf '%s' "$cols"
}

CALL_OPCODE_COPY_COLUMNS="$(build_call_opcode_copy_columns)"

copy_block_transactions() {
  local block_id="$1"
  local block_file="$2"

  # block CSV header: transactionIndexInBlock,transactionHash,succeeded,gasUsed,accessListAddressCount,accessListStorageSlotCount
  tail -n +2 "$block_file" | awk -v block_id="$block_id" '
    BEGIN { OFS = "," }
    NF > 0 { print block_id, $0 }
  ' | "${PSQL[@]}" -c \
    "COPY replay_block_transactions (block_id, transaction_index_in_block, transaction_hash, succeeded, gas_used, access_list_address_count, access_list_storage_slot_count) FROM STDIN WITH (FORMAT csv);"
}

copy_call_opcode_counts() {
  local block_id="$1"
  local txs_file="$2"

  # txs CSV: transactionIndex, then 284 usage-vector fields (empty means 0), then call_target_address; no header.
  awk -F, -v block_id="$block_id" '
    BEGIN { int_fields = 285 }
    NF > 0 {
      printf "%s", block_id
      for (i = 1; i <= int_fields; i++) {
        if (i <= NF && $i != "") {
          printf ",%s", $i
        } else {
          printf ",0"
        }
      }
      addr_idx = int_fields + 1
      if (addr_idx <= NF && $(addr_idx) != "") {
        printf ",%s", $(addr_idx)
      } else {
        printf ",0x0000000000000000000000000000000000000000"
      }
      printf "\n"
    }
  ' "$txs_file" | "${PSQL[@]}" -c \
    "COPY replay_call_opcode_counts (${CALL_OPCODE_COPY_COLUMNS}) FROM STDIN WITH (FORMAT csv);"
}

shopt -s nullglob
block_files=("$DATA_DIR"/block.*.csv)
shopt -u nullglob

if ((${#block_files[@]} == 0)); then
  echo "No block.*.csv files found in: $DATA_DIR" >&2
  exit 1
fi

mapfile -t block_files_sorted < <(
  for f in "${block_files[@]}"; do
    basename="${f##*/}"
    if [[ "$basename" =~ ^block\.([0-9]+)\.(0x[0-9a-fA-F]+)\.csv$ ]]; then
      printf '%020d %s\n' "${BASH_REMATCH[1]}" "$f"
    fi
  done | sort -n | cut -d' ' -f2-
)

if ((${#block_files_sorted[@]} == 0)); then
  echo "No files matching block.{number}.{hash}.csv in: $DATA_DIR" >&2
  exit 1
fi

imported=0
for block_file in "${block_files_sorted[@]}"; do
  basename="${block_file##*/}"
  [[ "$basename" =~ ^block\.([0-9]+)\.(0x[0-9a-fA-F]+)\.csv$ ]] || continue

  block_number="${BASH_REMATCH[1]}"
  block_hash="${BASH_REMATCH[2]}"
  txs_file="$DATA_DIR/txs.${block_number}.${block_hash}.csv"

  block_id="$("${PSQL[@]}" -t -A -c "SELECT nextval('replay_block_id_seq');")"

  "${PSQL[@]}" -c \
    "INSERT INTO replay_blocks (block_number, block_hash, block_id)
     VALUES (${block_number}, '${block_hash}', ${block_id});"

  copy_block_transactions "$block_id" "$block_file"

  if [[ -f "$txs_file" ]]; then
    copy_call_opcode_counts "$block_id" "$txs_file"
  else
    echo "Warning: missing txs file for block ${block_number}: ${txs_file}" >&2
  fi

  imported=$((imported + 1))
  if ((imported % 100 == 0)); then
    echo "Imported ${imported} blocks (last: ${block_number})"
  fi
done

echo "Done. Imported ${imported} blocks from ${DATA_DIR}"

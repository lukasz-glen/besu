ALTER TABLE replay_block_transactions ADD PRIMARY KEY(block_id,transaction_index_in_block);
CREATE INDEX call_block_tx_idx ON replay_call_opcode_counts(block_id,transaction_index_in_block);
ALTER TABLE replay_block_transactions ADD CONSTRAINT tx_block_fk FOREIGN KEY (block_id) REFERENCES replay_blocks(block_id);
ALTER TABLE replay_call_opcode_counts ADD CONSTRAINT call_tx_fk FOREIGN KEY (block_id,transaction_index_in_block) REFERENCES replay_block_transactions(block_id,transaction_index_in_block);

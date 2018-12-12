# Genesis Files 

Genesis files that contain genesis transactions to access different public networks.

Genesis File                            | Network           | Source URL
---                                     | ---               | ---
docker_pool_transactions_genesis.txt    | indy-pool         | https://github.com/hyperledger/indy-sdk/blob/master/cli/docker_pool_transactions_genesis
sovrin_test_network_genesis.txt         | Sovrin Test Net   | https://github.com/sovrin-foundation/sovrin/blob/master/sovrin/pool_transactions_sandbox_genesis

## Genesis Transactions
Each Indy ledger has a number of pre-defined transactions defining the initial pool and network.
They are called _genesis transactions_ and they are shared in _genesis files_.

- pool genesis transactions define initial trusted nodes in the pool
- domain genesis transactions define initial trusted trustees and stewards

For more information please see [official Indy documentation](https://github.com/hyperledger/indy-node/blob/e2e08fa874827fd4ba4a502f05751c761a0347ec/docs/transactions.md#genesis-transactions)
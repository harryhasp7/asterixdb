## <a id="Performance_tuning">Appendix 2. Performance tuning</a>
The SET statement can be used to override some cluster-wide configuration parameters for a specific request:

          SET <IDENTIFIER> <STRING_LITERAL>

As parameter identifiers are qualified names (containing a '.') they have to be escaped using backticks (\`\`).
Note that changing query parameters will not affect query correctness but only impact performance
characteristics, such as response time and throughput.

## <a id="Parallelism_parameter">Parallelism parameter</a>
The system can execute each request using multiple cores on multiple machines (a.k.a., partitioned parallelism)
in a cluster. A user can manually specify the maximum execution parallelism for a request to scale it up and down
using the following parameter:

*  **compiler.parallelism**: the maximum number of CPU cores can be used to process a query.
There are three cases of the value *p* for compiler.parallelism:

     - *p* \< 0 or *p* \> the total number of cores in a cluster:  the system will use all available cores in the
       cluster;

     - *p* = 0 (the default):  the system will use the storage parallelism (the number of partitions of stored datasets)
       as the maximum parallelism for query processing;

     - all other cases:  the system will use the user-specified number as the maximum number of CPU cores to use for
       executing the query.

## <a id="Memory_parameters">Memory parameters</a>
In the system, each blocking runtime operator such as join, group-by and order-by
works within a fixed memory budget, and can gracefully spill to disks if
the memory budget is smaller than the amount of data they have to hold.
A user can manually configure the memory budget of those operators within a query.
The supported configurable memory parameters are:

*  **compiler.groupmemory**: the memory budget that each parallel group-by operator instance can use;
   32MB is the default budget.

*  **compiler.sortmemory**: the memory budget that each parallel sort operator instance can use;
   32MB is the default budget.

*  **compiler.joinmemory**: the memory budget that each parallel hash join operator instance can use;
   32MB is the default budget.

For each memory budget value, you can use a 64-bit integer value
with a 1024-based binary unit suffix (e.g., B, KB, MB, GB).
If there is no user-provided suffix, "B" is the default suffix. See the following examples.

##### Example

    SET `compiler.groupmemory` "64MB"

    SELECT msg.authorId, COUNT(*)
    FROM GleambookMessages msg
    GROUP BY msg.authorId;

##### Example

    SET `compiler.sortmemory` "67108864"

    SELECT VALUE user
    FROM GleambookUsers AS user
    ORDER BY ARRAY_LENGTH(user.friendIds) DESC;

##### Example

    SET `compiler.joinmemory` "132000KB"

    SELECT u.name AS uname, m.message AS message
    FROM GleambookUsers u JOIN GleambookMessages m ON m.authorId = u.id;


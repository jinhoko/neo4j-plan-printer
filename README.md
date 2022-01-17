# neo4j-plan-printer

## Introduction

Prints neo4j logical query plan during execution. 

## Status
- Runs on `neo4j v4.3.9`

## Installation

Run the modified docker container!
```
docker run -itd --name npp TODO
```

## Usage Guide

First, run your query using `cypher-shell`.
```
docker exec -it npp "cypher-shell -u neo4j -p neo4j"
```

#### Physical(Execution) Plan Debugging

Just simply use `EXPLAIN` query of neo4j. E.g. `EXPLAIN MATCH (n) RETURN n`.

#### Logical Plan Debugging

The engine omits log to `neo4j.log` while the logical plan is generated.
```
docker run -it npp cat /root/neo4j/logs/neo4j.log
```

For each query plan, the logical plan is recorded in the following format :
```
[ 21364 ms] QG : ...
[ 21365 ms] AST: ...
[ 21366 ms] SEM: ...
[ 21367 ms] LP : ...
[ 21368 ms] LPB: ...
```
where `QG`, `AST`, `SEM`, `LP`, `LPB` represents `Query Graph`, `Normailzed AST`, `Semantic State`, `Logical Plan`, `LogicalPlanBuilder`, respectively.


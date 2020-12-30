
# Qrono

[![build](https://github.com/c2nes/qrono/workflows/build/badge.svg)](https://github.com/c2nes/qrono/actions?query=workflow%3Abuild)

Qrono is a time-ordered queue server.

> :warning: Qrono is a hobby project. **It is not production ready.**

Values in a Qrono queue are ordered by their _deadline_ and can only be dequeued once their deadline has passed. A deadline can be specified when enqueuing or requeuing a value and defaults to the current time. Values with equal deadlines are processed in FIFO order.

Qrono provides multiple interfaces. In addition to standard HTTP and gRPC interfaces, Qrono provides a Redis-compatible [RESP](https://redis.io/topics/protocol) interface allowing `redis-cli` and many existing Redis client libraries to be used.

## Queue operations

### Enqueue

```
ENQUEUE queue value [DEADLINE milliseconds-timestamp]
  (integer) id
  (integer) deadline
```

Add a `value` to the `queue` with the given deadline which defaults to now. After being enqueued the value is considered _pending_.

### Dequeue

```
DEQUEUE queue
  (integer) id
  (integer) deadline
  (integer) enqueue-time
  (integer) requeue-time
  (integer) dequeue-count
  (bulk) value
```

Dequeue the next pending value from the `queue` if available. Returns `null` if the queue is empty, or the deadline of the next value in the queue lies in the future. On success, the next pending value in the queue is moved into the _dequeued_ state.

### Requeue

```
REQUEUE queue id [DEADLINE milliseconds-timestamp]
  OK
```

Requeue a dequeued value by `id`, returning it to pending status. If not specified, deadline will default to now.

### Release

```
RELEASE queue id
  OK
```

Release a dequeued value by `id`.

### Peek

```
PEEK queue
  (integer) id
  (integer) deadline
  (integer) enqueue-time
  (integer) requeue-time
  (integer) dequeue-count
  (bulk) value
```

Returns, but does not dequeue the next pending value from the `queue`. Returns `null` if the queue is empty.

### Stat

```
STAT queue
  (integer) pending-count
  (integer) dequeued-count
```

Returns statistics for the `queue` including the number of _pending_ and _dequeued_ values. The pending count includes dequeued values.

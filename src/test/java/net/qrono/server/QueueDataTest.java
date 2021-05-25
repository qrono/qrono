package net.qrono.server;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import net.qrono.server.data.Entry;
import net.qrono.server.data.ImmutableItem;
import net.qrono.server.data.Item;
import net.qrono.server.data.Timestamp;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

@Ignore
public class QueueDataTest {
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final IOScheduler ioScheduler = new ExecutorIOScheduler(directExecutor());
  private final SegmentFlushScheduler segmentFlushScheduler =
      new SegmentFlushScheduler(1024 * 1024);

  @Test
  public void testLoad() throws IOException, InterruptedException {
    var directory = temporaryFolder.getRoot().toPath();
    var writer = new StandardSegmentWriter(directory);
    var data = new QueueData(directory, ioScheduler, writer, segmentFlushScheduler);
    var item = ImmutableItem.builder()
        .deadline(Timestamp.ZERO)
        .stats(ImmutableItem.Stats.builder()
            .enqueueTime(Timestamp.ZERO)
            .requeueTime(Timestamp.ZERO)
            .dequeueCount(0)
            .build())
        .value(Unpooled.EMPTY_BUFFER);
    for (int i = 0; i < 10 * 128 * 1024; i++) {
      data.write(Entry.newPendingEntry(item.id(i).build()));
    }
    data.stopAsync().awaitTerminated();

    // Re-open
    data = new QueueData(directory, ioScheduler, writer, segmentFlushScheduler);
    data.startAsync().awaitRunning();

    for (int i = 0; i < 10 * 128 * 1024; i++) {
      assertEquals(i, assertPending(data.next()).id());
    }

    assertNull(data.next());
  }

  @Test
  public void testLoadWithTombstons() throws IOException, InterruptedException {
    var directory = temporaryFolder.getRoot().toPath();
    var writer = new StandardSegmentWriter(directory);
    var data = new QueueData(directory, ioScheduler, writer, segmentFlushScheduler);
    var item = ImmutableItem.builder()
        .deadline(Timestamp.ZERO)
        .stats(ImmutableItem.Stats.builder()
            .enqueueTime(Timestamp.ZERO)
            .requeueTime(Timestamp.ZERO)
            .dequeueCount(0)
            .build())
        .value(Unpooled.EMPTY_BUFFER);

    for (int i = 0; i < 10; i++) {
      data.write(Entry.newPendingEntry(item.id(i).build()));
    }

    // TODO: Test freezing the current segment after we've dequeued items from it.

    assertEquals(0, assertPending(data.next()).id());

    // Force flush otherwise entry and tombstone will be merged
    // in-memory and not written to disk.
    data.flushCurrentSegment();

    assertEquals(1, assertPending(data.next()).id());
    assertEquals(2, assertPending(data.next()).id());

    var entry = data.next();
    assertEquals(3, assertPending(entry).id());

    // Tombstone entry 3
    data.write(Entry.newTombstoneEntry(entry.key()));

    // Close and re-open
    data.stopAsync().awaitTerminated();
    data = new QueueData(directory, ioScheduler, writer, segmentFlushScheduler);
    data.startAsync().awaitRunning();

    assertEquals(0, assertPending(data.next()).id());
    assertEquals(1, assertPending(data.next()).id());
    assertEquals(2, assertPending(data.next()).id());
    // Item ID 3 was tombstoned and should not appear!
    // assertEquals(3, assertPending(data.next()).id());
    assertEquals(4, assertPending(data.next()).id());
    assertEquals(5, assertPending(data.next()).id());
  }

  static Item assertPending(Entry entry) {
    Item item = entry.item();
    assertNotNull(item);
    assertTrue(entry.isPending());
    return item;
  }
}
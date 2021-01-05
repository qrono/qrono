package net.qrono.server;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.Comparator.comparing;

import com.google.common.util.concurrent.AbstractScheduledService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueueManager extends AbstractScheduledService {
  private static final Logger log = LoggerFactory.getLogger(QueueManager.class);
  private static final QueueWrapper NULL_QUEUE = QueueWrapper.wrap(null);
  private static final QueueWrapper DELETING_QUEUE =
      QueueWrapper.unavailable("queue is being deleted");

  private final Path directory;
  private final IdGenerator idGenerator;
  private final IOScheduler ioScheduler;
  private final WorkingSet workingSet;
  private final SegmentFlushScheduler segmentFlushScheduler;

  private final Map<String, QueueWrapper> queues = new HashMap<>();

  public QueueManager(
      Path directory,
      IdGenerator idGenerator,
      IOScheduler ioScheduler,
      WorkingSet workingSet,
      SegmentFlushScheduler segmentFlushScheduler
  ) {
    this.directory = directory;
    this.idGenerator = idGenerator;
    this.ioScheduler = ioScheduler;
    this.workingSet = workingSet;
    this.segmentFlushScheduler = segmentFlushScheduler;

    addListener(new Listener() {
      @Override
      public void failed(State from, Throwable failure) {
        log.error("QueueManager failed (was {}). Compactions will not run.", from, failure);
      }
    }, directExecutor());
  }

  @Override
  protected synchronized void startUp() throws Exception {
    Files.list(directory).forEach(entry -> {
      if (Files.isDirectory(entry)) {
        var queueName = entry.getFileName().toString();
        queues.put(queueName, QueueWrapper.wrap(createQueue(queueName)));
      }
    });
  }

  public synchronized Queue getQueue(String queueName) {
    return queues.getOrDefault(queueName, NULL_QUEUE).unwrap();
  }

  public synchronized Queue getOrCreateQueue(String queueName) {
    Queue queue = queues.getOrDefault(queueName, NULL_QUEUE).unwrap();
    if (queue == null) {
      queue = createQueue(queueName);
      queues.put(queueName, QueueWrapper.wrap(queue));
    }
    return queue;
  }

  public void deleteQueue(String queueName) throws IOException {
    Queue queue;

    synchronized (this) {
      queue = queues.getOrDefault(queueName, NULL_QUEUE).unwrap();
      if (queue == null) {
        return;
      }
      queues.put(queueName, DELETING_QUEUE);
    }

    try {
      queue.stopAsync().awaitTerminated();
      queue.delete();
    } finally {
      synchronized (this) {
        queues.remove(queueName);
      }
    }
  }

  private Queue createQueue(String name) {
    var queueDirectory = directory.resolve(name);
    var segmentWriter = new StandardSegmentWriter(queueDirectory);

    var queueData = new QueueData(
        queueDirectory,
        ioScheduler,
        segmentWriter,
        segmentFlushScheduler);

    var queue = new Queue(queueData, idGenerator, Clock.systemUTC(), workingSet);
    queue.startAsync().awaitRunning();

    return queue;
  }

  private static long compactionScore(Queue queue) {
    return queue.getQueueInfo().storageStats().persistedTombstoneCount();
  }

  @Override
  protected void runOneIteration() throws Exception {
    var maybeQueue = queues.values()
        .stream()
        .filter(QueueWrapper::isAvailable)
        .map(QueueWrapper::unwrap)
        .max(comparing(QueueManager::compactionScore));

    if (maybeQueue.isPresent()) {
      var queue = maybeQueue.get();
      queue.compact();
    }
  }

  @Override
  protected Scheduler scheduler() {
    return Scheduler.newFixedRateSchedule(
        Duration.ofMinutes(1),
        Duration.ofMinutes(1));
  }

  private static class QueueWrapper {
    private final Queue queue;
    private final String message;

    private QueueWrapper(Queue queue, String message) {
      this.queue = queue;
      this.message = message;
    }

    Queue unwrap() {
      if (message != null) {
        throw new QronoException(message);
      }

      return queue;
    }

    boolean isAvailable() {
      return message == null;
    }

    static QueueWrapper wrap(Queue queue) {
      return new QueueWrapper(queue, null);
    }

    static QueueWrapper unavailable(String message) {
      return new QueueWrapper(null, message);
    }
  }
}

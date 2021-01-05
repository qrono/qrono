package net.qrono.server;

import static java.util.concurrent.CompletableFuture.completedFuture;

import com.google.common.util.concurrent.AbstractIdleService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class QueueService extends AbstractIdleService {
  private final Path directory;
  private final QueueFactory factory;
  private final Map<String, QueueWrapper> queues = new HashMap<>();

  public QueueService(Path directory, QueueFactory factory) {
    this.directory = directory;
    this.factory = factory;
  }

  @Override
  protected synchronized void startUp() throws Exception {
    Files.list(directory).forEach(entry -> {
      if (Files.isDirectory(entry)) {
        var queueName = entry.getFileName().toString();
        queues.put(queueName, new QueueWrapper(factory.createQueue(queueName)));
      }
    });
  }

  @Override
  protected void shutDown() throws Exception {

  }

  private synchronized QueueWrapper getQueue(String queueName) {
    return queues.get(queueName);
  }

  private synchronized QueueWrapper getOrCreateQueue(String queueName) {
    QueueWrapper queue = queues.get(queueName);
    if (queue == null) {
      queue = new QueueWrapper(factory.createQueue(queueName));
      queues.put(queueName, queue);
    }
    return queue;
  }

  public <T> CompletableFuture<T> withExistingQueue(
      String queueName, Function<Queue, CompletableFuture<T>> operation
  ) {
    // TODO: Should this return an error instead of null on a missing queue?
    while (true) {
      QueueWrapper wrapper = getQueue(queueName);
      if (wrapper == null) {
        return completedFuture(null);
      }

      var result = wrapper.apply(operation);
      if (result.isPresent()) {
        return result.get();
      }
    }
  }

  public <T> CompletableFuture<T> withQueue(
      String queueName, Function<Queue, CompletableFuture<T>> operation
  ) {
    while (true) {
      var result = getOrCreateQueue(queueName).apply(operation);
      if (result.isPresent()) {
        return result.get();
      }
    }
  }

  public void deleteQueue(String queueName) {
    withExistingQueue(queueName, queue -> {
      try {
        queues.get(queueName).deleted = true;
        queue.stopAsync().awaitTerminated();
        queue.delete();
        queues.remove(queueName);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }

      return null;
    });
  }

  private static class QueueWrapper {
    private final Queue queue;
    private boolean deleted = false;

    private QueueWrapper(Queue queue) {
      this.queue = queue;
    }

    public <T> Optional<T> apply(Function<Queue, T> f) {
      if (deleted) {
        return Optional.empty();
      }
      return Optional.of(f.apply(queue));
    }
  }
}

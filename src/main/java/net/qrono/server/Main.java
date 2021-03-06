package net.qrono.server;

import static java.lang.Math.toIntExact;

import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import net.qrono.server.grpc.QueueServerService;
import net.qrono.server.redis.RedisChannelInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

  private static final Logger log = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws IOException, InterruptedException {
    Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
      log.error("Caught unhandled exception. Terminating; thread={}", thread, ex);
      System.exit(1);
    });

    DefaultExports.initialize();

    var config = Config.load();
    log.info("Config {}", config);

    Path root = config.dataRoot();
    Files.createDirectories(root);

    ExecutorTaskScheduler ioScheduler = new ExecutorTaskScheduler(
        Executors.newFixedThreadPool(4, new ThreadFactoryBuilder()
            .setDaemon(true)
            .setNameFormat("Qrono-IOWorker-%d")
            .build()));

    TaskScheduler cpuScheduler = new ExecutorTaskScheduler(ForkJoinPool.commonPool());

    Path queuesDirectory = root.resolve(config.dataQueuesDir());
    Files.createDirectories(queuesDirectory);

    var idGenerator = new PersistentIdGenerator(root.resolve("last-id"));
    idGenerator.startAsync().awaitRunning();

    Path workingSetDirectory = root.resolve(config.dataWorkingSetDir());

    var workingSet = new DiskBackedWorkingSet(
        workingSetDirectory,
        toIntExact(config.dataWorkingSetMappedFileSize().bytes()),
        ioScheduler);

    workingSet.startAsync().awaitRunning();

    var segmentFlushScheduler = new SegmentFlushScheduler(config.segmentFlushThreshold().bytes());
    var queueFactory = new QueueFactory(
        queuesDirectory,
        idGenerator,
        ioScheduler,
        cpuScheduler,
        workingSet,
        segmentFlushScheduler);
    var queueManager = new QueueManager(queuesDirectory, queueFactory);
    queueManager.startAsync().awaitRunning();

    QueueServerService service = new QueueServerService(queueManager);

    Server server =
        NettyServerBuilder.forAddress(toSocketAddress(config.netGrpcListen()))
            .addService(service)
            .build();

    // -----------------------------------------------------------------------
    // Netty Redis
    // -----------------------------------------------------------------------

    var parentGroup = new NioEventLoopGroup();
    var childGroup = new NioEventLoopGroup();
    var redisServer = new ServerBootstrap()
        .group(parentGroup, childGroup)
        .channel(NioServerSocketChannel.class)
        .childOption(ChannelOption.SO_KEEPALIVE, true)
        .childHandler(new RedisChannelInitializer(queueManager));

    var channelFuture = redisServer
        .bind(toSocketAddress(config.netRespListen()))
        .sync();

    // -----------------------------------------------------------------------
    // gRPC Gateway (HTTP interface)
    // -----------------------------------------------------------------------

    if (config.netHttpGatewayPath().isPresent()) {
      var gatewayPath = config.netHttpGatewayPath().get();
      if (Files.isExecutable(gatewayPath)) {
        var cmd = List.of(
            gatewayPath.toString(),
            "-target", config.netGrpcListen().toString(),
            "-listen", config.netHttpListen().toString()
        );

        new ProcessBuilder(cmd).inheritIO().start();
      }
    }

    // -----------------------------------------------------------------------
    // Prometheus exporter
    // -----------------------------------------------------------------------

    var metricsServer = new HTTPServer(
        toSocketAddress(config.netMetricsListen()),
        CollectorRegistry.defaultRegistry);

    // -----------------------------------------------------------------------

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        server.shutdown().awaitTermination();
        metricsServer.stop();
      } catch (InterruptedException e) {
        log.error("BUG: Unexpected InterruptedException while shutting down");
      }
    }));

    server.start().awaitTermination();
  }

  @SuppressWarnings("UnstableApiUsage")
  private static InetSocketAddress toSocketAddress(HostAndPort hostAndPort) {
    return new InetSocketAddress(hostAndPort.getHost(), hostAndPort.getPort());
  }
}

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.config.OptionsMap;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class GetSsTablesCrash {

    public static void main(String[] args) throws IOException, InterruptedException {
        final MetricRegistry metrics = new MetricRegistry();
        final ConsoleReporter reporter = ConsoleReporter.forRegistry(metrics).build();
        reporter.start(5, TimeUnit.SECONDS);

        final int maxParallelism = 1000;

        int hostport;
        try (final ServerSocket s = new ServerSocket(0)) {
            hostport = s.getLocalPort();
        }

        Process runContainer =
            new ProcessBuilder(
                "docker",
                "run",
                "--name=crash-test",
                "--expose=10000",
                "-p",
                hostport + ":19042",
                "--expose=22",
                "--expose=7000",
                "--expose=7001",
                "-p",
                "9042",
                "--expose=9160",
                "--expose=9180",
                "--rm",
                "scylladb/scylla:latest",
                "--smp",
                "2"
            ).inheritIO()
                .start();

        // exit if the container fails to be created
        if (runContainer.waitFor(1L, TimeUnit.SECONDS) && runContainer.exitValue() != 0) {
            System.exit(runContainer.exitValue());
        }

        // wait for Scylla to become available.
        try (final CqlSession session = getSession(hostport)) {
            session.execute("CREATE KEYSPACE tests WITH replication={'class':'SimpleStrategy','replication_factor':1}");
            session.execute("CREATE TABLE tests.slow (key bigint PRIMARY KEY)");
        }

        // unset when inserting should stop
        final AtomicBoolean c = new AtomicBoolean(true);

        final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        executor.execute(() -> GetSsTablesCrash.insertForever(metrics, c, hostport, maxParallelism));

        // flushing periodically aggrevates the issue
        executor.scheduleWithFixedDelay(() ->
            {
                try {
                    Runtime.getRuntime().exec(
                        new String[] {
                            "docker",
                            "exec",
                            "crash-test",
                            "nodetool",
                            "flush"
                        }
                    );
                } catch (IOException e) {
                    e.printStackTrace();
                }
            },
            5L,
            5L,
            TimeUnit.SECONDS
        );

        // Run nodetool until Scylla crashes.
        Failsafe.with(
            RetryPolicy.<Integer>builder()
                .withMaxAttempts(-1)
                .withDelay(Duration.ofSeconds(1))
                .handleResultIf(result -> result == 0)
                .build()
        ).get(() -> {
            /*
            When this fails, the exit code is 1 and the output is

            nodetool: Unable to connect to Scylla API server: java.net.ConnectException: Connection refused (Connection refused)
            See 'nodetool help' or 'nodetool help <command>'.
             */
            final Process getsstables =
                new ProcessBuilder(
                    "docker",
                    "exec",
                    "crash-test",
                    "nodetool",
                    "getsstables",
                    "tests",
                    "slow",
                    "123"
                ).inheritIO().start();
            getsstables.waitFor();
            return getsstables.exitValue();
        });
        c.set(false);

        Runtime.getRuntime().exec("docker stop crash-test");
        Runtime.getRuntime().exec("docker rm crash-test");
        executor.shutdown();
    }

    public static CqlSession getSession(final int hostport) {
        final DriverConfigLoader driverConfigLoader = DriverConfigLoader.fromMap(OptionsMap.driverDefaults());
        return Failsafe.with(
            RetryPolicy.<CqlSession>builder()
                .withMaxAttempts(300)
                .withDelay(Duration.ofSeconds(1))
                .build()
        ).get(() ->
                CqlSession.builder()
                    .addContactPoint(new InetSocketAddress("localhost", hostport))
                    .withConfigLoader(driverConfigLoader)
                    .withLocalDatacenter("datacenter1")
                    .build()
        );
    }

    public static void insertForever(
        final MetricRegistry metrics,
        final AtomicBoolean c,
        final int hostport,
        final int maxParallelism
    ) {
        final Meter insertMeter = metrics.meter("insert");

        try (final CqlSession session = getSession(hostport)) {
            final Semaphore tickets = new Semaphore(maxParallelism);

            final PreparedStatement insert =
                session.prepare("INSERT INTO tests.slow (key) VALUES (?)");

            long i = 0;
            while (c.get()) {
                try {
                    tickets.acquire();
                } catch (InterruptedException e) { }
                session.executeAsync(insert.bind(i))
                    .whenComplete((result, exception) -> {
                        tickets.release();
                        insertMeter.mark();
                    });
                i++;
            }
        }
    }

}

package uk.sky.cirrus;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.QueryOptions;
import com.datastax.driver.core.Session;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.sky.cirrus.locking.Lock;
import uk.sky.cirrus.locking.LockConfig;
import uk.sky.cirrus.locking.LockService;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Standard implementation for {@code CqlMigrator}
 *
 * Implements Locking by providing mutex locking via the locks table.
 * Client tries to acquire the lock and would only succeed for one thread.
 * The inserted record will be deleted once the client releases the lock it holds, hence making way for a subsequent client thread to acquire the lock.
 * Locking is done per client per keyspace. A client can obtain a lock on a different keyspace if no other client is holding a lock for that keyspace.
 *
 */

public final class CqlMigratorImpl implements CqlMigrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(CqlMigratorImpl.class);

    private final LockConfig lockConfig;

    public CqlMigratorImpl(LockConfig lockConfig) {
        this.lockConfig = lockConfig;
    }

    /**
     * Allows cql migrate to be run on the command line.
     * Set hosts, keyspace and directories using system properties.
     *
     * @param args
     */
    public static void main(String[] args) {

        final String hosts = System.getProperty("hosts");
        final String keyspace = System.getProperty("keyspace");
        final String directoriesProperty = System.getProperty("directories");
        final String port = System.getProperty("port");

        Preconditions.checkNotNull(hosts, "'hosts' property should be provided having value of a comma separated list of cassandra hosts");
        Preconditions.checkNotNull(keyspace, "'keyspace' property should be provided having value of the cassandra keyspace");
        Preconditions.checkNotNull(directoriesProperty, "'directories' property should be provided having value of the comma separated list of paths to cql files");

        final Collection<Path> directories = Arrays.stream(directoriesProperty.split(","))
                .map(Paths::get)
                .collect(Collectors.toList());

        new CqlMigratorImpl(LockConfig.builder().build())
                .migrate(hosts.split(","), port == null ? 9042 : Integer.parseInt(port), keyspace, directories);
    }

    /**
     * {@inheritDoc}
     */
    public void migrate(String[] hosts, int port, String keyspace, Collection<Path> directories) {

        try (Cluster cluster = createCluster(hosts, port);
             Session session = cluster.connect()) {
            this.migrate(session, keyspace, directories);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void migrate(Session session, String keyspace, Collection<Path> directories) {
        final Cluster cluster = session.getCluster();
        ClusterHealth clusterHealth = new ClusterHealth(cluster);
        clusterHealth.check();

        Lock lock = LockService.acquire(lockConfig, keyspace, session);

        LOGGER.info("Loading cql files from {}", directories);
        CqlPaths paths = CqlPaths.create(directories);
        final KeyspaceBootstrapper keyspaceBootstrapper = new KeyspaceBootstrapper(session, keyspace, paths);
        final SchemaUpdates schemaUpdates = new SchemaUpdates(session, keyspace);
        final SchemaLoader schemaLoader = new SchemaLoader(session, keyspace, schemaUpdates, paths);

        keyspaceBootstrapper.bootstrap();
        schemaUpdates.initialise();
        schemaLoader.load();
        lock.release();
    }

    /**
     * {@inheritDoc}
     */
    public void clean(String[] hosts, int port, String keyspace) {
        try (Cluster cluster = createCluster(hosts, port);
             Session session = cluster.connect()) {
            this.clean(session, keyspace);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void clean(Session session, String keyspace) {
        session.execute("DROP KEYSPACE IF EXISTS " + keyspace);
        LOGGER.info("Cleaned {}", keyspace);
    }

    private Cluster createCluster(String[] hosts, int port) {
        QueryOptions queryOptions = new QueryOptions();
        queryOptions.setConsistencyLevel(ConsistencyLevel.ALL);

        return Cluster.builder()
                .addContactPoints(hosts)
                .withPort(port)
                .withQueryOptions(queryOptions)
                .build();
    }
}

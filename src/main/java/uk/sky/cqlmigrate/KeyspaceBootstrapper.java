package uk.sky.cqlmigrate;

import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

class KeyspaceBootstrapper {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeyspaceBootstrapper.class);

    private final Session session;
    private final String keyspace;
    private final CqlPaths paths;

    KeyspaceBootstrapper(Session session, String keyspace, CqlPaths paths) {
        this.session = session;
        this.keyspace = keyspace;
        this.paths = paths;
    }

    void bootstrap() {
        KeyspaceMetadata keyspaceMetadata = session.getCluster().getMetadata().getKeyspace(keyspace);
        if (keyspaceMetadata == null) {
            paths.applyBootstrap((filename, path) -> {
                LOGGER.info("Keyspace not found, applying {}", path);
                List<String> cqlStatements = CqlFileParser.getCqlStatementsFrom(path);
                CqlLoader.load(session, cqlStatements);
                LOGGER.info("Applied: bootstrap.cql");
            });
        } else {
            LOGGER.info("Keyspace found, not applying bootstrap.cql");
        }
    }

}

package uk.sky.cqlmigrate;

import com.datastax.driver.core.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

class SchemaLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaLoader.class);

    private final Session session;
    private final String keyspace;
    private final SchemaUpdates schemaUpdates;
    private final CqlPaths paths;

    SchemaLoader(Session session, String keyspace, SchemaUpdates schemaUpdates, CqlPaths paths) {
        this.session = session;
        this.keyspace = keyspace;
        this.schemaUpdates = schemaUpdates;
        this.paths = paths;
    }

    void load() {
        session.execute("USE " + keyspace + ";");
        paths.applyInSortedOrder(new Loader());
    }

    private class Loader implements CqlPaths.Function {
        @Override
        public void apply(String filename, Path path) {
            if (schemaUpdates.alreadyApplied(filename)) {
                if (schemaUpdates.contentsAreDifferent(filename, path)) {
                    LOGGER.error("Contents have changed: {}", path.getFileName());
                    throw new IllegalStateException("Contents have changed for " + filename + " at " + path);
                } else {
                    LOGGER.info("Skipped: {}", path.getFileName());
                }
            } else {
                String lowercasePath = path.toString().toLowerCase();
                if (lowercasePath.endsWith(".cql")) {
                    List<String> cqlStatements = CqlFileParser.getCqlStatementsFrom(path);
                    CqlLoader.load(session, cqlStatements);
                } else {
                    throw new IllegalArgumentException("Unrecognised file type: " + path);
                }

                schemaUpdates.add(filename, path);
                LOGGER.info("Applied: {}", path.getFileName());
            }
        }
    }
}
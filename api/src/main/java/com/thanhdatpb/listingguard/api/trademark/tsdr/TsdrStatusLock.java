package com.thanhdatpb.listingguard.api.trademark.tsdr;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * The PostgreSQL advisory lock of the {@code tsdr-status} job (ADR-009, "TSDR status check"): one run at a time, so two runs
 * never ask about the same mark. The key pair {@code (1414743122, 1398030676)} ("TSDR", "STAT") is reserved for it; no other
 * kind of query may take it (the retention cleanup and the USPTO sync have pairs of their own).
 *
 * <p>It is a session lock held on a connection of its own for as long as the holder runs, so a crashed process releases it when
 * its connection closes.
 */
@Component
public class TsdrStatusLock {

    /** "TSDR" */
    public static final int KEY_HIGH = 1414743122;
    /** "STAT" */
    public static final int KEY_LOW = 1398030676;

    private final DataSource dataSource;

    public TsdrStatusLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** The lock, held until closed; empty when another session holds it. */
    public Optional<Held> tryAcquire() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
            boolean locked;
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
                statement.setInt(1, KEY_HIGH);
                statement.setInt(2, KEY_LOW);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    locked = rows.getBoolean(1);
                }
            }
            if (!locked) {
                connection.close();
                return Optional.empty();
            }
            return Optional.of(new Held(connection));
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("Cannot take the TSDR status lock: " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // nothing else to release
            }
        }
    }

    /** The held lock; closing it releases the lock and returns the connection. */
    public static final class Held implements AutoCloseable {
        private final Connection connection;
        private boolean closed;

        private Held(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
                statement.setInt(1, KEY_HIGH);
                statement.setInt(2, KEY_LOW);
                statement.execute();
            } catch (SQLException ignored) {
                // the lock goes with the session when the connection is closed below
            } finally {
                closeQuietly(connection);
            }
        }
    }
}

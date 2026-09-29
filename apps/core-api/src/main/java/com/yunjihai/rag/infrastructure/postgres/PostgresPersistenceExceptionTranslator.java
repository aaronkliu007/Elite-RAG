package com.yunjihai.rag.infrastructure.postgres;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.sql.SQLException;
import java.util.Objects;

/** Converts persistence failures to fixed public codes without retaining driver details. */
public final class PostgresPersistenceExceptionTranslator {
    private PostgresPersistenceExceptionTranslator() {
    }

    public static Failure translate(DataAccessException failure) {
        Objects.requireNonNull(failure);
        if (failure instanceof OptimisticLockingFailureException) {
            return new Failure("OPTIMISTIC_LOCK");
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return switch (sql.getSQLState() == null ? "" : sql.getSQLState()) {
                    case "23505" -> new Failure("UNIQUE_CONSTRAINT");
                    case "23503" -> new Failure("FOREIGN_KEY_CONSTRAINT");
                    case "23514" -> new Failure("CHECK_CONSTRAINT");
                    default -> new Failure("DATABASE_FAILURE");
                };
            }
        }
        return new Failure("DATABASE_FAILURE");
    }

    public static final class Failure extends RuntimeException {
        private final String code;

        private Failure(String code) {
            super(code, null, false, true);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

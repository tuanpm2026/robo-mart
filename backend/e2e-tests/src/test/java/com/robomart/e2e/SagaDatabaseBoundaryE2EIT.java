package com.robomart.e2e;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

class SagaDatabaseBoundaryE2EIT {
    @Test
    void sagaRoleCanUseItsDatabaseButCannotConnectToParticipantDatabases() throws Exception {
        String postgres = E2eSupport.container("postgres");
        for (String database :
                List.of(
                        "saga_db",
                        "order_db",
                        "payment_db",
                        "inventory_db",
                        "product_db",
                        "notification_db")) {
            var file = Files.createTempFile("saga-privilege-", ".log");
            try {
                Process process =
                        new ProcessBuilder(
                                        E2eSupport.dockerExecutable(),
                                        "exec",
                                        "-e",
                                        "PGOPTIONS=-c default_transaction_read_only=on",
                                        postgres,
                                        "psql",
                                        "-X",
                                        "-A",
                                        "-t",
                                        "-U",
                                        "saga",
                                        "-d",
                                        database,
                                        "-c",
                                        "SELECT rolsuper FROM pg_roles WHERE rolname=current_user")
                                .redirectErrorStream(true)
                                .redirectOutput(file.toFile())
                                .start();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
                if (database.equals("saga_db")) {
                    assertThat(process.exitValue()).isZero();
                    assertThat(Files.readString(file).strip()).isEqualTo("f");
                } else {
                    assertThat(process.exitValue()).isNotZero();
                    assertThat(Files.readString(file)).contains("permission denied");
                }
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }
}

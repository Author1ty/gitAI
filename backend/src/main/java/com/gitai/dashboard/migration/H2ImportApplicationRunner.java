package com.gitai.dashboard.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Locale;

/** Runs only with the explicit h2-import profile; normal application starts can never invoke this importer. */
@Component
@Profile("h2-import")
public class H2ImportApplicationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(H2ImportApplicationRunner.class);

    private final DataSource targetDataSource;
    private final H2ImportProperties properties;

    public H2ImportApplicationRunner(DataSource targetDataSource, H2ImportProperties properties) {
        this.targetDataSource = targetDataSource;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("H2 import profile is active, but git-ai.h2-import.enabled is not true");
        }
        if (!H2ImportProperties.CONFIRMATION_PHRASE.equals(properties.getConfirm())) {
            throw new IllegalStateException("H2 import requires git-ai.h2-import.confirm=" + H2ImportProperties.CONFIRMATION_PHRASE);
        }
        try (Connection connection = targetDataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (!product.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("H2 import requires the mysql profile and a MySQL target; detected " + product);
            }
        }
        log.warn("Starting one-time H2-to-MySQL data import. No Git command, clone, fetch, note parsing, or synchronization will be performed.");
        H2ToMySqlDataImporter.MigrationReport report = new H2ToMySqlDataImporter(targetDataSource,
                properties.getSourceUrl(), properties.getSourceUsername(), properties.getSourcePassword(), properties.getBatchSize()).importData();
        log.warn("H2-to-MySQL import completed successfully: tables={}, rows={}, elapsedMs={}. Automatic synchronization remains disabled.",
                report.tables().size(), report.totalRows(), report.elapsedMillis());
        report.tables().forEach(table -> log.info("H2 import report table={} sourceRows={} targetRows={} maxId={} elapsedMs={}",
                table.table(), table.sourceRows(), table.targetRows(), table.maxId(), table.elapsedMillis()));
    }
}

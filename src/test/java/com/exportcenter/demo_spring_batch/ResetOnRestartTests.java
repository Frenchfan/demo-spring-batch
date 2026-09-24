package com.exportcenter.demo_spring_batch;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ResetOnRestartTests {
    @Test
    void restartDiscardsAllTablesAndCollectionsAndReseedsBothDatabases() {
        String originalTitle;
        try (var context = start()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var mongo = context.getBean(MongoTemplate.class);
            originalTitle = jdbc.queryForObject("SELECT title FROM TEST.company WHERE id = 1", String.class);
            jdbc.execute("CREATE TABLE TEST.restart_marker (id INT)");
            jdbc.execute("CREATE TABLE PUBLIC.restart_marker (id INT)");
            jdbc.update("""
                    INSERT INTO TEST.BATCH_JOB_INSTANCE (VERSION, JOB_NAME, JOB_KEY)
                    VALUES (0, 'previous-run', 'restart-marker')
                    """);
            jdbc.update("UPDATE TEST.company SET title = 'Changed during demo' WHERE id = 1");
            mongo.getCollection("restart_marker").insertOne(new Document("marker", true));
            mongo.getCollection("subsidy_applications").updateOne(new Document("subsidyId", 1L),
                    new Document("$set", new Document("company.title", originalTitle)));
        }
        try (var context = start()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var mongo = context.getBean(MongoTemplate.class);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'RESTART_MARKER'
                    """, Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT title FROM TEST.company WHERE id = 1", String.class))
                    .isEqualTo(originalTitle);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEST.subsidy", Long.class)).isEqualTo(1500);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEST.BATCH_JOB_INSTANCE", Long.class)).isZero();
            assertThat(mongo.collectionExists("restart_marker")).isFalse();
            var collection = mongo.getCollection("subsidy_applications");
            assertThat(collection.countDocuments()).isEqualTo(1500);
            assertThat(collection.find(new Document("subsidyId", 1L)).first()
                    .get("company", Document.class).getString("title"))
                    .isEqualTo("  " + originalTitle + "  ");
        }
    }

    private ConfigurableApplicationContext start() {
        // Use application.yaml storage settings to verify the real default lifecycle.
        return SpringApplication.run(DemoSpringBatchApplication.class,
                "--spring.main.web-application-type=none", "--spring.mongodb.port=0",
                "--logging.level.root=WARN");
    }
}

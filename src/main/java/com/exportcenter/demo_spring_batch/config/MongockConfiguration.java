package com.exportcenter.demo_spring_batch.config;

import com.mongodb.client.MongoClient;
import io.mongock.driver.mongodb.sync.v4.driver.MongoSync4Driver;
import io.mongock.runner.standalone.MongockStandalone;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class MongockConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ApplicationRunner seedMongo(MongoClient client, MongoTemplate mongo, JdbcTemplate jdbc) {
        return args -> MongockStandalone.builder()
                .setDriver(MongoSync4Driver.withDefaultLock(client, mongo.getDb().getName()))
                .addMigrationScanPackage("com.exportcenter.demo_spring_batch.migration")
                .addDependency(JdbcTemplate.class, jdbc)
                // Embedded MongoDB is standalone, without replica-set transactions.
                .setTransactional(false)
                .buildRunner()
                .execute();
    }
}

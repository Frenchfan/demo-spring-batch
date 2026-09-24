package com.exportcenter.demo_spring_batch.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;

/** A frozen v1 demo scenario. Add another change unit to evolve an installed dataset. */
@ChangeUnit(id = "seed-subsidy-applications-v1", order = "001", author = "demo")
public class V001__SeedSubsidyApplications {
    public static final String COLLECTION = "subsidy_applications";
    public static final String DATASET = "subsidies-v1";

    @Execution
    public void execute(MongoDatabase database, JdbcTemplate jdbc) {
        var collection = database.getCollection(COLLECTION);
        List<Document> documents = readSource(jdbc);
        if (documents.size() != 1500) {
            throw new IllegalStateException("Expected 1500 Flyway demo subsidies, got " + documents.size());
        }
        collection.createIndex(new Document("subsidyId", 1), new IndexOptions().unique(true));
        for (Document document : documents) {
            introduceError(document);
            // Retry after partial failure never overwrites records already fixed by Batch.
            collection.updateOne(eq("_id", document.get("_id")),
                    new Document("$setOnInsert", document), new UpdateOptions().upsert(true));
        }
    }

    public static List<Document> readSource(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT s.id, s.requested_subsidy, s.approved_subsidy,
                       c.id company_id, c.title, c.legal_address, c.fact_address,
                       i.id industry_id, i.name industry_name, i.code,
                       e.id employee_id, e.name employee_name, e.position, e.company_id employee_company_id
                FROM TEST.subsidy s
                JOIN TEST.company c ON c.id = s.company_id
                JOIN TEST.industry i ON i.id = s.industry_id
                JOIN TEST.employee e ON e.id = s.person_in_charge_id
                WHERE s.id BETWEEN 1 AND 1500
                ORDER BY s.id
                """, (rs, row) -> new Document("_id", "subsidy-v1-" + rs.getLong("id"))
                .append("dataset", DATASET)
                .append("schemaVersion", 1)
                .append("subsidyId", rs.getLong("id"))
                .append("requestedSubsidy", new Decimal128(rs.getBigDecimal("requested_subsidy")))
                .append("approvedSubsidy", new Decimal128(rs.getBigDecimal("approved_subsidy")))
                .append("company", new Document("id", rs.getLong("company_id"))
                        .append("title", rs.getString("title"))
                        .append("legalAddress", rs.getString("legal_address"))
                        .append("factAddress", rs.getString("fact_address")))
                .append("industry", new Document("id", rs.getLong("industry_id"))
                        .append("name", rs.getString("industry_name"))
                        .append("code", rs.getString("code")))
                .append("personInCharge", new Document("id", rs.getLong("employee_id"))
                        .append("name", rs.getString("employee_name"))
                        .append("position", rs.getString("position"))
                        .append("companyId", rs.getLong("employee_company_id"))));
    }

    /** IDs ending 01..08 in each hundred carry one defect; the other 92 are intact. */
    public static void introduceError(Document document) {
        int scenario = (int) (document.getLong("subsidyId") % 100);
        Document company = document.get("company", Document.class);
        Document industry = document.get("industry", Document.class);
        Document person = document.get("personInCharge", Document.class);
        BigDecimal requested = document.get("requestedSubsidy", Decimal128.class).bigDecimalValue();
        switch (scenario) {
            case 1 -> company.put("title", "  " + company.getString("title") + "  ");
            case 2 -> company.remove("legalAddress");
            case 3 -> company.put("factAddress", "г. Москва, ул. Архивная, д. 1 (устаревший адрес)");
            case 4 -> document.put("approvedSubsidy", new Decimal128(requested.add(new BigDecimal("10000.00"))));
            case 5 -> document.put("requestedSubsidy", new Decimal128(requested.negate()));
            case 6 -> industry.put("code", "99.99");
            case 7 -> person.put("id", 999999L);
            case 8 -> document.put("requestedSubsidy", requested.toPlainString());
            default -> { }
        }
    }

    @RollbackExecution
    public void rollback() {
        // Intentionally keep partial inserts: deterministic IDs + $setOnInsert resume safely.
        // Do not delete records that a later Batch job might already have repaired.
    }
}

package com.exportcenter.demo_spring_batch.batch;

import com.exportcenter.demo_spring_batch.mongo.SubsidyApplication;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Step-scoped reader. Checkpoint is a stable key, not an offset in a changing list. */
public class SubsidyApplicationReader implements ItemStreamReader<SubsidyApplication> {
    public static final String CHECKPOINT = "subsidyReader.lastId";
    private final MongoTemplate mongo;
    private final int scenario;
    private Iterator<SubsidyApplication> items = Collections.emptyIterator();
    private long lastId;

    public SubsidyApplicationReader(MongoTemplate mongo, int scenario) {
        this.mongo = mongo;
        this.scenario = scenario;
    }

    @Override
    public void open(ExecutionContext context) {
        lastId = context.getLong(CHECKPOINT, 0L);
        Query query = Query.query(Criteria.where("subsidyId").gt(lastId).mod(100, scenario))
                .with(Sort.by("subsidyId"));
        // Demo dataset is small; materialize only this scenario after startup seeding.
        items = mongo.find(query, SubsidyApplication.class).iterator();
    }

    @Override
    public SubsidyApplication read() {
        if (!items.hasNext()) return null;
        SubsidyApplication item = items.next();
        lastId = item.subsidyId();
        return item;
    }

    @Override
    public void update(ExecutionContext context) {
        context.putLong(CHECKPOINT, lastId);
    }

    @Override
    public void close() {
        items = Collections.emptyIterator();
    }
}

package org.taniwha.config;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.stream.StreamSupport;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProjectProvenanceIndexMigration implements ApplicationRunner {

    static final String COLLECTION = "project_provenance_log";
    static final String INDEX_NAME = "project_node_source_event_unique_idx";
    private static final Document INDEX_KEYS = new Document("projectId", 1)
            .append("nodeId", 1)
            .append("sourceEventId", 1);
    private static final Document PARTIAL_FILTER = new Document(
            "sourceEventId", new Document("$type", "string"));

    private final MongoTemplate mongoTemplate;

    public ProjectProvenanceIndexMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        MongoCollection<Document> collection = mongoTemplate.getCollection(COLLECTION);
        Document existing = StreamSupport.stream(collection.listIndexes().spliterator(), false)
                .filter(index -> INDEX_NAME.equals(index.getString("name")))
                .findFirst()
                .orElse(null);

        if (isCurrent(existing)) {
            return;
        }
        if (existing != null) {
            collection.dropIndex(INDEX_NAME);
        }
        collection.createIndex(
                Indexes.ascending("projectId", "nodeId", "sourceEventId"),
                new IndexOptions()
                        .name(INDEX_NAME)
                        .unique(true)
                        .partialFilterExpression(PARTIAL_FILTER));
    }

    private boolean isCurrent(Document index) {
        return index != null
                && Boolean.TRUE.equals(index.getBoolean("unique"))
                && INDEX_KEYS.equals(index.get("key", Document.class))
                && PARTIAL_FILTER.equals(index.get("partialFilterExpression", Document.class));
    }
}

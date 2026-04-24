package com.github.streamshub.demo;

import io.apicurio.registry.resolver.ParsedSchema;
import io.apicurio.registry.resolver.data.Record;
import io.apicurio.registry.resolver.strategy.ArtifactReference;
import io.apicurio.registry.resolver.strategy.ArtifactReferenceResolverStrategy;
import io.apicurio.registry.serde.data.SerdeRecord;
import org.apache.avro.Schema;

/**
 * Uses the Kafka topic name as the Apicurio Registry group ID, enabling
 * per-topic authorization via Authorino and Keycloak Authorization Services.
 */
public class TopicGroupStrategy implements ArtifactReferenceResolverStrategy<Object, Object> {

    @Override
    @SuppressWarnings("unchecked")
    public ArtifactReference artifactReference(Record<Object> record, ParsedSchema<Object> parsedSchema) {
        SerdeRecord<Object> serdeRecord = (SerdeRecord<Object>) record;
        Schema schema = (Schema) parsedSchema.getParsedSchema();
        return ArtifactReference.builder()
                .groupId(serdeRecord.metadata().getTopic())
                .artifactId(schema.getFullName())
                .build();
    }

    @Override
    public boolean loadSchema() {
        return true;
    }
}

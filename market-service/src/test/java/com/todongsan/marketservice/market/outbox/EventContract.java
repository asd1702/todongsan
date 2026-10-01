package com.todongsan.marketservice.market.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** 루트 contracts/events 스키마로 이벤트 payload를 검증한다. */
public final class EventContract {

    private static final Path CONTRACTS_DIR = Path.of("..", "contracts", "events");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JsonSchema schema;

    private EventContract(JsonSchema schema) {
        this.schema = schema;
    }

    public static EventContract load(String fileName) {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                .formatAssertionsEnabled(true)
                .build();
        try (InputStream in = Files.newInputStream(CONTRACTS_DIR.resolve(fileName))) {
            return new EventContract(factory.getSchema(in, config));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Set<ValidationMessage> validate(String json) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            return schema.validate(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

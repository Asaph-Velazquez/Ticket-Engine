package com.ticketengine.config;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.ticketengine.crypto.TicketCryptoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;

@Configuration
public class TicketConfiguration {
    @Bean
    public Clock ticketClock() {
        return Clock.systemUTC();
    }

    @Bean
    public TicketCryptoService ticketCryptoService(
            @Value("${ticket.private-key:/run/keys/private.pem}") String privateKey,
            @Value("${ticket.public-key:/run/keys/public.pem}") String publicKey) {
        return TicketCryptoService.load(Path.of(privateKey), Path.of(publicKey));
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return builder -> {
            builder.featuresToEnable(
                    StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature(),
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
            builder.featuresToDisable(MapperFeature.ALLOW_COERCION_OF_SCALARS, DeserializationFeature.ACCEPT_FLOAT_AS_INT);
            builder.postConfigurer(mapper -> mapper.coercionConfigFor(LogicalType.Textual)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
            builder.deserializerByType(Instant.class, new JsonDeserializer<Instant>() {
                @Override
                public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                    if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                        throw JsonMappingException.from(parser, "Se requiere una fecha ISO-8601 de texto");
                    }
                    try {
                        return Instant.parse(parser.getText());
                    } catch (DateTimeParseException ex) {
                        throw JsonMappingException.from(parser, "Fecha ISO-8601 inválida");
                    }
                }
            });
        };
    }
}

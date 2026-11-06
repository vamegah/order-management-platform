package com.example.omp.shared.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Feign;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Keeps Feign clients on the application's existing JSON wire contract. */
@AutoConfiguration(
        after = Jackson2AutoConfiguration.class,
        beforeName = "org.springframework.cloud.openfeign.FeignAutoConfiguration")
@ConditionalOnClass({Feign.class, JacksonEncoder.class, JacksonDecoder.class})
@ConditionalOnBean(ObjectMapper.class)
public class FeignJsonAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(Encoder.class)
    public Encoder feignJsonEncoder(ObjectMapper mapper) {
        return new JacksonEncoder(mapper);
    }

    @Bean
    @ConditionalOnMissingBean(Decoder.class)
    public Decoder feignJsonDecoder(ObjectMapper mapper) {
        return new JacksonDecoder(mapper);
    }
}

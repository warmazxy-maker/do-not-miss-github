package com.donotmiss.backend.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableRabbit
@EnableConfigurationProperties(MqProperties.class)
public class RabbitMqConfig {

    @Bean
    public TopicExchange doNotMissExchange(MqProperties properties) {
        return ExchangeBuilder.topicExchange(properties.getExchange())
                .durable(true)
                .build();
    }

    @Bean
    public Queue eventIndexQueue(MqProperties properties) {
        return QueueBuilder.durable(properties.getQueues().getEventIndex()).build();
    }

    @Bean
    public Queue eventQualityQueue(MqProperties properties) {
        return QueueBuilder.durable(properties.getQueues().getEventQuality()).build();
    }

    @Bean
    public Queue growthTagQueue(MqProperties properties) {
        return QueueBuilder.durable(properties.getQueues().getGrowthTag()).build();
    }

    @Bean
    public Queue abilityEvidenceQueue(MqProperties properties) {
        return QueueBuilder.durable(properties.getQueues().getAbilityEvidence()).build();
    }

    @Bean
    public Queue userProfileQueue(MqProperties properties) {
        return QueueBuilder.durable(properties.getQueues().getUserProfile()).build();
    }

    @Bean
    public Binding eventIndexBinding(@Qualifier("eventIndexQueue") Queue eventIndexQueue,
                                     TopicExchange doNotMissExchange,
                                     MqProperties properties) {
        return BindingBuilder.bind(eventIndexQueue)
                .to(doNotMissExchange)
                .with(properties.getRoutingKeys().getEventIndex());
    }

    @Bean
    public Binding eventQualityBinding(@Qualifier("eventQualityQueue") Queue eventQualityQueue,
                                       TopicExchange doNotMissExchange,
                                       MqProperties properties) {
        return BindingBuilder.bind(eventQualityQueue)
                .to(doNotMissExchange)
                .with(properties.getRoutingKeys().getEventQuality());
    }

    @Bean
    public Binding growthTagBinding(@Qualifier("growthTagQueue") Queue growthTagQueue,
                                    TopicExchange doNotMissExchange,
                                    MqProperties properties) {
        return BindingBuilder.bind(growthTagQueue)
                .to(doNotMissExchange)
                .with(properties.getRoutingKeys().getGrowthTag());
    }

    @Bean
    public Binding abilityEvidenceBinding(@Qualifier("abilityEvidenceQueue") Queue abilityEvidenceQueue,
                                          TopicExchange doNotMissExchange,
                                          MqProperties properties) {
        return BindingBuilder.bind(abilityEvidenceQueue)
                .to(doNotMissExchange)
                .with(properties.getRoutingKeys().getAbilityEvidence());
    }

    @Bean
    public Binding userProfileBinding(@Qualifier("userProfileQueue") Queue userProfileQueue,
                                      TopicExchange doNotMissExchange,
                                      MqProperties properties) {
        return BindingBuilder.bind(userProfileQueue)
                .to(doNotMissExchange)
                .with(properties.getRoutingKeys().getUserProfile());
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        return rabbitTemplate;
    }
}

package com.example.marketplace.events;

import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.UUID;

@Configuration
@ConditionalOnProperty(name = "marketplace.events.transport", havingValue = "kafka")
public class KafkaEvents {
    @Bean
    NewTopic marketplaceTopic(
            @Value("${marketplace.events.topic:marketplace.events.v1}") String topic,
            @Value("${marketplace.events.replication:1}") short replication) {
        return org.springframework.kafka.config.TopicBuilder.name(topic)
                .partitions(3)
                .replicas(replication)
                .build();
    }

    @Bean
    NewTopic deadLetters(
            @Value("${marketplace.events.topic:marketplace.events.v1}") String topic,
            @Value("${marketplace.events.replication:1}") short replication) {
        return org.springframework.kafka.config.TopicBuilder.name(topic + ".DLT")
                .partitions(3)
                .replicas(replication)
                .build();
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumers,
            KafkaTemplate<String, String> template,
            @Value("${marketplace.events.topic:marketplace.events.v1}") String topic) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        var recoverer =
                new DeadLetterPublishingRecoverer(
                        template,
                        (record, exception) -> new TopicPartition(topic + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(250L, 2L)));
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> deadLetterFactory(
            ConsumerFactory<String, String> consumers) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, Long.MAX_VALUE)));
        return factory;
    }

    @Bean
    Listeners listeners(Store s, EventService events) {
        return new Listeners(s, events);
    }

    static class Listeners {
        private final Store s;
        private final EventService events;

        Listeners(Store s, EventService events) {
            this.s = s;
            this.events = events;
        }

        @KafkaListener(
                topics = "${marketplace.events.topic:marketplace.events.v1}",
                groupId = "marketplace-notifications-v1")
        void event(ConsumerRecord<String, String> record) throws Exception {
            var event = s.json.readValue(record.value(), EventService.Envelope.class);
            Rules.require(
                    event.aggregateId() != null && event.aggregateId().toString().equals(record.key()),
                    "Invalid partition key");
            events.consume(event);
        }

        @KafkaListener(
                topics = "${marketplace.events.topic:marketplace.events.v1}.DLT",
                groupId = "marketplace-dlt-v1",
                containerFactory = "deadLetterFactory")
        void deadLetter(ConsumerRecord<String, String> record) {
            s.db.update(
                    "insert into event_failures(id,topic,partition_id,offset_id,payload) values (?,?,?,?,?)"
                            + " on conflict(topic,partition_id,offset_id) do nothing",
                    UUID.randomUUID(),
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.value() == null ? "null" : record.value());
        }
    }
}

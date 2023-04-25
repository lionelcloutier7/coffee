package io.spring.barcelona.coffee.waiter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import io.spring.barcelona.coffee.waiter.controller.OrderController;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.verifier.converter.YamlContract;
import org.springframework.cloud.contract.verifier.messaging.MessageVerifierReceiver;
import org.springframework.cloud.contract.verifier.messaging.boot.AutoConfigureMessageVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.DefaultKafkaHeaderMapper;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * @author Olga Maciaszek-Sharma
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = {WaiterApplication.class, BaseTestClass.TestConfig.class})
@Testcontainers
@AutoConfigureMessageVerifier
@ActiveProfiles("contracts")
@DirtiesContext
public class BaseTestClass {


	@Container
	static KafkaContainer kafkaForIntegration = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka"));


	@DynamicPropertySource
	static void kafkaProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.kafka.bootstrap-servers", kafkaForIntegration::getBootstrapServers);
	}

	@Autowired
	private OrderController orderController;

	public void triggerOrder() {
		orderController.testOrder();
	}

	@Configuration
	@EnableKafka
	static class TestConfig {

		@Bean
		KafkaMessageVerifier kafkaTemplateMessageVerifier() {
			return new KafkaMessageVerifier();
		}

	}

	static class KafkaMessageVerifier implements MessageVerifierReceiver<Message<?>> {

		private static final Log LOG = LogFactory.getLog(KafkaMessageVerifier.class);

		private final Map<String, Message<?>> broker = new ConcurrentHashMap<>();

		private final CyclicBarrier cyclicBarrier = new CyclicBarrier(2);

		@Override
		public Message receive(String destination, long timeout, TimeUnit timeUnit, @Nullable YamlContract contract) {
			Message message = message(destination);
			if (message != null) {
				return message;
			}
			await(timeout, timeUnit);
			return message(destination);
		}

		private void await(long timeout, TimeUnit timeUnit) {
			try {
				cyclicBarrier.await(timeout, timeUnit);
			}
			catch (Exception e) {

			}
		}

		private Message message(String destination) {
			Message message = broker.get(destination);
			if (message != null) {
				broker.remove(destination);
				LOG.info("Removed a message from a topic [" + destination + "]");
				LOG.info(message.getPayload().toString());
			}
			return message;
		}

		@KafkaListener(id = "listener", topicPattern = ".*")
		public void listen(ConsumerRecord payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) throws BrokenBarrierException, InterruptedException {
			LOG.info("Got a message from a topic [" + topic + "]");
			Map<String, Object> headers = new HashMap<>();
			new DefaultKafkaHeaderMapper().toHeaders(payload.headers(), headers);
			broker.put(topic, MessageBuilder.createMessage(payload.value(), new MessageHeaders(headers)));
			cyclicBarrier.await();
			cyclicBarrier.reset();
		}

		@Override
		public Message receive(String destination, YamlContract contract) {
			return receive(destination, 10, TimeUnit.SECONDS, contract);
		}

	}
}



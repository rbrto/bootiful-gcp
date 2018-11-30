package com.example.bootifulgcp;

import com.google.api.gax.core.CredentialsProvider;
import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.gcp.data.spanner.core.mapping.Table;
import org.springframework.cloud.gcp.data.spanner.repository.SpannerRepository;
import org.springframework.cloud.gcp.pubsub.core.publisher.PubSubPublisherTemplate;
import org.springframework.cloud.gcp.pubsub.core.subscriber.PubSubSubscriberTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SpringBootApplication
public class BootifulGcpApplication {

	@Bean
	RestTemplate restTemplate() {
		return new RestTemplate();
	}

	@Bean
	ImageAnnotatorClient imageAnnotatorClient(CredentialsProvider cp) throws Exception {
		ImageAnnotatorSettings build = ImageAnnotatorSettings.newBuilder()
			.setCredentialsProvider(cp)
			.build();
		return ImageAnnotatorClient.create(build);
	}

	public static void main(String[] args) {
		SpringApplication.run(BootifulGcpApplication.class, args);
	}
}


@Component
@Log4j2
class PubsubDemo {

	private final PubSubPublisherTemplate publisher;
	private final PubSubSubscriberTemplate subscriber;

	PubsubDemo(PubSubPublisherTemplate publisher, PubSubSubscriberTemplate subscriber) {
		this.publisher = publisher;
		this.subscriber = subscriber;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void demo() throws Exception {

		this.subscriber.subscribe("reservations-subscription", msg -> {
			ByteString data = msg.getPubsubMessage().getData();
			String stringUtf8 = data.toStringUtf8();
			log.info("message: " + stringUtf8);
			msg.ack();
		});

		this.publisher
			.publish("reservations", "Hello @ " + Instant.now().toString());

	}
}

@Component
@Log4j2
class VisionDemo {

	private final Resource resource;
	private final ImageAnnotatorClient imageAnnotatorClient;

	VisionDemo(
		@Value("gs://pgtm-jlong-bucket/cat.jpg") Resource cat,
		ImageAnnotatorClient imageAnnotatorClient) {
		this.resource = cat;
		this.imageAnnotatorClient = imageAnnotatorClient;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void demo() throws Exception {

		byte[] catBytes = FileCopyUtils
			.copyToByteArray(this.resource.getInputStream());

		AnnotateImageRequest build = AnnotateImageRequest
			.newBuilder()
			.addFeatures(Feature.newBuilder().setType(Feature.Type.LABEL_DETECTION))
			.setImage(Image.newBuilder().setContent(ByteString.copyFrom(catBytes)))
			.build();

		BatchAnnotateImagesResponse response =
			this.imageAnnotatorClient.batchAnnotateImages(Collections.singletonList(build));

		log.info(response);
	}

}

@Component
@Log4j2
class SpannerDemo {

	private final ReservationRepository reservationRepository;

	SpannerDemo(ReservationRepository reservationRepository) {
		this.reservationRepository = reservationRepository;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void demo() throws Exception {
		this.reservationRepository.deleteAll();
		Stream.of("Ray", "Josh", "Olga", "Violetta", "Cornelia", "Dave", "Mark", "Madhura", "Andy")
			.map(name -> new Reservation(UUID.randomUUID().toString(), name))
			.map(this.reservationRepository::save)
			.forEach(log::info);
	}

}

interface ReservationRepository extends SpannerRepository<Reservation, String> {
}

@Table(name = "reservations")
@Data
@AllArgsConstructor
@NoArgsConstructor
class Reservation {
	private String id;
	private String name;
}


@Component
@Log4j2
class MySqlDemo {

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	private static class Reservation {
		private Long id;
		private String name;
	}

	private final JdbcTemplate jdbcTemplate;

	MySqlDemo(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void demo() throws Exception {

		List<Reservation> reservationList = this.jdbcTemplate.query("select * from reservations",
			(rs, rowNum) -> new Reservation(rs.getLong("id"), rs.getString("name")));
		reservationList.forEach(log::info);
	}
}


@RestController
@Log4j2
class GreetingsRestController {

	private final RestTemplate restTemplate;

	GreetingsRestController(RestTemplate restTemplate) {
		this.restTemplate = restTemplate;
	}

	@GetMapping("/greet/{name}")
	String greet(@PathVariable String name) {
		log.info("greeting " + name + '.');
		return "hello, " + name + "!";
	}

	@GetMapping("/client")
	Collection<String> client() {
		return Stream.of("Ray", "Dave", "Bob", "Paul", "Tammie", "Kimly", "Holden", "Cornelia")
			.map(this::call)
			.collect(Collectors.toList());
	}

	private String call(String name) {
		return this.restTemplate
			.getForEntity("http://localhost:8080/greet/{name}", String.class, name)
			.getBody();
	}

}

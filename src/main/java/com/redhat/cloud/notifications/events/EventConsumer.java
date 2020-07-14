package com.redhat.cloud.notifications.events;

import com.redhat.cloud.notifications.webhooks.WebhookProcessor;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.hawkular.alerts.api.model.action.Action;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class EventConsumer {

    @Inject
    EndpointProcessor destinations;

    @Inject
    WebhookProcessor webhooks;

    @Incoming("ingress")
//    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    // Can be modified to use Multi<Message<String>> input also for more concurrency
    public Uni<Void> processAsync(Message<String> input) {
        return Uni.createFrom()
                .item(input)
                .then(self -> self
                                // First pipeline stage - modify from Kafka message to processable entity
                                .onItem().apply(Message::getPayload)
                                .onItem().apply(this::extractJson)
                        // TODO Handle here and set the counters for broken input data and produce empty message?
                )
                .then(self -> self
                                // Second pipeline stage - enrich from input to destination (webhook) processor format
                                .onItem()
                                .produceMulti(action -> destinations.process(action))
                                .onItem().produceUni(notif -> webhooks.process(notif))
                                .merge()
                        // Receive only notification of completion
                )
                .toUni()
                // Third pipeline stage - handle failures (nothing should be propagated here) and ack the Kafka topic
                .onFailure().invoke(Throwable::printStackTrace); // This does not invoke the next ack
//                .onFailure().retry().atMost(1) // Retry should not be here..
        // TODO Return this after testing
//                .onItem().produceCompletionStage(m -> input.ack());
    }

    public Action extractJson(String payload) {
        // JSON, JSON-P, JSON-B, Jackson? Uh? Vertx?
        JsonObject json = new JsonObject(payload);
        return json.mapTo(Action.class);
    }
}

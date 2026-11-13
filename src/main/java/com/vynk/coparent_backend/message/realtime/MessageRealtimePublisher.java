package com.vynk.coparent_backend.message.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Envoie les événements de messagerie aux abonnés, après validation de la transaction seulement. */
@Component
@RequiredArgsConstructor
public class MessageRealtimePublisher {

    private final SimpMessagingTemplate template;

    @TransactionalEventListener
    public void publish(MessagePublished published) {
        template.convertAndSend(MessagePublished.topic(published.familyId()), published.event());
    }
}

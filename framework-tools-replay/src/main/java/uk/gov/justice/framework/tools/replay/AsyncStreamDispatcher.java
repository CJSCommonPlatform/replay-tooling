package uk.gov.justice.framework.tools.replay;

import static javax.ejb.TransactionAttributeType.NOT_SUPPORTED;

import uk.gov.justice.services.core.handler.exception.MissingHandlerException;
import uk.gov.justice.services.event.buffer.core.repository.subscription.Subscription;
import uk.gov.justice.services.messaging.JsonEnvelope;

import java.util.UUID;
import java.util.stream.Stream;

import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.inject.Inject;

@Stateless
public class AsyncStreamDispatcher {

    private static final int PAGE_SIZE = 1000;
    private static final long FIRST_POSITION = 1L;
    
    private static final String STREAM_ID_MDC_KEY = "streamId";
    private static final String EVENT_DATA_MDC_KEY = "eventData";

    @Inject
    private TransactionalEnvelopeDispatcher envelopeDispatcher;

    @Inject
    private StreamSubscriptionFactory streamSubscriptionFactory;

    @Inject
    private JsonEnvelopeJdbcRepository jsonEnvelopeJdbcRepository;

    @Inject
    private ProgressLogger progressLogger;

    @Inject
    private LoggingMdc loggingMdc;

    @Inject
    private TransactionalStreamStatusRepository transactionalStreamStatusRepository;

    @TransactionAttribute(NOT_SUPPORTED)
    public UUID dispatch(final UUID streamId) {

        loggingMdc.put(STREAM_ID_MDC_KEY, "streamId: " + streamId);
        progressLogger.logStart(streamId);

        try {
            replayAllEventsOf(streamId);
        } finally {
            final Subscription subscription = streamSubscriptionFactory.create(
                    jsonEnvelopeJdbcRepository.getLatestEvent(streamId),
                    streamId);

            transactionalStreamStatusRepository.insert(subscription);
        }

        progressLogger.logCompletion(streamId);

        loggingMdc.clear();

        return streamId;
    }

    private void replayAllEventsOf(final UUID streamId) {

        final long lastPosition = jsonEnvelopeJdbcRepository.getCurrentVersion(streamId);

        for (long position = FIRST_POSITION; position <= lastPosition; position = position + PAGE_SIZE) {
            replayBatchOfEvents(streamId, position);
        }
    }

    private void replayBatchOfEvents(final UUID streamId, final long position) {
        try (final Stream<JsonEnvelope> eventStream = jsonEnvelopeJdbcRepository.pageEventStream(streamId, position, PAGE_SIZE)) {
            eventStream.forEach(jsonEnvelope -> dispatchEnvelope(jsonEnvelope, streamId));
        }
    }

    private void dispatchEnvelope(final JsonEnvelope jsonEnvelope, final UUID streamId) {

        loggingMdc.put(EVENT_DATA_MDC_KEY, "event: " + jsonEnvelope.toString());

        doDispatch(jsonEnvelope, streamId);

        loggingMdc.remove(EVENT_DATA_MDC_KEY);
    }

    private void doDispatch(final JsonEnvelope jsonEnvelope, final UUID streamId) {
        try {
            progressLogger.logDispatch();
            envelopeDispatcher.dispatch(jsonEnvelope);
            progressLogger.logSuccess(streamId, jsonEnvelope);
        } catch (final MissingHandlerException ex) {
            progressLogger.logFailure(streamId, jsonEnvelope);
        }
    }
}

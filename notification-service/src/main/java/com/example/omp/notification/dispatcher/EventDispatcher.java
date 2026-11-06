package com.example.omp.notification.dispatcher;

import com.example.omp.shared.events.BaseEvent;

/**
 * Interface for dispatching events to local WebSocket subscribers.
 *
 * <p>Implemented by {@link EventDispatcherImpl}. Called by the Redis subscriber when an event
 * arrives on a Redis channel.
 *
 * <p>Defined as an interface (not a concrete class) to allow mocking in tests and potential future
 * implementations (e.g., a synchronous dispatcher for dev/testing that doesn't require Redis).
 */
public interface EventDispatcher {

    /**
     * Dispatches an event to all local WebSocket subscribers whose subscriptions match the event's
     * resource.
     */
    void dispatch(BaseEvent event);
}

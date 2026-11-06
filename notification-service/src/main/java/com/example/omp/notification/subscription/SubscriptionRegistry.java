package com.example.omp.notification.subscription;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of active subscriptions.
 *
 * <p><b>Data structure:</b> three concurrent maps providing different lookup patterns:
 *
 * <ul>
 *   <li>{@code bySession} — sessionId → subscriptions (for cleanup on disconnect).
 *   <li>{@code byDestination} — destination string → subscriptions (for direct lookup by exact
 *       destination).
 *   <li>{@code byTypeAndResource} — (type, resourceId) → subscriptions (for efficient event push:
 *       the dispatcher knows the event's type and aggregate ID, not the destination string).
 * </ul>
 *
 * <p><b>Lifecycle:</b> subscriptions are added on SUBSCRIBE, removed on UNSUBSCRIBE or session
 * disconnect. There is no persistence — a service restart drops all subscriptions, and clients must
 * re-subscribe.
 *
 * <p><b>Concurrency:</b> all maps are concurrent. Subscription sets use {@link CopyOnWriteArraySet}
 * — subscriptions are added and removed rarely relative to reads, so copy-on-write is efficient.
 * The set is small (typically a handful of subscriptions per destination).
 */
@Component
public class SubscriptionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRegistry.class);

    private final Map<String, Set<Subscription>> bySession = new ConcurrentHashMap<>();
    private final Map<String, Set<Subscription>> byDestination = new ConcurrentHashMap<>();
    private final Map<String, Set<Subscription>> byTypeAndResource = new ConcurrentHashMap<>();

    /**
     * Registers a STOMP subscription ID scoped to its session. Duplicate IDs are rejected; distinct
     * IDs may subscribe to the same destination independently.
     */
    public synchronized void register(Subscription subscription) {
        if (bySession.getOrDefault(subscription.sessionId(), Set.of()).stream()
                .anyMatch(existing -> existing.id().equals(subscription.id())))
            throw new SubscriptionUnauthorizedException("Subscription ID is already in use");
        bySession
                .computeIfAbsent(subscription.sessionId(), k -> new CopyOnWriteArraySet<>())
                .add(subscription);
        byDestination
                .computeIfAbsent(subscription.destination(), k -> new CopyOnWriteArraySet<>())
                .add(subscription);
        byTypeAndResource
                .computeIfAbsent(
                        key(subscription.type(), subscription.resourceId()),
                        k -> new CopyOnWriteArraySet<>())
                .add(subscription);

        log.debug(
                "Registered subscription: session={} dest={} type={} resource={}",
                subscription.sessionId(),
                subscription.destination(),
                subscription.type(),
                subscription.resourceId());
    }

    /** Removes a subscription. Called on UNSUBSCRIBE. */
    public synchronized void unregister(Subscription subscription) {
        removeFrom(bySession, subscription.sessionId(), subscription);
        removeFrom(byDestination, subscription.destination(), subscription);
        removeFrom(
                byTypeAndResource,
                key(subscription.type(), subscription.resourceId()),
                subscription);

        log.debug(
                "Unregistered subscription: session={} dest={}",
                subscription.sessionId(),
                subscription.destination());
    }

    /** Removes all subscriptions for a session. Called on session disconnect. */
    public synchronized void removeSession(String sessionId) {
        Set<Subscription> subscriptions = bySession.remove(sessionId);
        if (subscriptions == null) {
            return;
        }
        for (Subscription sub : subscriptions) {
            removeFrom(byDestination, sub.destination(), sub);
            removeFrom(byTypeAndResource, key(sub.type(), sub.resourceId()), sub);
        }
        log.info("Cleaned up {} subscriptions for session {}", subscriptions.size(), sessionId);
    }

    /**
     * Returns subscriptions matching a (type, resourceId) pair.
     *
     * <p>The result is a snapshot — safe to iterate without further locking.
     */
    public synchronized Set<Subscription> find(TopicType type, String resourceId) {
        Set<Subscription> subs = byTypeAndResource.get(key(type, resourceId));
        return subs != null ? Set.copyOf(subs) : Set.of();
    }

    /** Total active subscriptions. */
    public synchronized int totalSubscriptions() {
        return byDestination.values().stream().mapToInt(Set::size).sum();
    }

    /** Active sessions (sessions with at least one subscription). */
    public synchronized int totalSessions() {
        return bySession.size();
    }

    /** Immutable view of the bySession map (for metrics and diagnostics). */
    public synchronized Map<String, Set<Subscription>> sessionsSnapshot() {
        return bySession.entrySet().stream()
                .collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    public synchronized void unregister(String sessionId, String subscriptionId) {
        bySession.getOrDefault(sessionId, Set.of()).stream()
                .filter(sub -> sub.id().equals(subscriptionId))
                .findFirst()
                .ifPresent(this::unregister);
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private static String key(TopicType type, String resourceId) {
        return type.name() + ":" + resourceId;
    }

    private static void removeFrom(
            Map<String, Set<Subscription>> map, String key, Subscription sub) {
        Set<Subscription> set = map.get(key);
        if (set != null) {
            set.remove(sub);
            if (set.isEmpty()) {
                map.remove(key);
            }
        }
    }
}

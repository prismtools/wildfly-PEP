/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.web.infinispan.session.fine;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.infinispan.Cache;
import org.wildfly.clustering.ee.Immutability;
import org.wildfly.clustering.ee.cache.CacheProperties;
import org.wildfly.clustering.infinispan.listener.ListenerRegistration;
import org.wildfly.clustering.infinispan.listener.PostActivateBlockingListener;
import org.wildfly.clustering.infinispan.listener.PostPassivateBlockingListener;
import org.wildfly.clustering.infinispan.listener.PrePassivateBlockingListener;
import org.wildfly.clustering.infinispan.listener.PrePassivateNonBlockingListener;
import org.wildfly.clustering.marshalling.spi.Marshaller;
import org.wildfly.clustering.web.cache.session.CompositeImmutableSession;
import org.wildfly.clustering.web.cache.session.ImmutableSessionAttributeActivationNotifier;
import org.wildfly.clustering.web.cache.session.SessionAttributeActivationNotifier;
import org.wildfly.clustering.web.cache.session.SessionAttributes;
import org.wildfly.clustering.web.cache.session.SessionAttributesFactory;
import org.wildfly.clustering.web.cache.session.fine.FineImmutableSessionAttributes;
import org.wildfly.clustering.web.cache.session.fine.FineSessionAttributes;
import org.wildfly.clustering.web.infinispan.logging.InfinispanWebLogger;
import org.wildfly.clustering.web.infinispan.session.InfinispanSessionAttributesFactoryConfiguration;
import org.wildfly.clustering.web.infinispan.session.SessionCreationMetaDataKey;
import org.wildfly.clustering.web.session.HttpSessionActivationListenerProvider;
import org.wildfly.clustering.web.session.ImmutableSessionAttributes;
import org.wildfly.clustering.web.session.ImmutableSessionMetaData;

/**
 * {@link SessionAttributesFactory} for fine granularity sessions.
 * A given session's attributes are mapped to N+1 co-located cache entries, where N is the number of session attributes.
 * A separate cache entry stores the activate attribute names for the session.
 * @author Paul Ferraro
 */
public class FineSessionAttributesFactory<S, C, L, V> implements SessionAttributesFactory<C, Map.Entry<Map<String, UUID>, Map<UUID, Object>>> {

    private final Cache<SessionAttributeNamesKey, Map<String, UUID>> namesCache;
    private final Cache<SessionAttributeNamesKey, Map<String, UUID>> writeOnlyNamesCache;
    private final Cache<SessionAttributeKey, V> attributeCache;
    private final Cache<SessionAttributeKey, V> writeOnlyAttributeCache;
    private final Cache<SessionAttributeKey, V> silentAttributeCache;
    private final Marshaller<Object, V> marshaller;
    private final Immutability immutability;
    private final CacheProperties properties;
    private final HttpSessionActivationListenerProvider<S, C, L> provider;
    private final Function<String, SessionAttributeActivationNotifier> notifierFactory;
    private final Executor executor;
    private final ListenerRegistration evictListenerRegistration;
    private final ListenerRegistration evictAttributesListenerRegistration;
    private final ListenerRegistration prePassivateListenerRegistration;
    private final ListenerRegistration postActivateListenerRegistration;

    public FineSessionAttributesFactory(InfinispanSessionAttributesFactoryConfiguration<S, C, L, Object, V> configuration) {
        this.namesCache = configuration.getCache();
        this.writeOnlyNamesCache = configuration.getWriteOnlyCache();
        this.attributeCache = configuration.getCache();
        this.writeOnlyAttributeCache = configuration.getWriteOnlyCache();
        this.silentAttributeCache = configuration.getSilentWriteCache();
        this.marshaller = configuration.getMarshaller();
        this.immutability = configuration.getImmutability();
        this.properties = configuration.getCacheProperties();
        this.provider = configuration.getHttpSessionActivationListenerProvider();
        this.notifierFactory = configuration.getActivationNotifierFactory();
        this.executor = configuration.getBlockingManager().asExecutor(this.getClass().getName());
        this.evictListenerRegistration = new PostPassivateBlockingListener<>(configuration.getCache(), this::cascadeEvict).register(SessionCreationMetaDataKey.class);
        this.evictAttributesListenerRegistration = new PrePassivateNonBlockingListener<>(this.namesCache, this::cascadeEvictAttributes).register(SessionAttributeNamesKey.class);
        this.prePassivateListenerRegistration = !this.properties.isPersistent() ? new PrePassivateBlockingListener<>(this.attributeCache, this::prePassivate).register(SessionAttributeKey.class) : null;
        this.postActivateListenerRegistration = !this.properties.isPersistent() ? new PostActivateBlockingListener<>(this.attributeCache, this::postActivate).register(SessionAttributeKey.class) : null;
    }

    @Override
    public void close() {
        this.evictListenerRegistration.close();
        this.evictAttributesListenerRegistration.close();
        if (this.prePassivateListenerRegistration != null) {
            this.prePassivateListenerRegistration.close();
        }
        if (this.postActivateListenerRegistration != null) {
            this.postActivateListenerRegistration.close();
        }
    }

    @Override
    public Map.Entry<Map<String, UUID>, Map<UUID, Object>> createValue(String id, Void context) {
        return new AbstractMap.SimpleImmutableEntry<>(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }

    @Override
    public Map.Entry<Map<String, UUID>, Map<UUID, Object>> findValue(String id) {
        return this.getValue(id, true);
    }

    @Override
    public Map.Entry<Map<String, UUID>, Map<UUID, Object>> tryValue(String id) {
        return this.getValue(id, false);
    }

    private Map.Entry<Map<String, UUID>, Map<UUID, Object>> getValue(String id, boolean purgeIfInvalid) {
        Map<String, UUID> names = this.namesCache.get(new SessionAttributeNamesKey(id));
        if (names == null) {
            return this.createValue(id, null);
        }
        // Validate all attributes
        Map<SessionAttributeKey, String> keys = new TreeMap<>();
        for (Map.Entry<String, UUID> entry : names.entrySet()) {
            keys.put(new SessionAttributeKey(id, entry.getValue()), entry.getKey());
        }
        Map<SessionAttributeKey, V> values = this.attributeCache.getAdvancedCache().getAll(keys.keySet());
        // Validate attributes
        Map<UUID, Object> attributes = new ConcurrentHashMap<>();
        for (Map.Entry<SessionAttributeKey, String> entry : keys.entrySet()) {
            SessionAttributeKey key = entry.getKey();
            V value = values.get(key);
            if (value != null) {
                try {
                    attributes.put(key.getAttributeId(), this.marshaller.read(value));
                    continue;
                } catch (IOException e) {
                    InfinispanWebLogger.ROOT_LOGGER.failedToActivateSessionAttribute(e, id, entry.getValue());
                }
            } else {
                InfinispanWebLogger.ROOT_LOGGER.missingSessionAttributeCacheEntry(id, entry.getValue());
            }
            if (purgeIfInvalid) {
                this.purge(id);
            }
            return null;
        }
        return new AbstractMap.SimpleImmutableEntry<>(new ConcurrentHashMap<>(names), attributes);
    }

    @Override
    public boolean remove(String id) {
        return this.delete(this.writeOnlyAttributeCache, id);
    }

    @Override
    public boolean purge(String id) {
        return this.delete(this.silentAttributeCache, id);
    }

    private boolean delete(Cache<SessionAttributeKey, V> cache, String id) {
        SessionAttributeNamesKey key = new SessionAttributeNamesKey(id);
        Map<String, UUID> names = this.namesCache.remove(key);
        if (names != null) {
            for (UUID attributeId : names.values()) {
                cache.remove(new SessionAttributeKey(id, attributeId));
            }
        }
        return true;
    }

    @Override
    public SessionAttributes createSessionAttributes(String id, Map.Entry<Map<String, UUID>, Map<UUID, Object>> entry, ImmutableSessionMetaData metaData, C context) {
        SessionAttributeActivationNotifier notifier = this.properties.isPersistent() ? new ImmutableSessionAttributeActivationNotifier<>(this.provider, new CompositeImmutableSession(id, metaData, this.createImmutableSessionAttributes(id, entry)), context) : null;
        return new FineSessionAttributes<>(this.writeOnlyNamesCache, new SessionAttributeNamesKey(id), entry.getKey(), this.writeOnlyAttributeCache, getKeyFactory(id), entry.getValue(), this.marshaller, this.immutability, this.properties, notifier);
    }

    @Override
    public ImmutableSessionAttributes createImmutableSessionAttributes(String id, Map.Entry<Map<String, UUID>, Map<UUID, Object>> entry) {
        return new FineImmutableSessionAttributes(entry.getKey(), entry.getValue());
    }

    private static Function<UUID, SessionAttributeKey> getKeyFactory(String id) {
        return new Function<>() {
            @Override
            public SessionAttributeKey apply(UUID attributeId) {
                return new SessionAttributeKey(id, attributeId);
            }
        };
    }

    private void cascadeEvict(SessionCreationMetaDataKey key) {
        this.namesCache.evict(new SessionAttributeNamesKey(key.getId()));
    }

    private void cascadeEvictAttributes(SessionAttributeNamesKey key, Map<String, UUID> value) {
        String sessionId = key.getId();
        for (UUID attributeId : value.values()) {
            this.executor.execute(() -> this.attributeCache.evict(new SessionAttributeKey(sessionId, attributeId)));
        }
    }

    private void prePassivate(SessionAttributeKey key, V value) {
        this.notify(SessionAttributeActivationNotifier.PRE_PASSIVATE, key, value);
    }

    private void postActivate(SessionAttributeKey key, V value) {
        this.notify(SessionAttributeActivationNotifier.POST_ACTIVATE, key, value);
    }

    private void notify(BiConsumer<SessionAttributeActivationNotifier, Object> notification, SessionAttributeKey key, V value) {
        String sessionId = key.getId();
        try (SessionAttributeActivationNotifier notifier = this.notifierFactory.apply(key.getId())) {
            notification.accept(notifier, this.marshaller.read(value));
        } catch (IOException e) {
            InfinispanWebLogger.ROOT_LOGGER.failedToActivateSessionAttribute(e, sessionId, key.getAttributeId().toString());
        }
    }
}

/*
 * TrustDeck SQL Connector
 * Copyright 2026 Armin Müller
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.trustdeck.connector.sql.trustdeck;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.trustdeck.client.TrustDeckClient;
import org.trustdeck.connector.sql.config.ConnectorProperties;

/**
 * Verifies that configured and dynamically resolved TrustDeck resources are accessible.
 *
 * Static resources are checked during startup, while dynamic domains and entity
 * types are verified on demand. Successful checks are cached for a limited time
 * to avoid repeated authenticated backend requests during normal processing.
 *
 * @author Armin Müller
 */
@Component
public class TrustDeckPreflight {

	/** Lifetime of successful dynamic resource checks. */
	private static final Duration CACHE_TTL = Duration.ofMinutes(5);

	/** Shared authenticated TrustDeck client used for resource verification. */
	private final TrustDeckClient client;

	/** Connector configuration containing operation and target settings. */
	private final ConnectorProperties properties;

	/** Expiring cache of successfully verified TrustDeck resources. */
	private final ConcurrentMap<String, Instant> verified = new ConcurrentHashMap<>();

	/** Whether authenticated preflight checks are enabled for this connector instance. */
	private final boolean enabled;

	/**
	 * Creates the authenticated TrustDeck preflight checker.
	 *
	 * @param client shared authenticated TrustDeck client
	 * @param properties connector configuration
	 * @param enabled whether preflight checks are enabled
	 */
	public TrustDeckPreflight(TrustDeckClient client, ConnectorProperties properties, @Value("${connector.preflight.enabled:true}") boolean enabled) {
		this.client = client;
		this.properties = properties;
		this.enabled = enabled;
	}

	/**
	 * Verifies configured static TrustDeck resources after application startup.
	 *
	 * Entity operations verify the configured project and optional fixed entity
	 * type. Pseudonym operations verify a configured constant domain when one is
	 * present. No checks are performed when preflight validation is disabled.
	 */
	@PostConstruct
	public void verifyStaticResources() {
		if (!enabled) {
			return;
		}

		String operation = properties.getOperation().toUpperCase(Locale.ROOT);

		// Verify statically configured entity resources required by the operation
		if (requiresEntity(operation)) {
			verifyProject(properties.getTrustdeck().getProject());

			if (!blank(properties.getTrustdeck().getEntityType())) {
				verifyEntityType(properties.getTrustdeck().getProject(), properties.getTrustdeck().getEntityType());
			}
		}

		// Verify a fixed pseudonym domain when the operation requires one
		if (requiresDomain(operation) && !blank(properties.getMapping().getCommon().getDomainConstant())) {
			verifyDomain(properties.getMapping().getCommon().getDomainConstant());
		}
	}

	/**
	 * Forgets all successful checks, so the next check of every resource queries
	 * the backend again.
	 *
	 * Called when processing pauses, because a cached success would otherwise let
	 * a recovery probe pass without contacting the failed backend.
	 */
	public void invalidate() {
		verified.clear();
	}

	/**
	 * Verifies that a dynamic TrustDeck domain is accessible.
	 *
	 * Successful checks are cached for {@link #CACHE_TTL} before the backend is
	 * queried again.
	 *
	 * @param domain domain name to verify
	 * @throws IllegalArgumentException if the domain is null or blank
	 */
	public void verifyDomain(String domain) {
		if (!enabled) {
			return;
		}

		if (blank(domain)) {
			throw new IllegalArgumentException("A dynamic TrustDeck domain is required.");
		}

		verify("domain:" + domain, () -> client.project(properties.getTrustdeck().getProject()).domains().get(domain));
	}

	/**
	 * Verifies that a dynamic TrustDeck entity type is accessible in the given project.
	 *
	 * Successful checks are cached for {@link #CACHE_TTL} before the backend is
	 * queried again.
	 *
	 * @param project project containing the entity type
	 * @param type entity type name to verify
	 * @throws IllegalArgumentException if the project or entity type is null or blank
	 */
	public void verifyEntityType(String project, String type) {
		if (!enabled) {
			return;
		}

		if (blank(project) || blank(type)) {
			throw new IllegalArgumentException("A TrustDeck project and entity type are required.");
		}

		verify("type:" + project + ":" + type, () -> client.project(project).entityTypes().get(type));
	}

	/**
	 * Verifies that a TrustDeck project is accessible.
	 *
	 * Successful checks are cached for {@link #CACHE_TTL} before the backend is
	 * queried again.
	 *
	 * @param project project name to verify
	 * @throws IllegalArgumentException if the project is null or blank
	 */
	private void verifyProject(String project) {
		if (blank(project)) {
			throw new IllegalArgumentException("A TrustDeck project is required.");
		}

		verify("project:" + project, () -> client.projects().get(project));
	}

	/**
	 * Executes one authenticated resource check unless a recent successful result exists.
	 *
	 * Failed checks are never cached, so the next invocation immediately attempts
	 * the backend request again.
	 *
	 * @param key unique cache key for the verified resource
	 * @param check authenticated resource lookup to execute
	 */
	private void verify(String key, Runnable check) {
		Instant now = Instant.now();
		Instant previous = verified.get(key);

		// Reuse recent successful checks to avoid unnecessary backend requests
		if (previous != null && previous.plus(CACHE_TTL).isAfter(now)) {
			return;
		}

		check.run();
		verified.put(key, now);
	}

	/**
	 * Determines whether the configured operation requires a pseudonym domain.
	 *
	 * @param operation normalized connector operation
	 * @return {@code true} if the operation requires a domain, otherwise {@code false}
	 */
	private boolean requiresDomain(String operation) {
		return operation.equals("CREATE_PSEUDONYM")
				|| operation.equals("UPDATE_PSEUDONYM")
				|| operation.equals("DELETE_PSEUDONYM")
				|| operation.equals("CREATE_ENTITY_AND_PSEUDONYM");
	}

	/**
	 * Determines whether the configured operation requires a project and entity type.
	 *
	 * @param operation normalized connector operation
	 * @return {@code true} if the operation requires entity resources, otherwise {@code false}
	 */
	private boolean requiresEntity(String operation) {
		return operation.equals("CREATE_ENTITY")
				|| operation.equals("UPDATE_ENTITY")
				|| operation.equals("DELETE_ENTITY")
				|| operation.equals("CREATE_ENTITY_AND_PSEUDONYM");
	}

	/**
	 * Checks whether a configuration value is absent or contains only whitespace.
	 *
	 * @param value value to check
	 * @return {@code true} if the value is null or blank, otherwise {@code false}
	 */
	private boolean blank(String value) {
		return value == null || value.isBlank();
	}
}

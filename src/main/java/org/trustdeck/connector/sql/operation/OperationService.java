/*
 * TrustDeck SQL Connector
 * Copyright 2026 Armin Müller
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.trustdeck.connector.sql.operation;

import java.util.List;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.trustdeck.client.ProjectScope;
import org.trustdeck.client.TrustDeckClient;
import org.trustdeck.client.exception.TrustDeckResponseException;
import org.trustdeck.client.model.Entity;
import org.trustdeck.client.model.IdentifierItem;
import org.trustdeck.client.model.Pseudonym;
import org.trustdeck.client.model.PseudonymUpdate;
import org.trustdeck.connector.sql.config.ConnectorProperties;
import org.trustdeck.connector.sql.mapping.MappedMessage;

/**
 * Executes mapped connector operations against the TrustDeck client.
 *
 * The service dispatches each mapped message to the configured pseudonym or
 * entity operation. Combined entity-and-pseudonym creation retains completed
 * entity stages by source correlation so retries can continue without creating
 * the entity again.
 *
 * @author Armin Müller
 */
@Slf4j
@Service
public class OperationService {

	/** Connector configuration defining the selected operation and fixed targets. */
	private final ConnectorProperties properties;

	/** The scope in which the operations are performed. */
	private final ProjectScope projectScope;

	/** LRU-cache of completed entity stages for combined-operation retries. */
	private final Map<String, UUID> combinedStages = new LinkedHashMap<>(16, 0.75f, true) {

		/** This Map's UID. */
		private static final long serialVersionUID = -9108611188653229983L;

		/**
		 * Removes the least recently accessed entry once the stage cache reaches
		 * its configured maximum size.
		 *
		 * @param eldest least recently accessed map entry
		 * @return {@code true} when the cache contains more than 10,000 entries
		 */
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, UUID> eldest) {
			return size() > 10_000;
		}
	};

	/**
	 * Creates the operation service.
	 *
	 * @param client shared TrustDeck client used to execute backend operations
	 * @param properties connector configuration
	 */
	public OperationService(TrustDeckClient client, ConnectorProperties properties) {
		this.properties = properties;
		this.projectScope = client.project(properties.getTrustdeck().getProject());
	}

	/**
	 * Executes one mapped message using the configured connector operation.
	 *
	 * No source correlation is supplied, so combined operations cannot reuse a
	 * previously completed entity stage through this overload.
	 *
	 * @param message mapped connector input
	 * @return object returned by the executed TrustDeck operation
	 */
	public Object execute(MappedMessage message) {
		return execute(message, null);
	}

	/**
	 * Executes one mapped message using the configured connector operation.
	 *
	 * The optional source correlation is used by combined entity-and-pseudonym
	 * creation to retain and reuse a successfully completed entity stage during
	 * retries.
	 *
	 * @param message mapped connector input
	 * @param correlation stable source table and key correlation, or {@code null}
	 * @return object returned by the executed TrustDeck operation
	 * @throws IllegalStateException if the configured operation is unsupported
	 */
	public Object execute(MappedMessage message, String correlation) {
		return switch (properties.getOperation()) {
			case "CREATE_PSEUDONYM" -> createPseudonym(message);
			case "UPDATE_PSEUDONYM" -> updatePseudonym(message);
			case "DELETE_PSEUDONYM" -> deletePseudonym(message);
			case "CREATE_ENTITY" -> createEntity(message);
			case "UPDATE_ENTITY" -> updateEntity(message);
			case "DELETE_ENTITY" -> deleteEntity(message);
			case "CREATE_ENTITY_AND_PSEUDONYM" -> createEntityAndPseudonym(message, correlation);
			default -> throw new IllegalStateException("Unsupported connector operation.");
		};
	}

	/**
	 * Returns the entity UUID retained for a completed first stage of a combined operation.
	 *
	 * @param correlation source table and key correlation
	 * @return retained entity UUID, or {@code null} if no completed stage is known
	 */
	public UUID completedUuid(String correlation) {
		synchronized (combinedStages) {
			return combinedStages.get(correlation);
		}
	}

	/**
	 * Creates a pseudonym-object from the mapped identifier and validity values.
	 *
	 * Only explicitly mapped validity settings are included in the request.
	 * TrustDeck already answers an exact duplicate with 200 when the domain
	 * allows one pseudonym per identifier. Every other rejection of an imported
	 * pseudonym is a body-less 422, which is resolved by {@link #resolveRejectedImport}.
	 *
	 * @param message mapped connector input
	 * @return created or already existing pseudonym
	 * @throws PseudonymConflictException if the imported pseudonym contradicts the domain
	 */
	private Pseudonym createPseudonym(MappedMessage message) {
		Pseudonym pseudonym = Pseudonym.builder()
				.identifierItem(IdentifierItem.builder().identifier(message.identifier()).idType(message.idType()).build())
				.psn(message.psn())
				.validFrom(message.validFrom())
				.validTo(message.validTo())
				.validityTime(message.validityTime())
				.build();

		try {
			return projectScope.pseudonyms(message.domain()).create(pseudonym, false);
		} catch (TrustDeckResponseException rejection) {
			if (rejection.getStatusCode() != 422 || message.psn() == null || message.psn().isBlank()) {
				throw rejection;
			}

			return resolveRejectedImport(message, rejection);
		}
	}

	/**
	 * Determines why TrustDeck rejected an imported pseudonym with 422.
	 *
	 * The pseudonym value is looked up first: if it already belongs to the same
	 * identifier and idType, the record was imported before (for example in a
	 * domain that allows several pseudonyms per identifier) and counts as
	 * success. If it belongs to someone else, or the identifier already holds a
	 * different pseudonym, the record contradicts the domain. Otherwise the
	 * rejection has another cause, such as an expired domain or the same
	 * identifier and pseudonym being stored in a different domain, and the
	 * original rejection is rethrown.
	 *
	 * @param message mapped connector input with an imported pseudonym
	 * @param rejection the original 422 rejection
	 * @return the identical, already existing pseudonym
	 * @throws PseudonymConflictException if the imported pseudonym contradicts the domain
	 * @throws TrustDeckResponseException the original rejection if no conflict was found
	 */
	private Pseudonym resolveRejectedImport(MappedMessage message, TrustDeckResponseException rejection) {
		String psn = message.psn().trim();

		Pseudonym byPsn = lookup(() -> projectScope.pseudonyms(message.domain()).get(psn), rejection);
		if (byPsn != null) {
			if (sameIdentifier(byPsn, message)) {
				return byPsn;
			}

			throw new PseudonymConflictException(PseudonymConflictException.Reason.PSEUDONYM_ASSIGNED_ELSEWHERE, rejection);
		}

		Pseudonym byIdentifier = lookup(() -> projectScope.pseudonyms(message.domain())
				.get(IdentifierItem.builder().identifier(message.identifier()).idType(message.idType()).build()), rejection);
		if (byIdentifier != null) {
			throw new PseudonymConflictException(PseudonymConflictException.Reason.IDENTIFIER_HAS_OTHER_PSEUDONYM, rejection);
		}

		throw rejection;
	}

	/**
	 * Runs a pseudonym lookup used to explain a rejection.
	 *
	 * A 404 means nothing is stored. A missing read permission (403) leaves the
	 * reason unknown, so the original rejection is rethrown instead of pausing
	 * the connector. Other failures, such as an outage, propagate unchanged.
	 *
	 * @param lookup the lookup to run
	 * @param rejection the original rejection
	 * @return the stored pseudonym, or {@code null} if none exists
	 */
	private Pseudonym lookup(Supplier<Pseudonym> lookup, TrustDeckResponseException rejection) {
		try {
			return lookup.get();
		} catch (TrustDeckResponseException failure) {
			if (failure.getStatusCode() == 404) {
				return null;
			}

			if (failure.getStatusCode() == 403) {
				log.warn("Cannot explain a rejected pseudonym import: the connector lacks pseudonym:read permission.");
				throw rejection;
			}

			throw failure;
		}
	}

	/**
	 * Tests whether a stored pseudonym belongs to the identifier and idType of a message.
	 *
	 * @param stored stored pseudonym
	 * @param message mapped connector input
	 * @return {@code true} if identifier and idType match
	 */
	private static boolean sameIdentifier(Pseudonym stored, MappedMessage message) {
		IdentifierItem item = stored.getIdentifierItem();

		return item != null && message.identifier() != null && message.identifier().equals(item.getIdentifier())
				&& message.idType() != null && message.idType().equals(item.getIdType());
	}

	/**
	 * Updates a pseudonym using explicit current and replacement selectors.
	 *
	 * A complete update is used when the domain, identifier, or pseudonym value
	 * changes. Otherwise only validity information is updated.
	 *
	 * @param message mapped connector input
	 * @return updated pseudonym
	 * @throws IllegalArgumentException if the existing selector is incomplete or replacement identifier fields are only partially mapped
	 */
	private Pseudonym updatePseudonym(MappedMessage message) {
		IdentifierItem oldItem = item(message.oldIdentifier(), message.oldIdType());

		if (oldItem == null) {
			throw new IllegalArgumentException("Pseudonym update needs the complete old selector.");
		}

		if ((message.newIdentifier() == null) != (message.newIdType() == null)) {
			throw new IllegalArgumentException("Replacement identifier and id type must be mapped together.");
		}

		PseudonymUpdate update = PseudonymUpdate.builder()
				.oldIdentifierItem(oldItem)
				.oldPsn(message.oldPsn())
				.newIdentifierItem(item(message.newIdentifier(), message.newIdType()))
				.newPsn(message.newPsn())
				.validFrom(message.validFrom())
				.validTo(message.validTo())
				.build();

		String domain = message.oldDomain() == null ? message.domain() : message.oldDomain();
		boolean complete = message.newDomain() != null || message.newIdentifier() != null || message.newPsn() != null;

		// Changes to identity or domain require the complete pseudonym update endpoint
		if (complete) {
			update.setNewDomainName(message.newDomain() == null ? domain : message.newDomain());

			return projectScope.pseudonyms(domain).updateComplete(update, false);
		}

		return projectScope.pseudonyms(domain).update(update);
	}

	/**
	 * Deletes a pseudonym using the most specific available selector.
	 *
	 * A mapped pseudonym value is preferred. Otherwise the pseudonym is first
	 * resolved by identifier and identifier type before deletion.
	 *
	 * @param message mapped connector input
	 * @return {@code true} when the pseudonym was deleted
	 * @throws IllegalArgumentException if neither a pseudonym value nor a complete identifier selector is available
	 * @throws IllegalStateException if identifier lookup returns no deletable pseudonym
	 */
	private Boolean deletePseudonym(MappedMessage message) {
		if (message.psn() != null) {
			return projectScope.pseudonyms(message.domain()).delete(message.psn());
		}

		if (message.identifier() == null || message.idType() == null) {
			throw new IllegalArgumentException("Pseudonym deletion needs psn or identifier and idType.");
		}

		// Resolve the generated pseudonym value before calling the value-based delete endpoint
		Pseudonym selected = projectScope.pseudonyms(message.domain())
				.get(IdentifierItem.builder().identifier(message.identifier()).idType(message.idType()).build());

		if (selected == null || selected.getPsn() == null) {
			throw new IllegalStateException("Identifier lookup returned no deletable pseudonym.");
		}

		return projectScope.pseudonyms(message.domain()).delete(selected.getPsn());
	}

	/**
	 * Creates an entity from the mapped entity data.
	 *
	 * @param message mapped connector input
	 * @return created entity
	 */
	private Entity createEntity(MappedMessage message) {
		Entity e = Entity.builder().data(message.entityData()).build();

		return projectScope.entities(message.entityType()).create(e);
	}

	/**
	 * Replaces entity data for the mapped TrustDeck entity UUID.
	 *
	 * @param message mapped connector input
	 * @return updated entity
	 */
	private Entity updateEntity(MappedMessage message) {
		Entity e = Entity.builder().data(message.entityData()).build();

		return projectScope.entities(message.entityType()).update(message.entityId(), e);
	}

	/**
	 * Deletes or tombstones the mapped TrustDeck entity, depending on backend behavior.
	 *
	 * @param message mapped connector input
	 * @return {@code true} when the entity deletion was accepted
	 */
	private Boolean deleteEntity(MappedMessage message) {
		return projectScope.entities(message.entityType()).delete(message.entityId());
	}

	/**
	 * Creates an entity and then creates or reuses its requested target pseudonym.
	 *
	 * The successfully created entity UUID is retained by source correlation before
	 * the second stage starts. This allows a retry to continue with pseudonym
	 * creation without creating another entity. No rollback is attempted when the
	 * second stage fails.
	 *
	 * @param message mapped connector input
	 * @param correlation stable source correlation used to resume a retried row, or {@code null}
	 * @return existing or newly created target pseudonym
	 * @throws IllegalStateException if entity creation returns no TrustDeck UUID
	 */
	private Pseudonym createEntityAndPseudonym(MappedMessage message, String correlation) {
		UUID entityId;

		// Reuse a completed entity stage when retrying the same source record
		synchronized (combinedStages) {
			entityId = correlation == null ? null : combinedStages.get(correlation);
		}

		if (entityId == null) {
			Entity entity = createEntity(message);

			if (entity == null || entity.getTrustdeckID() == null) {
				throw new IllegalStateException("Entity creation returned no TrustDeck UUID.");
			}

			entityId = entity.getTrustdeckID();

			// Persist the completed first stage before attempting pseudonym creation
			if (correlation != null) {
				synchronized (combinedStages) {
					combinedStages.put(correlation, entityId);
				}
			}
		}

		// Verify automatic backend pseudonymization before creating another target pseudonym
		List<Pseudonym> associated = projectScope.entities(message.entityType()).getPseudonyms(entityId).items();

		for (Pseudonym existing : associated) {
			IdentifierItem existingItem = existing.getIdentifierItem();

			boolean sameEntity = existingItem != null
					&& entityId.toString().equals(existingItem.getIdentifier())
					&& "TrustDeckID".equals(existingItem.getIdType());

			boolean sameRequestedValue = message.psn() == null || message.psn().equals(existing.getPsn());

			// Reuse a matching automatically created pseudonym instead of creating a duplicate
			if (sameEntity && message.domain().equals(existing.getDomainName()) && sameRequestedValue) {
				return existing;
			}
		}

		Pseudonym pseudonym = pseudonym(entityId.toString(), "TrustDeckID", message.psn(),
				message.validityTime(), message.validFrom(), message.validTo());

		return projectScope.pseudonyms(message.domain()).create(pseudonym, false);
	}

	/**
	 * Builds a pseudonym request payload from mapped identifier and validity values.
	 *
	 * @param identifier pseudonym identifier
	 * @param idType pseudonym identifier type
	 * @param psn optional imported pseudonym value
	 * @param validityTime optional validity duration
	 * @param validFrom optional validity start
	 * @param validTo optional validity end
	 * @return configured pseudonym payload
	 */
	private Pseudonym pseudonym(String identifier, String idType, String psn, String validityTime, LocalDateTime validFrom, LocalDateTime validTo) {
		return Pseudonym.builder()
				.identifierItem(item(identifier, idType))
				.psn(psn)
				.validityTime(validityTime)
				.validFrom(validFrom)
				.validTo(validTo)
				.build();
	}

	/**
	 * Builds an identifier item when at least one selector value is present.
	 *
	 * @param identifier identifier value
	 * @param idType identifier type
	 * @return identifier item, or {@code null} if both values are absent
	 */
	private IdentifierItem item(String identifier, String idType) {
		return identifier == null && idType == null ? null : IdentifierItem.builder().identifier(identifier).idType(idType).build();
	}
}

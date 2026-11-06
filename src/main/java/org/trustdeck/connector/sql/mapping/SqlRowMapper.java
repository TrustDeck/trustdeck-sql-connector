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
package org.trustdeck.connector.sql.mapping;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.zone.ZoneRulesException;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.trustdeck.connector.sql.config.ConnectorProperties;
import org.trustdeck.connector.sql.database.SourceRow;

/**
 * Maps source rows to validated connector messages.
 *
 * The mapper reads the configured columns, converts their SQL values to the
 * values TrustDeck expects, builds entity data, and validates operation-specific
 * requirements before returning a {@link MappedMessage}. Error messages name
 * the affected column but never contain row values.
 *
 * @author Armin Müller
 */
@Component
public class SqlRowMapper {

	/** Factory for entity data nodes; decimals keep their exact scale. */
	private static final JsonNodeFactory NODES = JsonNodeFactory.withExactBigDecimals(true);

	/** Connector configuration containing the column mappings. */
	private final ConnectorProperties properties;

	/**
	 * Creates the row mapper.
	 *
	 * @param properties connector configuration containing mapping settings
	 */
	public SqlRowMapper(ConnectorProperties properties) {
		this.properties = properties;
	}

	/**
	 * Maps one source row according to the configured connector operation.
	 *
	 * @param row source row
	 * @return mapped and validated connector message
	 * @throws MappingException if a mapped value is missing, has an unsupported type, or is invalid
	 */
	public MappedMessage map(SourceRow row) {
		String operation = properties.getOperation();
		var common = properties.getMapping().getCommon();
		var update = properties.getMapping().getUpdatePseudonym();

		// Resolve common pseudonym, validity, provenance, and entity mappings
		String domain = text(row, common.getDomainConstant(), common.getDomainColumn());
		String identifier = text(row, null, common.getIdentifierColumn());
		String idType = text(row, null, common.getIdTypeColumn());
		String psn = text(row, null, common.getPsnColumn());
		String validityTime = text(row, null, common.getValidityTimeColumn());
		LocalDateTime validFrom = datetime(row, common.getValidFromColumn());
		LocalDateTime validTo = datetime(row, common.getValidToColumn());
		String username = text(row, null, common.getUsernameColumn());
		String entityType = text(row, properties.getTrustdeck().getEntityType(), common.getEntityTypeColumn());
		UUID entityId = uuid(row, common.getEntityIdColumn());
		ObjectNode entityData = entityData(row, common.getEntityFields());

		// Resolve fields used specifically by pseudonym update operations
		String oldIdentifier = text(row, null, update.getOldIdentifierColumn());
		String oldIdType = text(row, null, update.getOldIdTypeColumn());
		String oldPsn = text(row, null, update.getOldPsnColumn());
		String newIdentifier = text(row, null, update.getNewIdentifierColumn());
		String newIdType = text(row, null, update.getNewIdTypeColumn());
		String newPsn = text(row, null, update.getNewPsnColumn());
		String oldDomain = text(row, null, update.getOldDomainColumn());
		String newDomain = text(row, null, update.getNewDomainColumn());

		// Validate values required by the selected operation
		if (operation.equals("CREATE_PSEUDONYM") || operation.equals("UPDATE_PSEUDONYM")) {
			require(identifier, "identifier-column");
			require(idType, "id-type-column");
		}

		if (operation.equals("DELETE_PSEUDONYM") && (identifier == null || idType == null) && psn == null) {
			throw new MappingException("DELETE_PSEUDONYM requires a psn or an identifier and id type value.");
		}

		if ((operation.equals("CREATE_PSEUDONYM") || operation.equals("UPDATE_PSEUDONYM")
				|| operation.equals("DELETE_PSEUDONYM") || operation.equals("CREATE_ENTITY_AND_PSEUDONYM"))
				&& domain == null) {
			throw new MappingException("A domain constant or domain column value is required.");
		}

		if ((operation.equals("UPDATE_ENTITY") || operation.equals("DELETE_ENTITY")) && entityId == null) {
			throw new MappingException("Entity update/delete requires a TrustDeck UUID in the entity id column.");
		}

		if (operation.contains("ENTITY") && entityType == null) {
			throw new MappingException("Entity operations require a fixed entity type or an entity type column value.");
		}

		return new MappedMessage(domain, identifier, idType, psn, validityTime, validFrom, validTo, username, entityType,
				entityId, entityData, oldIdentifier, oldIdType, oldPsn, newIdentifier, newIdType, newPsn, oldDomain, newDomain);
	}

	/**
	 * Resolves a text value from a configured constant or column.
	 *
	 * A non-blank constant takes precedence over the column. Text columns are
	 * used unchanged, including leading zeros. Integer and decimal columns are
	 * converted to their plain decimal text, UUID columns to their canonical
	 * form. Other types, such as floating-point, boolean, date, or binary
	 * columns, are rejected because their text form is not unambiguous.
	 *
	 * @param row source row
	 * @param constant optional constant value
	 * @param column optional column name
	 * @return resolved non-blank text, or {@code null} if absent, NULL, or blank
	 * @throws MappingException if the column has an unsupported type
	 */
	private String text(SourceRow row, String constant, String column) {
		if (constant != null && !constant.isBlank()) {
			return constant;
		}

		if (column == null || column.isBlank()) {
			return null;
		}

		String text = switch (row.value(column)) {
			case null -> null;
			case CharSequence characters -> characters.toString();
			case Integer number -> number.toString();
			case Long number -> number.toString();
			case Short number -> number.toString();
			case Byte number -> number.toString();
			case BigInteger number -> number.toString();
			case BigDecimal number -> number.toPlainString();
			case UUID uuid -> uuid.toString();
			default -> throw new MappingException("Column " + column.trim() + " must contain text, an integer, a decimal, or a UUID.");
		};

		return text == null || text.isBlank() ? null : text;
	}

	/**
	 * Resolves and converts a mapped time stamp to a UTC {@link LocalDateTime}.
	 *
	 * Time stamps with an offset are converted directly to UTC. Time stamps and
	 * dates without an offset are interpreted in the configured source timezone;
	 * a date stands for the start of its day. Text columns are parsed as ISO time
	 * stamps with offset or with the configured format. Local times that are
	 * ambiguous or do not exist because of a daylight-saving change are rejected.
	 *
	 * @param row source row
	 * @param column configured time stamp column
	 * @return time stamp converted to UTC, or {@code null} if no value is mapped
	 * @throws MappingException if the time stamp, format, or timezone is invalid
	 */
	private LocalDateTime datetime(SourceRow row, String column) {
		if (column == null || column.isBlank()) {
			return null;
		}

		try {
			return switch (row.value(column)) {
				case null -> null;
				case OffsetDateTime timestamp -> timestamp.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
				case LocalDateTime timestamp -> toUtc(timestamp);
				case LocalDate date -> toUtc(date.atStartOfDay());
				case CharSequence characters -> parse(characters.toString().trim(), column);
				default -> throw new MappingException("Column " + column.trim() + " must contain a date, a time stamp, or text.");
			};
		} catch (DateTimeParseException | ZoneRulesException exception) {
			throw new MappingException("Column " + column.trim() + " contains an invalid time stamp.", exception);
		}
	}

	/**
	 * Parses a time stamp stored as text.
	 *
	 * @param text non-null time stamp text
	 * @param column column name used in error messages
	 * @return time stamp converted to UTC, or {@code null} for blank text
	 * @throws MappingException if an offset-free time stamp is found but no format is configured
	 */
	private LocalDateTime parse(String text, String column) {
		if (text.isEmpty()) {
			return null;
		}

		// Prefer time stamps that already contain an explicit UTC offset
		try {
			return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
		} catch (DateTimeParseException ignored) {
			// Offset-free time stamps require an explicit format and source timezone
			String pattern = properties.getMapping().getCommon().getDatetimeFormat();
			if (pattern == null || pattern.isBlank()) {
				throw new MappingException("Column " + column.trim() + " contains an offset-free time stamp, which requires mapping.common.datetime-format.");
			}

			DateTimeFormatter formatter = new DateTimeFormatterBuilder().appendPattern(pattern).toFormatter().withResolverStyle(ResolverStyle.STRICT);

			return toUtc(LocalDateTime.parse(text, formatter));
		}
	}

	/**
	 * Interprets an offset-free time stamp in the source timezone and converts it to UTC.
	 *
	 * @param local offset-free time stamp
	 * @return UTC wall-clock time
	 * @throws MappingException if the local time is ambiguous or does not exist in the source timezone
	 */
	private LocalDateTime toUtc(LocalDateTime local) {
		ZoneId zone = ZoneId.of(properties.getMapping().getCommon().getSourceTimezone());
		List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);

		// Reject daylight-saving overlaps and gaps instead of choosing an offset implicitly
		if (offsets.size() != 1) {
			throw new MappingException("Time stamp falls in an ambiguous or nonexistent local time.");
		}

		return ZonedDateTime.ofLocal(local, zone, offsets.getFirst()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
	}

	/**
	 * Resolves a TrustDeck entity UUID.
	 *
	 * @param row source row
	 * @param column configured UUID column
	 * @return UUID, or {@code null} if no value is mapped
	 * @throws MappingException if the value is not a valid UUID
	 */
	private UUID uuid(SourceRow row, String column) {
		if (column != null && !column.isBlank() && row.value(column) instanceof UUID uuid) {
			return uuid;
		}

		String text = text(row, null, column);
		if (text == null) {
			return null;
		}

		try {
			return UUID.fromString(text.trim());
		} catch (IllegalArgumentException exception) {
			throw new MappingException("Column " + column.trim() + " does not contain a UUID.", exception);
		}
	}

	/**
	 * Builds entity data from the configured column-to-target field mappings.
	 *
	 * SQL types are preserved as far as JSON allows: text stays text (including
	 * leading zeros), integers and decimals become exact JSON numbers, booleans
	 * become JSON booleans, and date and time values become ISO-8601 text. Missing
	 * optional values are skipped, while missing required values cause processing to fail.
	 *
	 * @param row source row
	 * @param fields configured entity field mappings
	 * @return entity data assembled from the mapped fields
	 * @throws MappingException if a required value is absent or a value has an unsupported type
	 */
	private ObjectNode entityData(SourceRow row, List<ConnectorProperties.FieldMapping> fields) {
		ObjectNode result = NODES.objectNode();

		if (fields == null) {
			return result;
		}

		for (ConnectorProperties.FieldMapping field : fields) {
			// Constants override column values while preserving the same target handling
			JsonNode value = field.getConstant() == null ? json(row.value(field.getColumn()), field.getColumn()) : NODES.textNode(field.getConstant());

			if (value == null) {
				if (field.isRequired()) {
					throw new MappingException("Required entity column is NULL: " + field.getColumn());
				}

				continue;
			}

			setPointer(result, field.getTargetPath(), value);
		}

		return result;
	}

	/**
	 * Converts one SQL value to a JSON value.
	 *
	 * @param value column value
	 * @param column column name used in error messages
	 * @return JSON value, or {@code null} for SQL NULL
	 * @throws MappingException if the value has a type that cannot be represented safely
	 */
	private static JsonNode json(Object value, String column) {
		return switch (value) {
			case null -> null;
			case CharSequence characters -> NODES.textNode(characters.toString());
			case Integer number -> NODES.numberNode(number);
			case Long number -> NODES.numberNode(number);
			case Short number -> NODES.numberNode(number);
			case Byte number -> NODES.numberNode(number);
			case BigInteger number -> NODES.numberNode(number);
			case BigDecimal number -> NODES.numberNode(number);
			case Double number when Double.isFinite(number) -> NODES.numberNode(number);
			case Float number when Float.isFinite(number) -> NODES.numberNode(number);
			case Boolean bool -> NODES.booleanNode(bool);
			case LocalDate date -> NODES.textNode(date.toString());
			case LocalTime time -> NODES.textNode(time.toString());
			case LocalDateTime timestamp -> NODES.textNode(timestamp.toString());
			case OffsetDateTime timestamp -> NODES.textNode(timestamp.toString());
			case UUID uuid -> NODES.textNode(uuid.toString());
			default -> throw new MappingException("Column " + column + " has a type that cannot be mapped to entity data.");
		};
	}

	/**
	 * Requires a mapped text value to be present.
	 *
	 * @param value mapped value to validate
	 * @param name mapping name used in the validation message
	 * @throws MappingException if the value is {@code null} or blank
	 */
	private void require(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new MappingException(name + " must resolve to a nonblank value.");
		}
	}

	/**
	 * Writes a mapped value to a shallow or nested object JSON Pointer.
	 *
	 * Missing intermediate objects are created as required. JSON Pointer escape
	 * sequences in path segments are decoded before the target fields are written.
	 * A bare name without a leading slash is treated as a top-level field.
	 *
	 * @param target target entity data object
	 * @param path configured target JSON Pointer
	 * @param value mapped JSON value
	 */
	private void setPointer(ObjectNode target, String path, JsonNode value) {
		String pointer = path.startsWith("/") ? path : "/" + path;
		String[] parts = pointer.substring(1).split("/");
		ObjectNode current = target;

		// Traverse the target path and create missing intermediate objects
		for (int i = 0; i < parts.length - 1; i++) {
			String part = parts[i].replace("~1", "/").replace("~0", "~");
			JsonNode child = current.get(part);
			current = child != null && child.isObject() ? (ObjectNode) child : current.putObject(part);
		}

		current.set(parts[parts.length - 1].replace("~1", "/").replace("~0", "~"), value);
	}
}

package k4k.claudecraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Small builder for the object JSON schemas used as tool input schemas.
 */
public final class Schema {
	private final JsonObject properties = new JsonObject();
	private final JsonArray required = new JsonArray();

	private Schema() {
	}

	/**
	 * @return a new empty object schema
	 */
	public static Schema object() {
		return new Schema();
	}

	/**
	 * @param name        property name
	 * @param description property description
	 * @param isRequired  whether the property is required
	 * @return this builder
	 */
	public Schema string(String name, String description, boolean isRequired) {
		return property(name, "string", description, isRequired);
	}

	/**
	 * @param name        property name
	 * @param description property description
	 * @param isRequired  whether the property is required
	 * @param values      allowed values
	 * @return this builder
	 */
	public Schema enumeration(String name, String description, boolean isRequired, String... values) {
		property(name, "string", description, isRequired);
		JsonArray allowed = new JsonArray();
		for (String value : values) {
			allowed.add(value);
		}
		properties.getAsJsonObject(name).add("enum", allowed);
		return this;
	}

	/**
	 * @param name        property name
	 * @param description property description
	 * @param isRequired  whether the property is required
	 * @return this builder
	 */
	public Schema integer(String name, String description, boolean isRequired) {
		return property(name, "integer", description, isRequired);
	}

	/**
	 * @param name        property name
	 * @param description property description
	 * @param isRequired  whether the property is required
	 * @return this builder
	 */
	public Schema number(String name, String description, boolean isRequired) {
		return property(name, "number", description, isRequired);
	}

	/**
	 * @param name        property name
	 * @param description property description
	 * @param isRequired  whether the property is required
	 * @return this builder
	 */
	public Schema bool(String name, String description, boolean isRequired) {
		return property(name, "boolean", description, isRequired);
	}

	/**
	 * @return the finished schema
	 */
	public JsonObject build() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		if (!required.isEmpty()) {
			schema.add("required", required);
		}
		return schema;
	}

	/**
	 * Adds a property of the given JSON type.
	 */
	private Schema property(String name, String type, String description, boolean isRequired) {
		JsonObject property = new JsonObject();
		property.addProperty("type", type);
		property.addProperty("description", description);
		properties.add(name, property);
		if (isRequired) {
			required.add(name);
		}
		return this;
	}
}

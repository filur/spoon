package spoon.smpl.label;

import spoon.smpl.Label;
import spoon.smpl.LabelMatchResult;
import spoon.smpl.LabelMatchResultImpl;
import spoon.smpl.formula.MetadataPredicate;
import spoon.smpl.formula.Predicate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A MetadataLabel is used to associate a CTL state with an exported arbitrary metadata key-value pair.
 */
public class MetadataLabel implements Label {
	/**
	 * Create a new metadata key-value pair label.
	 *
	 * @param key   Metadata key
	 * @param value Metadata value
	 */
	public MetadataLabel(String key, Object value) {
		this.key = key;
		this.value = value;
	}

	/**
	 * Check if a given predicate matches the label, binding the appropriate environment variable
	 * on a successful match.
	 *
	 * @param obj Predicate to test
	 * @return True if the predicate is a MetadataPredicate with matching key, false otherwise
	 */
	@Override
	public boolean matches(Predicate obj) {
		if (obj instanceof MetadataPredicate && ((MetadataPredicate) obj).getKey().equals(key)) {
			metavarBindings = new HashMap<>();
			metavarBindings.put(((MetadataPredicate) obj).getVarname(), value);
			return true;
		}

		return false;
	}

	/**
	 * Get the match results produced for the most recently matched Predicate.
	 *
	 * @return List of results
	 */
	@Override
	public List<LabelMatchResult> getMatchResults() {
		if (metavarBindings.keySet().size() > 0) {
			return Collections.singletonList(new LabelMatchResultImpl(metavarBindings));
		} else {
			return null;
		}
	}

	/**
	 * Reset/clear metavariable bindings
	 */
	@Override
	public void reset() {
		metavarBindings = new HashMap<>();
	}

	@Override
	public String toString() {
		return "Metadata(" + key + ":" + value.toString() + ")";
	}

	/**
	 * Exported metadata key.
	 */
	private String key;

	/**
	 * Exported metadata value.
	 */
	private Object value;

	/**
	 * The most recently matched metavariable bindings.
	 */
	private Map<String, Object> metavarBindings;
}

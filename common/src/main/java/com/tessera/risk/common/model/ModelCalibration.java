package com.tessera.risk.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns the classifier's raw probability back into a true chance of an incident.
 *
 * <h2>Why the raw probability is wrong</h2>
 * Incident windows are rare — about one in eleven — so training weights each one
 * roughly ten times more heavily than a quiet window. Without that the cheapest way
 * to reduce the loss is to predict "no incident" every time, and the model learns
 * nothing. The side effect is that the model is fitted as though the two outcomes
 * were equally common, so every probability it emits is inflated: a window whose
 * real chance of an incident is about one in five comes out as roughly 73%.
 *
 * <h2>Why the correction is exact</h2>
 * Inverse-frequency weighting gives both classes the same total weight, which is the
 * same as training under a 50:50 prior. The model's odds are therefore the true odds
 * divided by the real prior odds, and multiplying them back by
 * {@code positives / negatives} recovers the true odds with no fitting involved:
 *
 * <pre>
 *   corrected odds = raw odds × (positives / negatives)
 *   corrected p    = p·k / (1 − p + p·k),   where k = positives / negatives
 * </pre>
 *
 * Applied to this model it maps the RISKY tier's ~73% to ~21% and the SAFE tier's
 * ~32% to ~4.5%, against measured incident rates of 22.5% and 4.4%.
 *
 * <p>The correction is monotonic, so it never changes which window ranks above
 * another and never changes a yes/no decision — only how confident the number
 * claims to be. The decision threshold was measured on the raw scale and stays
 * there; {@link #correctedThreshold()} is that same cutoff expressed as a chance.
 *
 * <p>Lives in {@code common} because training writes it and the streaming job
 * applies it. Two copies of this arithmetic would be two chances to disagree about
 * what a probability means.
 *
 * @param model             which classifier this describes, for the log and the reader
 * @param decisionThreshold raw-probability cutoff baked into the model
 * @param trainPositives    incident windows in the training split
 * @param trainNegatives    quiet windows in the training split
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelCalibration(
        String model,
        double decisionThreshold,
        long trainPositives,
        long trainNegatives) {

    /** Written next to the persisted pipeline, inside its directory. */
    public static final String FILE_NAME = "tessera-calibration.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ModelCalibration {
        if (trainPositives <= 0 || trainNegatives <= 0) {
            // A split with no examples of one class has no prior to correct by,
            // and a zero here would silently turn every probability into 0 or 1.
            throw new IllegalArgumentException("Calibration needs both classes in training, got "
                    + trainPositives + " positive and " + trainNegatives + " negative");
        }
    }

    /** The real ratio of incident windows to quiet ones in the training data. */
    public double priorOdds() {
        return (double) trainPositives / trainNegatives;
    }

    /** A raw model probability as a true chance of an incident. */
    public double correct(double rawProbability) {
        double p = Math.min(1.0, Math.max(0.0, rawProbability));
        double k = priorOdds();
        double denominator = 1.0 - p + p * k;
        return denominator == 0.0 ? 1.0 : p * k / denominator;
    }

    /** The decision threshold as a chance: the model flags windows at or above this. */
    public double correctedThreshold() {
        return correct(decisionThreshold);
    }

    public String toJson() {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise model calibration", e);
        }
    }

    public static ModelCalibration fromJson(String json) {
        try {
            return MAPPER.readValue(json, ModelCalibration.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed model calibration", e);
        }
    }
}

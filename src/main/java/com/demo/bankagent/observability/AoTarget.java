package com.demo.bankagent.observability;

/**
 * Project and Agent Stream (Galileo: Log Stream) resolved by name at startup. Kept in memory only; the IDs are
 * never written back to the configuration file.
 */
public record AoTarget(String projectName, String projectId, boolean projectCreated,
                       String agentStreamName, String agentStreamId, boolean agentStreamCreated) {
}

package com.demo.bankagent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Look up by name, create when missing" for the Project and the Agent Stream, like the official SDK loggers
 * (splunk_ao SplunkAOLogger._init_project / _init_agent_stream, galileo GalileoLogger).
 *
 * <ul>
 *   <li>o11y (base .../ao/api, X-SF-Token): GET projects?project_name=&amp;type=gen_ai, POST projects,
 *       GET/POST projects/{id}/log_streams. Verified against the SaaS: create 200, duplicate project 422, duplicate stream 400.</li>
 *   <li>galileo (base https://api..., Galileo-API-Key): public OpenAPI /v2/projects/paginated (POST query),
 *       POST /v2/projects, GET/POST /v2/projects/{id}/log_streams. Not verified online (no Galileo key).</li>
 * </ul>
 * A failed create (concurrent creation, 400/409/422 duplicate) is followed by exactly one re-query.
 */
public class AoTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(AoTargetResolver.class);

    private final AoApiClient api;
    private final AoBackendProfile profile;

    public AoTargetResolver(AoApiClient api, AoBackendProfile profile) {
        this.api = api;
        this.profile = profile;
    }

    public AoTarget resolve(String projectName, String streamName) {
        boolean projectCreated = false;
        String projectId = findProject(projectName).orElse(null);
        if (projectId == null) {
            try {
                projectId = createProject(projectName);
                projectCreated = true;
            } catch (AoApiException e) {
                if (!e.isConflict()) {
                    throw e;
                }
                log.info("Project '{}' was created concurrently ({}); looking it up again", projectName, e.getMessage());
                projectId = findProject(projectName).orElseThrow(() -> e);
            }
        }
        boolean streamCreated = false;
        String streamId = findStream(projectId, streamName).orElse(null);
        if (streamId == null) {
            try {
                streamId = createStream(projectId, streamName);
                streamCreated = true;
            } catch (AoApiException e) {
                if (!e.isConflict()) {
                    throw e;
                }
                log.info("Agent stream '{}' was created concurrently ({}); looking it up again", streamName,
                        e.getMessage());
                final String pid = projectId;
                streamId = findStream(pid, streamName).orElseThrow(() -> e);
            }
        }
        return new AoTarget(projectName, projectId, projectCreated, streamName, streamId, streamCreated);
    }

    /** Read-only lookup; never creates anything. */
    public Optional<String> findProject(String name) {
        if (profile == AoBackendProfile.O11Y) {
            JsonNode list = api.get("/projects?project_name=" + encode(name) + "&type=gen_ai");
            return matchProject(list, name);
        }
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("name", "name");
        filter.put("operator", "eq");
        filter.put("value", name);
        filter.put("case_sensitive", true);
        JsonNode page = api.post("/v2/projects/paginated?limit=100", Map.of("filters", List.of(filter)));
        return matchProject(page.path("projects"), name);
    }

    /** Read-only lookup; never creates anything. */
    public Optional<String> findStream(String projectId, String name) {
        String path = profile == AoBackendProfile.O11Y ? "/projects/" + projectId + "/log_streams"
                : "/v2/projects/" + projectId + "/log_streams";
        JsonNode list = api.get(path);
        for (JsonNode stream : list) {
            if (name.equals(stream.path("name").asText(null))) {
                return Optional.of(requireId(stream, "agent stream"));
            }
        }
        return Optional.empty();
    }

    private String createProject(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", "gen_ai");
        if (profile == AoBackendProfile.O11Y) {
            body.put("create_example_templates", false);
        }
        JsonNode created = api.post(profile == AoBackendProfile.O11Y ? "/projects" : "/v2/projects", body);
        return requireId(created, "project");
    }

    private String createStream(String projectId, String name) {
        String path = profile == AoBackendProfile.O11Y ? "/projects/" + projectId + "/log_streams"
                : "/v2/projects/" + projectId + "/log_streams";
        return requireId(api.post(path, Map.of("name", name)), "agent stream");
    }

    private static Optional<String> matchProject(JsonNode list, String name) {
        boolean sameNameOtherType = false;
        for (JsonNode project : list) {
            if (!name.equals(project.path("name").asText(null))) {
                continue;
            }
            JsonNode type = project.path("type");
            if (type.isMissingNode() || type.isNull() || "gen_ai".equals(type.asText())) {
                return Optional.of(requireId(project, "project"));
            }
            sameNameOtherType = true;
        }
        if (sameNameOtherType) {
            throw new AoApiException(200, "Project '" + name + "' exists but is not a gen_ai (Agent Observability) "
                    + "project. Choose another AO_PROJECT_NAME.");
        }
        return Optional.empty();
    }

    private static String requireId(JsonNode node, String what) {
        String id = node.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new AoApiException(200, "The " + what + " response did not contain an id.");
        }
        return id;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

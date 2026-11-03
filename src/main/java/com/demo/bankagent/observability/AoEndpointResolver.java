package com.demo.bankagent.observability;

import com.demo.bankagent.control.ControlFailureMode;
import com.demo.bankagent.control.ControlMode;
import com.demo.bankagent.observability.AoSettings.Secret;
import com.demo.bankagent.observability.AoSettings.Source;
import com.demo.bankagent.observability.AoSettings.Value;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The single place where backend URLs, header names and credentials are derived (task book section 0.4.3).
 * Rule: an EMPTY key means "use the documented rule of the active profile"; a non-empty key overrides it.
 * Values written as template placeholders ("&lt;...&gt;") count as empty.
 *
 * <p>Sources of the rules (see docs/CONFIG-MAPPING.md):
 * <ul>
 *   <li>o11y: splunk/splunk-ao-python@f7a20ed deployment.py (O11yConfig: otlp_endpoint, api_root, crud_token),
 *       exporter/o11y.py (X-SF-Token), agent_control.py (log_stream target), Splunk AO docs "Initialize and
 *       configure Agent Control" (SaaS: https://&lt;realm-host&gt;/ao/agent-control, X-SF-Token).</li>
 *   <li>galileo: galileo-core 4.5.0 base_config.set_api_url (app.galileo.ai -&gt; api.galileo.ai, otherwise
 *       "console" -&gt; "api"), galileo 2.6.0 otel.py (api_url + "otel/traces", Galileo-API-Key), golden demo
 *       setup_env.py (console root + "/api/agent-control").</li>
 *   <li>Agent Control evaluation route: verified against the SaaS (docs/galileo/PROTOCOL-VERIFICATION.md).</li>
 * </ul>
 */
public final class AoEndpointResolver {

    public static final String DEFAULT_PROJECT_NAME = "bank-agent-java";
    public static final String DEFAULT_AGENT_STREAM = "bank-java-demo";
    public static final String DEFAULT_SERVICE_NAME = "bank-agent-java";
    public static final String DEFAULT_AGENT_NAME = "bank-agent-java";
    public static final String DEFAULT_TARGET_TYPE = "log_stream";
    /** Verified against the au0 SaaS (agent-control 8.15.0) and identical to agent-control-sdk 8.11. */
    public static final String DEFAULT_EVALUATION_PATH = "/api/v1/evaluation";
    public static final String DEFAULT_RUNTIME_AUTH_MODE = "jwt";
    public static final String DEFAULT_RUNTIME_TOKEN_HEADER = "X-Agent-Control-Runtime-Token";
    public static final int DEFAULT_MAX_STEER_RETRIES = 2;
    public static final String GALILEO_DEFAULT_CONSOLE = "https://app.galileo.ai";

    private static final Pattern REALM = Pattern.compile("[a-z0-9]+");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    // agent_control/validation.py: lowercase letters, digits, ':', '_' or '-', at least 10 characters.
    private static final Pattern AGENT_NAME = Pattern.compile("[a-z0-9:_-]{10,}");

    private AoEndpointResolver() {
    }

    /** A configuration problem; CONTROL problems only block startup when CONTROL_MODE=remote-api. */
    record Problem(boolean controlOnly, String message) {
    }

    public static AoSettings resolve(AoRawProperties raw) {
        List<Problem> problems = new ArrayList<>();

        boolean observability = parseSwitch(raw.observabilityEnabled(), "OBSERVABILITY_ENABLED");
        boolean content = parseSwitch(raw.demoTraceContent(), "DEMO_TRACE_CONTENT");
        ControlMode controlMode;
        try {
            controlMode = ControlMode.parse(raw.controlMode());
        } catch (IllegalArgumentException e) {
            throw new AoConfigurationException(e.getMessage());
        }

        String realm = text(raw.realm());
        String o11yToken = text(raw.o11yToken());
        String apiKey = text(raw.apiKey());

        // ---- profile selection -------------------------------------------------------------------
        AoBackendProfile profile;
        String profileSource;
        String explicitProfile = text(raw.backendProfile());
        if (explicitProfile != null) {
            profile = AoBackendProfile.parse(explicitProfile).orElseThrow(() -> new AoConfigurationException(
                    "AO_BACKEND_PROFILE must be 'o11y' or 'galileo' (got '" + explicitProfile + "'). A tenant name "
                            + "such as 'splunkse' is not a profile."));
            profileSource = "explicit AO_BACKEND_PROFILE";
        } else {
            boolean optionA = realm != null || o11yToken != null;
            boolean optionBc = apiKey != null;
            if (optionA && optionBc) {
                profile = AoBackendProfile.O11Y;
                profileSource = "ambiguous: credentials of both backends are set";
                problems.add(new Problem(false, "Credentials for option A (AO_REALM / AO_O11Y_TOKEN) and for options "
                        + "B/C (AO_API_KEY) are both set. Set AO_BACKEND_PROFILE=o11y or AO_BACKEND_PROFILE=galileo."));
            } else if (optionBc) {
                profile = AoBackendProfile.GALILEO;
                profileSource = "auto: only AO_API_KEY is set";
            } else if (optionA) {
                profile = AoBackendProfile.O11Y;
                profileSource = "auto: only option A credentials (AO_REALM / AO_O11Y_TOKEN) are set";
            } else {
                profile = AoBackendProfile.O11Y;
                profileSource = "default: no backend credentials are set";
            }
        }

        // ---- console / API roots -----------------------------------------------------------------
        String validRealm = null;
        if (profile == AoBackendProfile.O11Y) {
            if (realm == null) {
                problems.add(new Problem(false, "AO_REALM is required for profile o11y (e.g. au0, us1)."));
            } else if (!REALM.matcher(realm).matches()) {
                problems.add(new Problem(false, "AO_REALM may only contain lowercase letters and digits (got '"
                        + realm + "')."));
            } else {
                validRealm = realm;
            }
        }
        String appRoot = validRealm == null ? null : "https://app." + validRealm + ".observability.splunkcloud.com";

        Value<String> consoleUrl;
        String consoleOverride = url(raw.consoleUrl());
        if (consoleOverride != null) {
            checkConsoleRoot(consoleOverride, problems);
            consoleUrl = Value.override(consoleOverride);
        } else if (profile == AoBackendProfile.O11Y) {
            consoleUrl = Value.derived(appRoot);
        } else {
            consoleUrl = Value.derived(GALILEO_DEFAULT_CONSOLE);
        }

        Value<String> apiUrl;
        String apiOverride = url(raw.apiUrl());
        if (apiOverride != null) {
            checkHttpUrl("AO_API_URL", apiOverride, problems, false);
            apiUrl = Value.override(apiOverride);
        } else if (profile == AoBackendProfile.O11Y) {
            // SDK O11yConfig.require_api_url(): app host + /ao/api (X-SF-Token, no JWT exchange).
            apiUrl = Value.derived(appRoot == null ? null : appRoot + "/ao/api");
        } else {
            apiUrl = Value.derived(galileoApiUrl(consoleUrl.value(), problems));
        }

        // ---- credentials -------------------------------------------------------------------------
        Secret apiToken;
        Secret otlpAuthValue;
        Secret controlKey;
        String controlKeyOverride = text(raw.agentControlApiKey());
        if (profile == AoBackendProfile.O11Y) {
            String o11yApiToken = text(raw.o11yApiToken());
            apiToken = o11yApiToken != null ? new Secret(o11yApiToken, "AO_O11Y_API_TOKEN", Source.OVERRIDE)
                    : new Secret(o11yToken, "AO_O11Y_TOKEN", Source.DERIVED);
            // SDK O11yConfig.require_ingest_token(): OTLP export always uses the ingest token.
            otlpAuthValue = new Secret(o11yToken, "AO_O11Y_TOKEN", Source.DERIVED);
            controlKey = controlKeyOverride != null
                    ? new Secret(controlKeyOverride, "AGENT_CONTROL_API_KEY", Source.OVERRIDE)
                    : new Secret(o11yToken, "AO_O11Y_TOKEN", Source.DERIVED);
            if (o11yToken == null) {
                problems.add(new Problem(false, "AO_O11Y_TOKEN is required for profile o11y (Splunk Observability "
                        + "Cloud access token with INGEST and API scopes)."));
            }
        } else {
            apiToken = new Secret(apiKey, "AO_API_KEY", Source.DERIVED);
            otlpAuthValue = new Secret(apiKey, "AO_API_KEY", Source.DERIVED);
            controlKey = controlKeyOverride != null
                    ? new Secret(controlKeyOverride, "AGENT_CONTROL_API_KEY", Source.OVERRIDE)
                    : new Secret(apiKey, "AO_API_KEY", Source.DERIVED);
            if (apiKey == null) {
                problems.add(new Problem(false, "AO_API_KEY is required for profile galileo (Galileo API key)."));
            }
        }

        // ---- OTLP --------------------------------------------------------------------------------
        Value<String> otlpEndpoint;
        String otlpOverride = url(raw.otlpEndpoint());
        if (otlpOverride != null) {
            checkHttpUrl("AO_OTLP_ENDPOINT", otlpOverride, problems, false);
            otlpEndpoint = Value.override(otlpOverride);
        } else if (profile == AoBackendProfile.O11Y) {
            // SDK O11yConfig.otlp_endpoint (not the standard OTLP /v1/traces path).
            otlpEndpoint = Value.derived(validRealm == null ? null
                    : "https://ingest." + validRealm + ".observability.splunkcloud.com/v2/trace/otlp");
        } else {
            otlpEndpoint = Value.derived(apiUrl.value() == null ? null : apiUrl.value() + "/otel/traces");
        }
        Value<String> otlpHeader = header("AO_OTLP_AUTH_HEADER", raw.otlpAuthHeader(),
                profile == AoBackendProfile.O11Y ? "X-SF-Token" : "Galileo-API-Key", problems, false);
        Value<String> serviceName = defaulted(raw.serviceName(), DEFAULT_SERVICE_NAME);

        // ---- Agent Control -----------------------------------------------------------------------
        Value<String> controlUrl;
        String controlOverride = url(raw.agentControlUrl());
        if (controlOverride != null) {
            checkHttpUrl("AGENT_CONTROL_URL", controlOverride, problems, true);
            controlUrl = Value.override(controlOverride);
        } else if (profile == AoBackendProfile.O11Y) {
            controlUrl = Value.derived(appRoot == null ? null : appRoot + "/ao/agent-control");
        } else {
            controlUrl = Value.derived(consoleUrl.value() == null ? null : consoleUrl.value() + "/api/agent-control");
        }
        Value<String> evaluationPath = defaulted(raw.agentControlEvaluationPath(), DEFAULT_EVALUATION_PATH);
        if (!evaluationPath.value().startsWith("/")) {
            problems.add(new Problem(true, "AGENT_CONTROL_EVALUATION_PATH must start with '/'."));
        }
        Value<String> controlHeader = header("AGENT_CONTROL_API_KEY_HEADER", raw.agentControlApiKeyHeader(),
                profile == AoBackendProfile.O11Y ? "X-SF-Token" : "Galileo-API-Key", problems, true);
        Value<String> agentName = defaulted(raw.agentControlAgentName(), DEFAULT_AGENT_NAME);
        if (!AGENT_NAME.matcher(agentName.value()).matches()) {
            problems.add(new Problem(true, "AGENT_CONTROL_AGENT_NAME must be at least 10 characters of lowercase "
                    + "letters, digits, ':', '_' or '-' (got '" + agentName.value() + "')."));
        }
        Value<String> targetType = defaulted(raw.agentControlTargetType(), DEFAULT_TARGET_TYPE);
        Value<String> authMode = defaulted(lower(raw.agentControlRuntimeAuthMode()), DEFAULT_RUNTIME_AUTH_MODE);
        if (!authMode.value().equals("jwt") && !authMode.value().equals("api_key")) {
            problems.add(new Problem(true, "AGENT_CONTROL_RUNTIME_AUTH_MODE must be 'jwt' or 'api_key'."));
        }
        Value<String> runtimeHeader = header("AGENT_CONTROL_RUNTIME_TOKEN_HEADER",
                raw.agentControlRuntimeTokenHeader(), DEFAULT_RUNTIME_TOKEN_HEADER, problems, true);
        if (runtimeHeader.source() == Source.DERIVED) {
            runtimeHeader = Value.defaulted(runtimeHeader.value());
        }
        Value<ControlFailureMode> readMode = failureMode(raw.controlReadFailureMode(), "CONTROL_READ_FAILURE_MODE",
                ControlFailureMode.FAIL_OPEN, problems);
        Value<ControlFailureMode> writeMode = failureMode(raw.controlWriteFailureMode(), "CONTROL_WRITE_FAILURE_MODE",
                ControlFailureMode.FAIL_CLOSED, problems);
        Value<Integer> steerRetries = steerRetries(raw.maxControlSteerRetries(), problems);

        Value<String> project = defaulted(raw.projectName(), DEFAULT_PROJECT_NAME);
        Value<String> stream = defaulted(raw.agentStream(), DEFAULT_AGENT_STREAM);

        if (apiUrl.value() == null || otlpEndpoint.value() == null) {
            problems.add(new Problem(false, "Backend URLs cannot be derived; fix the problems above or set AO_API_URL "
                    + "and AO_OTLP_ENDPOINT explicitly."));
        }
        if (controlUrl.value() == null) {
            problems.add(new Problem(true, "AGENT_CONTROL_URL cannot be derived; set it explicitly."));
        }
        if (!controlKey.isSet()) {
            problems.add(new Problem(true, "No Agent Control credential: set the profile credential or "
                    + "AGENT_CONTROL_API_KEY."));
        }

        List<String> active = new ArrayList<>();
        for (Problem p : problems) {
            if (!p.controlOnly() || controlMode == ControlMode.REMOTE_API) {
                active.add(p.message());
            }
        }
        return new AoSettings(observability, controlMode, content, profile, profileSource, validRealm, consoleUrl,
                apiUrl, apiToken, otlpEndpoint, otlpHeader, otlpAuthValue, serviceName, controlUrl, evaluationPath,
                controlHeader, controlKey, agentName, targetType, authMode, runtimeHeader, readMode, writeMode,
                steerRetries, project, stream, active);
    }

    /** galileo-core set_api_url: app.galileo.ai -> https://api.galileo.ai, otherwise "console" -> "api". */
    static String galileoApiUrl(String console, List<Problem> problems) {
        if (console == null) {
            return null;
        }
        if (console.contains("app.galileo.ai")) {
            return "https://api.galileo.ai";
        }
        String replaced = console.replace("console", "api");
        if (replaced.equals(console)) {
            problems.add(new Problem(false, "Cannot derive AO_API_URL from AO_CONSOLE_URL '" + console
                    + "' (no 'console' in the host). Set AO_API_URL explicitly."));
            return null;
        }
        return replaced;
    }

    private static void checkConsoleRoot(String url, List<Problem> problems) {
        URI uri = parse(url);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            problems.add(new Problem(false, "AO_CONSOLE_URL must be an https:// URL (got '" + url + "')."));
            return;
        }
        String path = uri.getPath();
        if ((path != null && !path.isEmpty() && !path.equals("/")) || uri.getQuery() != null) {
            problems.add(new Problem(false, "AO_CONSOLE_URL must be the console ROOT, e.g. https://"
                    + uri.getHost() + " - remove '" + path + "'. A tenant path such as /splunkse is only the "
                    + "browser login entry and is never used to derive API addresses."));
        }
    }

    private static void checkHttpUrl(String key, String url, List<Problem> problems, boolean controlOnly) {
        URI uri = parse(url);
        if (uri == null || uri.getHost() == null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            problems.add(new Problem(controlOnly, key + " must be an absolute http(s) URL (got '" + url + "')."));
        }
    }

    private static URI parse(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Value<String> header(String key, String raw, String derived, List<Problem> problems,
                                        boolean controlOnly) {
        String value = text(raw);
        if (value == null) {
            return Value.derived(derived);
        }
        if (!HEADER_NAME.matcher(value).matches()) {
            problems.add(new Problem(controlOnly, key + " must be an HTTP header NAME (not a credential value)."));
        }
        return Value.override(value);
    }

    private static Value<ControlFailureMode> failureMode(String raw, String key, ControlFailureMode def,
                                                         List<Problem> problems) {
        String value = text(raw);
        if (value == null) {
            return Value.defaulted(def);
        }
        try {
            return Value.override(ControlFailureMode.parse(value, key));
        } catch (IllegalArgumentException e) {
            problems.add(new Problem(true, e.getMessage()));
            return Value.defaulted(def);
        }
    }

    private static Value<Integer> steerRetries(String raw, List<Problem> problems) {
        String value = text(raw);
        if (value == null) {
            return Value.defaulted(DEFAULT_MAX_STEER_RETRIES);
        }
        try {
            int n = Integer.parseInt(value);
            if (n < 0 || n > 5) {
                throw new NumberFormatException();
            }
            return Value.override(n);
        } catch (NumberFormatException e) {
            problems.add(new Problem(true, "MAX_CONTROL_STEER_RETRIES must be an integer between 0 and 5."));
            return Value.defaulted(DEFAULT_MAX_STEER_RETRIES);
        }
    }

    private static Value<String> defaulted(String raw, String def) {
        String value = text(raw);
        return value == null ? Value.defaulted(def) : Value.override(value);
    }

    private static boolean parseSwitch(String raw, String key) {
        String value = text(raw);
        if (value == null) {
            return false;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new AoConfigurationException(key + " must be 'true' or 'false' (got '" + value + "').");
        };
    }

    /** Empty strings and template placeholders such as "&lt;your-realm&gt;" count as "not set". */
    static String text(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty() || (value.startsWith("<") && value.endsWith(">"))) {
            return null;
        }
        return value;
    }

    private static String lower(String raw) {
        String value = text(raw);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    /** Trims and removes trailing slashes so that paths can be appended with a single "/". */
    static String url(String raw) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }
}

package com.demo.bankagent.llm;

import java.util.regex.Pattern;

/** Removes anything that looks like an API key or bearer token before text reaches logs or clients. */
public final class SecretMasker {

    private static final Pattern OPENAI_KEY = Pattern.compile("sk-[A-Za-z0-9_\\-]{8,}");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{8,}");

    private SecretMasker() {
    }

    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String masked = OPENAI_KEY.matcher(text).replaceAll("sk-***");
        return BEARER.matcher(masked).replaceAll("$1***");
    }
}

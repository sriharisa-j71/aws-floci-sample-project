package com.example.regression.context;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ContextResolver {

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{\\s*(\\S+?)\\s*\\}\\}");
    private static final Pattern STEP_REF_PATTERN = Pattern.compile("^step\\.(.+)\\.response\\.(.+)$");
    private static final Pattern SPLIT_FILTER = Pattern.compile("\\|split$");

    private final ThreadLocal<Map<String, String>> cache = ThreadLocal.withInitial(ConcurrentHashMap::new);

    public void clearCache() {
        cache.get().clear();
    }

    @SuppressWarnings("unchecked")
    public Object resolve(Object value, TestStepContext stepCtx, String level) {
        if (value == null) return null;
        if (value instanceof String s) {
            String cacheKey = level + ":" + s;
            Map<String, String> c = cache.get();
            String cached = c.get(cacheKey);
            if (cached != null) return cached;

            String resolved = resolveTemplate(s, stepCtx, level);
            c.put(cacheKey, resolved);
            return resolved;
        }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            for (var entry : ((Map<String, Object>) map).entrySet()) {
                result.put(entry.getKey(), resolve(entry.getValue(), stepCtx, level));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(e -> resolve(e, stepCtx, level))
                    .collect(Collectors.toList());
        }
        return value;
    }

    private String resolveTemplate(String template, TestStepContext stepCtx, String level) {
        var sb = new StringBuffer();
        var matcher = TEMPLATE_PATTERN.matcher(template);
        while (matcher.find()) {
            String expr = matcher.group(1);
            String replacement = resolveExpression(expr, stepCtx);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement != null ? replacement : matcher.group(0)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String resolveExpression(String expr, TestStepContext stepCtx) {
        var splitMatcher = SPLIT_FILTER.matcher(expr);
        boolean hasSplit = splitMatcher.find();
        String key = hasSplit ? splitMatcher.replaceFirst("") : expr;

        if (key.startsWith("env.")) {
            return System.getenv(key.substring(4));
        }
        if (key.startsWith("config.")) {
            return resolveConfig(key.substring(7), stepCtx);
        }
        if (key.startsWith("shared.")) {
            return resolveShared(key.substring(7), stepCtx);
        }
        if (key.startsWith("resolved.")) {
            return resolveResolved(key.substring(9), stepCtx);
        }
        if (key.startsWith("step.")) {
            return resolveStepRef(key, stepCtx);
        }
        return null;
    }

    private String resolveConfig(String key, TestStepContext stepCtx) {
        var mergedConfig = mergeConfigs(stepCtx);
        return resolveFromMap(key, mergedConfig);
    }

    private String resolveShared(String key, TestStepContext stepCtx) {
        var shared = stepCtx.parent().parent().sharedData();
        return resolveFromMap(key, shared);
    }

    private String resolveResolved(String key, TestStepContext stepCtx) {
        var dataRow = stepCtx.parent().dataRow();
        return resolveFromMap(key, dataRow);
    }

    private String resolveStepRef(String expr, TestStepContext stepCtx) {
        var m = STEP_REF_PATTERN.matcher(expr);
        if (!m.find()) return null;
        String stepName = m.group(1);
        String jsonPath = m.group(2);

        var responses = stepCtx.parent().stepResponses();
        var response = responses.get(stepName);
        if (response == null) return null;

        if (response instanceof Map<?, ?> respMap) {
            return resolveFromMap(jsonPath, (Map<String, Object>) respMap);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String resolveFromMap(String dotPath, Map<String, Object> map) {
        if (map == null) return null;
        String[] parts = dotPath.split("\\.");
        Object current = map;
        for (String part : parts) {
            if (current instanceof Map<?, ?> m) {
                current = ((Map<String, Object>) m).get(part);
            } else {
                return null;
            }
        }
        return current != null ? current.toString() : null;
    }

    private Map<String, Object> mergeConfigs(TestStepContext stepCtx) {
        var merged = new LinkedHashMap<String, Object>();
        if (stepCtx.parent().parent().suite().config() != null) {
            merged.putAll(stepCtx.parent().parent().suite().config());
        }
        if (stepCtx.parent().testCase().config() != null) {
            merged.putAll(stepCtx.parent().testCase().config());
        }
        if (stepCtx.step().config() != null) {
            merged.putAll(stepCtx.step().config());
        }
        return merged;
    }
}

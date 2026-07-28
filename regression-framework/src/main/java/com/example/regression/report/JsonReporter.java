package com.example.regression.report;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

@Component
public class JsonReporter {

    private final JsonMapper jsonMapper;

    public JsonReporter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String render(ReportModel model) {
        try {
            return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
}

package com.example.regression.shell;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

@ShellComponent
public class ConfigCommand {

    private final JdbcTemplate jdbc;

    public ConfigCommand(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @ShellMethod(key = "config", value = "View or set persisted config")
    public String config(
            @ShellOption(value = "--key", defaultValue = ShellOption.NULL) String key,
            @ShellOption(value = "--value", defaultValue = ShellOption.NULL) String value) {
        if (key != null && value != null) {
            jdbc.update("""
                    INSERT INTO regression_config (key, value, updated_at)
                    VALUES (?, ?, now())
                    ON CONFLICT (key) DO UPDATE SET value = ?, updated_at = now()
                    """, key, value, value);
            return "Set " + key + " = " + value;
        }

        var sb = new StringBuilder("Configuration:\n");
        jdbc.query("SELECT key, value FROM regression_config ORDER BY key",
                (rs, rowNum) -> {
                    sb.append("  ").append(rs.getString("key"))
                            .append(" = ").append(rs.getString("value")).append("\n");
                    return null;
                });
        return sb.toString();
    }
}

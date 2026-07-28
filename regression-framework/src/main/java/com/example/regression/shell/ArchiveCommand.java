package com.example.regression.shell;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

@ShellComponent
public class ArchiveCommand {

    private final JdbcTemplate jdbc;

    public ArchiveCommand(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @ShellMethod(key = "archive", value = "Archive old test runs")
    public String archive(
            @ShellOption(value = "--older-than", defaultValue = "90") int olderThanDays) {
        jdbc.update("CALL archive_runs(?)", olderThanDays);
        return "Archived runs older than " + olderThanDays + " days";
    }
}

package edu.bu.archive.adapter.in.web;

import edu.bu.archive.dto.DashboardDto;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@Transactional(readOnly = true)
public class DashboardController {

    private final JdbcClient jdbcClient;

    public DashboardController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping
    public DashboardDto dashboard() {
        return new DashboardDto(
                count("archive.irb_protocol"),
                countDistinct("archive.irb_protocol_version", "protocol_base"),
                count("archive.irb_protocol_version"),
                count("archive.irb_submission"),
                count("archive.irb_funding_source"),
                count("archive.irb_timeline_event"),
                0,
                0,
                0,
                0,
                0
        );
    }

    private long count(String tableName) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + tableName)
                .query(Long.class)
                .single();
    }

    private long countDistinct(String tableName, String columnName) {
        return jdbcClient.sql(
                        "SELECT COUNT(DISTINCT " + columnName + ") FROM " + tableName
                )
                .query(Long.class)
                .single();
    }
}

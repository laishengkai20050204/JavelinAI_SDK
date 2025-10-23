package com.example.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

@Configuration
public class JdbcConfig {

    @Bean
    @ConditionalOnProperty(name = "ai.memory.storage", havingValue = "database")
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource aiMemoryDataSource(Environment environment) {
        String url = environment.getProperty("spring.datasource.url");
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("Property 'spring.datasource.url' must be set when ai.memory.storage=database");
        }

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(url);
        dataSource.setUsername(environment.getProperty("spring.datasource.username"));
        dataSource.setPassword(environment.getProperty("spring.datasource.password"));
        dataSource.setDriverClassName(
                environment.getProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver")
        );
        return dataSource;
    }

    @Bean
    @ConditionalOnProperty(name = "ai.memory.storage", havingValue = "database")
    @ConditionalOnMissingBean(NamedParameterJdbcTemplate.class)
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }
}

package com.finnza;

import com.finnza.repository.UsuarioRepository;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.sql.Connection;

@SpringBootApplication
@EnableScheduling
@EnableJpaAuditing
@Slf4j
public class FinnzaApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinnzaApplication.class, args);
    }

    /**
     * Confirma conexão com o banco e quantos usuários existem (diagnóstico local).
     */
    @Bean
    CommandLineRunner logDiagnosticoUsuarios(
            UsuarioRepository usuarioRepository,
            Environment env,
            DataSource dataSource) {
        return args -> {
            String propUrl = env.getProperty("spring.datasource.url");
            String jdbcUrl = resolverJdbcUrl(dataSource);

            if (jdbcUrl != null && !jdbcUrl.contains("postgresql")) {
                log.warn(
                        "Datasource não é PostgreSQL ({}). Ajuste spring.datasource.* e variáveis de ambiente (ex.: não use SPRING_DATASOURCE_URL vazia no IntelliJ).",
                        jdbcUrl);
            }

            try {
                long n = usuarioRepository.count();
                log.info(
                        "Banco conectado — usuários: {} | JDBC efetivo: {} | spring.datasource.url no Environment: {}",
                        n,
                        jdbcUrl,
                        propUrl != null && !propUrl.isEmpty() ? propUrl : "(vazio — veja aviso no application.properties)");
                if (n == 0) {
                    log.warn("Nenhum usuário no banco. Crie o primeiro admin: POST /api/usuarios/primeiro-admin");
                }
            } catch (Exception e) {
                log.error("Falha ao consultar usuários — verifique PostgreSQL e spring.datasource.* : {}", e.getMessage());
            }
        };
    }

    private static String resolverJdbcUrl(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getJdbcUrl();
        }
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().getURL();
        } catch (Exception e) {
            return "(não foi possível ler URL: " + e.getMessage() + ")";
        }
    }
}
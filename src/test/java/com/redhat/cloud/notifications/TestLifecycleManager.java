package com.redhat.cloud.notifications;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpResponse;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.MockServerContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.mockserver.model.HttpRequest.request;

public class TestLifecycleManager implements QuarkusTestResourceLifecycleManager {

    PostgreSQLContainer<?> postgreSQLContainer;
    MockServerContainer mockEngineServer;
    MockServerClient mockServerClient;
    RbacConfigurator configurator;

    @Override
    public Map<String, String> start() {
        System.out.println("++++  TestLifecycleManager start +++");
        Map<String, String> properties = new HashMap<>();
        try {
            setupPostgres(properties);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        setupMockEngine(properties);

        System.out.println(" -- Running with properties: " + properties);
        return properties;
    }

    @Override
    public void stop() {
        postgreSQLContainer.stop();
        mockEngineServer.stop();
    }


    @Override
    public void inject(Object testInstance) {
        Class<?> c = testInstance.getClass();
        while (c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getAnnotation(MockRbacConfig.class) != null) {
                    if (!RbacConfigurator.class.isAssignableFrom(f.getType())) {
                        throw new RuntimeException("@MockRbacConfig can only be used on fields of type RbacConfigurator");
                    }

                    f.setAccessible(true);
                    try {
                        f.set(testInstance, configurator);
                        return;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            c = c.getSuperclass();
        }
    }

    void setupPostgres(Map<String, String> props) throws SQLException {
        postgreSQLContainer = new PostgreSQLContainer<>("postgres");
        postgreSQLContainer.start();
        // Now that postgres is started, we need to get its URL and tell Quarkus
        // quarkus.datasource.driver=io.opentracing.contrib.jdbc.TracingDriver
        // Driver needs a 'tracing' in the middle like jdbc:tracing:postgresql://localhost:5432/postgres
        String jdbcUrl = postgreSQLContainer.getJdbcUrl();
        String dbUrl = jdbcUrl.substring(jdbcUrl.indexOf(':') + 1);
        String classicJdbcUrl = "jdbc:" /* + "tracing:" */ + dbUrl;
        props.put("quarkus.datasource.jdbc.url", classicJdbcUrl);
        String vertxJdbcUrl = "vertx-reactive:" + dbUrl;
        props.put("quarkus.datasource.reactive.url", "vertx-reactive:" + vertxJdbcUrl);
        props.put("quarkus.datasource.username", "test");
        props.put("quarkus.datasource.password", "test");
        props.put("quarkus.datasource.db-kind", "postgresql");

        // Install the pgcrypto extension
        // Could perhas be done by a migration with a lower number than the 'live' ones.
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(jdbcUrl);
        Connection connection = ds.getConnection("test", "test");
        Statement statement = connection.createStatement();
        statement.execute("CREATE EXTENSION pgcrypto;");
        statement.close();
        connection.close();

    }

    void setupMockEngine(Map<String, String> props) {
        mockEngineServer = new MockServerContainer();

        // set up mock engine
        mockEngineServer.start();
        String mockServerUrl = "http://" + mockEngineServer.getContainerIpAddress() + ":" + mockEngineServer.getServerPort();
        mockServerClient = new MockServerClient(mockEngineServer.getContainerIpAddress(), mockEngineServer.getServerPort());

        configurator = new RbacConfigurator(mockServerClient);

        props.put("engine/mp-rest/url", mockServerUrl);
        props.put("rbac/mp-rest/url", mockServerUrl);
        props.put("notifications/mp-rest/url", mockServerUrl);

    }
}

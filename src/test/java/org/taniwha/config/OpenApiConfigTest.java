package org.taniwha.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void documentsOrchestratorIdentityAndJwtScheme() {
        OpenAPI openApi = config.orchestratorOpenApi();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("MEDIATA Orchestrator API");
        assertThat(openApi.getComponents().getSecuritySchemes())
                .containsKeys(OpenApiConfig.BEARER_AUTH, OpenApiConfig.NODE_SYNC_AUTH);
    }

    @Test
    void leavesPublicOperationsOpenAndSecuresApplicationOperations() {
        Operation login = new Operation();
        Operation projects = new Operation();
        Operation heartbeat = new Operation();
        OpenAPI openApi = config.orchestratorOpenApi().paths(new Paths()
                .addPathItem("/api/user/login", new PathItem().post(login))
                .addPathItem("/nodes/heartbeat", new PathItem().post(heartbeat))
                .addPathItem("/api/projects/list", new PathItem().get(projects)));

        config.orchestratorSecurityRequirements().customise(openApi);

        assertThat(login.getSecurity()).isNullOrEmpty();
        assertThat(heartbeat.getSecurity().get(0)).containsKey(OpenApiConfig.NODE_SYNC_AUTH);
        assertThat(projects.getSecurity().get(0)).containsKey(OpenApiConfig.BEARER_AUTH);
    }
}

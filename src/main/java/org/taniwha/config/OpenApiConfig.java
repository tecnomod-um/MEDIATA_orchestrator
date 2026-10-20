package org.taniwha.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Set;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
    public static final String NODE_SYNC_AUTH = "nodeSyncAuth";

    private static final Set<String> PUBLIC_POST_PATHS = Set.of(
            "/api/user/login",
            "/api/user/register",
            "/api/error",
            "/nodes/register",
            "/nodes/deregister"
    );
    private static final Set<String> NODE_SYNC_POST_PATHS = Set.of(
            "/nodes/heartbeat",
            "/nodes/provenance/batch"
    );

    @Bean
    public OpenAPI orchestratorOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MEDIATA Orchestrator API")
                        .version("1.0")
                        .description("Central APIs for authentication, node coordination, project management, "
                                + "semantic mapping, terminology lookup, and secure proxying to MEDIATA nodes.")
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/license/mit")))
                .servers(List.of(new Server()
                        .url("/taniwha")
                        .description("Current MEDIATA orchestrator")))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_AUTH,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT returned by POST /api/user/login."))
                        .addSecuritySchemes(
                                NODE_SYNC_AUTH,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Short-lived, endpoint-scoped JWT signed with the node's registered RSA private key.")));
    }

    @Bean
    public OpenApiCustomizer orchestratorSecurityRequirements() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, pathItem) ->
                    pathItem.readOperationsMap().forEach((method, operation) -> {
                        if (HttpMethod.POST.name().equals(method.name())
                                && NODE_SYNC_POST_PATHS.contains(path)) {
                            operation.setSecurity(List.of(
                                    new SecurityRequirement().addList(NODE_SYNC_AUTH)));
                            if (operation.getResponses() == null) {
                                operation.setResponses(new ApiResponses());
                            }
                            operation.getResponses().putIfAbsent(
                                    "401", new ApiResponse().description("Invalid node-scoped JWT."));
                            return;
                        }
                        if (HttpMethod.POST.name().equals(method.name()) && PUBLIC_POST_PATHS.contains(path)) {
                            operation.setSecurity(List.of());
                            return;
                        }
                        operation.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
                        if (operation.getResponses() == null) {
                            operation.setResponses(new ApiResponses());
                        }
                        operation.getResponses().putIfAbsent(
                                "401",
                                new ApiResponse().description("Missing, invalid, or expired JWT.")
                        );
                        operation.getResponses().putIfAbsent(
                                "403",
                                new ApiResponse().description("The authenticated user cannot access this operation.")
                        );
                    })
            );
        };
    }
}

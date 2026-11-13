package org.taniwha.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
        name = "springdoc.swagger-ui.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SwaggerUiStyleController {

    @GetMapping(value = "/swagger-ui/index.css", produces = "text/css")
    public Resource swaggerUiStylesheet() {
        return new ClassPathResource("swagger/swagger-ui.css");
    }
}


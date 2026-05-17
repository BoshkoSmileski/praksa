package com.praksa.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Diploma Thesis Management System API")
                        .version("1.0.0")
                        .description("""
                                REST API for managing the full lifecycle of university diploma theses.

                                **Workflow overview:**
                                1. Student requests eligibility check
                                2. Student selects topic and mentor
                                3. Mentor accepts/rejects
                                4. Application validation
                                5. Work on thesis + version uploads
                                6. Final submission and mentor approval
                                7. Committee formation and review
                                8. Defense scheduling
                                9. Grade recording and archiving

                                **How to authenticate:**
                                1. Call `POST /api/auth/login` with your credentials
                                2. Copy the `token` from the response
                                3. Click **Authorize** above, paste the token (without "Bearer ")
                                4. All subsequent requests will include the token automatically
                                """)
                        .contact(new Contact()
                                .name("DiplomaSystem")
                                .email("admin@diplomasystem.mk")))
                // Register the Bearer JWT security scheme
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste your JWT token here (without the 'Bearer ' prefix)")))
                // Apply the security scheme globally — every endpoint requires auth by default.
                // Individual endpoints can override this with @SecurityRequirements({})
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}

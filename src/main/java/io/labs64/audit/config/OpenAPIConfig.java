package io.labs64.audit.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.*;
import io.swagger.v3.oas.annotations.servers.Server;

@OpenAPIDefinition(
        // (1)
        info = @Info(
                title = "Resource Server Example Service",
                version = "1.0.0",
                description = "Documentation for Resource Server Example Service"
        ),
        // (2)
        servers = {
                @Server(url = "http://nsa2-gateway:8080/resource-server"),
                @Server(url = "http://nsa2-resource-server-example:8082")
        }
)
@SecuritySchemes({
        // (3)
        @SecurityScheme(
                name = "security-auth",
                type = SecuritySchemeType.OAUTH2,
                flows = @OAuthFlows(

                        authorizationCode = @OAuthFlow(
                                authorizationUrl = "${springdoc.oAuthFlow.authorizationUrl}",
                                tokenUrl = "${springdoc.oAuthFlow.tokenUrl}",
                                scopes = {
                                        @OAuthScope(name = "openid", description = "OpenID"),
                                        @OAuthScope(name = "profile", description = "Profile")
                                }
                        )
                )
        ),
        // (4)
        @SecurityScheme(
                name = "bearer-key",
                type = SecuritySchemeType.HTTP,
                scheme = "bearer",
                bearerFormat = "JWT"
        )
})
public class OpenAPIConfig {
}

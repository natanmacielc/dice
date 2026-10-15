package io.github.natanmacielc.dice.app.og;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Renderiza dentro da imagem oficial do AWS Lambda (Java 21), que não tem fontconfig.
 * Regressão do "Fontconfig head is null". Fora do build padrão: ./mvnw -pl dice-app -am verify -Plambda
 */
@Tag("lambda")
class OgImageLambdaTest {

    @Test
    void rendersInsideLambdaJava21Image() {
        try (GenericContainer<?> lambda = new GenericContainer<>(DockerImageName.parse("public.ecr.aws/lambda/java:21"))
                .withCopyFileToContainer(MountableFile.forHostPath("target/classes"), "/app")
                // amd64: a arquitetura padrão do Lambda (e a variante arm64 do registry pode falhar no pull).
                .withCreateContainerCmdModifier(cmd -> cmd.withPlatform("linux/amd64").withEntrypoint("/var/lang/bin/java"))
                .withCommand("-cp", "/app", OgImageRenderer.class.getName(), "/tmp/og")
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(3)))) {
            lambda.start();
            String logs = lambda.getLogs();
            assertTrue(logs.contains("PNGs gerados"), logs);
        }
    }
}

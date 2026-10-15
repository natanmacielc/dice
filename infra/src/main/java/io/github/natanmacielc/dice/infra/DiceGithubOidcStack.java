package io.github.natanmacielc.dice.infra;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.iam.IOpenIdConnectProvider;
import software.amazon.awscdk.services.iam.OpenIdConnectProvider;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.WebIdentityPrincipal;
import software.constructs.Construct;

/**
 * Role que o GitHub Actions assume via OIDC. Só o environment "dev" do repositório confia nela, e
 * ela só pode assumir as roles do bootstrap do CDK (que fazem o deploy de fato).
 */
public class DiceGithubOidcStack extends Stack {

    static final String GITHUB_OIDC = "token.actions.githubusercontent.com";

    private final Role role;

    public DiceGithubOidcStack(Construct scope, String id, StackProps props, DiceConfig cfg) {
        super(scope, id, props);
        IOpenIdConnectProvider provider = cfg.existingOidcProviderArn()
                .map(arn -> OpenIdConnectProvider.fromOpenIdConnectProviderArn(this, "GithubOidc", arn))
                .orElseGet(() -> OpenIdConnectProvider.Builder.create(this, "GithubOidc")
                        .url("https://" + GITHUB_OIDC)
                        .clientIds(List.of("sts.amazonaws.com"))
                        .build());

        this.role = Role.Builder.create(this, "DeployRole")
                .roleName("dice-github-deploy")
                .description("GitHub Actions (" + cfg.githubRepo() + ", environment " + cfg.githubEnvironment() + ")")
                .maxSessionDuration(Duration.hours(1))
                .assumedBy(new WebIdentityPrincipal(provider.getOpenIdConnectProviderArn(), Map.of(
                        "StringEquals", Map.of(
                                GITHUB_OIDC + ":aud", "sts.amazonaws.com",
                                GITHUB_OIDC + ":sub",
                                "repo:" + cfg.githubRepo() + ":environment:" + cfg.githubEnvironment()))))
                .build();
        role.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("sts:AssumeRole"))
                .resources(List.of("arn:aws:iam::" + cfg.account() + ":role/cdk-hnb659fds-*"))
                .build());

        CfnOutput.Builder.create(this, "DeployRoleArn").value(role.getRoleArn())
                .description("Valor da variável AWS_DEPLOY_ROLE_ARN do environment dev no GitHub").build();
    }

    public Role role() {
        return role;
    }
}

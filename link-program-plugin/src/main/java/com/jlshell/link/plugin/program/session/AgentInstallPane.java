package com.jlshell.link.plugin.program.session;

import com.jlshell.plugin.api.PluginContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** Optional deployment through SSH; normal Link connections do not require opening SSH first. */
final class AgentInstallPane {
    static TitledPane create(PluginContext context, Supplier<String> publicKeys, Supplier<String> website,
            Supplier<String> controlWss, Supplier<String> stunServers) {
        Label status = new Label("从发行页下载对应平台 ZIP、manifest.json 和 signature.json，放在同一目录。"
                + "安装前请准备远端 Ed25519 TLS 身份、密码文件和精确目标白名单。Linux 需要可用的 systemd 用户会话，macOS 需要登录会话，Windows 需要管理员 SSH。");
        status.setWrapText(true);
        TextField identity = field("远端 TLS identity.p12 路径");
        TextField password = field("远端 TLS 密码文件路径");
        TextField targets = field("远端精确目标白名单文件路径");
        TextField agent = field("首次注册的 Agent ID；升级时留空");
        PasswordField enrollment = new PasswordField();
        enrollment.setPromptText("首次注册的一次性令牌；升级时留空，不会保存");
        Button choose = new Button("选择签名 Java Agent ZIP 并安装/升级");
        choose.setOnAction(event -> {
            if (context.sshSession().isEmpty()) { status.setText("需要已连接的 SSH 会话。"); return; }
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择 Java Agent 签名包");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java Agent ZIP", "*.zip"));
            java.io.File selected = chooser.showOpenDialog(choose.getScene().getWindow());
            if (selected == null) return;
            JavaAgentDeploymentService.Request request;
            List<String> trust;
            try {
                trust = SignedAgentPackage.parseTrustedKeys(publicKeys.get());
                request = new JavaAgentDeploymentService.Request(website.get(), controlWss.get(), identity.getText(),
                        password.getText(), targets.getText(), agent.getText().strip(), enrollment.getText(), stunServers.get());
            } catch (Exception invalid) { status.setText("请核对发布公钥、服务地址和远端配置。"); return; }
            enrollment.clear();
            choose.setDisable(true);
            status.setText("正在验证发布签名和包摘要…");
            CompletableFuture.supplyAsync(() -> {
                try {
                    Path zip = selected.toPath();
                    String base = zip.getFileName().toString().replaceFirst("\\.zip$", "");
                    Path manifest = zip.resolveSibling(base + ".manifest.json");
                    Path signature = zip.resolveSibling(base + ".signature.json");
                    if (Files.size(manifest) > 1_048_576 || Files.size(signature) > 4096) {
                        throw new SecurityException("Agent 清单或签名文件过大");
                    }
                    return SignedAgentPackage.verify(zip, Files.readAllBytes(manifest), Files.readAllBytes(signature), trust);
                } catch (Exception invalid) { throw new java.util.concurrent.CompletionException(invalid); }
            }).whenComplete((artifact, error) -> Platform.runLater(() -> {
                if (error != null) {
                    choose.setDisable(false);
                    status.setText("发布签名或包校验失败，未上传文件。请确认可信公钥和完整的发行文件。");
                    return;
                }
                Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                        "版本：" + artifact.version() + "\n平台：" + artifact.platform() + "/" + artifact.architecture()
                                + "\n发布公钥指纹：" + artifact.publisherKeyId()
                                + "\n将通过当前 SSH 会话上传并安装 Java Agent。升级会短暂停止 Agent 服务，启动失败时恢复上一版本。"
                                + "首次注册产生的节点身份会保留；安装完成后仍需核对 Website 在线状态。", ButtonType.OK, ButtonType.CANCEL);
                confirmation.setHeaderText("确认安装到当前 SSH 主机");
                if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                    choose.setDisable(false); status.setText("已取消安装。"); return;
                }
                status.setText("正在上传、注册并安装服务…");
                new JavaAgentDeploymentService(context.sshSession().orElseThrow()).install(artifact, trust, request, true)
                        .whenComplete((result, failure) -> Platform.runLater(() -> {
                            choose.setDisable(false);
                            status.setText(failure == null ? result : "安装未完成。请核对平台、远端配置和服务恢复状态；不要重复使用已消耗的注册令牌。");
                        }));
            }));
        });
        TitledPane pane = new TitledPane("通过当前 SSH 安装 Java 网关（可选，支持离线包）",
                new VBox(8, status, identity, password, targets, agent, enrollment, choose));
        pane.setExpanded(false);
        return pane;
    }

    private static TextField field(String prompt) {
        TextField value = new TextField(); value.setPromptText(prompt); return value;
    }
}

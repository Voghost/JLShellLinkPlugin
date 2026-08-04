package com.jlshell.link.plugin.program;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.jlshell.link.plugin.common.LinkPluginContract;
import com.jlshell.program.api.AccountRequest;
import com.jlshell.program.api.AccountSessionService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 通过 JLShell 宿主会话读取 Link 套餐和插件策略；绝不持有账号令牌。 */
final class LinkSubscriptionService {
    private final AccountSessionService account;
    private volatile JsonObject entitlement;
    private volatile JsonObject programAccess;
    private volatile JsonObject sessionAccess;

    LinkSubscriptionService(AccountSessionService account) {
        this.account = account == null ? AccountSessionService.unavailable() : account;
    }

    CompletableFuture<JsonElement> refresh() {
        return CompletableFuture.supplyAsync(() -> {
            requireLogin();
            try {
                entitlement = request("/api/v1/account/entitlements").getAsJsonObject();
                programAccess = request(accessPath("PROGRAM")).getAsJsonObject();
                sessionAccess = request(accessPath("SESSION")).getAsJsonObject();
                return status();
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        });
    }

    CompletableFuture<JsonElement> claimTrial(String machineFingerprint, String deviceId) {
        return CompletableFuture.supplyAsync(() -> {
            requireLogin();
            if (machineFingerprint == null || !machineFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("机器指纹无效");
            }
            JsonObject body = new JsonObject();
            body.addProperty("machineFingerprint", machineFingerprint);
            body.addProperty("deviceId", deviceId);
            try {
                account.request(new AccountRequest("POST", "/api/v1/account/trial", body)).get();
                entitlement = request("/api/v1/account/entitlements").getAsJsonObject();
                programAccess = request(accessPath("PROGRAM")).getAsJsonObject();
                sessionAccess = request(accessPath("SESSION")).getAsJsonObject();
                return status();
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        });
    }

    CompletableFuture<JsonElement> requireProgram(String entitlementCode) {
        return require(programAccess, "PROGRAM", entitlementCode);
    }

    CompletableFuture<JsonElement> requireSession(String entitlementCode) {
        return require(sessionAccess, "SESSION", entitlementCode);
    }

    JsonObject status() {
        JsonObject result = new JsonObject();
        if (!account.snapshot().authenticated()) {
            result.addProperty("state", "SIGNED_OUT");
        } else if (entitlement == null || programAccess == null || sessionAccess == null) {
            result.addProperty("state", "CHECKING");
        } else if (!allowed(programAccess) || !allowed(sessionAccess)) {
            result.addProperty("state", reason(programAccess, sessionAccess));
        } else if (has("link.tcp-tunnel") && has("link.agent-deploy")) {
            result.addProperty("state", "READY");
        } else {
            result.addProperty("state", entitlement.has("trialAvailable") && entitlement.get("trialAvailable").getAsBoolean()
                    ? "TRIAL_AVAILABLE" : "UPGRADE_REQUIRED");
        }
        result.add("entitlement", entitlement == null ? JsonNull.INSTANCE : entitlement.deepCopy());
        result.add("programAccess", programAccess == null ? JsonNull.INSTANCE : programAccess.deepCopy());
        result.add("sessionAccess", sessionAccess == null ? JsonNull.INSTANCE : sessionAccess.deepCopy());
        return result;
    }

    private CompletableFuture<JsonElement> require(JsonObject cached, String scope, String code) {
        return refresh().thenApply(ignored -> {
            JsonObject policy = "PROGRAM".equals(scope) ? programAccess : sessionAccess;
            if (!allowed(policy)) throw new IllegalStateException(policyMessage(policy));
            if (!has(code)) throw new IllegalStateException("当前套餐不包含此 Link 功能，请升级 Plus 或 Pro 或领取试用");
            return status();
        });
    }

    private JsonElement request(String path) throws Exception {
        return account.request(new AccountRequest("GET", path, null)).get();
    }

    private static String accessPath(String scope) {
        return "/api/v1/account/plugin-access?pluginId=" + LinkPluginContract.PROGRAM_PLUGIN_ID
                + "&version=" + LinkPluginContract.VERSION + "&scope=" + scope;
    }

    private void requireLogin() {
        if (!account.snapshot().authenticated()) throw new IllegalStateException("请先在 JLShell 的账号设置中通过 Web 登录");
    }

    private boolean has(String code) {
        if (entitlement == null || !entitlement.has("entitlements")) return false;
        for (JsonElement item : entitlement.getAsJsonArray("entitlements")) if (code.equals(item.getAsString())) return true;
        return false;
    }

    private static boolean allowed(JsonObject value) {
        return value != null && value.has("allowed") && value.get("allowed").getAsBoolean();
    }

    private static String reason(JsonObject program, JsonObject session) {
        JsonObject denied = !allowed(program) ? program : session;
        return denied != null && denied.has("reason") ? denied.get("reason").getAsString() : "UPGRADE_REQUIRED";
    }

    private static String policyMessage(JsonObject policy) {
        return policy != null && policy.has("reason") && "DISABLED_BY_ADMIN".equals(policy.get("reason").getAsString())
                ? "JLShell Link 已被管理员停用" : "当前插件版本或套餐不具备此 Link 功能";
    }
}

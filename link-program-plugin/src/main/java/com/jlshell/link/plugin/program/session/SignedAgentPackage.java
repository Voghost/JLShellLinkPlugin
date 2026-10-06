package com.jlshell.link.plugin.program.session;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** An archive is usable only after checking a separately provisioned publisher trust anchor. */
public record SignedAgentPackage(Path archive, byte[] manifest, byte[] signatureEnvelope,
        String version, String platform, String architecture, String baseName, String sha256,
        String publisherKeyId) {
    public static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;

    public SignedAgentPackage {
        manifest = manifest.clone();
        signatureEnvelope = signatureEnvelope.clone();
    }
    @Override public byte[] manifest() { return manifest.clone(); }
    @Override public byte[] signatureEnvelope() { return signatureEnvelope.clone(); }

    public static SignedAgentPackage verify(Path archive, byte[] manifest, byte[] envelope,
            List<String> trustedKeys) throws Exception {
        JsonObject identity = verifyManifest(manifest, envelope, trustedKeys);
        String version = text(identity, "version");
        validateVersion(version);
        String platform = text(identity, "platform");
        String architecture = text(identity, "architecture");
        if (!Map.of("linux", "x64", "macos", "arm64", "windows", "x64").containsKey(platform)
                || !Map.of("linux", "x64", "macos", "arm64", "windows", "x64").get(platform).equals(architecture)
                || !text(identity, "sourceRevision").matches("[0-9a-fA-F]{40,64}")) {
            throw new SecurityException("Agent 包的平台或来源无效");
        }
        String baseName = "jlshell-link-agent-java-" + version + "-" + platform + "-" + architecture;
        String fileName = archive.getFileName().toString();
        if (!fileName.equals(baseName + ".zip")) throw new SecurityException("安装需要对应平台的签名 ZIP 包");
        if (Files.isSymbolicLink(archive) || !Files.isRegularFile(archive)) throw new SecurityException("Agent 包不是普通文件");
        var artifacts = identity.getAsJsonArray("artifacts");
        if (artifacts == null || artifacts.size() != 2) throw new SecurityException("Agent 包清单不完整");
        JsonObject selected = null;
        var names = new java.util.HashSet<String>();
        for (var item : artifacts) {
            JsonObject artifact = item.getAsJsonObject();
            String name = text(artifact, "name");
            if (!names.add(name) || (!name.equals(baseName + ".zip") && !name.equals(baseName + ".tar.gz"))) {
                throw new SecurityException("Agent 清单包含非预期归档");
            }
            if (name.equals(fileName)) selected = artifact;
        }
        if (selected == null) throw new SecurityException("Agent ZIP 未列入签名清单");
        long size = selected.get("sizeBytes").getAsBigDecimal().longValueExact();
        String sha = text(selected, "sha256");
        if (size < 1 || size > MAX_ARCHIVE_BYTES || size != Files.size(archive)
                || !sha.matches("[0-9a-f]{64}") || !sha.equals(digest(archive))) {
            throw new SecurityException("Agent ZIP 的大小或 SHA-256 与签名清单不符");
        }
        return new SignedAgentPackage(archive, manifest, envelope, version, platform, architecture,
                baseName, sha, text(JsonParser.parseString(new String(envelope, java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject(), "keyId"));
    }

    public static JsonObject verifyManifest(byte[] manifest, byte[] envelope, List<String> trustedKeys) throws Exception {
        if (manifest.length == 0 || manifest.length > 1_048_576 || envelope.length == 0 || envelope.length > 4096
                || trustedKeys == null || trustedKeys.isEmpty()) throw new SecurityException("请先配置可信发布公钥");
        JsonObject value = JsonParser.parseString(new String(envelope, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if (!"1".equals(text(value, "schemaVersion")) || !"Ed25519".equals(text(value, "algorithm"))
                || !digest(manifest).equals(text(value, "manifestSha256"))) throw new SecurityException("Agent 清单签名格式无效");
        java.security.PublicKey publicKey = null;
        for (String trusted : trustedKeys) {
            var key = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(trusted.strip())));
            if (digest(key.getEncoded()).equals(text(value, "keyId"))) publicKey = key;
        }
        if (publicKey == null) throw new SecurityException("Agent 发布签名来自未知公钥");
        byte[] signature = Base64.getDecoder().decode(text(value, "signature"));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(manifest);
        if (signature.length != 64 || !verifier.verify(signature)) throw new SecurityException("Agent 发布签名验证失败");
        JsonObject identity = JsonParser.parseString(new String(manifest, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if (!"1".equals(text(identity, "schemaVersion")) || !"jlshell-link-agent-java".equals(text(identity, "product"))
                || !"jlshell-link-v2".equals(text(identity, "protocolVersion"))) throw new SecurityException("非 Java v2 Agent 包");
        return identity;
    }

    public static List<String> parseTrustedKeys(String value) throws Exception {
        if (value == null || value.isBlank()) throw new SecurityException("请先配置可信发布公钥");
        List<String> keys = List.of(value.strip().split("[\\s,]+"));
        if (keys.size() > 8) throw new SecurityException("最多配置 8 个发布公钥");
        for (String key : keys) KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(key)));
        return keys;
    }

    public static int compareVersions(String left, String right) {
        validateVersion(left); validateVersion(right);
        String[] a = left.split("-", 2), b = right.split("-", 2);
        String[] ac = a[0].split("\\."), bc = b[0].split("\\.");
        for (int i = 0; i < 3; i++) {
            int compared = new BigInteger(ac[i]).compareTo(new BigInteger(bc[i]));
            if (compared != 0) return compared;
        }
        if (a.length == 1 || b.length == 1) return Integer.compare(b.length, a.length);
        String[] ai = a[1].split("\\."), bi = b[1].split("\\.");
        for (int i = 0; i < Math.min(ai.length, bi.length); i++) {
            boolean an = ai[i].matches("[0-9]+"), bn = bi[i].matches("[0-9]+");
            int compared = an && bn ? new BigInteger(ai[i]).compareTo(new BigInteger(bi[i]))
                    : an != bn ? (an ? -1 : 1) : ai[i].compareTo(bi[i]);
            if (compared != 0) return compared;
        }
        return Integer.compare(ai.length, bi.length);
    }

    private static void validateVersion(String version) {
        String id = "(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)";
        if (!version.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-" + id + "(?:\\." + id + ")*)?")) {
            throw new SecurityException("Agent 版本无效");
        }
    }

    static String text(JsonObject value, String name) {
        if (!value.has(name) || !value.get(name).isJsonPrimitive()) throw new SecurityException("Agent 清单缺少 " + name);
        return value.get(name).getAsString();
    }
    static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static String digest(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

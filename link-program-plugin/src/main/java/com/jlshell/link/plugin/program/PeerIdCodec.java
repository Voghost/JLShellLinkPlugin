package com.jlshell.link.plugin.program;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Base64;

/** 在 Website 的 base64url 原始 multihash 与 libp2p CLI 的 base58btc PeerId 之间转换。 */
final class PeerIdCodec {
    private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger FIFTY_EIGHT = BigInteger.valueOf(58);
    private static final int MAX_ENCODED_LENGTH = 256;

    private PeerIdCodec() { }

    static String toBase58(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_ENCODED_LENGTH) {
            throw invalid();
        }
        String encoded = value.trim();
        byte[] decoded = tryBase58(encoded);
        if (validPeerId(decoded)) {
            return base58(decoded);
        }
        decoded = tryBase64(encoded);
        if (validPeerId(decoded)) {
            return base58(decoded);
        }
        throw invalid();
    }

    private static byte[] tryBase58(String value) {
        BigInteger number = BigInteger.ZERO;
        for (int index = 0; index < value.length(); index++) {
            int digit = BASE58.indexOf(value.charAt(index));
            if (digit < 0) return null;
            number = number.multiply(FIFTY_EIGHT).add(BigInteger.valueOf(digit));
        }
        byte[] significant = number.equals(BigInteger.ZERO) ? new byte[0] : number.toByteArray();
        if (significant.length > 0 && significant[0] == 0) {
            significant = Arrays.copyOfRange(significant, 1, significant.length);
        }
        int leadingZeros = 0;
        while (leadingZeros < value.length() && value.charAt(leadingZeros) == '1') leadingZeros++;
        byte[] result = new byte[leadingZeros + significant.length];
        System.arraycopy(significant, 0, result, leadingZeros, significant.length);
        return result;
    }

    private static byte[] tryBase64(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException ignored) {
            try {
                return Base64.getDecoder().decode(value);
            } catch (IllegalArgumentException error) {
                return null;
            }
        }
    }

    private static boolean validPeerId(byte[] value) {
        if (value == null) return false;
        // Ed25519 公钥使用 identity multihash: 0x00 + length 36 + protobuf public key。
        if (value.length == 38 && value[0] == 0x00 && value[1] == 0x24
                && value[2] == 0x08 && value[3] == 0x01
                && value[4] == 0x12 && value[5] == 0x20) return true;
        // 同时接受标准 sha2-256 multihash，避免未来非内联 PeerId 无法通过。
        return value.length == 34 && value[0] == 0x12 && value[1] == 0x20;
    }

    private static String base58(byte[] value) {
        BigInteger number = new BigInteger(1, value);
        StringBuilder result = new StringBuilder();
        while (number.signum() > 0) {
            BigInteger[] divided = number.divideAndRemainder(FIFTY_EIGHT);
            result.append(BASE58.charAt(divided[1].intValue()));
            number = divided[0];
        }
        for (byte current : value) {
            if (current != 0) break;
            result.append('1');
        }
        return result.reverse().toString();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("PeerId is not a supported base58/base64 encoding");
    }
}

package vn.machtrip.pipeline.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import vn.machtrip.pipeline.config.PipelineProperties;

/** author_hash = sha256(SALT + id). Raw usernames / user ids are never stored. */
@Component
public class Hashing {

    private final String salt;

    public Hashing(PipelineProperties props) {
        this.salt = props.hashSalt();
    }

    public String authorHash(String id) {
        if (salt.isBlank()) {
            throw new IllegalStateException("HASH_SALT is not set; refusing to hash author ids without a salt");
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + id).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

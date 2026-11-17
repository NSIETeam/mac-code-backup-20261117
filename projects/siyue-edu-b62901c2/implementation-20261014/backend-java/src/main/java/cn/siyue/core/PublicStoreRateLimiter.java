package cn.siyue.core;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Enforces separate public and submission minute windows without persisting IP addresses. */
@Service
public class PublicStoreRateLimiter {
    private final PublicStoreRateRepository repository;
    private final PiiCipher cipher;

    /**
     * @param repository minute-window persistence, with no independent transaction boundary
     * @param cipher keyed source fingerprint provider
     */
    public PublicStoreRateLimiter(PublicStoreRateRepository repository, PiiCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    /**
     * @param bucket public browsing or submission bucket
     * @param address source address, converted to a keyed fingerprint before persistence
     * @param now current UTC observation time
     * @return whether this committed attempt remains within the selected limit
     * @throws IllegalArgumentException if the bucket is not recognized
     * @throws org.springframework.dao.DataAccessException if the counter cannot be recorded
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean allow(String bucket, String address, Instant now) {
        if (!bucket.equals("public") && !bucket.equals("submission")) {
            throw new IllegalArgumentException("Unknown public store rate bucket");
        }
        byte[] source = cipher.fingerprint("public_store_ip", address);
        Integer count=repository.hit(bucket,source,now);
        return count != null && count <= (bucket.equals("submission") ? 10 : 60);
    }
}

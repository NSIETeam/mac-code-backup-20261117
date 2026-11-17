package cn.siyue.core;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Applies a small per-form, per-source submission limit without storing source addresses. */
@Service
public class CrmFormRateLimiter {
    private final CrmFormRateRepository repository;
    private final PiiCipher cipher;

    /**
     * @param repository minute-window persistence, with no independent transaction boundary
     * @param cipher keyed source fingerprint provider
     */
    public CrmFormRateLimiter(CrmFormRateRepository repository,PiiCipher cipher){this.repository=repository;this.cipher=cipher;}

    /**
     * @param formId internal form identifier
     * @param sourceAddress source address, converted to a keyed fingerprint before persistence
     * @param now current UTC observation time
     * @return whether this committed attempt remains within the form limit
     * @throws org.springframework.dao.DataAccessException if the counter cannot be recorded
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Exception.class)
    public boolean allow(long formId,String sourceAddress,Instant now){
        byte[] sourceHash=cipher.fingerprint("public_form_ip",sourceAddress);
        Integer count=repository.hit(formId,sourceHash,now);
        return count!=null&&count<=10;
    }
}

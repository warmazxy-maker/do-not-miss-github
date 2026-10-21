package com.donotmiss.backend.capabilityv2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports only the stable function buckets. It never touches legacy growth tags or the
 * current scoring tables, so the v2 taxonomy can be introduced without a risky cutover.
 */
@Service
public class FunctionBucketSeedImportService {
    private static final Logger log = LoggerFactory.getLogger(FunctionBucketSeedImportService.class);
    public static final String SEED_VERSION = "function-buckets-v1";
    private static final String SOURCE = "SYSTEM_SEED";

    private final FunctionBucketSeedCatalog catalog;
    private final CapabilityBucketRepository bucketRepository;
    private final ObjectMapper objectMapper;

    public FunctionBucketSeedImportService(
            FunctionBucketSeedCatalog catalog,
            CapabilityBucketRepository bucketRepository,
            ObjectMapper objectMapper
    ) {
        this.catalog = catalog;
        this.bucketRepository = bucketRepository;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void importOnStartup() {
        SeedImportResult result = importSeedLibrary();
        log.info("Capability v2 function seed library ready: inserted={}, refreshed={}, total={}",
                result.inserted(), result.refreshed(), result.total());
    }

    public SeedImportResult importSeedLibrary() {
        List<CapabilityBucketEntity> changed = new ArrayList<>();
        // The curated source can contain the same broad job function in more than one
        // industry section. One normalized function bucket must still map to one row.
        Map<String, FunctionBucketSeedCatalog.FunctionBucketSeed> uniqueSeeds = new LinkedHashMap<>();
        for (FunctionBucketSeedCatalog.FunctionBucketSeed seed : catalog.all()) {
            uniqueSeeds.putIfAbsent(seed.normalizedName(), seed);
        }
        int inserted = 0;
        int refreshed = 0;
        for (FunctionBucketSeedCatalog.FunctionBucketSeed seed : uniqueSeeds.values()) {
            CapabilityBucketEntity bucket = bucketRepository
                    .findByBucketTypeAndOwnerUserIdAndNormalizedName(
                            CapabilityBucketType.FUNCTION,
                            CapabilityBucketEntity.GLOBAL_OWNER,
                            seed.normalizedName()
                    )
                    .orElse(null);
            if (bucket == null) {
                bucket = new CapabilityBucketEntity();
                bucket.setBucketType(CapabilityBucketType.FUNCTION);
                bucket.setOwnerUserId(CapabilityBucketEntity.GLOBAL_OWNER);
                bucket.setNormalizedName(seed.normalizedName());
                inserted++;
            } else {
                refreshed++;
            }
            bucket.setDisplayName(seed.displayName());
            bucket.setDomainName(seed.domainName());
            bucket.setGroupName(seed.groupName());
            bucket.setDescription(seed.description());
            bucket.setAliasesJson(writeAliases(seed.aliases()));
            bucket.setSource(SOURCE);
            bucket.setStatus(CapabilityBucketStatus.ACTIVE);
            bucket.setSeedVersion(SEED_VERSION);
            changed.add(bucket);
        }
        bucketRepository.saveAll(changed);
        return new SeedImportResult(inserted, refreshed, changed.size());
    }

    private String writeAliases(List<String> aliases) {
        try {
            return objectMapper.writeValueAsString(aliases);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize function bucket aliases", exception);
        }
    }

    public record SeedImportResult(int inserted, int refreshed, int total) {
    }
}

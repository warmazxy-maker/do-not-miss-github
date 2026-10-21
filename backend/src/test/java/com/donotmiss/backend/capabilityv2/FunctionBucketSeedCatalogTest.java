package com.donotmiss.backend.capabilityv2;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionBucketSeedCatalogTest {
    @Test
    void parsesDomainGroupBucketAndAbsorbedCapabilities() throws Exception {
        String fixture = "一、软件与信息技术\n软件研发\n后端开发\n吸收：服务端编程、缓存、消息队列。\n";

        List<FunctionBucketSeedCatalog.FunctionBucketSeed> parsed = FunctionBucketSeedCatalog.parse(
                new ByteArrayInputStream(fixture.getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(1, parsed.size());
        FunctionBucketSeedCatalog.FunctionBucketSeed seed = parsed.getFirst();
        assertEquals("软件与信息技术", seed.domainName());
        assertEquals("软件研发", seed.groupName());
        assertEquals("后端开发", seed.displayName());
        assertTrue(seed.aliases().contains("缓存"));
    }

    @Test
    void normalizesNamesWithoutChangingChineseMeaning() {
        assertEquals("javabackend开发", FunctionBucketSeedCatalog.normalize(" Java Backend 开发 "));
    }

    @Test
    void loadsTheRealSeedLibraryAndFindsBackendDevelopment() {
        FunctionBucketSeedCatalog catalog = new FunctionBucketSeedCatalog();
        catalog.reload();

        assertTrue(catalog.all().size() > 100);
        assertEquals("后端开发", catalog.search("后端开发", 3).getFirst().displayName());
    }
}

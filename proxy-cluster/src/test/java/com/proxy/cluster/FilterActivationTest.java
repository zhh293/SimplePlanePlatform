package com.proxy.cluster;

import com.proxy.cluster.filter.*;
import com.proxy.common.filter.Filter;
import com.proxy.common.spi.ExtensionLoader;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FilterActivationTest {
    @Test
    void clientChainExcludesQpsLimiterButPreservesOtherFiltersAndExplicitLookup() {
        ExtensionLoader<Filter> loader = ExtensionLoader.getLoader(Filter.class);
        List<Class<?>> classes = loader.getActivateExtensions("client").stream()
                .map(Object::getClass).collect(Collectors.toList());
        assertFalse(classes.contains(RateLimitFilter.class));
        assertTrue(classes.containsAll(Arrays.asList(RouterFilter.class, MonitorFilter.class,
                AccessLogFilter.class, TrafficFilter.class)));
        assertEquals(4, classes.size());
        assertTrue(loader.getExtension("rate-limit") instanceof RateLimitFilter);
    }
}

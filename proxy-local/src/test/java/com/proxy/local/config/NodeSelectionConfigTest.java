package com.proxy.local.config;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class NodeSelectionConfigTest {
    private static ProxyConfig.RemoteServer node(String id, int port) {
        ProxyConfig.RemoteServer node = new ProxyConfig.RemoteServer();
        node.setId(id);
        node.setHost("localhost");
        node.setPort(port);
        node.setTransport("http3");
        node.setCipher("none");
        node.getHttp3().setHandshakeTimeoutMs(100);
        return node;
    }

    private static ProxyConfig config(String selected) {
        ProxyConfig config = new ProxyConfig();
        config.setConnectionsPerNode(1);
        config.setRemoteServers(Arrays.asList(node("a", 9090), node("b", 9091)));
        ProxyConfig.NodeSelection selection = new ProxyConfig.NodeSelection();
        selection.setSelectedNodeId(selected);
        config.setNodeSelection(selection);
        return config;
    }

    @Test void missingDuplicateAndInvalidModesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> config("missing").validate());
        ProxyConfig duplicate = config("a");
        duplicate.getRemoteServers().get(1).setId("a");
        assertThrows(IllegalArgumentException.class, duplicate::validate);
        ProxyConfig automatic = config("a");
        automatic.getNodeSelection().setMode("auto");
        assertThrows(IllegalArgumentException.class, automatic::validate);
        automatic.getNodeSelection().setPreferredNodeId("a");
        assertDoesNotThrow(automatic::validate);
        automatic.getNodeSelection().setMode("unknown");
        assertThrows(IllegalArgumentException.class, automatic::validate);
    }

    @Test void automaticModeRequiresExistingPreferredAndRetainsManualSelection() {
        ProxyConfig automatic = config("b");
        automatic.getNodeSelection().setMode("auto");
        automatic.getNodeSelection().setPreferredNodeId("missing");
        assertThrows(IllegalArgumentException.class, automatic::validate);
        automatic.getNodeSelection().setPreferredNodeId("a");
        automatic.validate();
        assertEquals("b", automatic.getNodeSelection().getSelectedNodeId());
        assertEquals("a", automatic.getNodeSelection().getPreferredNodeId());
    }

    @Test void invalidConnectionParametersAreRejectedBeforeSwitching() {
        ProxyConfig multipleConnections = config("a");
        multipleConnections.setConnectionsPerNode(2);
        assertThrows(IllegalArgumentException.class, multipleConnections::validate);
        ProxyConfig invalid = config("a");
        invalid.getRemoteServers().get(1).getHttp3().setHandshakeTimeoutMs(-1);
        assertThrows(IllegalArgumentException.class, invalid::validate);
        ProxyConfig invalidKey = config("a");
        invalidKey.getRemoteServers().get(1).setCipher("aes-gcm-v2");
        invalidKey.getRemoteServers().get(1).setCipherKey("short");
        assertThrows(RuntimeException.class, invalidKey::validate);
    }

    @Test void oldSingleHttp3ConfigurationMigratesToStableManualSelection() {
        ProxyConfig legacy = new ProxyConfig();
        legacy.setConnectionsPerNode(1);
        legacy.setRemoteServers(Collections.singletonList(node(null, 9090)));
        legacy.validate();
        assertEquals("legacy-node", legacy.getNodeSelection().getSelectedNodeId());
        legacy.validate();
        assertEquals("legacy-node", legacy.getRemoteServers().get(0).getId());
    }

    @Test void backupEditsAndSelectionChangesAreApplied() throws Exception {
        Http3NodeController controller = new Http3NodeController(config("a"));
        try {
            ProxyConfig backupChanged = config("a");
            backupChanged.getRemoteServers().get(1).setPort(9191);
            backupChanged.setLoadBalance("unused-for-single-http3-connection");
            controller.apply(backupChanged);
            assertEquals("a", controller.getCurrentNodeId());
            controller.apply(config("b"));
            assertEquals("b", controller.getCurrentNodeId());
            assertThrows(IllegalArgumentException.class, () -> controller.apply(config("missing")));
            assertEquals("b", controller.getCurrentNodeId());
        } finally { controller.close(); }
    }
}

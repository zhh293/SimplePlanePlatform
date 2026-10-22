package com.proxy.local.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NodeCircuitTest {
    @Test void graceAndResidencePreventNewNodeBeingImmediatelyRejected() {
        NodeCircuit circuit = new NodeCircuit();
        circuit.activate(1000);
        assertFalse(circuit.failedCheck(2000));
        assertEquals(0, circuit.failures);
        assertFalse(circuit.failedCheck(16000));
        assertFalse(circuit.failedCheck(22000));
        assertFalse(circuit.failedCheck(28000));
        assertTrue(circuit.failedCheck(34000));
        circuit.response(35000);
        assertEquals(0, circuit.failures);
    }

    @Test void transientDisconnectResetsButPersistentDisconnectBypassesGrace() {
        NodeCircuit circuit = new NodeCircuit();
        circuit.activate(1000);
        assertFalse(circuit.disconnected(1000, false));
        assertFalse(circuit.disconnected(5999, false));
        assertFalse(circuit.disconnected(6000, true));
        assertFalse(circuit.disconnected(7000, false));
        assertTrue(circuit.disconnected(12000, false));
    }

    @Test void recoveryNeedsTwoSpacedResponsesAndDoesNotResetBackoff() {
        NodeCircuit circuit = new NodeCircuit();
        circuit.trip(1000, "disconnected", 0);
        assertFalse(circuit.eligible());
        assertFalse(circuit.beginRecovery(30999));
        assertTrue(circuit.beginRecovery(31000));
        assertFalse(circuit.recoveredResponse(31000));
        assertFalse(circuit.recoveredResponse(35999));
        assertTrue(circuit.recoveredResponse(36000));
        assertTrue(circuit.eligible());
        assertEquals(1, circuit.openings);
        circuit.trip(40000, "failed again", 0);
        assertEquals(100000, circuit.openUntil);
        assertTrue(circuit.beginRecovery(100000));
        circuit.recoveredResponse(100000);
        circuit.recoveredResponse(105000);
        circuit.activate(106000);
        circuit.response(165999);
        assertEquals(2, circuit.openings);
        circuit.response(166000);
        assertEquals(0, circuit.openings);
    }

    @Test void cooldownCapsAndConfigurationBlockPreventsRecovery() {
        NodeCircuit circuit = new NodeCircuit();
        for (int i = 0; i < 20; i++) circuit.trip(0, "failure", 0);
        assertEquals(300000, circuit.openUntil);
        circuit.blocked = true;
        assertFalse(circuit.beginRecovery(1000000));
        assertFalse(circuit.eligible());
    }
}

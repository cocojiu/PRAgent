package com.repoguard.agent.service.impl;

/**
 * Strategy boundary for a concrete external dependency connectivity probe.
 *
 * @param <T> configuration model accepted by the probe
 */
interface ConnectionProbe<T> {

    String provider();

    ConnectionProbeResult probe(T config);
}

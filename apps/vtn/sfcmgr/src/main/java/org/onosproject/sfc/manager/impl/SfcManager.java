/*
 * Copyright 2015 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.sfc.manager.impl;

import static org.slf4j.LoggerFactory.getLogger;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TCP;
import org.onlab.packet.UDP;
import org.onlab.util.ItemNotFoundException;
import org.onlab.util.KryoNamespace;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.NshServicePathId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.sfc.forwarder.ServiceFunctionForwarderService;
import org.onosproject.sfc.forwarder.impl.ServiceFunctionForwarderImpl;
import org.onosproject.sfc.installer.FlowClassifierInstallerService;
import org.onosproject.sfc.installer.impl.FlowClassifierInstallerImpl;
import org.onosproject.sfc.manager.NshSpiIdGenerators;
import org.onosproject.sfc.manager.SfcService;
import org.onosproject.vtnrsc.DefaultFiveTuple;
import org.onosproject.vtnrsc.FiveTuple;
import org.onosproject.vtnrsc.FixedIp;
import org.onosproject.vtnrsc.FlowClassifier;
import org.onosproject.vtnrsc.FlowClassifierId;
import org.onosproject.vtnrsc.PortChain;
import org.onosproject.vtnrsc.PortChainId;
import org.onosproject.vtnrsc.PortPair;
import org.onosproject.vtnrsc.PortPairGroup;
import org.onosproject.vtnrsc.PortPairGroupId;
import org.onosproject.vtnrsc.PortPairId;
import org.onosproject.vtnrsc.TenantId;
import org.onosproject.vtnrsc.VirtualPort;
import org.onosproject.vtnrsc.VirtualPortId;
import org.onosproject.vtnrsc.event.VtnRscEvent;
import org.onosproject.vtnrsc.event.VtnRscEventFeedback;
import org.onosproject.vtnrsc.event.VtnRscListener;
import org.onosproject.vtnrsc.flowclassifier.FlowClassifierService;
import org.onosproject.vtnrsc.portchain.PortChainService;
import org.onosproject.vtnrsc.service.VtnRscService;
import org.onosproject.vtnrsc.virtualport.VirtualPortService;
import org.slf4j.Logger;

/**
 * Provides implementation of SFC Service.
 */
@Component(immediate = true)
@Service
public class SfcManager implements SfcService {

    private final Logger log = getLogger(getClass());
    private static final String APP_ID = "org.onosproject.app.vtn";
    private static final int SFC_PRIORITY = 1000;
    private static final int NULL_PORT = 0;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected VtnRscService vtnRscService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PortChainService portChainService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowClassifierService flowClassifierService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected VirtualPortService virtualPortService;

    private SfcPacketProcessor processor = new SfcPacketProcessor();

    protected ApplicationId appId;
    private ServiceFunctionForwarderService serviceFunctionForwarderService;
    private FlowClassifierInstallerService flowClassifierInstallerService;

    private final VtnRscListener vtnRscListener = new InnerVtnRscListener();

    private ConcurrentMap<PortChainId, NshServicePathId> nshSpiPortChainMap = new ConcurrentHashMap<>();

    @Activate
    public void activate() {
        appId = coreService.registerApplication(APP_ID);
        serviceFunctionForwarderService = new ServiceFunctionForwarderImpl(appId);
        flowClassifierInstallerService = new FlowClassifierInstallerImpl(appId);

        vtnRscService.addListener(vtnRscListener);

        KryoNamespace.Builder serializer = KryoNamespace.newBuilder()
                .register(TenantId.class)
                .register(PortPairId.class)
                .register(PortPairGroupId.class)
                .register(FlowClassifierId.class)
                .register(PortChainId.class);

        packetService.addProcessor(processor, PacketProcessor.director(SFC_PRIORITY));
        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        vtnRscService.removeListener(vtnRscListener);
        packetService.removeProcessor(processor);
        log.info("Stopped");
    }

    /*
     * Handle events.
     */
    private class InnerVtnRscListener implements VtnRscListener {
        @Override
        public void event(VtnRscEvent event) {

            if (VtnRscEvent.Type.PORT_PAIR_PUT == event.type()) {
                PortPair portPair = ((VtnRscEventFeedback) event.subject()).portPair();
                onPortPairCreated(portPair);
            } else if (VtnRscEvent.Type.PORT_PAIR_DELETE == event.type()) {
                PortPair portPair = ((VtnRscEventFeedback) event.subject()).portPair();
                onPortPairDeleted(portPair);
            } else if (VtnRscEvent.Type.PORT_PAIR_UPDATE == event.type()) {
                PortPair portPair = ((VtnRscEventFeedback) event.subject()).portPair();
                onPortPairDeleted(portPair);
                onPortPairCreated(portPair);
            } else if (VtnRscEvent.Type.PORT_PAIR_GROUP_PUT == event.type()) {
                PortPairGroup portPairGroup = ((VtnRscEventFeedback) event.subject()).portPairGroup();
                onPortPairGroupCreated(portPairGroup);
            } else if (VtnRscEvent.Type.PORT_PAIR_GROUP_DELETE == event.type()) {
                PortPairGroup portPairGroup = ((VtnRscEventFeedback) event.subject()).portPairGroup();
                onPortPairGroupDeleted(portPairGroup);
            } else if (VtnRscEvent.Type.PORT_PAIR_GROUP_UPDATE == event.type()) {
                PortPairGroup portPairGroup = ((VtnRscEventFeedback) event.subject()).portPairGroup();
                onPortPairGroupDeleted(portPairGroup);
                onPortPairGroupCreated(portPairGroup);
            } else if (VtnRscEvent.Type.FLOW_CLASSIFIER_PUT == event.type()) {
                FlowClassifier flowClassifier = ((VtnRscEventFeedback) event.subject()).flowClassifier();
                onFlowClassifierCreated(flowClassifier);
            } else if (VtnRscEvent.Type.FLOW_CLASSIFIER_DELETE == event.type()) {
                FlowClassifier flowClassifier = ((VtnRscEventFeedback) event.subject()).flowClassifier();
                onFlowClassifierDeleted(flowClassifier);
            } else if (VtnRscEvent.Type.FLOW_CLASSIFIER_UPDATE == event.type()) {
                FlowClassifier flowClassifier = ((VtnRscEventFeedback) event.subject()).flowClassifier();
                onFlowClassifierDeleted(flowClassifier);
                onFlowClassifierCreated(flowClassifier);
            } else if (VtnRscEvent.Type.PORT_CHAIN_PUT == event.type()) {
                PortChain portChain = (PortChain) ((VtnRscEventFeedback) event.subject()).portChain();
                onPortChainCreated(portChain);
            } else if (VtnRscEvent.Type.PORT_CHAIN_DELETE == event.type()) {
                PortChain portChain = (PortChain) ((VtnRscEventFeedback) event.subject()).portChain();
                onPortChainDeleted(portChain);
            } else if (VtnRscEvent.Type.PORT_CHAIN_UPDATE == event.type()) {
                PortChain portChain = (PortChain) ((VtnRscEventFeedback) event.subject()).portChain();
                onPortChainDeleted(portChain);
                onPortChainCreated(portChain);
            }
        }
    }

    @Override
    public void onPortPairCreated(PortPair portPair) {
        log.debug("onPortPairCreated");
        // TODO: Modify forwarding rule on port-pair creation.
    }

    @Override
    public void onPortPairDeleted(PortPair portPair) {
        log.debug("onPortPairDeleted");
        // TODO: Modify forwarding rule on port-pair deletion.
    }

    @Override
    public void onPortPairGroupCreated(PortPairGroup portPairGroup) {
        log.debug("onPortPairGroupCreated");
        // TODO: Modify forwarding rule on port-pair-group creation.
    }

    @Override
    public void onPortPairGroupDeleted(PortPairGroup portPairGroup) {
        log.debug("onPortPairGroupDeleted");
        // TODO: Modify forwarding rule on port-pair-group deletion.
    }

    @Override
    public void onFlowClassifierCreated(FlowClassifier flowClassifier) {
        log.debug("onFlowClassifierCreated");
        // TODO: Modify forwarding rule on flow-classifier creation.
    }

    @Override
    public void onFlowClassifierDeleted(FlowClassifier flowClassifier) {
        log.debug("onFlowClassifierDeleted");
        // TODO: Modify forwarding rule on flow-classifier deletion.
    }

    @Override
    public void onPortChainCreated(PortChain portChain) {
        NshServicePathId nshSpi;
        log.info("onPortChainCreated");
        if (nshSpiPortChainMap.containsKey(portChain.portChainId())) {
            nshSpi = nshSpiPortChainMap.get(portChain.portChainId());
        } else {
            nshSpi = NshServicePathId.of(NshSpiIdGenerators.create());
            nshSpiPortChainMap.put(portChain.portChainId(), nshSpi);
        }

        // install in OVS.
        flowClassifierInstallerService.installFlowClassifier(portChain, nshSpi);
        serviceFunctionForwarderService.installForwardingRule(portChain, nshSpi);
    }

    @Override
    public void onPortChainDeleted(PortChain portChain) {
        log.info("onPortChainDeleted");
        if (!nshSpiPortChainMap.containsKey(portChain.portChainId())) {
            throw new ItemNotFoundException("Unable to find NSH SPI");
        }

        NshServicePathId nshSpi = nshSpiPortChainMap.get(portChain.portChainId());
        // uninstall from OVS.
        flowClassifierInstallerService.unInstallFlowClassifier(portChain, nshSpi);
        serviceFunctionForwarderService.unInstallForwardingRule(portChain, nshSpi);

        // remove SPI. No longer it will be used.
        nshSpiPortChainMap.remove(nshSpi);
    }

    private class SfcPacketProcessor implements PacketProcessor {

        /**
         * Check for given ip match with the fixed ips for the virtual port.
         *
         * @param vPortId virtual port id
         * @param ip ip address to match
         * @return true if the ip match with the fixed ips in virtual port false otherwise
         */
        private boolean checkIpInVirtualPort(VirtualPortId vPortId, IpAddress ip) {
            boolean found = false;
            Set<FixedIp> ips = virtualPortService.getPort(vPortId).fixedIps();
            for (FixedIp fixedIp : ips) {
                if (fixedIp.ip().equals(ip)) {
                    found = true;
                    break;
                }
            }
            return found;
        }

        /**
         * Find the port chain for the received packet.
         *
         * @param fiveTuple five tuple info from the packet
         * @return portChainId id of port chain
         */
        private PortChainId findPortChainFromFiveTuple(FiveTuple fiveTuple) {

            PortChainId portChainId = null;

            Iterable<PortChain> portChains = portChainService.getPortChains();
            if (portChains == null) {
                log.error("Could not retrive port chain list");
            }

            // Identify the port chain to which the packet belongs
            for (final PortChain portChain : portChains) {

                Iterable<FlowClassifierId> flowClassifiers = portChain.flowClassifiers();

                // One port chain can have multiple flow classifiers.
                for (final FlowClassifierId flowClassifierId : flowClassifiers) {

                    FlowClassifier flowClassifier = flowClassifierService.getFlowClassifier(flowClassifierId);
                    boolean match = false;
                    // Check whether protocol is set in flow classifier
                    if (flowClassifier.protocol() != null) {
                        if ((flowClassifier.protocol().equals("TCP") && fiveTuple.protocol() == IPv4.PROTOCOL_TCP) ||
                                (flowClassifier.protocol().equals("UDP") &&
                                        fiveTuple.protocol() == IPv4.PROTOCOL_UDP)) {
                            match = true;
                        } else {
                            continue;
                        }
                    }

                    // Check whether source ip prefix is set in flow classifier
                    if (flowClassifier.srcIpPrefix() != null) {
                        if (flowClassifier.srcIpPrefix().contains(fiveTuple.ipSrc())) {
                            match = true;
                        } else {
                            continue;
                        }
                    }

                    // Check whether destination ip prefix is set in flow classifier
                    if (flowClassifier.dstIpPrefix() != null) {
                        if (flowClassifier.dstIpPrefix().contains(fiveTuple.ipDst())) {
                            match = true;
                        } else {
                            continue;
                        }
                    }

                    // Check whether source port is set in flow classifier
                    if (fiveTuple.portSrc().toLong() >= flowClassifier.minSrcPortRange() ||
                            fiveTuple.portSrc().toLong() <= flowClassifier.maxSrcPortRange()) {
                        match = true;
                    } else {
                        continue;
                    }

                    // Check whether destination port is set in flow classifier
                    if (fiveTuple.portDst().toLong() >= flowClassifier.minSrcPortRange() ||
                            fiveTuple.portDst().toLong() <= flowClassifier.maxSrcPortRange()) {
                        match = true;
                    } else {
                        continue;
                    }

                    // Check whether neutron source port is set in flow classfier
                    if ((flowClassifier.srcPort() != null) && (!flowClassifier.srcPort().portId().isEmpty())) {
                        match = checkIpInVirtualPort(VirtualPortId.portId(flowClassifier.srcPort().portId()),
                                                     fiveTuple.ipSrc());
                        if (!match) {
                            continue;
                        }
                    }

                    // Check whether destination neutron destination port is set in flow classifier
                    if ((flowClassifier.dstPort() != null) && (!flowClassifier.dstPort().portId().isEmpty())) {
                        match = checkIpInVirtualPort(VirtualPortId.portId(flowClassifier.dstPort().portId()),
                                                     fiveTuple.ipDst());
                        if (!match) {
                            continue;
                        }
                    }

                    if (match) {
                        portChainId = portChain.portChainId();
                        break;
                    }
                }
            }
            return portChainId;
        }


        /**
         * Get the tenant id for the given mac address.
         *
         * @param mac mac address
         * @return tenantId tenant id for the given mac address
         */
        private TenantId getTenantId(MacAddress mac) {
            Collection<VirtualPort> virtualPorts = virtualPortService.getPorts();
            for (VirtualPort virtualPort : virtualPorts) {
                if (virtualPort.macAddress().equals(mac)) {
                    return virtualPort.tenantId();
                }
            }
            return null;
        }

        @Override
        public void process(PacketContext context) {
            Ethernet packet = context.inPacket().parsed();
            if (packet == null) {
                return;
            }
            // get the five tuple parameters for the packet
            short ethType = packet.getEtherType();
            IpAddress ipSrc = null;
            IpAddress ipDst = null;
            int portSrc = 0;
            int portDst = 0;
            byte protocol = 0;
            MacAddress macSrc = packet.getSourceMAC();
            TenantId tenantId = getTenantId(macSrc);

            if (ethType == Ethernet.TYPE_IPV4) {
                IPv4 ipv4Packet = (IPv4) packet.getPayload();
                ipSrc = IpAddress.valueOf(ipv4Packet.getSourceAddress());
                ipDst = IpAddress.valueOf(ipv4Packet.getDestinationAddress());
                protocol = ipv4Packet.getProtocol();
                if (protocol == IPv4.PROTOCOL_TCP) {
                    TCP tcpPacket = (TCP) ipv4Packet.getPayload();
                    portSrc = tcpPacket.getSourcePort();
                    portDst = tcpPacket.getDestinationPort();
                } else  if (protocol == IPv4.PROTOCOL_UDP) {
                    UDP udpPacket = (UDP) ipv4Packet.getPayload();
                    portSrc = udpPacket.getSourcePort();
                    portDst = udpPacket.getDestinationPort();
                }
            } else if (ethType == Ethernet.TYPE_IPV6) {
                return;
            }


            FiveTuple fiveTuple = DefaultFiveTuple.builder()
                    .setIpSrc(ipSrc)
                    .setIpDst(ipDst)
                    .setPortSrc(PortNumber.portNumber(portSrc))
                    .setPortDst(PortNumber.portNumber(portDst))
                    .setProtocol(protocol)
                    .setTenantId(tenantId)
                    .build();

            PortChainId portChainId = findPortChainFromFiveTuple(fiveTuple);

            if (portChainId == null) {
                log.error("Packet does not match with any classifier");
                return;
            }

            // TODO
            // download the required flow rules for classifier and forwarding
            // resend the packet back to classifier
        }
    }
}

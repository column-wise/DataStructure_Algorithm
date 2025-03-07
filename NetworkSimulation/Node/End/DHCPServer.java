package NetworkSimulation.Node.End;

import NetworkSimulation.DataUnit.DataLinkLayer.EthernetFrame;
import NetworkSimulation.DataUnit.DataUnit;
import NetworkSimulation.DataUnit.NetworkLayer.IPPacket;
import NetworkSimulation.Network.Network;
import NetworkSimulation.Node.Node;
import NetworkSimulation.DataUnit.TransportLayer.UDPDatagram;
import NetworkSimulation.Util.IPUtil;
import NetworkSimulation.Util.PayloadParser;

import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

public class DHCPServer extends Node {

    private final int ipAddress;
    private final int startIP;
    private final int endIP;
    private final long LEASE_TIME = 60000;   // 60초 후 만료
    private final Map<Integer, Long> allocatedIP;

    public DHCPServer(String MACAddress, String ipAddress, Network network) throws UnknownHostException {
        super(MACAddress, network);
        this.ipAddress = IPUtil.ipToInt(ipAddress);
        startIP = network.getSubnetAddress() + 1;
        endIP = (network.getSubnetAddress() | (~network.getSubnetMask())) - 1;
        allocatedIP = new HashMap<>();
    }

    // client 가 discover를 보낸 후 DHCP request를 하지 않을 수도 있지만 바로 request를 한다고 가정.
    private int findAllocatableIP() {
        cleanExpiredIPs();
        return allocateIP();
    }

    private int allocateIP() {
        for(int ip = startIP; ip <= endIP; ip++) {
            if(ip == ipAddress) continue;
            if(!allocatedIP.containsKey(ip)) {
                allocatedIP.put(ip, System.currentTimeMillis() + LEASE_TIME);
                return ip;
            }
        }
        return -1;
    }

    // TODO.
    private void cleanExpiredIPs() {
        for(Map.Entry<Integer, Long> entry : allocatedIP.entrySet()) {
            if(entry.getValue() < System.currentTimeMillis()) {
                allocatedIP.remove(entry.getKey());
            }
        }
    }

    @Override
    public void receive(DataUnit data) {
        EthernetFrame frame = (EthernetFrame) data;
        String destinationMAC = frame.getDestinationMAC();
        String sourceMAC = frame.getSourceMAC();

        IPPacket ipPacket = frame.getIPPacket();

        // UDP: IP 프로토콜 17
        if(ipPacket.getProtocol() != 17) return;

        String destinationIP = ipPacket.getDestinationIP();
        String sourceIP = ipPacket.getSourceIP();

        UDPDatagram udpDatagram = (UDPDatagram) ipPacket.getTransportDataUnit();
        Map<String, String> payload = PayloadParser.parsePayload(udpDatagram.getPayload());
        String dhcpMessageType = payload.get("DHCP Message Type");

        // received DHCP discover
        if(destinationMAC.equals("FF:FF:FF:FF:FF:FF")
                && destinationIP.equals("255.255.255.255")
                && sourceIP.equals("0.0.0.0")
                && dhcpMessageType.equals("DHCPDISCOVER")) {
            offerDHCP(sourceMAC);
        }
    }

    private void offerDHCP(String clientMAC) {
        String payload = "Client MAC=" + clientMAC +
                ",Your IP=" + findAllocatableIP() +
                ",Subnet Mask=" + IPUtil.intToIp(network.getSubnetMask()) +
                ",Router=" + IPUtil.intToIp(network.getSubnetAddress()+1) +
                ",DNS=" + "DNS 서버 IP 주소" +
                ",IP Lease Time=" + LEASE_TIME +
                ",DHCP Message Type=DHCPOFFER" +
                ",DHCP Server Identifier=" + ipAddress;

        // DHCP의 UDP 포트: 서버(67), 클라이언트(68)
        int sourcePort = 67;
        int destinationPort = 68;
        int length = payload.length() + 8;  // UDP 헤더 기본 길이 8바이트를 포함
        int checksum = 0; // 시뮬레이션에서는 체크섬 계산 생략

        UDPDatagram.UDPHeader header = new UDPDatagram.UDPHeader(sourcePort, destinationPort, length, checksum);
        UDPDatagram datagram = new UDPDatagram(header, payload);
        IPPacket ipPacket = new IPPacket(IPUtil.intToIp(ipAddress), "255.255.255.255", 6, datagram);
        EthernetFrame frame = new EthernetFrame("", MACAddress, 0x0800, ipPacket);
        network.broadcast(frame);
    }
}

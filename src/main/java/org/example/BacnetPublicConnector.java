package org.example;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkBuilder;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkUtils;
import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.service.confirmed.ReadPropertyRequest;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;

import java.net.InetAddress;

public class BacnetPublicConnector {

    private LocalDevice local;


    public void start() throws Exception {
        // For public IP connections
        IpNetworkBuilder builder = new IpNetworkBuilder()
                .withLocalBindAddress("0.0.0.0")  // listen on all NICs
                .withSubnet("255.255.255.0", 32)  // Single host subnet for WAN connections
                .withPort(47808)
                .withReuseAddress(true);

        IpNetwork network = builder.build();
        local = new LocalDevice(234234, new DefaultTransport(network));
        local.initialize();

        System.out.println("Local BACnet device initialized on port 47808");
    }

    public RemoteDevice connectToGateway(String publicIp, int remotePort, int deviceInstanceNumber) throws Exception {

        // Create remote address for the public IP
        byte[] ipBytes = InetAddress.getByName(publicIp).getAddress();
        OctetString mac = IpNetworkUtils.toOctetString(ipBytes, remotePort);
        Address addr = new Address(mac);

        System.out.println("Attempting to connect to: " + publicIp + ":" + remotePort);
        System.out.println("Looking for device instance: " + deviceInstanceNumber);

        // Create WhoIsRequest for the specific device
        WhoIsRequest whoIs = new WhoIsRequest(deviceInstanceNumber, deviceInstanceNumber);

        // Send WHO-IS directly to the remote address
        local.send(addr, whoIs);

        System.out.println("WHO-IS sent, waiting for I-Am response...");

        // Wait for device to respond with I-Am
        Thread.sleep(3000);

        // Try to get the remote device from cache (it should be there if I-Am was received)
        RemoteDevice discovered = local.getCachedRemoteDevice(deviceInstanceNumber);

        if (discovered != null) {
            System.out.println("Device discovered via WHO-IS/I-Am exchange!");

            // Get extended information
            //discovered.getExtendedDeviceInformation(local);


            System.out.println("Device: " + discovered);
            System.out.println("Model Name: " + discovered.getModelName());
            System.out.println("Vendor: " + discovered.getVendorName());

            return discovered;
        } else {
            // If WHO-IS didn't work, try direct communication
            System.out.println("WHO-IS/I-Am failed, attempting direct communication...");

            // Create remote device manually
            RemoteDevice remote = new RemoteDevice(local, deviceInstanceNumber, addr);

            try {
                // Try to read device object name to verify connection
                ReadPropertyRequest request = new ReadPropertyRequest(
                        new ObjectIdentifier(ObjectType.device, deviceInstanceNumber),
                        PropertyIdentifier.objectName
                );

                local.send(remote, request).get();

                System.out.println("Direct communication successful!");

                // Get extended information

                System.out.println("Device: " + remote);
                System.out.println("Model Name: " + remote.getModelName());

                return remote;

            } catch (Exception e) {
                System.err.println("Failed to establish connection: " + e.getMessage());
                throw e;
            }
        }
    }

    public void shutdown() {
        if (local != null) {
            local.terminate();
        }
    }

    // Usage example
    public static void main(String[] args) {
        BacnetPublicConnector connector = new BacnetPublicConnector();
        try {
            connector.start();
            System.out.println("What is happening ?");
            // Replace with your actual values
            String gatewayPublicIp = "38.10.108.156";
            int remotePort = 47808;
            int deviceInstance = 600032; // Your gateway's BACnet device ID

            RemoteDevice gateway = connector.connectToGateway(
                    gatewayPublicIp,
                    remotePort,
                    deviceInstance
            );

            System.out.println("\nConnection established successfully! : " + gateway.getModelName() );

            // Keep connection alive or do your work here
            Thread.sleep(1000);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            connector.shutdown();
        }
    }
}
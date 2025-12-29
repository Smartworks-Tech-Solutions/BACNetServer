package org.example;


import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.ServiceFuture;
import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkBuilder;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkUtils;
import com.serotonin.bacnet4j.service.acknowledgement.ReadPropertyAck;
import com.serotonin.bacnet4j.service.confirmed.ReadPropertyRequest;
import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.bacnet4j.transport.ServiceFutureImpl;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class BacnetDeviceDiscovery {

    private LocalDevice local;

    public void start() throws Exception {
        IpNetworkBuilder builder = new IpNetworkBuilder()
                .withLocalBindAddress("0.0.0.0")  // listen on all NICs
                .withSubnet("192.168.1.0", 32)  // Single host subnet for WAN connections
                .withPort(47808)
                .withReuseAddress(true);

        IpNetwork network = builder.build();
        local = new LocalDevice(234234, new DefaultTransport(network));
        local.initialize();

        System.out.println("Local BACnet device initialized on port 47808");
    }

    /**
     * Discover all BACnet devices at a specific IP address
     */
    public void discoverDevicesAt(String remoteIp, int remotePort) throws Exception {
        System.out.println("\n=== BACnet Device Discovery ===");
        System.out.println("Target: " + remoteIp + ":" + remotePort);
        System.out.println("Searching for devices...\n");

        CountDownLatch latch = new CountDownLatch(1);

        // Add listener to capture I-Am responses
        DeviceEventAdapter listener = new DeviceEventAdapter() {
            @Override
            public void iAmReceived(RemoteDevice d) {
                System.out.println("┌─────────────────────────────────────────────");
                System.out.println("│ DEVICE FOUND!");
                System.out.println("├─────────────────────────────────────────────");
                System.out.println("│ Device Instance: " + d.getInstanceNumber());
                System.out.println("│ Address: " + d.getAddress());
                System.out.println("│ Max APDU: " + d.getMaxAPDULengthAccepted());
                System.out.println("│ Segmentation: " + d.getSegmentationSupported());
                System.out.println("│ Vendor ID: " + d.getVendorIdentifier());

                try {
                    // Try to get more info
                    //d.getExtendedDeviceInformation(local);
                    System.out.println("│ Model Name: " + d.getModelName());
                    System.out.println("│ Object Name: " + d.getName());
                    //System.out.println("│ Description: " + d.getDescription());
                    System.out.println("│ Vendor Name: " + d.getVendorName());
                } catch (Exception e) {
                    System.out.println("│ (Extended info not available)");
                }

                System.out.println("└─────────────────────────────────────────────\n");

                ObjectIdentifier deviceOid =
                        new ObjectIdentifier(ObjectType.device, d.getInstanceNumber());

                PropertyIdentifier propertyId = PropertyIdentifier.objectList;

                ReadPropertyRequest request =
                        new ReadPropertyRequest(deviceOid, propertyId);

                ServiceFuture future =
                         local.send(
                                d,
                                request
                        );

                ReadPropertyAck ack =
                        null;
                try {
                    ack = future.get();
                } catch (BACnetException e) {
                    throw new RuntimeException(e);
                }

                @SuppressWarnings("unchecked")
                SequenceOf<ObjectIdentifier> objectList =
                        (SequenceOf<ObjectIdentifier>) ack.getValue();

                for (ObjectIdentifier oid : objectList) {
                    System.out.println("Object: " + oid);
                }
            }
        };

        local.getEventHandler().addListener(listener);

        try {
            // Create address for the remote IP
            byte[] ipBytes = InetAddress.getByName(remoteIp).getAddress();
            OctetString mac = IpNetworkUtils.toOctetString(ipBytes, remotePort);
            Address addr = new Address(mac);

            // Send WHO-IS to discover ALL devices at this address
            // (no range specified = all devices)
            WhoIsRequest whoIs = new WhoIsRequest();

            System.out.println("Sending WHO-IS broadcast to " + remoteIp + ":" + remotePort);
            local.send(addr, whoIs);

            // Wait for responses
            System.out.println("Listening for I-Am responses for 10 seconds...\n");
            latch.await(10, TimeUnit.SECONDS);

            // Check if any devices were found
            if (local.getRemoteDevices().isEmpty()) {
                System.out.println("❌ No devices responded.");
                System.out.println("\nPossible reasons:");
                System.out.println("1. Firewall blocking UDP port " + remotePort);
                System.out.println("2. Device is offline or unreachable");
                System.out.println("3. Device doesn't support WHO-IS requests");
                System.out.println("4. Device requires BBMD registration");
                System.out.println("5. Incorrect IP address");
            } else {
                System.out.println("✓ Discovery complete. Found " +
                        local.getRemoteDevices().size() + " device(s)");
            }

        } finally {
            local.getEventHandler().removeListener(listener);
        }
    }

    /**
     * Try to discover a device by testing common device ID ranges
     */
    public void bruteForceDeviceId(String remoteIp, int remotePort) throws Exception {
        System.out.println("\n=== Brute Force Device ID Search ===");
        System.out.println("Testing common device ID ranges...\n");

        // Common ranges to test
        int[][] ranges = {
                {1, 100},           // Very common for small installations
                {100, 1000},        // Common commercial range
                {1000, 10000},      // Larger installations
                {100000, 100100},   // Some vendors use high numbers
                {400000, 400100}    // Another common high range
        };

        DeviceEventAdapter listener = new DeviceEventAdapter() {
            @Override
            public void iAmReceived(RemoteDevice d) {
                System.out.println("✓ FOUND: Device Instance " + d.getInstanceNumber() +
                        " at " + d.getAddress());
            }
        };

        local.getEventHandler().addListener(listener);

        try {
            byte[] ipBytes = InetAddress.getByName(remoteIp).getAddress();
            OctetString mac = IpNetworkUtils.toOctetString(ipBytes, remotePort);
            Address addr = new Address(mac);

            for (int[] range : ranges) {
                System.out.println("Testing range " + range[0] + " to " + range[1] + "...");

                WhoIsRequest whoIs = new WhoIsRequest(range[0], range[1]);
                local.send(addr, whoIs);

                Thread.sleep(2000); // Wait for responses
            }

            System.out.println("\nSearch complete.");

        } finally {
            local.getEventHandler().removeListener(listener);
        }
    }

    public void shutdown() {
        if (local != null) {
            local.terminate();
        }
    }

    public static void main(String[] args) {
        BacnetDeviceDiscovery discovery = new BacnetDeviceDiscovery();

        try {
            discovery.start();

            // Replace with your gateway's public IP
            String gatewayIp = "192.168.1.10";
            int port = 47808;

            // Method 1: Simple discovery (sends WHO-IS to all devices)
            discovery.discoverDevicesAt(gatewayIp, port);

            // Method 2: If no response, try brute force search
            if (discovery.local.getRemoteDevices().isEmpty()) {
                System.out.println("\nNo response to general WHO-IS.");
                System.out.println("Attempting targeted range search...");
                discovery.bruteForceDeviceId(gatewayIp, port);
            }

            // Keep running to receive any late responses
            Thread.sleep(5000);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            discovery.shutdown();
        }
    }
}
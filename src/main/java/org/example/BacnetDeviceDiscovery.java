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
import com.serotonin.bacnet4j.service.acknowledgement.ReadPropertyMultipleAck;
import com.serotonin.bacnet4j.service.confirmed.ReadPropertyMultipleRequest;
import com.serotonin.bacnet4j.service.confirmed.ReadPropertyRequest;
import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.bacnet4j.type.constructed.*;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.RequestUtils;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BacnetDeviceDiscovery {

    private LocalDevice local;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor();
    public void start() throws Exception {
        IpNetworkBuilder builder = new IpNetworkBuilder()
                .withLocalBindAddress("0.0.0.0")  // listen on all NICs
                .withSubnet("192.168.1.0", 24)  // Single host subnet for WAN connections
                .withPort(47808)
                .withReuseAddress(true);

        IpNetwork network = builder.build();
        DefaultTransport transport = new DefaultTransport(network);
        transport.setTimeout(15000);

        transport.setRetries(3);
        transport.setSegTimeout(5000);
        transport.setSegWindow(10);

        local = new LocalDevice(234234, transport);


        local.initialize();

        System.out.println("Local BACnet device initialized on port 47808");

    }


    private void enumerateByType(RemoteDevice d) {

        ObjectType[] types = {
                ObjectType.analogInput,
                ObjectType.analogOutput,
                ObjectType.analogValue,
                ObjectType.binaryInput,
                ObjectType.binaryOutput,
                ObjectType.binaryValue,
                ObjectType.multiStateInput,
                ObjectType.multiStateOutput,
                ObjectType.multiStateValue
        };

        int maxInstance = 10000; // realistic for large systems

        for (ObjectType type : types) {
            for (int i = 0; i < maxInstance; i++) {
                try {
                    ObjectIdentifier oid =
                            new ObjectIdentifier(type, i);

                    ReadPropertyRequest req =
                            new ReadPropertyRequest(
                                    oid,
                                    PropertyIdentifier.objectName
                            );

                    ReadPropertyAck ack =
                            local.send(d, req).get();

                    System.out.println("FOUND: " + oid);

                    Thread.sleep(50); // RATE LIMIT

                } catch (BACnetException e) {
                    // object does not exist → ignore
                } catch (InterruptedException ignored) {}
            }
        }
    }


    private void readObjectListSafeAnthropic(RemoteDevice d) {
        System.out.println(
                "Reading object-list from device "
                        + d.getInstanceNumber()
                        + " (Segmentation: " + d.getSegmentationSupported() + ") "
                        + " (MaxAPDU=" + d.getMaxAPDULengthAccepted() + ")"
        );

        // Strategy 1: Try RequestUtils first
        try {
            System.out.println("Strategy 1: Using RequestUtils.getObjectList()...");
            SequenceOf<ObjectIdentifier> objectList = RequestUtils.getObjectList(local, d);

            System.out.println("✓ Success! Total objects: " + objectList.size());
            int i = 1;
            for (ObjectIdentifier oid : objectList) {
                System.out.println("  [" + i++ + "] " + oid);
            }
            System.out.println("Object-list complete!");
            return; // Success!

        } catch (BACnetException e) {
            System.err.println("Strategy 1 failed: " + e.getMessage());
        }

        // Strategy 2: Try reading entire property at once
        try {
            System.out.println("Strategy 2: Reading entire objectList property...");

            ObjectIdentifier deviceOid = new ObjectIdentifier(
                    ObjectType.device,
                    d.getInstanceNumber()
            );

            ReadPropertyRequest req = new ReadPropertyRequest(
                    deviceOid,
                    PropertyIdentifier.objectList
                    // No index = read entire property
            );

            ServiceFuture future = local.send(d, req);
            ReadPropertyAck ack = (ReadPropertyAck) future.get(); // 30 second timeout

            @SuppressWarnings("unchecked")
            SequenceOf<ObjectIdentifier> list = (SequenceOf<ObjectIdentifier>) ack.getValue();

            System.out.println("✓ Success! Total objects: " + list.getCount());
            for (int i = 1; i <= list.getCount(); i++) {
                ObjectIdentifier oid = list.getBase1(i);
                System.out.println("  [" + i + "] " + oid);
            }
            System.out.println("Object-list complete!");
            return; // Success!

        } catch (Exception e) {
            System.err.println("Strategy 2 failed: " + e.getMessage());
        }

        // Strategy 3: Try reading with ReadPropertyMultiple
        try {
            System.out.println("Strategy 3: Using ReadPropertyMultiple...");

            ObjectIdentifier deviceOid = new ObjectIdentifier(
                    ObjectType.device,
                    d.getInstanceNumber()
            );

            // Read multiple properties at once (sometimes works better)
            ReadPropertyMultipleRequest rpm =
                    new ReadPropertyMultipleRequest(
                            new SequenceOf<>(
                                    new ReadAccessSpecification(
                                            deviceOid,
                                            new SequenceOf<>(
                                                    new PropertyReference(
                                                            PropertyIdentifier.objectList
                                                    )
                                            )
                                    )
                            )
                    );

            ServiceFuture future = local.send(d, rpm);
            ReadPropertyMultipleAck rpmAck =
                    (com.serotonin.bacnet4j.service.acknowledgement.ReadPropertyMultipleAck) future.get();

            // Extract object list from response
            ReadAccessResult result =
                    rpmAck.getListOfReadAccessResults().getBase1(1);

            @SuppressWarnings("unchecked")
            SequenceOf<ObjectIdentifier> list = (SequenceOf<ObjectIdentifier>)
                    result.getListOfResults().getBase1(1).getReadResult().getDatum();

            System.out.println("✓ Success! Total objects: " + list.getCount());
            for (int i = 1; i <= list.getCount(); i++) {
                ObjectIdentifier oid = list.getBase1(i);
                System.out.println("  [" + i + "] " + oid);
            }
            System.out.println("Object-list complete!");
            return; // Success!

        } catch (Exception e) {
            System.err.println("Strategy 3 failed: " + e.getMessage());
        }

        // Strategy 4: Fall back to index-by-index reading (slow but reliable)
        try {
            System.out.println("Strategy 4: Reading array index-by-index (slow method)...");

            ObjectIdentifier deviceOid = new ObjectIdentifier(
                    ObjectType.device,
                    d.getInstanceNumber()
            );

            // First, try to get the size
            ReadPropertyRequest sizeReq = new ReadPropertyRequest(
                    deviceOid,
                    PropertyIdentifier.objectList,
                    UnsignedInteger.ZERO
            );

            int size;
            try {
                ReadPropertyAck sizeAck = local.send(d, sizeReq).get();
                size = ((UnsignedInteger) sizeAck.getValue()).intValue();
                System.out.println("Array size: " + size);
            } catch (Exception e) {
                // If we can't get size, try a reasonable default
                System.out.println("Cannot get array size, trying first 500 indices...");
                size = 500;
            }

            int success = 0;
            int consecutiveFails = 0;

            for (int i = 1; i <= size; i++) {
                try {
                    ReadPropertyRequest itemReq = new ReadPropertyRequest(
                            deviceOid,
                            PropertyIdentifier.objectList,
                            new UnsignedInteger(i)
                    );

                    ReadPropertyAck itemAck = local.send(d, itemReq).get();
                    ObjectIdentifier oid = (ObjectIdentifier) itemAck.getValue();

                    System.out.println("  [" + i + "] " + oid);
                    success++;
                    consecutiveFails = 0;

                } catch (Exception e) {
                    consecutiveFails++;
                    System.err.println("  [" + i + "] failed");

                    // If we get 10 consecutive failures, probably reached the end
                    if (consecutiveFails >= 10) {
                        System.out.println("Stopping after 10 consecutive failures");
                        break;
                    }
                }

                Thread.sleep(100); // Rate limiting
            }

            System.out.println("Object-list complete (partial): " + success + " objects found");

        } catch (Exception e) {
            System.err.println("Strategy 4 failed: " + e.getMessage());
            System.err.println("All strategies failed for device " + d.getInstanceNumber());
            e.printStackTrace();
        }
    }


    private void readObjectListSafe(RemoteDevice d) {

        try {
            ObjectIdentifier deviceOid =
                    new ObjectIdentifier(
                            ObjectType.device,
                            d.getInstanceNumber()
                    );

            System.out.println(
                    "Reading object-list from device "
                            + d.getInstanceNumber()
                            + " (Segmentation Supported : " + d.getSegmentationSupported() + ") "
                            + " (MaxAPDU=" + d.getMaxAPDULengthAccepted() + ")"
            );

            // -------- read array size --------
            ReadPropertyRequest sizeReq =
                    new ReadPropertyRequest(
                            deviceOid,
                            PropertyIdentifier.objectList,
                            UnsignedInteger.ZERO
                    );

            ReadPropertyAck sizeAck =
                    local.send(d, sizeReq).get();

            int size =
                    ((UnsignedInteger) sizeAck.getValue()).intValue();

            System.out.println("Total objects: " + size);

            int success = 0;
            int failed = 0;

            // -------- read elements SLOWLY --------
            for (int i = 1; i <= size; i++) {

                try {
                    ReadPropertyRequest itemReq =
                            new ReadPropertyRequest(
                                    deviceOid,
                                    PropertyIdentifier.objectList,
                                    new UnsignedInteger(i)
                            );

                    ReadPropertyAck itemAck =
                            local.send(d, itemReq).get();

                    ObjectIdentifier oid =
                            (ObjectIdentifier) itemAck.getValue();

                    System.out.println("  [" + i + "] " + oid);
                    success++;

                } catch (BACnetException e) {
                    failed++;
                    System.err.println(
                            "  [" + i + "] timeout / unsupported"
                    );
                }

                // 🔥 RATE LIMIT (CRITICAL)
                Thread.sleep(75);
            }

            System.out.println(
                    "Object-list complete: success="
                            + success + ", failed=" + failed
            );

        } catch (Exception e) {
            System.err.println(
                    "Object-list FAILED for device "
                            + d.getInstanceNumber()
            );
            e.printStackTrace();
        }
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

                // ✅ NEVER BLOCK HERE
                latch.countDown();

                // ✅ THIS is where readObjectListSafe is used
                worker.submit(() -> enumerateByType(d));
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
            /*if (local.getRemoteDevices().isEmpty()) {
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
            }*/

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
        worker.shutdownNow();
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
            /*if (discovery.local.getRemoteDevices().isEmpty()) {
                System.out.println("\nNo response to general WHO-IS.");
                System.out.println("Attempting targeted range search...");
                discovery.bruteForceDeviceId(gatewayIp, port);
            }*/

            // Keep running to receive any late responses
            Thread.sleep(500000);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            discovery.shutdown();
        }
    }
}
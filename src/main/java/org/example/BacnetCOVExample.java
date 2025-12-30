package org.example;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.service.confirmed.SubscribeCOVRequest;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.type.primitive.Boolean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BacnetCOVExample {

    private LocalDevice local;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();



    public void setupCOVListener() {
        DeviceEventAdapter listener = new DeviceEventAdapter() {
            @Override
            public void covNotificationReceived(
                    UnsignedInteger subscriberProcessIdentifier,
                    ObjectIdentifier initiatingDeviceIdentifier,  // This is the device ObjectIdentifier, not RemoteDevice
                    ObjectIdentifier monitoredObjectIdentifier,
                    UnsignedInteger timeRemaining,
                    SequenceOf<PropertyValue> listOfValues) {

                System.out.println("\n🔔 COV NOTIFICATION RECEIVED!");
                System.out.println("From Device: " + initiatingDeviceIdentifier);
                System.out.println("Object: " + monitoredObjectIdentifier);
                System.out.println("Subscription ID: " + subscriberProcessIdentifier);
                System.out.println("Time Remaining: " + timeRemaining + " seconds");
                System.out.println("Changed Properties:");

                // Parse the changed values
                for (PropertyValue pv : listOfValues) {
                    PropertyIdentifier propId = pv.getPropertyIdentifier();
                    Encodable value = pv.getValue();

                    System.out.println("  " + propId + " = " + value);

                    // Handle specific property types
                    if (propId.equals(PropertyIdentifier.presentValue)) {
                        if (value instanceof Real) {
                            float floatVal = ((Real) value).floatValue();
                            System.out.println("    → Float value: " + floatVal);
                        } else if (value instanceof com.serotonin.bacnet4j.type.enumerated.BinaryPV) {
                            com.serotonin.bacnet4j.type.enumerated.BinaryPV binaryVal =
                                    (com.serotonin.bacnet4j.type.enumerated.BinaryPV) value;
                            System.out.println("    → Binary value: " + binaryVal);
                            System.out.println("    → Is Active: " + binaryVal.equals(
                                    com.serotonin.bacnet4j.type.enumerated.BinaryPV.active));
                        } else if (value instanceof UnsignedInteger) {
                            int intVal = ((UnsignedInteger) value).intValue();
                            System.out.println("    → Integer value: " + intVal);
                        }
                    }

                    if (propId.equals(PropertyIdentifier.statusFlags)) {
                        System.out.println("    → Status: " + value);
                    }
                }

                System.out.println("========================\n");

                // If you need to get the RemoteDevice object
                try {
                    RemoteDevice device = local.getRemoteDevice(
                            initiatingDeviceIdentifier.getInstanceNumber()
                    ).get();
                    if (device != null) {
                        System.out.println("Device Name: " + device.getName());
                    }
                } catch (Exception e) {
                    // Device might not be in cache
                }
            }
        };

        local.getEventHandler().addListener(listener);
    }

    public void subscribeToObjects(RemoteDevice device) {
        // Subscribe to multiple objects
        ObjectIdentifier[] objectsToMonitor = {
                new ObjectIdentifier(ObjectType.analogInput, 1),
                new ObjectIdentifier(ObjectType.analogInput, 2),
                new ObjectIdentifier(ObjectType.binaryOutput, 1),
                new ObjectIdentifier(ObjectType.analogValue, 1)
        };

        int subscriptionId = 1;
        for (ObjectIdentifier oid : objectsToMonitor) {
            try {
                SubscribeCOVRequest covRequest = new SubscribeCOVRequest(
                        new UnsignedInteger(subscriptionId++),
                        oid,
                        Boolean.valueOf(true),      // confirmed notifications
                        new UnsignedInteger(0)  // lifetime: 0 = infinite
                );

                local.send(device, covRequest).get();
                System.out.println("✓ Subscribed to " + oid);

            } catch (BACnetException e) {
                System.err.println("✗ Failed to subscribe to " + oid + ": " + e.getMessage());
            }
        }
    }


    public void setLocalDevice(LocalDevice local){
        this.local = local;
    }

    /*public static void main(String[] args) {
        BacnetCOVExample app = new BacnetCOVExample();

        try {
            app.start();

            // Discover devices
            String deviceIp = "192.168.1.10";
            // ... device discovery code ...

            // Once you have a RemoteDevice, subscribe to its objects
            // RemoteDevice device = ...
            // app.subscribeToObjects(device);

            // Keep running to receive notifications


        } catch (Exception e) {
            e.printStackTrace();
        }
    }*/
}

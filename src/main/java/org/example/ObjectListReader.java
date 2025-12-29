package org.example;


import com.serotonin.bacnet4j.*;
import com.serotonin.bacnet4j.exception.*;
import com.serotonin.bacnet4j.obj.*;
import com.serotonin.bacnet4j.service.acknowledgement.*;
import com.serotonin.bacnet4j.service.confirmed.*;
import com.serotonin.bacnet4j.type.constructed.*;
import com.serotonin.bacnet4j.type.enumerated.*;
import com.serotonin.bacnet4j.type.primitive.*;

import java.util.*;
import java.util.concurrent.*;

public final class ObjectListReader {

    private static final int TIMEOUT_SEC = 5;
    private static final int MAX_RETRIES = 3;

    private ObjectListReader() {}

    public static List<ObjectIdentifier> readAll(
            LocalDevice localDevice,
            RemoteDevice remoteDevice
    ) throws Exception {

        ObjectIdentifier deviceOid =
                new ObjectIdentifier(
                        ObjectType.device,
                        remoteDevice.getInstanceNumber()
                );

        int size = readArraySize(localDevice, remoteDevice, deviceOid);

        List<ObjectIdentifier> result = new ArrayList<>(size);

        for (int i = 1; i <= size; i++) {
            ObjectIdentifier oid =
                    readArrayElement(
                            localDevice,
                            remoteDevice,
                            deviceOid,
                            i
                    );

            if (oid != null) {
                result.add(oid);
            }
        }

        return result;
    }

    // --------------------------------------------------------------------

    private static int readArraySize(
            LocalDevice localDevice,
            RemoteDevice remoteDevice,
            ObjectIdentifier deviceOid
    ) throws Exception {

        ReadPropertyRequest request =
                new ReadPropertyRequest(
                        deviceOid,
                        PropertyIdentifier.objectList,
                        UnsignedInteger.ZERO
                );

        ReadPropertyAck ack =
                sendWithRetry(localDevice, remoteDevice, request);

        return ((UnsignedInteger) ack.getValue()).intValue();
    }

    private static ObjectIdentifier readArrayElement(
            LocalDevice localDevice,
            RemoteDevice remoteDevice,
            ObjectIdentifier deviceOid,
            int index
    ) {

        ReadPropertyRequest request =
                new ReadPropertyRequest(
                        deviceOid,
                        PropertyIdentifier.objectList,
                        new UnsignedInteger(index)
                );

        try {
            ReadPropertyAck ack =
                    sendWithRetry(localDevice, remoteDevice, request);

            return (ObjectIdentifier) ack.getValue();

        } catch (BACnetTimeoutException e) {
            System.err.println(
                    "Timeout reading object-list[" + index + "] from device "
                            + remoteDevice.getInstanceNumber()
            );
        } catch (Exception e) {
            System.err.println(
                    "Failed reading object-list[" + index + "]: " + e.getMessage()
            );
        }

        return null;
    }

    // --------------------------------------------------------------------

    private static ReadPropertyAck sendWithRetry(
            LocalDevice localDevice,
            RemoteDevice remoteDevice,
            ReadPropertyRequest request
    ) throws Exception {

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                ServiceFuture future =
                        localDevice.send(remoteDevice, request);

                return future.get();

            } catch (BACnetTimeoutException e) {
                if (attempt == MAX_RETRIES) {
                    throw e;
                }
                Thread.sleep(300);
            }
        }

        throw new BACnetTimeoutException("Unreachable");
    }
}

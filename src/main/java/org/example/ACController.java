package org.example;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkBuilder;
import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.bacnet4j.util.RemoteDeviceDiscoverer;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class ACController {
    // Source - https://stackoverflow.com/a
// Posted by SimpleJack, modified by community. See post 'Timeline' for change history
// Retrieved 2025-12-11, License - CC BY-SA 4.0

    public static void main(String[] args) throws Exception {
        // Grab your current local address
       BacnetPublicConnector connector = new BacnetPublicConnector();
       connector.start();
       //connector.connectToGateway("38.10.108.156");
    }

}

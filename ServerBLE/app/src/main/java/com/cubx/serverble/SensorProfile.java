package com.cubx.serverble;

import java.util.UUID;

public class SensorProfile {

    public static UUID SENSOR_SERVICE = UUID.fromString("06391ebb-4050-42b6-ab55-8282a15fa094");    /* Current Sensor Service UUID */

    public static UUID DATA_R = UUID.fromString("010d815c-031c-4de8-ac10-1ffebcf874fa");            /* Readable Data Characteristic */
    public static UUID DATA_R2 = UUID.fromString("1bc9f3e1-ad2c-4305-a855-f9f1b9bcef8b");            /* Readable Data Characteristic */

    public static UUID DATA_W = UUID.fromString("f2926f0f-336a-4502-9948-e4e8fd2316e9");            /* Writable Data Characteristic */

    public static UUID CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");     /* Client Characteristic Config Descriptor */
}

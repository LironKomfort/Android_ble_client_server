package com.cubx.clientble;

import java.util.UUID;

public class SensorProfile {
    private static final String TAG = SensorProfile.class.getSimpleName();

    public static UUID SENSOR_SERVICE = UUID.fromString("06391ebb-4050-42b6-ab55-8282a15fa094");    /* Current Sensor Service UUID */
    public static UUID DATA_R = UUID.fromString("010d815c-031c-4de8-ac10-1ffebcf874fa");            /* Readable Data Characteristic */
    public static UUID CLIENT_CONFIG = UUID.fromString("7385e060-b9a8-4853-848d-c70178b0e01e");     /* Client Characteristic Config Descriptor */
    public static UUID DATA_W = UUID.fromString("f2926f0f-336a-4502-9948-e4e8fd2316e9");            /* Writable Data Characteristic */

}
# BTLEPicoServer
Project for testing Bluetooth LE in Android Things

The project consists of two applications:
• BLEserver: the main Android Things app;
• BLEclient: a companion Android app.

The role of Master is played by the smartphone: it starts scanning for available devices that advertise, in order to start a connection. The list of accessible devices is displayed in the smatphone and, when a device in the list is selected by the user, a connection is initialized: if the devices were already paired, the connection proceeds normally, else a pairing phase begins, with
a pairing request sent by the smartphone.
The role of Slave is played by the PicoPi: it broadcasts information about its service and its characteristics and it waits for the incoming connection requests. After the connection is established, the PicoPi starts sending the temperature value, detected by its sensor, over the BLE channel.

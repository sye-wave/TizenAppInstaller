# Tizen App Installer
This app is an Android implementation of Samsung's 
[Smart Development Bridge](https://developer.tizen.org/development/tizen-studio/web-tools/running-and-testing-your-app/sdb) 
(SDB) that is built for streamlined installation of apps and services to Samsung smart watches and other SDB-supported devices. 

This app uses Cameron Gutman's [AdbLib](https://github.com/cgutman/AdbLib) to implement the general communication protocol 
between the Android and Samsung device.
# Prerequisites
1. Minimum Android Sdk version of 19 (KitKat)
2. Both the Android device and SDB-supported device must be connected to a secure network (running the app on a public or unsecure network will fail)
# Overview
This app adapts SDB from C to Java, implementing SDB's file syncing and installation process to run on Android devices. 
The app utilizes a [general communication protocol](https://android.googlesource.com/platform/system/core/+/master/adb/protocol.txt) 
that is used in both SDB and Google's Android Development Bridge (ADB), 
as well as a [subprotocol](https://android.googlesource.com/platform/system/core/+/master/adb/SYNC.TXT) 
that is specifically used for syncing files between devices. 
AdbLib implements the general communication protocol, while the application implements the sync protocol.

Upon being given an IP address and optional port number for the target device, 
the app will start a background service that attempts to connect to the device. 
If successful, the service will try to push the given app file to the device, 
request the device to install the app, and finally remove the temporary app file.
# Usage
While using the application is straight-forward, there are some initial steps that 
need to be made to the current iteration of the code to work properly.

### Setup
In the current iteration of the app, the watch app or service file that is to be downloaded must be stored in the raw resources folder, 
and the two variables in the MainActivity class must be customized to the specific file: the file name and the file id.
```
public class MainActivity extends AppCompatActivity {
    private static final String rawFileName = "test_file.tpk"; //replace this with the name of the file to be installed
    private static final int rawFileID = R.raw.test_file; //replace this with the R.raw.FILENAME id
    ...
}
```
Once those variable are modified to correspond to the file put in the raw resources folder,
the application will be able to copy the file to the app's internal memory space, where it can then be used in the file transferring process.
The watch app must be either a tpk file, wgt file, or rpm file to properly be installed; using other files types or extensions will fail.

As well, ensure that the debugging mode on the target device is turned on. Without this mode on, the application will
be unable to connect to the device.
### Running the App

After installing the setup application on an Android device and opening it, the user enters the main UI page,
where they will be able to enter the IP address and port of the device they want to install to.
The IP address is required to be inputted, while the port input field is optional and defaults to 26101, 
the default port used in SDB. 

Once everything is inputted, pressing the "Update" button starts the background
service, which will continue running until either the installation is successful or a problem occurred.
On the target device, a popup might appear asking to accept a public RSA key: to continue with the installation
you must accept the public key. 

If the installation was successful, a toast will pop up notifying them that the process is complete.
Otherwise, a toast will pop up warning the user of the failed attempt and generally describing what
went wrong. For more detailed error messages, look at the debug and error logs made by the service using Logcat.

# License
This application is licensed under the Apache License Version 2.0. A copy of the Apache 2.0 license
can be found [here](https://github.com/AvivBenchorin/Samsung-Watch-App-Installer-For-Android/blob/master/LICENSE).

This app is derived from an Android Studio template and uses code examples by the [Android Open Source Project](https://developer.android.com/license), 
which are licensed under the Apache License Version 2.0. As well, the application's communication protocol 
implementation and installation procedure are derived from [Smart Development Bridge](https://review.tizen.org/git/?p=sdk/tools/sdb.git), 
which is also licensed under the Apache License Version 2.0, and the corresponding NOTICE file for SDB can be found [here](https://github.com/AvivBenchorin/Samsung-Watch-App-Installer-For-Android/blob/master/NOTICE).

This app uses [AdbLib](https://github.com/cgutman/AdbLib), which is licensed under the BSD 3-Clause License. A copy
of the BSD 3-Clause license can be found [here](https://github.com/AvivBenchorin/Samsung-Watch-App-Installer-For-Android/blob/master/app/libs/LICENSE). 




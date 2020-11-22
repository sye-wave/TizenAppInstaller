/*
* Samsung Watch App-Installer for Android
*
* Copyright 2020 Aviv Benchorin
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0

* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/


package org.healthscitech.rct.sdbupdateservice;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;


public class MainActivity extends AppCompatActivity {


    /*~*~*~*~*~
     Variables to modify:
     filename = name of file to be installed (in res/raw folder)
     rawFileID = resource ID number of file to be installed
     *~*~*~*~*/
    private static final String rawFileName = "test_file.tpk";
    private static final int rawFileID = R.raw.test_file;



    private static final String LOG_TAG =
            MainActivity.class.getSimpleName();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    private static void copyFile(InputStream inFile, FileOutputStream outFile) throws IOException{
        byte[] buffer = new byte[4096];
        int bytesRead;
        while((bytesRead = inFile.read(buffer)) > 0){
            outFile.write(buffer, 0, bytesRead);
        }
        inFile.close();
        outFile.close();
    }

    public void launchUpdateService(View view) {
        Log.d(LOG_TAG, "Trying to Connect!");
        final TextView remoteIPTextView = findViewById(R.id.hostIPInput);
        String remoteIP = remoteIPTextView.getText().toString();
        System.out.println("remoteIP:" + remoteIP + "\n");
        if(remoteIP.isEmpty()){
            Toast.makeText(this, "Error: The IP address field cannot be empty, please input a valid IP address", Toast.LENGTH_LONG).show();
            return;
        }
        final TextView portTextView = findViewById(R.id.hostPortInput);
        String port = portTextView.getText().toString();
        System.out.println("port:" + port + "\n");
        if(port.isEmpty())
            port = "26101";

        String rawFileName = MainActivity.rawFileName;
        int rawFileID = MainActivity.rawFileID;
        File sourceFile = new File("data/data/org.healthscitech.rct.sdbupdateservice/" + rawFileName);
        try {
            InputStream inFile = getResources().openRawResource(rawFileID);
            FileOutputStream internalOutStream = new FileOutputStream(sourceFile);
            copyFile(inFile, internalOutStream);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error: unable to find the source file at the given path", Toast.LENGTH_LONG).show();
            return;
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error: was unable to copy file to internal memory", Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(this, SdbUpdateService.class);
        intent.putExtra("sourceFile", sourceFile);
        intent.putExtra("remoteIP", remoteIP);
        intent.putExtra("port", Integer.parseInt(port));
        startService(intent);
    }
}

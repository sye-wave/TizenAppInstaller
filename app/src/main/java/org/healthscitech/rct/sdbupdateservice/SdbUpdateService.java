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

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

import android.util.Base64;
import android.widget.Toast;

import com.cgutman.adblib.AdbBase64;
import com.cgutman.adblib.AdbConnection;
import com.cgutman.adblib.AdbCrypto;
import com.cgutman.adblib.AdbStream;



/**
 * SdbUpdateService is a background service that, when started, attempts to push and install
 * a given file to a remote device that can natively communicate with Samsung's
 * Smart Development Bridge (SDB). This class defines the functions that sync, install, and delete
 * the file, sending and processing protocol messages through the AdbLib library.
 *
 * @author      Aviv Benchorin
 * @version     %I%, %G%
 * @since       1.0
 */
public class SdbUpdateService extends Service {

	private static final String LOG_TAG =
			SdbUpdateService.class.getSimpleName();

	public static final int SYNC_DATA_MAX = (2 * 1024); //64*1024 in SDB, downscaled to work with Android sockets
	public static final int MAX_PAYLOAD = (256 * 1024);

	public static final byte[] SYNC_DATA = {'D', 'A', 'T', 'A'};
	public static final byte[] SYNC_SEND = {'S', 'E', 'N', 'D'};
	public static final byte[] SYNC_RECV = {'R', 'E', 'C', 'V'};
	public static final byte[] SYNC_LIST = {'L', 'I', 'S', 'T'};
	public static final byte[] SYNC_DENT = {'D', 'E', 'N', 'T'};
	public static final byte[] SYNC_STAT = {'S', 'T', 'A', 'T'};
	public static final byte[] SYNC_OKAY = {'O', 'K', 'A', 'Y'};
	public static final byte[] SYNC_FAIL = {'F', 'A', 'I', 'L'};
	public static final byte[] SYNC_DONE = {'D', 'O', 'N', 'E'};
	public static final byte[] SYNC_QUIT = {'Q', 'U', 'I', 'T'};

	private byte[] syncPayload;
	private byte[] readPayload;
	private int syncPayloadOffset = 0;
	private int amountRead = 0;
	private int totalWritten = 0;
	private long toWrite = 0;

	private Looper serviceLooper;
	private ServiceHandler serviceHandler;

	public SdbUpdateService() {

	}

	private final class ServiceHandler extends Handler {
		public ServiceHandler(Looper looper) {
			super(looper);
		}

		@Override
		public void handleMessage(Message msg) {
			Bundle inputInfo = msg.getData();
			sdbUpdateServiceFunc((File) inputInfo.get("sourceFile"), inputInfo.getString("remoteIP"), inputInfo.getInt("port"));
			stopSelf(msg.arg1);
		}
	}

	@Override
	public void onCreate() {
		// Start up the thread running the service. Note that we create a
		// separate thread because the service normally runs in the process's
		// main thread, which we don't want to block. We also make it
		// background priority so CPU-intensive work doesn't disrupt our UI.
		HandlerThread thread = new HandlerThread("ServiceStartArguments",
				Process.THREAD_PRIORITY_BACKGROUND);
		thread.start();

		// Get the HandlerThread's Looper and use it for our Handler
		serviceLooper = thread.getLooper();
		serviceHandler = new ServiceHandler(serviceLooper);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// For each start request, send a message to start a job and deliver the
		// start ID so we know which request we're stopping when we finish the job
		Message msg = serviceHandler.obtainMessage();
		msg.arg1 = startId;
		msg.setData(intent.getExtras());
		serviceHandler.sendMessage(msg);
		// If we get killed, after returning from here, restart
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onDestroy() {}

	/**
	 * Called when the service is started. Creates the RSA keys that will be used for
	 * authentication, and then creates a AdbConnection object and attempts to connect to the
	 * given remote IP address. Upon a successful connection, tries to sync the given file to the
	 * remote device, install it, and delete the temporary remote file. Upon any failure or errors,
	 * creates a toast that warns the user of the general problem.
	 *
	 * @param sourceFile      the local file to be sent to the remote device
	 * @param remoteIP        the IP address of the remote device
	 * @param port			  the port of the remote device to connect to
	 * @since           	  1.0
	 */
	public void sdbUpdateServiceFunc(File sourceFile, String remoteIP, int port) {
		AdbConnection sdb;
		InetSocketAddress sockAddress;
		Socket sock;

		AdbCrypto crypto = null;

		AdbBase64 base64 = new AdbBase64() {
			@Override
			public String encodeToString(byte[] bytes) {
				return Base64.encodeToString(bytes, Base64.DEFAULT);
			}
		};

		File privateKey = new File("data/data/org.healthscitech.rct.sdbupdateservice/privateKey.txt");
		File publicKey = new File("data/data/org.healthscitech.rct.sdbupdateservice/publicKey.txt");
		if (privateKey.exists() && publicKey.exists()) {
			try {
				crypto = AdbCrypto.loadAdbKeyPair(base64, privateKey, publicKey);
			}catch(IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
				Log.d(LOG_TAG, "Failed to load existing AdbCrypto keys\n");
				crypto = null;
			}
		}
		if(crypto == null) {
			try {
				crypto = AdbCrypto.generateAdbKeyPair(base64);
				crypto.saveAdbKeyPair(privateKey, publicKey);
			} catch (NoSuchAlgorithmException | IOException e) {
				Toast.makeText(this, "Update Failed: RSA setup unsuccessful", Toast.LENGTH_LONG).show();
				e.printStackTrace();
				return;
			}
		}

		Log.d(LOG_TAG, "Attempting to create and connect socket\n");
		try {
			sockAddress = new InetSocketAddress(remoteIP, port);
			sock = new Socket();
			sock.connect(sockAddress, 10000);

		} catch (SocketTimeoutException e) {
			Toast.makeText(this, "Update Failed: timed out while trying to connect to the target", Toast.LENGTH_LONG).show();
			e.printStackTrace();
			return;
		}
		catch (UnknownHostException e) {
			Toast.makeText(this, "Update Failed: could not find specified remoteIP", Toast.LENGTH_LONG).show();
			e.printStackTrace();
			return;
		} catch (ConnectException e) {
			Toast.makeText(this, "Update Failed: could not connect to specified remoteIP", Toast.LENGTH_LONG).show();
			e.printStackTrace();
			return;
		} catch (IOException e) {
			Toast.makeText(this, "Update Failed: socket creation unsuccessful", Toast.LENGTH_LONG).show();
			e.printStackTrace();
			return;
		}
		Log.d(LOG_TAG, "Socket to remote device has connected successfully\n");

		try {
			sdb = AdbConnection.create(sock, crypto);
		} catch (IOException e) {
			e.printStackTrace();
			Toast.makeText(this, "Update Failed: creation of AdbConnection object unsuccessful", Toast.LENGTH_LONG).show();
			return;
		}

		Log.d(LOG_TAG, "SDB Update Service attempting to connect\n");
		try {
			sdb.connect();
		} catch (IOException | InterruptedException e) {
			Toast.makeText(this, "Update Failed: connection to remote device unsuccessful", Toast.LENGTH_LONG).show();
			e.printStackTrace();
			return;
		}
		Log.d(LOG_TAG, "SDB Update Service has successfully connected\n");

		String tempFilePath;
		String destinationPath;
		boolean successfulProcess = true;
		if ((tempFilePath = sdbUpdateGetTempDirectory(sdb)) == null) {
			Toast.makeText(this, "Update Failed: getting temporary directory unsuccessful", Toast.LENGTH_LONG).show();
		} else if(do_sync_copy(sdb, sourceFile, (destinationPath = tempFilePath + sourceFile.getName())) == -1) {
			//do_sync_copy(adb, this.sourceFile , "/home/owner/share/tmp/sdk_tools/test_file.tpk", 1);
			Toast.makeText(this, "Update Failed: syncing files to device unsuccessful", Toast.LENGTH_LONG).show();
			successfulProcess = false;
		} else {
			if (!sdbUpdateInstallTempFile(sdb, destinationPath)) {
				Toast.makeText(this, "Update Failed: file installation unsuccessful", Toast.LENGTH_LONG).show();
				successfulProcess = false;
			}
			if (!sdbUpdateRemoveTempFile(sdb, destinationPath)) {
				if(successfulProcess) {
					Toast.makeText(this, "Update Partially Failed: file installation successful, but file removal unsuccessful", Toast.LENGTH_LONG).show();
					successfulProcess = false;
				}
				else
					Toast.makeText(this, "File removal unsuccessful after unsuccessful installation", Toast.LENGTH_LONG).show();
			}
		}
		try {
			sdb.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		if(successfulProcess)
			Toast.makeText(this, "Update Complete!", Toast.LENGTH_LONG).show();

	}

	/**
	 * Sends the remote device a request for the temporary directory to write the app file to and
	 * processes the remote device's response.
	 *
	 * @param adb	the AdbConnection representing the connection between this device
	 *              and the remote device
	 * @return		the string of the temporary directory on the remote device if successful, else
	 * 				<code>null</code>
	 * @since       1.0
	 */
	private String sdbUpdateGetTempDirectory(AdbConnection adb){
		String tempFilePath;

		tempFilePath = getTempDirectoryFromCapability(adb);

		if(tempFilePath == null) {
			tempFilePath = getTempDirectoryFromPkgCmd(adb);
		}
		return tempFilePath;
	}

	private String getTempDirectoryFromCapability(AdbConnection adb) {
		AdbStream stream;
		String tempFilePath;
		String capabilitySize;
		try {
			stream = adb.open("capability:");
			capabilitySize = new String(stream.read(), StandardCharsets.US_ASCII);
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
			return null;
		}

		String fullCapability;
		String[] capabilityArray;
		try {
			fullCapability = new String(stream.read(), StandardCharsets.US_ASCII);
			Log.i(LOG_TAG, "getTempDirectoryFromCapability: full Capability  = " + fullCapability + "\n");
			capabilityArray = fullCapability.split("\n");
			for (String s : capabilityArray) {
				String[] keyPair;
				keyPair = s.split(":");
				if (keyPair.length > 1 && keyPair[0].equals("sdk_toolpath")) {
					if (keyPair[1].equals("unknown")) {
						break;
					} else {
						tempFilePath = keyPair[1];
						Log.d(LOG_TAG, "getTempDirectoryFromCapability: Capability toolpath = " + tempFilePath + "\n");
						return tempFilePath + "/";
					}
				}
			}
		}
		catch (InterruptedException | IOException e) {
			e.printStackTrace();
		}
		finally {
			try {
				stream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return null;
	}

	private String getTempDirectoryFromPkgCmd(AdbConnection adb) {
		AdbStream stream;
		String tempFilePath;
		String tempFilePathFull;

		try {
			stream = adb.open("shell:/usr/bin/pkgcmd -a | head -1 | awk '{print $5}'");
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
			return null;
		}

		try {
			tempFilePath = new String(stream.read(), StandardCharsets.US_ASCII);
			tempFilePathFull = tempFilePath.split("\r")[0].concat("/tmp/");
			Log.d(LOG_TAG, "getTempDirectoryFromPkgCmd: temp file path = " + tempFilePathFull + "\n");
			return tempFilePathFull;
		} catch (InterruptedException | IOException e) {
			e.printStackTrace();
		} finally {
			try {
				stream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return null;
	}

	/**
	 * Sends the remote device a request for to install the app file at the temporary directory, and
	 * processes the remote device's response. The remote device will continuously send messages
	 * back detailing the progress of the installation, as well as whether or not the installation was
	 * successful.
	 *
	 * @param adb				the AdbConnection representing the connection between this device
	 *              			and the remote device
	 * @param destinationPath	the String file path to write to on the remote device. Includes the
	 *                          name of the file (such as /path/to/temp/directory/filename.tpk)
	 * @return					<code>true</code> if the installation was successful; <code>false</code> otherwise.
	 * @since       			1.0
	 */
	private boolean sdbUpdateInstallTempFile(AdbConnection adb, String destinationPath){
		AdbStream stream;
		String[] filePathExtension = destinationPath.split("\\.");
		String installShellCommand, extension;
		if(filePathExtension.length > 1){
			extension = filePathExtension[filePathExtension.length - 1];
			Log.d(LOG_TAG, "sdbUpdateInstallTempFile: file extension = " + extension + "\n");

			if(extension.equals("tpk") || extension.equals("wgt") || extension.equals("rpm")) {
				installShellCommand = "shell:/usr/bin/pkgcmd -i -t " + extension + " -p \"" + destinationPath + "\" -q";
			} else{
				Log.d(LOG_TAG, "sdbUpdateInstallTempFile: invalid extension " + extension + ". Can only install tpk, wgt, or rpm files\n");
				return false;
			}
		}
		else
			installShellCommand = "shell:/usr/bin/pkgcmd -i -t tpk -p \"" + destinationPath + "\" -q";
		try {
			Log.d(LOG_TAG, "sdbUpdateInstallTempFile: cmd = " + installShellCommand + "\n");
			stream = adb.open(installShellCommand);

		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
			return false;
		}
		boolean successfulInstallation = false;
		try {
			byte[] msgBytes;
			String msg;
			while((msgBytes = stream.read()) != null){
				msg = new String(msgBytes);
				Log.d(LOG_TAG, "sdbUpdateInstallTempFile: " + msg);
				if(msg.contains("val[ok]")){
					successfulInstallation = true;
				}
			}
		} catch (IOException e) {
			Log.d(LOG_TAG, "sdbUpdateInstallTempFile: sdb stream closed\n");
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			try {
				stream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return successfulInstallation;
	}

	/**
	 * Sends the remote device a request to remove the app file from temporary directory, and
	 * processes the remote device's response.
	 * @param adb				the AdbConnection representing the connection between this device
	 *              			and the remote device
	 * @param destinationPath	the String file path of the file to delete from the remote device. Includes the
	 *                          name of the file (such as /path/to/temp/directory/filename.tpk)
	 * @return					<code>true</code> if the installation was successful; <code>false</code> otherwise.
	 * @since       			1.0
	 */
	private boolean sdbUpdateRemoveTempFile(AdbConnection adb, String destinationPath){
		AdbStream stream;
		try {
			Log.d(LOG_TAG, "sdbUpdateRemoveTempFile: cmd = " + "shell:/bin/rm -f \"" + destinationPath+ "\"\n");
			stream = adb.open("shell:/bin/rm -f \"" + destinationPath + "\"");
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
			return false;
		}
		boolean successfulDeletion = true;
		try {
			byte[] msgBytes;
			String msg;
//			msgBytes = stream.read();
//			msg = new String(msgBytes);
//			Log.d(LOG_TAG, msg);

			while((msgBytes = stream.read()) != null){
				msg = new String(msgBytes);
				Log.d(LOG_TAG, "sdbUpdateRemoveTempFile: " + msg);
				if(msg.contains("cannot remove") || msg.contains("Permission denied")){
					successfulDeletion = false;
				}
			}

		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (IOException e) {
			Log.d(LOG_TAG, "sdbUpdateRemoveTempFile: stream closed\n");
		}finally{
			try {
				stream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return successfulDeletion;
	}

	/**
	 * Initalizes the file syncing protocol with the remote device, tries to sync the source file to
	 * the remote destination, and then finalizes the syncing process. Upon any issues, the function
	 * tries to clean up and close the created AdbStream.
	 * @param sdbConn			the AdbConnection representing the connection between this device
	 *              			and the remote device
	 * @param sourceFile		the local file to be sent to the remote device
	 * @param destinationPath	the String file path of the file to write to on the remote device. Includes the
	 *                          name of the file (such as /path/to/temp/directory/filename.tpk)
	 * @return					<code>true</code> if the file sync was successful; <code>false</code> otherwise.
	 * @since       			1.0
	 */
	public int do_sync_copy(AdbConnection sdbConn, File sourceFile, String destinationPath) {
		Log.d(LOG_TAG, "do_sync_copy: starting\n");
		AdbStream destinationStream = null;
		String fullDestinationPath = destinationPath;
		boolean sourceDir, destinationDir;
		int filesSent = 0;
		int filesSkipped = 0;
		initialize_local(sourceFile.getPath());
		try {
			if ((destinationStream = initialize_remote(sdbConn, destinationPath)) == null) {
				Log.e(LOG_TAG, "do_sync_copy: remote initialization failed\n");
				throw new Exception();
			}
			this.syncPayload = new byte[MAX_PAYLOAD];
			if (stat_local(sourceFile) < 0 || (sourceDir = is_directory_local(sourceFile))) {
				Log.e(LOG_TAG, "do_sync_copy: local stat failed\n");
				throw new Exception();//Need to clean up opened files here!!!
			}

			destinationDir = false;
			if (!sourceDir) {
				if (destinationDir) {
					fullDestinationPath = destinationPath.concat("/" + sourceFile.getName());
				}
				if (file_copy(sourceFile, destinationStream, fullDestinationPath))
					filesSent++;
				else {
					Log.e(LOG_TAG, "do_sync_copy: file_copy failed\n");
					throw new Exception(); //Need to clean up opened files here!!!
				}
			} else {
				File[] files = sourceFile.listFiles();
				for (File file : files) {
					fullDestinationPath = destinationPath.concat("/" + file.getName());
					if (file_copy(file, destinationStream, fullDestinationPath))
						filesSent++;
					else
						filesSkipped++;
				}
			}
			finalize_local();
			finalize_remote(destinationStream);
			Log.d(LOG_TAG, "do_sync_copy: "+ filesSent + " files sent, " + filesSkipped + " files skipped\n");
			return filesSent;

		} catch (Exception e) {
			Log.e(LOG_TAG, "do_sync_copy: exited abnormally, cleaning up stream\n");
		} finally {
			try {
				destinationStream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}

		}
		return -1;
	}

	/**
	 * Opens the local source file and the remote file on the connected device, and subsequently
	 * syncs the source file to the remote file's path in the temporary directory in 2KB increments.
	 * Can transfer up to 256KB of data in total during one function call. If the file sync fails
	 * before all of the data has been sent (such as having an issue reading the source file),
	 * the function tries to properly close the sync stream with the remote device.
	 * @param destinationStream		the AdbStream representing the open data stream between this device
	 *              				and the remote device
	 * @param sourceFile			the local file to be sent to the remote device
	 * @param destinationPath		the String file path of the file to write to on the remote device. Includes the
	 *                         		name of the file (such as /path/to/temp/directory/filename.tpk)
	 * @return						<code>true</code> if the file sync was successful; <code>false</code> otherwise.
	 * @since       				1.0
	 */
	public boolean file_copy(File sourceFile, AdbStream destinationStream, String destinationPath){
		Log.d(LOG_TAG, "file_copy: is_closed = " + destinationStream.isClosed() + "\n");

		syncPayloadOffset = 0;
		amountRead = 0;
		totalWritten = 0;
		toWrite = sourceFile.length();
		FileInputStream sourceInputStream = readopen_local(sourceFile);
		if(sourceInputStream == null){
			Log.d(LOG_TAG, "file_copy: opening local file failed\n");
			return false;
		}
		Log.d(LOG_TAG,"file_copy: total bytes expected to be sent = " + toWrite + "\n");
		writeopen_remote(destinationPath);
		int previousPayloadOffset = 0;
		boolean wroteDONE = false;
		try {
			while(true) {
				int ret = readfile_local(sourceInputStream);
				if (ret == 0) {
					break;
				} else if (ret == 1) {
					wroteDONE = writefile_remote();
				} else
					throw new Exception();
				try {
					byte[] syncMsgTransport = new byte[this.syncPayloadOffset - previousPayloadOffset];
					System.arraycopy(this.syncPayload, previousPayloadOffset, syncMsgTransport, 0, this.syncPayloadOffset - previousPayloadOffset);
					destinationStream.write(syncMsgTransport);
					previousPayloadOffset = this.syncPayloadOffset;
				} catch (IOException | InterruptedException e) {
					Log.d(LOG_TAG, "file_copy: failed to write data payload\n");
					e.printStackTrace();
				}
			}
		} catch (Exception e) {
			Log.d(LOG_TAG, "file_copy: file reading has failed, attempting to send DONE msg\n");
			if(!wroteDONE)
				appendDONEMsgToSync();
			byte[] syncMsgTransport = new byte[this.syncPayloadOffset - previousPayloadOffset];
			System.arraycopy(this.syncPayload, previousPayloadOffset, syncMsgTransport, 0, this.syncPayloadOffset - previousPayloadOffset);

			try {
				destinationStream.write(syncMsgTransport);
				writeclose_remote(destinationStream, destinationPath, sourceFile);

			} catch (IOException | InterruptedException ex) {
				ex.printStackTrace();
			}
			return false;
		}

		if(readclose_local(sourceInputStream) != 0 || writeclose_remote(destinationStream, destinationPath, sourceFile) != 0)
			return false;
		Log.d(LOG_TAG, "file_copy: sending file successful, sent " + totalWritten + " bytes\n");
		return true;
	}

	/**
	 * Generates a sync protocol message comprised of a 8-byte packet (sync request) and an accompanying
	 * payload.
	 * @param id		a byte array representing the id for a sync protocol command, used for the first
	 *                  four bytes of the sync request
	 * @param size		a multi-purpose Little-Endian integer used for the final four bytes of the sync
	 *                  request. Can either represent the size of the payload or the last modified
	 *                  time for the sync'd file, depending on the circumstance.
	 * @param payload	a byte array representing a payload to be delivered to the remote device. Can
	 *                  be <code>null</code>.
	 * @return			the byte array containing the entire sync protocol message.
	 * @since       	1.0
	 */
	public static byte[] generateSyncMessage(byte[] id, int size, byte[] payload) {
		ByteBuffer message;
		if (payload != null) {
			message = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
		} else {
			message = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		}

		message.put(id);
		message.putInt(size);
		if (payload != null) {
			message.put(payload);
		}
		return message.array();
	}

	static final class syncMessage {
		public byte[] id;
		public int size;
		public byte[] payload;

		syncMessage() {
		}

		public static SdbUpdateService.syncMessage parseSyncMessage(byte[] message) throws IOException {
			Log.d(LOG_TAG, new String(message));
			SdbUpdateService.syncMessage msg = new SdbUpdateService.syncMessage();
			msg.id = new byte[4];
			ByteBuffer packet = ByteBuffer.allocate(message.length).order(ByteOrder.LITTLE_ENDIAN);
			System.arraycopy(message, 0, packet.array(), 0, message.length);
			//packet.put(message);

			packet.get(msg.id);
			msg.size = packet.getInt();
			if (msg.size != 0) {
				msg.payload = new byte[msg.size];
				packet.get(msg.payload, 0, msg.size);
			}

			return msg;
		}
	}


	public void initialize_local(String path){
		Log.d(LOG_TAG, "initialize local file '" + path +"'\n");
	}

	public AdbStream initialize_remote(AdbConnection sdbConn, String path){
		Log.d(LOG_TAG, "initialize remote file '" + path + "'\n");
		try {
			return sdbConn.open("sync:");
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
		return null;
	}

	public void finalize_local(){
		Log.d(LOG_TAG, "finalize local file\n");
	}

	public void finalize_remote(AdbStream sdbStream){
		Log.d(LOG_TAG, "finalize remote file\n");
		byte[] finalizeMessage = generateSyncMessage(SYNC_QUIT, 0, null);
		try {
			sdbStream.write(finalizeMessage);
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	public int stat_local(File sourceFile){
		if(!sourceFile.exists()){
			Log.e(LOG_TAG, "stat_local: Source file does not exist\n");
			return -1;
		}
		else
			Log.d(LOG_TAG, "stat_local: file '" + sourceFile.getPath() +"'\n");
		sourceFile.setReadable(true);
		sourceFile.setExecutable(true);
		sourceFile.setWritable(true,true);
		return 0;
	}

	//Currently unworking code, remote device responds to the STAT msg with a stat structure corresponding to the
	public boolean stat_remote(AdbStream sdbStream, String path){
		Log.d(LOG_TAG, "stat_remote: file '" + path +"'\n");
		byte[] statMessage = generateSyncMessage(SYNC_STAT, path.length(), path.getBytes());
		try {
			Log.d(LOG_TAG, "stat_remote: sent stat msg:'" + new String(statMessage) +"'\n");
			sdbStream.write(statMessage);
			byte[] statResult = sdbStream.read();
			Log.d(LOG_TAG, "stat_remote: received stat msg: '" + new String(statResult) +"'\n");
			syncMessage msg = syncMessage.parseSyncMessage(statResult);
			if(msg.id != SYNC_STAT){
				//Only Fill in if needed to determine whether the destination is a directory
			}
			return false;
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
		return true;
	}

	public boolean is_directory_local(File sourceFile){
		return sourceFile.isDirectory();
	}

	public FileInputStream readopen_local(File sourcePath){
		Log.d(LOG_TAG, "readopen_local: file '" + sourcePath.getPath() +"'\n");
		try {
			return new FileInputStream(sourcePath);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		return null;

	}
	public void writeopen_remote(String destinationPath){
		Log.d(LOG_TAG, "writeopen_remote: file '" + destinationPath +"'\n");
		String sendString = (destinationPath + "," + 33261 + "\0"); //Using 755 mode on the remote file (33261)
		byte[] sendMessage = generateSyncMessage(SYNC_SEND, sendString.length(), sendString.getBytes());
		System.arraycopy(sendMessage, 0, this.syncPayload, this.syncPayloadOffset, sendMessage.length);
		this.syncPayloadOffset += sendMessage.length;

	}

	public int readclose_local(FileInputStream sourceInputStream){
		Log.d(LOG_TAG, "readclose_local: file\n");
		try {
			sourceInputStream.close();
			return 0;
		} catch (IOException e) {
			e.printStackTrace();

		}
		return -1;
	}
	public int writeclose_remote(AdbStream sdbStream, String destinationPath, File sourceFile){
		Log.d(LOG_TAG, "writeclose_remote: file '" + destinationPath +"'\n");
		try {
			byte[] closeResult = sdbStream.read();
			syncMessage msg = syncMessage.parseSyncMessage(closeResult);
			if(!Arrays.equals(msg.id, SYNC_OKAY)){
				Log.e(LOG_TAG, "writeclose_remote: error " + new String(msg.payload) + "\n");
				return -1;
			}
			return 0;
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
		return -1;

	}

	// 0: finish normally.
	//-1: fail
	// 1: write and continue load
	public int readfile_local(FileInputStream sourceInputStream){
		Log.d(LOG_TAG, "readfile_local: starting\n");
		int amountRead;
		this.readPayload = new byte[SYNC_DATA_MAX];
		try {
			amountRead = sourceInputStream.read(this.readPayload, 0, SYNC_DATA_MAX);
		} catch (IOException e) {
			e.printStackTrace();
			Log.d(LOG_TAG, "readfile_local: starting\n");
			this.readPayload = null;
			return -1;
		}

		if(amountRead == -1) {
			this.amountRead = 0;
			return 0;
		}
		else {
			this.amountRead = amountRead;
			return 1;
		}
	}
	public boolean writefile_remote(){
		Log.d(LOG_TAG, "writefile local: writing " + this.amountRead +" bytes, " +this.totalWritten + " bytes already written\n");
		int amountRead = this.amountRead;
		byte[] dataMessage = generateSyncMessage(SYNC_DATA, amountRead, this.readPayload);
		System.arraycopy(dataMessage, 0, this.syncPayload, this.syncPayloadOffset, 8 + amountRead);
		this.syncPayloadOffset += (8 + amountRead);
		this.totalWritten += amountRead;
		if (amountRead < SYNC_DATA_MAX || this.totalWritten == this.toWrite) {
			appendDONEMsgToSync();
			return true;
		}
		return false;
	}

	public void appendDONEMsgToSync(){
		byte[] closeMessage = generateSyncMessage(SYNC_DONE, 0, null); //Can be changed later to properly be modified time
		System.arraycopy(closeMessage, 0, this.syncPayload, this.syncPayloadOffset, closeMessage.length);
		this.syncPayloadOffset += closeMessage.length;
	}

}

import java.net.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.io.*;
import java.util.Scanner;

public class Coordinator {
	private int port;
	private int threshold;
	private long curTime = 0;
	private ServerSocket server = null;
	private Socket participant = null;
	private DataInputStream in = null;
	private DataOutputStream out = null;
	private HashMap<Integer, ParticipantThread> memberList = new HashMap<Integer, ParticipantThread>();
	private static int MAX_SIZE = 10;

	public Coordinator(File config) {
		// Parse through config file to get proper settings
		try {
			Scanner sc = new Scanner(config);
			port = Integer.parseInt(sc.nextLine());
			threshold = Integer.parseInt(sc.nextLine());
			sc.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

		try {
			server = new ServerSocket(port);
			server.setReuseAddress(true);

			// Establishes connection to client, receives command, closes connection, then
			// completes command
			while (true) {
				participant = server.accept();

				in = new DataInputStream(new BufferedInputStream(participant.getInputStream()));
				out = new DataOutputStream(participant.getOutputStream());

				String command = in.readUTF();

				if (command.equals("register")) {
					int partID = in.readInt();
					int participantPort = in.readInt();
					String ipAdress = in.readUTF().substring(10);
					System.out.println("Client " + partID + " connected from: " + ipAdress + " on port " + participantPort);
					
					acknowledge();
					register(partID, participantPort, ipAdress);
				} else if (command.equals("deregister")) {
					int partID = in.readInt();

					acknowledge();
					deregister(partID);
				} else if (command.equals("disconnect")) {
					int partID = in.readInt();

					acknowledge();
					disconnect(partID);
				} else if (command.equals("reconnect")) {
					int partID = in.readInt();
					int participantPort = in.readInt();
					String ipAdress = in.readUTF().substring(10);

					acknowledge();
					reconnect(partID, participantPort, ipAdress);
				} else if (command.equals("msend")) {
					int partID = in.readInt();
					String msg = in.readUTF();

					acknowledge();
					msend(partID, msg);
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// Sends acknowledgement that a command is received
	public void acknowledge() {
		try {
			if (memberList.size() >= MAX_SIZE)
				out.writeUTF("Unable to accept more clients at the moment."); // Checks if a client is able to register
			else
				out.writeUTF("Command acknowledged");
			in.close();
			out.close();
			participant.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// Handles register command
	public void register(int partID, int participantPort, String ipAdress) {
		if (memberList.size() < MAX_SIZE) {
			ParticipantThread thread = new ParticipantThread(partID, participantPort, ipAdress);
			memberList.put(partID, thread);
			thread.start();
		}
	}

	// Handles deregister command
	public void deregister(int partID) {
		ParticipantThread temp = memberList.get(partID);
		synchronized (temp) {
			temp.deregistered = true;
			temp.notify();
		}
		memberList.remove(partID);
		System.out.println("Client " + partID + " deregistered on port " + temp.port);
		temp = null;
	}

	// Handles disconnect command
	public void disconnect(int partID) {
		ParticipantThread temp = memberList.get(partID);
		temp.offline = true;
		temp.disconTime = System.currentTimeMillis() / 1000;
	}

	// Handles reconnect command
	public void reconnect(int partID, int participantPort, String ipAdress) {
		ParticipantThread temp = memberList.get(partID);
		synchronized (temp) {
			temp.port = participantPort;
			temp.ipAdress = ipAdress;
			temp.reconTime = System.currentTimeMillis() / 1000;
			temp.reconnect = true;
			temp.notify();
		}
	}

	// Handles multi-cast send
	public void msend(int partID, String msg) {
		// Create iterator to send message to each registered client
		Iterator hmIterator = memberList.entrySet().iterator();
		ParticipantThread temp = null;
		curTime = System.currentTimeMillis() / 1000;

		while (hmIterator.hasNext()) {
			Map.Entry element = (Map.Entry) hmIterator.next();
			temp = (ParticipantThread) element.getValue();

			synchronized (temp) {
				// Sends message to clients excluding the caller
				temp.curTime = this.curTime;
				temp.receiving = true;
				temp.msg = msg;
				temp.notify();
			}
		}
	}

	// Each thread corresponds to a registered client. Handles commands that require
	// connecting to the client listener thread.
	class ParticipantThread extends Thread {
		private int partID;
		int port;
		long disconTime;
		long reconTime;
		long curTime; // Current time when a message sent while client is offline
		String ipAdress = null;
		String msg = null; // Message received from msend
		boolean offline = false;
		boolean deregistered = false;
		boolean receiving = false;
		boolean reconnect = false;

		private Socket participant = null;
		private DataOutputStream out = null;
		private File storage = null;

		ParticipantThread(int partID, int port, String ipAdress) {
			this.partID = partID;
			this.port = port;
			this.ipAdress = ipAdress;

			// Create storage file for when client is offline
			storage = new File("storage" + partID + ".txt");
			if (!storage.exists()) {
				try {
					storage.createNewFile();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		public void run() {
			while (true) {
				// Wait for a deregister, reconnect, or msend command
				synchronized (this) {
					while (!deregistered && !receiving && !reconnect)
						try {
							wait();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
				}

				if (deregistered) {
					close();
				}

				if (reconnect) {
					reconnect();
				}

				if (receiving) {
					if (offline) {
						// write msg to file
						offlineRcv();
					} else {
						// create socket and write to participant
						onlineRcv();
					}
				}
			}
		}

		// Performs overhead to close all streams and sockets, and remove the storage
		// file for the offline messages when deregister is called
		public void close() {
			try {
				if (out != null) {
					out.close();
					out = null;
				}
				if (participant != null) {
					participant.close();
					participant = null;
				}

				if (storage != null) {
					storage.delete();
					storage = null;
				}

				interrupt();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		// Writes message from a multicast send and the time to the storage file for
		// this thread while client is offline
		public void offlineRcv() {
			try {
				msg = msg + "\t" + curTime + "\n";
				FileOutputStream fos;
				fos = new FileOutputStream(storage, true);
				BufferedOutputStream bos = new BufferedOutputStream(fos);

				bos.write(msg.getBytes());
				bos.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			receiving = false;
		}

		// Sends message to corresponding client that is online
		public void onlineRcv() {
			try {
				participant = new Socket(ipAdress, port);

				out = new DataOutputStream(participant.getOutputStream());

				out.writeUTF(msg);

				out.close();
				participant.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			receiving = false;
		}

		// Establishes connection with corresponding client and sends messages from
		// storage file within threshold
		public void reconnect() {
			try {
				String line;
				String[] msg = null;
				String toSend = "";
				long tempTime = (reconTime - disconTime);

				offline = false;
				participant = new Socket(ipAdress, port);
				out = new DataOutputStream(participant.getOutputStream());

				Scanner sc = new Scanner(storage);

				if (sc.hasNext()) {
					line = sc.nextLine();
					msg = line.split("\t");
					toSend = "";
					tempTime = (reconTime - disconTime);

					// Send all messages in storage file if time since disconnect is below threshold
					if (tempTime <= threshold) {
						while (sc.hasNext()) {
						    toSend += (msg[0] + "\t");
						    line = sc.nextLine();
						    msg = line.split("\t");
						}
						toSend += (msg[0] + "\t");
						out.writeUTF(toSend);
					}
					
					// Send messages in file from (reconnect time - threshold) to the reconnect time
					else {
					    tempTime = reconTime - Long.parseLong(msg[1]);

						// Skip messages past threshold
						while (tempTime > threshold && sc.hasNext()) {
						    line = sc.nextLine();
							msg = line.split("\t");
							tempTime = reconTime - Long.parseLong(msg[1]);

						}

						// All messages in file skipped
						if (tempTime > threshold && !sc.hasNext())
						    out.writeUTF("No messages received since last online.");
						// Send messages from current line to the end of the file
						else {
							while (sc.hasNext()) {
							    toSend += (msg[0] + "\t");
							    line = sc.nextLine();
							    msg = line.split("\t");
							}
							toSend += (msg[0] + "\t");
							out.writeUTF(toSend);
						}
					}
				} else {
					out.writeUTF("No messages received since last online.");
				}

				sc.close();
			} catch (IOException e) {
				e.printStackTrace();
			}

			storage.delete();
			storage = new File("storage" + partID + ".txt");
			try {
				storage.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
			reconnect = false;
		}
	}

	public static void main(String[] args) {
		File config = new File(System.getProperty("user.dir") + "/" + args[0]);

		Coordinator coordinator = new Coordinator(config);
	}
}

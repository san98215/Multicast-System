import java.net.*;
import java.util.HashMap;
import java.io.*;
import java.util.Scanner;

public class Participant {
	private int partID;
	private int port;
	private String storPath = null;
	private String ipAdress = null;
    private String host = null;
	private File storage = null;

	public Participant(File config) {
		// Parse through config file to get proper settings
		try {
			Scanner sc = new Scanner(config);
			partID = Integer.parseInt(sc.nextLine());
			storPath = sc.nextLine();
			String[] msg = sc.nextLine().split(" ");
			ipAdress = msg[0];
			port = Integer.parseInt(msg[1]);
			sc.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

                        try {
                                host = InetAddress.getLocalHost().toString();
                        } catch (UnknownHostException e1) {
                                e1.printStackTrace();
                        }

		
		storage = new File(System.getProperty("user.dir") + "/" + storPath);
		
		if (!storage.exists()) {
			try {
				storage.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		InputThread input = new InputThread(ipAdress);
		input.start();
	}

	// Thread for handling input from user
	class InputThread extends Thread {
		private int listenPort;
		private String listenIP = "127.0.0.1";
		private boolean connected = false;
		private boolean registered = false;
		private BufferedReader input = null;
		private DataOutputStream out = null;
		private DataInputStream in = null;
		private Socket socket = null;
		private ReceiverThread thread = null;

		public InputThread(String ip) {
      listenIP = ip;
			input = new BufferedReader(new InputStreamReader(System.in));
		}

		public void run() {
			// Handles client commands until they quit
			while (true) {
				try {
					System.out.print("> ");
					String[] command = input.readLine().split(" ",2);

					// Prevents register and reconnect commands if already connected and registered
					if (registered && connected) {
						if (command[0].equalsIgnoreCase("register")) {
							System.out.println("Already registed, please use different command");
						} else if (command[0].equals("deregister")) {
							deregister();
						} else if (command[0].equals("disconnect")) {
							disconnect();
						} else if (command[0].equals("reconnect")) {
							System.out.println("Already connected, please use different command");
						} else if (command[0].equals("msend")) {
							msend(command[1]);
						}
					}
					// Prevents commands except reconnect and deregister when disconnected
					else if (registered && !connected) {
						if (command[0].equals("reconnect")) {
							reconnect(command[1]);
						} else if (command[0].equals("deregister")) {
							deregister();
						} else {
							System.out.println("Cannot input other commands until reconnected.");
						}
					}
					// Onlly allows register command when not registered
					else {
						if (command[0].equalsIgnoreCase("register")) {
							register(command[1]);
						} else {
							System.out.println("Cannot input other commands until registered.");
						}
					}

					if (in != null)
						in.close();
					if (out != null)
						out.close();
					if (socket != null)
						socket.close();
				} catch (IOException e) {
					System.out.println("Error is from run");
					e.printStackTrace();
				}
			}
		}

		// Establishes connection to coordinator for each command
		public void connect() {
			try {
				socket = new Socket(ipAdress, port);
				in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
				out = new DataOutputStream(socket.getOutputStream());
			} catch (UnknownHostException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		// Handles register command
		public void register(String port) {
			connected = true;
			registered = true;
			listenPort = Integer.parseInt(port);

			// Starts receiver thread before connecting to coordinator
			thread = new ReceiverThread(listenPort, false);
			thread.start();

			connect();

			try {
				out.writeUTF("register");

				out.writeInt(partID);
				out.writeInt(listenPort);
				out.writeUTF(host);

				System.out.println(in.readUTF());
				System.out.println("Registered on port " + listenPort);
			} catch (IOException e) {
				System.out.println("Error is from Register");
				e.printStackTrace();
			}
		}

		// Handles deregister command
		public void deregister() {
			connect();

			try {
				out.writeUTF("deregister");
				out.writeInt(partID);
				thread.listen.close(); // Kills listener thread by force closing its server socket

				System.out.println(in.readUTF());
				System.out.println("Port " + thread.port + " relinquished");
			} catch (IOException e) {
				e.printStackTrace();
			}

			connected = false;
			registered = false;
		}

		// Handles disconnect command
		public void disconnect() {
			connect();

			try {
				out.writeUTF("disconnect");
				out.writeInt(partID);
				thread.listen.close(); // Kills listener thread by force closing its server socket

				System.out.println(in.readUTF());
				System.out.println("Port " + thread.port + " relinquished");
			} catch (IOException e) {
				e.printStackTrace();
			}

			connected = false;
		}

		// Handles reconnect command
		public void reconnect(String port) {
			listenPort = Integer.parseInt(port);
			connected = true;

			// Creates new listener thread before connecting
			thread = new ReceiverThread(listenPort, true);
			thread.start();

			connect();

			try {
				out.writeUTF("reconnect");
				out.writeInt(partID);
				out.writeInt(listenPort);
				out.writeUTF(host);

				System.out.println(in.readUTF());
				sleep(20); // Waits so that syntax is preserved
			} catch (IOException | InterruptedException e) {
				e.printStackTrace();
			}
		}

		// Handles msend command
		public void msend(String msg) {
			connect();

			try {
				out.writeUTF("msend");
				out.writeInt(partID);
				out.writeUTF(msg);

				System.out.println(in.readUTF());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	// Thread that listens for messages sent by other registered client's. Handles
	// msend and reconnect command
	class ReceiverThread extends Thread {
		int port;
		boolean reconnect = false;
		ServerSocket listen = null;
		private Socket multicast = null;
		private DataOutputStream out = null;
		private DataInputStream in = null;
		private String msg = null;

		// Creates server socket on specified port and sets flag for reconnecting
		public ReceiverThread(int port, boolean reconnect) {
			this.port = port;
			this.reconnect = reconnect;

			try {
				listen = new ServerSocket(port);
			} catch (IOException e) {
				System.out.println("RecieverThread Error: Port is already in use.");
			}
		}

		public void run() {
			try {
				while (true) {
					// Waits for a multi-cast send, reconnect, or handles a forcibly closed server
					// socket; only establishes connection for reconnect and msend commands
					multicast = listen.accept();

					in = new DataInputStream(new BufferedInputStream(multicast.getInputStream()));
					out = new DataOutputStream(multicast.getOutputStream());

					msg = in.readUTF(); // Received from coordinator corresponding to msend or reconnect command
					
					if (reconnect) {
						if (!msg.equals("No messages received since last online.")) {
							String[] msgs = msg.split("\t"); // Msg received from coordinator contains stored msgs
															// separated by a space; threshold handled by coordinator
							System.out.println("Messages receive since last online: ");

							for (int i = 0; i < msgs.length; i++) {
								System.out.println(msgs[i]);
								updateLog(msgs[i]);
							}
						} else {
							System.out.println(msg);
						}
						reconnect = false;
					} else {
						System.out.println("Message received: " + msg); // Msg received while online
						System.out.print("> ");
						updateLog(msg);
					}
					
					in.close();
					out.close();
					multicast.close();
				}
			} catch (IOException e1) {
				// If deregister or disconnect command, call close command to handle overhead
				close();
			}
		}

		// Closes all streams, sockets, and kills the thread. Handles forcibly closed
		// server socket from deregister and disconnect commands
		public void close() {
			try {
				if (out != null) {
					out.close();
					out = null;
				}
				if (in != null) {
					in.close();
					in = null;
				}
				if (multicast != null) {
					multicast.close();
					multicast = null;
				}

				interrupt();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		public void updateLog(String msg) {
			FileOutputStream fos;
			try {
				msg += "\n";
				fos = new FileOutputStream(storage, true);
				BufferedOutputStream bos = new BufferedOutputStream(fos);
				bos.write(msg.getBytes());
				bos.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public static void main(String[] args) {
		File config = new File(System.getProperty("user.dir") + "/" + args[0]);

		Participant participant = new Participant(config);
	}
}

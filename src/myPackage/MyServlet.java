package myPackage;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * Servlet implementation class MyServlet
 */
@WebServlet("/MyServlet")
public class MyServlet extends HttpServlet 
{
	private static final long serialVersionUID = 1L;
	private static AtomicInteger sessionID = new AtomicInteger();
	private String cookieName = "CS5300PROJ1SESSION";
	public static int SESSION_TIMEOUT_SECS = 15;
	public static int DISCARD_TIME_DELTA = 1;
	private final String location = "0";
	private static AtomicInteger callId = new AtomicInteger();
	private final byte SESSIONREAD = 0; // operation code
	private final byte SESSIONWRITE = 1; // operation code
	private final int PORT = 5300;
	
	// Session State table, aka hash map used to store session information
	// K: sessionID, V: SessionState
	private ConcurrentHashMap<Integer, SessionState> map = new ConcurrentHashMap<Integer, SessionState>();
	
	// spawns the garbage collector daemon thread
	public void garbageCollector()
	{
		Thread daemonThread = new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				while(true)
				{
					List<Integer> list = new ArrayList<Integer>(map.keySet());
					System.out.println("gc:" + list.size() + " session(s)");
					for (int id : list)
					{
						System.out.println("gc:" + id + " has " + (map.get(id).timeout - (int)(System.currentTimeMillis()/1000)) + " seconds left");
						if (map.get(id).timeout < (int)(System.currentTimeMillis()/1000))
						{
							SessionState s = map.remove(id);
							System.out.println("gc:removed <" + id + ", " + s.sessionID.serverId + ">");
						}
					}
					try {
						Thread.sleep(10*1000);		// 10 seconds
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();		// restore interrupted status
					}
				}
			}
		});
		daemonThread.setDaemon(true);	// making this thread daemon
	    daemonThread.start();
	}
	
    /**
     * @see HttpServlet#HttpServlet()
     */
    public MyServlet() {
        super();
        
        garbageCollector();   
    }

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException 
	{	
		Cookie[] cookies = request.getCookies();
		Cookie myCookie = findCookie(cookies, cookieName);		// current client's cookie
		
		String[] sess = new String[2];
		int ver;
		long timeout;
		String[] loc = new String[2];
		String message = "";
		String value = "";
		Cookie c;
		InetAddress local_ip = getIP();
		
		// check if this is client's first request. if so, construct new cookie and new SessionState
		if (myCookie == null)
		{
			int sid = sessionID.getAndAdd(1);
			sess[0] = String.valueOf(sid);
			sess[1] = local_ip.getHostAddress();
			ver = 1;
			message = "Hello, User!";
			long curTime = System.currentTimeMillis() / 1000;
			timeout = curTime + SESSION_TIMEOUT_SECS;
			loc[0] = getIP().getHostAddress();
			loc[1] = location;				// TODO: choose random server from local server's View
			value = concatValue(sess, ver, loc);
			
			// store new info to map
			SessionTuple sessTup = new SessionTuple(sid, loc[0]);
			SessionState state = new SessionState(sessTup, ver, message, timeout);
			map.put(Integer.valueOf(sess[0]), state);
			
			// construct cookie
			c = new Cookie(cookieName, value);
			c.setMaxAge(SESSION_TIMEOUT_SECS);	// set timeout!!!
			c.setComment(message);
			
			// send cookie back to client
			response.addCookie(c);
			
			// forward information to jsp page
			request.setAttribute("myVal", c.getValue());
			request.setAttribute("myMessage", c.getComment());
	        request.getRequestDispatcher("/myServlet.jsp").forward(request, response);

		}
		// otherwise, check if SessionState is stored in local server
		// i.e. check server_primary or server_backup == server_local
		else 
		{
			String[] locs = getLoc(myCookie);
			boolean flag = false;
			for (String s : locs)
			{
				// if the SessionState is stored in local server, simply reconstruct cookie and update it
				if (!flag && (local_ip.getHostAddress()).equals(s))
				{
					int sid = getID(myCookie);
					sess[0] = String.valueOf(sid);
					sess[1] = getIP(myCookie);
					SessionState ss = map.get(Integer.valueOf(sess[0]));
					if (ss == null)
						throw new ServletException("Current session has timed out.");
					ver = ss.version;
					ver++;
					message = ss.message;
					long curTime = System.currentTimeMillis() / 1000;
					timeout = curTime + SESSION_TIMEOUT_SECS;
					loc[0] = getIP().getHostAddress();					
					// choose a backup server
					// TODO: call SessionWrite() to backup server and wait for successful response
					// if (fail) { loc[1] = null; }
					// else { loc[1] = // TODO: backup server - choose at random from local server's view }
					loc[1] = getLoc(myCookie)[1];
					value = concatValue(sess, ver, loc);
					
					// store updated info to map (choose primary server)
					SessionTuple sessTup = new SessionTuple(sid, loc[0]);
					SessionState state = new SessionState(sessTup, ver, message, timeout);
					map.replace(Integer.valueOf(sess[0]), state);
					
					// kill current cookie
					myCookie.setMaxAge(0);
					myCookie.setValue(null);
					// reconstruct cookie
					c = new Cookie(cookieName, value);
					c.setMaxAge(SESSION_TIMEOUT_SECS);		// set timeout!!!
					c.setComment(message);
					
					// send cookie back to client
					response.addCookie(c);
					
					// forward information to jsp page
					request.setAttribute("myVal", c.getValue());
					request.setAttribute("myMessage", c.getComment());
			        request.getRequestDispatcher("/myServlet.jsp").forward(request, response);

					flag = true;
				}
			}
			// if the SessionState is stored in another server, execute RPC calls
			if (!flag)
			{
				// TODO: SessionRead stuff here
			}
		}
		
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException 
	{
		Cookie[] cookies = request.getCookies();
		Cookie myCookie = findCookie(cookies, cookieName);		// current client's cookie

		// in the case of time-out, redirect to time-out page
		if (myCookie == null)
		{
			java.util.Set<Integer> blah2 = map.keySet();
			request.getRequestDispatcher("/timeout.jsp").forward(request, response);
			return;
		}
        
		String action = request.getParameter("action");
		String message = null;
		
		// update cookie parameters
		String[] sess = new String[2];
		int ver;
		long timeout;
		String[] loc = new String[2];
		String msg = "";
		String value = "";
		Cookie c;
		InetAddress local_ip = getIP();
		
		// check if SessionState is stored in local server
		// i.e. check server_primary or server_backup == server_local
		String[] locs = getLoc(myCookie);
		boolean flag = false;
		for (String s : locs)
		{
			// if the SessionState is stored in local server, simply reconstruct cookie and update it
			if (!flag && (local_ip.getHostAddress()).equals(s))
			{
				// replace and refresh buttons
				if (!action.equals("logout"))
				{
					int sid = getID(myCookie);
					sess[0] = String.valueOf(sid);
					sess[1] = getIP(myCookie);
					SessionState ss = map.get(Integer.valueOf(sess[0]));
					if (ss == null)
						throw new ServletException("Current session has timed out.");
					ver = ss.version;
					ver++;
					if (action.equals("replace"))
					{
						// retrieve message from form
						message = request.getParameter("message");
					}
					else if (action.equals("refresh"))
					{
						// retrieve message from cookie
						message = ss.message;
					}
					msg = message;
					long curTime = System.currentTimeMillis() / 1000;
					timeout = curTime + SESSION_TIMEOUT_SECS;
					loc[0] = getIP().getHostAddress();
					// choose a backup server
					// TODO: call SessionWrite() to backup server and wait for successful response
					// if (fail) { loc[1] = null; }
					// else { loc[1] = // TODO: backup server - choose at random from local server's view }
					loc[1] = loc[0];
					value = concatValue(sess, ver, loc);
					
					// store updated info to map (choose primary server)
					SessionTuple sessTup = new SessionTuple(sid, loc[0]);
					SessionState state = new SessionState(sessTup, ver, msg, timeout);
					map.replace(Integer.valueOf(sess[0]), state);
					
					// kill current cookie
					myCookie.setMaxAge(0);
					myCookie.setValue(null);
					// reconstruct cookie
					c = new Cookie(cookieName, value);
					c.setMaxAge(SESSION_TIMEOUT_SECS);		// set timeout!!!
					c.setComment(msg);
					
					// send cookie back to client
					response.addCookie(c);
					
					// forward information to jsp page
					request.setAttribute("myVal", c.getValue());
					request.setAttribute("myMessage", c.getComment());
			        request.getRequestDispatcher("/myServlet.jsp").forward(request, response);
				}
				// logout button
				else
				{
					// remove session info from map
					map.remove(getID(myCookie));
					
					// kill the cookie
					myCookie.setMaxAge(0);
					myCookie.setValue(null);
					
					// send cookie back to client
					response.addCookie(myCookie);
					
					// forward information to jsp page and display it
			        request.getRequestDispatcher("/logout.jsp").forward(request, response);
				}
				
				flag = true;
			}
		}
		// if the SessionState is stored in another server, execute RPC calls
		if (!flag)
		{
			// TODO: SessionRead stuff here
		}
		
	}

	// search for cookie if it exists already, else return null
	private Cookie findCookie(Cookie[] cookies, String name)
	{
		Cookie myCookie = null;
		
		if (cookies != null)
		{
			for (Cookie c : cookies)
			{
				if (name.equals(c.getName()))
					myCookie = c;
			}
		}
		
		return myCookie;
	}
	
	// returns the session ID of the Cookie c
	private int getID(Cookie c)
	{
		if (c != null)
		{
			String val = c.getValue();
			String[] tokens = val.split("_");
			
			return Integer.valueOf(tokens[0]);
		}
		else 
			return -1;
	}
	// returns the serverID of the Cookie c
	private String getIP(Cookie c)
	{
		if (c != null)
		{
			String val = c.getValue();
			String[] tokens = val.split("_");
			
			return String.valueOf(tokens[1]);
		}
		else 
			return null;
	}
	// returns the locations (primary, backup ips)
	private String[] getLoc(Cookie c)
	{
		String[] ret = new String[2];
		if (c != null)
		{
			String val = c.getValue();
			String[] tokens = val.split("_");
			
			ret[0] = tokens[3];
			ret[1] = tokens[4];
			return ret;
		}
		else 
			return null;
	}
	
	// constructs the value string for session states
	private String concatValue(String[] sess, int ver, String[] loc)
	{
		return sess[0] + "_" + sess[1] + "_" + 
				String.valueOf(ver) + "_" + 
				loc[0] + "_" + loc[1];
	}
	
	// running on local tomcat
	private InetAddress getIP()
	{
		InetAddress addr = null;
		
		try 
		{
			addr = InetAddress.getByName(InetAddress.getLocalHost().getHostAddress());
		} 
		catch (UnknownHostException e) 
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return addr;
	}
	
	// sessionRead, sent by this server to multiple remote servers
	// give an array of addresses and ports from which to read (returns the first result)
	// returns a String containing the message (if null, error was encountered)
	private String sessionRead(
			int sessNum, int serverId , int sessVersionNum, 
			InetAddress[] address) {
		try {
			DatagramSocket rpcSocket = new DatagramSocket();
			int newCallId = callId.getAndAdd(1);

			ByteBuffer bbuf = ByteBuffer.allocate(17); //4 ints + 1 byte
			bbuf.putInt(newCallId);
			bbuf.put(SESSIONREAD);
			bbuf.putInt(sessNum);
			bbuf.putInt(serverId);
			bbuf.putInt(sessVersionNum);
			byte[] outBuf = bbuf.array();
			return sessionReadHelper(newCallId, rpcSocket, outBuf, address, 0);
		} catch (SocketException e) {
			// DatagramSocket could not be opened
			e.printStackTrace();
		}
        return null;
	}
	
	private String sessionReadHelper(int cid, DatagramSocket socket, byte[] outBuf, InetAddress[] address, int index) {
		// failed on all calls
		if (index > address.length) {
			return null;
		}
		DatagramPacket sendPkt = new DatagramPacket(outBuf, outBuf.length, address[index], PORT);
		try {
			socket.send(sendPkt);
			byte[] inBuf = new byte[516]; // 512 + 4 //TODO restrict input to 256 characters
			DatagramPacket recvPkt = new DatagramPacket(inBuf, inBuf.length);
			ByteBuffer bbuf = null;
			int recvCallId = -1;
			do {
				recvPkt.setLength(inBuf.length);
				socket.receive(recvPkt);
				bbuf = ByteBuffer.wrap(inBuf);
				recvCallId = bbuf.getInt();
			} while(recvCallId != cid);
			return new String(inBuf, 4, recvPkt.getLength()-4);
		} catch (IOException e) {
			//send failed or timeout
			e.printStackTrace();
			return sessionReadHelper(cid, socket, outBuf, address, index+1);
		}
	}
	
	// sessionWrite to one remote server
	// returns whether the call was successful
	private boolean sessionWrite(
			int sessNum, int serverId, int sessVersionNum, 
			int discard_time, String msg, InetAddress address) {
        try {
			DatagramSocket rpcSocket = new DatagramSocket();
			int newCallId = callId.getAndAdd(1);

			ByteBuffer bbuf = ByteBuffer.allocate(4*5 + 1 + 2*msg.length()); //5 ints + 1 byte + string
			bbuf.putInt(newCallId);
			bbuf.put(SESSIONWRITE);
			bbuf.putInt(sessNum);
			bbuf.putInt(serverId);
			bbuf.putInt(sessVersionNum);
			bbuf.putInt(discard_time);
			for (byte b : msg.getBytes()) {
				bbuf.put(b);
			}
			byte[] outBuf = bbuf.array();
	        DatagramPacket sendPkt = new DatagramPacket(outBuf, outBuf.length, address, PORT);
            rpcSocket.send(sendPkt);
            byte[] inBuf = new byte[4]; // response contains callId
            DatagramPacket recvPkt = new DatagramPacket(inBuf, inBuf.length);
            int recvCallId = -1;
            do { //loop through responses
                recvPkt.setLength(inBuf.length);
                rpcSocket.receive(recvPkt);
                bbuf = ByteBuffer.wrap(inBuf);
                recvCallId = bbuf.getInt();
            } while(recvCallId != newCallId);
        return true;
		} catch (SocketException e) {
			// DatagramSocket could not be opened
			e.printStackTrace();
        } catch (IOException e) {
            //send failed or timeout
            e.printStackTrace();
        }
		return false;
	}
	
	// to be called in the MyServlet constructor
	private void rpcServer() {
		Thread daemonThread = new Thread(new Runnable() {
			@Override
			public void run()
			{
				try {
	                DatagramSocket rpcSocket = new DatagramSocket(PORT);
					while(true)
					{
						// TODO: RPC SERVER
						byte[] inBuf = new byte[0]; //BYTE LENGTH?!?!
						DatagramPacket recvPkt = new DatagramPacket(inBuf, inBuf.length);
						rpcSocket.receive(recvPkt);
						InetAddress returnAddr = recvPkt.getAddress();
						int returnPort = recvPkt.getPort();
						ByteBuffer bbuf = ByteBuffer.wrap(inBuf);
						int cid = bbuf.getInt();
						byte code = bbuf.get();
						byte[] outBuf = null;
						switch (code) {
						case SESSIONREAD:
							int[] readArgs = unpackReadRequest(inBuf);
						case SESSIONWRITE:
							int sessNum = bbuf.getInt();
							int serverId = bbuf.getInt();
							int sessionVersionNum = bbuf.getInt();
							int discardTime = bbuf.getInt();
							// TODO: make a session state
							// TODO: do stuff with to update the session state
							bbuf = ByteBuffer.allocate(4);
							bbuf.putInt(cid);
							outBuf = bbuf.array();
						}
						DatagramPacket sendPkt = new DatagramPacket(outBuf, outBuf.length, returnAddr, returnPort);
						rpcSocket.send(sendPkt);
					}
				} catch (SocketException e) {
					// DatagramSocket could not be opened
					e.printStackTrace();
				} catch (IOException e) {
					// error receiving packet
					e.printStackTrace();
				}
			}
		});
		daemonThread.setDaemon(true);	// making this thread daemon
	    daemonThread.start();
	}
	
	// unpackage the received byte array (for the rpc handler thread)
	// byte array contains:
	// callId, operationCode, sessionNum, serverId, sessionVersionNum
	// returned int array contains:
	// sessionNum, serverId, sessionVersionNum
	private int[] unpackReadRequest(byte[] buffer) {
		return null;
	}
	
}
